(ns tactrix-reader.device.can
  "A raw CAN session (channel 5) with a pass-all filter that polls mode 01 PIDs
  with single-frame requests, and decoding of the messages it receives into
  PID samples and broadcast signals. It can also poll UDS data identifiers
  (service 22) between the PIDs. Recording and the live source both use it.
  Sends read-only mode 01 and service 22 requests on the vehicle bus."
  (:require [tactrix-reader.device.broadcast :as broadcast]
            [tactrix-reader.device.codec :as codec]
            [tactrix-reader.device.openport :as openport]
            [tactrix-reader.device.pids :as pids]
            [tactrix-reader.device.serial :as serial]
            [tactrix-reader.device.uds :as uds]))

(def can-channel
  "The adapter uses J2534 protocol IDs as channel IDs; 5 is raw CAN."
  5)

(def default-poll-interval-ms
  "The shortest time from one polling request to the next. Each request also
  waits for the reply to the one before, so this caps the request rate (at
  200 a second) rather than setting it."
  5)

(def default-reply-timeout-ms
  "How long a polling request waits for its reply before the next is sent.
  The project's 2015 Impreza answers in about 1 ms and always within 5 ms
  (see docs/recordings.md); 50 ms is ISO 15765-4's P2 limit for a server's
  reply."
  50)

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
  slow PID, and repeat. At the default interval, with replies in time, each
  fast PID is sampled about every 70 ms and each slow PID about every
  second; polling DIDs as well doubles both."
  (vec (mapcat #(conj fast-pids %) slow-pids)))

(defn- collect! [input ^java.io.ByteArrayOutputStream sink ms]
  (let [deadline (+ (serial/monotonic-ms) ms)]
    (while (< (serial/monotonic-ms) deadline)
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

(defn did-reply
  "Decodes a received message carrying a single-frame service 22 reply from
  `rx-id` into `{:did :did-hex :data :timestamp-us}`, with the data bytes
  undecoded. Returns nil for any other message, including the first frame of
  a multi-frame reply."
  [rx-id {:keys [type id data timestamp-us]}]
  (when (and (= :message type) (= rx-id id) (seq data) (<= 3 (first data) 7))
    (let [[service hi lo & reply] (take (first data) (rest data))]
      (when (= (+ uds/read-data-by-identifier-service 0x40) service)
        (let [did (+ (* 256 hi) lo)]
          {:did did
           :did-hex (format "%04X" did)
           :data (vec reply)
           :timestamp-us timestamp-us})))))

(defn- poll-request
  "The request payload for the `i`th poll: mode 01 PIDs and service 22 DIDs
  in turn when there are both, each rotating through its own list."
  [pids dids i]
  (let [pid-request #(vector 0x01 (nth pids (mod % (count pids))))
        did-request #(into [uds/read-data-by-identifier-service]
                           (subvec (codec/u32->bytes (nth dids (mod % (count dids)))) 2))]
    (cond
      (empty? dids) (pid-request i)
      (empty? pids) (did-request i)
      (even? i) (pid-request (quot i 2))
      :else (did-request (quot i 2)))))

(defn- answers?
  "True when `message`, a received message from `rx-id`, answers `request`:
  a positive reply to its service and parameters, as a single frame or the
  first frame of a longer reply, or a negative reply to its service other
  than response pending (NRC 78), after which the ECU replies again."
  [rx-id request {:keys [type id data]}]
  (when (and (= :message type) (= rx-id id) (seq data))
    (let [payload (case (bit-shift-right (first data) 4)
                    0 (rest data)
                    1 (drop 2 data)
                    nil)
          [service & params] request]
      (or (= (cons (+ service 0x40) params) (take (count request) payload))
          (and (= [0x7F service] (take 2 payload))
               (not= 0x78 (nth payload 2 nil)))))))

(defn message-samples
  "Returns the samples in one received message: a mode 01 reply from `rx-id`,
  or broadcast signals from `signals`."
  [rx-id signals message]
  (if-let [sample (reply-sample rx-id message)]
    [sample]
    (broadcast/broadcast-samples signals message)))

(defn message-events
  "Returns the source events for one received message: `:sample` events as
  `message-samples` gives, then `:signal` events for `inferred`, then a
  `:did` event for a single-frame service 22 reply from `rx-id`, decoded as
  far as `uds/did-sample` can."
  [rx-id broadcast inferred message]
  (concat (map #(assoc % :type :sample) (message-samples rx-id broadcast message))
          (map #(assoc % :type :signal) (broadcast/signal-samples inferred message))
          (some-> (did-reply rx-id message) uds/did-sample (assoc :type :did) vector)))

(defn run-can-session!
  "Opens the raw CAN channel with a pass-all filter and polls `pids`
  round-robin while `continue?` returns true. Each request is sent once the
  reply to the one before has come from `rx-id`, or `reply-timeout-ms` has
  passed without one, and at least `interval-ms` after the one before.
  With `dids`, it alternates between a PID and a service 22 DID request, each
  rotating through its own list. DIDs whose reply needs more than one frame
  get only the first frame, because the channel sends no flow control.
  Every byte the adapter sends from the connect command on is passed to
  `on-bytes`. `on-connected` is called with the firmware version once the
  channel is open. Always closes the channel it opened. Returns the number
  of requests sent, and of those that got no reply in time, in
  `:request-count` and `:unanswered-count`."
  [{:keys [input output]} {:keys [pids dids interval-ms reply-timeout-ms tx-id rx-id baud
                                  continue? on-bytes on-connected]
                           :or {reply-timeout-ms default-reply-timeout-ms
                                rx-id 0x7E8
                                on-connected (fn [_])}}]
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
        (let [request-count (atom 0)
              unanswered-count (atom 0)]
          (try
            (openport/write-command! output (str "atf" channel " 1 0 4") (repeat 8 0))
            (on-connected firmware)
            ;; `awaiting` is the request still waiting for its reply, and
            ;; `reply-due` when the next may be sent without one. The times
            ;; are on the monotonic clock: paced on the system's, polling
            ;; once stopped for the 2.3 s that clock was set back by.
            (loop [i 0
                   next-request (serial/monotonic-ms)
                   awaiting nil
                   reply-due 0
                   remainder []]
              (when (continue?)
                (let [now (serial/monotonic-ms)]
                  (if (and (or (seq pids) (seq dids))
                           (>= now next-request)
                           (or (nil? awaiting) (>= now reply-due)))
                    (let [request (poll-request pids dids i)]
                      (when awaiting
                        (swap! unanswered-count inc))
                      (openport/write-command! output (str "att" channel " 12 0")
                                               (concat (codec/u32->bytes tx-id)
                                                       (take 8 (concat [(count request)] request
                                                                       (repeat 0)))))
                      (swap! request-count inc)
                      ;; Never schedule in the past, so a stall does not
                      ;; cause a burst of catch-up requests.
                      (recur (inc i) (max (+ next-request interval-ms) now)
                             request (+ now reply-timeout-ms) remainder))
                    (if-let [chunk (serial/read-chunk input)]
                      (let [_ (on-bytes chunk)
                            parsed (openport/parse-adapter-stream
                                    (into remainder (codec/unsigned-bytes chunk)))
                            answered? (and awaiting
                                           (some #(answers? rx-id awaiting %) (:events parsed)))]
                        (recur i next-request (when-not answered? awaiting) reply-due
                               (:remainder parsed)))
                      (do
                        (Thread/sleep 1)
                        (recur i next-request awaiting reply-due remainder)))))))
            {:ok? true
             :firmware firmware
             :request-count @request-count
             :unanswered-count @unanswered-count}
            (finally
              (let [tail (java.io.ByteArrayOutputStream.)]
                (collect! input tail 300)
                (openport/write-command! output (str "atc" channel) [])
                (collect! input tail 300)
                (on-bytes (.toByteArray tail))))))))))
