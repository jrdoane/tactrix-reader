(ns tactrix-reader.dashboard.series
  "Logs of values over time for the map view, and the binning that maps one
  value against two others.

  A log is what `/api/recordings/:name/series` returns
  (`tactrix-reader.device.series/recording-series`): `{:duration-ms :channels
  {key {:unit :name}} :series {key {:t [ms ...] :v [number-or-nil ...]}}}`,
  with at most one point per channel in each `bucket-ms`. `append-batch`
  builds the same shape from the stream's batches, using `event-points`,
  which repeats the device library's. Keep the two in step.

  `column` puts a channel on a grid with one row per bucket, and `bin` puts
  rows into the cells of a table whose axes are two such columns."
  (:require [clojure.string :as str]))

(def bucket-ms 100)

(def ^:private max-log-ms
  "The stream log keeps the last hour."
  (* 60 60 1000))

(def ^:private leaf-units
  "Units for the parts of PIDs that decode to a map and so give no unit."
  {:lambda :lambda
   :milliamps :milliamps
   :volts :volts
   :fuel-trim-percent :percent})

(defn- number-value [value]
  (cond
    (number? value) value
    (boolean? value) (if value 1 0)
    :else nil))

(defn- hex4 [n]
  (.padStart (.toUpperCase (.toString n 16)) 4 "0"))

(defn- event-key [{:keys [type signal did] :as event}]
  (case type
    :sample (keyword "pid" (name (:name event)))
    :signal (keyword "signal" (name signal))
    :did (keyword "did" (str "x" (hex4 did)))
    nil))

