(ns tactrix-reader.device.recording
  "CAN recordings: `record` captures an adapter session to raw.bin, the source
  of truth, `start-recording!` saves bytes from a session someone else runs,
  `export-recording` derives the CSVs from raw.bin, and `list-recordings`
  describes the recordings in a folder. See docs/recordings.md."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [tactrix-reader.device.broadcast :as broadcast]
            [tactrix-reader.device.can :as can]
            [tactrix-reader.device.codec :as codec]
            [tactrix-reader.device.openport :as openport]
            [tactrix-reader.device.uds :as uds]))

(def default-record-duration-ms 60000)

(defn- csv-field [value]
  (let [s (str value)]
    (if (re-find #"[\",\n]" s)
      (str "\"" (str/replace s "\"" "\"\"") "\"")
      s)))

(defn- hex-string [bytes]
  (str/join " " (map codec/byte->hex bytes)))

(defn recording-meta
  "Reads a recording's meta.edn, or returns nil when it has none."
  [dir]
  (let [meta-file (io/file dir "meta.edn")]
    (when (.exists meta-file)
      (edn/read-string (slurp meta-file)))))

(defn recording-rx-id
  "The ID the recording's polled replies came from, from its meta.edn."
  [dir]
  (get-in (recording-meta dir) [:polling :rx-id] 0x7E8))

(defn recording-bytes
  "Reads a recording's raw.bin as unsigned byte values."
  [dir]
  (codec/unsigned-bytes (java.nio.file.Files/readAllBytes (.toPath (io/file dir "raw.bin")))))

(defn recording-samples
  "Returns the samples in a recording's raw.bin, in order: decoded PID
  replies, plus broadcast signals from `signals` (by default
  `broadcast-signals`)."
  ([dir]
   (recording-samples dir broadcast/broadcast-signals))
  ([dir signals]
   (let [rx-id (recording-rx-id dir)]
     (into []
           (mapcat #(can/message-samples rx-id signals %))
           (:events (openport/parse-adapter-stream (recording-bytes dir)))))))

(defn export-recording
  "Writes frames.csv and pids.csv in `dir` from its raw.bin.

  frames.csv lists every received CAN frame. pids.csv lists decoded mode 01
  replies from `rx-id`, which defaults to the one in meta.edn. If there are
  any single-frame service 22 replies, dids.csv lists them, with their data
  undecoded. Times are seconds since the first frame, plus the adapter's
  microsecond timestamp."
  ([dir]
   (export-recording dir (recording-rx-id dir)))
  ([dir rx-id]
   (let [events (:events (openport/parse-adapter-stream (recording-bytes dir)))
         messages (filterv #(and (= :message (:type %)) (not (:malformed? %))) events)
         t0 (:timestamp-us (first messages))
         seconds (fn [timestamp-us] (format "%.6f" (/ (- timestamp-us t0) 1e6)))
         pid-rows (keep #(can/reply-sample rx-id %) messages)
         did-rows (keep #(can/did-reply rx-id %) messages)]
     (with-open [writer (io/writer (io/file dir "frames.csv"))]
       (.write writer "t_s,timestamp_us,channel,status,id,dlc,data\n")
       (doseq [{:keys [timestamp-us channel status id data]} messages]
         (.write writer (str (str/join "," [(seconds timestamp-us) timestamp-us channel
                                            (codec/byte->hex status) (format "%03X" id)
                                            (count data) (hex-string data)])
                             "\n"))))
     (with-open [writer (io/writer (io/file dir "pids.csv"))]
       (.write writer "t_s,timestamp_us,pid,name,value,unit\n")
       (doseq [{:keys [timestamp-us pid name value unit]} pid-rows]
         (.write writer (str (str/join "," [(seconds timestamp-us) timestamp-us (codec/byte->hex pid)
                                            (clojure.core/name name) (csv-field value)
                                            (if unit (clojure.core/name unit) "")])
                             "\n"))))
     (when (seq did-rows)
       (with-open [writer (io/writer (io/file dir "dids.csv"))]
         (.write writer "t_s,timestamp_us,did,data\n")
         (doseq [{:keys [timestamp-us did-hex data]} did-rows]
           (.write writer (str (str/join "," [(seconds timestamp-us) timestamp-us did-hex
                                              (hex-string data)])
                               "\n")))))
     (cond-> {:frames (count messages)
              :pid-samples (count pid-rows)
              :reply-lines (frequencies (keep #(some-> (:text %) (str/split #" ") first) events))}
       (seq did-rows) (assoc :did-samples (count did-rows))))))


(defn- write-meta! [dir meta]
  (spit (io/file dir "meta.edn")
        (binding [*print-namespace-maps* false]
          (with-out-str (pprint/pprint meta)))))

(defn vehicle-meta
  "The items of `obd/read-vehicle-info` worth saving in meta.edn: those that
  were read, without the errors."
  [vehicle-info]
  (not-empty (into {}
                   (filter (comp some? val))
                   (dissoc vehicle-info :ok? :error :path :errors))))

(defn session-meta
  "The meta.edn description of a polling session: the adapter firmware, the
  bus settings, and what was polled."
  [{:keys [firmware pids dids interval-ms tx-id rx-id baud]}]
  {:adapter {:firmware firmware}
   :bus {:channel can/can-channel
         :protocol :can
         :bitrate baud
         :filter :pass-all
         :loopback false}
   :polling (cond-> {:tx-id tx-id
                     :rx-id rx-id
                     :interval-ms interval-ms
                     :pids (mapv codec/byte->hex pids)
                     :request-frame "02 01 <pid> 00 00 00 00 00"}
              (seq dids) (assoc :dids (mapv #(format "%04X" %) dids)
                                :did-request-frame "03 22 <did> 00 00 00 00"))})

(defn start-recording!
  "Starts a recording in `dir`: creates it, opens raw.bin, and writes a
  meta.edn from `meta` (see `session-meta`; it may also have `:vehicle` and
  `:note`) marked `:in-progress? true`, so a recording cut off by a crash
  is still described. Returns a map of functions:

  - `:write!` appends a chunk of adapter bytes to raw.bin.
  - `:finish!` takes `{:stopped-by ... :error message :request-count n}`,
    closes raw.bin, writes the CSVs with `export-recording`, rewrites
    meta.edn with the duration, why it stopped, and counts, and returns
    `{:out-dir :duration-ms :stopped-by :counts}`. Later calls return the
    same result."
  [dir meta]
  (let [dir (io/file dir)
        _ (.mkdirs dir)
        raw-file (io/file dir "raw.bin")
        out (java.io.FileOutputStream. raw-file)
        started-ms (System/currentTimeMillis)
        meta (assoc meta :recorded-at (str (java.time.Instant/now)))
        finished (atom nil)
        lock (Object.)]
    (write-meta! dir (assoc meta :in-progress? true))
    {:dir (str dir)
     :write! (fn [^bytes chunk]
               (locking lock
                 (when-not @finished
                   (.write out chunk))))
     :finish! (fn [{:keys [stopped-by error request-count]}]
                (locking lock
                  (or @finished
                      (do
                        (.close out)
                        (let [duration-ms (- (System/currentTimeMillis) started-ms)
                              counts (assoc (export-recording dir (get-in meta [:polling :rx-id] 0x7E8))
                                            :raw-bytes (.length raw-file))]
                          (write-meta! dir (cond-> (assoc meta
                                                          :duration-ms duration-ms
                                                          :stopped-by stopped-by
                                                          :files (cond-> {"raw.bin" "Exact bytes received from the adapter."
                                                                          "frames.csv" "Every received CAN frame, including copies of the polling requests. Regenerate with export-recording."
                                                                          "pids.csv" "Decoded mode 01 replies. Regenerate with export-recording."}
                                                                   (:did-samples counts) (assoc "dids.csv" "Single-frame service 22 replies, undecoded. Regenerate with export-recording."))
                                                          :counts counts)
                                             request-count (assoc-in [:polling :request-count] request-count)
                                             error (assoc :error error)))
                          (reset! finished {:out-dir (str dir)
                                            :duration-ms duration-ms
                                            :stopped-by stopped-by
                                            :counts counts}))))))}))

(defn record
  "Records raw CAN traffic to `out-dir` while polling mode 01 PIDs.

  Streams raw.bin (the exact bytes the adapter sent) to disk as it arrives,
  then writes meta.edn and the CSVs from `export-recording` (see
  `start-recording!`). `:vehicle`, from `obd/read-vehicle-info`, and `:note`
  are saved in meta.edn. Records for `:duration-ms` (60 seconds by default;
  nil for no limit), or until the `:stop` promise is delivered. If the
  adapter fails partway through, the files are still written from what was
  captured, and the result has `:ok? false`. Polling sends one read-only
  request every `:interval-ms`, rotating through `:pids`; pass `:pids []` to
  only listen. With `:dids`, it alternates between a PID and a service 22 DID
  request (see `run-can-session!`)."
  ([device-or-path out-dir]
   (record device-or-path out-dir {}))
  ([device-or-path out-dir {:keys [pids dids interval-ms tx-id rx-id baud stop vehicle note]
                            :or {pids can/default-poll-schedule
                                 dids []
                                 interval-ms can/default-poll-interval-ms
                                 tx-id 0x7E0
                                 rx-id 0x7E8
                                 baud 500000}
                            :as opts}]
   (let [duration-ms (get opts :duration-ms default-record-duration-ms)
         path (if (map? device-or-path) (:path device-or-path) device-or-path)
         dir (some-> out-dir io/file)]
     (cond
       (not (seq path))
       {:ok? false
        :error :missing-path}

       (not dir)
       {:ok? false
        :path path
        :error :missing-out-dir}

       (not (and (sequential? dids) (every? uds/did? dids)))
       {:ok? false
        :path path
        :error :invalid-dids
        :dids dids}

       :else
       (try
         (let [session (openport/open-session path)
               ;; Bytes from before the channel opens are held back, so a
               ;; failed connect leaves no files behind.
               pending (java.io.ByteArrayOutputStream.)
               recorder (atom nil)
               connected-at (atom nil)
               stop-reason (atom nil)
               outcome (try
                         (try
                           (can/run-can-session! session
                                                 {:pids pids
                                                  :dids dids
                                                  :interval-ms interval-ms
                                                  :tx-id tx-id
                                                  :baud baud
                                                  :continue? #(cond
                                                                (and stop (realized? stop))
                                                                (do (reset! stop-reason :stop) false)

                                                                (and duration-ms
                                                                     (>= (System/currentTimeMillis)
                                                                         (+ @connected-at duration-ms)))
                                                                (do (reset! stop-reason :duration) false)

                                                                :else true)
                                                  :on-bytes (fn [^bytes chunk]
                                                              (if-let [{:keys [write!]} @recorder]
                                                                (write! chunk)
                                                                (.write pending chunk)))
                                                  :on-connected (fn [firmware]
                                                                  (let [r (start-recording!
                                                                           dir
                                                                           (cond-> (session-meta {:firmware firmware
                                                                                                  :pids pids
                                                                                                  :dids dids
                                                                                                  :interval-ms interval-ms
                                                                                                  :tx-id tx-id
                                                                                                  :rx-id rx-id
                                                                                                  :baud baud})
                                                                             vehicle (assoc :vehicle vehicle)
                                                                             note (assoc :note note)))]
                                                                    ((:write! r) (.toByteArray pending))
                                                                    (reset! recorder r))
                                                                  (reset! connected-at (System/currentTimeMillis)))})
                           (finally
                             (openport/close-session session)))
                         (catch Throwable e
                           {:ok? false
                            :error :record-failed
                            :message (.getMessage e)}))]
           (if-let [{:keys [finish!]} @recorder]
             (let [stopped-by (if (:ok? outcome) @stop-reason :error)
                   {:keys [duration-ms counts]} (finish! {:stopped-by stopped-by
                                                          :error (when-not (:ok? outcome) (:message outcome))
                                                          :request-count (:request-count outcome)})]
               (merge {:ok? (:ok? outcome)
                       :path path
                       :out-dir (str dir)
                       :duration-ms duration-ms
                       :stopped-by stopped-by
                       :request-count (:request-count outcome)
                       :counts counts}
                      (select-keys outcome [:error :message])))
             (assoc outcome :path path)))
         (catch java.io.FileNotFoundException e
           {:ok? false
            :path path
            :error :not-found-or-permission-denied
            :message (.getMessage e)})
         (catch Throwable e
           {:ok? false
            :path path
            :error :record-failed
            :message (.getMessage e)}))))))

(defn list-recordings
  "Describes each recording in `root` (each folder with a raw.bin), newest
  first: its `:name`, `:dir`, `:recorded-at`, `:duration-ms`, `:stopped-by`,
  `:vehicle`, `:note`, whether it polled DIDs, `:counts`, and `:raw-bytes`.
  One still being written has `:in-progress? true`, and one with no meta.edn
  has `:incomplete? true` and the raw.bin modification time."
  [root]
  (->> (.listFiles (io/file root))
       (filter #(.isFile (io/file % "raw.bin")))
       (map (fn [^java.io.File dir]
              (let [raw-file (io/file dir "raw.bin")
                    {:keys [recorded-at duration-ms stopped-by vehicle note in-progress? counts error polling]
                     :as meta} (recording-meta dir)]
                (cond-> {:name (.getName dir)
                         :dir (str dir)
                         :recorded-at (or recorded-at
                                          (str (java.time.Instant/ofEpochMilli (.lastModified raw-file))))
                         :raw-bytes (.length raw-file)
                         :dids? (boolean (seq (:dids polling)))}
                  (nil? meta) (assoc :incomplete? true)
                  duration-ms (assoc :duration-ms duration-ms)
                  stopped-by (assoc :stopped-by stopped-by)
                  vehicle (assoc :vehicle vehicle)
                  (or note (:state vehicle)) (assoc :note (or note (:state vehicle)))
                  in-progress? (assoc :in-progress? true)
                  counts (assoc :counts counts)
                  error (assoc :error error)))))
       (sort-by :recorded-at #(compare %2 %1))
       vec))
