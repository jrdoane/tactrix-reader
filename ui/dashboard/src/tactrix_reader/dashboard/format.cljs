(ns tactrix-reader.dashboard.format
  "Labels, units, and number formats shared by the dashboard and the map."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]))

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
   :grams-per-revolution "g/rev"
   :newton-metres "Nm"
   :milliseconds "ms"
   :kilowatts "kW"
   :litres-per-100-km "l/100 km"})

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
   :grams-per-revolution 3
   :newton-metres 1
   :milliseconds 2
   :kilowatts 1
   :litres-per-100-km 1})

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

(defn- on-off [value]
  (if value "on" "off"))

(defn- open-closed [value]
  (if value "open" "closed"))

(def ^:private formatters
  "Display for values that read better as words than as numbers, by the
  catalog's `:format`."
  {:gear #(if (= :neutral %) "N" (str %))
   ;; Positive is a left turn.
   :steering #(cond
                (pos? %) (str (.toFixed % 1) "° L")
                (neg? %) (str (.toFixed (- %) 1) "° R")
                :else "0.0°")
   :on-off on-off
   :open-closed open-closed
   :pressed-released #(if % "pressed" "released")
   :keyword name
   :monitor-status (fn [{:keys [mil-on? dtc-count monitors]}]
                     (str "check engine " (if mil-on? "on" "off")
                          ", " dtc-count " stored codes, "
                          (count (filter #{:complete} (vals monitors))) " of "
                          (count monitors) " monitors complete"))
   :in-gear #(case (:manual-transmission-in-gear? %)
               true "in gear"
               false "neutral"
               nil "—")
   :fuel-system #(let [state (:system-1 %)]
                   (if (keyword? state)
                     (str/replace (name state) "-" " ")
                     (str state)))})

(def ^:private formats-with-unit
  "Formats that already show the unit."
  #{:steering})

(defn tile-text
  "A tile's value as text."
  [{:keys [format unit decimals]} value sample-unit]
  (cond
    (nil? value) "—"
    format ((get formatters format str) value)
    (and decimals (number? value)) (.toFixed value decimals)
    :else (format-value value (or unit sample-unit))))

(defn tile-unit
  "A tile's unit label: its unit's, or `raw` for a count with no known
  scale."
  [{:keys [format unit raw?]} sample-unit]
  (cond
    (contains? formats-with-unit format) ""
    raw? "raw"
    :else (get unit-labels (or unit sample-unit) "")))

(defn label
  "The catalog's label for a PID, signal, or DID name, or one made from the
  name. It subscribes, so call it while rendering."
  [value-name]
  (get @(rf/subscribe [:catalog/labels]) value-name
       (str/capitalize (str/replace (name value-name) "-" " "))))
