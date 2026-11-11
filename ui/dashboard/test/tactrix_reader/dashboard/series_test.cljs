(ns tactrix-reader.dashboard.series-test
  (:require [cljs.test :refer [deftest is testing]]
            [tactrix-reader.dashboard.series :as series]))

(deftest event-points-match-the-device-library
  (is (= [[:pid/engine-rpm :rpm 800]]
         (series/event-points {:type :sample :name :engine-rpm :value 800 :unit :rpm})))
  (is (= [[:pid/wideband-oxygen-sensor-1-current.lambda :lambda 0.998]]
         (series/event-points {:type :sample :name :wideband-oxygen-sensor-1-current
                               :value {:lambda 0.998}})))
  (is (= [[:signal/brake nil 1]] (series/event-points {:type :signal :signal :brake :value true})))
  (is (= [[:signal/gear nil nil]] (series/event-points {:type :signal :signal :gear :value :neutral})))
  (is (= [[:did/x10A3 :milliseconds 4.1]]
         (series/event-points {:type :did :did 0x10A3 :data [16] :value 4.1 :unit :milliseconds})))
  (is (= [[:did/x10F0 nil 258]] (series/event-points {:type :did :did 0x10F0 :data [1 2]}))))

(deftest append-batch-builds-a-log-from-the-stream
  (let [log (-> nil
                (series/append-batch {:samples [{:name :engine-rpm :value 800 :unit :rpm :timestamp-us 5000000}]})
                (series/append-batch {:samples [{:name :engine-rpm :value 900 :unit :rpm :timestamp-us 5050000}]
                                      :dids [{:did 0x10A3 :name :injector-pulse-width :data [16] :value 4.1
                                              :unit :milliseconds :timestamp-us 5060000}]})
                (series/append-batch {:samples [{:name :engine-rpm :value 1000 :unit :rpm :timestamp-us 5150000}
                                                ;; Older than the channel's last point.
                                                {:name :engine-rpm :value 1 :unit :rpm :timestamp-us 5100000}]}))]
    (is (= 5000000 (:t0-us log)))
    (is (= 150 (:duration-ms log)))
    (testing "the last point in a bucket is kept"
      (is (= {:t [50 150] :v [900 1000]} (get-in log [:series :pid/engine-rpm]))))
    (is (= {:pid/engine-rpm {:unit :rpm}
            :did/x10A3 {:unit :milliseconds :name :injector-pulse-width}}
           (:channels log))))
  (is (nil? (series/append-batch nil {:samples []}))))

(def ^:private log
  {:duration-ms 999
   :channels {:pid/engine-rpm {:unit :rpm}
              :pid/maf-air-flow {:unit :grams-per-second}
              :signal/gear {}}
   :series {:pid/engine-rpm {:t [0 100 200 300 400 500 600 700 800 900]
                             :v [3000 3000 3000 3000 3000 200 3000 3000 3000 3000]}
            :pid/maf-air-flow {:t [0 300 600 900] :v [10 20 30 40]}
            ;; Gaps of 100 ms hold for 1 s at most.
            :signal/gear {:t [0 100 200] :v [nil nil nil]}}})

(deftest column-holds-values-on-the-grid
  (is (= 10 (series/row-count log)))
  (is (= [3000 3000 3000 3000 3000 200 3000 3000 3000 3000] (series/column log :pid/engine-rpm)))
  (testing "a slow channel is held between its points"
    (is (= [10 10 10 20 20 20 30 30 30 40] (series/column log :pid/maf-air-flow))))
  (testing "a value is held only as long as max-age"
    (let [sparse {:duration-ms 2999 :series {:k {:t [0 100 200] :v [1 2 3]}}}]
      (is (= 1000 (series/max-age [0 100 200])))
      (is (= [1 2 3 3 3 3 3 3 3 3 3 3 nil] (take 13 (series/column sparse :k))))))
  (is (= 9000 (series/max-age [0 3000 6000])))
  (is (= 10000 (series/max-age [0 5000 10000])))
  (is (= (repeat 10 nil) (series/column log :pid/missing))))

