(ns tactrix-reader.engine-server.server
  "Pedestal routes: the dashboard page, its assets, and the SSE stream."
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [cognitect.transit :as transit]
            [io.pedestal.connector :as connector]
            [io.pedestal.environment :refer [dev-mode?]]
            [io.pedestal.http.jetty :as jetty]
            [io.pedestal.http.sse :as sse]
            [io.pedestal.service.resources :as resources]
            [tactrix-reader.engine-server.hub :as hub]))

(def ^:private content-security-policy
  "Everything comes from this server."
  "default-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'")

(def ^:private dev-content-security-policy
  "Also allows shadow-cljs hot reload, which evaluates code it receives over a
  websocket on port 9630."
  (str "default-src 'self'; script-src 'self' 'unsafe-eval'; "
       "connect-src 'self' ws://localhost:9630; "
       "object-src 'none'; base-uri 'none'; frame-ancestors 'none'"))

(defn ->transit
  "Encodes data as a single line of Transit JSON."
  [data]
  (let [out (java.io.ByteArrayOutputStream.)]
    (transit/write (transit/writer out :json) data)
    (.toString out "UTF-8")))

(defn- stream-interceptor
  "Streams Server-Sent Events: a `snapshot` event on connect, then `samples`
  and `status` events from the hub. Data is Transit JSON."
  [hub]
  {:name ::stream
   :enter (fn [context]
            (sse/start-stream
             (fn [event-channel _context]
               (let [subscription (hub/subscribe hub)]
                 (async/go-loop [message {:event :snapshot
                                          :data (hub/snapshot hub)}]
                   (if (and message
                            (async/>! event-channel {:name (name (:event message))
                                                     :data (->transit (:data message))}))
                     (recur (async/<! subscription))
                     (do
                       (hub/unsubscribe hub subscription)
                       (async/close! event-channel))))))
             context
             10
             16))})

(defn- index-handler [_request]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (slurp (io/resource "public/index.html"))})

(defn- snapshot-handler [hub]
  (fn [_request]
    {:status 200
     :headers {"Content-Type" "application/transit+json"}
     :body (->transit (hub/snapshot hub))}))

(defn create-server
  "Returns an unstarted Pedestal connector serving the dashboard for `hub`."
  [{:keys [host port hub]
    :or {host "localhost"}}]
  (-> (connector/default-connector-map host port)
      (connector/with-default-interceptors
        :secure-headers {:content-security-policy-settings (if dev-mode?
                                                              dev-content-security-policy
                                                              content-security-policy)})
      (connector/with-routes
        #{["/" :get index-handler :route-name ::index]
          ["/stream" :get (stream-interceptor hub) :route-name ::stream]
          ["/api/snapshot" :get (snapshot-handler hub) :route-name ::snapshot]}
        (resources/resource-routes {:resource-root "public/assets"
                                    :prefix "/assets"}))
      (jetty/create-connector {})))
