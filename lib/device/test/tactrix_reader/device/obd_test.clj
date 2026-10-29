(ns tactrix-reader.device.obd-test
  (:require [clojure.test :refer [deftest is testing]]
            [tactrix-reader.device.obd :as obd]
            [tactrix-reader.device.openport :as openport]
            [tactrix-reader.device.ssm :as ssm]
            [tactrix-reader.device.test-support :refer [ascii message-bytes]]))

(defn- run-fake-obd
  "Runs obd-request against canned adapter output. Returns the result and the
  bytes written to the adapter."
  [replies request-hex opts]
  (let [output (java.io.ByteArrayOutputStream.)
        input (java.io.ByteArrayInputStream. (byte-array (map unchecked-byte replies)))]
    (with-redefs [openport/open-session (fn [_]
                                          (#'openport/stream-session input output))]
      {:result (obd/obd-request "/dev/cu.usbmodemTAlgJ3P81" request-hex
                                (merge {:drain? false} opts))
       :written (mapv #(bit-and % 0xFF) (.toByteArray output))})))

(def fake-vin (ascii "TESTVIN0123456789"))

(deftest obd-request-validates-before-opening-the-device
  (let [opened? (atom false)]
    (with-redefs [openport/open-session (fn [_] (reset! opened? true))]
      (is (= :missing-path (:error (obd/obd-request nil "0100"))))
      (is (= :invalid-hex-request (:error (obd/obd-request "/dev/cu.usbmodemTAlgJ3P81" "GG"))))
      (is (= {:ok? false
              :backend :mac-serial
              :path "/dev/cu.usbmodemTAlgJ3P81"
              :protocol :iso15765
              :tx-id 0x7E0
              :rx-id 0x7E8
              :error :service-not-read-only
              :service 4}
             (obd/obd-request "/dev/cu.usbmodemTAlgJ3P81" "04")))
      (is (false? @opened?)))))

(deftest obd-request-reads-multi-frame-reply
  (let [{:keys [result written]}
        (run-fake-obd (concat (ascii "aro\r\naro\r\narf6 9 0\r\naro\r\n")
                              (message-bytes 6 0x10 100 0x7E0 [])
                              (message-bytes 6 0x80 200 0x7E8 [])
                              (message-bytes 6 0x40 300 0x7E8 (concat [0x49 2 1] fake-vin))
                              (ascii "aro\r\n"))
                      "0902"
                      {})]
    (is (= (vec (concat (ascii "atc6\r\n")
                        (ascii "ato6 0 500000\r\n")
                        (ascii "atf6 3 64 4\r\n")
                        [0xFF 0xFF 0xFF 0xFF 0 0 7 0xE8 0 0 7 0xE0]
                        (ascii "att6 6 64\r\n")
                        [0 0 7 0xE0 9 2]
                        (ascii "atc6\r\n")))
           written))
    (is (= {:ok? true
            :backend :mac-serial
            :path "/dev/cu.usbmodemTAlgJ3P81"
            :protocol :iso15765
            :tx-id 0x7E0
            :rx-id 0x7E8
            :request {:bytes [9 2]
                      :hex ["09" "02"]}
            :response {:bytes (vec (concat [0x49 2 1] fake-vin))
                       :hex (mapv #(format "%02X" %) (concat [0x49 2 1] fake-vin))
                       :status 0x40
                       :timestamp-us 300}}
           result))))

(deftest obd-request-waits-through-response-pending
  (let [{:keys [result]}
        (run-fake-obd (concat (ascii "aro\r\naro\r\narf6 9 0\r\naro\r\n")
                              (message-bytes 6 0x40 100 0x7E8 [0x7F 1 0x78])
                              (message-bytes 6 0x40 200 0x7E8 [0x41 0 0xBE 0x3F 0xA8 0x13])
                              (ascii "aro\r\n"))
                      "0100"
                      {})]
    (is (true? (:ok? result)))
    (is (= ["41" "00" "BE" "3F" "A8" "13"] (get-in result [:response :hex])))))

(deftest obd-request-reports-negative-responses
  (let [{:keys [result]}
        (run-fake-obd (concat (ascii "aro\r\naro\r\narf6 9 0\r\naro\r\n")
                              (message-bytes 6 0x40 100 0x7E8 [0x7F 9 0x31])
                              (ascii "aro\r\n"))
                      "0902"
                      {})]
    (is (= {:ok? false
            :error :negative-response
            :nrc 0x31}
           (select-keys result [:ok? :error :nrc])))))

(deftest obd-request-reports-adapter-errors-and-closes-the-channel
  (let [{:keys [result written]}
        (run-fake-obd (ascii "aro\r\nare 20\r\naro\r\n") "0100" {})]
    (is (= {:ok? false
            :step :connect
            :command "ato6 0 500000"
            :reply "are 20"
            :error :adapter-error
            :adapter/error-code 20}
           (select-keys result [:ok? :step :command :reply :error :adapter/error-code])))
    (is (= (vec (concat (ascii "atc6\r\n")
                        (ascii "ato6 0 500000\r\n")
                        (ascii "atc6\r\n")))
           written))))

(deftest obd-request-times-out-without-a-reply
  (let [{:keys [result]}
        (run-fake-obd (ascii "aro\r\naro\r\narf6 9 0\r\naro\r\naro\r\n")
                      "0100"
                      {:timeout-ms 50})]
    (is (= {:ok? false
            :step :response
            :error :timeout}
           (select-keys result [:ok? :step :error])))))

(deftest read-pid-requests-and-decodes
  (with-redefs [obd/obd-request (fn [path request-hex _opts]
                                  (is (= "/dev/cu.usbmodemTAlgJ3P81" path))
                                  (is (= "010C" request-hex))
                                  {:ok? true
                                   :response {:bytes [0x41 0x0C 0x0C 0x80]}})]
    (is (= {:ok? true :pid 0x0C :name :engine-rpm :value 800.0 :unit :rpm}
           (select-keys (obd/read-pid "/dev/cu.usbmodemTAlgJ3P81" :engine-rpm)
                        [:ok? :pid :name :value :unit])))
    (is (= 800.0 (:value (obd/read-pid "/dev/cu.usbmodemTAlgJ3P81" 0x0C))))))

(deftest read-pid-passes-through-request-failures
  (with-redefs [obd/obd-request (fn [& _] {:ok? false :error :timeout})]
    (is (= {:ok? false :error :timeout}
           (obd/read-pid "/dev/cu.usbmodemTAlgJ3P81" :coolant-temp)))))

(deftest read-pid-rejects-unknown-names-without-sending
  (with-redefs [obd/obd-request (fn [& _] (throw (ex-info "should not send" {})))]
    (let [result (obd/read-pid "/dev/cu.usbmodemTAlgJ3P81" :warp-drive)]
      (is (= :unknown-pid (:error result)))
      (is (some #{:engine-rpm} (:known-pids result))))))

(defn- fake-read-pid
  "Returns canned read-pid results by PID and records which PIDs were read."
  [requested results]
  (fn [_path pid _opts]
    (swap! requested conj pid)
    (get results pid {:ok? false :error :timeout})))

(deftest read-all-pids-reads-only-supported-pids
  (let [requested (atom [])]
    (with-redefs [obd/read-pid (fake-read-pid
                                requested
                                {0x00 {:ok? true :value [0x05 0x0C 0x20]}
                                 0x20 {:ok? true :value [0x2F]}
                                 0x05 {:ok? true :pid 0x05 :name :coolant-temp :value 73 :unit :celsius}
                                 0x0C {:ok? true :pid 0x0C :name :engine-rpm :value 682.0 :unit :rpm}})]
      (let [result (obd/read-all-pids "/dev/cu.usbmodemTAlgJ3P81")]
        (is (= [0x00 0x20 0x05 0x0C 0x2F] @requested))
        (is (= {:ok? false
                :supported-hex ["05" "0C" "20" "2F"]
                :values [{:pid 0x05 :name :coolant-temp :value 73 :unit :celsius}
                         {:pid 0x0C :name :engine-rpm :value 682.0 :unit :rpm}]
                :errors [{:pid 0x2F :name :fuel-level :error :timeout}]}
               (dissoc result :not-supported)))
        (is (some #{0x5C} (:not-supported result)))
        (is (not-any? #{0x05 0x0C 0x2F} (:not-supported result)))))))

(deftest read-all-pids-stops-when-bitmap-read-fails
  (let [requested (atom [])]
    (with-redefs [obd/read-pid (fake-read-pid requested {})]
      (is (= {:ok? false
              :error :timeout
              :supported []}
             (obd/read-all-pids "/dev/cu.usbmodemTAlgJ3P81")))
      (is (= [0x00] @requested)))))

(def ^:private fake-calibration-id
  (concat (ascii "EA1T002C") (repeat 8 0)))

(deftest decodes-mode-09-vehicle-information
  (is (= {:ok? true :name :vin :value "TESTVIN0123456789"}
         (obd/decode-mode-09 (concat [0x49 2 1] fake-vin))))
  (is (= {:ok? true :name :calibration-ids :value ["EA1T002C" "CAL2"]}
         (obd/decode-mode-09 (concat [0x49 4 2] fake-calibration-id
                                     (ascii "CAL2") (repeat 12 0)))))
  (is (= {:ok? true :name :ecu-name :value "ECM-EngineControl"}
         (obd/decode-mode-09 (concat [0x49 0x0A 1] (ascii "ECM") [0] (ascii "-EngineControl") [0 0]))))
  (is (= :unknown-info-type (:error (obd/decode-mode-09 [0x49 0x0B 1 0x12 0x34]))))
  (is (= :not-mode-09-reply (:error (obd/decode-mode-09 [0x41 0x0C 0x0C 0x80])))))

;; Replies from the project's 2015 Impreza.
(deftest decodes-mode-09-support-and-calibration-checks
  (is (= {:ok? true :name :supported-info-types :value [0x02 0x04 0x06 0x08 0x0A]}
         (obd/decode-mode-09 [0x49 0x00 0x55 0x40 0x00 0x00])))
  (is (= {:ok? true :name :calibration-verification-numbers :value ["9EB33551"]}
         (obd/decode-mode-09 [0x49 0x06 0x01 0x9E 0xB3 0x35 0x51])))
  (is (= {:ok? true
          :name :in-use-performance
          :value {:general-denominator 4
                  :ignition-cycles 19
                  :monitors {:catalyst-bank-1 {:completions 0 :conditions 0}
                             :catalyst-bank-2 {:completions 0 :conditions 0}
                             :oxygen-sensor-bank-1 {:completions 4 :conditions 4}
                             :oxygen-sensor-bank-2 {:completions 0 :conditions 0}
                             :egr-system {:completions 15 :conditions 4}
                             :secondary-air {:completions 0 :conditions 0}
                             :evaporative-system {:completions 1 :conditions 0}
                             :secondary-oxygen-sensor-bank-1 {:completions 7 :conditions 4}
                             :secondary-oxygen-sensor-bank-2 {:completions 0 :conditions 0}}}}
         (obd/decode-mode-09 [0x49 0x08 0x14
                              0 4 0 19 0 0 0 0 0 0 0 0 0 4 0 4 0 0 0 0
                              0 15 0 4 0 0 0 0 0 1 0 0 0 7 0 4 0 0 0 0])))
  (testing "an older ECU sends 16 counters, without the secondary oxygen sensors"
    (is (= #{:catalyst-bank-1 :catalyst-bank-2 :oxygen-sensor-bank-1 :oxygen-sensor-bank-2
             :egr-system :secondary-air :evaporative-system}
           (-> (obd/decode-mode-09 (into [0x49 0x08 0x10] (repeat 32 0)))
               (get-in [:value :monitors])
               keys
               set)))))

(deftest read-vehicle-info-collects-each-item-and-its-errors
  (let [requested (atom [])
        replies {"0902" {:ok? true :response {:bytes (vec (concat [0x49 2 1] fake-vin))}}
                 "0904" {:ok? true :response {:bytes (vec (concat [0x49 4 1] fake-calibration-id))}}
                 "0906" {:ok? true :response {:bytes [0x49 6 1 0x9E 0xB3 0x35 0x51]}}
                 "090A" {:ok? false :error :negative-response :nrc 0x12}}]
    (with-redefs [obd/obd-request (fn [_path request-hex _opts]
                                    (swap! requested conj request-hex)
                                    (get replies request-hex))
                  ssm/ssm-init (fn [_path _opts]
                                 (swap! requested conj "AA")
                                 {:ok? false :step :response :error :timeout})]
      (is (= {:ok? true
              :path "/dev/cu.usbmodemTAlgJ3P81"
              :vin "TESTVIN0123456789"
              :calibration-ids ["EA1T002C"]
              :calibration-verification-numbers ["9EB33551"]
              :ecu-name nil
              :ecu-id nil
              :errors {:ecu-name {:error :negative-response :nrc 0x12}
                       :ecu-id {:error :timeout}}}
             (obd/read-vehicle-info "/dev/cu.usbmodemTAlgJ3P81")))
      (is (= ["0902" "0904" "0906" "090A" "AA"] @requested)))))

(deftest decodes-trouble-code-replies
  (is (= {:ok? true :name :stored-trouble-codes :value ["P0133" "P0420"]}
         (obd/decode-dtc-reply [0x43 0x02 0x01 0x33 0x04 0x20])))
  (is (= {:ok? true :name :pending-trouble-codes :value ["C0035" "B1A2F" "U0100"]}
         (obd/decode-dtc-reply [0x47 0x03 0x40 0x35 0x9A 0x2F 0xC1 0x00])))
  (testing "the project's Impreza, with no codes"
    (is (= {:ok? true :name :permanent-trouble-codes :value []}
           (obd/decode-dtc-reply [0x4A 0x00]))))
  (is (= :short-reply (:error (obd/decode-dtc-reply [0x43]))))
  (is (= :not-dtc-reply (:error (obd/decode-dtc-reply [0x41 0x0C 0x0C 0x80])))))

(deftest read-trouble-codes-reads-each-list-and-never-clears
  (let [requested (atom [])
        replies {"03" {:ok? true :response {:bytes [0x43 0x01 0x01 0x33]}}
                 "07" {:ok? true :response {:bytes [0x47 0x00]}}
                 "0A" {:ok? false :error :negative-response :nrc 0x11}}]
    (with-redefs [obd/obd-request (fn [_path request-hex _opts]
                                    (swap! requested conj request-hex)
                                    (get replies request-hex))]
      (is (= {:ok? true
              :path "/dev/cu.usbmodemTAlgJ3P81"
              :stored ["P0133"]
              :pending []
              :permanent nil
              :errors {:permanent {:error :negative-response :nrc 0x11}}}
             (obd/read-trouble-codes "/dev/cu.usbmodemTAlgJ3P81")))
      (is (= ["03" "07" "0A"] @requested))))
  (with-redefs [obd/obd-request (fn [& _] {:ok? false :error :timeout})]
    (is (= {:ok? false :error :timeout}
           (select-keys (obd/read-trouble-codes "/dev/cu.usbmodemTAlgJ3P81") [:ok? :error]))))
  (is (= {:ok? false :error :missing-path}
         (obd/read-trouble-codes nil))))

(deftest read-vehicle-info-reports-when-nothing-answers
  (with-redefs [obd/obd-request (fn [& _] {:ok? false :error :not-found-or-permission-denied})
                ssm/ssm-init (fn [& _] {:ok? false :error :not-found-or-permission-denied})]
    (is (= {:ok? false
            :error :not-found-or-permission-denied}
           (select-keys (obd/read-vehicle-info "/dev/cu.usbmodemTAlgJ3P81") [:ok? :error]))))
  (is (= {:ok? false :error :missing-path}
         (obd/read-vehicle-info nil))))

