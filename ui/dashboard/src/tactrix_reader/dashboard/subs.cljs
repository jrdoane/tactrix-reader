(ns tactrix-reader.dashboard.subs
  (:require [re-frame.core :as rf]
            [tactrix-reader.dashboard.catalog :as catalog]
            [tactrix-reader.dashboard.series :as series]))

(rf/reg-sub
 ::connection
 (fn [db _]
   (:connection db)))

(rf/reg-sub
 ::status
 (fn [db _]
   (:status db)))

(rf/reg-sub
 ::info
 (fn [db _]
   (:info db)))

(rf/reg-sub
 ::firmware
 (fn [db _]
   (or (:firmware db)
       (get-in db [:info :adapter :firmware]))))

(rf/reg-sub
 ::adapters
 (fn [db _]
   (:adapters db)))

(rf/reg-sub
 ::selected-path
 (fn [db _]
   (:selected-path db)))

(rf/reg-sub
 ::request
 (fn [db _]
   (:request db)))

(rf/reg-sub
 ::busy?
 ;; True while a switch is being requested or the server is connecting.
 :<- [::request]
 :<- [::status]
 (fn [[request status] _]
   (boolean (or (= :pending (:state request))
                (#{:connecting :identifying} (:state status))))))

(rf/reg-sub
 ::samples
 (fn [db _]
   (:samples db)))

(rf/reg-sub
 ::sample
 :<- [::samples]
 (fn [samples [_ pid-name]]
   (get samples pid-name)))

(rf/reg-sub
 ::sample-list
 :<- [::samples]
 (fn [samples _]
   (sort-by :pid (vals samples))))

(rf/reg-sub
 ::signals
 (fn [db _]
   (:signals db)))

(rf/reg-sub
 ::signal
 :<- [::signals]
 (fn [signals [_ signal-name]]
   (get signals signal-name)))

(rf/reg-sub
 ::catalog
 ;; How to show each PID, signal, and DID, from /api/catalog, or nil until
 ;; it has loaded.
 (fn [db _]
   (:catalog db)))

(rf/reg-sub
 :catalog/labels
 ;; Labels by name, for `format/label`.
 :<- [::catalog]
 (fn [catalog _]
   (catalog/labels catalog)))

(rf/reg-sub
 ::seen-names
 ;; The names of the PIDs and signals that have arrived. Its value changes
 ;; only when a new name does, so what is built from it is not rebuilt for
 ;; every batch.
 :<- [::samples]
 :<- [::signals]
 (fn [[samples signals] _]
   {:pid (set (keys samples)) :signal (set (keys signals))}))

(rf/reg-sub
 ::entries
 ;; The catalog's entries plus one for each arrived name it does not have.
 :<- [::catalog]
 :<- [::seen-names]
 (fn [[catalog seen] _]
   (catalog/entries catalog seen)))

(rf/reg-sub
 ::primary-tiles
 :<- [::catalog]
 :<- [::entries]
 (fn [[catalog entries] _]
   (catalog/primary-tiles catalog entries)))

(rf/reg-sub
 ::sections
 ;; The dashboard's sections, each with its groups of tiles.
 :<- [::catalog]
 :<- [::entries]
 :<- [::seen-names]
 (fn [[catalog entries seen] _]
   (catalog/sections catalog entries seen)))

(rf/reg-sub
 ::tile-value
 ;; The latest {:value :unit} for a tile, with `:value` the tile's part of a
 ;; value that is a map.
 (fn [[_ {:keys [kind name]}]]
   (rf/subscribe [(if (= :signal kind) ::signal ::sample) name]))
 (fn [sample [_ {:keys [part]}]]
   (cond-> sample
     (and sample part) (update :value get part))))

(rf/reg-sub
 ::tile-override
 ;; The text a tile shows instead of its value, such as R for the gear in
 ;; reverse, or nil.
 :<- [::signals]
 (fn [signals [_ tile]]
   (catalog/override-text tile signals)))

(rf/reg-sub
 ::position
 ;; Where a replay is, or nil.
 (fn [db _]
   (:position db)))

(rf/reg-sub
 ::spark-log
 (fn [db _]
   (:spark-log db)))

(rf/reg-sub
 ::sparkline
 ;; The last minute of a channel as an SVG path, redrawn once a second.
 :<- [::spark-log]
 (fn [log [_ key]]
   (when log
     (series/sparkline log key 60000 100 24))))

(rf/reg-sub
 ::bus
 (fn [db _]
   (:bus db)))

(rf/reg-sub
 ::can-fields
 ;; What is decoded from each CAN ID and from which bits, by ID.
 :<- [::catalog]
 (fn [catalog _]
   (catalog/can-fields catalog)))

(rf/reg-sub
 ::collapsed
 (fn [db _]
   (:collapsed db)))

(rf/reg-sub
 ::details?
 (fn [db _]
   (:details? db)))

(rf/reg-sub
 ::can-ids
 (fn [db _]
   (:can-ids db)))

(rf/reg-sub
 ::can-id
 :<- [::can-ids]
 (fn [can-ids [_ pid-name]]
   (get can-ids pid-name)))

(rf/reg-sub
 ::dids
 (fn [db _]
   (:dids db)))

(rf/reg-sub
 ::did-list
 :<- [::dids]
 (fn [dids _]
   (sort-by :did (vals dids))))

(rf/reg-sub
 ::did-ranges
 (fn [db _]
   (:did-ranges db)))

(rf/reg-sub
 ::poll-dids?
 (fn [db _]
   (:poll-dids? db)))

(rf/reg-sub
 ::recordings
 (fn [db _]
   (:recordings db)))

(rf/reg-sub
 ::recordings-dir
 (fn [db _]
   (:recordings-dir db)))

(rf/reg-sub
 ::recording
 (fn [db _]
   (:recording db)))

(rf/reg-sub
 ::note
 (fn [db _]
   (:note db)))

(rf/reg-sub
 ::now
 (fn [db _]
   (:now db)))

(rf/reg-sub
 ::recordable?
 ;; Recording saves what a running live source receives.
 :<- [::status]
 (fn [{:keys [source state]} _]
   (and (= :live source) (= :running state))))

(rf/reg-sub
 ::view
 (fn [db _]
   (:view db)))

(rf/reg-sub
 ::map-settings
 (fn [db _]
   (:map db)))

(rf/reg-sub
 ::recording-log
 (fn [db _]
   (:recording-log db)))

(rf/reg-sub
 ::map-log
 ;; The log the map shows: the stream's, or the loaded recording's.
 (fn [db _]
   (let [log (get-in db [:map :log])]
     (if (= :stream log)
       (:stream-log db)
       (when (= log (get-in db [:recording-log :name]))
         (get-in db [:recording-log :log]))))))

(rf/reg-sub
 ::map-channels
 :<- [::map-log]
 (fn [log _]
   (when log (series/channels log))))

(rf/reg-sub
 ::map-row-count
 :<- [::map-log]
 (fn [log _]
   (when log (series/row-count log))))

(rf/reg-sub
 ::map-column
 :<- [::map-log]
 (fn [log [_ key]]
   (when (and log key) (series/column log key))))

(rf/reg-sub
 ::map-axis
 ;; Breakpoints typed for `key`, or else its default axis.
 (fn [[_ key]]
   [(rf/subscribe [::map-settings]) (rf/subscribe [::map-column key])])
 (fn [[settings values] [_ key]]
   (or (series/parse-axis (get-in settings [:axis-text key]))
       (series/default-axis key values))))

(rf/reg-sub
 ::map-cells
 ;; Rows in each cell for `x`, `y`, and `z` on the given axes, within the
 ;; window and filters.
 (fn [[_ x y z _ filters]]
   (into [(rf/subscribe [::map-column x])
          (rf/subscribe [::map-column y])
          (rf/subscribe [::map-column z])]
         (map #(rf/subscribe [::map-column (:key %)]) filters)))
 (fn [[xs ys zs & filter-columns] [_ _ _ _ [x-axis y-axis] filters window]]
   (series/bin xs ys zs x-axis y-axis
               (series/row-filter window (map #(assoc %1 :column %2) filters filter-columns)))))

(rf/reg-sub
 ::map-envelope
 (fn [[_ key]]
   (rf/subscribe [::map-column key]))
 (fn [values [_ _ width]]
   (when values (series/envelope values width))))
