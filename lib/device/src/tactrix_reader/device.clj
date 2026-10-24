(ns tactrix-reader.device
  "Proof-of-life helpers for OpenPort/J2534-style USB adapters.

  This namespace intentionally starts at the host/device layer. It proves that
  macOS can see and open the adapter before any vehicle protocol is attempted."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

(def default-open-timeout-ms 2000)
(def default-observe-ms 2000)
(def default-exchange-timeout-ms 1000)
(def default-exchange-idle-ms 100)
(def default-exchange-max-response-bytes 256)

(defn usbmodem-path?
  "Returns true when `path` looks like a macOS USB modem serial device."
  [path]
  (boolean (re-find #"^/dev/(cu|tty)\.usbmodem.+" (str path))))

(defn serial-token
  "Extracts the token from a macOS `/dev/*.usbmodem...` path."
  [path]
  (some->> (str path)
           (re-find #"^/dev/(?:cu|tty)\.usbmodem(.+)$")
           second))

(defn candidate-serial?
  "Returns true when a serial path token plausibly belongs to a USB descriptor
  serial. macOS often appends an interface number to the device serial."
  [device-serial path]
  (let [token (serial-token path)]
    (and (seq device-serial)
         (seq token)
         (str/starts-with? token device-serial))))

(defn serial-device-paths
  "Lists local macOS USB modem device paths, preferring callout devices."
  []
  (let [dev-dir (io/file "/dev")]
    (if-not (.isDirectory dev-dir)
      []
      (->> (.listFiles dev-dir)
           (map #(.getPath %))
           (filter usbmodem-path?)
           sort
           (sort-by #(if (str/starts-with? % "/dev/cu.") 0 1))
           vec))))

(defn- parse-int-literal [s]
  (when-let [value (some-> s str/trim not-empty)]
    (try
      (Long/parseLong value)
      (catch NumberFormatException _ nil))))

(defn- parse-usb-property [line]
  (when-let [[_ key quoted-value bare-value]
             (re-find #"^\s*\|\s+\"([^\"]+)\"\s+=\s+(?:\"([^\"]*)\"|([^ \n]+))" line)]
    [(keyword (-> key
                  (str/replace #"[^A-Za-z0-9]+" "-")
                  str/lower-case
                  (str/replace #"^-|-$" "")))
     (or quoted-value (parse-int-literal bare-value) bare-value)]))

(defn parse-ioreg-usb-devices
  "Parses `ioreg -p IOUSB -l -w 0 -c IOUSBHostDevice` output into maps."
  [text]
  (letfn [(device-start [line]
            (when-let [[_ name] (re-find #"\+-o\s+(.+?)@\S+\s+<class IOUSBHostDevice" line)]
              {:name name
               :source :ioreg}))]
    (loop [lines (str/split-lines (or text ""))
           current nil
           devices []]
      (if-let [line (first lines)]
        (if-let [started (device-start line)]
          (recur (rest lines)
                 started
                 (cond-> devices current (conj current)))
          (let [[k v] (or (parse-usb-property line) [nil nil])]
            (recur (rest lines)
                   (cond-> current k (assoc k v))
                   devices)))
        (cond-> devices current (conj current))))))

(defn- openport-usb-device? [device]
  (let [vendor (str/lower-case (str (or (:usb-vendor-name device)
                                        (:kusbvendorstring device)
                                        "")))
        product (str/lower-case (str (or (:usb-product-name device)
                                         (:kusbproductstring device)
                                         (:name device)
                                         "")))]
    (and (str/includes? vendor "tactrix")
         (str/includes? product "openport"))))

(defn- usb-device-summary [device serial-paths]
  (let [serial (or (:usb-serial-number device)
                   (:kusbserialnumberstring device))
        matching-paths (filterv #(candidate-serial? serial %) serial-paths)]
    {:vendor (or (:usb-vendor-name device) (:kusbvendorstring device))
     :product (or (:usb-product-name device) (:kusbproductstring device) (:name device))
     :serial serial
     :vendor-id (:idvendor device)
     :product-id (:idproduct device)
     :paths matching-paths
     :path (first matching-paths)
     :source :ioreg}))

(defn ioreg-usb-devices
  "Reads USB device metadata from macOS ioreg. Returns an empty vector when the
  command is unavailable or fails."
  []
  (let [{:keys [exit out]} (shell/sh "ioreg" "-p" "IOUSB" "-l" "-w" "0" "-c" "IOUSBHostDevice")]
    (if (zero? exit)
      (parse-ioreg-usb-devices out)
      [])))

(defn scan-devices
  "Scans for OpenPort devices and returns EDN-friendly maps.

  The current implementation supports macOS USB enumeration plus `/dev`
  usbmodem serial paths. A path-only fallback is returned when ioreg does not
  report a matching Tactrix device but usbmodem devices exist."
  []
  (let [serial-paths (serial-device-paths)
        usb-devices (filter openport-usb-device? (ioreg-usb-devices))
        usb-summaries (mapv #(usb-device-summary % serial-paths) usb-devices)
        matched-paths (set (mapcat :paths usb-summaries))
        path-only (->> serial-paths
                       (remove matched-paths)
                       (mapv (fn [path]
                               {:vendor nil
                                :product "USB modem serial device"
                                :serial (serial-token path)
                                :vendor-id nil
                                :product-id nil
                                :paths [path]
                                :path path
                                :source :dev})))]
    (vec (concat usb-summaries
                 (when (empty? usb-summaries) path-only)))))

(defn open-device!
  "Attempts to open and close a device path.

  Accepts a path string or a scan result map with `:path`. Returns data instead
  of throwing so CLI/manual smoke tests are easy to interpret."
  ([device-or-path]
   (open-device! device-or-path {}))
  ([device-or-path {:keys [timeout-ms]
                    :or {timeout-ms default-open-timeout-ms}}]
   (let [path (if (map? device-or-path) (:path device-or-path) device-or-path)]
     (if-not (seq path)
       {:ok? false
        :error :missing-path}
       (let [result (future
                      (try
                        (with-open [_ (java.io.RandomAccessFile. path "rw")]
                          {:ok? true
                           :path path
                           :opened? true})
                        (catch java.io.FileNotFoundException e
                          {:ok? false
                           :path path
                           :error :not-found-or-permission-denied
                           :message (.getMessage e)})
                        (catch Throwable e
                          {:ok? false
                           :path path
                           :error :open-failed
                           :message (.getMessage e)})))]
         (deref result
                timeout-ms
                {:ok? false
                 :path path
                 :error :timeout
                 :timeout-ms timeout-ms}))))))

(declare serial-exchange)

(def vbat-pin
  "OBD-II pin 16 carries vehicle battery +12V."
  16)

(def vbat-command (str "atr " vbat-pin "\r\n"))

(defn classify-vbat
  "Classifies an adapter-reported battery voltage."
  [volts]
  (cond
    (nil? volts) :unknown
    (>= volts 9.0) :present
    (< volts 3.0) :absent
    :else :unknown))

(defn parse-pin-voltage-response
  "Parses an OpenPort reply to `atr <pin>`.

  A successful read looks like `arr 16 12400`, where the value is in
  millivolts. Errors look like `are 19`, where the code matches a J2534 status
  code (19 is ERR_PIN_INVALID)."
  [response]
  (let [line (str/trim (str response))]
    (if-let [[_ pin millivolts] (re-matches #"arr (\d+) (\d+)" line)]
      {:ok? true
       :pin (Integer/parseInt pin)
       :millivolts (Integer/parseInt millivolts)}
      (if-let [[_ code] (re-matches #"are (\d+)" line)]
        {:ok? false
         :error :adapter-error
         :adapter/error-code (Integer/parseInt code)}
        {:ok? false
         :error :unexpected-response}))))

(defn- ascii->hex [s]
  (apply str (map #(format "%02X" (int %)) s)))

(defn read-vbat
  "Reads adapter-reported vehicle battery voltage from OBD-II pin 16.

  Sends the adapter-local `atr 16` command; no vehicle bus traffic is sent.
  Returns data instead of throwing so callers can distinguish a failed read
  from vehicle absence."
  ([device-or-path]
   (read-vbat device-or-path {}))
  ([device-or-path opts]
   (let [path (if (map? device-or-path) (:path device-or-path) device-or-path)
         exchange (serial-exchange path (ascii->hex vbat-command) opts)
         response (apply str (map char (get-in exchange [:response :bytes])))
         parsed (when (:ok? exchange)
                  (parse-pin-voltage-response response))
         failure {:ok? false
                  :backend :mac-serial
                  :path path
                  :state :unknown
                  :volts nil}]
     (cond
       (not (:ok? exchange))
       (merge failure (select-keys exchange [:error :message]))

       (and (:ok? parsed) (= vbat-pin (:pin parsed)))
       (let [volts (/ (:millivolts parsed) 1000.0)]
         {:ok? true
          :backend :mac-serial
          :path path
          :pin vbat-pin
          :millivolts (:millivolts parsed)
          :volts volts
          :state (classify-vbat volts)
          :response response})

       (:ok? parsed)
       (assoc failure
              :error :unexpected-pin
              :response response)

       :else
       (merge failure
              (dissoc parsed :ok?)
              {:response response})))))

(defn- vehicle-power-status [vbat-result]
  (if (:ok? vbat-result)
    {:state (classify-vbat (:volts vbat-result))
     :volts (:volts vbat-result)}
    {:state (or (:state vbat-result) :unknown)
     :volts (:volts vbat-result)
     :error (:error vbat-result)}))

(defn vehicle-status
  "Returns host and vehicle connection status for the first detected device or
  a supplied device/path.

  On the current macOS serial backend this reports real host presence/openability
  and vehicle power from the adapter's pin 16 voltage. It sends only the
  adapter-local Vbat read and no vehicle bus traffic."
  ([]
   (vehicle-status (first (scan-devices))))
  ([device-or-path]
   (let [device (when (map? device-or-path) device-or-path)
         path (if (map? device-or-path) (:path device-or-path) device-or-path)
         device-present? (boolean (or device path))
         open-result (when path (open-device! path))
         openable? (true? (:ok? open-result))
         vbat-result (when openable? (read-vbat (or device path)))
         vehicle-power (vehicle-power-status vbat-result)
         reasons (cond-> []
                   (not device-present?)
                   (conj :device-not-detected)

                   (and device-present? (not path))
                   (conj :device-path-not-detected)

                   (and path (not openable?))
                   (conj (or (:error open-result) :device-open-failed))

                   openable?
                   (conj :host-device-openable)

                   (:error vehicle-power)
                   (conj (:error vehicle-power)))]
     {:backend :mac-serial
      :device (when device
                (select-keys device [:vendor :product :serial :vendor-id :product-id :path :paths :source]))
      :path path
      :host/device-present? device-present?
      :host/openable? openable?
      :host/open-result open-result
      :vehicle/power vehicle-power
      :vehicle/vbat-result vbat-result
      :vehicle/ecu {:state :unsupported}
      :confidence (if openable? :medium :low)
      :reasons reasons})))

(defn direct-backend-info
  "Describes the in-house adapter backend and its current discovery surface."
  []
  {:backend :mac-serial
   :external-libraries? false
   :transport {:kind :macos-usbmodem
               :paths (serial-device-paths)}
   :implemented [:scan-devices
                 :open-device
                 :vehicle-status-host-fields
                 :observe-device
                 :serial-exchange
                 :run-probe
                 :hex-request-parser
                 :read-vbat
                 :obd-request
                 :read-pid
                 :read-all-pids]
   :not-implemented [:openport-command-framing
                     :j2534-pass-thru-api
                     :can-session
                     :k-line-session
                     :ecu-messaging]
   :discovery-policy {:writes :explicit-request-only
                      :sweeps? false
                      :vehicle-bus-traffic? false}})

(defn- byte->hex [b]
  (format "%02X" (bit-and b 0xFF)))

(defn hex->bytes
  "Parses a hex byte string into unsigned byte values.

  Whitespace is ignored. Separators such as `0x`, commas, and dashes are not
  accepted so discovery commands stay explicit and copy/paste friendly."
  [s]
  (let [hex (str/replace (str s) #"\s+" "")]
    (cond
      (empty? hex)
      {:ok? false
       :error :empty-hex}

      (odd? (count hex))
      {:ok? false
       :error :odd-length-hex
       :hex hex}

      (not (re-matches #"[0-9A-Fa-f]+" hex))
      {:ok? false
       :error :invalid-hex
       :hex hex}

      :else
      {:ok? true
       :hex (str/upper-case hex)
       :bytes (mapv (fn [pair]
                      (Integer/parseInt pair 16))
                    (map str/join (partition 2 hex)))})))

(defn- byte-values->byte-array [values]
  (byte-array (map unchecked-byte values)))

(defn- read-available-bytes [^java.io.BufferedInputStream input max-count]
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
         request (hex->bytes request-hex)]
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
             (.write output (byte-values->byte-array (:bytes request)))
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
                          :hex (mapv byte->hex (:bytes request))}
                :drained {:byte-count (count drained)
                          :bytes drained
                          :hex (mapv byte->hex drained)}
                :response {:byte-count (count (:bytes response))
                           :bytes (:bytes response)
                           :hex (mapv byte->hex (:bytes response))}
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

(defn known-operation?
  "Returns true when `operation-name` has a discovered direct adapter command."
  [_operation-name]
  false)

(defn unsupported-operation
  "Returns a standard unsupported result for J2534-shaped operations whose
  OpenPort framing has not been discovered yet."
  [operation-name]
  {:ok? false
   :backend :mac-serial
   :operation/name operation-name
   :operation/known? false
   :state :unsupported
   :error :needs-openport-command-framing
   :message "OpenPort command framing has not been discovered for this operation."})

(defn- probe-request-summary [request]
  {:byte-count (count (:bytes request))
   :bytes (:bytes request)
   :hex (mapv byte->hex (:bytes request))})

(defn probe-result
  "Converts a low-level exchange result into a stable discovery transcript."
  [probe-name exchange-result]
  (merge
   {:ok? (:ok? exchange-result)
    :backend :mac-serial
    :probe/name probe-name
    :path (:path exchange-result)
    :operation/known? (known-operation? probe-name)
    :vehicle-bus-traffic? false}
   (select-keys exchange-result
                [:request :drained :response :timeout-ms :read-idle-ms
                 :max-response-bytes :elapsed-ms :timed-out? :idle?
                 :truncated? :error :message])))

(defn run-probe
  "Runs one named adapter-local probe and returns a transcript-shaped result.

  This validates the name, request, and path before delegating to
  `serial-exchange`; it never sweeps or generates request bytes."
  ([probe-name request-hex]
   (run-probe (some :path (scan-devices)) probe-name request-hex))
  ([device-or-path probe-name request-hex]
   (let [name (some-> probe-name str/trim not-empty)
         path (if (map? device-or-path) (:path device-or-path) device-or-path)
         request (hex->bytes request-hex)]
     (cond
       (not name)
       {:ok? false
        :backend :mac-serial
        :error :missing-probe-name
        :vehicle-bus-traffic? false}

       (not (:ok? request))
       {:ok? false
        :backend :mac-serial
        :probe/name name
        :error :invalid-hex-request
        :request request
        :vehicle-bus-traffic? false}

       (not (seq path))
       {:ok? false
        :backend :mac-serial
        :probe/name name
        :path path
        :error :missing-path
        :request (probe-request-summary request)
        :vehicle-bus-traffic? false}

       :else
       (probe-result name (serial-exchange path request-hex))))))

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
                  :hex (mapv byte->hex bytes)}
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

(def default-obd-timeout-ms 1000)
(def default-obd-command-timeout-ms 1000)

(def iso15765-channel
  "The adapter uses J2534 protocol IDs as channel IDs; 6 is ISO15765."
  6)

(def iso15765-frame-pad
  "J2534 ISO15765_FRAME_PAD TxFlag. Some ECUs ignore unpadded frames."
  64)

(def read-only-obd-services
  "OBD-II services that only read data. Service 04 clears DTCs and service 08
  controls on-board systems, so both are excluded."
  #{0x01 0x02 0x03 0x05 0x06 0x07 0x09 0x0A})

(defn- u32 [bytes]
  (reduce (fn [acc b] (+ (* acc 256) b)) 0 bytes))

(defn- u32->bytes [n]
  (mapv #(bit-and (bit-shift-right n %) 0xFF) [24 16 8 0]))

(defn- decode-message [channel body]
  (if (< (count body) 9)
    {:type :message
     :channel channel
     :malformed? true
     :bytes body}
    {:type :message
     :channel channel
     :status (body 0)
     :timestamp-us (u32 (subvec body 1 5))
     :id (u32 (subvec body 5 9))
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

(defn- open-session [path]
  (stream-session (java.io.FileInputStream. path)
                  (java.io.FileOutputStream. path)))

(defn- close-session [{:keys [^java.io.Closeable input ^java.io.Closeable output]}]
  (.close output)
  (.close input))

(defn- read-events!
  "Reads whatever the adapter has sent and queues the parsed events. Returns
  true when any bytes were read."
  [{:keys [input remainder events]}]
  (let [bytes (read-available-bytes input 4096)]
    (when (seq bytes)
      (let [parsed (parse-adapter-stream (into @remainder bytes))]
        (reset! remainder (:remainder parsed))
        (swap! events into (:events parsed))))
    (boolean (seq bytes))))

(defn- take-event!
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
              (Thread/sleep 5))
            (recur)))))))

(defn- send-command!
  "Writes one ASCII command followed by an optional raw payload and returns the
  adapter's reply line, or nil on timeout."
  [{:keys [^java.io.OutputStream output] :as session} command payload timeout-ms]
  (.write output (byte-values->byte-array (concat (map int (str command "\r\n")) payload)))
  (.flush output)
  (:text (take-event! session #(= :line (:type %)) timeout-ms)))

(defn- reply-error [step command reply]
  (merge {:ok? false
          :step step
          :command command
          :reply reply}
         (if-let [[_ code] (when reply (re-matches #"are (\d+)( .*)?" reply))]
           {:error :adapter-error
            :adapter/error-code (Integer/parseInt code)}
           {:error (if reply :unexpected-reply :timeout)})))

(defn- response-pending?
  "True for the negative response NRC 0x78, which means the ECU needs more time."
  [data]
  (and (= 3 (count data))
       (= 0x7F (data 0))
       (= 0x78 (data 2))))

(defn- run-obd-request
  "Opens an ISO15765 channel, sends `request`, and waits for the reply message.
  Always closes the channel afterwards."
  [session request {:keys [tx-id rx-id baud timeout-ms command-timeout-ms]}]
  (let [channel iso15765-channel
        close (str "atc" channel)
        connect (str "ato" channel " 0 " baud)
        flow-control (str "atf" channel " 3 " iso15765-frame-pad " 4")
        transmit (str "att" channel " " (+ 4 (count request)) " " iso15765-frame-pad)
        reply? (fn [event]
                 (and (= :message (:type event))
                      (= channel (:channel event))
                      (= rx-id (:id event))
                      (seq (:data event))))]
    ;; Channels persist in the adapter between port opens, so start closed.
    (send-command! session close [] command-timeout-ms)
    (try
      (let [reply (send-command! session connect [] command-timeout-ms)]
        (if (not= "aro" reply)
          (reply-error :connect connect reply)
          (let [reply (send-command! session flow-control
                                     (concat [0xFF 0xFF 0xFF 0xFF]
                                             (u32->bytes rx-id)
                                             (u32->bytes tx-id))
                                     command-timeout-ms)]
            (if-not (and reply (str/starts-with? reply (str "arf" channel " ")))
              (reply-error :flow-control-filter flow-control reply)
              (let [reply (send-command! session transmit
                                         (concat (u32->bytes tx-id) request)
                                         command-timeout-ms)]
                (if (not= "aro" reply)
                  (reply-error :transmit transmit reply)
                  (loop []
                    (let [message (take-event! session reply? timeout-ms)]
                      (cond
                        (nil? message)
                        {:ok? false
                         :step :response
                         :error :timeout}

                        (response-pending? (:data message))
                        (recur)

                        :else
                        {:ok? true
                         :message message})))))))))
      (finally
        (send-command! session close [] command-timeout-ms)))))

(defn obd-request
  "Sends one read-only OBD-II request over ISO15765 and returns the ECU reply.

  `request-hex` is the service byte and its parameters, such as `0100`
  (supported PIDs) or `0902` (VIN). Only read-only services are sent. The
  adapter handles multi-frame replies and returns them reassembled.

  Unlike the discovery helpers, this sends traffic on the vehicle bus."
  ([device-or-path request-hex]
   (obd-request device-or-path request-hex {}))
  ([device-or-path request-hex {:keys [tx-id rx-id baud timeout-ms command-timeout-ms drain?]
                                :or {tx-id 0x7E0
                                     rx-id 0x7E8
                                     baud 500000
                                     timeout-ms default-obd-timeout-ms
                                     command-timeout-ms default-obd-command-timeout-ms
                                     drain? true}}]
   (let [path (if (map? device-or-path) (:path device-or-path) device-or-path)
         request (hex->bytes request-hex)
         service (first (:bytes request))
         base {:backend :mac-serial
               :path path
               :protocol :iso15765
               :tx-id tx-id
               :rx-id rx-id}]
     (cond
       (not (seq path))
       (assoc base :ok? false :error :missing-path)

       (not (:ok? request))
       (assoc base :ok? false :error :invalid-hex-request :request request)

       (not (read-only-obd-services service))
       (assoc base :ok? false :error :service-not-read-only :service service)

       :else
       (let [request-summary {:bytes (:bytes request)
                              :hex (mapv byte->hex (:bytes request))}]
         (try
           (let [session (open-session path)]
             (try
               (when drain?
                 (read-events! session)
                 (reset! (:events session) [])
                 (reset! (:remainder session) []))
               (let [result (run-obd-request session (:bytes request)
                                             {:tx-id tx-id
                                              :rx-id rx-id
                                              :baud baud
                                              :timeout-ms timeout-ms
                                              :command-timeout-ms command-timeout-ms})
                     {:keys [data status timestamp-us]} (:message result)
                     response {:bytes data
                               :hex (mapv byte->hex data)
                               :status status
                               :timestamp-us timestamp-us}]
                 (cond
                   (not (:ok? result))
                   (merge base {:request request-summary} result)

                   (= 0x7F (first data))
                   (merge base {:ok? false
                                :error :negative-response
                                :nrc (get data 2)
                                :request request-summary
                                :response response})

                   (not= (+ service 0x40) (first data))
                   (merge base {:ok? false
                                :error :unexpected-response
                                :request request-summary
                                :response response})

                   :else
                   (merge base {:ok? true
                                :request request-summary
                                :response response})))
               (finally
                 (close-session session))))
           (catch java.io.FileNotFoundException e
             (merge base {:ok? false
                          :error :not-found-or-permission-denied
                          :message (.getMessage e)}))
           (catch Throwable e
             (merge base {:ok? false
                          :error :obd-request-failed
                          :message (.getMessage e)}))))))))

(defn- supported-pids
  "Decodes a 4-byte supported-PIDs bitmap. Bit 7 of the first byte is PID
  `base + 1`."
  [base]
  (fn [[a b c d]]
    (let [bits (u32 [a b c d])]
      (vec (for [i (range 32)
                 :when (bit-test bits (- 31 i))]
             (+ base 1 i))))))

(defn- word [[a b]]
  (+ (* 256 a) b))

(defn- round-to [places x]
  (let [scale (Math/pow 10 places)]
    (/ (Math/round (* x scale)) scale)))

(defn- percent [[a]]
  (round-to 1 (/ (* 100.0 a) 255)))

(defn- fuel-trim [[a]]
  (round-to 1 (/ (* 100.0 (- a 128)) 128)))

(defn- lambda
  "Air-fuel equivalence ratio from two bytes; 1.0 is stoichiometric."
  [bytes]
  (round-to 3 (/ (* 2.0 (word bytes)) 65536)))

(defn- catalyst-temperature [bytes]
  (round-to 1 (- (/ (word bytes) 10.0) 40)))

(def ^:private fuel-system-states
  {0 :none
   1 :open-loop-cold
   2 :closed-loop
   4 :open-loop-load-or-decel
   8 :open-loop-failure
   16 :closed-loop-feedback-fault})

(defn- fuel-system-status
  "Bytes A/B are fuel systems 1/2. Unrecognised codes are returned as numbers."
  [[a b]]
  {:system-1 (get fuel-system-states a a)
   :system-2 (get fuel-system-states b b)})

(defn- oxygen-sensors-present
  "Bits 0-3 are bank 1 sensors 1-4; bits 4-7 are bank 2 sensors 1-4."
  [[a]]
  (vec (for [bit (range 8)
             :when (bit-test a bit)]
         (keyword (str "bank-" (inc (quot bit 4)) "-sensor-" (inc (mod bit 4)))))))

(defn- narrowband-oxygen-sensor
  "A narrowband sensor's voltage and the fuel trim it drives. A trim byte of
  0xFF means the sensor is not used for trim."
  [[a b]]
  {:volts (/ a 200.0)
   :fuel-trim-percent (when (not= 0xFF b) (fuel-trim [b]))})

(defn- wideband-oxygen-sensor-voltage [[a b c d]]
  {:lambda (lambda [a b])
   :volts (round-to 3 (/ (* 8.0 (word [c d])) 65536))})

(defn- wideband-oxygen-sensor-current [[a b c d]]
  {:lambda (lambda [a b])
   :milliamps (round-to 3 (- (/ (word [c d]) 256.0) 128))})

(def ^:private fuel-types
  [:not-available :gasoline :methanol :ethanol :diesel :lpg :cng :propane
   :electric :bifuel-gasoline :bifuel-methanol :bifuel-ethanol :bifuel-lpg
   :bifuel-cng :bifuel-propane :bifuel-electric :bifuel-electric-and-combustion
   :hybrid-gasoline :hybrid-ethanol :hybrid-diesel :hybrid-electric
   :hybrid-electric-and-combustion :hybrid-regenerative :bifuel-diesel])

(defn- fuel-type
  "Unrecognised codes are returned as numbers."
  [[a]]
  (get fuel-types a a))

(defn- temperature [[a]]
  (- a 40))

(def ^:private spark-ignition-monitors
  "Monitors in bytes C/D of PIDs 01 and 41, by bit, for gasoline engines."
  [:catalyst :heated-catalyst :evaporative-system :secondary-air-system
   :ac-refrigerant :oxygen-sensor :oxygen-sensor-heater :egr-system])

(def ^:private compression-ignition-monitors
  "Monitors in bytes C/D of PIDs 01 and 41, by bit, for diesel engines. Bits 2
  and 4 are reserved."
  [:nmhc-catalyst :nox-scr nil :boost-pressure
   nil :exhaust-gas-sensor :pm-filter :egr-vvt-system])

(defn- monitor-status
  "Decodes bytes B-D of PIDs 01 and 41. Only monitors the vehicle has are
  listed, each as `:complete` or `:incomplete`."
  [[_ b c d]]
  (let [compression? (bit-test b 3)
        common (for [[bit monitor] [[0 :misfire] [1 :fuel-system] [2 :components]]
                     :when (bit-test b bit)]
                 [monitor (if (bit-test b (+ bit 4)) :incomplete :complete)])
        specific (for [[bit monitor] (map-indexed vector (if compression?
                                                           compression-ignition-monitors
                                                           spark-ignition-monitors))
                       :when (and monitor (bit-test c bit))]
                   [monitor (if (bit-test d bit) :incomplete :complete)])]
    {:ignition (if compression? :compression :spark)
     :monitors (into (sorted-map) (concat common specific))}))

(defn- monitor-status-since-codes-cleared [[a :as bytes]]
  (merge {:mil-on? (bit-test a 7)
          :dtc-count (bit-and a 0x7F)}
         (monitor-status bytes)))

(def mode-01-pids
  "Common OBD-II mode 01 PIDs with their SAE J1979 formulas."
  {0x00 {:name :supported-pids-01-20 :byte-count 4 :decode (supported-pids 0x00) :pid-list? true}
   0x01 {:name :monitor-status :byte-count 4 :decode monitor-status-since-codes-cleared}
   0x03 {:name :fuel-system-status :byte-count 2 :decode fuel-system-status}
   0x04 {:name :engine-load :byte-count 1 :unit :percent :decode percent}
   0x05 {:name :coolant-temp :byte-count 1 :unit :celsius :decode temperature}
   0x06 {:name :short-term-fuel-trim-bank-1 :byte-count 1 :unit :percent :decode fuel-trim}
   0x07 {:name :long-term-fuel-trim-bank-1 :byte-count 1 :unit :percent :decode fuel-trim}
   0x0B {:name :intake-manifold-pressure :byte-count 1 :unit :kpa :decode first}
   0x0C {:name :engine-rpm :byte-count 2 :unit :rpm :decode #(/ (word %) 4.0)}
   0x0D {:name :vehicle-speed :byte-count 1 :unit :km-per-hour :decode first}
   0x0E {:name :timing-advance :byte-count 1 :unit :degrees :decode (fn [[a]] (- (/ a 2.0) 64))}
   0x0F {:name :intake-air-temp :byte-count 1 :unit :celsius :decode temperature}
   0x10 {:name :maf-air-flow :byte-count 2 :unit :grams-per-second :decode #(/ (word %) 100.0)}
   0x11 {:name :throttle-position :byte-count 1 :unit :percent :decode percent}
   0x13 {:name :oxygen-sensors-present :byte-count 1 :decode oxygen-sensors-present}
   0x15 {:name :oxygen-sensor-bank-1-sensor-2 :byte-count 2 :decode narrowband-oxygen-sensor}
   0x1F {:name :run-time :byte-count 2 :unit :seconds :decode word}
   0x20 {:name :supported-pids-21-40 :byte-count 4 :decode (supported-pids 0x20) :pid-list? true}
   0x21 {:name :distance-with-mil-on :byte-count 2 :unit :km :decode word}
   0x24 {:name :wideband-oxygen-sensor-1-voltage :byte-count 4 :decode wideband-oxygen-sensor-voltage}
   0x2C {:name :commanded-egr :byte-count 1 :unit :percent :decode percent}
   0x2D {:name :egr-error :byte-count 1 :unit :percent :decode fuel-trim}
   0x2E {:name :commanded-evap-purge :byte-count 1 :unit :percent :decode percent}
   0x2F {:name :fuel-level :byte-count 1 :unit :percent :decode percent}
   0x30 {:name :warm-ups-since-codes-cleared :byte-count 1 :unit :count :decode first}
   0x31 {:name :distance-since-codes-cleared :byte-count 2 :unit :km :decode word}
   0x33 {:name :barometric-pressure :byte-count 1 :unit :kpa :decode first}
   0x34 {:name :wideband-oxygen-sensor-1-current :byte-count 4 :decode wideband-oxygen-sensor-current}
   0x3C {:name :catalyst-temp-bank-1-sensor-1 :byte-count 2 :unit :celsius :decode catalyst-temperature}
   0x3D {:name :catalyst-temp-bank-2-sensor-1 :byte-count 2 :unit :celsius :decode catalyst-temperature}
   0x3E {:name :catalyst-temp-bank-1-sensor-2 :byte-count 2 :unit :celsius :decode catalyst-temperature}
   0x3F {:name :catalyst-temp-bank-2-sensor-2 :byte-count 2 :unit :celsius :decode catalyst-temperature}
   0x40 {:name :supported-pids-41-60 :byte-count 4 :decode (supported-pids 0x40) :pid-list? true}
   0x41 {:name :monitor-status-this-drive-cycle :byte-count 4 :decode monitor-status}
   0x42 {:name :control-module-voltage :byte-count 2 :unit :volts :decode #(/ (word %) 1000.0)}
   0x43 {:name :absolute-load :byte-count 2 :unit :percent :decode #(round-to 1 (/ (* 100.0 (word %)) 255))}
   0x44 {:name :commanded-lambda :byte-count 2 :unit :lambda :decode lambda}
   0x45 {:name :relative-throttle-position :byte-count 1 :unit :percent :decode percent}
   0x46 {:name :ambient-air-temp :byte-count 1 :unit :celsius :decode temperature}
   0x47 {:name :absolute-throttle-position-b :byte-count 1 :unit :percent :decode percent}
   0x48 {:name :absolute-throttle-position-c :byte-count 1 :unit :percent :decode percent}
   0x49 {:name :accelerator-pedal-position-d :byte-count 1 :unit :percent :decode percent}
   0x4A {:name :accelerator-pedal-position-e :byte-count 1 :unit :percent :decode percent}
   0x4B {:name :accelerator-pedal-position-f :byte-count 1 :unit :percent :decode percent}
   0x4C {:name :commanded-throttle-actuator :byte-count 1 :unit :percent :decode percent}
   0x4D {:name :time-with-mil-on :byte-count 2 :unit :minutes :decode word}
   0x4E {:name :time-since-codes-cleared :byte-count 2 :unit :minutes :decode word}
   0x51 {:name :fuel-type :byte-count 1 :decode fuel-type}
   0x53 {:name :evap-vapor-pressure-absolute :byte-count 2 :unit :kpa :decode #(round-to 3 (/ (word %) 200.0))}
   0x5A {:name :relative-accelerator-pedal-position :byte-count 1 :unit :percent :decode percent}
   0x5C {:name :oil-temp :byte-count 1 :unit :celsius :decode temperature}
   0x60 {:name :supported-pids-61-80 :byte-count 4 :decode (supported-pids 0x60) :pid-list? true}})

(def ^:private mode-01-pids-by-name
  (into {} (map (fn [[pid {:keys [name]}]] [name pid])) mode-01-pids))

(defn decode-mode-01
  "Decodes a positive mode 01 reply such as `[0x41 0x0C 0x0C 0x80]`."
  [data]
  (let [[service pid & args] data
        args (vec args)
        {:keys [name byte-count unit decode pid-list?]} (get mode-01-pids pid)]
    (cond
      (not= 0x41 service)
      {:ok? false
       :error :not-mode-01-reply}

      (nil? name)
      {:ok? false
       :pid pid
       :error :unknown-pid
       :raw args}

      (< (count args) byte-count)
      {:ok? false
       :pid pid
       :name name
       :error :short-reply
       :raw args}

      :else
      (let [value (decode (subvec args 0 byte-count))]
        (cond-> {:ok? true
                 :pid pid
                 :name name
                 :value value}
          unit (assoc :unit unit)
          pid-list? (assoc :value-hex (mapv byte->hex value)))))))

(defn read-pid
  "Reads and decodes one mode 01 PID, given as a number such as `0x0C` or a
  name such as `:engine-rpm`. Sends traffic on the vehicle bus."
  ([device-or-path pid]
   (read-pid device-or-path pid {}))
  ([device-or-path pid opts]
   (let [pid-number (if (keyword? pid) (mode-01-pids-by-name pid) pid)]
     (if-not (and (int? pid-number) (<= 0 pid-number 0xFF))
       {:ok? false
        :error :unknown-pid
        :pid pid
        :known-pids (sort (keys mode-01-pids-by-name))}
       (let [result (obd-request device-or-path (format "01%02X" pid-number) opts)]
         (if (:ok? result)
           (merge result (decode-mode-01 (get-in result [:response :bytes])))
           result))))))

(defn read-supported-pids
  "Reads the supported-PID bitmaps starting at PID 00, following each bitmap's
  next-range bit. Sends traffic on the vehicle bus."
  ([device-or-path]
   (read-supported-pids device-or-path {}))
  ([device-or-path opts]
   (loop [base 0x00
          supported []]
     (let [result (read-pid device-or-path base opts)
           supported (into supported (:value result))
           next-base (+ base 0x20)]
       (cond
         (not (:ok? result))
         (assoc result :supported supported)

         (and (some #{next-base} (:value result))
              (:pid-list? (get mode-01-pids next-base)))
         (recur next-base supported)

         :else
         {:ok? true
          :supported supported})))))

(defn read-all-pids
  "Reads every decodable mode 01 PID the vehicle reports as supported.
  Sends traffic on the vehicle bus."
  ([device-or-path]
   (read-all-pids device-or-path {}))
  ([device-or-path opts]
   (let [supported (read-supported-pids device-or-path opts)]
     (if-not (:ok? supported)
       supported
       (let [supported? (set (:supported supported))
             decodable (sort (for [[pid {:keys [pid-list?]}] mode-01-pids
                                   :when (not pid-list?)]
                               pid))
             results (for [pid decodable
                           :when (supported? pid)]
                       (assoc (read-pid device-or-path pid opts)
                              :pid pid
                              :name (get-in mode-01-pids [pid :name])))
             errors (vec (for [result results
                               :when (not (:ok? result))]
                           (select-keys result [:pid :name :error :nrc :step])))]
         {:ok? (empty? errors)
          :supported-hex (mapv byte->hex (:supported supported))
          :values (vec (for [result results
                             :when (:ok? result)]
                         (select-keys result [:pid :name :value :unit])))
          :errors errors
          :not-supported (vec (remove supported? decodable))})))))
