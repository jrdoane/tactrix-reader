(ns tactrix-reader.dashboard.views
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [tactrix-reader.dashboard.events :as events]
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

(def ^:private unit-decimals
  "Decimal places for values that are not whole numbers."
  {:rpm 0
   :kpa 0
   :volts 2
   :grams-per-second 2
   :lambda 3})

(def ^:private labels
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
   :fuel-level "Fuel level"
   :control-module-voltage "ECU voltage"})

(def ^:private primary-readouts
  [:engine-rpm :vehicle-speed :coolant-temp :throttle-position])

(def ^:private readout-groups
  [["Air and load" [:engine-load :maf-air-flow :intake-manifold-pressure
                    :timing-advance :relative-accelerator-pedal-position]]
   ["Temperatures" [:oil-temp :intake-air-temp :ambient-air-temp]]
   ["Fuel" [:short-term-fuel-trim-bank-1 :long-term-fuel-trim-bank-1
            :commanded-lambda :fuel-level]]
   ["Electrical" [:control-module-voltage]]])

(def ^:private error-messages
  {:missing-path "No adapter was found."
   :not-found-or-permission-denied "The adapter could not be opened. Is another program using it?"
   :no-vehicle-power "The adapter reads no battery voltage. Is it plugged into the car?"
   :connect-failed "The adapter refused the CAN connection."
   :unknown-adapter "That adapter is no longer connected. Rescan and try again."
   :request-failed "The server did not answer."
   :timeout "The adapter did not answer."})

(defn- error-message [error]
  (get error-messages error (some-> error name (str/replace "-" " "))))

(defn- format-value [value unit]
  (cond
    (integer? value) (str value)
    (number? value) (.toFixed value (get unit-decimals unit 1))
    (boolean? value) (if value "yes" "no")
    (keyword? value) (name value)
    (map? value) (str/join ", " (map (fn [[k v]] (str (name k) " " (format-value v nil))) value))
    :else (pr-str value)))

(defn- label [pid-name]
  (get labels pid-name (str/capitalize (str/replace (name pid-name) "-" " "))))

(defn- hex-byte [n]
  (.padStart (.toUpperCase (.toString n 16)) 2 "0"))

(defn- connection-badge []
  (let [connection @(rf/subscribe [::subs/connection])
        status @(rf/subscribe [::subs/status])]
    [:div.badges
     [:span.badge {:class (name connection)} (name connection)]
     (when status
       [:span.badge {:class (name (:state status))}
        (str (name (:source status)) " " (name (:state status)))])]))

