(ns tactrix-reader.device.derived
  "Values worked out from the ones the sources decode: engine power, fuel
  economy, road grade, clutch slip, and injector duty. None is read from the
  car. They are emitted as `:signal` events with no `:can-id`, so a consumer
  handles them like any inferred signal.

  The constants are the project's 2015 Impreza's, measured in its
  recordings; see docs/can-broadcast.md. The values need that car's
  broadcast and inferred signals, and on another vehicle, with those turned
  off, none is emitted."
  (:require [tactrix-reader.device.pids :refer [round-to]]))

(def derived-signals
  "The derived values, with how `catalog/catalog` should show each and, in
  `:from`, the names of the values each is worked out from."
  [{:signal :engine-power :unit :kilowatts :label "Power" :group :engine
    :from [:engine-torque :engine-rpm]}
   {:signal :fuel-economy :unit :litres-per-100-km :label "Fuel economy" :group :engine
    :from [:fuel-flow :vehicle-speed]}
   {:signal :injector-duty :unit :percent :label "Injector duty" :group :engine
    :from [:injector-pulse-width :engine-rpm]}
   {:signal :road-grade :unit :percent :label "Road grade" :group :road
    :from [:longitudinal-acceleration :vehicle-speed :yaw-rate]}
   {:signal :clutch-slip :unit :rpm :label "Clutch slip" :group :road
    :from [:engine-rpm :vehicle-speed :gear]}])

(def ^:private units
  (into {} (map (juxt :signal :unit)) derived-signals))

(def gear-rpm-per-km-per-hour
  "Engine rpm for each km/h of road speed with the clutch fully engaged, by
  gear: the median over five drives. Each step matches the gearbox's ratios
  within 0.3%."
  {1 123.2, 2 67.2, 3 46.07, 4 34.55, 5 26.22})

(def ^:private petrol-grams-per-litre 745.0)

(def ^:private standard-gravity 9.80665)

(def ^:private max-age-us
  "A value older than this is not used with a newer one."
  1000000)

(def ^:private restart-gap-us
  "A gap this long between events, or time running backwards, means the
  stream jumped, as a replay does when it loops or seeks, so what was
  remembered no longer applies."
  2000000)

(def ^:private grade-window-us
  "The road grade is worked out over this long. The acceleration sensor's
  steps are 1% of grade each, so one reading is too coarse."
  1000000)

(def ^:private min-interval-us
  "The shortest time from one value of a derived signal to its next. The
  torque and the acceleration arrive 100 and 50 times a second, more often
  than a display or a 100 ms series can use."
  50000)

(def ^:private min-economy-speed
  "Fuel for a distance means little near a standstill, in km/h."
  5.0)

(def ^:private min-grade-speed 10.0)

(def ^:private max-grade-yaw-rate
  "The grade has no value while the car turns faster than this, in degrees
  a second. The sensor sits ahead of the rear axle, so in a turn it feels
  some of the pull towards the centre as slowing down: about 2 m/s2 for
  each (rad/s)2 of yaw rate, which is 4% of grade at 25 degrees a second
  and 0.4% at this."
  8.0)

(def initial-state
  "What `step` starts from: the latest of each value it uses, when each
  trigger is next due, and the last second of speed and acceleration for
  the grade."
  {:latest {}
   :due {}
   :speeds clojure.lang.PersistentQueue/EMPTY
   :accelerations clojure.lang.PersistentQueue/EMPTY})

(defn- fresh
  "The latest value kept under `key`, unless it is older than `max-age-us`
  at `t`."
  [state key t]
  (let [[value at] (get-in state [:latest key])]
    (when (and at (<= (- t at) max-age-us))
      value)))

(defn- trim
  "Drops readings older than the grade window from a queue of `[t value]`."
  [queue t]
  (if (some-> (peek queue) first (< (- t grade-window-us)))
    (recur (pop queue) t)
    queue))

(defn- power
  "Kilowatts at the crankshaft, from torque in newton metres. Negative on
  the overrun, as the torque is."
  [torque rpm]
  (round-to 1 (/ (* torque rpm Math/PI) 30000.0)))

(defn- economy
  "Litres per 100 km from grams a second, or nil below walking pace."
  [flow speed]
  (when (>= speed min-economy-speed)
    (round-to 1 (/ (* flow 3600.0 100.0) petrol-grams-per-litre speed))))

(defn- slip
  "How much faster the engine turns than the gearbox's input, in rpm, or nil
  with no gear engaged or in reverse, whose ratio was not measured. Near 0
  with the clutch engaged."
  [rpm speed gear reverse?]
  (when-let [per-km-per-hour (and (not reverse?) (gear-rpm-per-km-per-hour gear))]
    (Math/round (double (- rpm (* speed per-km-per-hour))))))

(defn- injector-duty
  "The share of each engine cycle the injector is open, in percent. Each
  injector opens once every two revolutions."
  [pulse-width-ms rpm]
  (round-to 1 (/ (* pulse-width-ms rpm) 1200.0)))

