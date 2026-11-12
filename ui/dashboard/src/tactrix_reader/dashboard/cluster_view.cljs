(ns tactrix-reader.dashboard.cluster-view
  "The Virtual Cluster tab: the latest values drawn as the car's instrument
  cluster shows them, with a tachometer, a speedometer, fuel and coolant
  gauges, the gear, and the warning lamps. Unlike the dashboard's tiles,
  which come from the catalog, the cluster is a fixed set of values, named
  in `cluster/gauges` and here."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [tactrix-reader.dashboard.cluster :as cluster]
            [tactrix-reader.dashboard.events :as events]
            [tactrix-reader.dashboard.format :refer [tile-text tile-unit]]
            [tactrix-reader.dashboard.subs :as subs]))

(defn- rotate [angle]
  (str "rotate(" angle "deg)"))

(defn- line-between
  "A line along the direction `angle` from the radius `from` to `to`."
  [centre from to angle attrs]
  (let [[x1 y1] (cluster/point centre from angle)
        [x2 y2] (cluster/point centre to angle)]
    [:line (merge {:x1 x1 :y1 y1 :x2 x2 :y2 y2} attrs)]))

(defn- tick-class [{:keys [major? red? low?]}]
  (str/join " " (remove nil? ["tick" (when major? "major") (when red? "red") (when low? "low")])))

(defn- zone-arc
  "The gauge's red or amber zone, as a band at `radius`."
  [gauge centre radius]
  (when-let [[from to] (cluster/zone gauge)]
    [:path.zone {:class (if (:red-from gauge) "red" "low")
                 :d (cluster/arc centre radius from to)}]))

(def ^:private dial-centre [120 120])

(defn- dial-face
  "What does not move: the ring, the marks, and the numbers. It takes no
  value, so it is drawn once."
  [{:keys [caption] :as gauge}]
  (let [ticks (cluster/ticks gauge)]
    [:g
     [:circle.bezel {:cx 120 :cy 120 :r 118}]
     [:circle.face {:cx 120 :cy 120 :r 114}]
     [zone-arc gauge dial-centre 104.5]
     (into [:g]
           (for [{:keys [angle major?] :as tick} ticks]
             (line-between dial-centre 107 (if major? 93 100) angle {:class (tick-class tick)})))
     (into [:g]
           (for [{:keys [angle label red?]} ticks
                 :when label
                 :let [[x y] (cluster/point dial-centre 78 angle)]]
             [:text.number {:x x :y y :class (when red? "red")} label]))
     (when caption
       [:text.caption {:x 120 :y 86} caption])]))

(defn- needle
  "A needle about `centre`, `length` long with a `tail` behind the pivot. It
  is drawn pointing straight up and turned with CSS, so it moves smoothly
  between the stream's batches."
  [[cx cy] length tail half-width angle live?]
  [:polygon.needle
   {:class (when-not live? "resting")
    :points (str/join " " [(str (- cx half-width) "," (+ cy tail))
                           (str (+ cx half-width) "," (+ cy tail))
                           (str (+ cx 0.8) "," (- cy length))
                           (str (- cx 0.8) "," (- cy length))])
    :style {:transform-origin (str cx "px " cy "px")
            :transform (rotate angle)}}])

(defn- dial-hand
  "What moves: the needle, and the value in figures under it."
  [gauge unit-label]
  (let [{:keys [value]} @(rf/subscribe [::subs/sample (:name gauge)])
        value (cluster/gauge-value gauge value)]
    [:g
     [:text.figure {:x 120 :y 201} (if value (str (js/Math.round value)) "—")]
     [:text.unit {:x 120 :y 215} unit-label]
     [needle dial-centre 99 20 3 (cluster/angle gauge value) (some? value)]
     [:circle.hub {:cx 120 :cy 120 :r 11}]
     [:circle.hub-cap {:cx 120 :cy 120 :r 4}]]))

(defn- dial
  "A large round gauge."
  [id label unit-label]
  (let [gauge (get cluster/gauges id)]
    [:svg.dial {:viewBox "0 0 240 240" :role "img" :aria-label label}
     [dial-face gauge]
     [dial-hand gauge unit-label]]))

(def ^:private small-centre [60 60])

(defn- small-face [gauge]
  (let [ticks (cluster/ticks gauge)
        {:keys [from to]} gauge]
    [:g
     [:path.track {:d (cluster/arc small-centre 44 from to)}]
     [zone-arc gauge small-centre 42.5]
     (into [:g]
           (for [{:keys [angle major?] :as tick} ticks]
             (line-between small-centre 44 (if major? 35 39) angle {:class (tick-class tick)})))
     ;; The letters stand outside the ends of the scale, clear of the needle.
     (into [:g]
           (for [{:keys [angle label red? low?]} ticks
                 :when label
                 :let [[x y] (cluster/point small-centre 54 angle)]]
             [:text.number {:x x :y y :class (cond red? "red" low? "low")} label]))]))

(defn- small-hand [gauge]
  (let [{:keys [value]} @(rf/subscribe [::subs/sample (:name gauge)])]
    [:g
     [needle small-centre 41 7 2 (cluster/angle gauge value) (some? value)]
     [:circle.hub {:cx 60 :cy 60 :r 5.5}]]))

