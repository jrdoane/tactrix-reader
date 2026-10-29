(ns tactrix-reader.device.ssm
  "Subaru Select Monitor (SSM) requests over ISO15765: ECU identification
  (`AA`) and memory reads (`A8`). These are the only SSM commands sent; `B0`
  and `B8` write ECU memory and are never built. See docs/subaru-ssm.md."
  (:require [tactrix-reader.device.codec :as codec]
            [tactrix-reader.device.iso15765 :as iso15765]))

(def ssm-init-command
  "The Subaru Select Monitor (SSM) command that identifies the ECU. With
  `ssm-read-addresses-command`, it is one of only two SSM commands sent: `B0`
  and `B8` write ECU memory and are never built."
  0xAA)

(def ssm-read-addresses-command
  "The SSM command that reads one byte at each of a list of 3-byte ECU memory
  addresses."
  0xA8)

(defn ssm-init
  "Identifies the ECU with the SSM `AA` command, sent over ISO15765 like
  `obd-request`.

  The reply is `EA`, 3 bytes of unknown meaning, the 5-byte ECU ID, and
  bitfields of the standard SSM parameters the ECU supports. Returns the ECU ID
  as hex in `:ecu-id`, such as `\"7334384107\"`; logger definitions use it to
  find ECU-specific addresses. Takes the same options as `obd-request`.

  Sends traffic on the vehicle bus."
  ([device-or-path]
   (ssm-init device-or-path {}))
  ([device-or-path opts]
   (let [{:keys [tx-id rx-id] :as opts} (merge iso15765/iso15765-defaults opts)
         path (if (map? device-or-path) (:path device-or-path) device-or-path)
         base {:backend :mac-serial
               :path path
               :protocol :iso15765
               :tx-id tx-id
               :rx-id rx-id}]
     (if-not (seq path)
       (assoc base :ok? false :error :missing-path)
       (let [result (iso15765/iso15765-request path [ssm-init-command] base :ssm-init-failed opts)
             data (get-in result [:response :bytes])]
         (cond
           (not (:ok? result))
           result

           (< (count data) 9)
           (assoc result :ok? false :error :unexpected-response)

           :else
           (let [capabilities (subvec data 9)]
             (assoc result
                    :ecu-id (apply str (map codec/byte->hex (subvec data 4 9)))
                    :capabilities {:bytes capabilities
                                   :hex (mapv codec/byte->hex capabilities)}))))))))

(defn- ssm-address? [address]
  (and (int? address) (<= 0 address 0xFFFFFF)))

(defn ssm-read
  "Reads one byte at each ECU memory address with the Subaru Select Monitor
  (SSM) `A8` command, sent over ISO15765 like `obd-request`.

  `addresses` is a sequence of integers up to `0xFFFFFF`, such as `[0x000022]`.
  The reply has one byte per address, in order, so a 4-byte value is read as
  four consecutive addresses. Takes the same options as `obd-request`.

  Sends traffic on the vehicle bus."
  ([device-or-path addresses]
   (ssm-read device-or-path addresses {}))
  ([device-or-path addresses opts]
   (let [{:keys [tx-id rx-id] :as opts} (merge iso15765/iso15765-defaults opts)
         path (if (map? device-or-path) (:path device-or-path) device-or-path)
         base {:backend :mac-serial
               :path path
               :protocol :iso15765
               :tx-id tx-id
               :rx-id rx-id}]
     (cond
       (not (seq path))
       (assoc base :ok? false :error :missing-path)

       (not (and (sequential? addresses)
                 (seq addresses)
                 (every? ssm-address? addresses)))
       (assoc base :ok? false :error :invalid-ssm-addresses :addresses addresses)

       :else
       ;; The 00 after A8 selects a single read; SSM over CAN has no fast poll.
       (let [request (into [ssm-read-addresses-command 0x00]
                           (mapcat #(subvec (codec/u32->bytes %) 1))
                           addresses)
             result (iso15765/iso15765-request path request base :ssm-read-failed opts)
             data (get-in result [:response :bytes])]
         (cond
           (not (:ok? result))
           result

           (not= (inc (count addresses)) (count data))
           (assoc result :ok? false :error :unexpected-response)

           :else
           (assoc result :values (mapv (fn [address value]
                                         {:address address
                                          :address-hex (format "%06X" address)
                                          :value value})
                                       addresses
                                       (rest data)))))))))
