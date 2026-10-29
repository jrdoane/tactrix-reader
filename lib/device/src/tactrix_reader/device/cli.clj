(ns tactrix-reader.device.cli
  (:require [clojure.pprint :as pprint]
            [clojure.string :as str]
            [tactrix-reader.device.adapter :as adapter]
            [tactrix-reader.device.obd :as obd]
            [tactrix-reader.device.pids :as pids]
            [tactrix-reader.device.probe :as probe]
            [tactrix-reader.device.recording :as recording]
            [tactrix-reader.device.serial :as serial]
            [tactrix-reader.device.ssm :as ssm]
            [tactrix-reader.device.uds :as uds]
            [tactrix-reader.device.usb :as usb]))

(defn- print-edn [value]
  (binding [*print-namespace-maps* false]
    (pprint/pprint value)))

(defn- usage []
  {:usage ["clojure -M:run scan"
           "clojure -M:run open"
           "clojure -M:run open /dev/cu.usbmodemTAlgJ3P81"
           "clojure -M:run status"
           "clojure -M:run direct-info"
           "clojure -M:run vbat"
           "clojure -M:run observe"
           "clojure -M:run observe /dev/cu.usbmodemTAlgJ3P81"
           "clojure -M:run exchange 01020304"
           "clojure -M:run exchange 01020304 /dev/cu.usbmodemTAlgJ3P81"
           "clojure -M:run probe adapter-ping 01020304"
           "clojure -M:run probe adapter-ping 01020304 /dev/cu.usbmodemTAlgJ3P81"
           "clojure -M:run obd 0100"
           "clojure -M:run obd 0902 /dev/cu.usbmodemTAlgJ3P81"
           "clojure -M:run pid engine-rpm"
           "clojure -M:run pid 05 /dev/cu.usbmodemTAlgJ3P81"
           "clojure -M:run pids"
           "clojure -M:run pids /dev/cu.usbmodemTAlgJ3P81"
           "clojure -M:run vehicle-info"
           "clojure -M:run vehicle-info /dev/cu.usbmodemTAlgJ3P81"
           "clojure -M:run trouble-codes"
           "clojure -M:run trouble-codes /dev/cu.usbmodemTAlgJ3P81"
           "clojure -M:run ssm-init"
           "clojure -M:run ssm-init /dev/cu.usbmodemTAlgJ3P81"
           "clojure -M:run ssm-read 000022"
           "clojure -M:run ssm-read FF6A04,FF6A05,FF6A06,FF6A07 /dev/cu.usbmodemTAlgJ3P81"
           "clojure -M:run uds-read 1155"
           "clojure -M:run uds-read 1155,1156,1157,0030 /dev/cu.usbmodemTAlgJ3P81"
           "clojure -M:run uds-scan 1000 10FF"
           "clojure -M:run uds-scan 1000 10FF /dev/cu.usbmodemTAlgJ3P81"
           "clojure -M:run record recordings/my-drive"
           "clojure -M:run record recordings/my-drive 120"
           "clojure -M:run record recordings/my-drive 120 /dev/cu.usbmodemTAlgJ3P81"
           "clojure -M:run record-dids recordings/my-drive scanned"
           "clojure -M:run record-dids recordings/my-drive 10A5,10BA,10BE 120 /dev/cu.usbmodemTAlgJ3P81"
           "clojure -M:run export-recording recordings/my-drive"]
   :commands {:scan "List detected OpenPort/usbmodem devices."
              :open "Open and close the first detected device, or the given path."
              :status "Report host/device status and conservative vehicle status."
              :direct-info "Report what the in-house direct backend implements and which operations send vehicle bus traffic."
              :vbat "Read adapter-reported vehicle battery voltage from OBD-II pin 16."
              :observe "Passively read bytes from the device for protocol discovery."
              :exchange "Write explicit hex bytes to a disconnected adapter and capture response bytes."
              :probe "Run one named adapter-local probe and return a transcript."
              :obd "Send one read-only OBD-II request over ISO15765 and decode mode 01, 09, and trouble code replies. Sends vehicle bus traffic."
              :pid "Read and decode one mode 01 PID by name or hex number. Sends vehicle bus traffic."
              :pids "Read every decodable PID the vehicle supports. Sends vehicle bus traffic."
              :vehicle-info "Read the VIN, calibration IDs and verification numbers, ECU name, and SSM ECU ID. Sends vehicle bus traffic."
              :trouble-codes "Read stored, pending, and permanent trouble codes. Never clears them. Sends vehicle bus traffic."
              :ssm-init "Read the ECU ID with Subaru SSM AA. Sends vehicle bus traffic."
              :ssm-read "Read one byte at each comma-separated hex ECU address with Subaru SSM A8. Sends vehicle bus traffic."
              :uds-read "Read each comma-separated hex data identifier with UDS ReadDataByIdentifier (22), one request each. Sends vehicle bus traffic."
              :uds-scan "Read every data identifier from start to end (hex, inclusive) with UDS 22 and report those not out of range. Sends one request per DID on the vehicle bus."
              :record "Record CAN traffic and polled PIDs to a folder, for the given seconds or until Ctrl-C. Sends vehicle bus traffic."
              :record-dids "Record like record, alternating PID requests with UDS 22 requests for the comma-separated hex DIDs, or `scanned` for the DIDs found by uds-scan. Sends vehicle bus traffic."
              :export-recording "Regenerate frames.csv and pids.csv from a recording's raw.bin."}})

