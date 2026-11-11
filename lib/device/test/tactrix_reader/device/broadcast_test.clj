(ns tactrix-reader.device.broadcast-test
  (:require [clojure.test :refer [deftest is]]
            [tactrix-reader.device.broadcast :as broadcast]))

(deftest broadcast-samples-decode-confirmed-signals
  ;; Frames from the 2026-10-24-idle recording.
  (is (= [{:pid 0x0C :name :engine-rpm :value 658.0 :unit :rpm :can-id 0x140 :timestamp-us 5}
          {:pid 0x11 :name :throttle-position :value 13.7 :unit :percent :can-id 0x140 :timestamp-us 5}]
         (broadcast/broadcast-samples {:type :message :id 0x140 :timestamp-us 5
                                       :data [0x00 0x0A 0x92 0x42 0x00 0x00 0x09 0x81]})))
  (is (= [{:pid 0x5C :name :oil-temp :value 92 :unit :celsius :can-id 0x360 :timestamp-us 5}
          {:pid 0x05 :name :coolant-temp :value 97 :unit :celsius :can-id 0x360 :timestamp-us 5}
          {:pid 0x0B :name :intake-manifold-pressure :value 28 :unit :kpa :can-id 0x360 :timestamp-us 5}]
         (broadcast/broadcast-samples {:type :message :id 0x360 :timestamp-us 5
                                       :data [0xAC 0x00 0x84 0x89 0x0E 0x00 0x01 0x00]})))
  ;; Frames from the 2026-10-28-drive recording: 54 km/h, and wide-open throttle.
  (is (= [{:pid 0x0D :name :vehicle-speed :value 54.0 :unit :km-per-hour :can-id 0x0D1 :timestamp-us 5}]
         (broadcast/broadcast-samples {:type :message :id 0x0D1 :timestamp-us 5
                                       :data [0xC0 0x03 0x00 0x00]})))
  (is (= 80.4 (:value (second (broadcast/broadcast-samples {:type :message :id 0x140 :timestamp-us 5
                                                            :data [0xFF 0x0A 0x92 0x42 0x00 0xFF 0xFF 0x00]})))))
  (is (= [:engine-rpm] (map :name (broadcast/broadcast-samples {:type :message :id 0x140 :timestamp-us 5
                                                                :data [0x00 0x0A 0x92 0x42 0x00 0x00]}))))
  (is (empty? (broadcast/broadcast-samples {:type :message :id 0x141 :timestamp-us 5
                                            :data [0x65 0x26 0x3D 0x27 0x88 0xC2 0x27 0x00]})))
  (is (empty? (broadcast/broadcast-samples {:type :message :id 0x140 :timestamp-us 5
                                            :data [0x00 0x0A 0x92]})))
  (is (empty? (broadcast/broadcast-samples {:type :line :text "aro"})))
  (is (empty? (broadcast/broadcast-samples {} {:type :message :id 0x140 :timestamp-us 5
                                               :data [0x00 0x0A 0x92 0x42 0x00 0x00 0x09 0x81]}))))

