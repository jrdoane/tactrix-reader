(ns tactrix-reader.device.openport
  "The adapter's OpenPort command layer: the confirmed J2534 operations, parsing
  the serial stream into reply lines and received messages, and sessions that
  send `at...` commands and wait for typed events. See
  docs/openport-protocol.md."
  (:require [clojure.string :as str]
            [tactrix-reader.device.codec :as codec]
            [tactrix-reader.device.serial :as serial]))

(def openport-operations
  "J2534 operations whose OpenPort command has been confirmed by probing, with
  the command prefix. See docs/openport-protocol.md."
  {:pass-thru-read-version "ati"
   :pass-thru-connect "ato"
   :pass-thru-disconnect "atc"
   :pass-thru-write-msgs "att"
   :pass-thru-start-msg-filter "atf"
   :pass-thru-stop-msg-filter "atk"
   :get-config "atg"
   :set-config "ats"
   :read-vbatt "atr"})

(defn known-operation?
  "Returns true when `operation-name`, a keyword or string such as
  `:pass-thru-connect`, has a confirmed OpenPort command."
  [operation-name]
  (contains? openport-operations (some-> operation-name name keyword)))

(defn unsupported-operation
  "Returns a standard unsupported result for a J2534-shaped operation, which
  this backend does not implement yet. The error says whether its OpenPort
  command is known."
  [operation-name]
  (let [command (get openport-operations (some-> operation-name name keyword))]
    (cond-> {:ok? false
             :backend :mac-serial
             :operation/name operation-name
             :operation/known? (some? command)
             :state :unsupported
             :error (if command :not-implemented :needs-openport-command)
             :message (if command
                        "The OpenPort command for this operation is known, but the J2534 API is not implemented."
                        "The OpenPort command for this operation has not been discovered.")}
      command (assoc :operation/command command))))

(defn- decode-message [channel body]
  (if (< (count body) 9)
    {:type :message
     :channel channel
     :malformed? true
     :bytes body}
    {:type :message
     :channel channel
     :status (body 0)
     :timestamp-us (codec/u32 (subvec body 1 5))
     :id (codec/u32 (subvec body 5 9))
     :data (subvec body 9)}))

(defn parse-adapter-stream
  "Splits bytes read from the adapter into reply lines and received messages.

  Replies are ASCII lines such as `aro`. Received messages are binary:
  `ar<channel digit><length><body>`, where the body is a status byte, a
  big-endian microsecond timestamp, a big-endian CAN ID, and data. Returns the
  parsed events plus any trailing bytes that do not form a complete event yet."
  [bytes]
  (loop [bytes (vec bytes)
         events []]
    (let [message? (and (>= (count bytes) 3)
                        (= [97 114] (subvec bytes 0 2))
                        (<= 48 (bytes 2) 57))
          newline-at (when-not message?
                       (first (keep-indexed (fn [i b] (when (= 10 b) i)) bytes)))]
      (cond
        (empty? bytes)
        {:events events
         :remainder []}

        message?
        (let [end (when (>= (count bytes) 4) (+ 4 (bytes 3)))]
          (if (and end (<= end (count bytes)))
            (recur (subvec bytes end)
                   (conj events (decode-message (- (bytes 2) 48) (subvec bytes 4 end))))
            {:events events
             :remainder bytes}))

        newline-at
        (let [text (str/trim (apply str (map char (subvec bytes 0 newline-at))))]
          (recur (subvec bytes (inc newline-at))
                 (cond-> events
                   (seq text) (conj {:type :line :text text}))))

        :else
        {:events events
         :remainder bytes}))))

(defn- stream-session [input output]
  {:input (java.io.BufferedInputStream. input)
   :output (java.io.BufferedOutputStream. output)
   :remainder (atom [])
   :events (atom [])})

(defn open-session
  "Opens the serial device at `path` as a session for the functions below."
  [path]
  (stream-session (java.io.FileInputStream. path)
                  (java.io.FileOutputStream. path)))

(defn close-session [{:keys [^java.io.Closeable input ^java.io.Closeable output]}]
  (.close output)
  (.close input))

(defn read-events!
  "Reads whatever the adapter has sent and queues the parsed events. Returns
  true when any bytes were read."
  [{:keys [input remainder events]}]
  (let [bytes (serial/read-available-bytes input 4096)]
    (when (seq bytes)
      (let [parsed (parse-adapter-stream (into @remainder bytes))]
        (reset! remainder (:remainder parsed))
        (swap! events into (:events parsed))))
    (boolean (seq bytes))))

(defn take-event!
  "Removes and returns the first queued event matching `pred`, reading from the
  adapter until one arrives. Returns nil after `timeout-ms`."
  [session pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [queued @(:events session)
            index (first (keep-indexed (fn [i event] (when (pred event) i)) queued))]
        (cond
          index
          (do
            (reset! (:events session) (into (subvec queued 0 index)
                                            (subvec queued (inc index))))
            (queued index))

          (>= (System/currentTimeMillis) deadline)
          nil

          :else
          (do
            (when-not (read-events! session)
              (Thread/sleep 1))
            (recur)))))))

(defn write-command!
  "Writes one ASCII command followed by an optional raw payload."
  [^java.io.OutputStream output command payload]
  (.write output (codec/byte-values->byte-array (concat (map int (str command "\r\n")) payload)))
  (.flush output))

(defn send-command!
  "Writes one ASCII command followed by an optional raw payload and returns the
  adapter's reply line, or nil on timeout."
  [{:keys [output] :as session} command payload timeout-ms]
  (write-command! output command payload)
  (:text (take-event! session #(= :line (:type %)) timeout-ms)))

(defn reply-error
  "Describes an unexpected or missing reply to `command`, sent at `step`."
  [step command reply]
  (merge {:ok? false
          :step step
          :command command
          :reply reply}
         (if-let [[_ code] (when reply (re-matches #"are (\d+)( .*)?" reply))]
           {:error :adapter-error
            :adapter/error-code (Integer/parseInt code)}
           {:error (if reply :unexpected-reply :timeout)})))
