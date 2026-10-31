(ns tactrix-reader.device.recording-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tactrix-reader.device.openport :as openport]
            [tactrix-reader.device.recording :as recording]
            [tactrix-reader.device.test-support :refer [ascii fake-adapter fake-vehicle-responder
                                                        message-bytes temp-dir wait-for]]))

(deftest export-recording-writes-csvs-from-raw-bytes
  (let [dir (temp-dir)
        raw (concat (ascii "aro\r\n")
                    (message-bytes 5 0 1000000 0x372 [0 0xA8 0 0 0 0 0 0])
                    (message-bytes 5 0 1000246 0x7E0 [2 1 0x0C 0 0 0 0 0])
                    (ascii "aro\r\n")
                    (message-bytes 5 0 1001362 0x7E8 [4 0x41 0x0C 0x0A 0x3F 0 0 0])
                    (message-bytes 5 0 1002000 0x7E8 [6 0x41 0x03 0x02 0x00 0 0 0])
                    (ascii "arf5 1 0\r\n"))]
    (java.nio.file.Files/write (.toPath (java.io.File. dir "raw.bin"))
                               (byte-array (map unchecked-byte raw))
                               (make-array java.nio.file.OpenOption 0))
    (spit (java.io.File. dir "meta.edn") (pr-str {:polling {:rx-id 0x7E8}}))
    (is (= {:frames 4
            :pid-samples 2
            :reply-lines {"aro" 2 "arf5" 1}}
           (recording/export-recording (str dir))))
    (is (= ["t_s,timestamp_us,channel,status,id,dlc,data"
            "0.000000,1000000,5,00,372,8,00 A8 00 00 00 00 00 00"
            "0.000246,1000246,5,00,7E0,8,02 01 0C 00 00 00 00 00"
            "0.001362,1001362,5,00,7E8,8,04 41 0C 0A 3F 00 00 00"
            "0.002000,1002000,5,00,7E8,8,06 41 03 02 00 00 00 00"]
           (str/split-lines (slurp (java.io.File. dir "frames.csv")))))
    (testing "map values are quoted"
      (is (= ["t_s,timestamp_us,pid,name,value,unit"
              "0.001362,1001362,0C,engine-rpm,655.75,rpm"
              "0.002000,1002000,03,fuel-system-status,\"{:system-1 :closed-loop, :system-2 :none}\","]
             (str/split-lines (slurp (java.io.File. dir "pids.csv"))))))))

(deftest record-validates-before-opening-the-device
  (with-redefs [openport/open-session (fn [_] (throw (ex-info "should not open" {})))]
    (is (= {:ok? false :error :missing-path}
           (recording/record nil "recordings/x")))
    (is (= {:ok? false :path "/dev/cu.usbmodemTAlgJ3P81" :error :missing-out-dir}
           (recording/record "/dev/cu.usbmodemTAlgJ3P81" nil)))
    (doseq [dids [nil [0x10000] ["10A5"]]]
      (is (= {:ok? false :path "/dev/cu.usbmodemTAlgJ3P81" :error :invalid-dids :dids dids}
             (recording/record "/dev/cu.usbmodemTAlgJ3P81" "recordings/x" {:dids dids}))))))

(deftest export-recording-writes-did-replies
  (let [dir (temp-dir)
        raw (concat (message-bytes 5 0 1000000 0x7E0 [3 0x22 0x10 0xA5 0 0 0 0])
                    (message-bytes 5 0 1000500 0x7E8 [4 0x62 0x10 0xA5 0x80 0 0 0])
                    (message-bytes 5 0 1001000 0x7E8 [0x10 0x09 0x62 0x10 0x1E 0x6E 0x98 0x00])
                    (message-bytes 5 0 1002000 0x7E8 [7 0x62 0x10 0x00 0xFF 0xC0 0x00 0x0D]))]
    (java.nio.file.Files/write (.toPath (java.io.File. dir "raw.bin"))
                               (byte-array (map unchecked-byte raw))
                               (make-array java.nio.file.OpenOption 0))
    (is (= 2 (:did-samples (recording/export-recording (str dir) 0x7E8))))
    (is (= ["t_s,timestamp_us,did,data"
            "0.000500,1000500,10A5,80"
            "0.002000,1002000,1000,FF C0 00 0D"]
           (str/split-lines (slurp (java.io.File. dir "dids.csv")))))))

