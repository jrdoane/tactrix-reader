(ns tactrix-reader.dashboard.events
  (:require [re-frame.core :as rf]
            [tactrix-reader.dashboard.http]
            [tactrix-reader.dashboard.series :as series]
            [tactrix-reader.dashboard.stream]))

(def ^:private initial-map
  {;; :stream, or the name of a recording.
   :log :stream
   ;; Channel keys, as `series` names them.
   :x :pid/engine-rpm
   :y series/load-key
   :z :pid/timing-advance
   :timeline :pid/vehicle-speed
   ;; :mean, :min, :max, or :count.
   :stat :mean
   ;; Cells with fewer rows are dimmed.
   :min-hits 3
   ;; Breakpoints typed for a channel, keyed by channel; others use
   ;; `series/default-axis`.
   :axis-text {}
   ;; Each {:key :op :>=|:<= :value}.
   :filters []
   ;; [from-ms to-ms] of the log, or nil for all of it.
   :window nil
   ;; The selected [x-index y-index], or nil.
   :cell nil})

(def initial-db
  {:connection :connecting
   :status nil
   ;; What the source knows about the adapter, vehicle, and recording.
   :info nil
   ;; Adapter firmware from the last status that reported it.
   :firmware nil
   ;; Latest sample of each PID, keyed by name such as :engine-rpm.
   :samples {}
   ;; Latest value of each inferred CAN signal, keyed by name such as :gear.
   :signals {}
   ;; The CAN ID each PID has also been decoded from, keyed by PID name.
   :can-ids {}
   ;; Latest reply for each UDS data identifier, keyed by DID such as 0x10DF,
   ;; with its data bytes also read as one unsigned :number.
   :dids {}
   ;; The [lowest highest] :number of each DID since the page connected or
   ;; the source changed, keyed by DID.
   :did-ranges {}
   ;; Adapters the server detected, and the one chosen to connect to.
   :adapters nil
   :selected-path nil
   ;; Whether connecting also polls the scanned UDS DIDs.
   :poll-dids? false
   ;; Saved recordings from /api/recordings, newest first, and their folder.
   :recordings nil
   :recordings-dir nil
   ;; The last recording event: {:state :recording|:saved ...}, or nil.
   :recording nil
   ;; The note typed for the next recording.
   :note ""
   ;; The time in ms, updated every second while recording.
   :now nil
   ;; The last API request: nil, {:state :pending}, or {:state :failed :error ...}.
   :request nil
   ;; :dashboard or :map.
   :view :dashboard
   ;; Everything the stream has sent since the source last changed, as a
   ;; `series` log.
   :stream-log nil
   ;; The recording open in the map: {:name :state :loading|:loaded|:failed
   ;; :log :error}.
   :recording-log nil
   :map initial-map})

(defn- index-by-name [samples]
  (into {} (map (juxt :name identity)) samples))

(defn- index-by-signal [signals]
  (into {} (map (juxt :signal identity)) signals))

