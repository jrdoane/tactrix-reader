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
    (is (= {:lateral-acceleration 0.01 :yaw-rate -0.7 :longitudinal-acceleration -0.04}
           (decode 0x0D0 [0xFC 0xFF 0x01 0x00 0x0B 0x34 0xFF 0x04])))
    (is (= {:brake-pressure 0} (decode 0x0D1 [0x05 0x07 0x00 0x00])))
    (is (= {:brake false} (decode 0x0D3 [0x00 0x06 0xC0 0x0F 0x50 0x00 0x59])))
    (is (= {:wheel-speeds {:front-left 101.1 :front-right 101.1 :rear-left 101.0 :rear-right 101.0}}
           (decode 0x0D4 [0x05 0x07 0x06 0x07 0x03 0x07 0x03 0x07])))
    (is (= {:gear 5} (decode 0x141 [0x3E 0x28 0x19 0x28 0x5D 0x0A 0x05 0x00])))
    (is (= {:parking-brake false} (decode 0x152 [0xF9 0xA4 0x00 0x00 0x00 0x00 0x00 0x0C])))
    (is (= {:outside-temp 12.2 :driver-seatbelt :buckled}
           (decode 0x282 [0x64 0xC0 0x00 0x5E 0x5E 0x00 0x22 0x00])))
    (is (= {:fuel-flow 0.74} (decode 0x360 [0x40 0x04 0x62 0x70 0x13 0x00 0x01 0x00])))
    ;; Braking at 20 s.
    (is (= {:brake-pressure 5} (decode 0x0D1 [0xA9 0x00 0x05 0x00])))
    (is (= {:brake true} (decode 0x0D3 [0x00 0x06 0xE0 0x0F 0x00 0x00 0xDA])))
    ;; Idling in neutral with the parking brake on at the end.
    (is (= {:parking-brake true} (decode 0x152 [0xF9 0xD4 0x00 0x00 0x00 0x00 0x08 0x0C])))
    (is (= {:gear :neutral} (decode 0x141 [0x65 0x26 0x2F 0x27 0xFE 0x82 0x27 0x00]))))
  (is (= [{:signal :gear :value 5 :unit nil :can-id 0x141 :timestamp-us 5}]
         (broadcast/signal-samples {:type :message :id 0x141 :timestamp-us 5
                                    :data [0x3E 0x28 0x19 0x28 0x5D 0x0A 0x05 0x00]})))
  (is (empty? (broadcast/signal-samples {:type :message :id 0x141 :timestamp-us 5
                                         :data [0x3E 0x28 0x19 0x28 0x5D 0x0A]})))
  (is (empty? (broadcast/signal-samples {} {:type :message :id 0x141 :timestamp-us 5
                                            :data [0x3E 0x28 0x19 0x28 0x5D 0x0A 0x05 0x00]})))
  (is (empty? (broadcast/signal-samples {:type :line :text "aro"}))))

