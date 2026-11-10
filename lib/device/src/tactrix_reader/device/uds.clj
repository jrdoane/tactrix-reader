(ns tactrix-reader.device.uds
  "UDS (ISO 14229) ReadDataByIdentifier (service `22`) requests over ISO15765.
  It is the only UDS service sent; `2E` (WriteDataByIdentifier) and the other
  services that write, reset, or unlock the ECU are never built. See
  docs/subaru-ssm.md."
  (:require [tactrix-reader.device.codec :as codec]
            [tactrix-reader.device.iso15765 :as iso15765]
            [tactrix-reader.device.pids :refer [round-to]]))

(def read-data-by-identifier-service
  "The UDS service that reads the value behind a 2-byte data identifier (DID).
  It is the only UDS service sent."
  0x22)

(def negative-response-codes
  "Names for the UDS negative response codes a read is likely to get."
  {0x10 :general-reject
   0x11 :service-not-supported
   0x12 :sub-function-not-supported
   0x13 :incorrect-message-length
   0x14 :response-too-long
   0x22 :conditions-not-correct
   0x31 :request-out-of-range
   0x33 :security-access-denied
   0x7F :service-not-supported-in-active-session})

(defn did?
  "True for a data identifier: an integer from 0 to `0xFFFF`."
  [did]
  (and (int? did) (<= 0 did 0xFFFF)))

(def scanned-dids
  "The DIDs in `1000`-`10FF` that the project's 2015 Impreza returned data for
  (see docs/subaru-ssm.md), less `101E`, `1061`, and `1063`, whose replies
  need more than one CAN frame. What each holds is not yet known."
  [0x1000 0x1001 0x1002 0x1003 0x1004 0x1005 0x1006 0x1007 0x1008 0x1009 0x100A
   0x101D 0x1020 0x1022 0x1024 0x103C 0x103D 0x1040 0x1060 0x1080 0x1088 0x1089
   0x10A0 0x10A1 0x10A3 0x10A5 0x10A6 0x10AE 0x10B1 0x10B2 0x10B4 0x10B5 0x10B6
   0x10B7 0x10B8 0x10B9 0x10BA 0x10BC 0x10BE 0x10C0 0x10C1 0x10DE 0x10DF 0x10E0
   0x10E3 0x10E5 0x10EC 0x10ED 0x10EE 0x10EF 0x10F0 0x10F1])

