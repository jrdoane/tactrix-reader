(ns tactrix-reader.device.derived-test
  (:require [clojure.test :refer [deftest is testing]]
            [tactrix-reader.device.derived :as derived]))

(defn- sample [name value t & {:as more}]
  (merge {:type :sample :name name :value value :timestamp-us t} more))

(defn- signal [name value t]
  {:type :signal :signal name :value value :timestamp-us t})

(defn- run
  "The derived events a sequence of source events gives rise to, as
  `[signal value timestamp-us]`."
  [events]
  (->> events
       (reduce (fn [[state out] event]
                 (let [[state derived] (derived/step state event)]
                   [state (into out derived)]))
               [derived/initial-state []])
       second
       (mapv (juxt :signal :value :timestamp-us))))

(deftest power-and-clutch-slip-come-with-the-torque
  (testing "power needs RPM, and slip RPM and speed"
    (is (= [] (run [(signal :engine-torque 100.0 1000)])))
    (is (= [[:engine-power 31.4 2000]]
           (run [(sample :engine-rpm 3000.0 1000) (signal :engine-torque 100.0 2000)]))))
  (testing "slip is the engine's speed over the gearbox's, in the gear engaged"
    (is (= [[:engine-power 31.4 4000] [:clutch-slip 236 4000]]
           (run [(sample :engine-rpm 3000.0 1000)
                 (sample :vehicle-speed 60.0 2000)
                 (signal :gear 3 3000)
                 (signal :engine-torque 100.0 4000)]))))
  (testing "slip has no value in neutral, in reverse, or before a gear is known"
    (doseq [selected [[(signal :gear :neutral 3000)]
                      [(signal :gear 1 3000) (signal :reverse true 3000)]
                      []]]
      (is (= [[:engine-power -5.2 4000] [:clutch-slip nil 4000]]
             (run (concat [(sample :engine-rpm 2000.0 1000) (sample :vehicle-speed 10.0 2000)]
                          selected
                          [(signal :engine-torque -25.0 4000)]))))))
  (testing "an event carries its unit and the time of what set it off"
    (is (= [{:type :signal :signal :engine-power :value 31.4 :unit :kilowatts :timestamp-us 2000}]
           (second (derived/step (first (derived/step derived/initial-state (sample :engine-rpm 3000.0 1000)))
                                 (signal :engine-torque 100.0 2000)))))))

(deftest values-are-not-worked-out-from-stale-ones
  (is (= [] (run [(sample :engine-rpm 3000.0 0) (signal :engine-torque 100.0 1000001)])))
  (is (= [[:engine-power 31.4 1000000]]
         (run [(sample :engine-rpm 3000.0 0) (signal :engine-torque 100.0 1000000)]))))

(deftest a-value-comes-at-most-twenty-times-a-second
  (is (= [[:engine-power 31.4 10000] [:engine-power 62.8 60000]]
         (run (cons (sample :engine-rpm 3000.0 0)
                    (for [t (range 10000 70000 10000)]
                      (signal :engine-torque (if (< t 60000) 100.0 200.0) t)))))))

(deftest fuel-economy-needs-some-speed
  (is (= [[:fuel-economy 9.7 2000]]
         (run [(sample :vehicle-speed 50.0 1000) (signal :fuel-flow 1.0 2000)])))
  (is (= [[:fuel-economy 0.0 2000]]
         (run [(sample :vehicle-speed 50.0 1000) (signal :fuel-flow 0.0 2000)])))
  (is (= [[:fuel-economy nil 2000]]
         (run [(sample :vehicle-speed 4.9 1000) (signal :fuel-flow 0.12 2000)])))
  (is (= [] (run [(signal :fuel-flow 1.0 2000)]))))

(deftest injector-duty-comes-with-the-pulse-width
  (let [did (fn [data value t]
              (cond-> {:type :did :did 0x10A3 :data data :timestamp-us t}
                value (assoc :value value :unit :milliseconds)))]
    ;; Open for 4 ms of the 40 ms two revolutions take at 3,000 rpm.
    (is (= [[:injector-duty 10.0 2000]]
           (run [(sample :engine-rpm 3000.0 1000) (did [16] 4.0 2000)])))
    (is (= [] (run [(did [16] 4.0 2000)])))
    (is (= [] (run [(sample :engine-rpm 3000.0 1000) (did [16 0] nil 2000)])) "an undecoded reply")
    (is (= [] (run [(sample :engine-rpm 3000.0 1000)
                    {:type :did :did 0x10A5 :data [128] :value 0.0 :timestamp-us 2000}])))))

