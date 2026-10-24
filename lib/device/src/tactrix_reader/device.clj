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
                 :read-vbat]
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
