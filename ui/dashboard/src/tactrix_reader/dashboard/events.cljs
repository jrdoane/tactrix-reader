(ns tactrix-reader.dashboard.events
  (:require [re-frame.core :as rf]
            [tactrix-reader.dashboard.http]
            [tactrix-reader.dashboard.stream]))

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
   ;; Adapters the server detected, and the one chosen to connect to.
   :adapters nil
   :selected-path nil
   ;; The last API request: nil, {:state :pending}, or {:state :failed :error ...}.
   :request nil})

(defn- index-by-name [samples]
  (into {} (map (juxt :name identity)) samples))

(defn- index-by-signal [signals]
  (into {} (map (juxt :signal identity)) signals))

(defn- can-ids [samples]
  (into {} (keep (fn [{:keys [name can-id]}] (when can-id [name can-id]))) samples))

(rf/reg-event-fx
 ::initialize
 (fn [_ _]
   {:db initial-db
    :stream/connect "/stream"
    :fx [[:dispatch [::refresh-adapters]]]}))

(rf/reg-event-db
 :stream/connected
 (fn [db _]
   (assoc db :connection :connected)))

(rf/reg-event-db
 :stream/disconnected
 (fn [db _]
   (assoc db :connection :disconnected)))

(rf/reg-event-db
 :stream/snapshot
 ;; Sent on connect, and again with empty data whenever the source changes.
 (fn [db [_ {:keys [status info samples signals]}]]
   (assoc db
          :status status
          :info info
          :firmware (:firmware status)
          :samples (index-by-name samples)
          :signals (index-by-signal signals)
          :can-ids (can-ids samples))))

(rf/reg-event-db
 :stream/samples
 (fn [db [_ {:keys [samples signals]}]]
   (-> db
       (update :samples merge (index-by-name samples))
       (update :signals merge (index-by-signal signals))
       (update :can-ids merge (can-ids samples)))))

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
 ::switch-source
 ;; `source` is {:type :live :path ...}, {:type :replay}, or {:type :none}.
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
