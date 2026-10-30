(ns tactrix-reader.device.source
  "Live and replay data sources. Both run on a background thread and call
  `:emit` with the same events, so consumers cannot tell them apart:
  `{:type :sample ...}`, `{:type :signal ...}`, `{:type :did ...}`, and
  `{:type :status ...}`."
  (:require [tactrix-reader.device.broadcast :as broadcast]
            [tactrix-reader.device.can :as can]
            [tactrix-reader.device.codec :as codec]
            [tactrix-reader.device.openport :as openport]
            [tactrix-reader.device.recording :as recording]))

(defn- message-decoder
  "Returns a function that takes chunks of adapter bytes and calls `on-message`
  with each complete received message, keeping partial ones for the next chunk."
  [on-message]
  (let [remainder (atom [])]
    (fn [^bytes chunk]
      (let [parsed (openport/parse-adapter-stream (into @remainder (codec/unsigned-bytes chunk)))]
        (reset! remainder (:remainder parsed))
        (doseq [event (:events parsed)
                :when (= :message (:type event))]
          (on-message event))))))

(defn- status-emitter [emit base]
  (fn [state & {:as details}]
    (emit (merge {:type :status :state state} base details))))

(defn start-live-source!
  "Polls the vehicle on a background thread and calls `emit` with each event:
  `{:type :sample ...}` for every decoded PID reply and broadcast signal in
  `:broadcast` (by default `broadcast-signals`), `{:type :signal ...}` for
  each signal in `:signals` (by default `inferred-signals`), `{:type :did ...}`
  for each single-frame service 22 reply (it polls no DIDs, so there are none
  unless something else on the bus asks), and `{:type :status ...}` when the
  source is running, stopped, or failed. `{}`
  turns either kind of broadcast decoding off. Returns a map whose :stop!
  function stops polling and waits for the channel to close.

  Sends read-only mode 01 requests on the vehicle bus."
  [device-or-path {:keys [emit schedule interval-ms tx-id rx-id baud broadcast signals]
                   :or {schedule can/default-poll-schedule
                        interval-ms can/default-poll-interval-ms
                        tx-id 0x7E0
                        rx-id 0x7E8
                        baud 500000
                        broadcast broadcast/broadcast-signals
                        signals broadcast/inferred-signals}}]
  (let [path (if (map? device-or-path) (:path device-or-path) device-or-path)
        running? (atom true)
        status (status-emitter emit {:source :live :path path})
        worker (future
                 (try
                   (if-not (seq path)
                     (status :failed :error :missing-path)
                     (let [session (openport/open-session path)]
                       (try
                         (let [result (can/run-can-session!
                                       session
                                       {:pids schedule
                                        :interval-ms interval-ms
                                        :tx-id tx-id
                                        :baud baud
                                        :continue? #(deref running?)
                                        :on-bytes (message-decoder
                                                   (fn [message]
                                                     (run! emit (can/message-events rx-id broadcast signals message))))
                                        :on-connected #(status :running :firmware %)})]
                           (if (:ok? result)
                             (status :stopped)
                             (status :failed :error (:error result) :reply (:reply result))))
                         (finally
                           (openport/close-session session)))))
                   (catch Throwable e
                     (status :failed :error :live-source-failed :message (.getMessage e)))))]
    {:stop! (fn []
              (reset! running? false)
              @worker
              nil)}))

(defn start-replay-source!
  "Replays a recording on a background thread, paced by its timestamps,
  calling `emit` with the same events as `start-live-source!`, including
  broadcast signals in `:broadcast`, inferred signals in `:signals`, and the
  DID replies of a recording made with `:dids`. Loops
  by default, shifting timestamps so they keep increasing. `:speed` scales
  playback. Returns a map whose :stop! function stops playback."
  [dir {:keys [emit loop? speed broadcast signals]
        :or {loop? true
             speed 1.0
             broadcast broadcast/broadcast-signals
             signals broadcast/inferred-signals}}]
  (let [running? (atom true)
        status (status-emitter emit {:source :replay :recording (str dir)})
        gap-us 25000
        worker (future
                 (try
                   (let [rx-id (recording/recording-rx-id dir)
                         ;; Frames are decoded as they are played, so a long
                         ;; recording's events are never all held at once.
                         messages (filterv #(and (= :message (:type %)) (not (:malformed? %)))
                                           (:events (openport/parse-adapter-stream (recording/recording-bytes dir))))
                         sample-count (transduce (mapcat #(can/message-samples rx-id broadcast %))
                                                 (completing (fn [n _] (inc n)))
                                                 0
                                                 messages)]
                     (if (zero? sample-count)
                       (status :failed :error :no-samples)
                       (let [t0 (:timestamp-us (first messages))
                             span-us (- (:timestamp-us (peek messages)) t0)]
                         (status :running :sample-count sample-count)
                         (loop [pass 0]
                           (let [offset (* pass (+ span-us gap-us))
                                 started (System/nanoTime)]
                             (doseq [message messages
                                     :while @running?
                                     :let [events (can/message-events rx-id broadcast signals message)]
                                     :when (seq events)]
                               (let [due-ms (/ (- (:timestamp-us message) t0) 1000.0 speed)
                                     wait-ms (- due-ms (/ (- (System/nanoTime) started) 1e6))]
                                 (when (pos? wait-ms)
                                   (Thread/sleep (long wait-ms)))
                                 (doseq [event events]
                                   (emit (update event :timestamp-us + offset)))))
                             (if (and loop? @running?)
                               (do
                                 (Thread/sleep (long (/ gap-us 1000.0 speed)))
                                 (recur (inc pass)))
                               (status :stopped)))))))
                   (catch Throwable e
                     (status :failed :error :replay-failed :message (.getMessage e)))))]
    {:stop! (fn []
              (reset! running? false)
              @worker
              nil)}))
