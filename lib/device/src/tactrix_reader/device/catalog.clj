(ns tactrix-reader.device.catalog
  "How to show every value the sources emit: a label, unit, and group for
  each PID, inferred CAN signal, derived value, and UDS data identifier, as
  plain data a display can be built from. The engine server sends it to the dashboard, so
  a signal added to `broadcast/inferred-signals` or a PID added to the poll
  schedule appears there without the dashboard changing.

  PIDs get their display here, in `pid-display`. Inferred signals, derived
  values, and DIDs carry theirs in `broadcast/inferred-signals`,
  `derived/derived-signals`, and `uds/did-signals`."
  (:require [clojure.string :as str]
            [tactrix-reader.device.broadcast :as broadcast]
            [tactrix-reader.device.can :as can]
            [tactrix-reader.device.derived :as derived]
            [tactrix-reader.device.pids :as pids]
            [tactrix-reader.device.uds :as uds]))

(def sections
  "The blocks a display is divided into, in order. `:provenance` is how
  everything in the section is known, when that is the same for all of it."
  [{:id :obd :title "OBD-II PIDs"}
   {:id :can :title "From the CAN bus" :provenance :inferred
    :note (str "Identified from the car's own traffic by how they behave, but matched "
               "to no PID, so scales and directions are not confirmed.")}
   {:id :derived :title "Worked out" :provenance :derived
    :note (str "Calculated from the values above, with this car's gearing and "
               "petrol's density. None is read from the car.")}])

(def groups
  "The groups in each section, in order. A value whose group is missing or
  unknown goes in its section's `:other` group."
  [{:id :throttle :section :obd :title "Throttle and pedal"}
   {:id :air :section :obd :title "Air and load"}
   {:id :temperatures :section :obd :title "Temperatures"}
   {:id :fuel :section :obd :title "Fuel"}
   {:id :electrical :section :obd :title "Electrical and gearbox"}
   {:id :other :section :obd :title "Other PIDs"}
   {:id :driver :section :can :title "Driver"}
   {:id :motion :section :can :title "Motion"}
   {:id :wheels :section :can :title "Wheel speeds"}
   {:id :doors :section :can :title "Doors"}
   {:id :engine :section :can :title "Engine, battery, and outside"}
   {:id :counters :section :can :title "Fuel gauge and counters"}
   {:id :other :section :can :title "Other signals"}
   {:id :engine :section :derived :title "Engine"}
   {:id :road :section :derived :title "Road and clutch"}
   {:id :other :section :derived :title "Other"}])

(def primary
  "The values a display should show largest, by name."
  [:engine-rpm :vehicle-speed :gear :coolant-temp :throttle-position])

(def pid-display
  "A shorter label than the PID's name gives, and the group it is shown in,
  by PID name. `:format` names how to show a value that reads better as
  words, `:parts` picks what to show of a PID that decodes to a map, and
  `:tile? false` keeps a PID out of the tiles because a display shows it
  another way. A PID with no entry is labelled from its name and goes in
  `:other`."
  {:engine-rpm {:label "RPM" :group :air}
   :vehicle-speed {:label "Speed" :group :air}
   :coolant-temp {:label "Coolant" :group :temperatures}
   :throttle-position {:label "Throttle" :group :throttle}
   :relative-throttle-position {:label "Relative throttle" :group :throttle}
   :commanded-throttle-actuator {:label "Commanded throttle" :group :throttle}
   :relative-accelerator-pedal-position {:label "Accelerator pedal" :group :throttle}
   :accelerator-pedal-position-d {:label "Pedal position D" :group :throttle}
   :engine-load {:label "Engine load" :group :air}
   :maf-air-flow {:label "Mass air flow" :group :air}
   :intake-manifold-pressure {:label "Manifold pressure" :group :air}
   :barometric-pressure {:label "Barometric pressure" :group :air}
   :timing-advance {:label "Timing advance" :group :air}
   :oil-temp {:label "Oil" :group :temperatures}
   :intake-air-temp {:label "Intake air" :group :temperatures}
   :ambient-air-temp {:label "Ambient air" :group :temperatures}
   :catalyst-temp-bank-1-sensor-1 {:label "Catalyst" :group :temperatures}
   ;; The Impreza has one fuel system.
   :fuel-system-status {:label "Fuel system" :group :fuel :format :fuel-system}
   :commanded-lambda {:label "Commanded λ" :group :fuel}
   :wideband-oxygen-sensor-1-current {:label "Measured λ" :group :fuel
                                      :parts [{:part :lambda :unit :lambda}]}
   :short-term-fuel-trim-bank-1 {:label "Short-term trim" :group :fuel}
   :long-term-fuel-trim-bank-1 {:label "Long-term trim" :group :fuel}
   :oxygen-sensor-bank-1-sensor-2 {:label "Rear O₂ sensor" :group :fuel
                                   :parts [{:part :volts :unit :volts :decimals 3}]}
   :fuel-level {:label "Fuel level" :group :fuel}
   :control-module-voltage {:label "ECU voltage" :group :electrical}
   :auxiliary-io-status {:label "Neutral switch" :group :electrical :format :in-gear}
   ;; Shown with the trouble codes.
   :monitor-status {:label "Monitor status" :format :monitor-status :tile? false}})

