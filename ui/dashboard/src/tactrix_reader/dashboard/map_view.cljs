(ns tactrix-reader.dashboard.map-view
  "The map: one value binned by two others, such as timing advance by RPM and
  load, like an ECU table, for a recording or the stream. A timeline picks the
  window the map covers and shows when the car was in the selected cell."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [tactrix-reader.dashboard.events :as events]
            [tactrix-reader.dashboard.format :refer [format-value label unit-labels]]
            [tactrix-reader.dashboard.series :as series]
            [tactrix-reader.dashboard.subs :as subs]))

(def ^:private leaf-labels
  "Labels for the parts of values that decode to a map."
  {"lambda" "λ"
   "milliamps" "current"
   "volts" "voltage"
   "fuel-trim-percent" "trim"
   "manual-transmission-in-gear?" "in gear"})

(defn- channel-label [key {:keys [name]}]
  (let [[base leaf] (str/split (cljs.core/name key) #"\." 2)]
    (case (namespace key)
      "derived" "Load (MAF ÷ RPM)"
      "did" (str "DID " (subs base 1) (when name (str " · " (label name))))
      (cond-> (label (keyword base))
        leaf (str " · " (get leaf-labels leaf (str/replace leaf "-" " ")))))))

(def ^:private channel-groups
  [["Worked out" "derived"]
   ["PIDs" "pid"]
   ["CAN signals (inferred)" "signal"]
   ["UDS DIDs" "did"]])

(defn- key->value [key]
  (subs (str key) 1))

(defn- channel-select [channels value on-change]
  [:select {:value (key->value value)
            :on-change #(on-change (keyword (.. % -target -value)))}
   (when-not (contains? channels value)
     [:option {:value (key->value value)} (str (channel-label value nil) " (not in this log)")])
   (for [[group-name group-ns] channel-groups
         :let [entries (->> channels
                            (filter #(= group-ns (namespace (key %))))
                            (map (fn [[key channel]] [key (channel-label key channel)]))
                            (sort-by second))]
         :when (seq entries)]
     ^{:key group-ns}
     [:optgroup {:label group-name}
      (for [[key text] entries]
        ^{:key key}
        [:option {:value (key->value key)} text])])])

(defn- unit-of [channels key]
  (get-in channels [key :unit]))

(defn- format-number [value unit]
  (if (number? value)
    (format-value value unit)
    ""))

(defn- format-breakpoint [value]
  (str (js/parseFloat (.toFixed value 3))))

(defn- format-clock [ms]
  (let [seconds (js/Math.floor (/ ms 1000))]
    (str (quot seconds 60) ":" (.padStart (str (mod seconds 60)) 2 "0"))))

(defn- with-unit [text unit]
  (if-let [unit-label (get unit-labels unit)]
    (str text " " unit-label)
    text))

;; Colors. Sequential is one hue, light to dark (dark to light on a dark
;; page); diverging is blue to red through gray, with red for lean or more
;; fuel added.

(def ^:private sequential-light ["#cde2fb" "#86b6ef" "#3987e5" "#1c5cab" "#0d366b"])
(def ^:private sequential-dark ["#184f95" "#256abf" "#3987e5" "#86b6ef" "#cde2fb"])
(def ^:private diverging-light ["#104281" "#3987e5" "#9ec5f4" "#f0efec" "#f1a8a7" "#e34948" "#9e2b2b"])
(def ^:private diverging-dark ["#9ec5f4" "#3987e5" "#1c3f6e" "#383835" "#6e2a2a" "#d03b3b" "#f1a8a7"])

(defn- dark-page? []
  (.-matches (js/window.matchMedia "(prefers-color-scheme: dark)")))

(defn- hex->rgb [hex]
  (mapv #(js/parseInt (subs hex % (+ % 2)) 16) [1 3 5]))

(defn- ramp-color
  "The color `t` (0 to 1) of the way along `stops`, as [r g b]."
  [stops t]
  (let [t (-> t (max 0) (min 1))
        position (* t (dec (count stops)))
        i (min (js/Math.floor position) (- (count stops) 2))
        f (- position i)]
    (mapv #(js/Math.round (+ %1 (* f (- %2 %1))))
          (hex->rgb (nth stops i))
          (hex->rgb (nth stops (inc i))))))

(defn- ink
  "Dark or light text, whichever reads on `rgb`."
  [[red green blue]]
  (let [luminance (/ (+ (* 0.2126 red) (* 0.7152 green) (* 0.0722 blue)) 255)]
    (if (> luminance 0.5) "#0b0b0b" "#ffffff")))

(defn- diverging-center
  "The value a parameter is colored around, or nil to color it from its lowest
  to highest: 0 for trims, signed motion, and torque, and 1 for lambda."
  [key unit]
  (let [key-name (name key)]
    (cond
      (re-find #"fuel-trim|acceleration|steering|yaw|torque" key-name) 0
      (= :lambda unit) 1)))

(defn- color-scale
  "A function from a cell's value to its [r g b], for values from `low` to
  `high`."
  [low high center]
  (let [dark? (dark-page?)]
    (if center
      (let [reach (max (js/Math.abs (- low center)) (js/Math.abs (- high center)) 1e-9)]
        (fn [value]
          (ramp-color (if dark? diverging-dark diverging-light)
                      (+ 0.5 (/ (- value center) reach 2)))))
      (fn [value]
        (ramp-color (if dark? sequential-dark sequential-light)
                    (if (= low high) 0.5 (/ (- value low) (- high low))))))))

(defn- rgb [[red green blue]]
  (str "rgb(" red "," green "," blue ")"))

(defn- gradient [low high center]
  (let [scale (color-scale low high center)]
    (str "linear-gradient(to right, "
         (str/join ", " (map #(rgb (scale (+ low (* % (- high low))))) [0 0.25 0.5 0.75 1]))
         ")")))

(defn- log-picker [{:keys [log]}]
  (let [recordings @(rf/subscribe [::subs/recordings])
        {:keys [source recording]} @(rf/subscribe [::subs/info])
        stream-label (case source
                       :live "Live adapter"
                       :replay (str "Replay of " (:name recording))
                       "Current stream")]
    [:select {:value (if (= :stream log) "" log)
              :on-change #(let [value (.. % -target -value)]
                            (rf/dispatch [::events/open-map (if (= "" value) :stream value)]))}
     [:option {:value ""} (str stream-label ", as it arrives")]
     (when (and (string? log) (not-any? #(= log (:name %)) recordings))
       [:option {:value log} log])
     (for [{:keys [name note]} recordings]
       ^{:key name}
       [:option {:value name} (str "Recording " name (when (seq note) (str " · " note)))])]))

(defn- axis-input [key axis custom-text]
  (let [text (or custom-text (series/format-axis axis))]
    [:span.axis-input
     [:input {:type "text"
              :value text
              :class (when (and custom-text (nil? (series/parse-axis custom-text))) "invalid")
              :aria-label "Breakpoints"
              :title "Breakpoints, separated by commas"
              :on-change #(rf/dispatch [::events/set-axis-text key (.. % -target -value)])}]
     (when custom-text
       [:button {:on-click #(rf/dispatch [::events/reset-axis key])} "Reset"])]))

(defn- map-controls [{:keys [x y z stat min-hits axis-text] :as settings} channels x-axis y-axis]
  [:section.controls.map-controls
   [:div.control-row
    [:span.control-label "Log"]
    [log-picker settings]]
   [:div.control-row
    [:span.control-label "Across"]
    [channel-select channels x #(rf/dispatch [::events/set-map-option :x %])]
    [axis-input x x-axis (get axis-text x)]]
   [:div.control-row
    [:span.control-label "Down"]
    [channel-select channels y #(rf/dispatch [::events/set-map-option :y %])]
    [axis-input y y-axis (get axis-text y)]]
   [:div.control-row
    [:span.control-label "Show"]
    [channel-select channels z #(rf/dispatch [::events/set-map-option :z %])]
    [:select.stat {:value (name stat)
                   :aria-label "Statistic"
                   :on-change #(rf/dispatch [::events/set-map-option :stat (keyword (.. % -target -value))])}
     [:option {:value "mean"} "Mean"]
     [:option {:value "min"} "Lowest"]
     [:option {:value "max"} "Highest"]
     [:option {:value "count"} "Rows"]]
    [:label.check "Min rows"
     [:input.number {:type "number"
                     :min 1
                     :value min-hits
                     :on-change #(rf/dispatch [::events/set-map-option :min-hits
                                               (max 1 (or (js/parseInt (.. % -target -value)) 1))])}]]]])

(defn- filter-rows [filters channels]
  [:section.controls.map-filters
   (for [[i {:keys [key op value]}] (map-indexed vector filters)]
     ^{:key i}
     [:div.control-row
      [:span.control-label (if (zero? i) "Only where" "and")]
      [channel-select channels key #(rf/dispatch [::events/set-filter i :key %])]
      [:select.op {:value (name op)
                   :aria-label "Comparison"
                   :on-change #(rf/dispatch [::events/set-filter i :op (keyword (.. % -target -value))])}
       [:option {:value ">="} "≥"]
       [:option {:value "<="} "≤"]]
      [:input.number {:type "number"
                      :step "any"
                      :value (if (number? value) value "")
                      :aria-label "Value"
                      :on-change #(let [text (.. % -target -value)]
                                    (rf/dispatch [::events/set-filter i :value
                                                  (when-not (str/blank? text) (js/Number text))]))}]
      [:span.muted (get unit-labels (unit-of channels key) "")]
      [:button {:on-click #(rf/dispatch [::events/remove-filter i])} "Remove"]])
   [:div.control-row
    [:button {:on-click #(rf/dispatch [::events/add-filter])} "Add filter"]
    (when (empty? filters)
      [:span.muted "For example coolant ≥ 75 °C, or measured λ ≤ 1.2 to leave out fuel cut."])]])

(def ^:private timeline-width 1000)
(def ^:private timeline-height 120)
(def ^:private mark-height 10)

(defn- pointer-fraction [^js e]
  (let [rect (.getBoundingClientRect (.-currentTarget e))]
    (-> (/ (- (.-clientX e) (.-left rect)) (.-width rect))
        (max 0)
        (min 1))))

(defn- timeline-chart
  "The timeline channel over the whole log, drawn as its range in each pixel
  column. Dragging picks the window the map covers; the selected cell's rows
  are marked along the bottom."
  []
  (let [drag (r/atom nil)
        hover (r/atom nil)]
    (fn [{:keys [key channels rows window marked]}]
      (let [envelope @(rf/subscribe [::subs/map-envelope key timeline-width])
            values @(rf/subscribe [::subs/map-column key])
            unit (unit-of channels key)
            duration (* rows series/bucket-ms)
            present (remove nil? envelope)
            low (if (seq present) (apply min (map first present)) 0)
            high (if (seq present) (apply max (map second present)) 1)
            plot-height (- timeline-height mark-height 4)
            y-of #(- plot-height 2 (* (- plot-height 4) (if (= low high) 0.5 (/ (- % low) (- high low)))))
            x-of-ms #(* timeline-width (/ % (max duration 1)))
            ms-of #(* % duration)
            shown-window (if-let [{:keys [from to]} @drag]
                           [(ms-of (min from to)) (ms-of (max from to))]
                           window)
            hover-row (when-let [f @hover] (min (dec rows) (js/Math.floor (* f rows))))]
        [:div.timeline
         [:div.timeline-head
          [:span (with-unit (channel-label key (get channels key)) unit)
           (when (seq present)
             [:span.muted (str ", " (format-number low unit) " to " (format-number high unit))])]
          [:span.timeline-readout
           (when hover-row
             (str (format-clock (series/row-time hover-row)) " · "
                  (let [value (nth values hover-row nil)]
                    (if (some? value) (with-unit (format-number value unit) unit) "no value"))))]]
         [:svg {:viewBox (str "0 0 " timeline-width " " timeline-height)
                :preserveAspectRatio "none"
                :role "img"
                :aria-label "Timeline. Drag to choose the part of the log the map covers."
                :on-pointer-down (fn [^js e]
                                   (.setPointerCapture (.-currentTarget e) (.-pointerId e))
                                   (let [f (pointer-fraction e)]
                                     (reset! drag {:from f :to f})))
                :on-pointer-move (fn [e]
                                   (let [f (pointer-fraction e)]
                                     (reset! hover f)
                                     (when @drag (swap! drag assoc :to f))))
                :on-pointer-up (fn [_]
                                 (when-let [{:keys [from to]} @drag]
                                   (reset! drag nil)
                                   ;; A click without a drag leaves the window as it was.
                                   (when (> (js/Math.abs (- to from)) 0.004)
                                     (rf/dispatch [::events/set-window [(ms-of (min from to))
                                                                        (ms-of (max from to))]]))))
                :on-pointer-leave #(reset! hover nil)}
          (when-let [[from to] shown-window]
            [:rect.window {:x (x-of-ms from) :y 0
                           :width (max 1 (- (x-of-ms to) (x-of-ms from)))
                           :height timeline-height}])
          [:path.trace {:d (str/join " " (keep-indexed
                                          (fn [i [lo hi]]
                                            (when lo
                                              (str "M" (+ i 0.5) " " (- (y-of hi) 1)
                                                   "V" (+ (y-of lo) 1))))
                                          envelope))}]
          (when (seq marked)
            [:path.marks {:d (str/join " " (for [column (distinct (map #(js/Math.floor (* timeline-width (/ % rows))) marked))]
                                             (str "M" (+ column 0.5) " " (- timeline-height mark-height)
                                                  "V" timeline-height)))}])
          (when hover-row
            [:line.crosshair {:x1 (x-of-ms (series/row-time hover-row)) :x2 (x-of-ms (series/row-time hover-row))
                              :y1 0 :y2 timeline-height}])]
         [:div.timeline-foot
          [:span "0:00"]
          (if-let [[from to] window]
            [:span (str "Map covers " (format-clock from) " to " (format-clock to) " ")
             [:button {:on-click #(rf/dispatch [::events/set-window nil])} "Use the whole log"]]
            [:span.muted "Drag across the timeline to map part of the log."])
          [:span (format-clock duration)]]]))))

(defn- heat-table [{:keys [x y z stat min-hits cell]} channels x-axis y-axis cells]
  (let [x-unit (unit-of channels x)
        y-unit (unit-of channels y)
        z-unit (unit-of channels z)
        value-unit (when (not= :count stat) z-unit)
        shown (keep (fn [[_ c]] (when (>= (:count c) min-hits) (series/cell-stat stat c))) cells)
        low (when (seq shown) (apply min shown))
        high (when (seq shown) (apply max shown))
        center (when (not= :count stat) (diverging-center z z-unit))
        scale (when low (color-scale low high center))
        z-label (channel-label z (get channels z))]
    [:div.heat-block
     [:div.heat-scroll
      [:table.heat
       [:thead
        [:tr
         [:th.corner (str (with-unit (channel-label y (get channels y)) y-unit) " ↓  "
                          (with-unit (channel-label x (get channels x)) x-unit) " →")]
         (for [[i breakpoint] (map-indexed vector x-axis)]
           ^{:key i} [:th (format-breakpoint breakpoint)])]]
       [:tbody
        (for [[yi y-break] (map-indexed vector y-axis)]
          ^{:key yi}
          [:tr
           [:th (format-breakpoint y-break)]
           (for [[xi x-break] (map-indexed vector x-axis)
                 :let [{hits :count :as c} (get cells [xi yi])
                       value (when c (series/cell-stat stat c))
                       sparse? (and c (< hits min-hits))
                       color (when (and c (not sparse?)) (scale value))]]
             ^{:key xi}
             [:td {:class [(when (= cell [xi yi]) "selected") (when sparse? "sparse") (when-not c "empty")]
                   :style (when color {:background (rgb color) :color (ink color)})
                   :title (when c
                            (str (format-breakpoint x-break) " × " (format-breakpoint y-break) "\n"
                                 z-label ": mean " (with-unit (format-number (series/cell-stat :mean c) z-unit) z-unit)
                                 ", " (format-number (:min c) z-unit) " to " (format-number (:max c) z-unit) "\n"
                                 hits " rows, " (.toFixed (/ (* hits series/bucket-ms) 1000) 1) " s"))
                   :on-click (when c #(rf/dispatch [::events/select-cell [xi yi]]))}
              (when c
                [:<>
                 [:span.cell-value (if (= :count stat) (str value) (format-number value value-unit))]
                 [:span.cell-hits hits]])])])]]]
     (when low
       [:div.heat-legend
        [:span (with-unit (format-number low value-unit) value-unit)]
        [:span.legend-bar {:style {:background (gradient low high center)}}]
        [:span (with-unit (format-number high value-unit) value-unit)]
        [:span.muted (str z-label ", " ({:mean "mean" :min "lowest" :max "highest" :count "rows"} stat)
                          " in each cell. Small numbers are rows of " series/bucket-ms " ms.")]])]))

(defn- cell-detail [{:keys [x y z cell]} channels x-axis y-axis cells]
  (when-let [{hits :count :keys [x-sum y-sum] :as c} (get cells cell)]
    (let [[xi yi] cell
          x-unit (unit-of channels x)
          y-unit (unit-of channels y)
          z-unit (unit-of channels z)
          field (fn [title value] [:<> [:dt title] [:dd value]])]
      [:section.panel.cell-detail
       [:h2 (str (with-unit (format-breakpoint (nth x-axis xi)) x-unit) " × "
                 (with-unit (format-breakpoint (nth y-axis yi)) y-unit))]
       [:dl
        (field "Rows" (str hits ", " (.toFixed (/ (* hits series/bucket-ms) 1000) 1) " s"))
       (field (str (channel-label z (get channels z)) ", mean")
              (with-unit (format-number (series/cell-stat :mean c) z-unit) z-unit))
       (field "Lowest to highest" (str (format-number (:min c) z-unit) " to "
                                       (with-unit (format-number (:max c) z-unit) z-unit)))
       (field (str (channel-label x (get channels x)) ", mean")
              (with-unit (format-number (/ x-sum hits) x-unit) x-unit))
       (field (str (channel-label y (get channels y)) ", mean")
              (with-unit (format-number (/ y-sum hits) y-unit) y-unit))]
       [:p.muted "Its rows are marked along the bottom of the timeline."]])))

(defn- log-status [{:keys [log]} rows]
  (let [{:keys [state error]} @(rf/subscribe [::subs/recording-log])]
    (cond
      (and (string? log) (= :loading state)) [:p.muted "Decoding the recording…"]
      (and (string? log) (= :failed state)) [:p.error (str "The recording could not be read: "
                                                           (str/replace (name error) "-" " ") ".")]
      (and (= :stream log) (not (pos? rows))) [:p.muted "Waiting for samples. Connect or replay a recording."])))

(defn map-view []
  (let [{:keys [x y z timeline filters window cell] :as settings} @(rf/subscribe [::subs/map-settings])
        channels (or @(rf/subscribe [::subs/map-channels]) {})
        rows (or @(rf/subscribe [::subs/map-row-count]) 0)
        x-axis @(rf/subscribe [::subs/map-axis x])
        y-axis @(rf/subscribe [::subs/map-axis y])
        cells (when (pos? rows)
                @(rf/subscribe [::subs/map-cells x y z [x-axis y-axis] filters window]))
        in-table (reduce + 0 (map :count (vals cells)))]
    [:<>
     [map-controls settings channels x-axis y-axis]
     [filter-rows filters channels]
     (or (log-status settings rows)
         [:<>
          [:section.block
           [:div.block-head
            [:h2 "Timeline"]
            [channel-select channels timeline #(rf/dispatch [::events/set-map-option :timeline %])]]
           [timeline-chart {:key timeline
                      :channels channels
                      :rows rows
                      :window window
                      :marked (:rows (get cells cell))}]]
          [:section.block
           [:div.block-head
            [:h2 "Map"]
            [:span.muted (str in-table " of " rows " rows fall in the table. Click a cell for its details.")]]
           (cond
             (some #(not (contains? channels %)) [x y z])
             [:p.muted (str "This log has no "
                            (str/join " or " (map #(channel-label % nil) (remove #(contains? channels %) [x y z])))
                            ".")]

             (or (empty? x-axis) (empty? y-axis))
             [:p.muted "Type breakpoints for both axes."]

             :else
             [:div.map-body
              [:div.map-grid [heat-table settings channels x-axis y-axis cells]]
              [cell-detail settings channels x-axis y-axis cells]])]])]))
