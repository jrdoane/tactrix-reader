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
           "clojure -M:run vbat"
           "clojure -M:run observe"
           "clojure -M:run observe /dev/cu.usbmodemTAlgJ3P81"]
   :commands {:scan "List detected OpenPort/usbmodem devices."
              :open "Open and close the first detected device, or the given path."
              :status "Report host/device status and conservative vehicle status."
              :vbat "Read adapter-reported vehicle battery voltage when supported."
              :observe "Passively read bytes from the device for protocol discovery."}})

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

        "vbat"
        (print-edn (device/read-vbat (or arg (first (device/scan-devices)))))

        "observe"
        (print-edn (device/observe-device (or arg (first-detected-path))))

        (print-edn (assoc (usage)
                          :error :unknown-command
                          :command command))))
    (finally
      (shutdown-agents))))
