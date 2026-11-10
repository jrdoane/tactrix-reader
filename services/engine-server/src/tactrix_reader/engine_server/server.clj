(ns tactrix-reader.engine-server.server
  "Pedestal routes: the dashboard page, its assets, the SSE stream, and the
  API that describes the values to show, lists adapters and recordings, gives
  a recording's values as time series, switches the source, starts and stops
  recording, and pauses and seeks a replay."
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cognitect.transit :as transit]
            [io.pedestal.connector :as connector]
            [io.pedestal.environment :refer [dev-mode?]]
            [io.pedestal.http.jetty :as jetty]
            [io.pedestal.http.sse :as sse]
            [io.pedestal.service.resources :as resources]
            [tactrix-reader.device.catalog :as catalog]
            [tactrix-reader.device.config :as config]
            [tactrix-reader.device.recording :as recording]
            [tactrix-reader.device.series :as series]
            [tactrix-reader.device.usb :as usb]
            [tactrix-reader.engine-server.hub :as hub]
            [tactrix-reader.engine-server.sources :as sources]))

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
  "Streams Server-Sent Events: a `snapshot` event on connect, then `samples`,
  `status`, `info`, `recording`, `position`, and (after a source switch) `snapshot`
  events from the hub. Data is Transit JSON."
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

(defn- transit-response [status data]
  {:status status
   :headers {"Content-Type" "application/transit+json"}
   :body (->transit data)})

(defn- snapshot-handler [hub]
  (fn [_request]
    (transit-response 200 (hub/snapshot hub))))

