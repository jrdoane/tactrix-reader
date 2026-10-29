(ns tactrix-reader.dashboard.views
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [tactrix-reader.dashboard.events :as events]
            [tactrix-reader.dashboard.subs :as subs]))

(def ^:private unit-labels
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
   :degrees-per-second "°/s"})

(def ^:private unit-decimals
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
   :g 2})

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
   :wheel-front-left "Front left"
   :wheel-front-right "Front right"
   :wheel-rear-left "Rear left"
   :wheel-rear-right "Rear right"
   :longitudinal-acceleration "Acceleration"
   :lateral-acceleration "Lateral acceleration"
   :yaw-rate "Yaw rate"
   :fuel-flow "Fuel flow"
   :outside-temp "Outside"})

(def ^:private inferred-signals
  "Signals identified from the car's own CAN traffic by how they behave, but
  matched to no PID, so not confirmed. See lib/device/docs/can-broadcast.md."
  #{:gear :steering-angle :brake :brake-pressure :parking-brake :driver-seatbelt
    :wheel-speeds :longitudinal-acceleration :lateral-acceleration :yaw-rate
    :fuel-flow :outside-temp})

(def ^:private wheel-tiles
  "Tiles for each wheel in the :wheel-speeds signal."
  {:wheel-front-left :front-left
   :wheel-front-right :front-right
   :wheel-rear-left :rear-left
   :wheel-rear-right :rear-right})

(def ^:private primary-readouts
  [:engine-rpm :vehicle-speed :gear :coolant-temp :throttle-position])

(def ^:private readout-groups
  [["Throttle and pedal" [:relative-throttle-position :commanded-throttle-actuator
                          :relative-accelerator-pedal-position
                          :accelerator-pedal-position-d]]
   ["Air and load" [:engine-load :maf-air-flow :intake-manifold-pressure
                    :timing-advance]]
   ["Temperatures" [:oil-temp :intake-air-temp :ambient-air-temp]]
   ["Fuel" [:short-term-fuel-trim-bank-1 :long-term-fuel-trim-bank-1
            :commanded-lambda :fuel-level]]
   ["Electrical and gearbox" [:control-module-voltage :auxiliary-io-status]]])

(def ^:private can-groups
  [["Driver" [:steering-angle :brake :brake-pressure :parking-brake
              :driver-seatbelt]]
   ["Motion" [:longitudinal-acceleration :lateral-acceleration :yaw-rate]]
   ["Wheel speeds" [:wheel-front-left :wheel-front-right
                    :wheel-rear-left :wheel-rear-right]]
   ["Engine and outside" [:fuel-flow :outside-temp]]])

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

(def ^:private unit-conversions
  "Units shown converted, as [factor decimal-places]. The data keeps the
  PIDs' units; kPa is shown as bar."
  {:kpa [0.01 2]})

(defn- format-value [value unit]
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

(defn- label [pid-name]
  (get labels pid-name (str/capitalize (str/replace (name pid-name) "-" " "))))

(defn- hex-byte [n]
  (.padStart (.toUpperCase (.toString n 16)) 2 "0"))

(defn- can-id-hex [n]
  (.padStart (.toUpperCase (.toString n 16)) 3 "0"))

(defn- on-off [value]
  (if value "on" "off"))

(def ^:private value-formats
  "Display for values that read better as words than as numbers."
  {:gear #(if (= :neutral %) "N" (str %))
   ;; Positive is a left turn.
   :steering-angle #(cond
                      (pos? %) (str (.toFixed % 1) "° L")
                      (neg? %) (str (.toFixed (- %) 1) "° R")
                      :else "0.0°")
   :brake on-off
   :parking-brake on-off
   :driver-seatbelt name
   :monitor-status (fn [{:keys [mil-on? dtc-count monitors]}]
                     (str "check engine " (if mil-on? "on" "off")
                          ", " dtc-count " stored codes, "
                          (count (filter #{:complete} (vals monitors))) " of "
                          (count monitors) " monitors complete"))
   :auxiliary-io-status #(case (:manual-transmission-in-gear? %)
                           true "in gear"
                           false "neutral"
                           nil "—")})

(def ^:private formats-with-unit
  "Names whose `value-formats` entry already shows the unit."
  #{:steering-angle})

(defn- display-value [value-name value unit]
  (if-let [format-named (get value-formats value-name)]
    (format-named value)
    (format-value value unit)))

(defn- format-duration [ms]
  (let [seconds (js/Math.round (/ ms 1000))]
    (if (< seconds 60)
      (str seconds " s")
      (str (quot seconds 60) " min " (mod seconds 60) " s"))))

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
     (field "Length" (some-> (:duration-ms recording) format-duration))]))

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

