(ns tactrix-reader.device.recording
  "CAN recordings: `record` captures an adapter session to raw.bin, the source
  of truth, and `export-recording` derives the CSVs from it. See
  docs/recordings.md."
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

(defn record
  "Records raw CAN traffic to `out-dir` while polling mode 01 PIDs.

  Streams raw.bin (the exact bytes the adapter sent) to disk as it arrives,
  then writes meta.edn and the CSVs from `export-recording`. Records for
  `:duration-ms` (60 seconds by default; nil for no limit), or until the
  `:stop` promise is delivered. If the adapter fails partway through, the
  files are still written from what was captured, and the result has
  `:ok? false`. Polling sends one read-only request every `:interval-ms`,
  rotating through `:pids`; pass `:pids []` to only listen. With `:dids`, it
  alternates between a PID and a service 22 DID request (see
  `run-can-session!`)."
  ([device-or-path out-dir]
   (record device-or-path out-dir {}))
  ([device-or-path out-dir {:keys [pids dids interval-ms tx-id rx-id baud stop]
                            :or {pids can/default-poll-schedule
                                 dids []
                                 interval-ms can/default-poll-interval-ms
                                 tx-id 0x7E0
                                 rx-id 0x7E8
                                 baud 500000}
                            :as opts}]
   (let [duration-ms (get opts :duration-ms default-record-duration-ms)
         path (if (map? device-or-path) (:path device-or-path) device-or-path)
         dir (io/file out-dir)]
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
               raw-file (io/file dir "raw.bin")
               ;; Bytes from before the channel opens are held back, so a
               ;; failed connect leaves no files behind.
               pending (java.io.ByteArrayOutputStream.)
               raw (atom nil)
               started (java.time.Instant/now)
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
                                                              (if-let [^java.io.OutputStream out @raw]
                                                                (.write out chunk)
                                                                (.write pending chunk)))
                                                  :on-connected (fn [_]
                                                                  (.mkdirs dir)
                                                                  (let [out (java.io.FileOutputStream. raw-file)]
                                                                    (.writeTo pending out)
                                                                    (reset! raw out))
                                                                  (reset! connected-at (System/currentTimeMillis)))})
                           (finally
                             (openport/close-session session)))
                         (catch Throwable e
                           {:ok? false
                            :error :record-failed
                            :message (.getMessage e)})
                         (finally
                           (when-let [^java.io.OutputStream out @raw]
                             (.close out))))]
           (if-not @raw
             (assoc outcome :path path)
             (let [elapsed-ms (- (System/currentTimeMillis) @connected-at)
                   stopped-by (if (:ok? outcome) @stop-reason :error)
                   counts (assoc (export-recording dir rx-id) :raw-bytes (.length raw-file))
                   recording-meta (cond-> {:recorded-at (str started)
                                           :duration-ms elapsed-ms
                                           :stopped-by stopped-by
                                           :adapter {:firmware (:firmware outcome)}
                                           :bus {:channel can/can-channel
                                                 :protocol :can
                                                 :bitrate baud
                                                 :filter :pass-all
                                                 :loopback false}
                                           :polling {:tx-id tx-id
                                                     :rx-id rx-id
                                                     :interval-ms interval-ms
                                                     :request-count (:request-count outcome)
                                                     :pids (mapv codec/byte->hex pids)
                                                     :request-frame "02 01 <pid> 00 00 00 00 00"}
                                           :files {"raw.bin" "Exact bytes received from the adapter, including command replies."
                                                   "frames.csv" "Every received CAN frame, including copies of the polling requests. Regenerate with export-recording."
                                                   "pids.csv" "Decoded mode 01 replies. Regenerate with export-recording."}
                                           :counts counts}
                                    (seq dids) (update :polling assoc
                                                       :dids (mapv #(format "%04X" %) dids)
                                                       :did-request-frame "03 22 <did> 00 00 00 00")
                                    (:did-samples counts) (assoc-in [:files "dids.csv"] "Single-frame service 22 replies, undecoded. Regenerate with export-recording.")
                                    (not (:ok? outcome)) (assoc :error (:message outcome)))]
               (spit (io/file dir "meta.edn") (with-out-str (pprint/pprint recording-meta)))
               (merge {:ok? (:ok? outcome)
                       :path path
                       :out-dir (str dir)
                       :duration-ms elapsed-ms
                       :stopped-by stopped-by
                       :request-count (:request-count outcome)
                       :counts counts}
                      (select-keys outcome [:error :message])))))
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

