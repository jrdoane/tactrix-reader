(ns tactrix-reader.device
  "Talks to a Tactrix OpenPort 2.0 through its macOS serial device: device
  discovery, the adapter's OpenPort commands, read-only OBD-II requests and
  PID decoding, CAN recordings, and live and replay data sources."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.pprint :as pprint]
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
  "Describes the in-house adapter backend: what it implements, which operations
  send traffic on the vehicle bus, and the policy for protocol discovery."
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
                 :openport-command-framing
                 :read-vbat
                 :obd-request
                 :read-pid
                 :read-all-pids
                 :read-vehicle-info
                 :ssm-init
                 :ssm-read
                 :can-session
                 :record
                 :export-recording
                 :live-source
                 :replay-source]
   :not-implemented [:j2534-pass-thru-api
                     :k-line-session]
   :vehicle-bus {:read-only? true
                 :operations [:obd-request
                              :read-pid
                              :read-all-pids
                              :read-vehicle-info
                              :ssm-init
                              :ssm-read
                              :record
                              :live-source]}
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

(def ^:private iso15765-defaults
  {:tx-id 0x7E0
   :rx-id 0x7E8
   :baud 500000
   :timeout-ms default-obd-timeout-ms
   :command-timeout-ms default-obd-command-timeout-ms
   :drain? true})

