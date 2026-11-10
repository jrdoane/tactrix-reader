(ns tactrix-reader.dashboard.bus-test
  (:require [cljs.test :refer [deftest is]]
            [tactrix-reader.dashboard.bus :as bus]))

(defn- frame [id data changed & {:as more}]
  (merge {:id id :data data :changed changed :count 1 :first-us 0 :timestamp-us 0} more))

(deftest counts-the-batches-each-bit-changed-in
  (let [frames (-> {}
                   (bus/merge-frames [(frame 0x374 [0x00 0x80] [0x00 0x00])])
                   (bus/merge-frames [(frame 0x374 [0x01 0x80] [0x01 0x00])])
                   (bus/merge-frames [(frame 0x374 [0x00 0x00] [0x01 0x80])]))
        door (get frames 0x374)]
    (is (= [2 0 0 0 0 0 0 0, 0 0 0 0 0 0 0 1] (:flips door)))
    (is (= [0x00 0x00] (:data door)))
    (is (not (contains? door :changed)))
    ;; Before a baseline, a changed bit is active, never new.
    (is (= :active (bus/bit-state door 0 0)))
    (is (= :quiet (bus/bit-state door 0 1)))
    (is (empty? (bus/new-bits frames)))))

(deftest a-baseline-makes-earlier-changes-noise-and-later-ones-new
  (let [frames (-> {}
                   (bus/merge-frames [(frame 0x140 [0x10 0x00] [0x00 0x00])])
                   ;; A counter in byte 0's low bits, before the baseline.
                   (bus/merge-frames [(frame 0x140 [0x13 0x00] [0x03 0x00])])
                   bus/mark-baseline
                   ;; The counter again, and a bit that had not moved.
                   (bus/merge-frames [(frame 0x140 [0x10 0x40] [0x03 0x40])]))
        throttle (get frames 0x140)]
    (is (= [0x03 0x00] (:noise throttle)))
    (is (= [0x13 0x00] (:baseline throttle)))
    (is (= :active (bus/bit-state throttle 0 0)))
    (is (= :new (bus/bit-state throttle 1 6)))
    (is (= :active (bus/byte-state throttle 0)))
    (is (= :new (bus/byte-state throttle 1)))
    (is (= [{:id 0x140 :byte 1 :bit 6 :value 1 :flips 1}] (bus/new-bits frames)))
    ;; A second baseline adds the new bit to the noise.
    (is (= [0x03 0x40] (:noise (get (bus/mark-baseline frames) 0x140))))
    (is (not (contains? (get (bus/clear-baseline frames) 0x140) :noise)))
    (is (= :active (bus/bit-state (get (bus/merge-frames (bus/clear-baseline frames)
                                                         [(frame 0x140 [0x10 0x00] [0x00 0x40])])
                                       0x140)
                                  1 6)))))

(deftest a-frame-of-another-length-starts-over
  (let [frames (-> {}
                   (bus/merge-frames [(frame 0x100 [0x01] [0x00])])
                   (bus/merge-frames [(frame 0x100 [0x00] [0x01])])
                   bus/mark-baseline
                   (bus/merge-frames [(frame 0x100 [0x00 0x00] [0x00 0x00])]))]
    (is (= (vec (repeat 16 0)) (:flips (get frames 0x100))))
    (is (not (contains? (get frames 0x100) :baseline)))))

(deftest names-bits-and-works-out-rates
  (is (= "b3.7" (bus/bit-name 3 7)))
  (is (= 10 (bus/period-ms {:first-us 0 :timestamp-us 1000000 :count 101})))
  (is (nil? (bus/period-ms {:first-us 0 :timestamp-us 0 :count 1})))
  (is (= 150 (bus/frames-per-second {1 {:first-us 0 :timestamp-us 1000000 :count 101}
                                    2 {:first-us 0 :timestamp-us 1000000 :count 51}
                                    3 {:first-us 5 :timestamp-us 5 :count 1}}))))

(deftest finds-the-field-each-bit-is-decoded-into
  (let [pedal {:id :pedal :bits [0xFF]}
        clutch {:id :clutch :bits [0x00 0x80]}
        rpm {:id :rpm :bits [0x00 0x00 0xFF 0x3F]}
        fields [pedal clutch rpm]]
    (is (= pedal (bus/field-at fields 0 3)))
    (is (= clutch (bus/field-at fields 1 7)))
    (is (nil? (bus/field-at fields 1 6)))
    (is (= rpm (bus/field-at fields 3 5)))
    (is (nil? (bus/field-at fields 3 6)))
    ;; A byte the masks do not reach.
    (is (nil? (bus/field-at fields 7 0)))
    ;; Runs of a byte's bits, most significant first.
    (is (= [[pedal 8]] (bus/byte-fields fields 0)))
    (is (= [[clutch 1] [nil 7]] (bus/byte-fields fields 1)))
    (is (= [[nil 2] [rpm 6]] (bus/byte-fields fields 3)))
    (is (nil? (bus/byte-fields fields 4)))
    (is (nil? (bus/byte-fields nil 0)))))

(deftest writes-which-bits-a-mask-covers
  (is (= "b0" (bus/bits-text [0xFF])))
  (is (= "b0–b1" (bus/bits-text [0xFF 0xFF])))
  (is (= "b2, b3.5–0" (bus/bits-text [0x00 0x00 0xFF 0x3F])))
  (is (= "b0, b1.6–0" (bus/bits-text [0xFF 0x7F])))
  (is (= "b1.7" (bus/bits-text [0x00 0x80])))
  (is (= "b6.2–0" (bus/bits-text [0 0 0 0 0 0 0x07])))
  (is (= "b0–b2, b4" (bus/bits-text [0xFF 0xFF 0xFF 0x00 0xFF])))
  (is (= "b0.7–6, b0.1" (bus/bits-text [0xC2])))
  (is (= "" (bus/bits-text []))))

