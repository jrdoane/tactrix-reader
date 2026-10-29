(ns tactrix-reader.device.usb-test
  (:require [clojure.test :refer [deftest is testing]]
            [tactrix-reader.device.usb :as usb]))

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
    (is (true? (usb/usbmodem-path? "/dev/cu.usbmodemTAlgJ3P81")))
    (is (= "TAlgJ3P81" (usb/serial-token "/dev/cu.usbmodemTAlgJ3P81")))
    (is (true? (usb/candidate-serial? "TAlgJ3P8" "/dev/cu.usbmodemTAlgJ3P81")))
    (is (false? (usb/usbmodem-path? "/dev/cu.Bluetooth-Incoming-Port")))))

(deftest parses-ioreg-openport-device
  (let [device (first (usb/parse-ioreg-usb-devices sample-ioreg))]
    (is (= "OpenPort 2.0" (:name device)))
    (is (= "Tactrix" (:usb-vendor-name device)))
    (is (= "OpenPort 2_0" (:usb-product-name device)))
    (is (= "TAlgJ3P8" (:usb-serial-number device)))
    (is (= 1027 (:idvendor device)))
    (is (= 52301 (:idproduct device)))))

(deftest open-device-reports-missing-path
  (is (= {:ok? false :error :missing-path}
         (usb/open-device! nil))))

