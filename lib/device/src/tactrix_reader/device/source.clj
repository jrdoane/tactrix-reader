(ns tactrix-reader.device.source
  "Live and replay data sources. Both run on a background thread and call
  `:emit` with the same events, so consumers cannot tell them apart:
  `{:type :sample ...}`, `{:type :signal ...}`, `{:type :did ...}`, and
  `{:type :status ...}`. The live source can also save what it receives as a
  recording, and reports that with `{:type :recording ...}`."
  (:require [tactrix-reader.device.broadcast :as broadcast]
            [tactrix-reader.device.can :as can]
            [tactrix-reader.device.codec :as codec]
            [tactrix-reader.device.openport :as openport]
            [tactrix-reader.device.recording :as recording]))

(defn- message-decoder
  "Returns a function that takes chunks of adapter bytes and calls `on-message`
  with each complete received message, keeping partial ones for the next chunk.
  Before decoding, it calls `on-chunk` with the chunk and the partial message
  kept from the last one, so the two together start at an event boundary."
  ([on-message]
   (message-decoder on-message (fn [_ _])))
  ([on-message on-chunk]
   (let [remainder (atom [])]
     (fn [^bytes chunk]
       (on-chunk chunk @remainder)
       (let [parsed (openport/parse-adapter-stream (into @remainder (codec/unsigned-bytes chunk)))]
         (reset! remainder (:remainder parsed))
         (doseq [event (:events parsed)
                 :when (= :message (:type event))]
           (on-message event)))))))

(defn- status-emitter [emit base]
  (fn [state & {:as details}]
    (emit (merge {:type :status :state state} base details))))

(defn- recording-writer
  "Keeps the live source's recording, if any. `write!` takes each chunk and
  the partial message the decoder kept; the first write includes that partial
  message, so raw.bin starts at an event boundary and replays cleanly."
  [emit]
  (let [recording (atom nil)]
    {:write! (fn [^bytes chunk remainder]
               (when-let [{:keys [recorder aligned?] :as current} @recording]
                 (if aligned?
                   ((:write! recorder) chunk)
                   (when (compare-and-set! recording current (assoc current :aligned? true))
                     ((:write! recorder) (byte-array (map unchecked-byte
                                                          (into (vec remainder) (codec/unsigned-bytes chunk)))))))))
     :start! (fn [dir meta]
               (if @recording
                 {:ok? false :error :already-recording}
                 (let [recorder (recording/start-recording! dir meta)
                       name (.getName (java.io.File. ^String (:dir recorder)))]
                   (reset! recording {:recorder recorder :aligned? false})
                   (emit {:type :recording :state :recording :name name :dir (:dir recorder)
                          :started-at (str (java.time.Instant/now))})
                   {:ok? true :name name :dir (:dir recorder)})))
     :finish! (fn [details]
                (when-let [{:keys [recorder]} (first (reset-vals! recording nil))]
                  (let [result ((:finish! recorder) details)]
                    (emit (merge {:type :recording
                                  :state :saved
                                  :name (.getName (java.io.File. ^String (:out-dir result)))
                                  :dir (:out-dir result)}
                                 (select-keys result [:duration-ms :stopped-by])))
                    result)))}))

(defn start-live-source!
  "Polls the vehicle on a background thread and calls `emit` with each event:
  `{:type :sample ...}` for every decoded PID reply and broadcast signal in
  `:broadcast` (by default `broadcast-signals`), `{:type :signal ...}` for
  each signal in `:signals` (by default `inferred-signals`), `{:type :did ...}`
  for each single-frame service 22 reply (to the DIDs in `:dids`, which it
  alternates with PID requests), and `{:type :status ...}` when the source
  is running, stopped, or failed. `{}` turns either kind of broadcast
  decoding off.

  Returns a map of functions:

  - `:stop!` stops polling and waits for the channel to close.
  - `:start-recording!` takes a directory and extra meta.edn entries (such as
    `:vehicle` and `:note`) and saves the bytes received from then on as a
    recording, without interrupting polling. It returns `{:ok? true :name
    :dir}`, or `{:ok? false :error :not-running|:already-recording}`.
  - `:stop-recording!` finishes the recording and returns its result, or nil.

  Recording emits `{:type :recording :state :recording ...}` when it starts
  and `{:type :recording :state :saved ...}` when it is finished, which
  also happens when the source stops.

  Sends read-only mode 01 and service 22 requests on the vehicle bus."
  [device-or-path {:keys [emit schedule dids interval-ms tx-id rx-id baud broadcast signals]
                   :or {schedule can/default-poll-schedule
                        dids []
                        interval-ms can/default-poll-interval-ms
                        tx-id 0x7E0
                        rx-id 0x7E8
                        baud 500000
                        broadcast broadcast/broadcast-signals
                        signals broadcast/inferred-signals}}]
  (let [path (if (map? device-or-path) (:path device-or-path) device-or-path)
        running? (atom true)
        firmware (atom nil)
        writer (recording-writer emit)
        status (status-emitter emit {:source :live :path path})
        worker (future
                 (try
                   (if-not (seq path)
                     (status :failed :error :missing-path)
                     (let [session (openport/open-session path)
                           result (try
                                    (can/run-can-session!
                                     session
                                     {:pids schedule
                                      :dids dids
                                      :interval-ms interval-ms
                                      :tx-id tx-id
                                      :baud baud
                                      :continue? #(deref running?)
                                      :on-bytes (message-decoder
                                                 (fn [message]
                                                   (run! emit (can/message-events rx-id broadcast signals message)))
                                                 (:write! writer))
                                      :on-connected (fn [version]
                                                      (reset! firmware version)
                                                      (status :running :firmware version))})
                                    (catch Throwable e
                                      {:ok? false :error :live-source-failed :message (.getMessage e)})
                                    (finally
                                      (reset! firmware nil)
                                      (openport/close-session session)))]
                       ((:finish! writer) (if (:ok? result)
                                            {:stopped-by :source-stopped}
                                            {:stopped-by :error
                                             :error (or (:message result) (some-> (:error result) name))}))
                       (cond
                         (:ok? result) (status :stopped)
                         (:message result) (status :failed :error (:error result) :message (:message result))
                         :else (status :failed :error (:error result) :reply (:reply result)))))
                   (catch Throwable e
                     ((:finish! writer) {:stopped-by :error :error (.getMessage e)})
                     (status :failed :error :live-source-failed :message (.getMessage e)))))]
    {:stop! (fn []
              (reset! running? false)
              @worker
              nil)
     :start-recording! (fn [dir meta]
                         (if-let [version @firmware]
                           (try
                             ((:start! writer) dir (merge (recording/session-meta {:firmware version
                                                                                   :pids schedule
                                                                                   :dids dids
                                                                                   :interval-ms interval-ms
                                                                                   :tx-id tx-id
                                                                                   :rx-id rx-id
                                                                                   :baud baud})
                                                          meta))
                             (catch Throwable e
                               {:ok? false :error :record-failed :message (.getMessage e)}))
                           {:ok? false :error :not-running}))
     :stop-recording! (fn []
                        ((:finish! writer) {:stopped-by :stop}))}))

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
