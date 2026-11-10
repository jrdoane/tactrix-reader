(ns tactrix-reader.dashboard.views
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [tactrix-reader.dashboard.events :as events]
            [tactrix-reader.dashboard.format :refer [did-hex format-value label unit-labels]]
            [tactrix-reader.dashboard.map-view :as map-view]
            [tactrix-reader.dashboard.subs :as subs]))

(def ^:private inferred-signals
  "Signals identified from the car's own CAN traffic by how they behave, but
  matched to no PID, so not confirmed. See lib/device/docs/can-broadcast.md."
  #{:gear :steering-angle :brake :brake-pressure :parking-brake :driver-seatbelt
    :wheel-speeds :longitudinal-acceleration :lateral-acceleration :yaw-rate
    :fuel-flow :outside-temp :fuel-gauge :engine-starts :warm-up-cycles
    :door-front-left :door-front-right :door-rear-left :door-rear-right})

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
                    :barometric-pressure :timing-advance]]
   ["Temperatures" [:oil-temp :intake-air-temp :ambient-air-temp
                    :catalyst-temp-bank-1-sensor-1]]
   ["Fuel" [:fuel-system-status :commanded-lambda :wideband-oxygen-sensor-1-current
            :short-term-fuel-trim-bank-1 :long-term-fuel-trim-bank-1
            :oxygen-sensor-bank-1-sensor-2 :fuel-level]]
   ["Electrical and gearbox" [:control-module-voltage :auxiliary-io-status]]])

(def ^:private can-groups
  [["Driver" [:steering-angle :brake :brake-pressure :parking-brake
              :driver-seatbelt]]
   ["Motion" [:longitudinal-acceleration :lateral-acceleration :yaw-rate]]
   ["Wheel speeds" [:wheel-front-left :wheel-front-right
                    :wheel-rear-left :wheel-rear-right]]
   ["Doors" [:door-front-left :door-front-right :door-rear-left :door-rear-right]]
   ["Engine and outside" [:fuel-flow :outside-temp]]
   ["Fuel gauge and counters" [:fuel-gauge :engine-starts :warm-up-cycles]]])

(defn- paired-notes
  "Notes for DIDs that move together, each naming the other in its pair."
  [note & pairs]
  (into {} (for [[a b] pairs
                 [did other] [[a b] [b a]]]
             [did (str note "; pairs with " (did-hex other))])))

(def ^:private did-notes
  "What the 2026-10-29-did-drive recording showed about DIDs that match no
  PID. These are inferences; see lib/device/docs/subaru-ssm.md."
  (merge
   (zipmap (range 0x1000 0x1100 0x20) (repeat "Supported-DID bitmap"))
   (zipmap [0x101D 0x1022 0x10E3] (repeat "Held at 00; knock correction is not ruled out"))
   (zipmap [0x10AE 0x10B1 0x10B4 0x10B5] (repeat "Varies under load"))
   (paired-notes "Possibly cam timing" [0x10EC 0x10ED])
   (paired-notes "Loosely follows load and timing" [0x10EE 0x10EF] [0x10F0 0x10F1])
   (paired-notes "Drops at once in fuel cut" [0x10B6 0x10B7] [0x10B8 0x10B9])
   (paired-notes "Opposite flags that swap at the first full throttle" [0x1088 0x1089])
   {0x103D "Changes between drives only: 2A, 2D, 2F"
    0x10A5 "Rises above about 50% load"
    0x10DE "Follows load and timing advance"}))

