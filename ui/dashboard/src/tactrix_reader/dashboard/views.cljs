(ns tactrix-reader.dashboard.views
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [tactrix-reader.dashboard.subs :as subs]))

(def ^:private unit-labels
  {:rpm "rpm"
   :celsius "°C"
   :percent "%"
   :kpa "kPa"
   :km-per-hour "km/h"
   :degrees "°"
   :grams-per-second "g/s"
   :volts "V"
   :lambda "λ"
   :seconds "s"
   :minutes "min"
   :km "km"})

(defn- format-value [value]
  (cond
    (number? value) (str value)
    (keyword? value) (name value)
    :else (pr-str value)))

(defn- label [pid-name]
  (str/capitalize (str/replace (name pid-name) "-" " ")))

(defn- connection-badge []
  (let [connection @(rf/subscribe [::subs/connection])
        status @(rf/subscribe [::subs/status])]
    [:div.badges
     [:span.badge {:class (name connection)} (name connection)]
     (when status
       [:span.badge {:class (name (:state status))}
        (str (name (:source status)) " " (name (:state status)))])]))

(defn- readout [pid-name]
  (let [{:keys [value unit]} @(rf/subscribe [::subs/sample pid-name])]
    [:div.readout
     [:div.readout-label (label pid-name)]
     [:div.readout-value
      (if (some? value) (format-value value) "—")
      [:span.readout-unit (get unit-labels unit "")]]]))

(defn- sample-table []
  (let [samples @(rf/subscribe [::subs/sample-list])]
    [:table.samples
     [:thead
      [:tr [:th "PID"] [:th "Name"] [:th.value "Value"] [:th "Unit"]]]
     [:tbody
      (for [{:keys [pid name value unit]} samples]
        ^{:key pid}
        [:tr
         [:td.pid (.padStart (.toUpperCase (.toString pid 16)) 2 "0")]
         [:td (label name)]
         [:td.value (format-value value)]
         [:td (get unit-labels unit "")]])]]))

(defn app []
  [:main
   [:header
    [:h1 "Engine"]
    [connection-badge]]
   [:section.readouts
    [readout :engine-rpm]
    [readout :coolant-temp]]
   [sample-table]])
