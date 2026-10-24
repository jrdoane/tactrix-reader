(ns tactrix-reader.dashboard.events
  (:require [re-frame.core :as rf]
            [tactrix-reader.dashboard.stream]))

(def initial-db
  {:connection :connecting
   :status nil
   ;; Latest sample of each PID, keyed by name such as :engine-rpm.
   :samples {}})

(defn- index-by-name [samples]
  (into {} (map (juxt :name identity)) samples))

(rf/reg-event-fx
 ::initialize
 (fn [_ _]
   {:db initial-db
    :stream/connect "/stream"}))

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
 (fn [db [_ {:keys [status samples]}]]
   (assoc db
          :status status
          :samples (index-by-name samples))))

(rf/reg-event-db
 :stream/samples
 (fn [db [_ {:keys [samples]}]]
   (update db :samples merge (index-by-name samples))))

(rf/reg-event-db
 :stream/status
 (fn [db [_ status]]
   (assoc db :status status)))
