(ns tactrix-reader.engine-server.main
  "Starts the engine dashboard server with a live or replay source."
  (:require [io.pedestal.connector :as connector]
            [tactrix-reader.device :as device]
            [tactrix-reader.engine-server.hub :as hub]
            [tactrix-reader.engine-server.server :as server]
            [tactrix-reader.engine-server.sources :as sources]))

(def default-recording "../../lib/device/recordings/2026-10-24-idle")

(defn start-system
  "Starts the hub, the source manager, and the HTTP server. `source` is the
  first source: `{:type :replay :recording dir}` or
  `{:type :live :path device-path}`. The dashboard can switch to another
  adapter or back to replaying `recording`, which defaults to `source`'s."
  [{:keys [source recording port host]}]
  (let [hub (hub/start-hub)
        manager (sources/start-manager {:emit (hub/emit-fn hub)
                                        :recording (or recording (:recording source) default-recording)
                                        :source source})
        server (connector/start! (server/create-server {:host host :port port :hub hub :sources manager}))]
    {:hub hub
     :sources manager
     :server server}))

(defn stop-system [{:keys [hub sources server]}]
  (connector/stop! server)
  (sources/stop-manager sources)
  (hub/stop-hub hub))

(defn- parse-source [[mode arg]]
  (case mode
    "live" {:type :live :path (or arg (some :path (device/scan-devices)))}
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
