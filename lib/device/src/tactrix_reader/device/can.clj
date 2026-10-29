(ns tactrix-reader.device.can
  "A raw CAN session (channel 5) with a pass-all filter that polls mode 01 PIDs
  with single-frame requests, and decoding of the messages it receives into
  PID samples and broadcast signals. Recording and the live source both use
  it. Sends read-only mode 01 requests on the vehicle bus."
  (:require [tactrix-reader.device.broadcast :as broadcast]
            [tactrix-reader.device.codec :as codec]
            [tactrix-reader.device.openport :as openport]
            [tactrix-reader.device.pids :as pids]
            [tactrix-reader.device.serial :as serial]))

(def can-channel
  "The adapter uses J2534 protocol IDs as channel IDs; 5 is raw CAN."
  5)

(def default-poll-interval-ms
  "Gap between polling requests. The ECU answers in about 1 ms."
  15)

(def ^:private fast-pids
  "RPM, speed, load, throttle, pedal, MAF, manifold pressure, timing, plus
  relative throttle, pedal position D, commanded throttle, and the manual
  transmission's neutral input, which pin down broadcast signals (see
  docs/can-broadcast.md), and the measured lambda from the wideband sensor."
  [0x0C 0x0D 0x04 0x11 0x5A 0x10 0x0B 0x0E 0x45 0x49 0x4C 0x65 0x34])

(def ^:private slow-pids
  "Temperatures, voltage, fuel level, trims, commanded lambda, fuel system
  status, the rear oxygen sensor, barometric pressure, and check-engine
  status."
  [0x05 0x5C 0x0F 0x3C 0x42 0x2F 0x06 0x07 0x44 0x03 0x15 0x46 0x33 0x01])

(def default-poll-schedule
  "Request order for recording and the live source: all fast PIDs, then one
  slow PID, and repeat. At the default interval, each fast PID is sampled
  about every 210 ms and each slow PID about every 2.9 seconds."
  (vec (mapcat #(conj fast-pids %) slow-pids)))

(defn- collect! [input ^java.io.ByteArrayOutputStream sink ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (while (< (System/currentTimeMillis) deadline)
      (if-let [chunk (serial/read-chunk input)]
        (.write sink ^bytes chunk)
        (Thread/sleep 2)))))

(defn- reply-lines [^java.io.ByteArrayOutputStream sink]
  (keep :text (:events (openport/parse-adapter-stream (codec/unsigned-bytes (.toByteArray sink))))))

(defn reply-sample
  "Decodes a received message carrying a single-frame mode 01 reply from
  `rx-id` into a sample with the adapter's timestamp. Returns nil for any
  other message."
  [rx-id {:keys [type id data timestamp-us]}]
  (when (and (= :message type) (= rx-id id) (seq data))
    (let [decoded (pids/decode-mode-01 (vec (take (first data) (rest data))))]
      (when (:ok? decoded)
        (-> decoded
            (dissoc :ok?)
            (assoc :timestamp-us timestamp-us))))))

(defn message-samples
  "Returns the samples in one received message: a mode 01 reply from `rx-id`,
  or broadcast signals from `signals`."
  [rx-id signals message]
  (if-let [sample (reply-sample rx-id message)]
    [sample]
    (broadcast/broadcast-samples signals message)))

(defn message-events
  "Returns the source events for one received message: `:sample` events as
  `message-samples` gives, then `:signal` events for `inferred`."
  [rx-id broadcast inferred message]
  (concat (map #(assoc % :type :sample) (message-samples rx-id broadcast message))
          (map #(assoc % :type :signal) (broadcast/signal-samples inferred message))))

(defn run-can-session!
  "Opens the raw CAN channel with a pass-all filter and polls `pids`
  round-robin, one request every `interval-ms`, while `continue?` returns true.
  Every byte the adapter sends from the connect command on is passed to
  `on-bytes`. `on-connected` is called with the firmware version once the
  channel is open. Always closes the channel it opened."
  [{:keys [input output]} {:keys [pids interval-ms tx-id baud continue? on-bytes on-connected]
                           :or {on-connected (fn [_])}}]
  (let [channel can-channel
        setup (java.io.ByteArrayOutputStream.)
        connect (java.io.ByteArrayOutputStream.)]
    (collect! input (java.io.ByteArrayOutputStream.) 200)
    (openport/write-command! output (str "atc" channel) [])
    (openport/write-command! output "ati" [])
    (collect! input setup 300)
    (openport/write-command! output (str "ato" channel " 0 " baud) [])
    (collect! input connect 300)
    (on-bytes (.toByteArray connect))
    (let [firmware (some #(second (re-matches #"ari main code version : (.*)" %))
                         (reply-lines setup))
          connect-reply (first (reply-lines connect))]
      (if (not= "aro" connect-reply)
        {:ok? false
         :step :connect
         :reply connect-reply
         :error :connect-failed}
        (let [request-count (atom 0)]
          (try
            (openport/write-command! output (str "atf" channel " 1 0 4") (repeat 8 0))
            (on-connected firmware)
            (loop [i 0
                   next-request (System/currentTimeMillis)]
              (when (continue?)
                (let [now (System/currentTimeMillis)]
                  (if (and (seq pids) (>= now next-request))
                    (do
                      (openport/write-command! output (str "att" channel " 12 0")
                                               (concat (codec/u32->bytes tx-id)
                                                       [0x02 0x01 (nth pids (mod i (count pids))) 0 0 0 0 0]))
                      (swap! request-count inc)
                      ;; Never schedule in the past, so a stall does not
                      ;; cause a burst of catch-up requests.
                      (recur (inc i) (max (+ next-request interval-ms) now)))
                    (do
                      (if-let [chunk (serial/read-chunk input)]
                        (on-bytes chunk)
                        (Thread/sleep 1))
                      (recur i next-request))))))
            {:ok? true
             :firmware firmware
             :request-count @request-count}
            (finally
              (let [tail (java.io.ByteArrayOutputStream.)]
                (collect! input tail 300)
                (openport/write-command! output (str "atc" channel) [])
                (collect! input tail 300)
                (on-bytes (.toByteArray tail))))))))))
