(ns tactrix-reader.dashboard.cluster-view
  "The Virtual Cluster tab: the latest values drawn as the car's instrument
  cluster shows them, with a tachometer, a speedometer, fuel and coolant
  gauges, the gear, and the warning lamps. It is drawn in one of
  `cluster/styles`: classic, with dials and needles; modern, with rings
  that fill around the value in figures and bars for the small gauges; or
  track, a racing display with the revs as a strip across the top, over the
  gear between the revs and the speed in figures. All show the same values. Unlike the dashboard's tiles, which come from the
  catalog, the cluster is a fixed set of values, named in `cluster/gauges`
  and here."
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

(def ^:private ring-radius
  "The middle of a ring's band."
  100)

(defn- ring-face
  "What does not move in the modern style's ring: the band the value fills,
  its red zone, and the marks and numbers."
  [{:keys [from to caption] :as gauge}]
  (let [ticks (cluster/ticks gauge)]
    [:g
     [:circle.ring-rim {:cx 120 :cy 120 :r 118}]
     [:path.ring-track {:d (cluster/arc dial-centre ring-radius from to)}]
     [zone-arc gauge dial-centre ring-radius]
     (into [:g]
           (for [{:keys [angle major?] :as tick} ticks
                 :when major?]
             (line-between dial-centre 109 114 angle {:class (tick-class tick)})))
     (into [:g]
           (for [{:keys [angle label red?]} ticks
                 :when label
                 :let [[x y] (cluster/point dial-centre 81 angle)]]
             [:text.number {:x x :y y :class (when red? "red")} label]))
     (when caption
       [:text.caption {:x 120 :y 207} caption])]))

(defn- ring-gaps
  "Cuts across the band at every mark, over the fill, so it reads as
  segments."
  [gauge]
  (into [:g]
        (for [{:keys [angle]} (cluster/ticks gauge)]
          (line-between dial-centre 93 107 angle {:class "ring-gap"}))))

(defn- ring-hand
  "What moves: the band filled as far as the value, a bright mark at its
  end, and the value in figures in the middle. The fill is one dash of the
  whole band, drawn back by its offset, which CSS moves smoothly."
  [{:keys [from to] :as gauge} unit-label]
  (let [{:keys [value]} @(rf/subscribe [::subs/sample (:name gauge)])
        value (cluster/gauge-value gauge value)
        alert (some-> (cluster/alert gauge value) name)]
    [:g
     [:path.ring-fill {:class alert
                       :d (cluster/arc dial-centre ring-radius from to)
                       :path-length 1
                       :style {:stroke-dashoffset (- 1 (cluster/fraction gauge value))}}]
     [ring-gaps gauge]
     [:line.ring-cursor {:class [alert (when-not value "resting")]
                         :x1 120 :y1 5 :x2 120 :y2 28
                         :style {:transform-origin "120px 120px"
                                 :transform (rotate (cluster/angle gauge value))}}]
     [:text.figure {:class alert :x 120 :y 134} (if value (str (js/Math.round value)) "—")]
     [:text.unit {:x 120 :y 153} unit-label]]))

(defn- ring
  "A large round gauge in the modern style."
  [id label unit-label]
  (let [gauge (get cluster/gauges id)]
    [:svg.ring {:viewBox "0 0 240 240" :role "img" :aria-label label}
     [ring-face gauge]
     [ring-hand gauge unit-label]]))

(defn- shown
  "A tile and its latest value as the cluster shows them, in the chosen
  units: [tile value unit]."
  [tile]
  (let [{:keys [value unit]} @(rf/subscribe [::subs/tile-value tile])
        [value unit] (cluster/convert @(rf/subscribe [::subs/cluster-units])
                                      value
                                      (or (:unit tile) unit))]
    [(cond-> tile unit (assoc :unit unit)) value unit]))

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
  "A small gauge with letters for a scale, over its value in figures. The
  needle follows the PID's own value, and the figures the chosen units."
  [id]
  (let [gauge (get cluster/gauges id)
        [tile value unit] (shown @(rf/subscribe [::subs/tile :pid (:name gauge)]))]
    [:div.cluster-small
     [:svg.small-dial {:viewBox "0 0 120 70" :role "img" :aria-label (:label tile)}
      [small-face gauge]
      [small-hand gauge]]
     [:div.cluster-small-value
      (if (some? value) (str (js/Math.round value)) "—")
      [:span.cluster-unit (tile-unit tile unit)]]
     [:div.cluster-label (:label tile)]]))

(defn- bar-gauge
  "A small gauge in the modern style: a bar that fills along its scale,
  between the letters at its ends, under its value in figures. The bar
  follows the PID's own value, and the figures the chosen units."
  [id]
  (let [gauge (get cluster/gauges id)
        {raw :value} @(rf/subscribe [::subs/sample (:name gauge)])
        [tile value unit] (shown @(rf/subscribe [::subs/tile :pid (:name gauge)]))
        [bottom top] (map val (sort-by key (:marks gauge)))]
    [:div.cluster-bar
     [:div.cluster-bar-head
      [:span.cluster-label (:label tile)]
      [:span.cluster-bar-value
       (if (some? value) (str (js/Math.round value)) "—")
       [:span.cluster-unit (tile-unit tile unit)]]]
     [:div.cluster-bar-scale
      [:span.cluster-bar-end bottom]
      [:div.cluster-bar-track {:role "img" :aria-label (:label tile)}
       [:div.cluster-bar-fill {:class (some-> (cluster/alert gauge raw) name)
                               :style {:width (str (* 100 (cluster/fraction gauge raw)) "%")}}]]
      [:span.cluster-bar-end top]]]))

