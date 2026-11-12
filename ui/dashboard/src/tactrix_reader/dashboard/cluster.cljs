(ns tactrix-reader.dashboard.cluster
  "The pure parts of the virtual cluster: the scale of each gauge, where its
  needle points, the marks on its face, and which warning lamps are lit.
  Angles are in degrees, clockwise from straight up, as a needle turns."
  (:require [clojure.string :as str]))

(def km-per-mile 1.609344)

(def gauges
  "The gauges, by id. Each reads the PID `:name` and sweeps from `:from` at
  `:low` to `:to` at `:high`, with a labelled mark every `:major` and a
  small one every `:minor`. `:label-per` divides a mark's label, `:marks`
  gives the labels of a gauge that is not numbered, by value, `:red-from`
  starts a red zone that runs to the top of the scale and `:low-to` ends an
  amber one that starts at the bottom, and `:factor` converts the PID's
  value to the gauge's unit.

  The scales are drawn to suit the car, not copied from its cluster: the
  tachometer's red zone starts at 6500 rpm, about where a 2.0 l FB20 is
  limited, and the coolant gauge puts a warm engine, 90 C, in the middle."
  {:tach {:name :engine-rpm :low 0 :high 8000 :from -135 :to 135
          :major 1000 :minor 200 :label-per 1000 :red-from 6500
          :caption "×1000 rpm"}
   :speed {:name :vehicle-speed :low 0 :high 240 :from -135 :to 135
           :major 20 :minor 5 :unit-label "km/h"}
   :speed-mph {:name :vehicle-speed :low 0 :high 150 :from -135 :to 135
               :major 20 :minor 5 :factor (/ 1 km-per-mile) :unit-label "mph"}
   :fuel {:name :fuel-level :low 0 :high 100 :from -55 :to 55
          :major 50 :minor 25 :marks {0 "E" 100 "F"} :low-to 12.5}
   :coolant {:name :coolant-temp :low 50 :high 130 :from -55 :to 55
             :major 40 :minor 20 :marks {50 "C" 130 "H"} :red-from 115}})

(def speed-units
  "The speedometer's units, and the gauge for each."
  {:km-per-hour :speed
   :miles-per-hour :speed-mph})

(defn gauge-value
  "A PID's value in the gauge's unit, or nil when there is none."
  [{:keys [factor]} value]
  (when (number? value)
    (cond-> value factor (* factor))))

(defn angle
  "Where the needle points for a value in the gauge's unit. A value off
  either end of the scale stops at that end, and no value rests at the
  bottom."
  [{:keys [low high from to]} value]
  (let [fraction (if (number? value)
                   (-> (/ (- value low) (- high low)) (max 0) (min 1))
                   0)]
    (+ from (* fraction (- to from)))))

(defn ticks
  "The marks on a gauge's face, from the bottom of its scale to the top:
  `{:value :angle :major? :red? :low?}`, with `:label` on the major ones of
  a numbered gauge and on the `:marks` of one that is not."
  [{:keys [low high major minor label-per marks red-from low-to] :as gauge}]
  (for [i (range (inc (js/Math.round (/ (- high low) minor))))
        :let [value (+ low (* i minor))
              major? (zero? (mod (- value low) major))
              label (cond
                      marks (get marks value)
                      major? (str (/ value (or label-per 1))))]]
    (cond-> {:value value :angle (angle gauge value) :major? major?}
      label (assoc :label label)
      (and red-from (<= red-from value)) (assoc :red? true)
      (and low-to (<= value low-to)) (assoc :low? true))))

(defn- round-2 [n]
  (/ (js/Math.round (* n 100)) 100))

(defn point
  "The [x y] at `radius` from the centre [cx cy] in the direction `angle`."
  [[cx cy] radius angle]
  (let [radians (* angle (/ js/Math.PI 180))]
    [(round-2 (+ cx (* radius (js/Math.sin radians))))
     (round-2 (- cy (* radius (js/Math.cos radians))))]))

(defn arc
  "An SVG path along the circle of `radius` about `centre`, clockwise from
  the angle `from` to the angle `to`."
  [centre radius from to]
  (let [[x1 y1] (point centre radius from)
        [x2 y2] (point centre radius to)]
    (str/join " " ["M" x1 y1 "A" radius radius 0 (if (< 180 (- to from)) 1 0) 1 x2 y2])))

(defn zone
  "The angles [from to] of a gauge's red zone, or of its amber one at the
  bottom of the scale, or nil when it has neither."
  [{:keys [red-from low-to low high] :as gauge}]
  (cond
    red-from [(angle gauge red-from) (angle gauge high)]
    low-to [(angle gauge low) (angle gauge low-to)]))

(defn- flag-state [value]
  (case value
    true :on
    false :off
    :unknown))

(def ^:private doors
  [:door-front-left :door-front-right :door-rear-left :door-rear-right])

(defn- check-engine-state
  "The lamp as the car's own traffic has it, or else as PID 01 does."
  [samples signals]
  (let [lamp (get-in signals [:check-engine-lamp :value])
        mil-on? (get-in samples [:monitor-status :value :mil-on?])]
    (cond
      (#{:on :off :flashing} lamp) lamp
      (some? mil-on?) (flag-state mil-on?)
      :else :unknown)))

(defn- door-state
  "On while any door is open, and unknown until a door has been heard from."
  [signals]
  (let [open (keep #(get-in signals [% :value]) doors)
        heard (filter #(contains? signals %) doors)]
    (cond
      (some true? open) :on
      (seq heard) :off
      :else :unknown)))

(defn lamps
  "The warning lamps, in the order they are drawn, from the latest samples
  and signals by name: `{:id :label :color :state}`, where `:state` is
  `:on`, `:off`, `:flashing`, or `:unknown` when nothing has said."
  [samples signals]
  (let [flag #(flag-state (get-in signals [% :value]))]
    [{:id :check-engine :label "Check engine" :color :amber
      :state (check-engine-state samples signals)}
     {:id :parking-brake :label "Parking brake" :color :red :state (flag :parking-brake)}
     {:id :seatbelt :label "Driver's seatbelt" :color :red
      :state (case (get-in signals [:driver-seatbelt :value])
               :unbuckled :on
               :buckled :off
               :unknown)}
     {:id :door :label "Door open" :color :red :state (door-state signals)}
     {:id :parking-lights :label "Parking lights" :color :green :state (flag :parking-lights)}
     {:id :headlights :label "Headlights" :color :green :state (flag :headlights)}]))
