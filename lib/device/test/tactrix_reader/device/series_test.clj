(ns tactrix-reader.device.series-test
  (:require [clojure.test :refer [deftest is testing]]
            [tactrix-reader.device.series :as series]
            [tactrix-reader.device.test-support :refer [message-bytes temp-dir]]))

(deftest event-points-flatten-values-to-numbers
  (testing "a number keeps its unit"
    (is (= [[:pid/engine-rpm :rpm 800.0]]
           (series/event-points {:type :sample :pid 0x0C :name :engine-rpm :value 800.0 :unit :rpm}))))
  (testing "a map gives one point per entry"
    (is (= [[:pid/wideband-oxygen-sensor-1-current.lambda :lambda 0.998]
            [:pid/wideband-oxygen-sensor-1-current.milliamps :milliamps -0.02]]
           (series/event-points {:type :sample :name :wideband-oxygen-sensor-1-current
                                 :value {:lambda 0.998 :milliamps -0.02}})))
    (is (= [[:signal/wheel-speeds.front-left :km-per-hour 12.5]]
           (series/event-points {:type :signal :signal :wheel-speeds :unit :km-per-hour
                                 :value {:front-left 12.5}}))))
  (testing "a boolean is 1 or 0, and anything else is nil"
    (is (= [[:signal/brake nil 1]] (series/event-points {:type :signal :signal :brake :value true})))
    (is (= [[:signal/gear nil nil]] (series/event-points {:type :signal :signal :gear :value :neutral}))))
  (testing "a DID is its decoded value, or else its bytes as a number"
    (is (= [[:did/x10A3 :grams-per-revolution 0.304]]
           (series/event-points {:type :did :did 0x10A3 :did-hex "10A3" :data [16]
                                 :value 0.304 :unit :grams-per-revolution})))
    (is (= [[:did/x10F0 nil 258]]
           (series/event-points {:type :did :did 0x10F0 :did-hex "10F0" :data [1 2]}))))
  (is (nil? (series/event-points {:type :status :state :running}))))

(defn- write-raw! [dir raw]
  (java.nio.file.Files/write (.toPath (java.io.File. dir "raw.bin"))
                             (byte-array (map unchecked-byte raw))
                             (make-array java.nio.file.OpenOption 0)))

(deftest recording-series-keeps-the-last-point-in-each-bucket
  (let [dir (temp-dir)]
    (write-raw! dir (concat (message-bytes 5 0 1000000 0x7E8 [4 0x41 0x0C 0x0A 0x3F 0 0 0])
                            (message-bytes 5 0 1050000 0x7E8 [4 0x41 0x0C 0x0C 0x80 0 0 0])
                            (message-bytes 5 0 1150000 0x7E8 [4 0x41 0x0C 0x0A 0x3F 0 0 0])
                            (message-bytes 5 0 1160000 0x0D3 [0 0 0x20 0 0 0 0 0])
                            (message-bytes 5 0 1170000 0x7E8 [4 0x62 0x10 0xA5 0x80 0 0 0])
                            (message-bytes 5 0 1180000 0x7E8 [4 0x62 0x10 0xA3 0x10 0 0 0])
                            (message-bytes 5 0 1200000 0x141 [0 0 0 0 0 0 3 0])
                            (message-bytes 5 0 1260000 0x282 [0 0 0 0 112 1 0 0])
                            (message-bytes 5 0 1300000 0x141 [0 0 0 0 0 0 7 0])))
    (let [{:keys [ok? bucket-ms duration-ms channels series]} (series/recording-series (str dir))]
      (is ok?)
      (is (= 100 bucket-ms))
      (is (= 300 duration-ms))
      (is (= {:t [50 150] :v [800.0 655.75]} (:pid/engine-rpm series)))
      (is (= {:t [160] :v [1]} (:signal/brake series)))
      (is (= {:t [170] :v [128]} (:did/x10A5 series)))
      (is (= {:t [180] :v [0.304]} (:did/x10A3 series)))
      (testing "neutral ends the gear held from before"
        (is (= {:t [200 300] :v [3 nil]} (:signal/gear series))))
      (testing "a channel that never had a number is left out"
        (is (contains? series :signal/fuel-sender))
        (is (not (contains? series :signal/driver-seatbelt))))
      (is (= {:unit :rpm} (:pid/engine-rpm channels)))
      (is (= {:unit :grams-per-revolution :name :air-per-revolution} (:did/x10A3 channels)))
      (is (= {} (:did/x10A5 channels)))
      (is (= (set (keys series)) (set (keys channels)))))))

(deftest recording-series-needs-samples
  (let [dir (temp-dir)]
    (write-raw! dir (message-bytes 5 0 1000000 0x372 [0 0xA8 0 0 0 0 0 0]))
    (is (= {:ok? false :error :no-samples} (series/recording-series (str dir))))))
