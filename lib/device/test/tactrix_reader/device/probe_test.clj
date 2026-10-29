(ns tactrix-reader.device.probe-test
  (:require [clojure.test :refer [deftest is]]
            [tactrix-reader.device.probe :as probe]
            [tactrix-reader.device.serial :as serial]))

(deftest run-probe-validates-input-before-exchange
  (let [called? (atom false)]
    (with-redefs [serial/serial-exchange (fn [& _]
                                           (reset! called? true)
                                           {:ok? true})]
      (is (= {:ok? false
              :backend :mac-serial
              :error :missing-probe-name
              :vehicle-bus-traffic? false}
             (probe/run-probe "/dev/cu.usbmodemTAlgJ3P81" "" "0102")))
      (is (false? @called?))
      (is (= {:ok? false
              :backend :mac-serial
              :probe/name "adapter-ping"
              :error :invalid-hex-request
              :request {:ok? false
                        :error :invalid-hex
                        :hex "GG"}
              :vehicle-bus-traffic? false}
             (probe/run-probe "/dev/cu.usbmodemTAlgJ3P81" "adapter-ping" "GG")))
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
             (probe/run-probe nil "adapter-ping" "0102")))
      (is (false? @called?)))))

(deftest run-probe-wraps-exchange-as-transcript
  (with-redefs [serial/serial-exchange (fn [path request-hex]
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
           (probe/run-probe "/dev/cu.usbmodemTAlgJ3P81" " adapter-ping " "0102")))))

