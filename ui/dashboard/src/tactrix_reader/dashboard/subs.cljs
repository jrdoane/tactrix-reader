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
