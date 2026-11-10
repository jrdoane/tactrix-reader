(ns tactrix-reader.device.obd
  "Read-only OBD-II requests over ISO15765: mode 01 PID reads, vehicle
  information (mode 09), and trouble codes (services 03, 07, and 0A). Only
  services in `read-only-obd-services` are sent. Sends traffic on the vehicle
  bus."
  (:require [clojure.string :as str]
            [tactrix-reader.device.codec :as codec]
            [tactrix-reader.device.iso15765 :as iso15765]
            [tactrix-reader.device.pids :as pids]
            [tactrix-reader.device.ssm :as ssm]))

(def read-only-obd-services
  "OBD-II services that only read data. Service 04 clears DTCs and service 08
  controls on-board systems, so both are excluded."
  #{0x01 0x02 0x03 0x05 0x06 0x07 0x09 0x0A})

(defn obd-request
  "Sends one read-only OBD-II request over ISO15765 and returns the ECU reply.

  `request-hex` is the service byte and its parameters, such as `0100`
  (supported PIDs) or `0902` (VIN). Only read-only services are sent. The
  adapter handles multi-frame replies and returns them reassembled.

  Unlike the discovery helpers, this sends traffic on the vehicle bus."
  ([device-or-path request-hex]
   (obd-request device-or-path request-hex {}))
  ([device-or-path request-hex opts]
   (let [{:keys [tx-id rx-id] :as opts} (merge iso15765/iso15765-defaults opts)
         path (if (map? device-or-path) (:path device-or-path) device-or-path)
         request (codec/hex->bytes request-hex)
         service (first (:bytes request))
         base {:backend :mac-serial
               :path path
               :protocol :iso15765
               :tx-id tx-id
               :rx-id rx-id}]
     (cond
       (not (seq path))
       (assoc base :ok? false :error :missing-path)

       (not (:ok? request))
       (assoc base :ok? false :error :invalid-hex-request :request request)

       (not (read-only-obd-services service))
       (assoc base :ok? false :error :service-not-read-only :service service)

       :else
       (iso15765/iso15765-request device-or-path (:bytes request) base :obd-request-failed opts)))))

(defn read-pid
  "Reads and decodes one mode 01 PID, given as a number such as `0x0C` or a
  name such as `:engine-rpm`. Sends traffic on the vehicle bus."
  ([device-or-path pid]
   (read-pid device-or-path pid {}))
  ([device-or-path pid opts]
   (let [pid-number (if (keyword? pid) (pids/mode-01-pids-by-name pid) pid)]
     (if-not (and (int? pid-number) (<= 0 pid-number 0xFF))
       {:ok? false
        :error :unknown-pid
        :pid pid
        :known-pids (sort (keys pids/mode-01-pids-by-name))}
       (let [result (obd-request device-or-path (format "01%02X" pid-number) opts)]
         (if (:ok? result)
           (merge result (pids/decode-mode-01 (get-in result [:response :bytes])))
           result))))))

(defn read-supported-pids
  "Reads the supported-PID bitmaps starting at PID 00, following each bitmap's
  next-range bit, over one ISO15765 channel. Sends traffic on the vehicle bus."
  ([device-or-path]
   (read-supported-pids device-or-path {}))
  ([device-or-path opts]
   (iso15765/with-channel
    device-or-path opts
    (fn [channel]
      (loop [base 0x00
             supported []]
        (let [result (read-pid channel base opts)
              supported (into supported (:value result))
              next-base (+ base 0x20)]
          (cond
            (not (:ok? result))
            (assoc result :supported supported)

            (and (some #{next-base} (:value result))
                 (:pid-list? (get pids/mode-01-pids next-base)))
            (recur next-base supported)

            :else
            {:ok? true
             :supported supported})))))))

(defn read-all-pids
  "Reads every decodable mode 01 PID the vehicle reports as supported, over
  one ISO15765 channel. Sends traffic on the vehicle bus."
  ([device-or-path]
   (read-all-pids device-or-path {}))
  ([device-or-path opts]
   (iso15765/with-channel
    device-or-path opts
    (fn [channel]
      (let [supported (read-supported-pids channel opts)]
        (if-not (:ok? supported)
          supported
          (let [supported? (set (:supported supported))
                decodable (sort (for [[pid {:keys [pid-list?]}] pids/mode-01-pids
                                      :when (not pid-list?)]
                                  pid))
                ;; Read them all before the channel closes.
                results (vec (for [pid decodable
                                   :when (supported? pid)]
                               (assoc (read-pid channel pid opts)
                                      :pid pid
                                      :name (get-in pids/mode-01-pids [pid :name]))))
                errors (vec (for [result results
                                  :when (not (:ok? result))]
                              (select-keys result [:pid :name :error :nrc :step])))]
            {:ok? (empty? errors)
             :supported-hex (mapv codec/byte->hex (:supported supported))
             :values (vec (for [result results
                                :when (:ok? result)]
                            (select-keys result [:pid :name :value :unit])))
             :errors errors
             :not-supported (vec (remove supported? decodable))})))))))

(defn- ascii-text
  "Decodes ASCII bytes, dropping zero padding and surrounding spaces."
  [bytes]
  (str/trim (apply str (map char (remove zero? bytes)))))

(def ^:private in-use-monitors
  "The monitors in a spark ignition in-use performance reply (`0908`), in
  reply order. Each has a completion count and a conditions-met count."
  [:catalyst-bank-1 :catalyst-bank-2
   :oxygen-sensor-bank-1 :oxygen-sensor-bank-2
   :egr-system :secondary-air :evaporative-system
   :secondary-oxygen-sensor-bank-1 :secondary-oxygen-sensor-bank-2])

(defn- in-use-performance
  "Decodes the 16-bit counters of a `0908` reply. Older ECUs send 16
  counters and newer ones 20, so the monitor list is cut to fit."
  [counters]
  (let [[general-denominator ignition-cycles & monitor-counts] counters]
    {:general-denominator general-denominator
     :ignition-cycles ignition-cycles
     :monitors (into {}
                     (map (fn [monitor [completions conditions]]
                            [monitor {:completions completions :conditions conditions}])
                          in-use-monitors
                          (partition 2 monitor-counts)))}))

(defn decode-mode-09
  "Decodes a positive mode 09 reply, such as `[0x49 0x02 0x01 ...]`: the
  supported info types (`00`), VIN (`02`), calibration IDs (`04`),
  calibration verification numbers (`06`), in-use performance counters for a
  spark ignition engine (`08`), or ECU name (`0A`). On CAN, the byte after
  the info type is the number of data items, except for `00`."
  [data]
  (let [[service info-type & args] data
        [item-count & items] args
        items (vec items)]
    (cond
      (not= 0x49 service)
      {:ok? false
       :error :not-mode-09-reply}

      (nil? item-count)
      {:ok? false
       :info-type info-type
       :error :short-reply}

      :else
      (case info-type
        ;; A 4-byte bitmap, like mode 01 PID 00, with no item count.
        0x00 {:ok? true :name :supported-info-types :value ((pids/supported-pids 0x00) (vec args))}
        0x02 {:ok? true :name :vin :value (ascii-text items)}
        ;; Each calibration ID is 16 bytes, zero-padded.
        0x04 {:ok? true :name :calibration-ids :value (mapv ascii-text (take item-count (partition 16 items)))}
        ;; Each CVN is 4 bytes, one per calibration ID, shown as hex.
        0x06 {:ok? true
              :name :calibration-verification-numbers
              :value (mapv #(apply str (map codec/byte->hex %)) (take item-count (partition 4 items)))}
        ;; The item count is the number of 16-bit counters.
        0x08 {:ok? true
              :name :in-use-performance
              :value (in-use-performance (map codec/word (take item-count (partition 2 items))))}
        0x0A {:ok? true :name :ecu-name :value (ascii-text items)}
        {:ok? false
         :info-type info-type
         :error :unknown-info-type
         :raw items}))))

(defn- combine-reads
  "Combines `results`, `[item result]` pairs in request order, into a map of
  each item's `:value`, nil for an item that failed, with its error under
  `:errors`. `:ok?` is true when at least one item was read; when none was,
  `:error` is the first item's."
  [path results]
  (let [errors (into {}
                     (for [[item result] results
                           :when (not (:ok? result))]
                       [item (select-keys result [:error :nrc])]))]
    (merge {:ok? (boolean (some (comp :ok? second) results))
            :path path
            :errors errors}
           (when (= (count errors) (count results))
             {:error (get-in errors [(ffirst results) :error])})
           (into {} (for [[item result] results]
                      [item (when (:ok? result) (:value result))])))))

(def ^:private vehicle-info-requests
  [[:vin "0902"]
   [:calibration-ids "0904"]
   [:calibration-verification-numbers "0906"]
   [:ecu-name "090A"]])

(def ssm-init-timeout-ms
  "How long `read-vehicle-info` waits for the SSM `AA` reply. An ECU that
  speaks SSM answers within a few milliseconds, as it does mode 01, and one
  that does not, such as the project's 2015 Impreza, never answers, so the
  full `obd-request` timeout would only delay identification."
  250)

(defn read-vehicle-info
  "Reads what identifies the vehicle and its engine ECU: the VIN, calibration
  IDs, calibration verification numbers, and ECU name (mode 09), and the ECU
  ID (SSM `AA`, see `ssm-init`), over one ISO15765 channel.

  Each item the ECU does not answer is nil, with its error under `:errors`.
  `:ok?` is true when at least one item was read. Takes the same options as
  `obd-request`, plus `:ssm-init-timeout-ms` (by default
  `ssm-init-timeout-ms`) for the ECU ID.

  Sends traffic on the vehicle bus."
  ([device-or-path]
   (read-vehicle-info device-or-path {}))
  ([device-or-path opts]
   (let [path (if (map? device-or-path) (:path device-or-path) device-or-path)]
     (if-not (seq path)
       {:ok? false
        :error :missing-path}
       (iso15765/with-channel
        device-or-path opts
        (fn [channel]
          (let [results (vec (for [[item request-hex] vehicle-info-requests]
                               (let [result (obd-request channel request-hex opts)]
                                 [item (if (:ok? result)
                                         (decode-mode-09 (get-in result [:response :bytes]))
                                         result)])))
                ecu-id (ssm/ssm-init channel (assoc opts :timeout-ms (:ssm-init-timeout-ms
                                                                      opts
                                                                      ssm-init-timeout-ms)))]
            (combine-reads path (conj results [:ecu-id (assoc ecu-id :value (:ecu-id ecu-id))])))))))))

(def ^:private dtc-systems
  "A trouble code's first letter, from the top two bits of its first byte:
  powertrain, chassis, body, or network."
  ["P" "C" "B" "U"])

(defn- dtc
  "Decodes a 2-byte trouble code, such as `[0x01 0x33]`, into its SAE J2012
  form, such as `\"P0133\"`."
  [[a b]]
  (str (nth dtc-systems (bit-shift-right a 6))
       (bit-and (bit-shift-right a 4) 0x03)
       (format "%X%02X" (bit-and a 0x0F) b)))

(def ^:private dtc-replies
  "The trouble code lists, by the first byte of the reply to the service that
  reads them."
  {0x43 :stored-trouble-codes
   0x47 :pending-trouble-codes
   0x4A :permanent-trouble-codes})

(defn decode-dtc-reply
  "Decodes a positive reply to service 03 (stored), 07 (pending), or 0A
  (permanent trouble codes), such as `[0x43 0x01 0x01 0x33]`, into a list of
  codes such as `[\"P0133\"]`. On CAN, the byte after the service is the
  number of codes."
  [data]
  (let [[service code-count & code-bytes] data
        reply-name (get dtc-replies service)]
    (cond
      (nil? reply-name)
      {:ok? false
       :error :not-dtc-reply}

      (nil? code-count)
      {:ok? false
       :name reply-name
       :error :short-reply}

      :else
      {:ok? true
       :name reply-name
       :value (mapv dtc (take code-count (partition 2 code-bytes)))})))

(def ^:private trouble-code-requests
  [[:stored "03"]
   [:pending "07"]
   [:permanent "0A"]])

(defn read-trouble-codes
  "Reads the engine ECU's stored (service 03), pending (07), and permanent
  (0A) trouble codes, each a list such as `[\"P0133\"]`, over one ISO15765
  channel. It only reads them: clearing codes (service 04) is never sent.

  Each list the ECU does not answer is nil, with its error under `:errors`.
  `:ok?` is true when at least one list was read. Takes the same options as
  `obd-request`.

  Sends traffic on the vehicle bus."
  ([device-or-path]
   (read-trouble-codes device-or-path {}))
  ([device-or-path opts]
   (let [path (if (map? device-or-path) (:path device-or-path) device-or-path)]
     (if-not (seq path)
       {:ok? false
        :error :missing-path}
       (iso15765/with-channel
        device-or-path opts
        (fn [channel]
          (combine-reads path
                         (vec (for [[item request-hex] trouble-code-requests]
                                (let [result (obd-request channel request-hex opts)]
                                  [item (if (:ok? result)
                                          (decode-dtc-reply (get-in result [:response :bytes]))
                                          result)]))))))))))

