(ns tactrix-reader.device.cli
  (:require [clojure.pprint :as pprint]
            [clojure.string :as str]
            [tactrix-reader.device :as device]))

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
           "clojure -M:run ssm-init"
           "clojure -M:run ssm-init /dev/cu.usbmodemTAlgJ3P81"
           "clojure -M:run ssm-read 000022"
           "clojure -M:run ssm-read FF6A04,FF6A05,FF6A06,FF6A07 /dev/cu.usbmodemTAlgJ3P81"
           "clojure -M:run record recordings/my-drive"
           "clojure -M:run record recordings/my-drive 120 /dev/cu.usbmodemTAlgJ3P81"
           "clojure -M:run export-recording recordings/my-drive"]
   :commands {:scan "List detected OpenPort/usbmodem devices."
              :open "Open and close the first detected device, or the given path."
              :status "Report host/device status and conservative vehicle status."
              :direct-info "Report what the in-house direct backend implements and which operations send vehicle bus traffic."
              :vbat "Read adapter-reported vehicle battery voltage from OBD-II pin 16."
              :observe "Passively read bytes from the device for protocol discovery."
              :exchange "Write explicit hex bytes to a disconnected adapter and capture response bytes."
              :probe "Run one named adapter-local probe and return a transcript."
              :obd "Send one read-only OBD-II request over ISO15765. Sends vehicle bus traffic."
              :pid "Read and decode one mode 01 PID by name or hex number. Sends vehicle bus traffic."
              :pids "Read every decodable PID the vehicle supports. Sends vehicle bus traffic."
              :vehicle-info "Read the VIN, calibration IDs, ECU name, and SSM ECU ID. Sends vehicle bus traffic."
              :ssm-init "Read the ECU ID with Subaru SSM AA. Sends vehicle bus traffic."
              :ssm-read "Read one byte at each comma-separated hex ECU address with Subaru SSM A8. Sends vehicle bus traffic."
              :record "Record CAN traffic and polled PIDs to a folder (default 60 seconds). Sends vehicle bus traffic."
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

(defn- first-detected-path []
  (some :path (device/scan-devices)))

(defn -main [& args]
  (try
    (let [[command arg] args]
      (case (or command "scan")
        "scan"
        (print-edn {:devices (device/scan-devices)})

        "open"
        (print-edn (device/open-device! (or arg (first-detected-path))))

        "status"
        (print-edn (device/vehicle-status (or arg (first (device/scan-devices)))))

        "direct-info"
        (print-edn (device/direct-backend-info))

        "vbat"
        (print-edn (device/read-vbat (or arg (first (device/scan-devices)))))

        "observe"
        (print-edn (device/observe-device (or arg (first-detected-path))))

        "exchange"
        (let [[request-hex path] (rest args)]
          (print-edn (device/serial-exchange (or path (first-detected-path)) request-hex)))

        "probe"
        (let [[probe-name request-hex path] (rest args)]
          (print-edn (device/run-probe (or path (first-detected-path)) probe-name request-hex)))

        "obd"
        (let [[request-hex path] (rest args)]
          (print-edn (device/obd-request (or path (first-detected-path)) request-hex)))

        "pid"
        (let [[pid path] (rest args)]
          (print-edn (device/read-pid (or path (first-detected-path)) (parse-pid pid))))

        "pids"
        (print-edn (device/read-all-pids (or arg (first-detected-path))))

        "vehicle-info"
        (print-edn (device/read-vehicle-info (or arg (first-detected-path))))

        "ssm-init"
        (print-edn (device/ssm-init (or arg (first-detected-path))))

        "ssm-read"
        (let [[addresses path] (rest args)]
          (print-edn (device/ssm-read (or path (first-detected-path))
                                      (parse-ssm-addresses addresses))))

        "record"
        (let [[out-dir seconds path] (rest args)]
          (print-edn (device/record (or path (first-detected-path))
                                    out-dir
                                    (cond-> {}
                                      seconds (assoc :duration-ms (* 1000 (Long/parseLong seconds)))))))

        "export-recording"
        (print-edn (device/export-recording arg))

        (print-edn (assoc (usage)
                          :error :unknown-command
                          :command command))))
    (finally
      (shutdown-agents))))
