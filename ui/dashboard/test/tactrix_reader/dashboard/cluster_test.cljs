(ns tactrix-reader.dashboard.cluster-test
  (:require [cljs.test :refer [deftest is testing]]
            [tactrix-reader.dashboard.cluster :as cluster]))

(def ^:private tach (:tach cluster/gauges))

(deftest a-needle-sweeps-its-scale-and-stops-at-the-ends
  (is (= -135 (cluster/angle tach 0)))
  (is (= 0 (cluster/angle tach 4000)))
  (is (= 135 (cluster/angle tach 8000)))
  (testing "off the scale"
    (is (= 135 (cluster/angle tach 9200)))
    (is (= -135 (cluster/angle tach -50))))
  (testing "no value rests at the bottom"
    (is (= -135 (cluster/angle tach nil)))
    (is (= -55 (cluster/angle (:fuel cluster/gauges) nil))))
  (testing "a scale that does not start at 0"
    (is (= 0 (cluster/angle (:coolant cluster/gauges) 90)))))

(deftest the-speedometer-reads-in-either-unit
  (let [mph (get cluster/gauges (:miles-per-hour cluster/speed-units))]
    (is (= 100 (cluster/gauge-value (:speed cluster/gauges) 100)))
    (is (< 62.1 (cluster/gauge-value mph 100) 62.2))
    (is (nil? (cluster/gauge-value mph nil)))
    (testing "150 mph is not a multiple of 20, so the top mark has no number"
      (is (= ["0" "20" "40" "60" "80" "100" "120" "140"] (keep :label (cluster/ticks mph))))
      (is (= 150 (:value (last (cluster/ticks mph))))))))

(deftest values-are-shown-in-the-chosen-units
  (let [metric {:speed-unit :km-per-hour :temperature-unit :celsius}
        imperial {:speed-unit :miles-per-hour :temperature-unit :fahrenheit}]
    (testing "as they come, by default"
      (is (= [90 :celsius] (cluster/convert metric 90 :celsius)))
      (is (= [6.7 :litres-per-100-km] (cluster/convert metric 6.7 :litres-per-100-km))))
    (testing "temperatures in Fahrenheit"
      (is (= [194 :fahrenheit] (cluster/convert imperial 90 :celsius)))
      (is (= [-40 :fahrenheit] (cluster/convert imperial -40 :celsius))))
    (testing "the fuel economy in miles per US gallon, with the speed in mph"
      (let [[value unit] (cluster/convert imperial 6.7 :litres-per-100-km)]
        (is (= :miles-per-gallon unit))
        (is (< 35.1 value 35.2)))
      (is (= [99.9 :miles-per-gallon] (cluster/convert imperial 0 :litres-per-100-km))
          "fuel cut")
      (is (= [99.9 :miles-per-gallon] (cluster/convert imperial 1.2 :litres-per-100-km))))
    (testing "each setting stands alone"
      (is (= [90 :celsius] (cluster/convert (assoc metric :speed-unit :miles-per-hour) 90 :celsius)))
      (is (= [6.7 :litres-per-100-km]
             (cluster/convert (assoc metric :temperature-unit :fahrenheit) 6.7 :litres-per-100-km))))
    (testing "no value yet still has the chosen unit"
      (is (= [nil :fahrenheit] (cluster/convert imperial nil :celsius)))
      (is (= [nil :miles-per-gallon] (cluster/convert imperial nil :litres-per-100-km))))
    (testing "what is not a temperature or an economy"
      (is (= [97 :percent] (cluster/convert imperial 97 :percent)))
      (is (= [:neutral nil] (cluster/convert imperial :neutral nil))))))

(deftest ticks-number-the-major-marks
  (let [ticks (cluster/ticks tach)
        major (filter :major? ticks)]
    (is (= 41 (count ticks)))
    (is (= ["0" "1" "2" "3" "4" "5" "6" "7" "8"] (map :label major)))
    (is (= [-135 135] ((juxt first last) (map :angle ticks))))
    (is (not-any? :label (remove :major? ticks)))
    (testing "the red zone"
      (is (= 6600 (:value (first (filter :red? ticks)))))
      (is (= [84.375 135] (cluster/zone tach)))))
  (testing "a gauge with letters is labelled only where it has them"
    (let [ticks (cluster/ticks (:fuel cluster/gauges))]
      (is (= [0 25 50 75 100] (map :value ticks)))
      (is (= ["E" nil nil nil "F"] (map :label ticks)))
      (is (= [true false false false false] (map (comp boolean :low?) ticks)))
      (is (= [-55 -41.25] (cluster/zone (:fuel cluster/gauges)))))))

(deftest points-and-arcs-are-clockwise-from-straight-up
  (is (= [100 20] (cluster/point [100 100] 80 0)))
  (is (= [180 100] (cluster/point [100 100] 80 90)))
  (is (= [20 100] (cluster/point [100 100] 80 -90)))
  (is (= "M 20 100 A 80 80 0 0 1 180 100" (cluster/arc [100 100] 80 -90 90)))
  (testing "more than half a turn takes the long way round"
    (is (= "M 43.43 156.57 A 80 80 0 1 1 156.57 156.57" (cluster/arc [100 100] 80 -135 135)))))

(defn- states [samples signals]
  (into {} (map (juxt :id :state)) (cluster/lamps samples signals)))

(deftest lamps-are-unknown-until-something-says
  (is (= {:check-engine :unknown :parking-brake :unknown :seatbelt :unknown :door :unknown
          :parking-lights :unknown :headlights :unknown}
         (states {} {}))))

(deftest lamps-follow-the-signals
  (is (= {:check-engine :off :parking-brake :on :seatbelt :on :door :off
          :parking-lights :on :headlights :off}
         (states {}
                 {:check-engine-lamp {:value :off}
                  :parking-brake {:value true}
                  :driver-seatbelt {:value :unbuckled}
                  :door-front-left {:value false}
                  :parking-lights {:value true}
                  :headlights {:value false}})))
  (testing "any open door lights the lamp"
    (is (= :on (:door (states {} {:door-front-left {:value false} :door-rear-right {:value true}})))))
  (testing "the check engine lamp flashes, and falls back to PID 01"
    (is (= :flashing (:check-engine (states {} {:check-engine-lamp {:value :flashing}}))))
    (is (= :on (:check-engine (states {:monitor-status {:value {:mil-on? true}}} {}))))
    (is (= :off (:check-engine (states {:monitor-status {:value {:mil-on? true}}}
                                       {:check-engine-lamp {:value :off}}))))))
