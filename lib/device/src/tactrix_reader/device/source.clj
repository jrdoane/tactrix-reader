(ns tactrix-reader.device.source
  "Live and replay data sources. Both run on a background thread and call
  `:emit` with the same events, so consumers cannot tell them apart:
  `{:type :sample ...}`, `{:type :signal ...}`, `{:type :did ...}`, and
  `{:type :status ...}`, and with `:frames? true` a `{:type :frame :id :data
  :timestamp-us}` for every CAN frame received, before the events decoded
  from it. The live source can also save what it receives as a recording,
  and reports that with `{:type :recording ...}`."
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

(defn- message-events
  "The source events for one received message: with `frames?`, the frame
  itself, then what `can/message-events` decodes from it."
  [rx-id broadcast signals frames? {:keys [id data timestamp-us] :as message}]
  (cond->> (can/message-events rx-id broadcast signals message)
    (and frames? id) (cons {:type :frame :id id :data data :timestamp-us timestamp-us})))

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
  decoding off. `:frames? true` also emits a `{:type :frame ...}` for every
  CAN frame received.

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
  [device-or-path {:keys [emit schedule dids interval-ms reply-timeout-ms tx-id rx-id baud
                          broadcast signals frames?]
                   :or {schedule can/default-poll-schedule
                        dids []
                        interval-ms can/default-poll-interval-ms
                        reply-timeout-ms can/default-reply-timeout-ms
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
                                      :reply-timeout-ms reply-timeout-ms
                                      :tx-id tx-id
                                      :rx-id rx-id
                                      :baud baud
                                      :continue? #(deref running?)
                                      :on-bytes (message-decoder
                                                 (fn [message]
                                                   (run! emit (message-events rx-id broadcast signals frames? message)))
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
                                                                                   :reply-timeout-ms reply-timeout-ms
                                                                                   :tx-id tx-id
                                                                                   :rx-id rx-id
                                                                                   :baud baud})
                                                          meta))
                             (catch Throwable e
                               {:ok? false :error :record-failed :message (.getMessage e)}))
                           {:ok? false :error :not-running}))
     :stop-recording! (fn []
                        ((:finish! writer) {:stopped-by :stop}))}))

(def ^:private replay-tick-ms
  "The longest the replay sleeps before looking at its controls again."
  20)

(def ^:private seek-lead-ms
  "How much of the recording before a seek's target is emitted at once, so
  the latest value of everything is as it was at the target. It covers the
  slowest PIDs, which a recording that also polled DIDs read about every
  6 seconds."
  10000)

(defn- first-index-at
  "The index of the first of `messages` at or after `target-us`, or their
  count when there is none."
  [messages target-us]
  (loop [low 0
         high (count messages)]
    (if (< low high)
      (let [middle (quot (+ low high) 2)]
        (if (< (:timestamp-us (nth messages middle)) target-us)
          (recur (inc middle) high)
          (recur low middle)))
      low)))

(defn- replay-command
  "Applies a control command to the replay's controls, or returns nil for one
  that is not valid. `duration-ms` is the recording's length."
  [controls duration-ms {:keys [action position-ms speed]}]
  (case action
    :pause (assoc controls :paused? true)
    :resume (assoc controls :paused? false)
    :seek (when (number? position-ms)
            (assoc controls :seek-ms (-> position-ms (max 0) (min duration-ms))))
    :speed (when (and (number? speed) (<= 0.1 speed 16))
             (assoc controls :speed (double speed)))
    nil))