(defn- event-value [{:keys [type data] :as event}]
  (if (and (= :did type) (not (contains? event :value)))
    (reduce #(+ (* 256 %1) %2) 0 data)
    (:value event)))

(defn event-points
  "The `[key unit value]` points in a `:sample`, `:signal`, or `:did` event,
  as `tactrix-reader.device.series/event-points` gives them."
  [event]
  (when-let [key (event-key event)]
    (let [value (event-value event)
          unit (:unit event)]
      (if (map? value)
        (for [[leaf v] value]
          [(keyword (namespace key) (str (name key) "." (name leaf)))
           (or unit (get leaf-units leaf))
           (number-value v)])
        [[key unit (number-value value)]]))))

(defn- add-point
  "Adds a point at `t` ms to a series, replacing its last point when that is
  in the same bucket. A point earlier than the last one is dropped."
  [{:keys [t v] :as series} t-ms value]
  (let [last-t (peek t)]
    (cond
      (nil? last-t) {:t [t-ms] :v [value]}
      (< t-ms last-t) series
      (= (quot t-ms bucket-ms) (quot last-t bucket-ms)) {:t (conj (pop t) t-ms) :v (conj (pop v) value)}
      :else {:t (conj t t-ms) :v (conj v value)})))

(defn- trim-log
  "Drops points more than `max-log-ms` before the end of the log, once there
  are ten minutes to drop, and shifts what is left to start at 0."
  [{:keys [duration-ms] :as log}]
  (if (< duration-ms (+ max-log-ms (* 10 60 1000)))
    log
    (let [cut (- duration-ms max-log-ms)
          cut (- cut (mod cut bucket-ms))]
      (-> log
          (update :t0-us + (* 1000 cut))
          (update :duration-ms - cut)
          (update :series update-vals
                  (fn [{:keys [t v]}]
                    (let [i (count (take-while #(< % cut) t))]
                      {:t (mapv #(- % cut) (subvec t i))
                       :v (subvec v i)})))))))

(defn append-batch
  "Adds a stream batch, `{:samples :signals :dids}` as the `samples` and
  `snapshot` events carry them, to `log`, which may be nil. Times are kept
  from the first event's `:timestamp-us`."
  [log {:keys [samples signals dids]}]
  (let [events (concat (map #(assoc % :type :sample) samples)
                       (map #(assoc % :type :signal) signals)
                       (map #(assoc % :type :did) dids))
        stamps (keep :timestamp-us events)]
    (if (empty? stamps)
      log
      (let [t0-us (or (:t0-us log) (apply min stamps))]
        (trim-log
         (reduce (fn [log event]
                   (let [t-ms (js/Math.floor (/ (- (:timestamp-us event) t0-us) 1000))]
                     (reduce (fn [log [key unit value]]
                               (cond-> (update-in log [:series key] add-point t-ms value)
                                 (not (get-in log [:channels key]))
                                 (assoc-in [:channels key] (cond-> {}
                                                             unit (assoc :unit unit)
                                                             (and (:name event) (= :did (:type event)))
                                                             (assoc :name (:name event))))
                                 true (update :duration-ms max t-ms)))
                             log
                             (event-points event))))
                 (or log {:t0-us t0-us :duration-ms 0 :channels {} :series {}})
                 (filter :timestamp-us events)))))))

(def load-key
  "Air per revolution worked out from MAF and RPM, in g/rev."
  :derived/air-per-revolution)

(def ^:private maf-key :pid/maf-air-flow)
(def ^:private rpm-key :pid/engine-rpm)

(defn channels
  "The log's channels that have had a number, plus the derived load when MAF
  and RPM are there, as `{key {:unit :name}}`."
  [{:keys [series] :as log}]
  (let [found (into {}
                    (filter (fn [[key _]] (some some? (get-in series [key :v]))))
                    (:channels log))]
    (cond-> found
      (and (contains? found maf-key) (contains? found rpm-key))
      (assoc load-key {:unit :grams-per-revolution}))))

(defn row-count
  "The number of grid rows in a log: one per bucket."
  [{:keys [duration-ms]}]
  (if duration-ms
    (inc (quot duration-ms bucket-ms))
    0))

(defn row-time
  "The time in ms at the start of row `i`."
  [i]
  (* i bucket-ms))

(defn max-age
  "How long a channel's value is held on the grid: three times its median
  gap between points, from 1 to 10 seconds. Slow PIDs and DIDs are read every
  few seconds, but a fast value isn't held past a gap in it."
  [t]
  (let [gaps (sort (map - (rest t) t))]
    (if (empty? gaps)
      1000
      (-> (* 3 (nth gaps (quot (count gaps) 2)))
          (max 1000)
          (min 10000)))))

(defn- held-column [{:keys [t v]} n]
  (let [age (max-age t)
        points (count t)]
    (loop [i 0
           j -1
           out (transient [])]
      (if (= i n)
        (persistent! out)
        ;; Row i holds the latest point by the end of its bucket.
        (let [end (dec (* (inc i) bucket-ms))
              j (loop [j j]
                  (if (and (< (inc j) points) (<= (nth t (inc j)) end))
                    (recur (inc j))
                    j))]
          (recur (inc i)
                 j
                 (conj! out (when (and (>= j 0) (<= (- end (nth t j)) age))
                              (nth v j)))))))))

(defn column
  "The values of channel `key` with one row per bucket, as `row-count` gives,
  each the channel's latest value then, or nil once it is older than
  `max-age`. `load-key` is MAF × 60 / RPM, nil below 300 rpm."
  [log key]
  (let [n (row-count log)]
    (if (= key load-key)
      (mapv (fn [maf rpm]
              (when (and maf rpm (>= rpm 300))
                (/ (* maf 60) rpm)))
            (column log maf-key)
            (column log rpm-key))
      (if-let [series (get-in log [:series key])]
        (held-column series n)
        (vec (repeat n nil))))))

(def ^:private preset-axes
  "Breakpoints like an ECU table's for the usual map axes."
  {:pid/engine-rpm (vec (range 800 6401 400))
   load-key [0.1 0.2 0.3 0.4 0.5 0.6 0.7 0.8 0.9 1.0 1.1 1.2 1.3]
   :did/x10A3 [0.1 0.2 0.3 0.4 0.5 0.6 0.7 0.8 0.9 1.0 1.1 1.2 1.3]})

(defn- nice-step
  "The round step (1, 2, 2.5, or 5 times a power of ten) that divides `span`
  into the number of steps nearest `steps`."
  [span steps]
  (let [power (js/Math.pow 10 (js/Math.floor (js/Math.log10 (/ span steps))))]
    (apply min-key
           #(js/Math.abs (- (/ span %) steps))
           (map #(* % power) [1 2 2.5 5 10]))))

(defn- round-to-step
  "Rounds away the float error that adding steps leaves, such as 0.30000000000000004."
  [x step]
  (let [places (-> (js/Math.ceil (- (js/Math.log10 step))) inc (max 0) (min 6))]
    (js/parseFloat (.toFixed x places))))

(defn default-axis
  "Breakpoints for channel `key`: a preset for RPM and load, or else about
  twelve round steps across the middle 98% of `values`."
  [key values]
  (or (get preset-axes key)
      (let [sorted (vec (sort (filter some? values)))
            n (count sorted)]
        (when (pos? n)
          (let [low (nth sorted (quot n 100))
                high (nth sorted (min (dec n) (quot (* 99 n) 100)))]
            (if (= low high)
              [low]
              (let [step (nice-step (- high low) 11)
                    start (* step (js/Math.floor (/ low step)))]
                (->> (iterate #(+ % step) start)
                     (take-while #(< % (+ high step)))
                     (mapv #(round-to-step % step))))))))))

(defn parse-axis
  "Breakpoints typed as numbers separated by commas or spaces, sorted, or nil
  when there are none or any isn't a number."
  [text]
  (let [parts (remove str/blank? (str/split (str text) #"[,\s]+"))
        numbers (map js/Number parts)]
    (when (and (seq parts) (every? #(js/Number.isFinite %) numbers))
      (vec (sort (distinct numbers))))))

(defn format-axis [axis]
  (str/join ", " axis))

(defn nearest-index
  "The index of the breakpoint nearest `x`. Values past either end go to the
  end breakpoint, as in an ECU table."
  [axis x]
  (loop [i 1
         best 0]
    (if (< i (count axis))
      (recur (inc i)
             (if (< (js/Math.abs (- x (nth axis i))) (js/Math.abs (- x (nth axis best)))) i best))
      best)))

(defn row-filter
  "A predicate on row indexes for a time `window` [from-ms to-ms] (or nil)
  and `filters`, each `{:op :>= or :<= :value n :column [...]}`. A row whose
  filter value is missing is left out."
  [window filters]
  (let [filters (filterv #(number? (:value %)) filters)]
    (fn [i]
      (and (or (nil? window)
               (let [t (row-time i)] (<= (first window) t (second window))))
           (every? (fn [{:keys [op value column]}]
                     (let [x (nth column i nil)]
                       (and (some? x)
                            (if (= :<= op) (<= x value) (>= x value)))))
                   filters)))))

(defn bin
  "Puts each row with an x, y, and z into the cell of the breakpoints nearest
  its x and y. Returns `{[x-index y-index] {:count :sum :min :max :x-sum
  :y-sum :rows [i ...]}}`, for the rows `keep?` accepts."
  [xs ys zs x-axis y-axis keep?]
  (if (or (empty? x-axis) (empty? y-axis))
    {}
    (persistent!
     (reduce (fn [cells i]
               (let [x (nth xs i nil)
                     y (nth ys i nil)
                     z (nth zs i nil)]
                 (if (and (some? x) (some? y) (some? z) (keep? i))
                   (let [cell [(nearest-index x-axis x) (nearest-index y-axis y)]
                         {hits :count total :sum low :min high :max :keys [x-sum y-sum rows]
                          :or {hits 0 total 0 low z high z x-sum 0 y-sum 0 rows []}} (get cells cell)]
                     (assoc! cells cell {:count (inc hits)
                                         :sum (+ total z)
                                         :min (min low z)
                                         :max (max high z)
                                         :x-sum (+ x-sum x)
                                         :y-sum (+ y-sum y)
                                         :rows (conj rows i)}))
                   cells)))
             (transient {})
             (range (count xs))))))

(defn cell-stat
  "A cell's `:mean`, `:min`, `:max`, or `:count`."
  [stat {hits :count total :sum :as cell}]
  (case stat
    :mean (/ total hits)
    :count hits
    (get cell stat)))

(defn sparkline
  "The last `window-ms` of channel `key` as an SVG path in a box `width` by
  `height`, or nil with fewer than two numbers. The box spans the window, so
  a value that has only just arrived starts partway across, and the value is
  held between points, as on the dashboard. A channel that has not moved is
  a line across the middle."
  [{:keys [duration-ms] :as log} key window-ms width height]
  (let [{:keys [t v]} (get-in log [:series key])
        from (- (or duration-ms 0) window-ms)
        start (loop [i (count t)]
                (if (and (pos? i) (>= (nth t (dec i)) from))
                  (recur (dec i))
                  i))
        points (filterv #(some? (second %)) (map vector (subvec (or t []) start) (subvec (or v []) start)))]
    (when (>= (count points) 2)
      (let [values (map second points)
            low (apply min values)
            high (apply max values)
            x #(.toFixed (* width (/ (- % from) window-ms)) 1)
            y #(.toFixed (if (= low high)
                           (/ height 2)
                           ;; A pixel of room above and below.
                           (- (dec height) (* (- height 2) (/ (- % low) (- high low)))))
                         1)]
        (str/join " "
                  (cons (str "M" (x (ffirst points)) " " (y (second (first points))))
                        (mapcat (fn [[_ before] [at value]]
                                  (cond-> [(str "H" (x at))]
                                    (not= before value) (conj (str "V" (y value)))))
                                points
                                (rest points))))))))

(defn envelope
  "The lowest and highest value in each of `width` slices of `values`, as
  `[low high]` or nil for a slice with none, for drawing a long column."
  [values width]
  (let [n (count values)]
    (mapv (fn [slice]
            (let [from (js/Math.floor (/ (* slice n) width))
                  to (max (inc from) (js/Math.floor (/ (* (inc slice) n) width)))
                  present (filter some? (subvec values (min from n) (min to n)))]
              (when (seq present)
                [(apply min present) (apply max present)])))
          (range width))))
