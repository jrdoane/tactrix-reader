(ns tactrix-reader.engine-server.sources
  "Owns the running source and switches between replaying a recording, polling
  an adapter, and no source. Switches run one at a time on an agent, so a slow
  vehicle identification never overlaps another switch.

  Each switch stops the old source and emits `{:type :reset}`, so the hub
  drops the old source's data, then reports the new source with `:status` and
  `:info` events. Info has the same shape for every source:
  `{:source :adapter {...} :vehicle {...} :recording {...}}`, with only the
  keys that source knows."
  (:require [tactrix-reader.device :as device]))

(defn- adapter-description [path]
  (let [adapter (some #(when (some #{path} (:paths %)) %) (device/scan-devices))]
    (-> (select-keys adapter [:vendor :product :serial])
        (assoc :path path))))

(defn- start-live!
  "Checks vehicle power, reads the vehicle's identity, and starts polling.
  Returns the live source, or nil when it was not started."
  [emit path]
  (let [status #(emit (merge {:type :status :source :live :path path} %))]
    (if-not (seq path)
      (do (status {:state :failed :error :missing-path})
          nil)
      (let [_ (status {:state :connecting})
            vbat (device/read-vbat path)
            info {:type :info
                  :source :live
                  :adapter (cond-> (adapter-description path)
                             (:ok? vbat) (assoc :battery-volts (:volts vbat)))}]
        (cond
          (not (:ok? vbat))
          (do (emit info)
              (status {:state :failed :error (:error vbat)})
              nil)

          (not= :present (:state vbat))
          (do (emit info)
              (status {:state :failed :error :no-vehicle-power})
              nil)

          :else
          (do (status {:state :identifying})
              (emit (assoc info :vehicle (dissoc (device/read-vehicle-info path) :ok? :error :path)))
              (device/start-live-source! path {:emit emit})))))))

(defn- start-replay! [emit recording]
  (let [{:keys [recorded-at duration-ms vehicle adapter]} (device/recording-meta recording)]
    (emit {:type :info
           :source :replay
           :adapter (select-keys adapter [:product :firmware])
           :vehicle (select-keys vehicle [:description])
           :recording {:dir recording
                       :recorded-at recorded-at
                       :duration-ms duration-ms
                       :state (:state vehicle)}})
    (device/start-replay-source! recording {:emit emit})))

(defn- stop-current [{:keys [handle] :as state}]
  (when handle
    ((:stop! handle)))
  (assoc state :handle nil))

(defn- switch [{:keys [emit recording] :as state} source]
  (let [state (stop-current state)]
    (emit {:type :reset})
    (let [handle (try
                   (case (:type source)
                     :replay (start-replay! emit (or (:recording source) recording))
                     :live (start-live! emit (:path source))
                     :none (do (emit {:type :status :source :none :state :stopped})
                               nil))
                   (catch Throwable e
                     (emit {:type :status
                            :source (:type source)
                            :state :failed
                            :error :switch-failed
                            :message (.getMessage e)})
                     nil))]
      (assoc state :handle handle :source source))))

(defn switch!
  "Queues a switch to `source`: `{:type :replay}` (the manager's recording,
  unless `:recording` is given), `{:type :live :path device-path}`, or
  `{:type :none}`. Returns immediately; progress arrives as hub events."
  [manager source]
  (send-off manager switch source)
  nil)

(defn start-manager
  "Starts a manager that emits events with `emit` and switches to `source`.
  `recording` is the directory `{:type :replay}` replays."
  [{:keys [emit recording source]}]
  (doto (agent {:emit emit
                :recording recording
                :handle nil
                :source nil})
    (switch! source)))

(defn stop-manager
  "Stops the running source and waits for queued switches to finish."
  [manager]
  (send-off manager stop-current)
  (await manager))
