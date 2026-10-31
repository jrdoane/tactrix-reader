(ns tactrix-reader.device.config-test
  (:require [clojure.test :refer [deftest is]]
            [tactrix-reader.device.config :as config]
            [tactrix-reader.device.test-support :refer [temp-dir]]))

(deftest config-defaults-to-the-home-directory
  (let [home (temp-dir)]
    (with-redefs [config/home-dir (constantly home)]
      (is (= {:recordings-dir (str (java.io.File. home "recordings")) :vehicles {}}
             (config/read-config)))
      (is (= (java.io.File. home "recordings/my-drive")
             (config/resolve-recording-dir "my-drive")))
      (is (= (java.io.File. "/tmp/elsewhere/my-drive")
             (config/resolve-recording-dir "/tmp/elsewhere/my-drive")))
      (is (= (java.io.File. "recordings/my-drive")
             (config/resolve-recording-dir "recordings/my-drive")))
      (is (nil? (config/resolve-recording-dir nil))))))

(deftest config-edn-overrides-the-defaults
  (let [home (temp-dir)]
    (spit (java.io.File. home "config.edn")
          (pr-str {:recordings-dir "/data/recordings"
                   :vehicles {"EA1T002C" {:description "2015 Subaru Impreza"}}}))
    (with-redefs [config/home-dir (constantly home)]
      (let [cfg (config/read-config)]
        (is (= (java.io.File. "/data/recordings") (config/recordings-dir)))
        (is (= "2015 Subaru Impreza"
               (config/vehicle-description cfg {:vin "JF1" :calibration-ids ["EA1T002C"]})))
        (is (= "Own description"
               (config/vehicle-description cfg {:vin "JF2" :description "Own description"})))
        (is (nil? (config/vehicle-description cfg {})))))))

(deftest recording-names-are-local-timestamps
  (is (re-matches #"\d{4}-\d{2}-\d{2}-\d{6}" (config/new-recording-name))))