(deftest load-is-maf-over-rpm
  (let [load (series/column log series/load-key)]
    (is (= 0.2 (first load)))
    (is (= 0.4 (nth load 3)))
    (testing "nil below 300 rpm"
      (is (nil? (nth load 5)))))
  (testing "the derived load is a channel once MAF and RPM are there, and an all-nil channel is not"
    (is (= #{:pid/engine-rpm :pid/maf-air-flow series/load-key} (set (keys (series/channels log)))))))

(deftest axes
  (is (= [800 1200 1600 2000 2400 2800 3200 3600 4000 4400 4800 5200 5600 6000 6400]
         (series/default-axis :pid/engine-rpm [])))
  (is (= [0 5 10 15 20 25 30 35 40 45 50 55 60]
         (series/default-axis :pid/timing-advance (range 0 60.5 0.5))))
  (is (= [-10 -8 -6 -4 -2 0 2 4 6 8 10]
         (series/default-axis :pid/short-term-fuel-trim-bank-1 (range -10 10.01 0.1))))
  (is (= [3] (series/default-axis :k [3 3 3])))
  (is (nil? (series/default-axis :k [nil])))
  (is (= [0.1 0.25 2] (series/parse-axis "2, 0.1 0.25,0.1")))
  (is (nil? (series/parse-axis "1, x")))
  (is (nil? (series/parse-axis " ")))
  (is (= "0.1, 0.25, 2" (series/format-axis [0.1 0.25 2]))))

(deftest bin-puts-rows-in-the-nearest-cells
  (let [xs [790 1100 1300 9000 nil 1200]
        ys [0.12 0.18 0.31 0.5 0.2 0.2]
        zs [10 20 30 40 50 nil]
        cells (series/bin xs ys zs [800 1200 1600] [0.1 0.2 0.3] (constantly true))]
    (is (= {[0 0] {:count 1 :sum 10 :min 10 :max 10 :x-sum 790 :y-sum 0.12 :rows [0]}
            [1 1] {:count 1 :sum 20 :min 20 :max 20 :x-sum 1100 :y-sum 0.18 :rows [1]}
            ;; 9000 rpm and 0.5 g/rev are past the ends.
            [1 2] {:count 1 :sum 30 :min 30 :max 30 :x-sum 1300 :y-sum 0.31 :rows [2]}
            [2 2] {:count 1 :sum 40 :min 40 :max 40 :x-sum 9000 :y-sum 0.5 :rows [3]}}
           cells))
    (is (= 25 (series/cell-stat :mean {:count 2 :sum 50})))
    (is (= 2 (series/cell-stat :count {:count 2 :sum 50})))
    (is (= {} (series/bin xs ys zs [] [0.1] (constantly true))))))

(deftest row-filter-combines-the-window-and-filters
  (let [coolant [60 80 nil 90]
        keep? (series/row-filter [100 300] [{:op :>= :value 75 :column coolant}
                                            {:op :<= :value nil :column coolant}])]
    (is (= [1 3] (filter keep? (range 4))))
    (is (= [0 1 2 3] (filter (series/row-filter nil []) (range 4))))
    (is (= [0] (filter (series/row-filter nil [{:op :<= :value 70 :column coolant}]) (range 4))))))

(deftest envelope-gives-the-range-in-each-slice
  (is (= [[1 2] nil [5 9]] (series/envelope [1 2 nil nil 9 5] 3))))

(deftest sparkline-draws-the-last-window-held-between-points
  (let [log {:duration-ms 60000
             :series {:pid/engine-rpm {:t [0 30000 45000 60000] :v [900 1000 2000 1000]}
                      :signal/brake {:t [10000 50000] :v [0 0]}
                      :signal/gear {:t [10000 50000] :v [nil nil]}
                      :pid/coolant-temp {:t [59000] :v [90]}}}]
    ;; The window is the last 30 s, 100 wide: 30 s is at 0, 45 s at 50. The
    ;; value holds until the next point, then steps. 1000 is the bottom.
    (is (= "M0.0 23.0 H50.0 V1.0 H100.0 V23.0"
           (series/sparkline log :pid/engine-rpm 30000 100 24)))
    ;; A value that has not moved is a line across the middle.
    (is (= "M16.7 12.0 H83.3" (series/sparkline log :signal/brake 60000 100 24)))
    (is (nil? (series/sparkline log :signal/gear 60000 100 24)) "no numbers")
    (is (nil? (series/sparkline log :pid/coolant-temp 60000 100 24)) "one point")
    (is (nil? (series/sparkline log :pid/missing 60000 100 24)))
    (is (nil? (series/sparkline nil :pid/engine-rpm 60000 100 24)))))