(defn- name-label [value-name]
  (str/capitalize (str/replace (name value-name) "-" " ")))

(defn- broadcast-sources
  "Where each PID is also decoded from, by PID: the CAN ID and the bits of
  its frames."
  []
  (into {}
        (for [[can-id signals] broadcast/broadcast-signals
              {:keys [pid decode]} signals]
          [pid {:can-id can-id :bits (broadcast/decode-bits decode)}])))

(defn- pid-entries []
  (let [polled (set can/default-poll-schedule)
        sources (broadcast-sources)]
    (for [[pid {:keys [name unit pid-list?]}] (sort-by key pids/mode-01-pids)
          :when (not pid-list?)
          :let [display (get pid-display name)]]
      (cond-> (merge {:kind :pid
                      :name name
                      :pid pid
                      :label (name-label name)
                      :group :other}
                     display)
        unit (assoc :unit unit)
        (polled pid) (assoc :polled? true)
        (sources pid) (merge (sources pid))))))

(defn- signal-entries []
  (for [[can-id signals] (sort-by key broadcast/inferred-signals)
        {:keys [signal unit decode parts] :as entry} signals]
    (cond-> (merge {:kind :signal
                    :name signal
                    :can-id can-id
                    :bits (broadcast/decode-bits decode)
                    :label (name-label signal)
                    :group :other}
                   (select-keys entry [:label :group :format :raw? :override]))
      unit (assoc :unit unit)
      ;; Each part of a value that is a map has its own bits.
      parts (assoc :parts (mapv #(assoc % :bits (broadcast/decode-bits decode (:part %)))
                                parts)))))

(defn- derived-entries
  "Derived values arrive as signals, so they are `:kind :signal`, with the
  section they are shown in and what each is worked out `:from`."
  []
  (for [{:keys [signal] :as entry} derived/derived-signals]
    (merge {:kind :signal :name signal :section :derived}
           (select-keys entry [:label :group :unit :from]))))

(defn- did-entries []
  (for [[did {:keys [name] :as entry}] (sort-by key uds/did-signals)]
    (merge {:did did :label (name-label name)}
           (select-keys entry [:name :label :unit :matches :compare :published?]))))

(defn catalog
  "Everything a display needs, with no functions in it, so it can be sent as
  Transit or EDN:

  - `:sections` and `:groups`, as above.
  - `:primary`, the names to show largest.
  - `:signals`, one entry per PID, inferred signal, and derived value:
    `{:kind :pid|:signal :name :label :group}` with `:unit`, `:format`,
    `:parts`, `:raw?`, and `:override` where they apply, `:pid` and
    `:polled?` (in the default poll schedule) for a PID, and, for a signal
    or a PID also decoded from the bus, `:can-id` and `:bits`, the bits of
    that ID's frames it is decoded from, as `broadcast/decode-bits` gives
    them. Each of a signal's `:parts` has its own `:bits`. A PID is shown
    in the `:obd` section and a signal in `:can`, unless the entry names
    its `:section`: a derived value has `:section :derived`, no `:can-id`,
    and `:from`, the names of the values it is worked out from.
  - `:dids`, one entry per DID in `uds/did-signals`, with `:matches` and
    `:compare` for one fitted to a PID and `:published? true` for one named
    in a published list, and `:did-notes`, what is known about the others."
  []
  {:sections sections
   :groups groups
   :primary primary
   :signals (vec (concat (pid-entries) (signal-entries) (derived-entries)))
   :dids (vec (did-entries))
   :did-notes uds/did-notes})