(def ^:private did-comparisons
  "For each DID in `uds/did-signals`, by name, the polled value it was
  matched against, from the PID samples by name, and that value's unit."
  {:throttle-position [#(get-in % [:throttle-position :value]) :percent]
   :accelerator-pedal [#(get-in % [:relative-accelerator-pedal-position :value]) :percent]
   :wideband-current [#(get-in % [:wideband-oxygen-sensor-1-current :value :milliamps]) :milliamps]
   :lambda [#(get-in % [:wideband-oxygen-sensor-1-current :value :lambda]) :lambda]
   :air-per-revolution [#(let [maf (get-in % [:maf-air-flow :value])
                               rpm (get-in % [:engine-rpm :value])]
                           (when (and maf (pos? rpm))
                             (/ maf (/ rpm 60))))
                        :grams-per-revolution]
   :mass-air-flow [#(get-in % [:maf-air-flow :value]) :grams-per-second]
   :charging-voltage-target [#(get-in % [:control-module-voltage :value]) :volts]})

(def ^:private error-messages
  {:missing-path "No adapter was found."
   :not-found-or-permission-denied "The adapter could not be opened. Is another program using it?"
   :no-vehicle-power "The adapter reads no battery voltage. Is it plugged into the car?"
   :connect-failed "The adapter refused the CAN connection."
   :unknown-adapter "That adapter is no longer connected. Rescan and try again."
   :request-failed "The server did not answer."
   :timeout "The adapter did not answer."
   :not-live "Recording needs a running live connection."
   :not-running "The adapter is not polling yet."
   :already-recording "A recording is already running."
   :not-recording "No recording is running."
   :unknown-recording "That recording is no longer in the recordings folder."
   :no-recording "There is no recording to replay."})

(defn- error-message [error]
  (get error-messages error (some-> error name (str/replace "-" " "))))

(defn- hex-byte [n]
  (.padStart (.toUpperCase (.toString n 16)) 2 "0"))

(defn- can-id-hex [n]
  (.padStart (.toUpperCase (.toString n 16)) 3 "0"))

(defn- on-off [value]
  (if value "on" "off"))

(defn- open-closed [value]
  (if value "open" "closed"))

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
   :door-front-left open-closed
   :door-front-right open-closed
   :door-rear-left open-closed
   :door-rear-right open-closed
   :monitor-status (fn [{:keys [mil-on? dtc-count monitors]}]
                     (str "check engine " (if mil-on? "on" "off")
                          ", " dtc-count " stored codes, "
                          (count (filter #{:complete} (vals monitors))) " of "
                          (count monitors) " monitors complete"))
   :auxiliary-io-status #(case (:manual-transmission-in-gear? %)
                           true "in gear"
                           false "neutral"
                           nil "—")
   :wideband-oxygen-sensor-1-current #(some-> (:lambda %) (.toFixed 3))
   ;; The Impreza has one fuel system.
   :fuel-system-status #(let [state (:system-1 %)]
                          (if (keyword? state)
                            (str/replace (name state) "-" " ")
                            (str state)))
   :oxygen-sensor-bank-1-sensor-2 #(some-> (:volts %) (.toFixed 3))})

(def ^:private formats-with-unit
  "Names whose `value-formats` entry already shows the unit."
  #{:steering-angle})

(def ^:private display-units
  "Units for values whose PID gives none because it decodes to a map, for
  the part `value-formats` shows."
  {:wideband-oxygen-sensor-1-current :lambda
   :oxygen-sensor-bank-1-sensor-2 :volts})

(defn- display-value [value-name value unit]
  (if-let [format-named (get value-formats value-name)]
    (format-named value)
    (format-value value unit)))

(defn- format-duration [ms]
  (let [seconds (js/Math.round (/ ms 1000))]
    (if (< seconds 60)
      (str seconds " s")
      (str (quot seconds 60) " min " (mod seconds 60) " s"))))

(defn- format-time
  "An ISO instant in the browser's local time, such as `Oct 30, 2026, 8:04 AM`."
  [instant]
  (when instant
    (.toLocaleString (js/Date. instant) js/undefined #js {:dateStyle "medium" :timeStyle "short"})))

(defn- format-size [bytes]
  (if (< bytes 1e6)
    (str (js/Math.round (/ bytes 1e3)) " kB")
    (str (.toFixed (/ bytes 1e6) 1) " MB")))

(defn- format-clock
  "Elapsed ms as `m:ss`."
  [ms]
  (let [seconds (max 0 (js/Math.floor (/ ms 1000)))]
    (str (quot seconds 60) ":" (.padStart (str (mod seconds 60)) 2 "0"))))

(defn- connection-badge []
  (let [connection @(rf/subscribe [::subs/connection])
        status @(rf/subscribe [::subs/status])]
    [:div.badges
     [:span.badge {:class (name connection)} (name connection)]
     (when status
       [:span.badge {:class (name (:state status))}
        (str (name (:source status)) " " (name (:state status)))])]))

(defn- recording-controls
  "Record and Stop, with a note saved in the recording's meta.edn."
  []
  (let [recording @(rf/subscribe [::subs/recording])
        recordable? @(rf/subscribe [::subs/recordable?])
        request @(rf/subscribe [::subs/request])
        note @(rf/subscribe [::subs/note])
        now @(rf/subscribe [::subs/now])
        recording? (= :recording (:state recording))
        pending? (= :pending (:state request))]
    [:div.control-row
     [:label {:for "note"} "Note"]
     [:input#note {:type "text"
                   :placeholder "Conditions, such as cold start then highway"
                   :value note
                   :disabled recording?
                   :on-change #(rf/dispatch [::events/set-note (.. % -target -value)])}]
     (if recording?
       [:button.record.active {:disabled pending?
                               :on-click #(rf/dispatch [::events/stop-recording])}
        (str "Stop recording " (when now (format-clock (- now (js/Date.parse (:started-at recording))))))]
       [:button.record {:disabled (or pending? (not recordable?))
                        :title (when-not recordable? "Connect to the car to record")
                        :on-click #(rf/dispatch [::events/start-recording])}
        "Record"])]))

(defn- source-controls []
  (let [adapters @(rf/subscribe [::subs/adapters])
        selected @(rf/subscribe [::subs/selected-path])
        status @(rf/subscribe [::subs/status])
        request @(rf/subscribe [::subs/request])
        busy? @(rf/subscribe [::subs/busy?])
        poll-dids? @(rf/subscribe [::subs/poll-dids?])
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
                        :on-click #(switch! {:type :live :path selected :dids? poll-dids?})}
       (if (and (= :live (:source status)) (= selected (:path status))) "Reconnect" "Connect")]
      [:button {:disabled (or busy? (= :none (:source status)))
                :on-click #(switch! {:type :none})}
       "Disconnect"]
      [:label.check {:title "Alternate PID requests with the scanned UDS DIDs, which roughly halves the PID rate"}
       [:input {:type "checkbox"
                :checked poll-dids?
                :disabled busy?
                :on-change #(rf/dispatch [::events/set-poll-dids (.. % -target -checked)])}]
       "Poll DIDs"]]
     [recording-controls]
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
     (field "Recording" (:name recording))
     (field "Recorded" (format-time (:recorded-at recording)))
     (field "Note" (:note recording))
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

(defn- code-list [codes]
  (when codes
    (if (seq codes) (str/join ", " codes) "None")))

(defn- diagnostics-panel []
  (let [{:keys [mil-on? dtc-count monitors]} (:value @(rf/subscribe [::subs/sample :monitor-status]))
        {:keys [source trouble-codes]} @(rf/subscribe [::subs/info])
        {:keys [stored pending permanent errors]} trouble-codes
        live? (= :live source)
        incomplete (keep (fn [[monitor state]] (when (= :incomplete state) (name monitor))) monitors)]
    [panel "Diagnostics"
     (field "Check engine" (when (some? mil-on?) (if mil-on? "On" "Off")))
     ;; The count updates while polling; the codes are read on connect.
     (field "Code count" dtc-count)
     (field "Stored codes" (code-list stored) (when live? (unread-reason (:stored errors))))
     (field "Pending codes" (code-list pending) (when live? (unread-reason (:pending errors))))
     (field "Permanent codes" (code-list permanent) (when live? (unread-reason (:permanent errors))))
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
        [:span.readout-unit (get unit-labels (or unit (get display-units value-name)) "")])]]))

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

(defn- did-tile
  "A DID matched to a PID: its fitted value, or its raw number when it has no
  scale, over the polled value it was matched against."
  [{:keys [did name value unit number matches]} samples]
  (let [[polled polled-unit] (get did-comparisons name)
        polled-value (when polled (polled samples))]
    [:div.tile
     [:div.readout-label
      [:span (label name)]
      [:span.tag {:title (str "UDS data identifier " (did-hex did))} (did-hex did)]]
     [:div.readout-value
      (if (some? value) (format-value value unit) number)
      [:span.readout-unit (if (some? value) (get unit-labels unit "") "raw")]]
     [:div.readout-compare
      (str (if matches (str "PID " (hex-byte matches)) "MAF ÷ rpm") ": "
           (if (some? polled-value)
             (str (format-value polled-value polled-unit) " " (get unit-labels polled-unit ""))
             "—"))]]))

(defn- did-table [dids]
  (let [ranges @(rf/subscribe [::subs/did-ranges])]
    [:details.all-samples
     [:summary (str "All DIDs (" (count dids) ")")]
     [:table.samples
      [:thead
       [:tr [:th "DID"] [:th "Data"] [:th.value "Value"] [:th.value "Range seen"] [:th "What it is"]]]
      [:tbody
       (for [{:keys [did name data number]} dids
             :let [[low high] (get ranges did)]]
         ^{:key did}
         [:tr
          [:td.pid (did-hex did)]
          [:td.data (str/join " " (map hex-byte data))]
          [:td.value number]
          [:td.value {:class (when (not= low high) "varies")}
           (if (= low high) (str low) (str low "–" high))]
          [:td.note (if name
                      (str (label name) ", fitted")
                      (get did-notes did ""))]])]]]))

(defn- did-block
  "UDS data identifiers, which only recordings made with record-dids have."
  []
  (let [dids @(rf/subscribe [::subs/did-list])
        samples @(rf/subscribe [::subs/samples])]
    (when (seq dids)
      [:section.block
       [:div.block-head
        [:h2 "UDS data identifiers"]
        [:span.tag.inferred {:title "Scales fitted from one drive, not documented"} "fitted"]]
       [:p.block-note
        "Read with service 22 alongside the PIDs. Each tile is a DID matched to a "
        "PID, with a scale fitted from one drive, over the polled value it was "
        "matched against. What the others hold is unknown; ranges count from when "
        "the source started or the page connected."]
       (into [:div.tiles.did-tiles]
             (comp (filter :name)
                   (map (fn [reply] ^{:key (:did reply)} [did-tile reply samples])))
             dids)
       [did-table dids]])))

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
          [:td.unit (get unit-labels (or unit (get display-units name)) "")]
          [:td.source (if-let [can-id (get can-ids name)]
                        (str "CAN " (can-id-hex can-id) ", polled")
                        "polled")]])]]]))

(defn- vehicle-key [{:keys [vin calibration-ids description]}]
  (or vin (first calibration-ids) description))

(defn- vehicle-heading [{:keys [vin calibration-ids ecu-name description] :as vehicle}]
  (let [details (remove nil? [(some->> vin (str "VIN "))
                              (some->> (seq calibration-ids) (str/join ", ") (str "Calibration "))
                              ecu-name])]
    [:div.block-head
     [:h3 (or description (if (vehicle-key vehicle) "Unnamed vehicle" "Unknown vehicle"))]
     (when (seq details)
       [:span.muted (str/join " · " details)])
     (when-not (vehicle-key vehicle)
       [:span.muted "recorded before vehicles were saved"])]))

(defn- recording-state
  "Why a recording may be unusable, or nil."
  [{:keys [in-progress? incomplete? stopped-by error dir]} active-dir]
  (cond
    (and in-progress? (= dir active-dir)) "recording"
    in-progress? "interrupted"
    incomplete? "no meta.edn"
    (= :error stopped-by) (str "adapter error" (some->> error (str ": ")))
    :else nil))

(defn- recordings-block
  "Saved recordings, grouped by the vehicle each was recorded on, each with
  buttons that open it in the map and replay it."
  []
  (let [recordings @(rf/subscribe [::subs/recordings])
        dir @(rf/subscribe [::subs/recordings-dir])
        {:keys [source recording]} @(rf/subscribe [::subs/info])
        active @(rf/subscribe [::subs/recording])
        busy? @(rf/subscribe [::subs/busy?])
        active-dir (when (= :recording (:state active)) (:dir active))
        playing-dir (when (= :replay source) (:dir recording))
        by-vehicle (group-by (comp vehicle-key :vehicle) recordings)
        ;; Vehicles in the order of their newest recording.
        vehicles (distinct (map (comp vehicle-key :vehicle) recordings))]
    [:section.block
     [:div.block-head
      [:h2 "Recordings"]
      [:button {:on-click #(rf/dispatch [::events/refresh-recordings])} "Refresh"]]
     [:p.block-note "Saved in " [:code (or dir "~/.tactrix-reader/recordings")] "."]
     (cond
       (nil? recordings) [:p.muted "Loading…"]
       (empty? recordings) [:p.muted "No recordings yet. Connect to the car and press Record."]
       :else
       (into [:div.recordings]
             (for [key vehicles
                   :let [entries (get by-vehicle key)]]
               ^{:key (or key "unknown")}
               [:section.vehicle-recordings
                ;; The newest identity, with a description from any of them.
                [vehicle-heading (cond-> (:vehicle (first entries))
                                   (some (comp :description :vehicle) entries)
                                   (assoc :description (some (comp :description :vehicle) entries)))]
                [:table.samples
                 [:thead
                  [:tr [:th "Recorded"] [:th "Name"] [:th.value "Length"] [:th "Note"]
                   [:th "DIDs"] [:th.value "Size"] [:th "State"] [:th]]]
                 [:tbody
                  (for [{:keys [name dir recorded-at duration-ms note dids? raw-bytes] :as entry} entries
                        :let [playing? (= dir playing-dir)]]
                    ^{:key dir}
                    [:tr {:class (when playing? "playing")}
                     [:td.name (format-time recorded-at)]
                     [:td.pid name]
                     [:td.value (some-> duration-ms format-duration)]
                     [:td.note note]
                     [:td.source (when dids? "yes")]
                     [:td.value (format-size raw-bytes)]
                     [:td.note (recording-state entry active-dir)]
                     [:td.value.actions
                      [:button {:disabled (= dir active-dir)
                                :on-click #(rf/dispatch [::events/open-map name])}
                       "Map"]
                      [:button {:disabled (or busy? (= dir active-dir))
                                :on-click #(rf/dispatch [::events/switch-source {:type :replay :recording name}])}
                       (if playing? "Replaying" "Replay")]]])]]])))]))

(defn- view-toggle [view]
  [:nav.view-toggle
   (for [[key text] [[:dashboard "Dashboard"] [:map "Map"]]]
     ^{:key key}
     [:button {:class (when (= key view) "active")
               :aria-pressed (= key view)
               :on-click #(rf/dispatch [::events/show-view key])}
      text])])

(defn app []
  (let [view @(rf/subscribe [::subs/view])]
    [:main
     [:header
      [:div.title
       [:h1 "Engine"]
       [view-toggle view]]
      [connection-badge]]
     (if (= :map view)
       [map-view/map-view]
       [:<>
        [source-controls]
        [:section.panels
         [source-panel]
         [adapter-panel]
         [vehicle-panel]
         [diagnostics-panel]]
        [readouts]
        [did-block]
        [sample-table]
        [recordings-block]])]))
