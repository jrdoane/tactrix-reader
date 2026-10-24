(ns tactrix-reader.engine-server.main
  "Starts the engine dashboard server with a live or replay source."
  (:require [io.pedestal.connector :as connector]
            [tactrix-reader.device :as device]
            [tactrix-reader.engine-server.hub :as hub]
            [tactrix-reader.engine-server.server :as server]))

(def default-recording "../../lib/device/recordings/2026-10-24-idle")

(defn- start-source [{:keys [type recording path]} emit]
  (case type
    :replay (device/start-replay-source! recording {:emit emit})
    :live (device/start-live-source! (or path (some :path (device/scan-devices)))
                                     {:emit emit})))

(defn start-system
  "Starts the hub, the source, and the HTTP server. `source` is
  `{:type :replay :recording dir}` or `{:type :live :path device-path}`."
  [{:keys [source port host]}]
  (let [hub (hub/start-hub)
        source-handle (start-source source (hub/emit-fn hub))
        server (connector/start! (server/create-server {:host host :port port :hub hub}))]
    {:hub hub
     :source source-handle
     :server server}))

(defn stop-system [{:keys [hub source server]}]
  (connector/stop! server)
  ((:stop! source))
  (hub/stop-hub hub))

(defn- parse-source [[mode arg]]
  (case mode
    "live" {:type :live :path arg}
    {:type :replay :recording (or arg default-recording)}))

(defn -main
  "Usage: clojure -M:run [replay [recording-dir] | live [device-path]]

  Replays the committed idle recording when no arguments are given. The port
  defaults to 8080 and can be set with the PORT environment variable."
  [& args]
  (let [source (parse-source args)
        port (parse-long (or (System/getenv "PORT") "8080"))
        system (start-system {:source source :port port})]
    (.addShutdownHook (Runtime/getRuntime) (Thread. #(stop-system system)))
    (println (str "Engine dashboard on http://localhost:" port " (" (name (:type source)) ")"))
    @(promise)))
