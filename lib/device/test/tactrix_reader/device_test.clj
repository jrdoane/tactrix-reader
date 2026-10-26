(ns tactrix-reader.device-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [are deftest is testing]]
            [tactrix-reader.device :as device]))

(def sample-ioreg
  "  | +-o OpenPort 2.0@14100000  <class IOUSBHostDevice, id 0x100189c91, registered, matched, active, busy 0 (236 ms), retain 41>
  |     {
  |       \"idProduct\" = 52301
  |       \"USB Product Name\" = \"OpenPort 2_0\"
  |       \"kUSBSerialNumberString\" = \"TAlgJ3P8\"
  |       \"USB Vendor Name\" = \"Tactrix\"
  |       \"idVendor\" = 1027
  |       \"kUSBProductString\" = \"OpenPort 2.0\"
  |       \"USB Serial Number\" = \"TAlgJ3P8\"
  |       \"kUSBVendorString\" = \"Tactrix\"
  |     }")

(deftest usbmodem-path-detection
  (testing "detects macOS usbmodem serial paths"
    (is (true? (device/usbmodem-path? "/dev/cu.usbmodemTAlgJ3P81")))
    (is (= "TAlgJ3P81" (device/serial-token "/dev/cu.usbmodemTAlgJ3P81")))
    (is (true? (device/candidate-serial? "TAlgJ3P8" "/dev/cu.usbmodemTAlgJ3P81")))
    (is (false? (device/usbmodem-path? "/dev/cu.Bluetooth-Incoming-Port")))))

(deftest parses-ioreg-openport-device
  (let [device (first (device/parse-ioreg-usb-devices sample-ioreg))]
    (is (= "OpenPort 2.0" (:name device)))
    (is (= "Tactrix" (:usb-vendor-name device)))
    (is (= "OpenPort 2_0" (:usb-product-name device)))
    (is (= "TAlgJ3P8" (:usb-serial-number device)))
    (is (= 1027 (:idvendor device)))
    (is (= 52301 (:idproduct device)))))

(deftest open-device-reports-missing-path
  (is (= {:ok? false :error :missing-path}
         (device/open-device! nil))))

(deftest classifies-vbat-thresholds
  (is (= :unknown (device/classify-vbat nil)))
  (is (= :absent (device/classify-vbat 0.0)))
  (is (= :absent (device/classify-vbat 2.99)))
  (is (= :unknown (device/classify-vbat 3.0)))
  (is (= :unknown (device/classify-vbat 8.99)))
  (is (= :present (device/classify-vbat 9.0)))
  (is (= :present (device/classify-vbat 12.4))))

(defn- fake-exchange [response]
  (fn [path request-hex _opts]
    (is (= "/dev/cu.usbmodemTAlgJ3P81" path))
    (is (= "6174722031360D0A" request-hex))
    {:ok? true
     :path path
     :response {:bytes (mapv int response)}}))

(deftest parses-pin-voltage-responses
  (is (= {:ok? true :pin 16 :millivolts 12400}
         (device/parse-pin-voltage-response "arr 16 12400\r\n")))
  (is (= {:ok? false :error :adapter-error :adapter/error-code 19}
         (device/parse-pin-voltage-response "are 19\r\n")))
  (is (= {:ok? false :error :unexpected-response}
         (device/parse-pin-voltage-response ""))))

(deftest read-vbat-reports-vehicle-power-present
  (with-redefs [device/serial-exchange (fake-exchange "arr 16 12400\r\n")]
    (is (= {:ok? true
            :backend :mac-serial
            :path "/dev/cu.usbmodemTAlgJ3P81"
            :pin 16
            :millivolts 12400
            :volts 12.4
            :state :present
            :response "arr 16 12400\r\n"}
           (device/read-vbat "/dev/cu.usbmodemTAlgJ3P81")))))

(deftest read-vbat-reports-floating-pin-as-absent
  (with-redefs [device/serial-exchange (fake-exchange "arr 16 152\r\n")]
    (is (= {:state :absent :volts 0.152}
           (select-keys (device/read-vbat "/dev/cu.usbmodemTAlgJ3P81")
                        [:state :volts])))))

(deftest read-vbat-reports-adapter-errors
  (with-redefs [device/serial-exchange (fake-exchange "are 19\r\n")]
    (is (= {:ok? false
            :backend :mac-serial
            :path "/dev/cu.usbmodemTAlgJ3P81"
            :state :unknown
            :volts nil
            :error :adapter-error
            :adapter/error-code 19
            :response "are 19\r\n"}
           (device/read-vbat "/dev/cu.usbmodemTAlgJ3P81"))))
  (with-redefs [device/serial-exchange (fake-exchange "arr 12 0\r\n")]
    (is (= :unexpected-pin
           (:error (device/read-vbat "/dev/cu.usbmodemTAlgJ3P81"))))))

(deftest read-vbat-reports-exchange-failures
  (is (= {:ok? false
          :backend :mac-serial
          :path nil
          :state :unknown
          :volts nil
          :error :missing-path}
         (device/read-vbat nil))))

(deftest direct-backend-info-does-not-depend-on-external-libraries
  (with-redefs [device/serial-device-paths (fn [] ["/dev/cu.usbmodemTAlgJ3P81"])]
    (is (= {:backend :mac-serial
            :external-libraries? false
            :transport {:kind :macos-usbmodem
                        :paths ["/dev/cu.usbmodemTAlgJ3P81"]}
            :implemented [:scan-devices
                          :open-device
                          :vehicle-status-host-fields
                          :observe-device
                          :serial-exchange
                          :run-probe
                          :hex-request-parser
                          :openport-command-framing
                          :read-vbat
                          :obd-request
                          :read-pid
                          :read-all-pids
                          :read-vehicle-info
                          :ssm-init
                          :ssm-read
                          :can-session
                          :record
                          :export-recording
                          :live-source
                          :replay-source]
            :not-implemented [:j2534-pass-thru-api
                              :k-line-session]
            :vehicle-bus {:read-only? true
                          :operations [:obd-request
                                       :read-pid
                                       :read-all-pids
                                       :read-vehicle-info
                                       :ssm-init
                                       :ssm-read
                                       :record
                                       :live-source]}
            :discovery-policy {:writes :explicit-request-only
                               :sweeps? false
                               :vehicle-bus-traffic? false}}
           (device/direct-backend-info)))))

(deftest parses-hex-request-bytes
  (is (= {:ok? true
          :hex "0102FF0A"
          :bytes [1 2 255 10]}
         (device/hex->bytes "01 02 ff\n0a")))
  (is (= {:ok? false
          :error :empty-hex}
         (device/hex->bytes "")))
  (is (= {:ok? false
          :error :odd-length-hex
          :hex "ABC"}
         (device/hex->bytes "AB C")))
  (is (= {:ok? false
          :error :invalid-hex
          :hex "0x01"}
         (device/hex->bytes "0x01"))))

(deftest serial-exchange-validates-input-before-writing
  (is (= {:ok? false
          :error :missing-path}
         (device/serial-exchange nil "0102")))
  (is (= {:ok? false
          :path "/dev/cu.usbmodemTAlgJ3P81"
          :error :invalid-hex-request
          :request {:ok? false
                    :error :invalid-hex
                    :hex "GG"}}
         (device/serial-exchange "/dev/cu.usbmodemTAlgJ3P81" "GG"))))

(deftest run-probe-validates-input-before-exchange
  (let [called? (atom false)]
    (with-redefs [device/serial-exchange (fn [& _]
                                           (reset! called? true)
                                           {:ok? true})]
      (is (= {:ok? false
              :backend :mac-serial
              :error :missing-probe-name
              :vehicle-bus-traffic? false}
             (device/run-probe "/dev/cu.usbmodemTAlgJ3P81" "" "0102")))
      (is (false? @called?))
      (is (= {:ok? false
              :backend :mac-serial
              :probe/name "adapter-ping"
              :error :invalid-hex-request
              :request {:ok? false
                        :error :invalid-hex
                        :hex "GG"}
              :vehicle-bus-traffic? false}
             (device/run-probe "/dev/cu.usbmodemTAlgJ3P81" "adapter-ping" "GG")))
      (is (false? @called?))
      (is (= {:ok? false
              :backend :mac-serial
              :probe/name "adapter-ping"
              :path nil
              :error :missing-path
              :request {:byte-count 2
                        :bytes [1 2]
                        :hex ["01" "02"]}
              :vehicle-bus-traffic? false}
             (device/run-probe nil "adapter-ping" "0102")))
      (is (false? @called?)))))

(deftest run-probe-wraps-exchange-as-transcript
  (with-redefs [device/serial-exchange (fn [path request-hex]
                                         (is (= "/dev/cu.usbmodemTAlgJ3P81" path))
                                         (is (= "0102" request-hex))
                                         {:ok? true
                                          :backend :mac-serial
                                          :path path
                                          :request {:byte-count 2
                                                    :bytes [1 2]
                                                    :hex ["01" "02"]}
                                          :drained {:byte-count 0
                                                    :bytes []
                                                    :hex []}
                                          :response {:byte-count 1
                                                     :bytes [127]
                                                     :hex ["7F"]}
                                          :timeout-ms 1000
                                          :read-idle-ms 100
                                          :max-response-bytes 256
                                          :elapsed-ms 12
                                          :timed-out? false
                                          :idle? true
                                          :truncated? false})]
    (is (= {:ok? true
            :backend :mac-serial
            :probe/name "adapter-ping"
            :path "/dev/cu.usbmodemTAlgJ3P81"
            :operation/known? false
            :vehicle-bus-traffic? false
            :request {:byte-count 2
                      :bytes [1 2]
                      :hex ["01" "02"]}
            :drained {:byte-count 0
                      :bytes []
                      :hex []}
            :response {:byte-count 1
                       :bytes [127]
                       :hex ["7F"]}
            :timeout-ms 1000
            :read-idle-ms 100
            :max-response-bytes 256
            :elapsed-ms 12
            :timed-out? false
            :idle? true
            :truncated? false}
           (device/run-probe "/dev/cu.usbmodemTAlgJ3P81" " adapter-ping " "0102")))))

(deftest known-operations-have-confirmed-commands
  (is (true? (device/known-operation? :pass-thru-connect)))
  (is (true? (device/known-operation? "pass-thru-write-msgs")))
  (is (false? (device/known-operation? :pass-thru-start-periodic-msg)))
  (is (false? (device/known-operation? "adapter-ping")))
  (is (false? (device/known-operation? nil))))

(deftest unsupported-operation-reports-whether-command-is-known
  (is (= {:ok? false
          :backend :mac-serial
          :operation/name :pass-thru-connect
          :operation/known? true
          :operation/command "ato"
          :state :unsupported
          :error :not-implemented
          :message "The OpenPort command for this operation is known, but the J2534 API is not implemented."}
         (device/unsupported-operation :pass-thru-connect)))
  (is (= {:ok? false
          :backend :mac-serial
          :operation/name :pass-thru-start-periodic-msg
          :operation/known? false
          :state :unsupported
          :error :needs-openport-command
          :message "The OpenPort command for this operation has not been discovered."}
         (device/unsupported-operation :pass-thru-start-periodic-msg))))

(deftest vehicle-status-reports-no-device
  (is (= {:backend :mac-serial
          :device nil
          :path nil
          :host/device-present? false
          :host/openable? false
          :host/open-result nil
          :vehicle/power {:state :unknown
                          :volts nil
                          :error nil}
          :vehicle/vbat-result nil
          :vehicle/ecu {:state :unsupported}
          :confidence :low
          :reasons [:device-not-detected]}
         (device/vehicle-status nil))))

(deftest observe-device-reports-missing-path
  (is (= {:ok? false :error :missing-path}
         (device/observe-device nil))))

(defn- ascii [s]
  (map int s))

(defn- u32-bytes [n]
  (map #(bit-and (bit-shift-right n %) 0xFF) [24 16 8 0]))

(defn- message-bytes [channel status timestamp-us id data]
  (let [body (concat [status] (u32-bytes timestamp-us) (u32-bytes id) data)]
    (concat (ascii (str "ar" channel)) [(count body)] body)))

(defn- run-fake-obd
  "Runs obd-request against canned adapter output. Returns the result and the
  bytes written to the adapter."
  [replies request-hex opts]
  (let [output (java.io.ByteArrayOutputStream.)
        input (java.io.ByteArrayInputStream. (byte-array (map unchecked-byte replies)))]
    (with-redefs [device/open-session (fn [_]
                                        (#'device/stream-session input output))]
      {:result (device/obd-request "/dev/cu.usbmodemTAlgJ3P81" request-hex
                                   (merge {:drain? false} opts))
       :written (mapv #(bit-and % 0xFF) (.toByteArray output))})))

(def fake-vin (ascii "TESTVIN0123456789"))

(deftest parses-adapter-stream
  (is (= {:events [{:type :line :text "aro"}
                   {:type :message
                    :channel 5
                    :status 0
                    :timestamp-us 0x0A57C6EF
                    :id 0x370
                    :data [0 0 0x20 5 0xFC 7 0 0]}
                   {:type :line :text "arf5 1 0"}]
          :remainder []}
         (device/parse-adapter-stream
          (concat (ascii "aro\r\n")
                  (message-bytes 5 0 0x0A57C6EF 0x370 [0 0 0x20 5 0xFC 7 0 0])
                  (ascii "arf5 1 0\r\n")))))
  (testing "keeps incomplete events for the next read"
    (let [message (vec (message-bytes 6 0x40 1 0x7E8 [0x41 0]))]
      (is (= {:events []
              :remainder (subvec message 0 8)}
             (device/parse-adapter-stream (subvec message 0 8))))
      (is (= {:events [{:type :line :text "aro"}]
              :remainder (vec (ascii "ar"))}
             (device/parse-adapter-stream (ascii "aro\r\nar")))))))

(deftest obd-request-validates-before-opening-the-device
  (let [opened? (atom false)]
    (with-redefs [device/open-session (fn [_] (reset! opened? true))]
      (is (= :missing-path (:error (device/obd-request nil "0100"))))
      (is (= :invalid-hex-request (:error (device/obd-request "/dev/cu.usbmodemTAlgJ3P81" "GG"))))
      (is (= {:ok? false
              :backend :mac-serial
              :path "/dev/cu.usbmodemTAlgJ3P81"
              :protocol :iso15765
              :tx-id 0x7E0
              :rx-id 0x7E8
              :error :service-not-read-only
              :service 4}
             (device/obd-request "/dev/cu.usbmodemTAlgJ3P81" "04")))
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

(defn- run-fake-ssm
  "Runs `request`, an SSM function such as `device/ssm-init`, against canned
  adapter output. `args` go between the device path and the options. Returns
  the result and the bytes written to the adapter."
  [replies request & args]
  (let [output (java.io.ByteArrayOutputStream.)
        input (java.io.ByteArrayInputStream. (byte-array (map unchecked-byte replies)))]
    (with-redefs [device/open-session (fn [_]
                                        (#'device/stream-session input output))]
      {:result (apply request "/dev/cu.usbmodemTAlgJ3P81" (concat args [{:drain? false}]))
       :written (mapv #(bit-and % 0xFF) (.toByteArray output))})))

(deftest ssm-read-validates-before-opening-the-device
  (let [opened? (atom false)]
    (with-redefs [device/open-session (fn [_] (reset! opened? true))]
      (is (= :missing-path (:error (device/ssm-read nil [0x22]))))
      (are [addresses] (= :invalid-ssm-addresses
                          (:error (device/ssm-read "/dev/cu.usbmodemTAlgJ3P81" addresses)))
        nil
        []
        [0x1000000]
        [-1]
        ["000022"])
      (is (false? @opened?)))))

(deftest ssm-read-sends-a8-and-pairs-bytes-with-addresses
  (let [{:keys [result written]}
        (run-fake-ssm (concat (ascii "aro\r\naro\r\narf6 9 0\r\naro\r\n")
                              (message-bytes 6 0x40 100 0x7E8 [0xE8 0x80 0x3F])
                              (ascii "aro\r\n"))
                      device/ssm-read [0x000022 0xFF6A04])]
    (is (= (vec (concat (ascii "atc6\r\n")
                        (ascii "ato6 0 500000\r\n")
                        (ascii "atf6 3 64 4\r\n")
                        [0xFF 0xFF 0xFF 0xFF 0 0 7 0xE8 0 0 7 0xE0]
                        (ascii "att6 12 64\r\n")
                        [0 0 7 0xE0 0xA8 0 0 0 0x22 0xFF 0x6A 0x04]
                        (ascii "atc6\r\n")))
           written))
    (is (true? (:ok? result)))
    (is (= ["A8" "00" "00" "00" "22" "FF" "6A" "04"] (get-in result [:request :hex])))
    (is (= [{:address 0x22 :address-hex "000022" :value 0x80}
            {:address 0xFF6A04 :address-hex "FF6A04" :value 0x3F}]
           (:values result)))))

(deftest ssm-read-reports-replies-of-the-wrong-length
  (let [{:keys [result]}
        (run-fake-ssm (concat (ascii "aro\r\naro\r\narf6 9 0\r\naro\r\n")
                              (message-bytes 6 0x40 100 0x7E8 [0xE8 0x80])
                              (ascii "aro\r\n"))
                      device/ssm-read [0x000022 0x000023])]
    (is (= {:ok? false :error :unexpected-response}
           (select-keys result [:ok? :error])))
    (is (nil? (:values result)))))

(deftest ssm-read-reports-negative-responses
  (let [{:keys [result]}
        (run-fake-ssm (concat (ascii "aro\r\naro\r\narf6 9 0\r\naro\r\n")
                              (message-bytes 6 0x40 100 0x7E8 [0x7F 0xA8 0x31])
                              (ascii "aro\r\n"))
                      device/ssm-read [0x000022])]
    (is (= {:ok? false :error :negative-response :nrc 0x31}
           (select-keys result [:ok? :error :nrc])))))

(def ^:private fake-ssm-init-reply
  (vec (concat [0xEA 0xA2 0x10 0x11 0x73 0x34 0x38 0x41 0x07]
               [0xF3 0xFA 0xC9 0x8E])))

(deftest ssm-init-sends-aa-and-reads-the-ecu-id
  (let [{:keys [result written]}
        (run-fake-ssm (concat (ascii "aro\r\naro\r\narf6 9 0\r\naro\r\n")
                              (message-bytes 6 0x40 100 0x7E8 fake-ssm-init-reply)
                              (ascii "aro\r\n"))
                      device/ssm-init)]
    (is (= (vec (concat (ascii "atc6\r\n")
                        (ascii "ato6 0 500000\r\n")
                        (ascii "atf6 3 64 4\r\n")
                        [0xFF 0xFF 0xFF 0xFF 0 0 7 0xE8 0 0 7 0xE0]
                        (ascii "att6 5 64\r\n")
                        [0 0 7 0xE0 0xAA]
                        (ascii "atc6\r\n")))
           written))
    (is (= {:ok? true
            :ecu-id "7334384107"
            :capabilities {:bytes [0xF3 0xFA 0xC9 0x8E]
                           :hex ["F3" "FA" "C9" "8E"]}}
           (select-keys result [:ok? :ecu-id :capabilities])))))

(deftest ssm-init-reports-short-and-missing-replies
  (with-redefs [device/open-session (fn [_] (throw (ex-info "should not open" {})))]
    (is (= :missing-path (:error (device/ssm-init nil)))))
  (let [{:keys [result]}
        (run-fake-ssm (concat (ascii "aro\r\naro\r\narf6 9 0\r\naro\r\n")
                              (message-bytes 6 0x40 100 0x7E8 (subvec fake-ssm-init-reply 0 8))
                              (ascii "aro\r\n"))
                      device/ssm-init)]
    (is (= {:ok? false :error :unexpected-response}
           (select-keys result [:ok? :error])))
    (is (nil? (:ecu-id result)))))

(deftest decodes-common-mode-01-pids
  (are [data expected] (= expected (select-keys (device/decode-mode-01 data) [:ok? :name :value :unit]))
    [0x41 0x0C 0x0C 0x80] {:ok? true :name :engine-rpm :value 800.0 :unit :rpm}
    [0x41 0x05 0x55] {:ok? true :name :coolant-temp :value 45 :unit :celsius}
    [0x41 0x42 0x30 0x07] {:ok? true :name :control-module-voltage :value 12.295 :unit :volts}
    [0x41 0x0D 0x64] {:ok? true :name :vehicle-speed :value 100 :unit :km-per-hour}
    [0x41 0x11 0xFF] {:ok? true :name :throttle-position :value 100.0 :unit :percent}
    [0x41 0x2F 0xB3] {:ok? true :name :fuel-level :value 70.2 :unit :percent}
    [0x41 0x04 0x55] {:ok? true :name :engine-load :value 33.3 :unit :percent}
    [0x41 0x06 0x80] {:ok? true :name :short-term-fuel-trim-bank-1 :value 0.0 :unit :percent}
    [0x41 0x07 0x7B] {:ok? true :name :long-term-fuel-trim-bank-1 :value -3.9 :unit :percent}
    [0x41 0x0E 0x80] {:ok? true :name :timing-advance :value 0.0 :unit :degrees}
    [0x41 0x10 0x01 0x2C] {:ok? true :name :maf-air-flow :value 3.0 :unit :grams-per-second}))

(deftest decodes-supported-pid-bitmaps
  (is (= {:ok? true
          :pid 0
          :name :supported-pids-01-20
          :value [0x01 0x03 0x04 0x05 0x06 0x07 0x0B 0x0C 0x0D 0x0E 0x0F 0x10
                  0x11 0x13 0x15 0x1C 0x1F 0x20]
          :value-hex ["01" "03" "04" "05" "06" "07" "0B" "0C" "0D" "0E" "0F" "10"
                      "11" "13" "15" "1C" "1F" "20"]}
         (device/decode-mode-01 [0x41 0x00 0xBE 0x3F 0xA8 0x13])))
  (is (= ["42" "46" "5C" "60"]
         (:value-hex (device/decode-mode-01 [0x41 0x40 0x44 0x00 0x00 0x11]))))
  (is (= :supported-pids-61-80
         (:name (device/decode-mode-01 [0x41 0x60 0 0 0 0])))))

(deftest reports-undecodable-mode-01-replies
  (is (= {:ok? false :error :not-mode-01-reply}
         (device/decode-mode-01 [0x49 0x02 0x01])))
  (is (= {:ok? false :pid 0x99 :error :unknown-pid :raw [1 2]}
         (device/decode-mode-01 [0x41 0x99 1 2])))
  (is (= {:ok? false :pid 0x0C :name :engine-rpm :error :short-reply :raw [0x0C]}
         (device/decode-mode-01 [0x41 0x0C 0x0C]))))

(deftest read-pid-requests-and-decodes
  (with-redefs [device/obd-request (fn [path request-hex _opts]
                                     (is (= "/dev/cu.usbmodemTAlgJ3P81" path))
                                     (is (= "010C" request-hex))
                                     {:ok? true
                                      :response {:bytes [0x41 0x0C 0x0C 0x80]}})]
    (is (= {:ok? true :pid 0x0C :name :engine-rpm :value 800.0 :unit :rpm}
           (select-keys (device/read-pid "/dev/cu.usbmodemTAlgJ3P81" :engine-rpm)
                        [:ok? :pid :name :value :unit])))
    (is (= 800.0 (:value (device/read-pid "/dev/cu.usbmodemTAlgJ3P81" 0x0C))))))

(deftest read-pid-passes-through-request-failures
  (with-redefs [device/obd-request (fn [& _] {:ok? false :error :timeout})]
    (is (= {:ok? false :error :timeout}
           (device/read-pid "/dev/cu.usbmodemTAlgJ3P81" :coolant-temp)))))

(deftest read-pid-rejects-unknown-names-without-sending
  (with-redefs [device/obd-request (fn [& _] (throw (ex-info "should not send" {})))]
    (let [result (device/read-pid "/dev/cu.usbmodemTAlgJ3P81" :warp-drive)]
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
    (with-redefs [device/read-pid (fake-read-pid
                                   requested
                                   {0x00 {:ok? true :value [0x05 0x0C 0x20]}
                                    0x20 {:ok? true :value [0x2F]}
                                    0x05 {:ok? true :pid 0x05 :name :coolant-temp :value 73 :unit :celsius}
                                    0x0C {:ok? true :pid 0x0C :name :engine-rpm :value 682.0 :unit :rpm}})]
      (let [result (device/read-all-pids "/dev/cu.usbmodemTAlgJ3P81")]
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
    (with-redefs [device/read-pid (fake-read-pid requested {})]
      (is (= {:ok? false
              :error :timeout
              :supported []}
             (device/read-all-pids "/dev/cu.usbmodemTAlgJ3P81")))
      (is (= [0x00] @requested)))))

(def ^:private fake-calibration-id
  (concat (ascii "EA1T002C") (repeat 8 0)))

(deftest decodes-mode-09-vehicle-information
  (is (= {:ok? true :name :vin :value "TESTVIN0123456789"}
         (device/decode-mode-09 (concat [0x49 2 1] fake-vin))))
  (is (= {:ok? true :name :calibration-ids :value ["EA1T002C" "CAL2"]}
         (device/decode-mode-09 (concat [0x49 4 2] fake-calibration-id
                                        (ascii "CAL2") (repeat 12 0)))))
  (is (= {:ok? true :name :ecu-name :value "ECM-EngineControl"}
         (device/decode-mode-09 (concat [0x49 0x0A 1] (ascii "ECM") [0] (ascii "-EngineControl") [0 0]))))
  (is (= :unknown-info-type (:error (device/decode-mode-09 [0x49 0x0B 1 0x12 0x34]))))
  (is (= :not-mode-09-reply (:error (device/decode-mode-09 [0x41 0x0C 0x0C 0x80])))))

;; Replies from the project's 2015 Impreza.
(deftest decodes-mode-09-support-and-calibration-checks
  (is (= {:ok? true :name :supported-info-types :value [0x02 0x04 0x06 0x08 0x0A]}
         (device/decode-mode-09 [0x49 0x00 0x55 0x40 0x00 0x00])))
  (is (= {:ok? true :name :calibration-verification-numbers :value ["9EB33551"]}
         (device/decode-mode-09 [0x49 0x06 0x01 0x9E 0xB3 0x35 0x51])))
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
         (device/decode-mode-09 [0x49 0x08 0x14
                                 0 4 0 19 0 0 0 0 0 0 0 0 0 4 0 4 0 0 0 0
                                 0 15 0 4 0 0 0 0 0 1 0 0 0 7 0 4 0 0 0 0])))
  (testing "an older ECU sends 16 counters, without the secondary oxygen sensors"
    (is (= #{:catalyst-bank-1 :catalyst-bank-2 :oxygen-sensor-bank-1 :oxygen-sensor-bank-2
             :egr-system :secondary-air :evaporative-system}
           (-> (device/decode-mode-09 (into [0x49 0x08 0x10] (repeat 32 0)))
               (get-in [:value :monitors])
               keys
               set)))))

(deftest read-vehicle-info-collects-each-item-and-its-errors
  (let [requested (atom [])
        replies {"0902" {:ok? true :response {:bytes (vec (concat [0x49 2 1] fake-vin))}}
                 "0904" {:ok? true :response {:bytes (vec (concat [0x49 4 1] fake-calibration-id))}}
                 "0906" {:ok? true :response {:bytes [0x49 6 1 0x9E 0xB3 0x35 0x51]}}
                 "090A" {:ok? false :error :negative-response :nrc 0x12}}]
    (with-redefs [device/obd-request (fn [_path request-hex _opts]
                                       (swap! requested conj request-hex)
                                       (get replies request-hex))
                  device/ssm-init (fn [_path _opts]
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
             (device/read-vehicle-info "/dev/cu.usbmodemTAlgJ3P81")))
      (is (= ["0902" "0904" "0906" "090A" "AA"] @requested)))))

(deftest read-vehicle-info-reports-when-nothing-answers
  (with-redefs [device/obd-request (fn [& _] {:ok? false :error :not-found-or-permission-denied})
                device/ssm-init (fn [& _] {:ok? false :error :not-found-or-permission-denied})]
    (is (= {:ok? false
            :error :not-found-or-permission-denied}
           (select-keys (device/read-vehicle-info "/dev/cu.usbmodemTAlgJ3P81") [:ok? :error]))))
  (is (= {:ok? false :error :missing-path}
         (device/read-vehicle-info nil))))

(deftest decodes-monitor-status-since-codes-cleared
  (is (= {:mil-on? true
          :dtc-count 1
          :ignition :spark
          :monitors {:catalyst :complete
                     :components :complete
                     :evaporative-system :incomplete
                     :fuel-system :complete
                     :misfire :complete
                     :oxygen-sensor :complete
                     :oxygen-sensor-heater :complete}}
         (:value (device/decode-mode-01 [0x41 0x01 0x81 0x07 0x65 0x04]))))
  (testing "diesel monitors and incomplete common monitors"
    (is (= {:mil-on? false
            :dtc-count 0
            :ignition :compression
            :monitors {:egr-vvt-system :incomplete
                       :misfire :incomplete
                       :nmhc-catalyst :complete}}
           (:value (device/decode-mode-01 [0x41 0x01 0x00 0x19 0x81 0x80]))))))

(deftest decodes-monitor-status-this-drive-cycle
  (is (= {:ignition :spark
          :monitors {:catalyst :complete
                     :components :complete
                     :misfire :complete}}
         (:value (device/decode-mode-01 [0x41 0x41 0x00 0x05 0x01 0x00])))))

(deftest decodes-diagnostic-counters
  (are [data expected] (= expected (select-keys (device/decode-mode-01 data) [:name :value :unit]))
    [0x41 0x21 0x00 0x00] {:name :distance-with-mil-on :value 0 :unit :km}
    [0x41 0x30 0x05] {:name :warm-ups-since-codes-cleared :value 5 :unit :count}
    [0x41 0x31 0x12 0x34] {:name :distance-since-codes-cleared :value 4660 :unit :km}
    [0x41 0x4D 0x00 0x0A] {:name :time-with-mil-on :value 10 :unit :minutes}
    [0x41 0x4E 0x01 0x00] {:name :time-since-codes-cleared :value 256 :unit :minutes}))

(deftest decodes-fuel-and-air-fuel-ratio-pids
  (are [data expected] (= expected (select-keys (device/decode-mode-01 data) [:name :value :unit]))
    [0x41 0x03 0x02 0x00] {:name :fuel-system-status
                           :value {:system-1 :closed-loop :system-2 :none}}
    [0x41 0x03 0x04 0x03] {:name :fuel-system-status
                           :value {:system-1 :open-loop-load-or-decel :system-2 3}}
    [0x41 0x13 0x33] {:name :oxygen-sensors-present
                      :value [:bank-1-sensor-1 :bank-1-sensor-2 :bank-2-sensor-1 :bank-2-sensor-2]}
    [0x41 0x15 0x8C 0x80] {:name :oxygen-sensor-bank-1-sensor-2
                           :value {:volts 0.7 :fuel-trim-percent 0.0}}
    [0x41 0x15 0x8C 0xFF] {:name :oxygen-sensor-bank-1-sensor-2
                           :value {:volts 0.7 :fuel-trim-percent nil}}
    [0x41 0x24 0x80 0x00 0x40 0x00] {:name :wideband-oxygen-sensor-1-voltage
                                     :value {:lambda 1.0 :volts 2.0}}
    [0x41 0x34 0x80 0x00 0x80 0x80] {:name :wideband-oxygen-sensor-1-current
                                     :value {:lambda 1.0 :milliamps 0.5}}
    [0x41 0x43 0x00 0x55] {:name :absolute-load :value 33.3 :unit :percent}
    [0x41 0x44 0x7F 0x00] {:name :commanded-lambda :value 0.992 :unit :lambda}
    [0x41 0x51 0x01] {:name :fuel-type :value :gasoline}
    [0x41 0x51 0x63] {:name :fuel-type :value 0x63}))

(deftest decodes-throttle-and-pedal-pids
  (are [pid name] (= {:name name :value 50.2 :unit :percent}
                     (select-keys (device/decode-mode-01 [0x41 pid 0x80]) [:name :value :unit]))
    0x45 :relative-throttle-position
    0x47 :absolute-throttle-position-b
    0x48 :absolute-throttle-position-c
    0x49 :accelerator-pedal-position-d
    0x4A :accelerator-pedal-position-e
    0x4B :accelerator-pedal-position-f
    0x4C :commanded-throttle-actuator
    0x5A :relative-accelerator-pedal-position))

(deftest decodes-emissions-hardware-pids
  (are [data expected] (= expected (select-keys (device/decode-mode-01 data) [:name :value :unit]))
    [0x41 0x2C 0x80] {:name :commanded-egr :value 50.2 :unit :percent}
    [0x41 0x2D 0x70] {:name :egr-error :value -12.5 :unit :percent}
    [0x41 0x2E 0xFF] {:name :commanded-evap-purge :value 100.0 :unit :percent}
    [0x41 0x3C 0x12 0x34] {:name :catalyst-temp-bank-1-sensor-1 :value 426.0 :unit :celsius}
    [0x41 0x3F 0x01 0x91] {:name :catalyst-temp-bank-2-sensor-2 :value 0.1 :unit :celsius}
    [0x41 0x53 0x4E 0x21] {:name :evap-vapor-pressure-absolute :value 100.005 :unit :kpa}))

(deftest decodes-obd-standard-and-auxiliary-io
  (are [data expected] (= expected (select-keys (device/decode-mode-01 data) [:name :value]))
    [0x41 0x1C 0x01] {:name :obd-standard :value :obd-ii-carb}
    [0x41 0x1C 0x0B] {:name :obd-standard :value :jobd-and-obd-ii}
    [0x41 0x1C 0x0E] {:name :obd-standard :value 14}
    [0x41 0x65 0x02 0x02] {:name :auxiliary-io-status
                           :value {:automatic-transmission-in-drive? true}}
    [0x41 0x65 0x04 0x00] {:name :auxiliary-io-status
                           :value {:manual-transmission-in-gear? false}}
    [0x41 0x65 0x09 0x08] {:name :auxiliary-io-status
                           :value {:pto-active? false :glow-plug-lamp-on? true}}
    [0x41 0x65 0x00 0xFF] {:name :auxiliary-io-status :value {}}))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory "recording" (make-array java.nio.file.attribute.FileAttribute 0))))

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
           (device/export-recording (str dir))))
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
  (with-redefs [device/open-session (fn [_] (throw (ex-info "should not open" {})))]
    (is (= {:ok? false :error :missing-path}
           (device/record nil "recordings/x")))
    (is (= {:ok? false :path "/dev/cu.usbmodemTAlgJ3P81" :error :missing-out-dir}
           (device/record "/dev/cu.usbmodemTAlgJ3P81" nil)))))

(defn- fake-adapter
  "An in-memory adapter for session tests. `respond` is called with each
  command line and its binary payload and returns the bytes to send back."
  [respond]
  (let [incoming (java.util.concurrent.LinkedBlockingQueue.)
        pending (atom [])
        payload-length (fn [line]
                         (if-let [[_ n] (re-matches #"att\d+ (\d+) \d+" line)]
                           (parse-long n)
                           (if-let [[_ type n] (re-matches #"atf\d+ (\d+) \d+ (\d+).*" line)]
                             (* (if (= "3" type) 3 2) (parse-long n))
                             0)))
        process! (fn []
                   (loop []
                     (let [bytes @pending
                           newline-at (first (keep-indexed (fn [i b] (when (= 10 b) i)) bytes))]
                       (when newline-at
                         (let [line (str/trim (apply str (map char (subvec bytes 0 newline-at))))
                               end (+ (inc newline-at) (payload-length line))]
                           (when (<= end (count bytes))
                             (reset! pending (subvec bytes end))
                             (doseq [b (respond line (subvec bytes (inc newline-at) end))]
                               (.put incoming (int b)))
                             (recur)))))))
        receive! (fn [bs]
                   (swap! pending into (map #(bit-and % 0xFF)) bs)
                   (process!))
        input (proxy [java.io.InputStream] []
                (available [] (.size incoming))
                (read
                  ([] (if-let [b (.poll incoming)] b -1))
                  ([^bytes buffer] (.read ^java.io.InputStream this buffer 0 (alength buffer)))
                  ([^bytes buffer offset length]
                   (loop [n 0]
                     (if-let [b (when (< n length) (.poll incoming))]
                       (do (aset-byte buffer (+ offset n) (unchecked-byte b))
                           (recur (inc n)))
                       (if (pos? n) n -1))))))
        output (proxy [java.io.OutputStream] []
                 (write
                   ([b] (if (bytes? b) (receive! b) (receive! [b])))
                   ([^bytes buffer offset length]
                    (receive! (java.util.Arrays/copyOfRange buffer (int offset) (int (+ offset length))))))
                 (flush []))]
    (#'device/stream-session input output)))

(def ^:private fake-pid-data
  {0x0C [0x0C 0x80]
   0x05 [0x55]})

(defn- fake-vehicle-responder []
  (let [clock (atom 1000000)]
    (fn [line payload]
      (cond
        (= "ati" line) (ascii "ari main code version : 1.17.4877\r\n")
        (str/starts-with? line "ato") (ascii "aro\r\n")
        (str/starts-with? line "atc") (ascii "aro\r\n")
        (str/starts-with? line "atf") (ascii "arf5 1 0\r\n")
        (str/starts-with? line "att")
        (let [pid (nth payload 6)
              reply (into [0x41 pid] (get fake-pid-data pid [0 0 0 0]))]
          (concat (ascii "aro\r\n")
                  (message-bytes 5 0 (swap! clock + 1000) 0x7E8
                                 (take 8 (concat [(count reply)] reply (repeat 0))))))
        :else []))))

(defn- wait-for [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 10) (recur))))))

(deftest reply-sample-decodes-single-frame-mode-01-replies
  (is (= {:pid 0x0C :name :engine-rpm :value 800.0 :unit :rpm :timestamp-us 5}
         (device/reply-sample 0x7E8 {:type :message :id 0x7E8 :timestamp-us 5
                                     :data [4 0x41 0x0C 0x0C 0x80 0 0 0]})))
  (is (nil? (device/reply-sample 0x7E8 {:type :message :id 0x7E0 :timestamp-us 5
                                        :data [2 1 0x0C 0 0 0 0 0]})))
  (is (nil? (device/reply-sample 0x7E8 {:type :line :text "aro"}))))

(deftest record-captures-a-polling-session
  (let [dir (temp-dir)]
    (with-redefs [device/open-session (fn [_] (fake-adapter (fake-vehicle-responder)))]
      (let [result (device/record "/dev/fake" (str dir) {:duration-ms 100
                                                          :pids [0x0C 0x05]
                                                          :interval-ms 10})]
        (is (true? (:ok? result)))
        (is (pos? (:request-count result)))
        (is (= (:request-count result) (get-in result [:counts :pid-samples])))
        (is (= "1.17.4877" (get-in (edn/read-string (slurp (java.io.File. dir "meta.edn")))
                                   [:adapter :firmware])))
        (is (= ["engine-rpm" "800.0" "coolant-temp" "45"]
               (->> (str/split-lines (slurp (java.io.File. dir "pids.csv")))
                    (drop 1)
                    (take 2)
                    (mapcat #(let [[_ _ _ name value] (str/split % #",")] [name value])))))))))

(deftest live-source-emits-status-and-samples
  (let [events (atom [])]
    (with-redefs [device/open-session (fn [_] (fake-adapter (fake-vehicle-responder)))]
      (let [source (device/start-live-source! "/dev/fake" {:emit #(swap! events conj %)
                                                          :schedule [0x0C 0x05]
                                                          :interval-ms 10})]
        (is (wait-for #(<= 4 (count (filter (comp #{:sample} :type) @events))) 3000))
        ((:stop! source))))
    (let [statuses (filter (comp #{:status} :type) @events)
          samples (filter (comp #{:sample} :type) @events)]
      (is (= [{:type :status :state :running :source :live :path "/dev/fake" :firmware "1.17.4877"}
              {:type :status :state :stopped :source :live :path "/dev/fake"}]
             statuses))
      (is (= {:type :sample :pid 0x0C :name :engine-rpm :value 800.0 :unit :rpm}
             (dissoc (first samples) :timestamp-us)))
      (is (= #{:engine-rpm :coolant-temp} (set (map :name samples)))))))

(deftest live-source-reports-a-missing-path
  (let [events (atom [])]
    ((:stop! (device/start-live-source! nil {:emit #(swap! events conj %)})))
    (is (= [{:type :status :state :failed :source :live :path nil :error :missing-path}]
           @events))))

(defn- write-recording! [dir messages]
  (java.nio.file.Files/write (.toPath (java.io.File. dir "raw.bin"))
                             (byte-array (map unchecked-byte (apply concat messages)))
                             (make-array java.nio.file.OpenOption 0))
  (spit (java.io.File. dir "meta.edn") (pr-str {:polling {:rx-id 0x7E8}})))

(deftest replay-source-emits-recorded-samples-in-order
  (let [dir (temp-dir)
        events (atom [])]
    (write-recording! dir [(message-bytes 5 0 1000000 0x372 [0 0 0 0 0 0 0 0])
                           (message-bytes 5 0 1001000 0x7E8 [4 0x41 0x0C 0x0C 0x80 0 0 0])
                           (message-bytes 5 0 1011000 0x7E8 [3 0x41 0x05 0x55 0 0 0 0])])
    (let [source (device/start-replay-source! (str dir) {:emit #(swap! events conj %)
                                                         :loop? false})]
      (is (wait-for #(some (comp #{:stopped} :state) @events) 2000))
      ((:stop! source)))
    (is (= [{:type :status :state :running :source :replay :recording (str dir) :sample-count 2}
            {:type :sample :pid 0x0C :name :engine-rpm :value 800.0 :unit :rpm :timestamp-us 1001000}
            {:type :sample :pid 0x05 :name :coolant-temp :value 45 :unit :celsius :timestamp-us 1011000}
            {:type :status :state :stopped :source :replay :recording (str dir)}]
           @events))))

(deftest replay-source-loops-with-increasing-timestamps
  (let [dir (temp-dir)
        events (atom [])]
    (write-recording! dir [(message-bytes 5 0 1000000 0x7E8 [4 0x41 0x0C 0x0C 0x80 0 0 0])
                           (message-bytes 5 0 1010000 0x7E8 [3 0x41 0x05 0x55 0 0 0 0])])
    (let [source (device/start-replay-source! (str dir) {:emit #(swap! events conj %)
                                                         :speed 10.0})]
      (is (wait-for #(<= 6 (count (filter (comp #{:sample} :type) @events))) 2000))
      ((:stop! source)))
    (let [timestamps (keep :timestamp-us @events)]
      (is (apply < timestamps))
      (is (= [1000000 1010000 1035000 1045000] (take 4 timestamps))))))

(deftest replay-source-reports-an-empty-recording
  (let [dir (temp-dir)
        events (atom [])]
    (write-recording! dir [(ascii "aro\r\n")])
    ((:stop! (device/start-replay-source! (str dir) {:emit #(swap! events conj %)})))
    (is (= [{:type :status :state :failed :source :replay :recording (str dir) :error :no-samples}]
           @events))))
