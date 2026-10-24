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
