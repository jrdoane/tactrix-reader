(ns tactrix-reader.dashboard.format
  "Labels, units, and number formats shared by the dashboard and the map."
  (:require [clojure.string :as str]))

(def unit-labels
  {:rpm "rpm"
   :celsius "°C"
   :percent "%"
   :kpa "bar"
   :km-per-hour "km/h"
   :degrees "°"
   :grams-per-second "g/s"
   :volts "V"
   :lambda "λ"
   :seconds "s"
   :minutes "min"
   :km "km"
   :g "g"
   :degrees-per-second "°/s"
   :milliamps "mA"
   :grams-per-revolution "g/rev"})

(def unit-decimals
  "Decimal places for each unit, so a value keeps its width as it updates."
  {:rpm 0
   ;; Broadcast speed is finer than polled speed's whole km/h.
   :km-per-hour 0
   :percent 1
   :celsius 0
   :degrees 1
   :degrees-per-second 1
   :volts 2
   :grams-per-second 2
   :lambda 3
   :g 2
   :milliamps 3
   :grams-per-revolution 3})

(def labels
  "Shorter labels than the PID names give."
  {:engine-rpm "RPM"
   :vehicle-speed "Speed"
   :coolant-temp "Coolant"
   :throttle-position "Throttle"
   :engine-load "Engine load"
   :maf-air-flow "Mass air flow"
   :intake-manifold-pressure "Manifold pressure"
   :timing-advance "Timing advance"
   :relative-accelerator-pedal-position "Accelerator pedal"
   :oil-temp "Oil"
   :intake-air-temp "Intake air"
   :ambient-air-temp "Ambient air"
   :short-term-fuel-trim-bank-1 "Short-term trim"
   :long-term-fuel-trim-bank-1 "Long-term trim"
   :commanded-lambda "Commanded λ"
   :wideband-oxygen-sensor-1-current "Measured λ"
   :fuel-system-status "Fuel system"
   :oxygen-sensor-bank-1-sensor-2 "Rear O₂ sensor"
   :catalyst-temp-bank-1-sensor-1 "Catalyst"
   :barometric-pressure "Barometric pressure"
   :fuel-level "Fuel level"
   :control-module-voltage "ECU voltage"
   :relative-throttle-position "Relative throttle"
   :commanded-throttle-actuator "Commanded throttle"
   :accelerator-pedal-position-d "Pedal position D"
   :auxiliary-io-status "Neutral switch"
   ;; Inferred CAN signals.
   :gear "Gear"
   :steering-angle "Steering angle"
   :brake "Brake"
   :brake-pressure "Brake pressure"
   :parking-brake "Parking brake"
   :driver-seatbelt "Driver's seatbelt"
   :door-front-left "Driver's door"
   :door-front-right "Passenger's door"
   :door-rear-left "Rear left"
   :door-rear-right "Rear right"
   :wheel-front-left "Front left"
   :wheel-front-right "Front right"
   :wheel-rear-left "Rear left"
   :wheel-rear-right "Rear right"
   :longitudinal-acceleration "Acceleration"
   :lateral-acceleration "Lateral acceleration"
   :yaw-rate "Yaw rate"
   :fuel-flow "Fuel flow"
   :outside-temp "Outside"
   ;; UDS data identifiers.
   :accelerator-pedal "Accelerator pedal"
   :wideband-current "Wideband current"
   :lambda "Measured λ"
   :air-per-revolution "Air per revolution"
   :charging-voltage-target "Charging target"})

(defn did-hex [did]
  (.padStart (.toUpperCase (.toString did 16)) 4 "0"))

(def ^:private unit-conversions
  "Units shown converted, as [factor decimal-places]. The data keeps the
  PIDs' units; kPa is shown as bar."
  {:kpa [0.01 2]})

(defn format-value [value unit]
  (cond
    (and (number? value) (contains? unit-conversions unit))
    (let [[factor places] (get unit-conversions unit)]
      (.toFixed (* value factor) places))

    (and (number? value) (contains? unit-decimals unit)) (.toFixed value (get unit-decimals unit))
    (integer? value) (str value)
    (number? value) (.toFixed value 1)
    (boolean? value) (if value "yes" "no")
    (keyword? value) (name value)
    (map? value) (str/join ", " (map (fn [[k v]] (str (name k) " " (format-value v nil))) value))
    :else (pr-str value)))

(defn label [pid-name]
  (get labels pid-name (str/capitalize (str/replace (name pid-name) "-" " "))))