(def did-signals
  "Single-byte DIDs the project's 2015 Impreza returns that track a PID, by
  DID, with the PID in `:matches`. `10A3` matches air per revolution, MAF
  over rpm, rather than one PID. The scales were fitted from the
  2026-10-29-did-drive and 2026-10-30-did-drive recordings, not documented;
  see docs/subaru-ssm.md. `10B2` behaves like a charging voltage target
  rather than a measurement, so it matches ECU voltage only once the
  alternator catches up."
  {0x10A1 {:name :mass-air-flow :matches 0x10 :unit :grams-per-second
           ;; Probably the MAF sensor's own output, which rises with about
           ;; the fourth root of the flow.
           :decode #(round-to 2 (Math/pow (+ (* 0.0177 %) 0.186) 4))}
   0x10A3 {:name :air-per-revolution :unit :grams-per-revolution
           :decode #(round-to 3 (* 0.019 %))}
   0x10A6 {:name :accelerator-pedal :matches 0x5A :unit :percent
           :decode #(round-to 1 (* 0.178 %))}
   0x10B2 {:name :charging-voltage-target :matches 0x42 :unit :volts
           :decode #(round-to 2 (+ (* 0.05 %) 10.73))}
   0x10BA {:name :wideband-current :matches 0x34 :unit :milliamps
           :decode #(round-to 3 (/ (- % 128) 8.0))}
   0x10BE {:name :lambda :matches 0x34 :unit :lambda
           :decode #(round-to 3 (/ % 128.0))}
   0x10DF {:name :throttle-position :matches 0x11 :unit :percent
           :decode #(round-to 1 (- (* 0.8 %) 40))}})

(defn did-sample
  "Adds what `did-signals` knows to a DID reply such as `can/did-reply`
  returns: its `:name` and the PID it `:matches`, and, for a one-byte reply
  with a fitted scale, its `:value` and `:unit`. Other replies are returned
  unchanged."
  [{:keys [did data] :as reply}]
  (let [{:keys [decode unit] :as known} (get did-signals did)]
    (cond-> (merge reply (select-keys known [:name :matches]))
      (and decode (= 1 (count data))) (assoc :value (decode (first data)) :unit unit))))

(defn read-did
  "Reads one data identifier with UDS ReadDataByIdentifier (service `22`), sent
  over ISO15765 like `obd-request`.

  `did` is an integer up to `0xFFFF`, such as `0x1155`. The reply is `62`, the
  DID echoed back, and its data, returned undecoded in `:data`. A `7F` reply
  returns `:negative-response` with its `:nrc` and, when known, `:nrc-name`.
  Takes the same options as `obd-request`.

  Sends traffic on the vehicle bus."
  ([device-or-path did]
   (read-did device-or-path did {}))
  ([device-or-path did opts]
   (let [{:keys [tx-id rx-id] :as opts} (merge iso15765/iso15765-defaults opts)
         path (if (map? device-or-path) (:path device-or-path) device-or-path)
         base {:backend :mac-serial
               :path path
               :protocol :iso15765
               :tx-id tx-id
               :rx-id rx-id
               :did did}]
     (cond
       (not (seq path))
       (assoc base :ok? false :error :missing-path)

       (not (did? did))
       (assoc base :ok? false :error :invalid-did)

       :else
       (let [did-bytes (subvec (codec/u32->bytes did) 2)
             base (assoc base :did-hex (format "%04X" did))
             result (iso15765/iso15765-request device-or-path
                                               (into [read-data-by-identifier-service] did-bytes)
                                               base :uds-read-failed opts)
             reply (get-in result [:response :bytes])
             data (some-> reply (subvec (min 3 (count reply))))]
         (cond
           (= :negative-response (:error result))
           (cond-> result
             (negative-response-codes (:nrc result))
             (assoc :nrc-name (negative-response-codes (:nrc result))))

           (not (:ok? result))
           result

           (not= did-bytes (subvec reply 1 (min 3 (count reply))))
           (assoc result :ok? false :error :unexpected-response)

           :else
           (assoc result :data {:bytes data
                                :hex (mapv codec/byte->hex data)})))))))

(def default-scan-stop-after
  "How many requests in a row may go unanswered before `scan-dids` stops."
  3)

(defn- scan-finding
  "Trims a `read-did` result to what a scan reports. The raw response is kept
  only when there is no decoded data."
  [result]
  (cond-> (select-keys result [:did :did-hex :ok? :error :step :nrc :nrc-name :data :response])
    (:data result) (dissoc :response)))

(defn scan-dids
  "Reads every DID from `start` to `end`, inclusive, with `read-did`, one
  request each, over one ISO15765 channel. Only reads are sent, but a wide range is thousands of
  requests, so both ends must be given.

  Returns the DIDs whose reply was anything other than `request-out-of-range`
  in `:found`, with their data or error, and counts the out-of-range ones.
  Stops early, with `:stopped-early? true` and `:error :no-reply`, once
  `:stop-after` requests in a row get no reply from the ECU, such as when the
  ignition is turned off or the adapter is unplugged. `:on-result`, if given,
  is called with each `read-did` result. Takes the same other options as
  `obd-request`.

  Sends traffic on the vehicle bus."
  ([device-or-path start end]
   (scan-dids device-or-path start end {}))
  ([device-or-path start end {:keys [stop-after on-result]
                              :or {stop-after default-scan-stop-after}
                              :as opts}]
   (let [path (if (map? device-or-path) (:path device-or-path) device-or-path)
         base {:backend :mac-serial
               :path path
               :start start
               :end end}
         opts (dissoc opts :stop-after :on-result)]
     (cond
       (not (seq path))
       (assoc base :ok? false :error :missing-path)

       (not (and (did? start) (did? end) (<= start end)))
       (assoc base :ok? false :error :invalid-did-range)

       :else
       (iso15765/with-channel
        device-or-path opts
        (fn [channel]
          (loop [did start
                 found []
                 out-of-range 0
                 unanswered 0]
            (if (or (> did end) (>= unanswered stop-after))
              (cond-> (assoc base
                             :ok? (> did end)
                             :start-hex (format "%04X" start)
                             :end-hex (format "%04X" end)
                             :scanned (- did start)
                             :stopped-early? (<= did end)
                             :out-of-range out-of-range
                             :found found)
                (<= did end) (assoc :error :no-reply))
              (let [result (read-did channel did opts)]
                (when on-result
                  (on-result result))
                (if (= :request-out-of-range (:nrc-name result))
                  (recur (inc did) found (inc out-of-range) 0)
                  (recur (inc did)
                         (conj found (scan-finding result))
                         out-of-range
                         (if (:response result) 0 (inc unanswered)))))))))))))

(defn bitmap-dids
  "Returns the DIDs a 4-byte supported-DID bitmap read from `base` marks as
  supported: bit 7 of the first byte is `base + 1`, and the last bit is
  `base + 32`, the next bitmap. This is the layout of OBD mode 01 PIDs `00`,
  `20`, and so on."
  [base data]
  (vec (for [i (range 32)
             :when (bit-test (nth data (quot i 8)) (- 7 (mod i 8)))]
         (+ base 1 i))))

(defn supported-dids
  "Lists the DIDs the ECU supports by following its supported-DID bitmaps,
  starting at `start` (`0x1000` by default). Each bitmap is a DID whose
  4-byte value marks the next 32 DIDs, and the last of those is the next
  bitmap, so the walk reads one DID for every 32 and stops at the first
  bitmap whose last bit is clear. The reads share one ISO15765 channel.

  Returns the bitmaps read in `:bitmaps` and every other supported DID in
  `:dids`. If a read fails or a bitmap is not 4 bytes, it stops there with
  `:ok? false`, keeping what it found. Takes the same options as
  `obd-request`.

  Sends traffic on the vehicle bus."
  ([device-or-path]
   (supported-dids device-or-path 0x1000 {}))
  ([device-or-path start]
   (supported-dids device-or-path start {}))
  ([device-or-path start opts]
   (let [path (if (map? device-or-path) (:path device-or-path) device-or-path)
         base {:backend :mac-serial
               :path path
               :start start}
         done (fn [bitmaps dids]
                (assoc base
                       :start-hex (format "%04X" start)
                       :bitmaps bitmaps
                       :dids dids
                       :dids-hex (mapv #(format "%04X" %) dids)))]
     (cond
       (not (seq path))
       (assoc base :ok? false :error :missing-path)

       (not (did? start))
       (assoc base :ok? false :error :invalid-did)

       :else
       (iso15765/with-channel
        device-or-path opts
        (fn [channel]
          (loop [bitmap start
                 bitmaps []
                 dids []]
            (let [result (read-did channel bitmap opts)
                  data (get-in result [:data :bytes])]
              (cond
                (not (:ok? result))
                (merge (done bitmaps dids)
                       {:ok? false :failed-at (:did-hex result)}
                       (select-keys result [:error :nrc :nrc-name]))

                (not= 4 (count data))
                (assoc (done bitmaps dids)
                       :ok? false
                       :error :unexpected-bitmap
                       :failed-at (:did-hex result)
                       :data (:data result))

                :else
                (let [marked (bitmap-dids bitmap data)
                      next-bitmap (+ bitmap 0x20)
                      bitmaps (conj bitmaps {:did bitmap
                                             :did-hex (:did-hex result)
                                             :data (:data result)})]
                  (if (and (some #{next-bitmap} marked) (did? next-bitmap))
                    (recur next-bitmap bitmaps (into dids (remove #{next-bitmap}) marked))
                    (assoc (done bitmaps (into dids (remove #{next-bitmap}) marked))
                           :ok? true))))))))))))
