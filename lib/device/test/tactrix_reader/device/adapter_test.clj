(ns tactrix-reader.device.adapter-test
  (:require [clojure.test :refer [deftest is]]
            [tactrix-reader.device.adapter :as adapter]
            [tactrix-reader.device.serial :as serial]
            [tactrix-reader.device.usb :as usb]))

(deftest classifies-vbat-thresholds
  (is (= :unknown (adapter/classify-vbat nil)))
  (is (= :absent (adapter/classify-vbat 0.0)))
  (is (= :absent (adapter/classify-vbat 2.99)))
  (is (= :unknown (adapter/classify-vbat 3.0)))
  (is (= :unknown (adapter/classify-vbat 8.99)))
  (is (= :present (adapter/classify-vbat 9.0)))
  (is (= :present (adapter/classify-vbat 12.4))))

(defn- fake-exchange [response]
  (fn [path request-hex _opts]
    (is (= "/dev/cu.usbmodemTAlgJ3P81" path))
    (is (= "6174722031360D0A" request-hex))
    {:ok? true
     :path path
     :response {:bytes (mapv int response)}}))

(deftest parses-pin-voltage-responses
  (is (= {:ok? true :pin 16 :millivolts 12400}
         (adapter/parse-pin-voltage-response "arr 16 12400\r\n")))
  (is (= {:ok? false :error :adapter-error :adapter/error-code 19}
         (adapter/parse-pin-voltage-response "are 19\r\n")))
  (is (= {:ok? false :error :unexpected-response}
         (adapter/parse-pin-voltage-response ""))))

(deftest read-vbat-reports-vehicle-power-present
  (with-redefs [serial/serial-exchange (fake-exchange "arr 16 12400\r\n")]
    (is (= {:ok? true
            :backend :mac-serial
            :path "/dev/cu.usbmodemTAlgJ3P81"
            :pin 16
            :millivolts 12400
            :volts 12.4
            :state :present
            :response "arr 16 12400\r\n"}
           (adapter/read-vbat "/dev/cu.usbmodemTAlgJ3P81")))))

(deftest read-vbat-reports-floating-pin-as-absent
  (with-redefs [serial/serial-exchange (fake-exchange "arr 16 152\r\n")]
    (is (= {:state :absent :volts 0.152}
           (select-keys (adapter/read-vbat "/dev/cu.usbmodemTAlgJ3P81")
                        [:state :volts])))))

(deftest read-vbat-reports-adapter-errors
  (with-redefs [serial/serial-exchange (fake-exchange "are 19\r\n")]
    (is (= {:ok? false
            :backend :mac-serial
            :path "/dev/cu.usbmodemTAlgJ3P81"
            :state :unknown
            :volts nil
            :error :adapter-error
            :adapter/error-code 19
            :response "are 19\r\n"}
           (adapter/read-vbat "/dev/cu.usbmodemTAlgJ3P81"))))
  (with-redefs [serial/serial-exchange (fake-exchange "arr 12 0\r\n")]
    (is (= :unexpected-pin
           (:error (adapter/read-vbat "/dev/cu.usbmodemTAlgJ3P81"))))))

(deftest read-vbat-reports-exchange-failures
  (is (= {:ok? false
          :backend :mac-serial
          :path nil
          :state :unknown
          :volts nil
          :error :missing-path}
         (adapter/read-vbat nil))))

(deftest direct-backend-info-does-not-depend-on-external-libraries
  (with-redefs [usb/serial-device-paths (fn [] ["/dev/cu.usbmodemTAlgJ3P81"])]
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
                          :read-trouble-codes
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
                                       :read-trouble-codes
                                       :ssm-init
                                       :ssm-read
                                       :record
                                       :live-source]}
            :discovery-policy {:writes :explicit-request-only
                               :sweeps? false
                               :vehicle-bus-traffic? false}}
           (adapter/direct-backend-info)))))

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
         (adapter/vehicle-status nil))))