(defn- iso15765-request
  "Opens the device, sends `request` bytes over ISO15765, and checks that the
  reply's first byte is the request's plus 0x40. `base` is merged into every
  result, and `failed-error` is the `:error` for unexpected exceptions."
  [path request base failed-error {:keys [drain?] :as opts}]
  (let [service (first request)
        request-summary {:bytes request
                         :hex (mapv byte->hex request)}]
    (try
      (let [session (open-session path)]
        (try
          (when drain?
            (read-events! session)
            (reset! (:events session) [])
            (reset! (:remainder session) []))
          (let [result (run-obd-request session request
                                        (select-keys opts [:tx-id :rx-id :baud :timeout-ms
                                                           :command-timeout-ms]))
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
                     :error failed-error
                     :message (.getMessage e)})))))

(defn obd-request
  "Sends one read-only OBD-II request over ISO15765 and returns the ECU reply.

  `request-hex` is the service byte and its parameters, such as `0100`
  (supported PIDs) or `0902` (VIN). Only read-only services are sent. The
  adapter handles multi-frame replies and returns them reassembled.

  Unlike the discovery helpers, this sends traffic on the vehicle bus."
  ([device-or-path request-hex]
   (obd-request device-or-path request-hex {}))
  ([device-or-path request-hex opts]
   (let [{:keys [tx-id rx-id] :as opts} (merge iso15765-defaults opts)
         path (if (map? device-or-path) (:path device-or-path) device-or-path)
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
       (iso15765-request path (:bytes request) base :obd-request-failed opts)))))

(def ssm-init-command
  "The Subaru Select Monitor (SSM) command that identifies the ECU. With
  `ssm-read-addresses-command`, it is one of only two SSM commands sent: `B0`
  and `B8` write ECU memory and are never built."
  0xAA)

(def ssm-read-addresses-command
  "The SSM command that reads one byte at each of a list of 3-byte ECU memory
  addresses."
  0xA8)

(defn ssm-init
  "Identifies the ECU with the SSM `AA` command, sent over ISO15765 like
  `obd-request`.

  The reply is `EA`, 3 bytes of unknown meaning, the 5-byte ECU ID, and
  bitfields of the standard SSM parameters the ECU supports. Returns the ECU ID
  as hex in `:ecu-id`, such as `\"7334384107\"`; logger definitions use it to
  find ECU-specific addresses. Takes the same options as `obd-request`.

  Sends traffic on the vehicle bus."
  ([device-or-path]
   (ssm-init device-or-path {}))
  ([device-or-path opts]
   (let [{:keys [tx-id rx-id] :as opts} (merge iso15765-defaults opts)
         path (if (map? device-or-path) (:path device-or-path) device-or-path)
         base {:backend :mac-serial
               :path path
               :protocol :iso15765
               :tx-id tx-id
               :rx-id rx-id}]
     (if-not (seq path)
       (assoc base :ok? false :error :missing-path)
       (let [result (iso15765-request path [ssm-init-command] base :ssm-init-failed opts)
             data (get-in result [:response :bytes])]
         (cond
           (not (:ok? result))
           result

           (< (count data) 9)
           (assoc result :ok? false :error :unexpected-response)

           :else
           (let [capabilities (subvec data 9)]
             (assoc result
                    :ecu-id (apply str (map byte->hex (subvec data 4 9)))
                    :capabilities {:bytes capabilities
                                   :hex (mapv byte->hex capabilities)}))))))))

(defn- ssm-address? [address]
  (and (int? address) (<= 0 address 0xFFFFFF)))

(defn ssm-read
  "Reads one byte at each ECU memory address with the Subaru Select Monitor
  (SSM) `A8` command, sent over ISO15765 like `obd-request`.

  `addresses` is a sequence of integers up to `0xFFFFFF`, such as `[0x000022]`.
  The reply has one byte per address, in order, so a 4-byte value is read as
  four consecutive addresses. Takes the same options as `obd-request`.

  Sends traffic on the vehicle bus."
  ([device-or-path addresses]
   (ssm-read device-or-path addresses {}))
  ([device-or-path addresses opts]
   (let [{:keys [tx-id rx-id] :as opts} (merge iso15765-defaults opts)
         path (if (map? device-or-path) (:path device-or-path) device-or-path)
         base {:backend :mac-serial
               :path path
               :protocol :iso15765
               :tx-id tx-id
               :rx-id rx-id}]
     (cond
       (not (seq path))
       (assoc base :ok? false :error :missing-path)

       (not (and (sequential? addresses)
                 (seq addresses)
                 (every? ssm-address? addresses)))
       (assoc base :ok? false :error :invalid-ssm-addresses :addresses addresses)

       :else
       ;; The 00 after A8 selects a single read; SSM over CAN has no fast poll.
       (let [request (into [ssm-read-addresses-command 0x00]
                           (mapcat #(subvec (u32->bytes %) 1))
                           addresses)
             result (iso15765-request path request base :ssm-read-failed opts)
             data (get-in result [:response :bytes])]
         (cond
           (not (:ok? result))
           result

           (not= (inc (count addresses)) (count data))
           (assoc result :ok? false :error :unexpected-response)

           :else
           (assoc result :values (mapv (fn [address value]
                                         {:address address
                                          :address-hex (format "%06X" address)
                                          :value value})
                                       addresses
                                       (rest data)))))))))

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

(def ^:private obd-standards
  {1 :obd-ii-carb
   2 :obd-epa
   3 :obd-and-obd-ii
   4 :obd-i
   5 :not-obd-compliant
   6 :eobd
   7 :eobd-and-obd-ii
   8 :eobd-and-obd
   9 :eobd-obd-and-obd-ii
   10 :jobd
   11 :jobd-and-obd-ii
   12 :jobd-and-eobd
   13 :jobd-eobd-and-obd-ii
   17 :emd
   18 :emd-plus
   19 :hd-obd-c
   20 :hd-obd
   21 :wwh-obd})

(defn- obd-standard
  "Unrecognised codes are returned as numbers."
  [[a]]
  (get obd-standards a a))

(defn- auxiliary-io-status
  "Byte A says which inputs are reported; byte B holds their states. Only
  reported inputs are listed. The transmission bits are 0 in neutral, which
  was confirmed on a manual vehicle."
  [[a b]]
  (into {}
        (for [[bit key] [[0 :pto-active?]
                         [1 :automatic-transmission-in-drive?]
                         [2 :manual-transmission-in-gear?]
                         [3 :glow-plug-lamp-on?]]
              :when (bit-test a bit)]
          [key (bit-test b bit)])))

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
   0x1C {:name :obd-standard :byte-count 1 :decode obd-standard}
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
   0x60 {:name :supported-pids-61-80 :byte-count 4 :decode (supported-pids 0x60) :pid-list? true}
   0x65 {:name :auxiliary-io-status :byte-count 2 :decode auxiliary-io-status}})

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