(def ^:private inferred-tag
  [:span.tag.inferred {:title "Identified from CAN traffic, not confirmed against a PID"}
   "inferred"])

(defn- readout
  "A PID sample or, for names in `inferred-signals` and `wheel-tiles`, an
  inferred CAN signal. With `tags?`, inferred signals are tagged inferred and
  PIDs also decoded from the CAN bus are tagged CAN."
  [class value-name tags?]
  (let [wheel (get wheel-tiles value-name)
        inferred? (or (some? wheel) (contains? inferred-signals value-name))
        sample @(rf/subscribe [(if inferred? ::subs/signal ::subs/sample)
                               (if wheel :wheel-speeds value-name)])
        {:keys [value unit]} (if wheel (update sample :value get wheel) sample)
        can-id @(rf/subscribe [::subs/can-id value-name])]
    [:div {:class class}
     [:div.readout-label
      [:span (label value-name)]
      (when tags?
        (cond
          inferred? inferred-tag
          can-id [:span.tag {:title (str "Also decoded from CAN ID " (can-id-hex can-id))}
                  "CAN"]))]
     [:div.readout-value
      (if (some? value) (display-value value-name value unit) "—")
      (when-not (contains? formats-with-unit value-name)
        [:span.readout-unit (get unit-labels unit "")])]]))

(defn- groups [titled-names tags?]
  (into [:div.groups]
        (for [[title value-names] titled-names]
          ^{:key title}
          [:section.group
           [:h2 title]
           (into [:div.tiles]
                 (map (fn [value-name] ^{:key value-name} [readout "tile" value-name tags?]))
                 value-names)])))

(defn- readouts []
  [:<>
   (into [:section.readouts]
         (map (fn [value-name] ^{:key value-name} [readout "readout" value-name true]))
         primary-readouts)
   [:section.block
    [:div.block-head
     [:h2 "OBD-II PIDs"]]
    [groups readout-groups true]]
   [:section.block
    [:div.block-head
     [:h2 "From the CAN bus"]
     inferred-tag]
    [:p.block-note
     "Identified from the car's own traffic by how they behave, but matched "
     "to no PID, so scales and directions are not confirmed."]
    [groups can-groups false]]])

(defn- sample-table []
  (let [samples @(rf/subscribe [::subs/sample-list])
        can-ids @(rf/subscribe [::subs/can-ids])]
    [:details.all-samples {:open true}
     [:summary (str "All PIDs (" (count samples) ")")]
     [:table.samples
      [:thead
       [:tr [:th "PID"] [:th "Name"] [:th.value "Value"] [:th "Unit"] [:th "Source"]]]
      [:tbody
       (for [{:keys [pid name value unit]} samples]
         ^{:key pid}
         [:tr
          [:td.pid (hex-byte pid)]
          [:td.name (label name)]
          [:td.value (display-value name value unit)]
          [:td.unit (get unit-labels unit "")]
          [:td.source (if-let [can-id (get can-ids name)]
                        (str "CAN " (can-id-hex can-id) ", polled")
                        "polled")]])]]]))

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
