(ns tactrix-reader.device.cli
  (:require [clojure.pprint :as pprint]
            [tactrix-reader.device :as device]))

(defn- print-edn [value]
  (binding [*print-namespace-maps* false]
    (pprint/pprint value)))

(defn- usage []
  {:usage ["clojure -M:run scan"
           "clojure -M:run open"
           "clojure -M:run open /dev/cu.usbmodemTAlgJ3P81"]
   :commands {:scan "List detected OpenPort/usbmodem devices."
              :open "Open and close the first detected device, or the given path."}})

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

        (print-edn (assoc (usage)
                          :error :unknown-command
                          :command command))))
    (finally
      (shutdown-agents))))
