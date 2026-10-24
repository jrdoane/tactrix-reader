(ns tactrix-reader.device.cli
  (:require [clojure.pprint :as pprint]
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
           "clojure -M:run pid 05 /dev/cu.usbmodemTAlgJ3P81"]
   :commands {:scan "List detected OpenPort/usbmodem devices."
              :open "Open and close the first detected device, or the given path."
              :status "Report host/device status and conservative vehicle status."
              :direct-info "Report the in-house direct backend discovery surface."
              :vbat "Read adapter-reported vehicle battery voltage from OBD-II pin 16."
              :observe "Passively read bytes from the device for protocol discovery."
              :exchange "Write explicit hex bytes to a disconnected adapter and capture response bytes."
              :probe "Run one named adapter-local probe and return a transcript."
              :obd "Send one read-only OBD-II request over ISO15765. Sends vehicle bus traffic."
              :pid "Read and decode one mode 01 PID by name or hex number. Sends vehicle bus traffic."}})

(defn- parse-pid
  "Reads a PID argument as hex (`0C`) or a name (`engine-rpm`)."
  [s]
  (if (and s (re-matches #"[0-9A-Fa-f]{1,2}" s))
    (Integer/parseInt s 16)
    (keyword s)))

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

        (print-edn (assoc (usage)
                          :error :unknown-command
                          :command command))))
    (finally
      (shutdown-agents))))