(defn- grade
  "The road's slope in percent, positive uphill, over the grade window: the
  acceleration the sensor feels less the acceleration the wheels show, over
  g. The sensor feels gravity along the car on a slope, and the wheels do
  not. Nil without most of a window of both, below `min-grade-speed`, in
  reverse, or when the car turned hard in the window."
  [{:keys [speeds accelerations] :as state} t]
  (let [[t0 v0] (peek speeds)
        [t1 v1] (last speeds)]
    (when (and t0
               (>= (- t1 t0) (* 0.8 grade-window-us))
               (>= (- t (first (peek accelerations))) (* 0.8 grade-window-us))
               (<= (- t t1) (* 0.1 grade-window-us))
               (>= (min v0 v1) min-grade-speed)
               (not (fresh state :reverse t))
               (not (some-> (:turned-at state) (>= (- t grade-window-us)))))
      (let [felt (/ (transduce (map second) + 0.0 accelerations) (count accelerations))
            shown (/ (/ (- v1 v0) 3.6) (/ (- t1 t0) 1e6) standard-gravity)]
        (round-to 1 (* 100.0 (- felt shown)))))))

(defn- remember [state key value t]
  (assoc-in state [:latest key] [value t]))

(defn- due?
  "Whether `min-interval-us` has passed since `trigger` last gave values."
  [state trigger t]
  (>= t (get-in state [:due trigger] 0)))

(defn- gave [state trigger t]
  (assoc-in state [:due trigger] (+ t min-interval-us)))

(defn- event-key [{:keys [type name signal did]}]
  (case type
    :sample name
    :signal signal
    :did did
    nil))

(def ^:private used
  "The names of the samples and signals, and the DID, that `derive-event`
  reads."
  #{:engine-rpm :vehicle-speed :gear :reverse :yaw-rate :engine-torque :fuel-flow
    :longitudinal-acceleration 0x10A3})

(defn- derive-event
  "The state after `event`, and the `[signal value]` pairs it gives rise
  to. A nil value says the signal has no value now, as when stopped."
  [state key {:keys [value can-id] t :timestamp-us}]
  (case key
    :engine-rpm [(remember state :rpm value t) nil]
    ;; Only the broadcast speed goes into the grade: the polled one is whole
    ;; km/h, and mixing the two would look like acceleration.
    :vehicle-speed [(cond-> (remember state :speed value t)
                      can-id (update :speeds #(conj (trim % t) [t value])))
                    nil]
    :gear [(remember state :gear value t) nil]
    :reverse [(remember state :reverse value t) nil]
    :yaw-rate [(cond-> state
                 (>= (Math/abs (double value)) max-grade-yaw-rate) (assoc :turned-at t))
               nil]
    ;; `141` carries the gear and the torque, just after `140`'s RPM.
    :engine-torque (if-not (due? state key t)
                     [state nil]
                     (let [rpm (fresh state :rpm t)
                           speed (fresh state :speed t)]
                       [(gave state key t)
                        (cond-> []
                          rpm (conj [:engine-power (power value rpm)])
                          (and rpm speed) (conj [:clutch-slip
                                                 (slip rpm speed (fresh state :gear t)
                                                       (fresh state :reverse t))]))]))
    :fuel-flow [state (when-let [speed (fresh state :speed t)]
                        [[:fuel-economy (economy value speed)]])]
    :longitudinal-acceleration (let [state (update state :accelerations #(conj (trim % t) [t value]))]
                                 (if (due? state key t)
                                   [(gave state key t) [[:road-grade (grade state t)]]]
                                   [state nil]))
    ;; DID 10A3, the injector pulse width in ms.
    0x10A3 [state (when-let [rpm (and (number? value) (fresh state :rpm t))]
                    [[:injector-duty (injector-duty value rpm)]])]))

(defn step
  "Takes the state so far and one source event, and returns `[state events]`:
  the state to pass with the next event, and the derived `:signal` events
  this one gives rise to, stamped with its time. Events with no timestamp,
  and ones it does not use, give none.

  Each value is worked out when the last of what it needs arrives, from the
  latest of the rest if that is under a second old, and at most 20 times a
  second. Start from `initial-state`. A gap of two seconds between the
  events it uses, or time running backwards, starts it again."
  [state {t :timestamp-us :as event}]
  (let [key (event-key event)]
    (if-not (and t (used key))
      [state nil]
      (let [last-t (:last-t state)
            state (assoc (if (and last-t (not (<= last-t t (+ last-t restart-gap-us))))
                           initial-state
                           state)
                         :last-t t)
            [state values] (derive-event state key event)]
        [state (when (seq values)
                 (mapv (fn [[signal value]]
                         {:type :signal :signal signal :value value :unit (units signal) :timestamp-us t})
                       values))]))))

(defn deriver
  "Returns a function that takes a sequence of source events, in order, and
  returns them with the derived events each gives rise to after it. It
  remembers what it has seen, so call it from one thread, with every event
  of one source."
  []
  (let [state (volatile! initial-state)]
    (fn [events]
      (persistent!
       (reduce (fn [out event]
                 (let [out (conj! out event)]
                   ;; Most events are ones `step` does not use.
                   (if (used (event-key event))
                     (let [[next-state derived] (step @state event)]
                       (vreset! state next-state)
                       (reduce conj! out derived))
                     out)))
               (transient [])
               events)))))
