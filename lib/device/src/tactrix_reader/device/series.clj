(ns tactrix-reader.device.series
  "Recordings as time series, for mapping one value against others: each value
  the sources emit, flattened to a number and keyed by channel, with at most
  one point per channel in each `bucket-ms` bucket, the last one. That matches
  the hub's batching, so the dashboard builds the same shape from the live
  stream in `tactrix-reader.dashboard.series`, which repeats `event-points`.
  Keep the two in step."
  (:require [tactrix-reader.device.broadcast :as broadcast]
            [tactrix-reader.device.can :as can]
            [tactrix-reader.device.derived :as derived]
            [tactrix-reader.device.openport :as openport]
            [tactrix-reader.device.recording :as recording]
            [tactrix-reader.device.uds :as uds]))

(def bucket-ms 100)

(def ^:private leaf-units
  "Units for the parts of PIDs that decode to a map and so give no unit."
  {:lambda :lambda
   :milliamps :milliamps
   :volts :volts
   :fuel-trim-percent :percent})

(defn- number-value
  "A number as itself and a boolean as 1 or 0. Anything else, such as gear
  `:neutral`, is nil, which ends the value held from before."
  [value]
  (cond
    (number? value) value
    (boolean? value) (if value 1 0)
    :else nil))

(defn- event-key [{:keys [type name signal did-hex]}]
  (case type
    :sample (keyword "pid" (clojure.core/name name))
    :signal (keyword "signal" (clojure.core/name signal))
    ;; `x` like `0x`, because a keyword's name can't start with a digit.
    :did (keyword "did" (str "x" did-hex))
    nil))

(defn- event-value
  "A DID reply's decoded value, or else its data bytes read as one unsigned
  number, so DIDs with no known scale can be mapped too."
  [{:keys [type data] :as event}]
  (if (and (= :did type) (not (contains? event :value)))
    (reduce #(+ (* 256 %1) %2) 0 data)
    (:value event)))

(defn event-points
  "The `[key unit value]` points in a `:sample`, `:signal`, or `:did` event,
  keyed `:pid/<name>`, `:signal/<signal>`, or `:did/x<hex>` (such as
  `:did/x10A3`). A map value gives
  one point per entry, such as `:signal/wheel-speeds.front-left`."
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
  "Adds a point to its channel's series, replacing the channel's last point
  when it is in the same bucket."
  [series [key t value]]
  (let [{:keys [t-vals v-vals last-bucket]
         :or {t-vals (transient []) v-vals (transient [])}} (get series key)
        bucket (quot t bucket-ms)
        same? (= bucket last-bucket)]
    (assoc! series key {:t-vals (conj! (cond-> t-vals same? pop!) t)
                        :v-vals (conj! (cond-> v-vals same? pop!) value)
                        :last-bucket bucket})))

(defn- channel
  "What a channel's series says about it: its unit, and for a DID in
  `uds/did-signals`, its name, as the stream's DID events carry."
  [key unit]
  (let [did-name (when (= "did" (namespace key))
                   (:name (get uds/did-signals (Integer/parseInt (subs (name key) 1) 16))))]
    (cond-> {}
      unit (assoc :unit unit)
      did-name (assoc :name did-name))))

(defn recording-series
  "Returns a recording's values as `{:ok? true :bucket-ms :duration-ms
  :channels {key {:unit :name}} :series {key {:t [ms ...] :v [value ...]}}}`, with
  `t` in ms from the recording's first message. Decodes raw.bin as the replay
  source does, with broadcast, inferred, and derived signals and DID
  replies. Leaves out channels that never had a number."
  [dir]
  (let [rx-id (recording/recording-rx-id dir)
        messages (filterv #(and (= :message (:type %)) (not (:malformed? %)))
                          (:events (openport/parse-adapter-stream (recording/recording-bytes dir))))
        t0 (:timestamp-us (first messages))
        derive (derived/deriver)
        points (for [message messages
                     event (derive (can/message-events rx-id broadcast/broadcast-signals
                                                       broadcast/inferred-signals message))
                     point (event-points event)]
                 [point (quot (- (:timestamp-us event) t0) 1000)])]
    (if (empty? points)
      {:ok? false :error :no-samples}
      (let [units (reduce (fn [units [[key unit]]]
                            (cond-> units
                              (not (contains? units key)) (assoc key unit)))
                          {}
                          points)
            ;; Channels that never had a number, such as the seatbelt, are left out.
            series (into {}
                         (keep (fn [[key {:keys [t-vals v-vals]}]]
                                 (let [v (persistent! v-vals)]
                                   (when (some some? v)
                                     [key {:t (persistent! t-vals) :v v}]))))
                         (persistent!
                          (reduce (fn [series [[key _ value] t]]
                                    (add-point series [key t value]))
                                  (transient {})
                                  points)))]
        {:ok? true
         :bucket-ms bucket-ms
         :duration-ms (quot (- (:timestamp-us (peek messages)) t0) 1000)
         :channels (into {}
                         (map (fn [key]
                                [key (channel key (get units key))]))
                         (keys series))
         :series series}))))