(defn- percent [fraction]
  (str (* 100 fraction) "%"))

(defn- strip-scale
  "The numbers under the strip, each under its place along the scale."
  [gauge]
  (into [:div.strip-scale {:aria-hidden true}]
        (for [{:keys [value label red?]} (cluster/ticks gauge)
              :when label]
          [:span {:class (when red? "red")
                  :style {:left (percent (cluster/fraction gauge value))}}
           label])))

(defn- strip
  "A gauge in the track style: a strip that fills from left to right as far
  as the value, green and then amber up to its red zone. The colours stay
  where they are along the scale, and the fill uncovers them."
  [id label]
  (let [{:keys [red-from caption] :as gauge} (get cluster/gauges id)
        {:keys [value]} @(rf/subscribe [::subs/sample (:name gauge)])
        value (cluster/gauge-value gauge value)]
    [:div.strip {:role "img" :aria-label label
                 :style {"--red-from" (percent (cluster/fraction gauge red-from))}}
     [:div.strip-track
      [:div.strip-fill
       {:style {:clip-path (str "inset(0 " (percent (- 1 (cluster/fraction gauge value))) " 0 0)")}}]]
     [strip-scale gauge]
     (when caption
       [:div.strip-caption caption])]))

(defn- reading
  "A gauge's value in large figures over its unit, in the track style."
  [id unit-label]
  (let [gauge (get cluster/gauges id)
        {:keys [value]} @(rf/subscribe [::subs/sample (:name gauge)])
        value (cluster/gauge-value gauge value)]
    [:div.track-reading
     [:div.track-reading-value {:class (some-> (cluster/alert gauge value) name)}
      (if value (str (js/Math.round value)) "—")]
     [:div.cluster-label unit-label]]))

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
  (let [[tile value unit] (shown @(rf/subscribe [::subs/tile kind value-name]))]
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

(defn- toggle
  "A row of buttons that picks one of a setting's `options`, each [option
  text]."
  [setting label options chosen]
  [:div.cluster-toggle {:role "group" :aria-label label}
   (for [[option text] options]
     ^{:key option}
     [:button {:class (when (= option chosen) "active")
               :aria-pressed (= option chosen)
               :on-click #(rf/dispatch [::events/set-cluster-setting setting option])}
      text])])

(defn- infos
  "The lines of the display between the large gauges."
  []
  [:div.cluster-infos
   [info :signal :outside-temp]
   [info :pid :oil-temp]
   [info :signal :fuel-economy]])

(def ^:private parts
  "What the classic and modern styles draw their large and small gauges
  with."
  {:classic {:large dial :small small-gauge :smalls :div.cluster-smalls}
   :modern {:large ring :small bar-gauge :smalls :div.cluster-bars}})

(defn- dials
  "The classic and modern styles: a large gauge each side of the gear, the
  small gauges, and the display's lines. `speed` is the speedometer's
  gauge."
  [{:keys [large small smalls]} speed]
  [:div.cluster-dials
   [:div.cluster-tach
    [large :tach "Tachometer" "rpm"]]
   [:div.cluster-centre
    [gear]
    [smalls
     [small :coolant]
     [small :fuel]]
    [infos]]
   [:div.cluster-speed
    ;; Keyed, so a change of units draws a new face.
    ^{:key speed}
    [large speed "Speedometer" (:unit-label (get cluster/gauges speed))]]])

(defn- track
  "The track style: the revs as a strip across the top, the gear between
  the revs and the speed in figures, and the rest below."
  [speed]
  [:div.track
   [strip :tach "Tachometer"]
   [:div.track-row
    [reading :tach "rpm"]
    [gear]
    [reading speed (:unit-label (get cluster/gauges speed))]]
   [:div.track-lower
    [:div.cluster-bars
     [bar-gauge :coolant]
     [bar-gauge :fuel]]
    [infos]]])

(defn cluster-view []
  (let [style @(rf/subscribe [::subs/cluster-style])
        speed-unit @(rf/subscribe [::subs/speed-unit])
        speed (get cluster/speed-units speed-unit)]
    [:section.cluster {:class (name style) :aria-label "Virtual cluster"}
     ;; Keyed, so a change of style draws new gauges.
     (if (= :track style)
       ^{:key style} [track speed]
       ^{:key style} [dials (get parts style) speed])
     [:div.cluster-foot
      [:div.cluster-styles
       [toggle :cluster-style "Cluster style" cluster/styles style]]
      [lamps]
      [:div.cluster-units
       ;; Speed in mph also puts the fuel economy in miles per gallon.
       [toggle :speed-unit "Speed and fuel economy units"
        [[:km-per-hour "km/h"] [:miles-per-hour "mph"]] speed-unit]
       [toggle :temperature-unit "Temperature units"
        [[:celsius "°C"] [:fahrenheit "°F"]] @(rf/subscribe [::subs/temperature-unit])]]]]))
