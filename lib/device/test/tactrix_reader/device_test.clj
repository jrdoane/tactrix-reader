(ns tactrix-reader.device-test
  (:require [clojure.test :refer [deftest is testing]]
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
                          :read-vbat
                          :obd-request]
            :not-implemented [:openport-command-framing
                              :j2534-pass-thru-api
                              :can-session
                              :k-line-session
                              :ecu-messaging]
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

(deftest unsupported-operation-reports-framing-boundary
  (is (= {:ok? false
          :backend :mac-serial
          :operation/name :pass-thru-connect
          :operation/known? false
          :state :unsupported
          :error :needs-openport-command-framing
          :message "OpenPort command framing has not been discovered for this operation."}
         (device/unsupported-operation :pass-thru-connect))))

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
