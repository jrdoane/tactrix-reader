(ns tactrix-reader.dashboard.http
  "Calls the server's API with Transit and dispatches each reply into
  re-frame."
  (:require [cognitect.transit :as transit]
            [re-frame.core :as rf]))

(def ^:private reader (transit/reader :json))

(def ^:private writer (transit/writer :json))

(rf/reg-fx
 :http
 ;; Dispatches `on-success` or `on-failure` with the decoded reply appended.
 ;; A failure that has no Transit reply gets `{:error :request-failed}`.
 (fn [{:keys [method url body on-success on-failure]}]
   (-> (js/fetch url (clj->js (cond-> {:method (or method "GET")
                                       :headers {"Accept" "application/transit+json"}}
                                body (-> (assoc :body (transit/write writer body))
                                         (assoc-in [:headers "Content-Type"] "application/transit+json")))))
       (.then (fn [^js response]
                (.then (.text response)
                       (fn [text]
                         (rf/dispatch (conj (if (.-ok response) on-success on-failure)
                                            (transit/read reader text)))))))
       (.catch (fn [^js error]
                 (rf/dispatch (conj on-failure {:error :request-failed
                                                :message (.-message error)})))))))