(deftest signal-samples-decode-inferred-signals
  (let [decode (fn [id data]
                 (into {} (map (juxt :signal :value))
                       (broadcast/signal-samples {:type :message :id id :timestamp-us 5 :data data})))]
    ;; Frames from the 2026-10-28-drive recording, at 120 s in 5th gear at
    ;; about 101 km/h.
    (is (= {:steering-angle -1.3} (decode 0x002 [0xFF 0xF3 0x70 0x03 0x65 0x00 0x00 0x00])))
    (is (= {:yaw-rate 0.3 :lateral-acceleration -0.02 :longitudinal-acceleration -0.04}
           (decode 0x0D0 [0xFC 0xFF 0x01 0x00 0x0B 0x34 0xFF 0x04])))
    (is (= {:brake-pressure 0} (decode 0x0D1 [0x05 0x07 0x00 0x00])))
    (is (= {:brake false} (decode 0x0D3 [0x00 0x06 0xC0 0x0F 0x50 0x00 0x59])))
    (is (= {:wheel-speeds {:front-left 101.1 :front-right 101.1 :rear-left 101.0 :rear-right 101.0}}
           (decode 0x0D4 [0x05 0x07 0x06 0x07 0x03 0x07 0x03 0x07])))
    (is (= {:gear 5 :requested-torque 30.2 :engine-torque 26.5 :fuel-cut false}
           (decode 0x141 [0x3E 0x28 0x19 0x28 0x5D 0x0A 0x05 0x00])))
    (is (= {:parking-brake false :reverse false}
           (decode 0x152 [0xF9 0xA4 0x00 0x00 0x00 0x00 0x00 0x0C])))
    (is (= {:fuel-sender 100 :driver-seatbelt :buckled}
           (decode 0x282 [0x64 0xC0 0x00 0x5E 0x5E 0x00 0x22 0x00])))
    (is (= {:fuel-flow 0.783} (decode 0x360 [0x40 0x04 0x62 0x70 0x13 0x00 0x01 0x00])))
    ;; Braking at 20 s.
    (is (= {:brake-pressure 5} (decode 0x0D1 [0xA9 0x00 0x05 0x00])))
    (is (= {:brake true} (decode 0x0D3 [0x00 0x06 0xE0 0x0F 0x00 0x00 0xDA])))
    ;; Idling in neutral with the parking brake on at the end.
    (is (= {:parking-brake true :reverse false}
           (decode 0x152 [0xF9 0xD4 0x00 0x00 0x00 0x00 0x08 0x0C])))
    (is (= {:gear :neutral :requested-torque -17.1 :engine-torque 3.1 :fuel-cut false}
           (decode 0x141 [0x65 0x26 0x2F 0x27 0xFE 0x82 0x27 0x00])))
    (is (= {:pedal-travel 0.0 :clutch false}
           (decode 0x140 [0x00 0x0A 0x92 0x42 0x00 0x00 0x09 0x81])))
    ;; 2026-10-30-did-drive: backing out with the clutch slipping at 18 s, a
    ;; tight right turn at 20 km/h at 48 s, and fuel cut in 2nd at 58 s.
    (is (= {:pedal-travel 13.7 :clutch true}
           (decode 0x140 [0x23 0x83 0xD3 0x06 0x00 0x23 0x15 0x00])))
    (is (= {:parking-brake false :reverse true}
           (decode 0x152 [0xF9 0x04 0x00 0x00 0x00 0x00 0x04 0x02])))
    (is (= {:yaw-rate -38.4 :lateral-acceleration -0.33 :longitudinal-acceleration 0.08}
           (decode 0x0D0 [0xAD 0x0A 0x7A 0xFF 0xD7 0x0B 0xF0 0xF8])))
    (is (= {:gear 2 :requested-torque -26.0 :engine-torque -26.0 :fuel-cut true}
           (decode 0x141 [0x0C 0x26 0x0C 0x26 0x13 0x8C 0x42 0x08])))
    (is (= {:fuel-flow 0.0} (decode 0x360 [0x00 0x00 0x76 0x81 0x0D 0x00 0x01 0x00])))
    ;; The battery current in 2026-10-28-drive: with the ignition on and the
    ;; engine off, pinned while the starter cranks, and charging after the
    ;; start. In 2026-10-30-did-drive at 275 s, with the alternator backed
    ;; off under power.
    ;; The check engine lamp is lit until the starter turns the engine,
    ;; which `361` also reports as stopped until then. The barometer read
    ;; 221 that day, and 218 two days later, when PID 33 read 100 kPa.
    (let [running {:check-engine-lamp :off :engine-stopped false}]
      (is (= {:check-engine-lamp :on :barometer 101.6 :engine-stopped true :battery-current -329}
             (decode 0x361 [0x00 0x09 0x00 0xDD 0x27 0x70 0x6B 0x73])))
      (is (= {:check-engine-lamp :off :barometer 101.6 :engine-stopped false :battery-current -1798}
             (decode 0x361 [0x00 0x39 0x00 0xDD 0x06 0xA0 0x0F 0x73])))
      (is (= (assoc running :barometer 101.6 :battery-current 312)
             (decode 0x361 [0x00 0x29 0x00 0xDD 0x0F 0x80 0x93 0x73])))
      (is (= (assoc running :barometer 100.4 :battery-current -183)
             (decode 0x361 [0x05 0x39 0x00 0xDA 0x0D 0x90 0x74 0x85]))))
    ;; The lamp's other codes: 4 is flashing on a 2015 WRX, and the rest
    ;; are unknown.
    (is (= :flashing (:check-engine-lamp (decode 0x361 [0x00 0x89 0x00 0xDD 0x00 0x00 0x80 0x73]))))
    (is (nil? (:check-engine-lamp (decode 0x361 [0x00 0x49 0x00 0xDD 0x00 0x00 0x80 0x73]))))
    ;; Parked at 63 F in 2026-11-09-194644, with half a tank.
    (is (= {:outside-temp 17.3} (decode 0x3D1 [0x14 0x10 0x00 0x00 0x00 0x00 0x00 0x00])))
    (is (= {:fuel-sender 488 :driver-seatbelt :unbuckled}
           (decode 0x282 [0xE8 0xB1 0x00 0x67 0x67 0x03 0x22 0x00])))
    ;; The start of 2026-11-09-144943, and after coolant passed 71 C (200 s)
    ;; and the fuel gauge dropped a count (500 s).
    (is (= {:available-torque 137.6 :engine-starts 0x65 :warm-up-cycles 0x23}
           (decode 0x144 [0xC0 0x01 0x56 0x65 0x23 0xA4 0xA0 0x00])))
    (is (= {:available-torque 158.4 :engine-starts 0x65 :warm-up-cycles 0x24}
           (decode 0x144 [0xC0 0x00 0x63 0x65 0x24 0xA4 0xA0 0x00])))
    ;; Near its peak, which comes around 4,500 rpm.
    (is (= 184.0 (:available-torque (decode 0x144 [0xC0 0x00 0x73 0x65 0x24 0xA4 0xA0 0x00]))))
    (is (= {:fuel-gauge 17} (decode 0x156 [0x01 0x80 0x00 0x00 0x00 0x00 0x00 0x11])))
    (is (= {:fuel-gauge 16} (decode 0x156 [0x01 0x80 0x00 0x00 0x00 0x00 0x00 0x10])))
    ;; Each door opened and closed in turn in 2026-11-09-194644.
    (let [doors (fn [data] (decode 0x374 data))
          closed {:door-front-left false :door-front-right false
                  :door-rear-left false :door-rear-right false}]
      (is (= closed (doors [0x44 0x08 0x00 0x80 0x80 0x00 0x00 0x00])) "11 s")
      (is (= (assoc closed :door-front-left true) (doors [0x44 0x08 0x00 0x81 0x00 0x00 0x00 0x00])) "5 s")
      (is (= (assoc closed :door-front-right true) (doors [0x44 0x08 0x00 0x82 0x00 0x00 0x00 0x00])) "17 s")
      (is (= (assoc closed :door-rear-right true) (doors [0x44 0x08 0x00 0x84 0x00 0x00 0x00 0x00])) "30 s")
      (is (= (assoc closed :door-rear-left true) (doors [0x44 0x08 0x00 0x88 0x00 0x00 0x00 0x00])) "41 s")))
  (is (= [{:signal :gear :value 5 :unit nil :can-id 0x141 :timestamp-us 5}
          {:signal :requested-torque :value 30.2 :unit :newton-metres :can-id 0x141 :timestamp-us 5}
          {:signal :engine-torque :value 26.5 :unit :newton-metres :can-id 0x141 :timestamp-us 5}
          {:signal :fuel-cut :value false :unit nil :can-id 0x141 :timestamp-us 5}]
         (broadcast/signal-samples {:type :message :id 0x141 :timestamp-us 5
                                    :data [0x3E 0x28 0x19 0x28 0x5D 0x0A 0x05 0x00]})))
  ;; A short frame gives only the signals it is long enough for.
  (is (= [:requested-torque :engine-torque]
         (map :signal (broadcast/signal-samples {:type :message :id 0x141 :timestamp-us 5
                                                 :data [0x3E 0x28 0x19 0x28 0x5D 0x0A]}))))
  (is (empty? (broadcast/signal-samples {:type :message :id 0x141 :timestamp-us 5 :data [0x3E]})))
  (is (empty? (broadcast/signal-samples {} {:type :message :id 0x141 :timestamp-us 5
                                            :data [0x3E 0x28 0x19 0x28 0x5D 0x0A 0x05 0x00]})))
  (is (empty? (broadcast/signal-samples {:type :line :text "aro"}))))

