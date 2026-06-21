(ns tactrix-reader.device
  "Proof-of-life helpers for OpenPort/J2534-style USB adapters.

  This namespace intentionally starts at the host/device layer. It proves that
  macOS can see and open the adapter before any vehicle protocol is attempted."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

(def default-open-timeout-ms 2000)

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
