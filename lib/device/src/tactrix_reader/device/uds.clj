(ns tactrix-reader.device.uds
  "UDS (ISO 14229) ReadDataByIdentifier (service `22`) requests over ISO15765.
  It is the only UDS service sent; `2E` (WriteDataByIdentifier) and the other
  services that write, reset, or unlock the ECU are never built. See
  docs/subaru-ssm.md."
  (:require [tactrix-reader.device.codec :as codec]
            [tactrix-reader.device.iso15765 :as iso15765]))

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

(defn- did? [did]
  (and (int? did) (<= 0 did 0xFFFF)))

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
             result (iso15765/iso15765-request path
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
