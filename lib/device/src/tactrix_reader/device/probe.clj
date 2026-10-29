(ns tactrix-reader.device.probe
  "Named protocol-discovery probes: one explicit request per probe, recorded as
  a transcript. Never sweeps or generates request bytes."
  (:require [clojure.string :as str]
            [tactrix-reader.device.codec :as codec]
            [tactrix-reader.device.openport :as openport]
            [tactrix-reader.device.serial :as serial]
            [tactrix-reader.device.usb :as usb]))

(defn- probe-request-summary [request]
  {:byte-count (count (:bytes request))
   :bytes (:bytes request)
   :hex (mapv codec/byte->hex (:bytes request))})

(defn probe-result
  "Converts a low-level exchange result into a stable discovery transcript."
  [probe-name exchange-result]
  (merge
   {:ok? (:ok? exchange-result)
    :backend :mac-serial
    :probe/name probe-name
    :path (:path exchange-result)
    :operation/known? (openport/known-operation? probe-name)
    :vehicle-bus-traffic? false}
   (select-keys exchange-result
                [:request :drained :response :timeout-ms :read-idle-ms
                 :max-response-bytes :elapsed-ms :timed-out? :idle?
                 :truncated? :error :message])))

(defn run-probe
  "Runs one named adapter-local probe and returns a transcript-shaped result.

  This validates the name, request, and path before delegating to
  `serial-exchange`; it never sweeps or generates request bytes."
  ([probe-name request-hex]
   (run-probe (some :path (usb/scan-devices)) probe-name request-hex))
  ([device-or-path probe-name request-hex]
   (let [name (some-> probe-name str/trim not-empty)
         path (if (map? device-or-path) (:path device-or-path) device-or-path)
         request (codec/hex->bytes request-hex)]
     (cond
       (not name)
       {:ok? false
        :backend :mac-serial
        :error :missing-probe-name
        :vehicle-bus-traffic? false}

       (not (:ok? request))
       {:ok? false
        :backend :mac-serial
        :probe/name name
        :error :invalid-hex-request
        :request request
        :vehicle-bus-traffic? false}

       (not (seq path))
       {:ok? false
        :backend :mac-serial
        :probe/name name
        :path path
        :error :missing-path
        :request (probe-request-summary request)
        :vehicle-bus-traffic? false}

       :else
       (probe-result name (serial/serial-exchange path request-hex))))))
