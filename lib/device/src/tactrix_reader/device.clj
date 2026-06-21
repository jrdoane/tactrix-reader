(ns tactrix-reader.device
  "Proof-of-life helpers for OpenPort/J2534-style USB adapters.

  This namespace intentionally starts at the host/device layer. It proves that
  macOS can see and open the adapter before any vehicle protocol is attempted."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

(def default-open-timeout-ms 2000)
(def default-observe-ms 2000)

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

(defn classify-vbat
  "Classifies an adapter-reported battery voltage.

  This is ready for a future backend that can read Vbat. The current macOS
  serial backend does not know how to ask the OpenPort for this value."
  [volts]
  (cond
    (nil? volts) :unknown
    (>= volts 9.0) :present
    (< volts 3.0) :absent
    :else :unknown))

(defn read-vbat
  "Reads adapter-reported vehicle battery voltage when the active backend
  supports it.

  The macOS serial device is visible as `/dev/cu.usbmodem...`, but the
  OpenPort-local command for Vbat is not currently known. Return data instead
  of throwing so callers can distinguish an unsupported backend from vehicle
  absence."
  ([device-or-path]
   (read-vbat device-or-path {}))
  ([device-or-path _opts]
   (let [path (if (map? device-or-path) (:path device-or-path) device-or-path)]
     {:ok? false
      :backend :mac-serial
      :path path
      :state :unsupported
      :volts nil
      :error :mac-serial-vbat-command-unknown
      :message "OpenPort 2.0 direct serial Vbat command is not known; no bus traffic was sent."})))

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
  and explicit unsupported vehicle states. It does not guess proprietary
  OpenPort commands or send protocol traffic."
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

                   (= :unsupported (:state vehicle-power))
                   (conj :mac-serial-vbat-command-unknown))]
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

(defn- byte->hex [b]
  (format "%02X" (bit-and b 0xFF)))

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