(defn- ascii-text
  "Decodes ASCII bytes, dropping zero padding and surrounding spaces."
  [bytes]
  (str/trim (apply str (map char (remove zero? bytes)))))

(def ^:private in-use-monitors
  "The monitors in a spark ignition in-use performance reply (`0908`), in
  reply order. Each has a completion count and a conditions-met count."
  [:catalyst-bank-1 :catalyst-bank-2
   :oxygen-sensor-bank-1 :oxygen-sensor-bank-2
   :egr-system :secondary-air :evaporative-system
   :secondary-oxygen-sensor-bank-1 :secondary-oxygen-sensor-bank-2])

(defn- in-use-performance
  "Decodes the 16-bit counters of a `0908` reply. Older ECUs send 16
  counters and newer ones 20, so the monitor list is cut to fit."
  [counters]
  (let [[general-denominator ignition-cycles & monitor-counts] counters]
    {:general-denominator general-denominator
     :ignition-cycles ignition-cycles
     :monitors (into {}
                     (map (fn [monitor [completions conditions]]
                            [monitor {:completions completions :conditions conditions}])
                          in-use-monitors
                          (partition 2 monitor-counts)))}))

(defn decode-mode-09
  "Decodes a positive mode 09 reply, such as `[0x49 0x02 0x01 ...]`: the
  supported info types (`00`), VIN (`02`), calibration IDs (`04`),
  calibration verification numbers (`06`), in-use performance counters for a
  spark ignition engine (`08`), or ECU name (`0A`). On CAN, the byte after
  the info type is the number of data items, except for `00`."
  [data]
  (let [[service info-type & args] data
        [item-count & items] args
        items (vec items)]
    (cond
      (not= 0x49 service)
      {:ok? false
       :error :not-mode-09-reply}

      (nil? item-count)
      {:ok? false
       :info-type info-type
       :error :short-reply}

      :else
      (case info-type
        ;; A 4-byte bitmap, like mode 01 PID 00, with no item count.
        0x00 {:ok? true :name :supported-info-types :value ((supported-pids 0x00) (vec args))}
        0x02 {:ok? true :name :vin :value (ascii-text items)}
        ;; Each calibration ID is 16 bytes, zero-padded.
        0x04 {:ok? true :name :calibration-ids :value (mapv ascii-text (take item-count (partition 16 items)))}
        ;; Each CVN is 4 bytes, one per calibration ID, shown as hex.
        0x06 {:ok? true
              :name :calibration-verification-numbers
              :value (mapv #(apply str (map byte->hex %)) (take item-count (partition 4 items)))}
        ;; The item count is the number of 16-bit counters.
        0x08 {:ok? true
              :name :in-use-performance
              :value (in-use-performance (map word (take item-count (partition 2 items))))}
        0x0A {:ok? true :name :ecu-name :value (ascii-text items)}
        {:ok? false
         :info-type info-type
         :error :unknown-info-type
         :raw items}))))

(def ^:private vehicle-info-requests
  [[:vin "0902"]
   [:calibration-ids "0904"]
   [:calibration-verification-numbers "0906"]
   [:ecu-name "090A"]])

(defn read-vehicle-info
  "Reads what identifies the vehicle and its engine ECU: the VIN, calibration
  IDs, calibration verification numbers, and ECU name (mode 09), and the ECU
  ID (SSM `AA`, see `ssm-init`).

  Each item the ECU does not answer is nil, with its error under `:errors`.
  `:ok?` is true when at least one item was read. Takes the same options as
  `obd-request`.

  Sends traffic on the vehicle bus."
  ([device-or-path]
   (read-vehicle-info device-or-path {}))
  ([device-or-path opts]
   (let [path (if (map? device-or-path) (:path device-or-path) device-or-path)]
     (if-not (seq path)
       {:ok? false
        :error :missing-path}
       (let [results (into {}
                           (for [[item request-hex] vehicle-info-requests]
                             (let [result (obd-request path request-hex opts)
                                   decoded (when (:ok? result)
                                             (decode-mode-09 (get-in result [:response :bytes])))]
                               [item (if (:ok? result) decoded result)])))
             ecu-id (ssm-init path opts)
             results (assoc results :ecu-id (assoc ecu-id :value (:ecu-id ecu-id)))
             errors (into {}
                          (for [[item result] results
                                :when (not (:ok? result))]
                            [item (select-keys result [:error :nrc])]))]
         (merge {:ok? (boolean (some :ok? (vals results)))
                 :path path
                 :errors errors}
                (when (= (count errors) (count results))
                  {:error (get-in errors [:vin :error])})
                (update-vals results #(when (:ok? %) (:value %)))))))))

(def can-channel
  "The adapter uses J2534 protocol IDs as channel IDs; 5 is raw CAN."
  5)

(def default-record-duration-ms 60000)

(def default-poll-interval-ms
  "Gap between polling requests. The ECU answers in about 1 ms."
  15)

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
  slow PID, and repeat. At the default interval, each fast PID is sampled
  about every 210 ms and each slow PID about every 2.9 seconds."
  (vec (mapcat #(conj fast-pids %) slow-pids)))

(defn- read-chunk
  "Returns the bytes the adapter has sent so far, or nil when there are none."
  ^bytes [^java.io.InputStream input]
  (let [available (.available input)]
    (when (pos? available)
      (let [buffer (byte-array available)
            read-count (.read input buffer)]
        (when (pos? read-count)
          (java.util.Arrays/copyOf buffer read-count))))))

(defn- collect! [input ^java.io.ByteArrayOutputStream sink ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (while (< (System/currentTimeMillis) deadline)
      (if-let [chunk (read-chunk input)]
        (.write sink ^bytes chunk)
        (Thread/sleep 2)))))

(defn- write-command! [^java.io.OutputStream output command payload]
  (.write output (byte-values->byte-array (concat (map int (str command "\r\n")) payload)))
  (.flush output))

(defn- unsigned-bytes [^bytes bytes]
  (mapv #(bit-and % 0xFF) bytes))

(defn- reply-lines [^java.io.ByteArrayOutputStream sink]
  (keep :text (:events (parse-adapter-stream (unsigned-bytes (.toByteArray sink))))))

(defn- csv-field [value]
  (let [s (str value)]
    (if (re-find #"[\",\n]" s)
      (str "\"" (str/replace s "\"" "\"\"") "\"")
      s)))

(defn- hex-string [bytes]
  (str/join " " (map byte->hex bytes)))

(defn reply-sample
  "Decodes a received message carrying a single-frame mode 01 reply from
  `rx-id` into a sample with the adapter's timestamp. Returns nil for any
  other message."
  [rx-id {:keys [type id data timestamp-us]}]
  (when (and (= :message type) (= rx-id id) (seq data))
    (let [decoded (decode-mode-01 (vec (take (first data) (rest data))))]
      (when (:ok? decoded)
        (-> decoded
            (dissoc :ok?)
            (assoc :timestamp-us timestamp-us))))))

(def broadcast-signals
  "Signals the project's 2015 Impreza broadcasts on its CAN bus, by CAN ID.
  Each was confirmed against the mode 01 PID it reports as; see
  docs/can-broadcast.md. `:byte-count` is the data length a frame needs."
  {0x0D1 [{:pid 0x0D :byte-count 2
           :decode (fn [[low high]] (round-to 1 (* 0.05625 (+ low (* 256 high)))))}]
   0x140 [{:pid 0x0C :byte-count 4
           :decode (fn [[_ _ low high]] (double (+ low (* 256 (bit-and high 0x3F)))))}
          ;; Byte 6 is the throttle opening, 0-255. PID 11's raw byte is
          ;; 29 when it is closed and 205 when it is wide open.
          {:pid 0x11 :byte-count 7
           :decode #(percent [(Math/round (+ 29.0 (/ (* 176.0 (nth % 6)) 255)))])}]
   0x360 [{:pid 0x5C :byte-count 4 :decode #(temperature [(nth % 2)])}
          {:pid 0x05 :byte-count 4 :decode #(temperature [(nth % 3)])}
          {:pid 0x0B :byte-count 5 :decode #(* 2 (nth % 4))}]})

(defn broadcast-samples
  "Decodes a received message carrying signals in `signals` (by default
  `broadcast-signals`) into samples shaped like `reply-sample`'s, with the
  CAN ID under :can-id. Returns an empty sequence for any other message."
  ([message]
   (broadcast-samples broadcast-signals message))
  ([signals {:keys [type id data timestamp-us]}]
   (when (= :message type)
     (for [{:keys [pid byte-count decode]} (get signals id)
           :when (>= (count data) byte-count)
           :let [{:keys [name unit]} (get mode-01-pids pid)]]
       {:pid pid
        :name name
        :value (decode data)
        :unit unit
        :can-id id
        :timestamp-us timestamp-us}))))

(defn- signed-byte [b]
  (if (> b 127) (- b 256) b))

(defn- signed-word [high low]
  (let [n (+ (* 256 high) low)]
    (if (> n 32767) (- n 65536) n)))

(defn- wheel-speed [[low high]]
  (round-to 1 (* 0.05625 (+ low (* 256 high)))))

(def inferred-signals
  "Signals the project's 2015 Impreza broadcasts that were identified from how
  they behave in the 2026-10-28-drive recording but match no mode 01 PID, by
  CAN ID. Scales marked approximate come from fits against quantities derived
  from other signals; see docs/can-broadcast.md. Unlike `broadcast-signals`,
  these are not confirmed."
  {0x002 [{:signal :steering-angle :unit :degrees :byte-count 2
           ;; Positive is a left turn, assuming opendbc's wheel order in 0D4.
           :decode (fn [[high low]] (round-to 1 (* 0.1 (signed-word high low))))}]
   0x0D0 [;; Approximate scales. Positive is to the left and forwards.
          {:signal :lateral-acceleration :unit :g :byte-count 4
           :decode (fn [[_ _ low high]] (round-to 2 (* 0.00734 (signed-word high low))))}
          {:signal :yaw-rate :unit :degrees-per-second :byte-count 7
           :decode #(round-to 1 (* 0.68 (signed-byte (nth % 6))))}
          {:signal :longitudinal-acceleration :unit :g :byte-count 8
           :decode #(round-to 2 (* -0.00907 (signed-byte (nth % 7))))}]
   0x0D1 [{:signal :brake-pressure :byte-count 3 :decode #(nth % 2)}]
   0x0D3 [{:signal :brake :byte-count 3 :decode #(bit-test (nth % 2) 5)}]
   0x0D4 [{:signal :wheel-speeds :unit :km-per-hour :byte-count 8
           :decode (fn [data]
                     (zipmap [:front-left :front-right :rear-left :rear-right]
                             (map wheel-speed (partition 2 data))))}]
   0x141 [{:signal :gear :byte-count 7
           ;; 1-5, or 7 with no gear engaged.
           :decode #(let [gear (bit-and (nth % 6) 0x07)]
                      (cond (<= 1 gear 5) gear
                            (= 7 gear) :neutral))}]
   0x152 [{:signal :parking-brake :byte-count 7 :decode #(bit-test (nth % 6) 3)}]
   0x282 [{:signal :outside-temp :unit :celsius :byte-count 5
           ;; Byte 4 is degrees Fahrenheit plus 40.
           :decode #(round-to 1 (/ (* 5 (- (nth % 4) 72)) 9.0))}
          {:signal :driver-seatbelt :byte-count 6
           :decode #(if (bit-test (nth % 5) 0) :unbuckled :buckled)}]
   0x360 [;; Approximate scale.
          {:signal :fuel-flow :unit :grams-per-second :byte-count 2
           :decode #(round-to 2 (* 0.185 (nth % 1)))}]})

(defn signal-samples
  "Decodes a received message carrying signals in `signals` (by default
  `inferred-signals`) into `{:signal :value :unit :can-id :timestamp-us}`
  maps. Returns an empty sequence for any other message."
  ([message]
   (signal-samples inferred-signals message))
  ([signals {:keys [type id data timestamp-us]}]
   (when (= :message type)
     (for [{:keys [signal unit byte-count decode]} (get signals id)
           :when (>= (count data) byte-count)]
       {:signal signal
        :value (decode data)
        :unit unit
        :can-id id
        :timestamp-us timestamp-us}))))

(defn- message-samples
  "Returns the samples in one received message: a mode 01 reply from `rx-id`,
  or broadcast signals from `signals`."
  [rx-id signals message]
  (if-let [sample (reply-sample rx-id message)]
    [sample]
    (broadcast-samples signals message)))

(defn- message-events
  "Returns the source events for one received message: `:sample` events as
  `message-samples` gives, then `:signal` events for `inferred`."
  [rx-id broadcast inferred message]
  (concat (map #(assoc % :type :sample) (message-samples rx-id broadcast message))
          (map #(assoc % :type :signal) (signal-samples inferred message))))

(defn- run-can-session!
  "Opens the raw CAN channel with a pass-all filter and polls `pids`
  round-robin, one request every `interval-ms`, while `continue?` returns true.
  Every byte the adapter sends from the connect command on is passed to
  `on-bytes`. `on-connected` is called with the firmware version once the
  channel is open. Always closes the channel it opened."
  [{:keys [input output]} {:keys [pids interval-ms tx-id baud continue? on-bytes on-connected]
                           :or {on-connected (fn [_])}}]
  (let [channel can-channel
        setup (java.io.ByteArrayOutputStream.)
        connect (java.io.ByteArrayOutputStream.)]
    (collect! input (java.io.ByteArrayOutputStream.) 200)
    (write-command! output (str "atc" channel) [])
    (write-command! output "ati" [])
    (collect! input setup 300)
    (write-command! output (str "ato" channel " 0 " baud) [])
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
        (let [request-count (atom 0)]
          (try
            (write-command! output (str "atf" channel " 1 0 4") (repeat 8 0))
            (on-connected firmware)
            (loop [i 0
                   next-request (System/currentTimeMillis)]
              (when (continue?)
                (let [now (System/currentTimeMillis)]
                  (if (and (seq pids) (>= now next-request))
                    (do
                      (write-command! output (str "att" channel " 12 0")
                                      (concat (u32->bytes tx-id)
                                              [0x02 0x01 (nth pids (mod i (count pids))) 0 0 0 0 0]))
                      (swap! request-count inc)
                      ;; Never schedule in the past, so a stall does not
                      ;; cause a burst of catch-up requests.
                      (recur (inc i) (max (+ next-request interval-ms) now)))
                    (do
                      (if-let [chunk (read-chunk input)]
                        (on-bytes chunk)
                        (Thread/sleep 1))
                      (recur i next-request))))))
            {:ok? true
             :firmware firmware
             :request-count @request-count}
            (finally
              (let [tail (java.io.ByteArrayOutputStream.)]
                (collect! input tail 300)
                (write-command! output (str "atc" channel) [])
                (collect! input tail 300)
                (on-bytes (.toByteArray tail))))))))))

(defn recording-meta
  "Reads a recording's meta.edn, or returns nil when it has none."
  [dir]
  (let [meta-file (io/file dir "meta.edn")]
    (when (.exists meta-file)
      (edn/read-string (slurp meta-file)))))

(defn- recording-rx-id [dir]
  (get-in (recording-meta dir) [:polling :rx-id] 0x7E8))

(defn- recording-bytes [dir]
  (unsigned-bytes (java.nio.file.Files/readAllBytes (.toPath (io/file dir "raw.bin")))))

(defn recording-samples
  "Returns the samples in a recording's raw.bin, in order: decoded PID
  replies, plus broadcast signals from `signals` (by default
  `broadcast-signals`)."
  ([dir]
   (recording-samples dir broadcast-signals))
  ([dir signals]
   (let [rx-id (recording-rx-id dir)]
     (into []
           (mapcat #(message-samples rx-id signals %))
           (:events (parse-adapter-stream (recording-bytes dir)))))))

(defn export-recording
  "Writes frames.csv and pids.csv in `dir` from its raw.bin.

  frames.csv lists every received CAN frame. pids.csv lists decoded mode 01
  replies from `rx-id`, which defaults to the one in meta.edn. Times are
  seconds since the first frame, plus the adapter's microsecond timestamp."
  ([dir]
   (export-recording dir (recording-rx-id dir)))
  ([dir rx-id]
   (let [events (:events (parse-adapter-stream (recording-bytes dir)))
         messages (filterv #(and (= :message (:type %)) (not (:malformed? %))) events)
         t0 (:timestamp-us (first messages))
         seconds (fn [timestamp-us] (format "%.6f" (/ (- timestamp-us t0) 1e6)))
         pid-rows (keep #(reply-sample rx-id %) messages)]
     (with-open [writer (io/writer (io/file dir "frames.csv"))]
       (.write writer "t_s,timestamp_us,channel,status,id,dlc,data\n")
       (doseq [{:keys [timestamp-us channel status id data]} messages]
         (.write writer (str (str/join "," [(seconds timestamp-us) timestamp-us channel
                                            (byte->hex status) (format "%03X" id)
                                            (count data) (hex-string data)])
                             "\n"))))
     (with-open [writer (io/writer (io/file dir "pids.csv"))]
       (.write writer "t_s,timestamp_us,pid,name,value,unit\n")
       (doseq [{:keys [timestamp-us pid name value unit]} pid-rows]
         (.write writer (str (str/join "," [(seconds timestamp-us) timestamp-us (byte->hex pid)
                                            (clojure.core/name name) (csv-field value)
                                            (if unit (clojure.core/name unit) "")])
                             "\n"))))
     {:frames (count messages)
      :pid-samples (count pid-rows)
      :reply-lines (frequencies (keep #(some-> (:text %) (str/split #" ") first) events))})))

(defn record
  "Records raw CAN traffic to `out-dir` while polling mode 01 PIDs.

  Streams raw.bin (the exact bytes the adapter sent) to disk as it arrives,
  then writes meta.edn and the CSVs from `export-recording`. Records for
  `:duration-ms` (60 seconds by default; nil for no limit), or until the
  `:stop` promise is delivered. If the adapter fails partway through, the
  files are still written from what was captured, and the result has
  `:ok? false`. Polling sends one read-only request every `:interval-ms`,
  rotating through `:pids`; pass `:pids []` to only listen."
  ([device-or-path out-dir]
   (record device-or-path out-dir {}))
  ([device-or-path out-dir {:keys [pids interval-ms tx-id rx-id baud stop]
                            :or {pids default-poll-schedule
                                 interval-ms default-poll-interval-ms
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

       :else
       (try
         (let [session (open-session path)
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
                           (run-can-session! session
                                             {:pids pids
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
                             (close-session session)))
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
                                           :bus {:channel can-channel
                                                 :protocol :can
                                                 :bitrate baud
                                                 :filter :pass-all
                                                 :loopback false}
                                           :polling {:tx-id tx-id
                                                     :rx-id rx-id
                                                     :interval-ms interval-ms
                                                     :request-count (:request-count outcome)
                                                     :pids (mapv byte->hex pids)
                                                     :request-frame "02 01 <pid> 00 00 00 00 00"}
                                           :files {"raw.bin" "Exact bytes received from the adapter, including command replies."
                                                   "frames.csv" "Every received CAN frame, including copies of the polling requests. Regenerate with export-recording."
                                                   "pids.csv" "Decoded mode 01 replies. Regenerate with export-recording."}
                                           :counts counts}
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

(defn- message-decoder
  "Returns a function that takes chunks of adapter bytes and calls `on-message`
  with each complete received message, keeping partial ones for the next chunk."
  [on-message]
  (let [remainder (atom [])]
    (fn [^bytes chunk]
      (let [parsed (parse-adapter-stream (into @remainder (unsigned-bytes chunk)))]
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
  each signal in `:signals` (by default `inferred-signals`), and
  `{:type :status ...}` when the source is running, stopped, or failed. `{}`
  turns either kind of broadcast decoding off. Returns a map whose :stop!
  function stops polling and waits for the channel to close.

  Sends read-only mode 01 requests on the vehicle bus."
  [device-or-path {:keys [emit schedule interval-ms tx-id rx-id baud broadcast signals]
                   :or {schedule default-poll-schedule
                        interval-ms default-poll-interval-ms
                        tx-id 0x7E0
                        rx-id 0x7E8
                        baud 500000
                        broadcast broadcast-signals
                        signals inferred-signals}}]
  (let [path (if (map? device-or-path) (:path device-or-path) device-or-path)
        running? (atom true)
        status (status-emitter emit {:source :live :path path})
        worker (future
                 (try
                   (if-not (seq path)
                     (status :failed :error :missing-path)
                     (let [session (open-session path)]
                       (try
                         (let [result (run-can-session!
                                       session
                                       {:pids schedule
                                        :interval-ms interval-ms
                                        :tx-id tx-id
                                        :baud baud
                                        :continue? #(deref running?)
                                        :on-bytes (message-decoder
                                                   (fn [message]
                                                     (run! emit (message-events rx-id broadcast signals message))))
                                        :on-connected #(status :running :firmware %)})]
                           (if (:ok? result)
                             (status :stopped)
                             (status :failed :error (:error result) :reply (:reply result))))
                         (finally
                           (close-session session)))))
                   (catch Throwable e
                     (status :failed :error :live-source-failed :message (.getMessage e)))))]
    {:stop! (fn []
              (reset! running? false)
              @worker
              nil)}))

(defn start-replay-source!
  "Replays a recording on a background thread, paced by its timestamps,
  calling `emit` with the same events as `start-live-source!`, including
  broadcast signals in `:broadcast` and inferred signals in `:signals`. Loops
  by default, shifting timestamps so they keep increasing. `:speed` scales
  playback. Returns a map whose :stop! function stops playback."
  [dir {:keys [emit loop? speed broadcast signals]
        :or {loop? true
             speed 1.0
             broadcast broadcast-signals
             signals inferred-signals}}]
  (let [running? (atom true)
        status (status-emitter emit {:source :replay :recording (str dir)})
        gap-us 25000
        worker (future
                 (try
                   (let [rx-id (recording-rx-id dir)
                         ;; Frames are decoded as they are played, so a long
                         ;; recording's events are never all held at once.
                         messages (filterv #(and (= :message (:type %)) (not (:malformed? %)))
                                           (:events (parse-adapter-stream (recording-bytes dir))))
                         sample-count (transduce (mapcat #(message-samples rx-id broadcast %))
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
                                     :let [events (message-events rx-id broadcast signals message)]
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