(def ^:private local-hosts #{"localhost" "127.0.0.1" "::1" "[::1]"})

(def ^:private local-api-guard
  "Switching the source sends traffic on the vehicle bus, so the API only
  answers requests addressed to this machine by name (which defeats DNS
  rebinding). Requests with a body must be Transit, which a cross-site form
  cannot send and a cross-site script cannot send without a CORS preflight
  that this server never approves."
  {:name ::local-api-guard
   :enter (fn [{:keys [request] :as context}]
            ;; :server-name comes from the Host header, or :authority in HTTP/2.
            (let [host (:server-name request)
                  content-type (str (get-in request [:headers "content-type"]))]
              (cond
                (not (local-hosts host))
                (assoc context :response (transit-response 403 {:error :non-local-host}))

                (and (= :post (:request-method request))
                     (not (str/starts-with? content-type "application/transit+json")))
                (assoc context :response (transit-response 415 {:error :unsupported-content-type}))

                :else
                context)))})

(defn- adapters-handler [_request]
  (transit-response 200 {:adapters (mapv #(select-keys % [:vendor :product :serial :path :paths])
                                         (usb/scan-devices))}))

(defn- catalog-handler
  "How to show each PID, inferred signal, and DID, from `catalog/catalog`."
  [_request]
  (transit-response 200 (catalog/catalog)))

(defn- recordings
  "The recordings in the configured directory, each vehicle with its
  description from config.edn."
  [cfg]
  (mapv (fn [{:keys [vehicle] :as entry}]
          (let [description (config/vehicle-description cfg vehicle)]
            (cond-> (dissoc entry :counts)
              description (assoc-in [:vehicle :description] description))))
        (recording/list-recordings (:recordings-dir cfg))))

(defn- recordings-handler [cfg]
  (fn [_request]
    (transit-response 200 {:dir (str (:recordings-dir cfg))
                           :recordings (recordings cfg)})))

(defn- series-handler
  "A recording's values as time series, from `series/recording-series`, for
  the dashboard's map. Only names `list-recordings` gives are accepted. The
  last response is kept, since a long drive takes seconds to decode, unless
  the recording is still being made."
  [cfg]
  (let [cache (atom nil)]
    (fn [request]
      (let [name (get-in request [:path-params :name])
            entry (some #(when (= name (:name %)) %)
                        (recording/list-recordings (:recordings-dir cfg)))]
        (if-not entry
          (transit-response 404 {:error :unknown-recording})
          (let [raw (io/file (:dir entry) "raw.bin")
                key [(:dir entry) (.length raw) (.lastModified raw)]]
            (if (= key (:key @cache))
              (:response @cache)
              (let [result (series/recording-series (:dir entry))
                    response (if (:ok? result)
                               (transit-response 200 (-> result (dissoc :ok?) (assoc :name name)))
                               (transit-response 422 (select-keys result [:error])))]
                (when-not (:in-progress? entry)
                  (reset! cache {:key key :response response}))
                response))))))))

(defn- requested-source
  "Turns a request body into a source for `sources/switch!`, or an error. Only
  paths of detected adapters and names of listed recordings are accepted, so a
  request cannot make the server write adapter commands to any other file or
  read any other directory."
  [cfg {:keys [type path recording dids?] :as body}]
  (cond
    (not (map? body)) {:error :invalid-body}
    (= :none type) {:type :none}

    (= :replay type)
    (if-let [dir (some #(when (= recording (:name %)) (:dir %))
                       (recording/list-recordings (:recordings-dir cfg)))]
      {:type :replay :recording dir}
      {:error :unknown-recording})

    (not= :live type) {:error :unknown-source-type}

    (some #{path} (mapcat :paths (usb/scan-devices)))
    (cond-> {:type :live :path path}
      (true? dids?) (assoc :dids? true))

    :else {:error :unknown-adapter}))

(defn- source-handler [manager cfg]
  (fn [request]
    ;; Pedestal's default body-params interceptor has parsed the Transit body.
    (let [source (requested-source cfg (:transit-params request))]
      (if (:error source)
        (transit-response 400 source)
        (do (sources/switch! manager source)
            (transit-response 202 {:source source}))))))

(def ^:private recording-statuses
  {:not-live 409
   :not-running 409
   :already-recording 409
   :not-recording 409
   :timeout 503})

(defn- recording-handler
  "Starts (`{:action :start :note \"...\"}`) or stops (`{:action :stop}`)
  recording the live source."
  [manager]
  (fn [request]
    (let [{:keys [action note]} (:transit-params request)
          result (case action
                   :start (sources/start-recording! manager {:note (when (string? note) note)})
                   :stop (sources/stop-recording! manager)
                   {:ok? false :error :unknown-action})]
      (if (:ok? result)
        (transit-response 202 (select-keys result [:name :dir :duration-ms :stopped-by]))
        (transit-response (get recording-statuses (:error result) 400)
                          (select-keys result [:error :message]))))))

(defn- replay-handler
  "Controls the replay: `{:action :pause}`, `{:action :resume}`, `{:action
  :seek :position-ms n}`, or `{:action :speed :speed x}`."
  [manager]
  (fn [request]
    (let [command (:transit-params request)
          result (if (map? command)
                   (sources/control-replay! manager (select-keys command [:action :position-ms :speed]))
                   {:ok? false :error :invalid-command})]
      (if (:ok? result)
        (transit-response 202 {})
        (transit-response (case (:error result)
                            :not-replaying 409
                            :timeout 503
                            400)
                          (select-keys result [:error]))))))

(defn create-server
  "Returns an unstarted Pedestal connector serving the dashboard for `hub`,
  whose source the `sources` manager switches. `config` (from
  `config/read-config`) gives the recordings directory. `configurator`, if
  given, is called with the Jetty server before it starts and returns it."
  [{:keys [host port hub sources config configurator]
    :or {host "localhost"
         configurator identity}}]
  (-> (connector/default-connector-map host port)
      (connector/with-default-interceptors
        :secure-headers {:content-security-policy-settings (if dev-mode?
                                                              dev-content-security-policy
                                                              content-security-policy)})
      (connector/with-routes
        #{["/" :get index-handler :route-name ::index]
          ["/stream" :get (stream-interceptor hub) :route-name ::stream]
          ["/api/snapshot" :get (snapshot-handler hub) :route-name ::snapshot]
          ["/api/catalog" :get [local-api-guard catalog-handler] :route-name ::catalog]
          ["/api/adapters" :get [local-api-guard adapters-handler] :route-name ::adapters]
          ["/api/source" :post [local-api-guard (source-handler sources config)] :route-name ::source]
          ["/api/recordings" :get [local-api-guard (recordings-handler config)] :route-name ::recordings]
          ["/api/recordings/:name/series" :get [local-api-guard (series-handler config)] :route-name ::series]
          ["/api/recording" :post [local-api-guard (recording-handler sources)] :route-name ::recording]
          ["/api/replay" :post [local-api-guard (replay-handler sources)] :route-name ::replay]}
        (resources/resource-routes {:resource-root "public/assets"
                                    :prefix "/assets"}))
      (jetty/create-connector {:container-options {:configurator configurator}})))
