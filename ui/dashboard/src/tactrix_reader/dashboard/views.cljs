(ns tactrix-reader.dashboard.views
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [tactrix-reader.dashboard.bus-view :as bus-view]
            [tactrix-reader.dashboard.catalog :as catalog]
            [tactrix-reader.dashboard.events :as events]
            [tactrix-reader.dashboard.format :refer [did-hex format-value label tile-text tile-unit
                                                     unit-labels]]
            [tactrix-reader.dashboard.map-view :as map-view]
            [tactrix-reader.dashboard.subs :as subs]))

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
  "Record and Stop, with a note saved in the recording's meta.edn. Shown only
  while a live source can be recorded."
  []
  (let [recording @(rf/subscribe [::subs/recording])
        recordable? @(rf/subscribe [::subs/recordable?])
        request @(rf/subscribe [::subs/request])
        note @(rf/subscribe [::subs/note])
        now @(rf/subscribe [::subs/now])
        recording? (= :recording (:state recording))
        pending? (= :pending (:state request))]
    (when (or recordable? recording?)
      [:div.bar-group.grow
       [:input#note {:type "text"
                     :aria-label "Note for the recording"
                     :placeholder "Note, such as cold start then highway"
                     :value note
                     :disabled recording?
                     :on-change #(rf/dispatch [::events/set-note (.. % -target -value)])}]
       (if recording?
         [:button.record.active {:disabled pending?
                                 :on-click #(rf/dispatch [::events/stop-recording])}
          (str "Stop recording " (when now (format-clock (- now (js/Date.parse (:started-at recording))))))]
         [:button.record {:disabled pending?
                          :on-click #(rf/dispatch [::events/start-recording])}
          "Record"])])))

(defn- adapter-controls []
  (let [adapters @(rf/subscribe [::subs/adapters])
        selected @(rf/subscribe [::subs/selected-path])
        status @(rf/subscribe [::subs/status])
        busy? @(rf/subscribe [::subs/busy?])
        poll-dids? @(rf/subscribe [::subs/poll-dids?])
        switch! #(rf/dispatch [::events/switch-source %])]
    [:div.bar-group
     [:select#adapter {:value (or selected "")
                       :aria-label "Adapter"
                       :disabled (empty? adapters)
                       :on-change #(rf/dispatch [::events/select-adapter (.. % -target -value)])}
      (if (empty? adapters)
        [:option {:value ""} (if (nil? adapters) "Scanning…" "No adapters found")]
        (for [{:keys [path product serial]} adapters]
          ^{:key path}
          [:option {:value path} (str/join " · " (remove nil? [product serial path]))]))]
     [:button {:on-click #(rf/dispatch [::events/refresh-adapters])} "Rescan"]
     (when (seq adapters)
       [:<>
        [:button.primary {:disabled (or busy? (not selected))
                          :on-click #(switch! {:type :live :path selected :dids? poll-dids?})}
         (if (and (= :live (:source status)) (= selected (:path status))) "Reconnect" "Connect")]
        [:label.check {:title "Alternate PID requests with the scanned UDS DIDs, which roughly halves the PID rate"}
         [:input {:type "checkbox"
                  :checked poll-dids?
                  :disabled busy?
                  :on-change #(rf/dispatch [::events/set-poll-dids (.. % -target -checked)])}]
         "Poll DIDs"]])
     (when (#{:live :replay} (:source status))
       [:button {:disabled busy?
                 :title "Stop the source"
                 :on-click #(switch! {:type :none})}
        (if (= :live (:source status)) "Disconnect" "Stop")])]))

(defn- source-summary
  "What is playing, on which car, and whether it has trouble codes, in a
  line."
  []
  (let [status @(rf/subscribe [::subs/status])
        {:keys [recording vehicle adapter]} @(rf/subscribe [::subs/info])
        {:keys [mil-on? dtc-count]} (:value @(rf/subscribe [::subs/sample :monitor-status]))]
    [:div.bar-group.summary
     [:strong (case (:source status)
                :replay (str "Replay · " (or (:name recording) "recording"))
                :live (str "Live · " (or (:product adapter) (:path status) "adapter"))
                "No source")]
     (when-let [car (or (:description vehicle) (:vin vehicle) (first (:calibration-ids vehicle)))]
       [:span.muted car])
     (when (some? mil-on?)
       [:span {:class (if (or mil-on? (pos? dtc-count)) "warn" "muted")}
        (str "check engine " (if mil-on? "on" "off") " · "
             dtc-count (if (= 1 dtc-count) " code" " codes"))])]))

(def ^:private replay-speeds [0.5 1 2 4 8])

(defn- transport
  "Where the replay is, with pause, a slider to seek, and the speed. While
  the slider is dragged it shows where it is dragged to, and seeks when it
  is let go."
  []
  (let [scrub (r/atom nil)]
    (fn []
      (let [{:keys [position-ms duration-ms paused? speed]} @(rf/subscribe [::subs/position])
            control! #(rf/dispatch [::events/control-replay %])
            commit! #(when-let [to @scrub]
                       (control! {:action :seek :position-ms to})
                       (reset! scrub nil))]
        (when duration-ms
          [:div.transport
           [:button {:aria-label (if paused? "Play" "Pause")
                     :title (if paused? "Play" "Pause")
                     :on-click #(control! {:action (if paused? :resume :pause)})}
            (if paused? "▶" "❚❚")]
           [:span.clock (format-clock (or @scrub position-ms))]
           [:input {:type "range"
                    :aria-label "Position in the recording"
                    :min 0
                    :max duration-ms
                    :step 100
                    :value (or @scrub position-ms)
                    :on-change #(reset! scrub (js/Number (.. % -target -value)))
                    :on-pointer-up commit!
                    :on-key-up commit!
                    :on-blur commit!}]
           [:span.clock (format-clock duration-ms)]
           [:select {:aria-label "Replay speed"
                     :value (str speed)
                     :on-change #(control! {:action :speed :speed (js/Number (.. % -target -value))})}
            (for [option (distinct (sort (conj replay-speeds speed)))]
              ^{:key option}
              [:option {:value (str option)} (str option "×")])]])))))

(declare source-panel adapter-panel vehicle-panel diagnostics-panel)

(defn- source-bar
  "One bar for the source on every tab: what is playing, the adapter, and
  recording, with the adapter, vehicle, and diagnostics a click away."
  []
  (let [status @(rf/subscribe [::subs/status])
        request @(rf/subscribe [::subs/request])
        details? @(rf/subscribe [::subs/details?])]
    [:section.controls
     [:div.source-bar
      [source-summary]
      (when (= :replay (:source status))
        [transport])
      [:button.details-toggle {:aria-expanded details?
                               :on-click #(rf/dispatch [::events/toggle-details])}
       (if details? "Hide details" "Details")]
      [adapter-controls]
      [recording-controls]]
     (when (= :failed (:state request))
       [:p.error (error-message (:error request))])
     (when (= :failed (:state status))
       [:p.error (error-message (:error status))])
     (when details?
       [:div.panels
        [source-panel]
        [adapter-panel]
        [vehicle-panel]
        [diagnostics-panel]])]))

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
   [:h3 title]
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

(def ^:private provenance
  "How a value is known, as [text explanation]. The same badges are used on
  tiles, in tables, and in the legend."
  {:polled ["polled" "Read by polling an OBD-II mode 01 PID"]
   :can ["CAN" "Decoded from the car's own CAN traffic and confirmed against the polled PID"]
   :inferred ["inferred" "Identified from CAN traffic by how it behaves, not confirmed against a PID"]
   :fitted ["fitted" "A UDS data identifier with a scale fitted from drives, not documented"]})

(defn- badge
  "A provenance badge, with `detail` (such as a CAN ID) added to its
  explanation."
  ([kind]
   (badge kind nil))
  ([kind detail]
   (let [[text title] (get provenance kind)]
     [:span.tag {:class (name kind)
                 :title (cond-> title detail (str " (" detail ")"))}
      text])))

(defn- provenance-legend []
  (into [:p.legend]
        (for [kind [:polled :can :inferred :fitted]]
          ^{:key kind}
          [:span.legend-item [badge kind] (second (get provenance kind))])))

(defn- tile-badge
  "The badge for a tile: inferred for a CAN signal, CAN for a PID that has
  also been decoded from the bus. Polled alone is the default and gets none."
  [{:keys [kind name]}]
  (if (= :signal kind)
    [badge :inferred]
    (when-let [can-id @(rf/subscribe [::subs/can-id name])]
      [badge :can (str "CAN ID " (can-id-hex can-id))])))

(defn- sparkline
  "The last minute of a channel, drawn small. It keeps its room when there
  is nothing to draw, so tiles do not change height."
  [key]
  (let [path @(rf/subscribe [::subs/sparkline key])]
    [:svg.spark {:viewBox "0 0 100 24" :preserveAspectRatio "none" :aria-hidden true}
     (when path [:path {:d path}])]))

(def ^:private long-text-length
  "The most characters a tile's value shows on one line at full size."
  9)

(defn- readout
  "One value: a `catalog/tiles` tile with its latest value and its last
  minute."
  [class tile badges?]
  (let [{:keys [value unit]} @(rf/subscribe [::subs/tile-value tile])
        text (or @(rf/subscribe [::subs/tile-override tile])
                 (tile-text tile value unit))]
    [:div {:class class :title "The line is the last minute"}
     [:div.readout-label
      [:span (:label tile)]
      (when badges? [tile-badge tile])]
     ;; Words too long for one line, such as a fuel system state, are set
     ;; smaller and wrap.
     [:div.readout-value {:class (when (< long-text-length (count text)) "long")}
      text
      [:span.readout-unit (tile-unit tile unit)]]
     [sparkline (:id tile)]]))

(defn- group-columns
  "A group is as wide as its tiles need, up to five across."
  [tiles]
  {:style {"--columns" (min 5 (count tiles))}})

(defn- section-block
  "A section of the dashboard that folds away: a heading that toggles it,
  then its groups, each a grid of tiles, flowing across the page."
  [{:keys [id title note groups] section-provenance :provenance}]
  (let [collapsed? (contains? @(rf/subscribe [::subs/collapsed]) id)]
    [:section.block
     [:div.block-head
      [:button.fold {:aria-expanded (not collapsed?)
                     :on-click #(rf/dispatch [::events/toggle-collapsed id])}
       [:span.chevron (if collapsed? "▸" "▾")]
       [:h2 title]]
      (when section-provenance [badge section-provenance])
      [:span.muted (str (reduce + (map (comp count :tiles) groups)) " values")]]
     (when-not collapsed?
       [:<>
        (when note [:p.block-note note])
        (into [:div.groups]
              (for [{:keys [title tiles] group-id :id} groups]
                ^{:key group-id}
                [:section.group
                 [:h3 title]
                 (into [:div.tiles (group-columns tiles)]
                       (map (fn [tile]
                              ^{:key (:id tile)}
                              ;; A section that is all one provenance says so once.
                              [readout "tile" tile (not section-provenance)]))
                       tiles)]))])]))

(defn- readouts []
  (let [primary @(rf/subscribe [::subs/primary-tiles])
        sections @(rf/subscribe [::subs/sections])]
    [:<>
     (into [:section.readouts]
           (map (fn [tile] ^{:key (:id tile)} [readout "readout" tile true]))
           primary)
     (for [section sections
           :when (seq (:groups section))]
       ^{:key (:id section)}
       [section-block section])]))

(defn- did-tile
  "A DID matched to a PID: its fitted value, or its raw number when it has no
  scale, over the polled value it was matched against."
  [{:keys [did name value unit number matches]} compare samples]
  (let [polled-value (catalog/compare-value compare samples)]
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
             (str (format-value polled-value unit) " " (get unit-labels unit ""))
             "—"))]
     [sparkline (keyword "did" (str "x" (did-hex did)))]]))

(defn- did-block
  "The DIDs matched to a PID, which only recordings made with record-dids
  and a live source polling DIDs have."
  []
  (let [dids @(rf/subscribe [::subs/did-list])
        samples @(rf/subscribe [::subs/samples])
        {catalog-dids :dids} @(rf/subscribe [::subs/catalog])
        compare-by-did (into {} (map (juxt :did :compare)) catalog-dids)
        collapsed? (contains? @(rf/subscribe [::subs/collapsed]) :dids)
        matched (filter :name dids)]
    (when (seq matched)
      [:section.block
       [:div.block-head
        [:button.fold {:aria-expanded (not collapsed?)
                       :on-click #(rf/dispatch [::events/toggle-collapsed :dids])}
         [:span.chevron (if collapsed? "▸" "▾")]
         [:h2 "UDS data identifiers"]]
        [badge :fitted]
        [:span.muted (str (count matched) " values")]]
       (when-not collapsed?
         [:<>
          [:p.block-note
           "Read with service 22 alongside the PIDs. Each tile is a DID matched to a "
           "PID, with a scale fitted from drives, over the polled value it was "
           "matched against. Every DID is listed under Signals."]
          (into [:div.tiles.did-tiles]
                (map (fn [reply]
                       ^{:key (:did reply)}
                       [did-tile reply (get compare-by-did (:did reply)) samples]))
                matched)])])))

(defn- entry-text
  "A whole entry's value as text for a table: its format's, each of its
  parts', or the plain value."
  [{:keys [format parts] :as entry} value unit]
  (cond
    (nil? value) "—"
    format (tile-text entry value unit)
    (seq parts) (str/join ", " (for [{:keys [part] part-label :label :as part-entry} parts]
                                 (str (when part-label (str part-label " "))
                                      (tile-text (merge (select-keys entry [:unit]) part-entry)
                                                 (get value part) unit))))
    :else (tile-text entry value unit)))

(defn- entry-unit [{:keys [parts] :as entry} unit]
  (tile-unit (cond-> entry
               (= 1 (count parts)) (merge (first parts)))
             unit))

(defn- pid-table
  "Every PID that has arrived."
  []
  (let [samples @(rf/subscribe [::subs/sample-list])
        can-ids @(rf/subscribe [::subs/can-ids])
        by-name (into {} (map (juxt (juxt :kind :name) identity)) @(rf/subscribe [::subs/entries]))]
    [:section.block
     [:div.block-head
      [:h2 "OBD-II PIDs"]
      [:span.muted (str (count samples) " arrived")]]
     [:table.samples.compact
      [:thead
       [:tr [:th "PID"] [:th "Name"] [:th.value "Value"] [:th "Unit"] [:th "Source"]]]
      [:tbody
       (for [{:keys [pid name value unit]} samples
             :let [entry (get by-name [:pid name] {:label (catalog/name-label name)})
                   can-id (get can-ids name)]]
         ^{:key pid}
         [:tr
          [:td.pid (hex-byte pid)]
          [:td.name (:label entry)]
          [:td.value (entry-text entry value unit)]
          [:td.unit (entry-unit entry unit)]
          [:td.source
           [badge :polled]
           (when can-id [badge :can (str "CAN ID " (can-id-hex can-id))])
           (when can-id [:span.source-id (can-id-hex can-id)])]])]]]))

(defn- signal-table
  "Every inferred CAN signal the catalog has or that has arrived."
  []
  (let [signals @(rf/subscribe [::subs/signals])
        entries (filter (comp #{:signal} :kind) @(rf/subscribe [::subs/entries]))]
    [:section.block
     [:div.block-head
      [:h2 "CAN signals"]
      [:span.muted (str (count entries) " decoded")]]
     [:table.samples.compact
      [:thead
       [:tr [:th "CAN ID"] [:th "Name"] [:th.value "Value"] [:th "Unit"] [:th "Source"]]]
      [:tbody
       (for [{:keys [name can-id] :as entry} (sort-by (juxt :can-id :label) entries)
             :let [{:keys [value unit]} (get signals name)]]
         ^{:key name}
         [:tr
          [:td.pid (some-> (or can-id (:can-id (get signals name))) can-id-hex)]
          [:td.name (:label entry)]
          [:td.value (entry-text entry value unit)]
          [:td.unit (entry-unit entry unit)]
          [:td.source [badge :inferred]]])]]]))

(defn- did-table
  "Every DID that has arrived, with what is known about it."
  []
  (let [dids @(rf/subscribe [::subs/did-list])
        ranges @(rf/subscribe [::subs/did-ranges])
        {:keys [did-notes]} @(rf/subscribe [::subs/catalog])]
    [:section.block
     [:div.block-head
      [:h2 "UDS data identifiers"]
      [:span.muted (if (seq dids)
                     (str (count dids) " arrived; ranges count from when the source started or the page connected")
                     "none in this source; replay a recording made with DIDs, or connect with Poll DIDs")]]
     (when (seq dids)
       [:table.samples.compact
        [:thead
         [:tr [:th "DID"] [:th "Data"] [:th.value "Value"] [:th.value "Range seen"] [:th "What it is"] [:th "Source"]]]
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
            [:td.note (if name (label name) (get did-notes did ""))]
            [:td.source (when name [badge :fitted])]])]])]))

(defn- signals-view []
  [:<>
   [provenance-legend]
   [pid-table]
   [signal-table]
   [did-table]])

(defn- vehicle-key
  "What groups a vehicle's recordings. The calibration ID comes first:
  recordings made before the VIN was saved have only that."
  [{:keys [vin calibration-ids description]}]
  (or (first calibration-ids) vin description))

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
                ;; The newest identity, filled in from any of the others.
                [vehicle-heading (reduce (fn [vehicle other] (merge other vehicle))
                                         (map :vehicle entries))]
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

(def ^:private view-names
  {:dashboard "Dashboard"
   :signals "Signals"
   :bus "Bus"
   :map "Map"
   :recordings "Recordings"})

(defn- view-toggle [view]
  [:nav.view-toggle
   (for [key events/views]
     ^{:key key}
     [:button {:class (when (= key view) "active")
               :aria-pressed (= key view)
               :on-click #(rf/dispatch [::events/show-view key])}
      (get view-names key)])])

(defn app []
  (let [view @(rf/subscribe [::subs/view])
        catalog @(rf/subscribe [::subs/catalog])]
    [:main
     [:header
      [:div.title
       [:h1 "Engine"]
       [view-toggle view]]
      [connection-badge]]
     [source-bar]
     (case view
       :map [map-view/map-view]
       :recordings [recordings-block]
       :signals [signals-view]
       :bus [bus-view/bus-view]
       (if catalog
         [:<>
          [readouts]
          [did-block]]
         [:p.muted "Loading…"]))]))
