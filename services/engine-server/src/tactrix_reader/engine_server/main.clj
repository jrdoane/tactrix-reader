(ns tactrix-reader.engine-server.main
  "Starts the engine dashboard server with a live or replay source."
  (:require [io.pedestal.connector :as connector]
            [tactrix-reader.device.config :as config]
            [tactrix-reader.device.recording :as recording]
            [tactrix-reader.device.usb :as usb]
            [tactrix-reader.engine-server.hub :as hub]
            [tactrix-reader.engine-server.server :as server]
            [tactrix-reader.engine-server.sources :as sources])
  (:import (org.eclipse.jetty.server Server ServerConnector)))

(defn start-system
  "Starts the hub, the source manager, and the HTTP server. `source` is the
  first source: `{:type :replay :recording dir}`, `{:type :live :path
  device-path}`, or `{:type :none}`. `config` defaults to
  `config/read-config`; its `:recordings-dir` is where the dashboard lists
  recordings and saves new ones. `port` 0 picks a free port; the port the
  server listens on is in the result's `:port`."
  [{:keys [source port host] :as opts}]
  (let [cfg (or (:config opts) (config/read-config))
        hub (hub/start-hub)
        manager (sources/start-manager {:emit (hub/emit-fn hub)
                                        :recording (:recording source)
                                        :config cfg
                                        :source source})
        jetty (promise)
        server (connector/start! (server/create-server {:host host :port port :hub hub
                                                        :sources manager :config cfg
                                                        :configurator #(doto % (->> (deliver jetty)))}))]
    {:hub hub
     :sources manager
     :server server
     :port (.getLocalPort ^ServerConnector (first (.getConnectors ^Server @jetty)))}))

(defn stop-system [{:keys [hub sources server]}]
  (connector/stop! server)
  (sources/stop-manager sources)
  (hub/stop-hub hub))

(defn- parse-source [[mode arg]]
  (case mode
    "live" {:type :live :path (or arg (some :path (usb/scan-devices)))}
    (if-let [dir (if arg
                   (str (config/resolve-recording-dir arg))
                   (:dir (first (recording/list-recordings (config/recordings-dir)))))]
      {:type :replay :recording dir}
      {:type :none})))

(defn -main
  "Usage: clojure -M:run [replay [recording] | live [device-path]]

  A recording is a name in ~/.tactrix-reader/recordings or a path. With no
  arguments, replays the newest recording there, or starts with no source if
  there is none. The port defaults to 8080 and can be set with the PORT
  environment variable."
  [& args]
  (let [source (parse-source args)
        port (parse-long (or (System/getenv "PORT") "8080"))
        system (start-system {:source source :port port})]
    (.addShutdownHook (Runtime/getRuntime) (Thread. #(stop-system system)))
    (println (str "Engine dashboard on http://localhost:" (:port system) " (" (name (:type source))
                  (some->> (:recording source) (str " ")) ")"))
    @(promise)))