(defn- small-gauge
  "A small gauge with letters for a scale, over its value in figures."
  [id]
  (let [gauge (get cluster/gauges id)
        tile @(rf/subscribe [::subs/tile :pid (:name gauge)])
        {:keys [value unit]} @(rf/subscribe [::subs/tile-value tile])]
    [:div.cluster-small
     [:svg.small-dial {:viewBox "0 0 120 70" :role "img" :aria-label (:label tile)}
      [small-face gauge]
      [small-hand gauge]]
     [:div.cluster-small-value
      (if (some? value) (str (js/Math.round value)) "—")
      [:span.cluster-unit (tile-unit tile unit)]]
     [:div.cluster-label (:label tile)]]))

(defn- gear
  "The gear, as its tile shows it: N in neutral and R in reverse."
  []
  (let [tile @(rf/subscribe [::subs/tile :signal :gear])
        {:keys [value unit]} @(rf/subscribe [::subs/tile-value tile])]
    [:div.cluster-gear
     [:div.cluster-gear-value
      (or @(rf/subscribe [::subs/tile-override tile])
          (tile-text tile value unit))]
     [:div.cluster-label (:label tile)]]))

(defn- info
  "One line of the display between the dials."
  [kind value-name]
  (let [tile @(rf/subscribe [::subs/tile kind value-name])
        {:keys [value unit]} @(rf/subscribe [::subs/tile-value tile])]
    [:div.cluster-info
     [:span.cluster-label (:label tile)]
     [:span.cluster-info-value
      (tile-text tile value unit)
      [:span.cluster-unit (tile-unit tile unit)]]]))

(def ^:private lamp-icons
  "Each lamp's symbol, drawn in a 24 by 24 box."
  {:check-engine
   [:<>
    [:path {:d "M8 6h5.5M10.75 6v2.5M2.5 11v5M2.5 13.5H5"}]
    [:path {:d "M5 10.5v6h2.2l1.8 2h6.5V16h2v1.5h2v-7h-2V12h-2V9.5l-1-1H7z"}]]
   :parking-brake
   [:<>
    [:circle {:cx 12 :cy 12 :r 5.6}]
    [:path {:d "M10.6 15V9.2h1.7a1.75 1.75 0 0 1 0 3.5h-1.7"}]
    [:path {:d "M4.6 5.5a9.6 9.6 0 0 0 0 13M19.4 5.5a9.6 9.6 0 0 1 0 13"}]]
   :seatbelt
   [:<>
    [:circle {:cx 12 :cy 5 :r 2.6}]
    [:path {:d "M5.5 21.5c0-5.8 2.5-9.5 6.5-9.5s6.5 3.7 6.5 9.5"}]
    [:path {:d "M7.6 13.4l9.6 8.1"}]]
   :door
   [:<>
    [:rect {:x 8 :y 2.5 :width 8 :height 19 :rx 3}]
    [:path {:d "M10 7.5h4M10 17.5h4"}]
    [:path {:d "M8 10l-5 3.6M16 10l5 3.6"}]]
   :parking-lights
   [:<>
    [:path {:d "M11 8c-2.6 0-4 1.8-4 4s1.4 4 4 4zM13 8c2.6 0 4 1.8 4 4s-1.4 4-4 4z"}]
    [:path {:d "M4.8 9.2L2 8M4.4 12H1.5M4.8 14.8L2 16M19.2 9.2L22 8M19.6 12h2.9M19.2 14.8L22 16"}]]
   :headlights
   [:<>
    [:path {:d "M13 6.5c4 0 6.5 2.5 6.5 5.5s-2.5 5.5-6.5 5.5z"}]
    [:path {:d "M10 7.6l-6.5 2M10 11.2l-6.5 2M10 14.8l-6.5 2"}]]})

(def ^:private lamp-states
  {:on "on" :off "off" :flashing "flashing" :unknown "no data"})

(defn- lamps
  "The warning lamps, lit in their colours and dim while they are off."
  []
  (into [:div.cluster-lamps]
        (for [{:keys [id label color state]} @(rf/subscribe [::subs/lamps])
              :let [text (str label ": " (get lamp-states state))]]
          ^{:key id}
          [:span.lamp {:class [(name color) (name state)] :role "img" :title text :aria-label text}
           [:svg {:viewBox "0 0 24 24" :aria-hidden true}
            (get lamp-icons id)]])))

(def ^:private speed-unit-names
  [[:km-per-hour "km/h"] [:miles-per-hour "mph"]])

(defn- speed-unit-toggle [speed-unit]
  [:div.cluster-units {:role "group" :aria-label "Speedometer units"}
   (for [[unit text] speed-unit-names]
     ^{:key unit}
     [:button {:class (when (= unit speed-unit) "active")
               :aria-pressed (= unit speed-unit)
               :on-click #(rf/dispatch [::events/set-speed-unit unit])}
      text])])

(defn cluster-view []
  (let [speed-unit @(rf/subscribe [::subs/speed-unit])
        speed (get cluster/speed-units speed-unit)]
    [:section.cluster {:aria-label "Virtual cluster"}
     [:div.cluster-dials
      [:div.cluster-tach
       [dial :tach "Tachometer" "rpm"]]
      [:div.cluster-centre
       [gear]
       [:div.cluster-smalls
        [small-gauge :coolant]
        [small-gauge :fuel]]
       [:div.cluster-infos
        [info :signal :outside-temp]
        [info :pid :oil-temp]
        [info :signal :fuel-economy]]]
      [:div.cluster-speed
       ;; Keyed, so a change of units draws a new face.
       ^{:key speed}
       [dial speed "Speedometer" (:unit-label (get cluster/gauges speed))]]]
     [:div.cluster-foot
      [lamps]
      [speed-unit-toggle speed-unit]]]))
