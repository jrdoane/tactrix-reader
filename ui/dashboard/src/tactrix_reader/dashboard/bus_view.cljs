(ns tactrix-reader.dashboard.bus-view
  "The bus view: the latest frame of every CAN ID, with what changes
  highlighted, for working out what an unknown bit does. Mark a baseline,
  operate one control, and the bits that had not changed before stand out.
  The bits a value is decoded from wear that value's colour."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [tactrix-reader.dashboard.bus :as bus]
            [tactrix-reader.dashboard.events :as events]
            [tactrix-reader.dashboard.format :refer [tile-text tile-unit]]
            [tactrix-reader.dashboard.subs :as subs]))

(def ^:private obd-ids
  "The IDs the polling itself uses."
  {0x7E0 "OBD requests"
   0x7E8 "OBD replies"})

(def ^:private field-colors
  "How many field colours the stylesheet has, `--field-0` and up. A frame
  with more fields shows the rest in grey."
  6)

(defn- field-class
  "The class that gives an element its field's colour, as `--field`."
  [{:keys [slot]}]
  (if (< slot field-colors) (str "field-" slot) "field-more"))

(defn- field-color [{:keys [slot]}]
  (if (< slot field-colors) (str "var(--field-" slot ")") "var(--field-more)"))

(defn- byte-underline
  "A bar under a byte with an eighth for each bit, most significant first,
  in the colour of the field the bit is decoded into."
  [runs]
  (let [stops (reductions + 0 (map second runs))]
    (str "linear-gradient(to right, "
         (str/join ", " (map (fn [[field _] start end]
                               (str (if field (field-color field) "transparent")
                                    " " (* 12.5 start) "% " (* 12.5 end) "%"))
                             runs stops (rest stops)))
         ")")))

(defn- field-names
  "The values decoded from an ID, each after the colours of its fields."
  [fields]
  (for [[label parts] (map (juxt (comp :of first) identity) (partition-by :of fields))]
    ^{:key label}
    [:span.field-name
     (for [field parts]
       ^{:key (:id field)} [:i.swatch {:class (field-class field)}])
     label]))

(defn- hex [n width]
  (.padStart (.toUpperCase (.toString n 16)) width "0"))

(defn- format-period [ms]
  (cond
    (nil? ms) "—"
    (< ms 100) (str (.toFixed ms 0) " ms")
    :else (str (.toFixed (/ ms 1000) 1) " s")))