(defn- parse-pid
  "Reads a PID argument as hex (`0C`) or a name (`engine-rpm`)."
  [s]
  (if (and s (re-matches #"[0-9A-Fa-f]{1,2}" s))
    (Integer/parseInt s 16)
    (keyword s)))

(defn- parse-ssm-addresses
  "Reads comma-separated hex addresses such as `000022,000023`. Anything that
  is not hex is kept as a string so `ssm-read` reports it."
  [s]
  (when s
    (mapv #(if (re-matches #"[0-9A-Fa-f]{1,6}" %) (Integer/parseInt % 16) %)
          (str/split s #","))))

(defn- parse-dids
  "Reads comma-separated hex data identifiers such as `1155,0030`. Anything
  that is not hex is kept as a string so `read-did` reports it."
  [s]
  (when s
    (mapv #(if (re-matches #"[0-9A-Fa-f]{1,4}" %) (Integer/parseInt % 16) %)
          (str/split s #","))))

(defn- parse-did
  "Reads one hex data identifier such as `10FF`, or nil if it is not one."
  [s]
  (when (and s (re-matches #"[0-9A-Fa-f]{1,4}" s))
    (Integer/parseInt s 16)))

(defn- report-scan-progress
  "Prints each DID that is not out of range, and every 256 DIDs, to stderr
  while `uds-scan` runs."
  [{:keys [did did-hex nrc-name] :as result}]
  (binding [*out* *err*]
    (when-not (= :request-out-of-range nrc-name)
      (println did-hex (or nrc-name (:error result) (get-in result [:data :hex]))))
    (when (= 0xFF (bit-and did 0xFF))
      (println "scanned through" did-hex))))

(defn- first-detected-path []
  (some :path (usb/scan-devices)))

(defn- record!
  "Runs `recording/record` with `opts` for `seconds`, or until Ctrl-C, and
  prints the result."
  [out-dir seconds path opts]
  (let [stop (promise)
        done (promise)]
    ;; Ctrl-C runs shutdown hooks; this one stops the recording and holds the
    ;; JVM open until its files are written.
    (try
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. ^Runnable (fn []
                                             (deliver stop true)
                                             @done)))
      (binding [*out* *err*]
        (println (str "Recording to " out-dir
                      (if seconds (str " for " seconds " seconds") "")
                      ". Press Ctrl-C to stop.")))
      (print-edn (recording/record (or path (first-detected-path))
                                   out-dir
                                   (assoc opts
                                          :duration-ms (some-> seconds Long/parseLong (* 1000))
                                          :stop stop)))
      (flush)
      (finally
        (deliver done true)))))

(defn -main [& args]
  (try
    (let [[command arg] args]
      (case (or command "scan")
        "scan"
        (print-edn {:devices (usb/scan-devices)})

        "open"
        (print-edn (usb/open-device! (or arg (first-detected-path))))

        "status"
        (print-edn (adapter/vehicle-status (or arg (first (usb/scan-devices)))))

        "direct-info"
        (print-edn (adapter/direct-backend-info))

        "vbat"
        (print-edn (adapter/read-vbat (or arg (first (usb/scan-devices)))))

        "observe"
        (print-edn (serial/observe-device (or arg (first-detected-path))))

        "exchange"
        (let [[request-hex path] (rest args)]
          (print-edn (serial/serial-exchange (or path (first-detected-path)) request-hex)))

        "probe"
        (let [[probe-name request-hex path] (rest args)]
          (print-edn (probe/run-probe (or path (first-detected-path)) probe-name request-hex)))

        "obd"
        (let [[request-hex path] (rest args)
              result (obd/obd-request (or path (first-detected-path)) request-hex)
              reply (get-in result [:response :bytes])
              decoded (case (first reply)
                        0x41 (pids/decode-mode-01 reply)
                        0x49 (obd/decode-mode-09 reply)
                        (0x43 0x47 0x4A) (obd/decode-dtc-reply reply)
                        nil)]
          (print-edn (cond-> result
                       decoded (assoc :decoded decoded))))

        "pid"
        (let [[pid path] (rest args)]
          (print-edn (obd/read-pid (or path (first-detected-path)) (parse-pid pid))))

        "pids"
        (print-edn (obd/read-all-pids (or arg (first-detected-path))))

        "vehicle-info"
        (print-edn (obd/read-vehicle-info (or arg (first-detected-path))))

        "trouble-codes"
        (print-edn (obd/read-trouble-codes (or arg (first-detected-path))))

        "ssm-init"
        (print-edn (ssm/ssm-init (or arg (first-detected-path))))

        "ssm-read"
        (let [[addresses path] (rest args)]
          (print-edn (ssm/ssm-read (or path (first-detected-path))
                                   (parse-ssm-addresses addresses))))

        "uds-read"
        (let [[dids path] (rest args)
              path (or path (first-detected-path))]
          (print-edn (mapv #(uds/read-did path %) (or (parse-dids dids) [nil]))))

        "uds-scan"
        (let [[start end path] (rest args)]
          (print-edn (uds/scan-dids (or path (first-detected-path))
                                    (parse-did start)
                                    (parse-did end)
                                    {:on-result report-scan-progress})))

        "record"
        (let [[out-dir seconds path] (rest args)]
          (record! out-dir seconds path {}))

        "record-dids"
        (let [[out-dir dids seconds path] (rest args)]
          (record! out-dir seconds path {:dids (if (= "scanned" dids)
                                                 uds/scanned-dids
                                                 (parse-dids dids))}))

        "export-recording"
        (print-edn (recording/export-recording arg))

        (print-edn (assoc (usage)
                          :error :unknown-command
                          :command command))))
    (finally
      (shutdown-agents))))
