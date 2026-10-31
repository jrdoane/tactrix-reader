(ns tactrix-reader.dashboard.subs
  (:require [re-frame.core :as rf]))

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