(deftest decode-bits-finds-the-bits-a-decoding-reads
  (let [bits (fn [signals id key value]
               (broadcast/decode-bits (:decode (first (filter #(= value (key %)) (get signals id))))))]
    ;; RPM is byte 2 and the low six bits of byte 3.
    (is (= [0 0 0xFF 0x3F] (bits broadcast/broadcast-signals 0x140 :pid 0x0C)))
    ;; The throttle's rounding does not hide a bit.
    (is (= [0 0 0 0 0 0 0xFF] (bits broadcast/broadcast-signals 0x140 :pid 0x11)))
    (is (= [0 0 0 0 0xFF] (bits broadcast/broadcast-signals 0x360 :pid 0x0B)))
    (is (= [0 0x80] (bits broadcast/inferred-signals 0x140 :signal :clutch)))
    (is (= [0 0 0 0 0 0 0x07] (bits broadcast/inferred-signals 0x141 :signal :gear)))
    (is (= [0xFF 0x7F] (bits broadcast/inferred-signals 0x141 :signal :requested-torque)))
    (is (= [0 0 0 0 0 0 0 0x08] (bits broadcast/inferred-signals 0x141 :signal :fuel-cut)))
    (is (= [0 0 0xFF 0xFF] (bits broadcast/inferred-signals 0x0D0 :signal :yaw-rate)))
    (is (= [0 0 0 0 0 0 0 0xFF] (bits broadcast/inferred-signals 0x0D0 :signal :longitudinal-acceleration)))
    (is (= [0 0 0 0 0 0xF0 0xFF] (bits broadcast/inferred-signals 0x361 :signal :battery-current)))
    ;; A value that is one of a few words.
    (is (= [0 0xE0] (bits broadcast/inferred-signals 0x361 :signal :check-engine-lamp)))
    (is (= [0 0 0 0xFF] (bits broadcast/inferred-signals 0x361 :signal :barometer)))
    (is (= [0 0 0 0 0x20] (bits broadcast/inferred-signals 0x361 :signal :engine-stopped)))
    (is (= [0 0 0xFF] (bits broadcast/inferred-signals 0x144 :signal :available-torque)))
    (is (= [0xFF 0x0F] (bits broadcast/inferred-signals 0x282 :signal :fuel-sender)))
    (is (= [0 0 0 0 0 0x01] (bits broadcast/inferred-signals 0x282 :signal :driver-seatbelt))))
  ;; One part of a value that is a map.
  (let [wheel-speeds (:decode (first (get broadcast/inferred-signals 0x0D4)))]
    (is (= [0xFF 0xFF 0xFF 0xFF 0xFF 0xFF 0xFF 0xFF] (broadcast/decode-bits wheel-speeds)))
    (is (= [0 0 0 0 0xFF 0xFF] (broadcast/decode-bits wheel-speeds :rear-left))))
  ;; A decoding that reads nothing has no bits.
  (is (= [] (broadcast/decode-bits (constantly 1)))))
