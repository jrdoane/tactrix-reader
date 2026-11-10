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

(def inferred-signals
  "Signals the project's 2015 Impreza broadcasts that were identified from how
  they behave, in the 2026-10-28-drive recording and, for the doors,
  2026-11-09-194644, but match no mode 01 PID, by CAN ID. Scales marked approximate come from fits against quantities derived
  from other signals; see docs/can-broadcast.md. Unlike `broadcast-signals`,
  these are not confirmed."
  {0x002 [{:signal :steering-angle :unit :degrees :byte-count 2
           ;; Positive is a left turn, assuming opendbc's wheel order in 0D4.
           :decode (fn [[high low]] (round-to 1 (* 0.1 (signed-word high low))))}]
   0x0D0 [;; Approximate scales. Positive is to the left and forwards.
          {:signal :lateral-acceleration :unit :g :byte-count 4
           :decode (fn [[_ _ low high]] (round-to 2 (* 0.00734 (signed-word high low))))}
          {:signal :yaw-rate :unit :degrees-per-second :byte-count 7
           :decode #(round-to 1 (* 0.68 (signed-byte (nth % 6))))}
          {:signal :longitudinal-acceleration :unit :g :byte-count 8
           :decode #(round-to 2 (* -0.00907 (signed-byte (nth % 7))))}]
   0x0D1 [{:signal :brake-pressure :byte-count 3 :decode #(nth % 2)}]
   0x0D3 [{:signal :brake :byte-count 3 :decode #(bit-test (nth % 2) 5)}]
   0x0D4 [{:signal :wheel-speeds :unit :km-per-hour :byte-count 8
           :decode (fn [data]
                     (zipmap [:front-left :front-right :rear-left :rear-right]
                             (map wheel-speed (partition 2 data))))}]
   0x141 [{:signal :gear :byte-count 7
           ;; 1-5, or 7 with no gear engaged.
           :decode #(let [gear (bit-and (nth % 6) 0x07)]
                      (cond (<= 1 gear 5) gear
                            (= 7 gear) :neutral))}]
   0x152 [{:signal :parking-brake :byte-count 7 :decode #(bit-test (nth % 6) 3)}]
   0x282 [{:signal :outside-temp :unit :celsius :byte-count 5
           ;; Byte 4 is degrees Fahrenheit plus 40.
           :decode #(round-to 1 (/ (* 5 (- (nth % 4) 72)) 9.0))}
          {:signal :driver-seatbelt :byte-count 6
           :decode #(if (bit-test (nth % 5) 0) :unbuckled :buckled)}]
   0x360 [;; Approximate scale.
          {:signal :fuel-flow :unit :grams-per-second :byte-count 2
           :decode #(round-to 2 (* 0.185 (nth % 1)))}]
   ;; True while the door is open. Front left is the driver's door.
   0x374 [{:signal :door-front-left :byte-count 4 :decode #(bit-test (nth % 3) 0)}
          {:signal :door-front-right :byte-count 4 :decode #(bit-test (nth % 3) 1)}
          {:signal :door-rear-right :byte-count 4 :decode #(bit-test (nth % 3) 2)}
          {:signal :door-rear-left :byte-count 4 :decode #(bit-test (nth % 3) 3)}]})

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
