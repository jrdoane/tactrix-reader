(ns tactrix-reader.device.serial-test
  (:require [clojure.test :refer [deftest is]]
            [tactrix-reader.device.serial :as serial]))

(deftest serial-exchange-validates-input-before-writing
  (is (= {:ok? false
          :error :missing-path}
         (serial/serial-exchange nil "0102")))
  (is (= {:ok? false
          :path "/dev/cu.usbmodemTAlgJ3P81"
          :error :invalid-hex-request
          :request {:ok? false
                    :error :invalid-hex
                    :hex "GG"}}
         (serial/serial-exchange "/dev/cu.usbmodemTAlgJ3P81" "GG"))))

(deftest observe-device-reports-missing-path
  (is (= {:ok? false :error :missing-path}
         (serial/observe-device nil))))