(defn- source-controls []
  (let [adapters @(rf/subscribe [::subs/adapters])
        selected @(rf/subscribe [::subs/selected-path])
        status @(rf/subscribe [::subs/status])
        request @(rf/subscribe [::subs/request])
        busy? @(rf/subscribe [::subs/busy?])
        switch! #(rf/dispatch [::events/switch-source %])]
    [:section.controls
     [:div.control-row
      [:label {:for "adapter"} "Adapter"]
      [:select#adapter {:value (or selected "")
                        :disabled (empty? adapters)
                        :on-change #(rf/dispatch [::events/select-adapter (.. % -target -value)])}
       (if (empty? adapters)
         [:option {:value ""} (if (nil? adapters) "Scanning…" "No adapters found")]
         (for [{:keys [path product serial]} adapters]
           ^{:key path}
           [:option {:value path} (str/join " · " (remove nil? [product serial path]))]))]
      [:button {:on-click #(rf/dispatch [::events/refresh-adapters])} "Rescan"]
      [:button.primary {:disabled (or busy? (not selected))
                        :on-click #(switch! {:type :live :path selected})}
       (if (and (= :live (:source status)) (= selected (:path status))) "Reconnect" "Connect")]
      [:button {:disabled (or busy? (= :none (:source status)))
                :on-click #(switch! {:type :none})}
       "Disconnect"]
      [:button {:disabled busy?
                :on-click #(switch! {:type :replay})}
       "Replay recording"]]
     (when (= :failed (:state request))
       [:p.error (error-message (:error request))])
     (when (= :failed (:state status))
       [:p.error (error-message (:error status))])]))

(defn- field
  "One row of a panel. `value` is shown when present, else `missing`."
  ([term value]
   (field term value nil))
  ([term value missing]
   (when (or (some? value) missing)
     [:<>
      [:dt term]
      (if (some? value)
        [:dd value]
        [:dd.muted missing])])))

(defn- panel [title & fields]
  [:section.panel
   [:h2 title]
   (if (some some? fields)
     (into [:dl] fields)
     [:p.muted "Nothing yet."])])

(defn- source-panel []
  (let [status @(rf/subscribe [::subs/status])
        {:keys [recording]} @(rf/subscribe [::subs/info])]
    [panel "Source"
     (field "Source" (some-> (:source status) name))
     (field "State" (some-> (:state status) name))
     (field "Recorded" (some-> (:recorded-at recording) (subs 0 16) (str/replace "T" " ") (str " UTC")))
     (field "Conditions" (:state recording))
     (field "Length" (some-> (:duration-ms recording) (/ 1000) (str " s")))]))

(defn- adapter-panel []
  (let [{:keys [adapter]} @(rf/subscribe [::subs/info])
        firmware @(rf/subscribe [::subs/firmware])]
    [panel "Adapter"
     (field "Product" (:product adapter))
     (field "Serial" (:serial adapter))
     (field "Device" (:path adapter))
     (field "Firmware" firmware)
     (field "Battery" (some-> (:battery-volts adapter) (.toFixed 2) (str " V")))]))

(defn- unread-reason
  "Why a vehicle item could not be read."
  [{:keys [error nrc]}]
  (case error
    nil nil
    :negative-response (str "Not supported (NRC " (hex-byte nrc) ")")
    :timeout "No reply"
    (error-message error)))

(defn- vehicle-panel []
  (let [{:keys [source vehicle]} @(rf/subscribe [::subs/info])
        {:keys [vin calibration-ids calibration-verification-numbers ecu-name ecu-id errors]} vehicle
        live? (= :live source)]
    [panel "Vehicle"
     (field "Vehicle" (:description vehicle))
     (field "VIN" vin (when live? (unread-reason (:vin errors))))
     (field "Calibration ID" (some->> (seq calibration-ids) (str/join ", "))
            (when live? (unread-reason (:calibration-ids errors))))
     (field "CVN" (some->> (seq calibration-verification-numbers) (str/join ", "))
            (when live? (unread-reason (:calibration-verification-numbers errors))))
     (field "ECU name" ecu-name (when live? (unread-reason (:ecu-name errors))))
     (field "ECU ID (SSM)" ecu-id (when live? (unread-reason (:ecu-id errors))))]))

(defn- diagnostics-panel []
  (let [{:keys [mil-on? dtc-count monitors]} (:value @(rf/subscribe [::subs/sample :monitor-status]))
        incomplete (keep (fn [[monitor state]] (when (= :incomplete state) (name monitor))) monitors)]
    [panel "Diagnostics"
     (field "Check engine" (when (some? mil-on?) (if mil-on? "On" "Off")))
     (field "Stored codes" dtc-count)
     (field "Monitors" (when (seq monitors)
                         (str (- (count monitors) (count incomplete)) " of " (count monitors) " complete")))
     (field "Incomplete" (when (seq incomplete)
                           (str/replace (str/join ", " incomplete) "-" " ")))]))

(defn- readout [class pid-name]
  (let [{:keys [value unit]} @(rf/subscribe [::subs/sample pid-name])]
    [:div {:class class}
     [:div.readout-label (label pid-name)]
     [:div.readout-value
      (if (some? value) (format-value value unit) "—")
      [:span.readout-unit (get unit-labels unit "")]]]))

(defn- readouts []
  [:<>
   (into [:section.readouts]
         (map (fn [pid-name] ^{:key pid-name} [readout "readout" pid-name]))
         primary-readouts)
   (into [:section.groups]
         (for [[title pid-names] readout-groups]
           ^{:key title}
           [:section.group
            [:h2 title]
            (into [:div.tiles]
                  (map (fn [pid-name] ^{:key pid-name} [readout "tile" pid-name]))
                  pid-names)]))])

(defn- sample-table []
  (let [samples @(rf/subscribe [::subs/sample-list])]
    [:details.all-samples {:open true}
     [:summary (str "All PIDs (" (count samples) ")")]
     [:table.samples
      [:thead
       [:tr [:th "PID"] [:th "Name"] [:th.value "Value"] [:th "Unit"]]]
      [:tbody
       (for [{:keys [pid name value unit]} samples]
         ^{:key pid}
         [:tr
          [:td.pid (hex-byte pid)]
          [:td (label name)]
          [:td.value (format-value value unit)]
          [:td (get unit-labels unit "")]])]]]))

(defn app []
  [:main
   [:header
    [:h1 "Engine"]
    [connection-badge]]
   [source-controls]
   [:section.panels
    [source-panel]
    [adapter-panel]
    [vehicle-panel]
    [diagnostics-panel]]
   [readouts]
   [sample-table]])
