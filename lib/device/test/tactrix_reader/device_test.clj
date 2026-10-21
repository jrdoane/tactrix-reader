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

(deftest read-vbat-reports-unsupported-mac-serial-backend
  (is (= {:ok? false
          :backend :mac-serial
          :path "/dev/cu.usbmodemTAlgJ3P81"
          :state :unsupported
          :volts nil
          :error :mac-serial-vbat-command-unknown
          :message "OpenPort 2.0 direct serial Vbat command is not known; no bus traffic was sent."}
         (device/read-vbat "/dev/cu.usbmodemTAlgJ3P81"))))

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
                          :hex-request-parser]
            :not-implemented [:openport-command-framing
                              :adapter-vbat
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

(deftest active-vbat-remains-explicitly-unsupported-until-command-is-known
  (is (= {:ok? false
          :backend :mac-serial
          :path "/dev/cu.usbmodemTAlgJ3P81"
          :state :unsupported
          :volts nil
          :error :mac-serial-vbat-command-unknown
          :message "Use serial-exchange while disconnected from a vehicle to identify the OpenPort 2.0 direct serial Vbat command."}
         (device/active-vbat "/dev/cu.usbmodemTAlgJ3P81"))))

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
