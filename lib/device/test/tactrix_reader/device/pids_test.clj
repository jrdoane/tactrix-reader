(ns tactrix-reader.device.pids-test
  (:require [clojure.test :refer [are deftest is testing]]
            [tactrix-reader.device.pids :as pids]))

(deftest decodes-common-mode-01-pids
  (are [data expected] (= expected (select-keys (pids/decode-mode-01 data) [:ok? :name :value :unit]))
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
         (pids/decode-mode-01 [0x41 0x00 0xBE 0x3F 0xA8 0x13])))
  (is (= ["42" "46" "5C" "60"]
         (:value-hex (pids/decode-mode-01 [0x41 0x40 0x44 0x00 0x00 0x11]))))
  (is (= :supported-pids-61-80
         (:name (pids/decode-mode-01 [0x41 0x60 0 0 0 0])))))

(deftest reports-undecodable-mode-01-replies
  (is (= {:ok? false :error :not-mode-01-reply}
         (pids/decode-mode-01 [0x49 0x02 0x01])))
  (is (= {:ok? false :pid 0x99 :error :unknown-pid :raw [1 2]}
         (pids/decode-mode-01 [0x41 0x99 1 2])))
  (is (= {:ok? false :pid 0x0C :name :engine-rpm :error :short-reply :raw [0x0C]}
         (pids/decode-mode-01 [0x41 0x0C 0x0C]))))

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
         (:value (pids/decode-mode-01 [0x41 0x01 0x81 0x07 0x65 0x04]))))
  (testing "diesel monitors and incomplete common monitors"
    (is (= {:mil-on? false
            :dtc-count 0
            :ignition :compression
            :monitors {:egr-vvt-system :incomplete
                       :misfire :incomplete
                       :nmhc-catalyst :complete}}
           (:value (pids/decode-mode-01 [0x41 0x01 0x00 0x19 0x81 0x80]))))))

(deftest decodes-monitor-status-this-drive-cycle
  (is (= {:ignition :spark
          :monitors {:catalyst :complete
                     :components :complete
                     :misfire :complete}}
         (:value (pids/decode-mode-01 [0x41 0x41 0x00 0x05 0x01 0x00])))))

(deftest decodes-diagnostic-counters
  (are [data expected] (= expected (select-keys (pids/decode-mode-01 data) [:name :value :unit]))
    [0x41 0x21 0x00 0x00] {:name :distance-with-mil-on :value 0 :unit :km}
    [0x41 0x30 0x05] {:name :warm-ups-since-codes-cleared :value 5 :unit :count}
    [0x41 0x31 0x12 0x34] {:name :distance-since-codes-cleared :value 4660 :unit :km}
    [0x41 0x4D 0x00 0x0A] {:name :time-with-mil-on :value 10 :unit :minutes}
    [0x41 0x4E 0x01 0x00] {:name :time-since-codes-cleared :value 256 :unit :minutes}))

(deftest decodes-fuel-and-air-fuel-ratio-pids
  (are [data expected] (= expected (select-keys (pids/decode-mode-01 data) [:name :value :unit]))
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
                     (select-keys (pids/decode-mode-01 [0x41 pid 0x80]) [:name :value :unit]))
    0x45 :relative-throttle-position
    0x47 :absolute-throttle-position-b
    0x48 :absolute-throttle-position-c
    0x49 :accelerator-pedal-position-d
    0x4A :accelerator-pedal-position-e
    0x4B :accelerator-pedal-position-f
    0x4C :commanded-throttle-actuator
    0x5A :relative-accelerator-pedal-position))

(deftest decodes-emissions-hardware-pids
  (are [data expected] (= expected (select-keys (pids/decode-mode-01 data) [:name :value :unit]))
    [0x41 0x2C 0x80] {:name :commanded-egr :value 50.2 :unit :percent}
    [0x41 0x2D 0x70] {:name :egr-error :value -12.5 :unit :percent}
    [0x41 0x2E 0xFF] {:name :commanded-evap-purge :value 100.0 :unit :percent}
    [0x41 0x3C 0x12 0x34] {:name :catalyst-temp-bank-1-sensor-1 :value 426.0 :unit :celsius}
    [0x41 0x3F 0x01 0x91] {:name :catalyst-temp-bank-2-sensor-2 :value 0.1 :unit :celsius}
    [0x41 0x53 0x4E 0x21] {:name :evap-vapor-pressure-absolute :value 100.005 :unit :kpa}))

(deftest decodes-obd-standard-and-auxiliary-io
  (are [data expected] (= expected (select-keys (pids/decode-mode-01 data) [:name :value]))
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