(deftest export-recording-writes-no-dids-csv-without-did-replies
  (let [dir (temp-dir)]
    (java.nio.file.Files/write (.toPath (java.io.File. dir "raw.bin"))
                               (byte-array (map unchecked-byte
                                                (message-bytes 5 0 1000000 0x7E8 [4 0x41 0x0C 0x0C 0x80 0 0 0])))
                               (make-array java.nio.file.OpenOption 0))
    (is (nil? (:did-samples (recording/export-recording (str dir) 0x7E8))))
    (is (not (.exists (java.io.File. dir "dids.csv"))))))

(deftest record-captures-a-polling-session
  (let [dir (temp-dir)]
    (with-redefs [openport/open-session (fn [_] (fake-adapter (fake-vehicle-responder)))]
      (let [result (recording/record "/dev/fake" (str dir) {:duration-ms 100
                                                             :pids [0x0C 0x05]
                                                             :interval-ms 10})]
        (is (true? (:ok? result)))
        (is (= :duration (:stopped-by result)))
        (is (pos? (:request-count result)))
        (is (= (:request-count result) (get-in result [:counts :pid-samples])))
        (is (= "1.17.4877" (get-in (edn/read-string (slurp (java.io.File. dir "meta.edn")))
                                   [:adapter :firmware])))
        (is (= ["engine-rpm" "800.0" "coolant-temp" "45"]
               (->> (str/split-lines (slurp (java.io.File. dir "pids.csv")))
                    (drop 1)
                    (take 2)
                    (mapcat #(let [[_ _ _ name value] (str/split % #",")] [name value])))))))))

(deftest record-saves-the-vehicle-and-note
  (let [dir (temp-dir)
        vehicle {:vin "JF1GPAA60F8000000" :calibration-ids ["EA1T002C"]}]
    (with-redefs [openport/open-session (fn [_] (fake-adapter (fake-vehicle-responder)))]
      (recording/record "/dev/fake" (str dir) {:duration-ms 50 :pids [0x0C] :interval-ms 10
                                               :vehicle vehicle :note "idle"}))
    (let [meta (edn/read-string (slurp (java.io.File. dir "meta.edn")))]
      (is (= vehicle (:vehicle meta)))
      (is (= "idle" (:note meta)))
      (is (= 0x7E8 (get-in meta [:polling :rx-id])))
      (is (pos? (get-in meta [:polling :request-count]))))))

(deftest list-recordings-describes-each-recording-newest-first
  (let [root (temp-dir)
        write! (fn [name meta]
                 (let [dir (java.io.File. root name)]
                   (.mkdirs dir)
                   (spit (java.io.File. dir "raw.bin") "")
                   (when meta
                     (spit (java.io.File. dir "meta.edn") (pr-str meta)))))]
    (write! "old" {:recorded-at "2026-10-24T19:30:41Z" :duration-ms 60000 :stopped-by :duration
                   :vehicle {:description "Impreza" :state "idling"}})
    (write! "new" {:recorded-at "2026-10-30T12:04:43Z" :in-progress? true
                   :vehicle {:vin "JF1" :calibration-ids ["EA1T002C"]}
                   :polling {:dids ["10A5"]} :note "drive"})
    (write! "broken" nil)
    (.mkdirs (java.io.File. root "empty"))
    (let [listed (recording/list-recordings root)
          by-name (into {} (map (juxt :name identity)) listed)]
      (is (= #{"old" "new" "broken"} (set (keys by-name))))
      (is (= ["new" "old"] (filter #{"new" "old"} (map :name listed))))
      (is (= {:name "old" :dir (str (java.io.File. root "old")) :recorded-at "2026-10-24T19:30:41Z"
              :raw-bytes 0 :dids? false :duration-ms 60000 :stopped-by :duration
              :vehicle {:description "Impreza" :state "idling"} :note "idling"}
             (by-name "old")))
      (is (= {:dids? true :in-progress? true :note "drive"}
             (select-keys (by-name "new") [:dids? :in-progress? :note])))
      (is (true? (:incomplete? (by-name "broken")))))))

(deftest record-alternates-pid-and-did-requests
  (let [dir (temp-dir)]
    (with-redefs [openport/open-session (fn [_] (fake-adapter (fake-vehicle-responder)))]
      (let [result (recording/record "/dev/fake" (str dir) {:duration-ms 150
                                                             :pids [0x0C]
                                                             :dids [0x10A5 0x1000]
                                                             :interval-ms 10})
            meta (edn/read-string (slurp (java.io.File. dir "meta.edn")))
            did-rows (->> (str/split-lines (slurp (java.io.File. dir "dids.csv")))
                          (drop 1)
                          (mapv #(let [[_ _ did data] (str/split % #",")] [did data])))]
        (is (true? (:ok? result)))
        (is (= (:request-count result)
               (+ (get-in result [:counts :pid-samples]) (get-in result [:counts :did-samples]))))
        (is (#{0 1} (- (get-in result [:counts :pid-samples]) (get-in result [:counts :did-samples]))))
        (is (= [["10A5" "80"] ["1000" "FF C0 00 0D"] ["10A5" "80"]] (take 3 did-rows)))
        (is (= ["10A5" "1000"] (get-in meta [:polling :dids])))
        (is (contains? (:files meta) "dids.csv"))))))

(deftest record-streams-to-disk-until-stopped
  (let [dir (temp-dir)
        raw-file (java.io.File. dir "raw.bin")
        stop (promise)]
    (with-redefs [openport/open-session (fn [_] (fake-adapter (fake-vehicle-responder)))]
      (let [recording (future (recording/record "/dev/fake" (str dir) {:duration-ms nil
                                                                       :stop stop
                                                                       :pids [0x0C]
                                                                       :interval-ms 10}))]
        (is (wait-for #(< 200 (.length raw-file)) 3000))
        (is (true? (:in-progress? (edn/read-string (slurp (java.io.File. dir "meta.edn"))))))
        (deliver stop true)
        (let [result (deref recording 3000 nil)]
          (is (true? (:ok? result)))
          (is (= :stop (:stopped-by result)))
          (is (pos? (get-in result [:counts :pid-samples])))
          (let [meta (edn/read-string (slurp (java.io.File. dir "meta.edn")))]
            (is (= :stop (:stopped-by meta)))
            (is (nil? (:in-progress? meta)))))))))

(defn- failing-input
  "Wraps a fake session so reads throw, as when the adapter is unplugged,
  once `failed?` is true."
  [session failed?]
  (let [^java.io.InputStream input (:input session)
        check! #(when @failed? (throw (java.io.IOException. "Device not configured")))]
    (assoc session :input (proxy [java.io.InputStream] []
                            (available [] (check!) (.available input))
                            (read
                              ([] (check!) (.read input))
                              ([^bytes buffer] (check!) (.read input buffer))
                              ([^bytes buffer offset length] (check!) (.read input buffer offset length)))))))

(deftest record-saves-what-it-captured-when-the-adapter-fails
  (let [dir (temp-dir)
        failed? (atom false)]
    (with-redefs [openport/open-session (fn [_] (failing-input (fake-adapter (fake-vehicle-responder)) failed?))]
      (let [recording (future (recording/record "/dev/fake" (str dir) {:duration-ms nil
                                                                       :pids [0x0C]
                                                                       :interval-ms 10}))]
        (is (wait-for #(< 200 (.length (java.io.File. dir "raw.bin"))) 3000))
        (reset! failed? true)
        (let [result (deref recording 3000 nil)]
          (is (false? (:ok? result)))
          (is (= :record-failed (:error result)))
          (is (= :error (:stopped-by result)))
          (is (pos? (get-in result [:counts :pid-samples])))
          (is (= "Device not configured"
                 (:error (edn/read-string (slurp (java.io.File. dir "meta.edn"))))))
          (is (< 1 (count (str/split-lines (slurp (java.io.File. dir "pids.csv")))))))))))

(deftest record-writes-nothing-when-the-channel-does-not-open
  (let [dir (java.io.File. (temp-dir) "never")
        respond (fake-vehicle-responder)]
    (with-redefs [openport/open-session (fn [_] (fake-adapter (fn [line payload]
                                                                (if (str/starts-with? line "ato")
                                                                  (ascii "are 8\r\n")
                                                                  (respond line payload)))))]
      (let [result (recording/record "/dev/fake" (str dir) {:duration-ms 100})]
        (is (= {:ok? false :path "/dev/fake" :step :connect :reply "are 8" :error :connect-failed}
               result))
        (is (not (.exists dir)))))))


(deftest vehicle-meta-keeps-the-items-that-were-read
  (is (= {:vin "JF1GPAA60F8000000" :calibration-ids ["EA1T002C"]}
         (recording/vehicle-meta {:ok? true :path "/dev/fake" :vin "JF1GPAA60F8000000"
                                  :calibration-ids ["EA1T002C"] :ecu-id nil
                                  :errors {:ecu-id :timeout}})))
  (is (nil? (recording/vehicle-meta {:ok? false :error :missing-path}))))