(defn- has-new? [frame]
  (some #(= :new (bus/byte-state frame %)) (range (count (:data frame)))))

(defn- toolbar [{:keys [baseline? new-only?]}]
  [:div.control-row.bus-controls
   [:button.primary {:on-click #(rf/dispatch [::events/mark-baseline])}
    (if baseline? "Mark baseline again" "Mark baseline")]
   (when baseline?
     [:button {:on-click #(rf/dispatch [::events/clear-baseline])} "Clear baseline"])
   [:label.check
    [:input {:type "checkbox"
             :checked new-only?
             :disabled (not baseline?)
             :on-change #(rf/dispatch [::events/set-bus-option :new-only? (.. % -target -checked)])}]
    "Only IDs with new bits"]
   [:span.muted
    (if baseline?
      "Operate one control. Bits that had not changed before the baseline are highlighted."
      "Let the bus run for a few seconds, then mark a baseline before operating a control.")]])

(defn- new-bits-line [frames]
  (let [bits (bus/new-bits frames)]
    [:p.new-bits
     [:span.muted "New since the baseline:"]
     (if (empty? bits)
       [:span.muted "nothing yet"]
       (for [{:keys [id bit value flips] byte-index :byte} bits]
         ^{:key [id byte-index bit]}
         [:button.chip {:title (str "Now " value "; changed in " flips (if (= 1 flips) " batch" " batches"))
                        :on-click #(rf/dispatch [::events/set-bus-option :selected id])}
          (str (hex id 3) " " (bus/bit-name byte-index bit))]))]))

(defn- frame-table [frames selected fields]
  [:table.samples.compact.bus
   [:thead
    [:tr [:th "ID"] [:th.value "Every"] [:th.value "Frames"] [:th "Data"] [:th "Decoded from it"]]]
   [:tbody
    (for [{:keys [id data] frame-count :count :as frame} frames
          :let [id-fields (get fields id)]]
      ^{:key id}
      [:tr {:class (when (= id selected) "playing")
            :on-click #(rf/dispatch [::events/select-frame id])}
       [:td.pid (hex id 3)]
       [:td.value (format-period (bus/period-ms frame))]
       [:td.value frame-count]
       [:td.bytes
        (map-indexed (fn [i value]
                       (let [runs (bus/byte-fields id-fields i)]
                         ;; Keyed by value, so a byte that changes is drawn
                         ;; again and its highlight fades again.
                         ^{:key (str i "-" value)}
                         [:span.byte
                          {:class (name (bus/byte-state frame i))
                           :style (when runs {:background-image (byte-underline runs)})
                           :title (when runs (str/join ", " (distinct (keep (comp :label first) runs))))}
                          (hex value 2)]))
                     data)]
       [:td.note (or (get obd-ids id) (field-names id-fields))]])]])

(defn- field-value
  "A field's latest value, as a tile shows it."
  [field]
  (let [{:keys [value unit]} @(rf/subscribe [::subs/tile-value field])
        label (tile-unit field unit)]
    (str (or @(rf/subscribe [::subs/tile-override field])
             (tile-text field value unit))
         (when (and (some? value) (seq label)) (str " " label)))))

(defn- field-legend
  "What each colour in the bit table is decoded into, where, and its value
  now."
  [fields]
  [:table.fields
   [:tbody
    (for [{:keys [id label bits] :as field} fields]
      ^{:key id}
      [:tr
       [:td [:i.swatch {:class (field-class field)}] label]
       [:td.where (bus/bits-text bits)]
       [:td.value [field-value field]]])]])

(defn- frame-detail
  "Every bit of the selected ID, most significant first, as the docs number
  them, coloured by what it is decoded into."
  [{:keys [id data flips] :as frame} fields]
  [:section.panel.bit-detail
   [:h3 (str "ID " (hex id 3) " · " (count data) " bytes · every " (format-period (bus/period-ms frame)))]
   [:table.bits
    [:thead
     [:tr [:th] [:th] (for [bit (range 7 -1 -1)] ^{:key bit} [:th bit])]]
    [:tbody
     (map-indexed
      (fn [i value]
        ^{:key i}
        [:tr
         [:th (str "b" i)]
         [:td.pid (hex value 2)]
         (for [bit (range 7 -1 -1)
               :let [changes (nth flips (+ (* 8 i) bit) 0)
                     field (bus/field-at fields i bit)]]
           ^{:key bit}
           [:td.bit {:class [(name (bus/bit-state frame i bit))
                             (when (bit-test value bit) "on")
                             (when field "field")
                             (when field (field-class field))]
                     :title (str (bus/bit-name i bit)
                                 (when field (str " · " (:label field)))
                                 (when (pos? changes)
                                   (str " · changed in " changes (if (= 1 changes) " batch" " batches"))))}
            (if (bit-test value bit) "1" "0")])])
      data)]]
   (cond
     (seq fields) [field-legend fields]
     (get obd-ids id) [:p.muted (str (get obd-ids id) ".")]
     :else [:p.muted "Nothing is decoded from this ID."])
   [:p.muted (str (when (seq fields) "Coloured bits are decoded into the value of that colour. ")
                  "Outlined bits have changed. Filled orange bits are new since the baseline.")]])

(defn bus-view []
  (let [{:keys [frames baseline? new-only? selected] :as bus} @(rf/subscribe [::subs/bus])
        fields @(rf/subscribe [::subs/can-fields])
        listed (cond->> (sort-by :id (vals frames))
                 (and baseline? new-only?) (filter has-new?))]
    [:section.block
     [:div.block-head
      [:h2 "CAN bus"]
      [:span.muted (str (count frames) " IDs · "
                        (.toFixed (bus/frames-per-second frames) 0) " frames a second")]]
     [toolbar bus]
     (when baseline? [new-bits-line frames])
     (cond
       (empty? frames) [:p.muted "No frames yet. Connect or replay a recording."]
       (empty? listed) [:p.muted "No ID has a new bit yet."]
       :else
       [:div.bus-body
        [frame-table listed selected fields]
        (when-let [frame (get frames selected)]
          [frame-detail frame (get fields selected)])])]))