(defn- index-by-did [dids]
  (into {}
        (map (fn [{:keys [did data] :as reply}]
               [did (assoc reply :number (reduce #(+ (* 256 %1) %2) 0 data))]))
        dids))

(defn- widen-ranges [ranges dids-by-did]
  (reduce-kv (fn [ranges did {:keys [number]}]
               (update ranges did (fn [[low high]]
                                    [(min (or low number) number) (max (or high number) number)])))
             ranges
             dids-by-did))

(defn- can-ids [samples]
  (into {} (keep (fn [{:keys [name can-id]}] (when can-id [name can-id]))) samples))

(defonce ^:private ticker (atom nil))

(rf/reg-fx
 ::tick
 ;; While true, dispatches ::tick every second, for the recording timer.
 (fn [on?]
   (when-let [id @ticker]
     (js/clearInterval id)
     (reset! ticker nil))
   (when on?
     (rf/dispatch [::tick])
     (reset! ticker (js/setInterval #(rf/dispatch [::tick]) 1000)))))

(rf/reg-event-db
 ::tick
 (fn [db _]
   (assoc db :now (js/Date.now))))

(defn- recording? [recording]
  (= :recording (:state recording)))

(rf/reg-event-fx
 ::initialize
 (fn [_ _]
   {:db initial-db
    :stream/connect "/stream"
    :fx [[:dispatch [::refresh-adapters]]
         [:dispatch [::refresh-recordings]]]}))

(rf/reg-event-db
 :stream/connected
 (fn [db _]
   (assoc db :connection :connected)))

(rf/reg-event-db
 :stream/disconnected
 (fn [db _]
   (assoc db :connection :disconnected)))

(defn- reset-stream-map
  "A new stream log starts at 0, so a window or cell picked on the old one no
  longer means anything."
  [db]
  (cond-> db
    (= :stream (get-in db [:map :log])) (update :map assoc :window nil :cell nil)))

(rf/reg-event-fx
 :stream/snapshot
 ;; Sent on connect, and again with empty data whenever the source changes.
 (fn [{:keys [db]} [_ {:keys [status info recording samples signals dids] :as snapshot}]]
   (let [dids-by-did (index-by-did dids)]
     {:db (-> db
              (assoc :status status
                     :info info
                     :recording recording
                     :firmware (:firmware status)
                     :samples (index-by-name samples)
                     :signals (index-by-signal signals)
                     :can-ids (can-ids samples)
                     :dids dids-by-did
                     :did-ranges (widen-ranges {} dids-by-did)
                     :stream-log (series/append-batch nil snapshot))
              reset-stream-map)
      ::tick (recording? recording)})))

(rf/reg-event-db
 :stream/samples
 (fn [db [_ {:keys [samples signals dids] :as batch}]]
   (let [dids-by-did (index-by-did dids)]
     (-> db
         (update :samples merge (index-by-name samples))
         (update :signals merge (index-by-signal signals))
         (update :can-ids merge (can-ids samples))
         (update :dids merge dids-by-did)
         (update :did-ranges widen-ranges dids-by-did)
         (update :stream-log series/append-batch batch)))))

(rf/reg-event-db
 :stream/status
 (fn [db [_ status]]
   (cond-> (assoc db :status status)
     (:firmware status) (assoc :firmware (:firmware status)))))

(rf/reg-event-db
 :stream/info
 (fn [db [_ info]]
   (assoc db :info info)))

(rf/reg-event-fx
 :stream/recording
 ;; A recording started or was saved; a saved one joins the list.
 (fn [{:keys [db]} [_ recording]]
   (cond-> {:db (assoc db :recording recording)
            ::tick (recording? recording)}
     (= :saved (:state recording)) (assoc :fx [[:dispatch [::refresh-recordings]]]))))

(rf/reg-event-fx
 ::refresh-recordings
 (fn [_ _]
   {:http {:url "/api/recordings"
           :on-success [::recordings-loaded]
           :on-failure [::request-failed]}}))

(rf/reg-event-db
 ::recordings-loaded
 (fn [db [_ {:keys [dir recordings]}]]
   (assoc db :recordings recordings :recordings-dir dir)))

(rf/reg-event-fx
 ::refresh-adapters
 (fn [_ _]
   {:http {:url "/api/adapters"
           :on-success [::adapters-loaded]
           :on-failure [::request-failed]}}))

(rf/reg-event-db
 ::adapters-loaded
 (fn [db [_ {:keys [adapters]}]]
   (let [paths (set (map :path adapters))
         live-path (when (= :live (get-in db [:status :source]))
                     (get-in db [:status :path]))]
     (assoc db
            :adapters adapters
            :selected-path (some paths [(:selected-path db) live-path (:path (first adapters))])))))

(rf/reg-event-db
 ::select-adapter
 (fn [db [_ path]]
   (assoc db :selected-path path)))

(rf/reg-event-fx
 ::set-poll-dids
 ;; Polling DIDs is chosen on connect, so a running live source reconnects.
 (fn [{:keys [db]} [_ on?]]
   (let [{:keys [source state path]} (:status db)]
     (cond-> {:db (assoc db :poll-dids? on?)}
       (and (= :live source) (= :running state))
       (assoc :fx [[:dispatch [::switch-source {:type :live :path path :dids? on?}]]])))))

(rf/reg-event-db
 ::set-note
 (fn [db [_ note]]
   (assoc db :note note)))

(rf/reg-event-fx
 ::start-recording
 (fn [{:keys [db]} _]
   {:db (assoc db :request {:state :pending})
    :http {:method "POST"
           :url "/api/recording"
           :body {:action :start :note (:note db)}
           :on-success [::recording-accepted]
           :on-failure [::request-failed]}}))

(rf/reg-event-fx
 ::stop-recording
 (fn [{:keys [db]} _]
   {:db (assoc db :request {:state :pending})
    :http {:method "POST"
           :url "/api/recording"
           :body {:action :stop}
           :on-success [::recording-accepted]
           :on-failure [::request-failed]}}))

(rf/reg-event-db
 ::recording-accepted
 (fn [db _]
   (assoc db :request nil :note "")))

(rf/reg-event-fx
 ::switch-source
 ;; `source` is {:type :live :path ... :dids? ...}, {:type :replay :recording
 ;; name}, or {:type :none}.
 (fn [{:keys [db]} [_ source]]
   {:db (assoc db :request {:state :pending})
    :http {:method "POST"
           :url "/api/source"
           :body source
           :on-success [::source-accepted]
           :on-failure [::request-failed]}}))

(rf/reg-event-db
 ::source-accepted
 (fn [db _]
   (assoc db :request nil)))

(rf/reg-event-db
 ::request-failed
 (fn [db [_ {:keys [error]}]]
   (assoc db :request {:state :failed :error (or error :request-failed)})))

(rf/reg-event-db
 ::show-view
 (fn [db [_ view]]
   (assoc db :view view)))

(rf/reg-event-fx
 ::open-map
 ;; `log` is :stream or a recording's name, whose series is fetched unless it
 ;; is already loaded.
 (fn [{:keys [db]} [_ log]]
   (let [db (-> db
                (assoc :view :map)
                (update :map assoc :log log :window nil :cell nil))
         loaded? (and (= log (get-in db [:recording-log :name]))
                      (not= :failed (get-in db [:recording-log :state])))]
     (if (or (= :stream log) loaded?)
       {:db db}
       {:db (assoc db :recording-log {:name log :state :loading})
        :http {:url (str "/api/recordings/" (js/encodeURIComponent log) "/series")
               :on-success [::series-loaded log]
               :on-failure [::series-failed log]}}))))

(rf/reg-event-db
 ::series-loaded
 (fn [db [_ log data]]
   (cond-> db
     (= log (get-in db [:recording-log :name]))
     (assoc :recording-log {:name log :state :loaded :log data}))))

(rf/reg-event-db
 ::series-failed
 (fn [db [_ log {:keys [error]}]]
   (cond-> db
     (= log (get-in db [:recording-log :name]))
     (assoc :recording-log {:name log :state :failed :error (or error :request-failed)}))))

(rf/reg-event-db
 ::set-map-option
 ;; Changing an axis or the parameter clears the selected cell.
 (fn [db [_ option value]]
   (cond-> (assoc-in db [:map option] value)
     (#{:x :y :z} option) (assoc-in [:map :cell] nil))))

(rf/reg-event-db
 ::set-axis-text
 (fn [db [_ key text]]
   (-> db
       (assoc-in [:map :axis-text key] text)
       (assoc-in [:map :cell] nil))))

(rf/reg-event-db
 ::reset-axis
 (fn [db [_ key]]
   (-> db
       (update-in [:map :axis-text] dissoc key)
       (assoc-in [:map :cell] nil))))

(rf/reg-event-db
 ::add-filter
 ;; Starts with a warm engine, the usual first filter.
 (fn [db _]
   (update-in db [:map :filters] conj {:key :pid/coolant-temp :op :>= :value 75})))

(rf/reg-event-db
 ::set-filter
 (fn [db [_ i option value]]
   (assoc-in db [:map :filters i option] value)))

(rf/reg-event-db
 ::remove-filter
 (fn [db [_ i]]
   (update-in db [:map :filters] #(into (subvec % 0 i) (subvec % (inc i))))))

(rf/reg-event-db
 ::set-window
 (fn [db [_ window]]
   (assoc-in db [:map :window] window)))

(rf/reg-event-db
 ::select-cell
 ;; Selecting the selected cell clears it.
 (fn [db [_ cell]]
   (update-in db [:map :cell] #(when (not= % cell) cell))))