(defn start-replay-source!
  "Replays a recording on a background thread, paced by its timestamps,
  calling `emit` with the same events as `start-live-source!`, including
  broadcast signals in `:broadcast`, inferred signals in `:signals`, and the
  DID replies of a recording made with `:dids`, and with `:frames? true`
  every CAN frame. Loops by default. `:speed` scales playback.

  Emitted timestamps always increase: each pass, and each seek, shifts them
  to follow the last one emitted, so a consumer sees one continuous stream.

  With `:position-ms`, it also emits `{:type :position :position-ms
  :duration-ms :paused? :speed}` that often, and whenever a control changes
  it, giving where in the recording playback is.

  Returns a map of functions:

  - `:stop!` stops playback.
  - `:control!` takes `{:action :pause}`, `{:action :resume}`, `{:action
    :seek :position-ms n}`, or `{:action :speed :speed x}` (0.1 to 16) and
    returns `{:ok? true}`, or `{:ok? false :error :invalid-command}`. A seek
    is clamped to the recording, and works while paused. It first emits the
    ten seconds of the recording before its target, all at once, so a
    consumer keeping the latest of each value has them as they were there,
    even while paused."
  [dir {:keys [emit loop? speed broadcast signals frames? position-ms]
        :or {loop? true
             speed 1.0
             broadcast broadcast/broadcast-signals
             signals broadcast/inferred-signals}}]
  (let [running? (atom true)
        controls (atom {:paused? false :speed (double speed) :seek-ms nil})
        duration (atom 0)
        status (status-emitter emit {:source :replay :recording (str dir)})
        gap-us 25000
        now-ms #(/ (System/nanoTime) 1e6)
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
                       (let [total (count messages)
                             t0 (:timestamp-us (first messages))
                             stamp #(:timestamp-us (nth messages %))
                             ;; A message's time in the recording, in ms.
                             at-ms #(/ (- (stamp %) t0) 1000.0)
                             duration-ms (at-ms (dec total))
                             report! (fn [i]
                                       (when position-ms
                                         (let [{:keys [paused? speed]} @controls]
                                           (emit {:type :position
                                                  :position-ms (long (at-ms (min i (dec total))))
                                                  :duration-ms (long duration-ms)
                                                  :paused? paused?
                                                  :speed speed}))))]
                         (reset! duration duration-ms)
                         (status :running :sample-count sample-count)
                         ;; `i` is the next message. Playback is anchored:
                         ;; the message at `anchor-ms` in the recording was
                         ;; due at `anchor-wall` on the clock, at `speed`.
                         ;; `offset` is added to emitted timestamps, and
                         ;; `last` is the last message emitted.
                         (loop [i 0
                                anchor-wall (now-ms)
                                anchor-ms 0.0
                                speed (:speed @controls)
                                offset 0
                                last nil
                                reported (- (now-ms) (or position-ms 0))
                                was-paused? false]
                           (let [{:keys [paused? seek-ms] wanted-speed :speed} @controls
                                 reported (if (and position-ms (>= (- (now-ms) reported) position-ms))
                                            (do (report! i) (now-ms))
                                            reported)
                                 ;; Timestamps continue from the last one
                                 ;; emitted when playback jumps to `to`.
                                 jump (fn [to]
                                        (if last
                                          (+ offset (- (stamp last) (stamp to)) gap-us)
                                          offset))]
                             (cond
                               (not @running?)
                               (status :stopped)

                               seek-ms
                               (let [to (min (dec total) (first-index-at messages (+ t0 (* 1000 seek-ms))))
                                     from (first-index-at messages (- (stamp to) (* 1000 seek-lead-ms)))
                                     offset (jump from)]
                                 (swap! controls assoc :seek-ms nil)
                                 ;; The lead-in, with a pause now and then so
                                 ;; a consumer's buffer keeps up.
                                 (doseq [j (range from to)]
                                   (when (zero? (mod (- j from) 100))
                                     (Thread/sleep 1))
                                   (doseq [event (message-events rx-id broadcast signals frames? (nth messages j))]
                                     (emit (update event :timestamp-us + offset))))
                                 (report! to)
                                 (recur to (now-ms) (at-ms to) speed offset
                                        (if (< from to) (dec to) last)
                                        (now-ms) was-paused?))

                               paused?
                               (do
                                 (when-not was-paused? (report! i))
                                 (Thread/sleep (long replay-tick-ms))
                                 (recur i anchor-wall anchor-ms speed offset last reported true))

                               ;; Resuming, or a new speed, starts timing
                               ;; again from the next message.
                               (or was-paused? (not= speed wanted-speed))
                               (do
                                 (report! i)
                                 (recur i (now-ms) (at-ms (min i (dec total))) wanted-speed offset last (now-ms) false))

                               (>= i total)
                               (if loop?
                                 (do
                                   (Thread/sleep (long (/ gap-us 1000.0 speed)))
                                   (recur 0 (now-ms) 0.0 speed (jump 0) last reported false))
                                 (status :stopped))

                               :else
                               (let [wait-ms (- (+ anchor-wall (/ (- (at-ms i) anchor-ms) speed)) (now-ms))]
                                 (if (> wait-ms replay-tick-ms)
                                   (do
                                     (Thread/sleep (long replay-tick-ms))
                                     (recur i anchor-wall anchor-ms speed offset last reported false))
                                   (let [message (nth messages i)]
                                     (when (pos? wait-ms)
                                       (Thread/sleep (long wait-ms)))
                                     (doseq [event (message-events rx-id broadcast signals frames? message)]
                                       (emit (update event :timestamp-us + offset)))
                                     (recur (inc i) anchor-wall anchor-ms speed offset i reported false))))))))))
                   (catch Throwable e
                     (status :failed :error :replay-failed :message (.getMessage e)))))]
    {:stop! (fn []
              (reset! running? false)
              @worker
              nil)
     :control! (fn [command]
                 (if-let [changed (replay-command @controls @duration command)]
                   (do
                     ;; Only the keys the command set, so a seek the worker
                     ;; has just cleared is not put back.
                     (swap! controls merge (select-keys changed (case (:action command)
                                                                  (:pause :resume) [:paused?]
                                                                  :seek [:seek-ms]
                                                                  :speed [:speed])))
                     {:ok? true})
                   {:ok? false :error :invalid-command}))}))
