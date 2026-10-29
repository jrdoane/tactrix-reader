(ns tactrix-reader.device.serial
  "Raw reads and writes on the adapter's serial device. `serial-exchange` and
  `observe-device` are protocol-discovery helpers: they write only the bytes
  they are given, or nothing."
  (:require [tactrix-reader.device.codec :as codec]))

(def default-observe-ms 2000)
(def default-exchange-timeout-ms 1000)
(def default-exchange-idle-ms 100)
(def default-exchange-max-response-bytes 256)

(defn read-available-bytes
  "Reads up to `max-count` bytes that are already available, without waiting,
  as unsigned values."
  [^java.io.BufferedInputStream input max-count]
  (loop [bytes []]
    (let [remaining (- max-count (count bytes))
          available (min (.available input) remaining)]
      (if (pos? available)
        (let [buffer (byte-array (min available 256))
              read-count (.read input buffer)]
          (if (pos? read-count)
            (recur (into bytes
                         (map #(bit-and % 0xFF))
                         (take read-count buffer)))
            bytes))
        bytes))))

(defn read-chunk
  "Returns the bytes the adapter has sent so far, or nil when there are none."
  ^bytes [^java.io.InputStream input]
  (let [available (.available input)]
    (when (pos? available)
      (let [buffer (byte-array available)
            read-count (.read input buffer)]
        (when (pos? read-count)
          (java.util.Arrays/copyOf buffer read-count))))))

(defn- read-response [input {:keys [timeout-ms read-idle-ms max-response-bytes]}]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop [bytes []
           last-byte-at nil]
      (let [now (System/currentTimeMillis)
            idle? (and last-byte-at
                       (>= (- now last-byte-at) read-idle-ms))
            timed-out? (>= now deadline)
            full? (>= (count bytes) max-response-bytes)]
        (if (or idle? timed-out? full?)
          {:bytes bytes
           :timed-out? (and timed-out? (empty? bytes))
           :idle? (boolean idle?)
           :truncated? full?}
          (let [chunk (read-available-bytes input (- max-response-bytes (count bytes)))]
            (if (seq chunk)
              (recur (into bytes chunk) (System/currentTimeMillis))
              (do
                (Thread/sleep 10)
                (recur bytes last-byte-at)))))))))

(defn serial-exchange
  "Writes one explicit hex request to the adapter and captures response bytes.

  This is an opt-in protocol-discovery helper for use with an adapter that is
  not attached to a vehicle. It sends only the request supplied by the caller;
  it does not sweep or guess commands."
  ([device-or-path request-hex]
   (serial-exchange device-or-path request-hex {}))
  ([device-or-path request-hex {:keys [timeout-ms read-idle-ms max-response-bytes drain?]
                                :or {timeout-ms default-exchange-timeout-ms
                                     read-idle-ms default-exchange-idle-ms
                                     max-response-bytes default-exchange-max-response-bytes
                                     drain? true}}]
   (let [path (if (map? device-or-path) (:path device-or-path) device-or-path)
         request (codec/hex->bytes request-hex)]
     (cond
       (not (seq path))
       {:ok? false
        :error :missing-path}

       (not (:ok? request))
       {:ok? false
        :path path
        :error :invalid-hex-request
        :request request}

       :else
       (try
         (with-open [input (java.io.BufferedInputStream.
                            (java.io.FileInputStream. path))
                     output (java.io.BufferedOutputStream.
                             (java.io.FileOutputStream. path))]
           (let [drained (if drain?
                           (read-available-bytes input max-response-bytes)
                           [])
                 started-at (System/currentTimeMillis)]
             (.write output (codec/byte-values->byte-array (:bytes request)))
             (.flush output)
             (let [response (read-response input {:timeout-ms timeout-ms
                                                  :read-idle-ms read-idle-ms
                                                  :max-response-bytes max-response-bytes})
                   elapsed-ms (- (System/currentTimeMillis) started-at)]
               {:ok? true
                :backend :mac-serial
                :path path
                :request {:byte-count (count (:bytes request))
                          :bytes (:bytes request)
                          :hex (mapv codec/byte->hex (:bytes request))}
                :drained {:byte-count (count drained)
                          :bytes drained
                          :hex (mapv codec/byte->hex drained)}
                :response {:byte-count (count (:bytes response))
                           :bytes (:bytes response)
                           :hex (mapv codec/byte->hex (:bytes response))}
                :timeout-ms timeout-ms
                :read-idle-ms read-idle-ms
                :max-response-bytes max-response-bytes
                :elapsed-ms elapsed-ms
                :timed-out? (:timed-out? response)
                :idle? (:idle? response)
                :truncated? (:truncated? response)})))
         (catch java.io.FileNotFoundException e
           {:ok? false
            :path path
            :error :not-found-or-permission-denied
            :message (.getMessage e)})
         (catch Throwable e
           {:ok? false
            :path path
            :error :exchange-failed
            :message (.getMessage e)}))))))

(defn observe-device
  "Passively opens a serial device and records bytes that are already emitted.

  This is a protocol-discovery helper. It never writes to the device."
  ([device-or-path]
   (observe-device device-or-path {}))
  ([device-or-path {:keys [duration-ms]
                    :or {duration-ms default-observe-ms}}]
   (let [path (if (map? device-or-path) (:path device-or-path) device-or-path)]
     (if-not (seq path)
       {:ok? false
        :error :missing-path}
       (let [deadline (+ (System/currentTimeMillis) duration-ms)]
         (try
           (with-open [input (java.io.BufferedInputStream.
                              (java.io.FileInputStream. path))]
             (loop [bytes []]
               (if (>= (System/currentTimeMillis) deadline)
                 {:ok? true
                  :path path
                  :duration-ms duration-ms
                  :byte-count (count bytes)
                  :bytes bytes
                  :hex (mapv codec/byte->hex bytes)}
                 (let [available (.available input)]
                   (if (pos? available)
                     (let [buffer (byte-array (min available 256))
                           read-count (.read input buffer)]
                       (recur (into bytes
                                    (map #(bit-and % 0xFF))
                                    (take read-count buffer))))
                     (do
                       (Thread/sleep 25)
                       (recur bytes)))))))
           (catch java.io.FileNotFoundException e
             {:ok? false
              :path path
              :error :not-found-or-permission-denied
              :message (.getMessage e)})
           (catch Throwable e
             {:ok? false
              :path path
              :error :observe-failed
              :message (.getMessage e)})))))))
