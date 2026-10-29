(ns tactrix-reader.device.test-support
  "Fakes shared by the device tests: adapter bytes, an in-memory adapter, and
  a vehicle that answers it."
  (:require [clojure.string :as str]
            [tactrix-reader.device.openport :as openport]))

(defn ascii [s]
  (map int s))

(defn u32-bytes [n]
  (map #(bit-and (bit-shift-right n %) 0xFF) [24 16 8 0]))

(defn message-bytes [channel status timestamp-us id data]
  (let [body (concat [status] (u32-bytes timestamp-us) (u32-bytes id) data)]
    (concat (ascii (str "ar" channel)) [(count body)] body)))

(defn temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory "recording" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn fake-adapter
  "An in-memory adapter for session tests. `respond` is called with each
  command line and its binary payload and returns the bytes to send back."
  [respond]
  (let [incoming (java.util.concurrent.LinkedBlockingQueue.)
        pending (atom [])
        payload-length (fn [line]
                         (if-let [[_ n] (re-matches #"att\d+ (\d+) \d+" line)]
                           (parse-long n)
                           (if-let [[_ type n] (re-matches #"atf\d+ (\d+) \d+ (\d+).*" line)]
                             (* (if (= "3" type) 3 2) (parse-long n))
                             0)))
        process! (fn []
                   (loop []
                     (let [bytes @pending
                           newline-at (first (keep-indexed (fn [i b] (when (= 10 b) i)) bytes))]
                       (when newline-at
                         (let [line (str/trim (apply str (map char (subvec bytes 0 newline-at))))
                               end (+ (inc newline-at) (payload-length line))]
                           (when (<= end (count bytes))
                             (reset! pending (subvec bytes end))
                             (doseq [b (respond line (subvec bytes (inc newline-at) end))]
                               (.put incoming (int b)))
                             (recur)))))))
        receive! (fn [bs]
                   (swap! pending into (map #(bit-and % 0xFF)) bs)
                   (process!))
        input (proxy [java.io.InputStream] []
                (available [] (.size incoming))
                (read
                  ([] (if-let [b (.poll incoming)] b -1))
                  ([^bytes buffer] (.read ^java.io.InputStream this buffer 0 (alength buffer)))
                  ([^bytes buffer offset length]
                   (loop [n 0]
                     (if-let [b (when (< n length) (.poll incoming))]
                       (do (aset-byte buffer (+ offset n) (unchecked-byte b))
                           (recur (inc n)))
                       (if (pos? n) n -1))))))
        output (proxy [java.io.OutputStream] []
                 (write
                   ([b] (if (bytes? b) (receive! b) (receive! [b])))
                   ([^bytes buffer offset length]
                    (receive! (java.util.Arrays/copyOfRange buffer (int offset) (int (+ offset length))))))
                 (flush []))]
    (#'openport/stream-session input output)))

(def ^:private fake-pid-data
  {0x0C [0x0C 0x80]
   0x05 [0x55]})

(def ^:private fake-did-data
  {0x10A5 [0x80]
   0x1000 [0xFF 0xC0 0x00 0x0D]})

(defn fake-vehicle-responder []
  (let [clock (atom 1000000)]
    (fn [line payload]
      (cond
        (= "ati" line) (ascii "ari main code version : 1.17.4877\r\n")
        (str/starts-with? line "ato") (ascii "aro\r\n")
        (str/starts-with? line "atc") (ascii "aro\r\n")
        (str/starts-with? line "atf") (ascii "arf5 1 0\r\n")
        (str/starts-with? line "att")
        (let [reply (if (= 0x22 (nth payload 5))
                      (let [[hi lo] (subvec payload 6 8)]
                        (into [0x62 hi lo] (get fake-did-data (+ (* 256 hi) lo) [0])))
                      (let [pid (nth payload 6)]
                        (into [0x41 pid] (get fake-pid-data pid [0 0 0 0]))))]
          (concat (ascii "aro\r\n")
                  (message-bytes 5 0 (swap! clock + 1000) 0x7E8
                                 (take 8 (concat [(count reply)] reply (repeat 0))))))
        :else []))))

(defn wait-for [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 10) (recur))))))