(defn- drive
  "A second and a half of frames 20 ms apart, or `seconds`: the broadcast
  speed, from `speed` (a function of seconds, in km/h), and the
  acceleration sensor reading `felt` g."
  ([speed felt]
   (drive speed felt 1.5))
  ([speed felt seconds]
   (mapcat (fn [i]
             (let [t (* i 20000)]
               [(sample :vehicle-speed (speed (/ t 1e6)) t :can-id 0x0D1)
                (signal :longitudinal-acceleration felt (+ t 5000))]))
           (range (inc (long (* 50 seconds)))))))

(deftest road-grade-is-what-the-sensor-feels-less-what-the-wheels-show
  (let [grades (fn [events] (map second (filter (comp #{:road-grade} first) (run events))))]
    (testing "a steady speed up a 5% slope"
      (let [values (grades (drive (constantly 54.0) 0.05))]
        (is (nil? (first values)) "not until most of a second has been seen")
        (is (= 5.0 (last values)))
        (is (= #{nil 5.0} (set values)))))
    (testing "speeding up on the level, at 3.6 km/h a second"
      (is (= 0.0 (last (grades (drive #(+ 54.0 (* 3.6 %)) 0.102))))))
    (testing "slowing down the same on a 3% descent"
      (is (= -3.0 (last (grades (drive #(- 54.0 (* 3.6 %)) -0.132))))))
    (testing "no value near a standstill or in reverse"
      (is (= #{nil} (set (grades (drive (constantly 9.0) 0.05)))))
      (is (= #{nil} (set (grades (mapcat (fn [event] [(signal :reverse true (:timestamp-us event)) event])
                                         (drive (constantly 54.0) 0.05)))))))
    (testing "no value for a second after turning hard, in either direction"
      (doseq [yaw-rate [8.0 -30.0]]
        ;; Three seconds at a steady speed, with one reading of the turn at 1.5 s.
        (let [events (sort-by :timestamp-us (cons (signal :yaw-rate yaw-rate 1500000)
                                                  (drive (constantly 54.0) 0.05 3.0)))
              runs (->> (run events)
                        (filter (comp #{:road-grade} first))
                        (partition-by second)
                        (map (juxt (comp second first) (comp last first))))]
          (is (= [nil 5.0 nil 5.0] (map first runs)) (str yaw-rate))
          (is (< 1500000 (second (nth runs 2)) 1560000) "the first without a value")
          (is (< 2500000 (second (nth runs 3)) 2560000) "the first with one again")))
      (is (= #{nil 5.0} (set (grades (cons (signal :yaw-rate 7.9 0) (drive (constantly 54.0) 0.05)))))))
    (testing "the polled speed, in whole km/h, is left out"
      (is (= 5.0 (last (grades (mapcat (fn [[speed acceleration]]
                                         [speed (sample :vehicle-speed 99 (+ 1000 (:timestamp-us speed))) acceleration])
                                       (partition 2 (drive (constantly 54.0) 0.05))))))))))

(deftest a-jump-in-time-starts-again
  (testing "backwards, as when a replay loops"
    (is (= [[:engine-power 31.4 5000000]]
           (run [(sample :engine-rpm 3000.0 4999000)
                 (signal :engine-torque 100.0 5000000)
                 (signal :engine-torque 100.0 1000)]))))
  (testing "forwards by more than two seconds, as when it seeks"
    (is (= [nil 5.0 nil]
           (let [later (map #(update % :timestamp-us + 60000000) (take 4 (drive (constantly 54.0) 0.05)))]
             (->> (run (concat (drive (constantly 54.0) 0.05) later))
                  (map second)
                  (partition-by identity)
                  (map first)))))))

(deftest deriver-adds-derived-events-after-the-ones-they-come-from
  (let [derive (derived/deriver)
        rpm (sample :engine-rpm 3000.0 1000)
        status {:type :status :state :running}
        frame {:type :frame :id 0x141 :data [0] :timestamp-us 1500}
        torque (signal :engine-torque 100.0 2000)
        door (signal :door-front-left false 2500)]
    (is (= [rpm status frame] (derive [rpm status frame])))
    (testing "it remembers from one call to the next"
      (is (= [torque
              {:type :signal :signal :engine-power :value 31.4 :unit :kilowatts :timestamp-us 2000}
              door]
             (derive [torque door]))))
    (is (= [] (derive [])))))

(deftest every-derived-signal-is-described
  (is (= #{:engine-power :fuel-economy :injector-duty :road-grade :clutch-slip}
         (set (map :signal derived/derived-signals))))
  (is (every? (every-pred :unit :label :group (comp seq :from)) derived/derived-signals)))
