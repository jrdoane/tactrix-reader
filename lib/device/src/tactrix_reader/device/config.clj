(ns tactrix-reader.device.config
  "Local configuration and data, kept in `~/.tactrix-reader` (or
  `$TACTRIX_READER_HOME`). An optional `config.edn` there can set:

  - `:recordings-dir`: where recordings are saved, by default
    `<home>/recordings`.
  - `:vehicles`: names for vehicles, keyed by VIN or calibration ID, such as
    `{\"EA1T002C\" {:description \"2015 Subaru Impreza, manual\"}}`.

  Nothing writes config.edn; it is edited by hand."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn home-dir
  "The directory that holds configuration and recordings."
  []
  (io/file (or (System/getenv "TACTRIX_READER_HOME")
               (str (System/getProperty "user.home") "/.tactrix-reader"))))

(defn read-config
  "Reads config.edn from `home-dir` over the defaults. A missing file gives
  the defaults."
  []
  (let [home (home-dir)
        file (io/file home "config.edn")]
    (merge {:recordings-dir (str (io/file home "recordings"))
            :vehicles {}}
           (when (.exists file)
             (edn/read-string (slurp file))))))

(defn recordings-dir
  "The directory recordings are saved in and listed from."
  []
  (io/file (:recordings-dir (read-config))))

(defn new-recording-name
  "A recording name from the local date and time, such as `2026-10-31-143205`."
  []
  (.format (java.time.LocalDateTime/now)
           (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd-HHmmss")))

(defn resolve-recording-dir
  "The directory for a recording: a bare name such as `my-drive` is in
  `recordings-dir`, and anything containing `/` is used as a path."
  [name-or-path]
  (when (seq name-or-path)
    (if (str/includes? name-or-path "/")
      (io/file name-or-path)
      (io/file (recordings-dir) name-or-path))))

(defn vehicle-description
  "The description config.edn gives `vehicle`, matched by VIN and then by
  each calibration ID, or the vehicle's own `:description`."
  [config {:keys [vin calibration-ids description]}]
  (or (some #(get-in config [:vehicles % :description])
            (cons vin calibration-ids))
      description))
