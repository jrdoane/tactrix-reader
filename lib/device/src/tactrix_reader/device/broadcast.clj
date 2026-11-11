(ns tactrix-reader.device.broadcast
  "Decoding of the project's 2015 Impreza's own CAN traffic: signals confirmed
  against the mode 01 PID they match (`broadcast-signals`), and signals
  identified only from how they behave (`inferred-signals`). Record each in
  docs/can-broadcast.md."
  (:require [tactrix-reader.device.pids :as pids :refer [percent round-to temperature]]))

(def broadcast-signals
  "Signals the project's 2015 Impreza broadcasts on its CAN bus, by CAN ID.
  Each was confirmed against the mode 01 PID it reports as; see
  docs/can-broadcast.md. `:byte-count` is the data length a frame needs."
  {0x0D1 [{:pid 0x0D :byte-count 2
           :decode (fn [[low high]] (round-to 1 (* 0.05625 (+ low (* 256 high)))))}]
   0x140 [{:pid 0x0C :byte-count 4
           :decode (fn [[_ _ low high]] (double (+ low (* 256 (bit-and high 0x3F)))))}
          ;; Byte 6 is the throttle opening, 0-255. PID 11's raw byte is
          ;; 29 when it is closed and 205 when it is wide open.
          {:pid 0x11 :byte-count 7
           :decode #(percent [(Math/round (+ 29.0 (/ (* 176.0 (nth % 6)) 255)))])}]
   0x360 [{:pid 0x5C :byte-count 4 :decode #(temperature [(nth % 2)])}
          {:pid 0x05 :byte-count 4 :decode #(temperature [(nth % 3)])}
          {:pid 0x0B :byte-count 5 :decode #(* 2 (nth % 4))}]})

(defn broadcast-samples
  "Decodes a received message carrying signals in `signals` (by default
  `broadcast-signals`) into samples shaped like `reply-sample`'s, with the
  CAN ID under :can-id. Returns an empty sequence for any other message."
  ([message]
   (broadcast-samples broadcast-signals message))
  ([signals {:keys [type id data timestamp-us]}]
   (when (= :message type)
     (for [{:keys [pid byte-count decode]} (get signals id)
           :when (>= (count data) byte-count)
           :let [{:keys [name unit]} (get pids/mode-01-pids pid)]]
       {:pid pid
        :name name
        :value (decode data)
        :unit unit
        :can-id id
        :timestamp-us timestamp-us}))))

(defn- signed-byte [b]
  (if (> b 127) (- b 256) b))

(defn- signed-word [high low]
  (let [n (+ (* 256 high) low)]
    (if (> n 32767) (- n 65536) n)))

(defn- wheel-speed [[low high]]
  (round-to 1 (* 0.05625 (+ low (* 256 high)))))

(defn- torque
  "A torque from `141`: tenths of a newton metre, offset by 10000."
  [raw]
  (round-to 1 (/ (- raw 10000) 10.0)))

(def inferred-signals
  "Signals the project's 2015 Impreza broadcasts that were identified from how
  they behave, in the 2026-10-28-drive recording and, for the doors,
  2026-11-09-194644, but match no mode 01 PID, by CAN ID. The counters, the
  fuel gauge, the battery current, and the clutch, reverse, torque, and fuel
  cut signals were identified by comparing all seven reference recordings.
  The available torque, check engine lamp, barometer, and engine stopped
  signals are read as a 2015 WRX's ECU sends them and checked against the
  recordings. Scales marked
  approximate come from fits against quantities derived from other signals;
  see docs/can-broadcast.md. Unlike `broadcast-signals`, these are not
  confirmed against the PID that reports the same thing.

  Decoding uses `:signal`, `:unit`, `:byte-count`, and `:decode`. The rest is
  for `catalog/catalog`, which tells a display how to show each signal:
  `:label`, the `:group` it belongs to (one of `catalog/groups`), a `:format`
  for values that read better as words, `:raw? true` for a count with no
  known scale, `:parts` for a value that is a map, and `:override
  {:signal :text}` for a text to show instead of the value while another
  signal is true."
  {0x002 [{:signal :steering-angle :unit :degrees :byte-count 2
           :label "Steering angle" :group :driver :format :steering
           ;; Positive is a left turn, assuming opendbc's wheel order in 0D4.
           :decode (fn [[high low]] (round-to 1 (* 0.1 (signed-word high low))))}]
   0x0D0 [;; Approximate scales. Positive is to the left and forwards. The
          ;; yaw rate is 0.005 rad/s per bit, the lateral acceleration
          ;; 0.2 m/s2 per bit, and the longitudinal 0.1 m/s2 per bit, the
          ;; scales the RaceChrono BRZ notes give. The fits against wheel
          ;; speeds agree with the two accelerations, and put the yaw rate
          ;; about 7% lower.
          {:signal :yaw-rate :unit :degrees-per-second :byte-count 4
           :label "Yaw rate" :group :motion
           :decode (fn [[_ _ low high]] (round-to 1 (* 0.2865 (signed-word high low))))}
          {:signal :lateral-acceleration :unit :g :byte-count 7
           :label "Lateral acceleration" :group :motion
           :decode #(round-to 2 (* 0.0204 (signed-byte (nth % 6))))}
          {:signal :longitudinal-acceleration :unit :g :byte-count 8
           :label "Acceleration" :group :motion
           :decode #(round-to 2 (* -0.0102 (signed-byte (nth % 7))))}]
   0x0D1 [{:signal :brake-pressure :byte-count 3 :decode #(nth % 2)
           :label "Brake pressure" :group :driver :raw? true}]
   0x0D3 [{:signal :brake :byte-count 3 :decode #(bit-test (nth % 2) 5)
           :label "Brake" :group :driver :format :on-off}]
   0x0D4 [{:signal :wheel-speeds :unit :km-per-hour :byte-count 8
           :label "Wheel speeds" :group :wheels
           :parts [{:part :front-left :label "Front left"}
                   {:part :front-right :label "Front right"}
                   {:part :rear-left :label "Rear left"}
                   {:part :rear-right :label "Rear right"}]
           :decode (fn [data]
                     (zipmap [:front-left :front-right :rear-left :rear-right]
                             (map wheel-speed (partition 2 data))))}]
   0x140 [;; Byte 0 is the pedal, 0-255, the same byte as DID 10A6. Full
          ;; travel is about 45% on PID 5A.
          {:signal :pedal-travel :unit :percent :byte-count 1
           :label "Pedal travel" :group :driver
           :decode #(round-to 1 (/ (nth % 0) 2.55))}
          {:signal :clutch :byte-count 2 :decode #(bit-test (nth % 1) 7)
           :label "Clutch" :group :driver :format :pressed-released}]
   0x141 [{:signal :gear :byte-count 7
           :label "Gear" :group :driver :format :gear
           ;; The gear comes from the ratio, so reverse reads 1; the
           ;; reverse switch is in another message.
           :override {:signal :reverse :text "R"}
           ;; 1-5, or 7 with no gear engaged.
           :decode #(let [gear (bit-and (nth % 6) 0x07)]
                      (cond (<= 1 gear 5) gear
                            (= 7 gear) :neutral))}
          ;; What the pedal asks for, and what the engine delivers. Both
          ;; read the engine's drag, -17 to -63 Nm, with the pedal released.
          {:signal :requested-torque :unit :newton-metres :byte-count 2
           :label "Requested torque" :group :engine
           :decode (fn [[low high]] (torque (+ low (* 256 (bit-and high 0x7F)))))}
          {:signal :engine-torque :unit :newton-metres :byte-count 4
           :label "Engine torque" :group :engine
           :decode (fn [[_ _ low high]] (torque (+ low (* 256 high))))}
          {:signal :fuel-cut :byte-count 8 :decode #(bit-test (nth % 7) 3)
           :label "Fuel cut" :group :engine :format :on-off}]
   ;; Counts that carry over between drives: byte 3 steps when the engine
   ;; starts, and byte 4 once coolant reaches 71 C after a cold start.
   0x144 [;; The most torque the engine can make at its present speed, less
          ;; friction: what `141`'s engine torque reaches with the pedal
          ;; on the floor. The scale is the one a 2015 WRX's ECU uses.
          {:signal :available-torque :unit :newton-metres :byte-count 3
           :label "Available torque" :group :engine
           :decode #(round-to 1 (* 1.6 (nth % 2)))}
          {:signal :engine-starts :byte-count 4 :decode #(nth % 3)
           :label "Engine starts" :group :counters}
          {:signal :warm-up-cycles :byte-count 5 :decode #(nth % 4)
           :label "Warm-up cycles" :group :counters}]
   0x152 [{:signal :parking-brake :byte-count 7 :decode #(bit-test (nth % 6) 3)
           :label "Parking brake" :group :driver :format :on-off}
          {:signal :reverse :byte-count 7 :decode #(bit-test (nth % 6) 2)
           :label "Reverse" :group :driver :format :on-off}]
   ;; A raw count that follows fuel level (PID 2F), at about 2.4% of the tank
   ;; per count: 35 at 97% and 15 at 51%.
   0x156 [{:signal :fuel-gauge :byte-count 8 :decode #(nth % 7)
           :label "Fuel gauge" :group :counters :raw? true}]
   0x282 [;; The tank sender before the gauge's damping, so it moves as the
          ;; fuel does. It rises as the tank empties, by about 8 counts for
          ;; each 1% of the tank: 97 at 98% and 483 at 51%.
          {:signal :fuel-sender :byte-count 2
           :label "Fuel sender" :group :counters :raw? true
           :decode (fn [[low high]] (+ low (* 256 (bit-and high 0x0F))))}
          {:signal :driver-seatbelt :byte-count 6
           :label "Driver's seatbelt" :group :driver :format :keyword
           :decode #(if (bit-test (nth % 5) 0) :unbuckled :buckled)}]
   0x360 [;; Approximate scale: a count is about a cubic millimetre a second.
          {:signal :fuel-flow :unit :grams-per-second :byte-count 2
           :label "Fuel flow" :group :engine
           :decode (fn [[low high]] (round-to 3 (* 0.00072 (+ low (* 256 high)))))}]
   ;; Byte 5's high nibble and byte 6 are the battery current sensor, 12
   ;; bits around 2048: above it the battery is charging, below it
   ;; discharging, and it pins at 250 while the starter cranks. The scale is
   ;; not known, so the value is the count less 2048.
   ;;
   ;; The other three are read as a 2015 WRX's ECU sends them, which the
   ;; recordings bear out. The lamp's code is 0 while it is lit, 1 while it
   ;; is off, and, by the WRX alone, 4 while it flashes. The barometer's
   ;; scale rounds to PID 33.
   0x361 [{:signal :check-engine-lamp :byte-count 2
           :label "Check engine lamp" :group :engine :format :keyword
           :decode #(case (bit-shift-right (nth % 1) 5)
                      0 :on
                      1 :off
                      4 :flashing
                      nil)}
          {:signal :barometer :unit :kpa :byte-count 4
           :label "Barometer" :group :engine
           :decode #(round-to 1 (+ 13.2 (* 0.4 (nth % 3))))}
          {:signal :engine-stopped :byte-count 5 :decode #(bit-test (nth % 4) 5)
           :label "Engine stopped" :group :engine}
          {:signal :battery-current :byte-count 7
           :label "Battery current" :group :engine :raw? true
           :decode (fn [[_ _ _ _ _ low high]]
                     (- (+ (bit-shift-right low 4) (* 16 high)) 2048))}]
   ;; True while the door is open. Front left is the driver's door.
   0x374 [{:signal :door-front-left :byte-count 4 :decode #(bit-test (nth % 3) 0)
           :label "Driver's door" :group :doors :format :open-closed}
          {:signal :door-front-right :byte-count 4 :decode #(bit-test (nth % 3) 1)
           :label "Passenger's door" :group :doors :format :open-closed}
          {:signal :door-rear-right :byte-count 4 :decode #(bit-test (nth % 3) 2)
           :label "Rear right" :group :doors :format :open-closed}
          {:signal :door-rear-left :byte-count 4 :decode #(bit-test (nth % 3) 3)
           :label "Rear left" :group :doors :format :open-closed}]
   ;; Bytes 0-1 are 3600 plus the temperature in fifths of a degree
   ;; Fahrenheit above -40, five times as fine as `282` byte 4. Bit 15 is
   ;; opendbc's units flag, which this car never set.
   0x3D1 [{:signal :outside-temp :unit :celsius :byte-count 2
           :label "Outside" :group :engine
           :decode (fn [[low high]]
                     (round-to 1 (- (/ (- (+ low (* 256 (bit-and high 0x7F))) 3600) 9.0) 40)))}]})

(def ^:private probe-frames
  "The frames `decode-bits` flips the bits of."
  [(vec (repeat 8 0x00)) (vec (repeat 8 0xFF))])

(defn decode-bits
  "The bits of a frame that `decode` reads, as a mask per byte with the
  trailing zero bytes dropped: `[0 0 0xFF 0x3F]` is byte 2 and the low six
  bits of byte 3. They are found by flipping each bit of an all-zero and an
  all-one eight-byte frame and seeing whether the value changes, so they
  cannot disagree with the decoding. `part` picks one key of a value that is
  a map."
  ([decode]
   (decode-bits decode nil))
  ([decode part]
   (let [value (if part #(get (decode %) part) decode)
         reads? (fn [byte-index bit]
                  (some #(not= (value %) (value (update % byte-index bit-flip bit)))
                        probe-frames))
         mask (fn [byte-index]
                (reduce (fn [mask bit]
                          (cond-> mask
                            (reads? byte-index bit) (bit-or (bit-shift-left 1 bit))))
                        0
                        (range 8)))]
     (->> (map mask (range 8))
          reverse
          (drop-while zero?)
          reverse
          vec))))

(defn signal-samples
  "Decodes a received message carrying signals in `signals` (by default
  `inferred-signals`) into `{:signal :value :unit :can-id :timestamp-us}`
  maps. Returns an empty sequence for any other message."
  ([message]
   (signal-samples inferred-signals message))
  ([signals {:keys [type id data timestamp-us]}]
   (when (= :message type)
     (for [{:keys [signal unit byte-count decode]} (get signals id)
           :when (>= (count data) byte-count)]
       {:signal signal
        :value (decode data)
        :unit unit
        :can-id id
        :timestamp-us timestamp-us}))))
