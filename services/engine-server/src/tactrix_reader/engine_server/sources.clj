(ns tactrix-reader.engine-server.sources
  "Owns the running source and switches between replaying a recording, polling
  an adapter, and no source. Switches run one at a time on an agent, so a slow
  vehicle identification never overlaps another switch.

  Each switch stops the old source and emits `{:type :reset}`, so the hub
  drops the old source's data, then reports the new source with `:status` and
  `:info` events. Info has the same shape for every source:
  `{:source :adapter {...} :vehicle {...} :trouble-codes {...} :recording {...}}`,
  with only the keys that source knows.

  While a live source runs, the manager can save what it receives as a new
  recording in the configured recordings directory, tagged with the vehicle
  the live source identified."
  (:require [clojure.java.io :as io]
            [tactrix-reader.device.adapter :as adapter]
            [tactrix-reader.device.config :as config]
            [tactrix-reader.device.iso15765 :as iso15765]
            [tactrix-reader.device.obd :as obd]
            [tactrix-reader.device.recording :as recording]
            [tactrix-reader.device.source :as source]
            [tactrix-reader.device.uds :as uds]
            [tactrix-reader.device.usb :as usb]))

(defn- adapter-description [path]
  (let [adapter (some #(when (some #{path} (:paths %)) %) (usb/scan-devices))]
    (-> (select-keys adapter [:vendor :product :serial])
        (assoc :path path))))

(defn- start-live!
  "Checks vehicle power, reads the vehicle's identity and trouble codes, and
  starts polling, with the DIDs `uds/scanned-dids` too when `dids?`. Returns
  the live source with the vehicle's identity under `:vehicle`, or nil when
  it was not started."
  [emit path dids?]
  (let [status #(emit (merge {:type :status :source :live :path path} %))]
    (if-not (seq path)
      (do (status {:state :failed :error :missing-path})
          nil)
      (let [_ (status {:state :connecting})
            vbat (adapter/read-vbat path)
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
          (let [_ (status {:state :identifying})
                [vehicle-info trouble-codes] (iso15765/with-channel
                                              path {}
                                              (fn [channel]
                                                [(obd/read-vehicle-info channel)
                                                 (obd/read-trouble-codes channel)]))]
            (emit (assoc info
                         :vehicle (dissoc vehicle-info :ok? :error :path)
                         :trouble-codes (dissoc trouble-codes :ok? :error :path)))
            (some-> (source/start-live-source! path (cond-> {:emit emit}
                                                      dids? (assoc :dids uds/scanned-dids)))
                    (assoc :vehicle (recording/vehicle-meta vehicle-info)))))))))

(defn- start-replay! [emit cfg recording]
  (let [{:keys [recorded-at duration-ms vehicle adapter note]} (recording/recording-meta recording)
        description (config/vehicle-description cfg vehicle)]
    (emit {:type :info
           :source :replay
           :adapter (select-keys adapter [:product :firmware])
           :vehicle (cond-> (dissoc vehicle :state)
                      description (assoc :description description))
           :recording {:dir (str recording)
                       :name (.getName (io/file recording))
                       :recorded-at recorded-at
                       :duration-ms duration-ms
                       :note (or note (:state vehicle))}})
    (source/start-replay-source! recording {:emit emit})))

(defn- stop-current [{:keys [handle] :as state}]
  (when handle
    ((:stop! handle)))
  (assoc state :handle nil))

(defn- switch [{:keys [emit recording] :as state} source]
  (let [state (stop-current state)]
    (emit {:type :reset})
    (let [handle (try
                   (case (:type source)
                     :replay (if-let [dir (or (:recording source) recording)]
                               (start-replay! emit (:config state) dir)
                               (do (emit {:type :status :source :replay :state :failed :error :no-recording})
                                   nil))
                     :live (start-live! emit (:path source) (:dids? source))
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
  "Queues a switch to `source`: `{:type :replay :recording dir}` (the
  manager's recording when `:recording` is absent), `{:type :live :path
  device-path}` (with `:dids? true` to poll `uds/scanned-dids` too), or
  `{:type :none}`. Returns immediately; progress arrives as hub events."
  [manager source]
  (send-off manager switch source)
  nil)

(defn- on-manager
  "Runs `f` with the manager's state after any queued switches, and returns
  its result, or `{:ok? false :error :timeout}`."
  [manager f]
  (let [result (promise)]
    (send-off manager (fn [state]
                        (deliver result (try (f state)
                                             (catch Throwable e
                                               {:ok? false :error :record-failed :message (.getMessage e)})))
                        state))
    (deref result 30000 {:ok? false :error :timeout})))

(defn start-recording!
  "Starts saving the live source's data as a new recording in the recordings
  directory, named from the date and time, with the vehicle's identity and
  `note` in its meta.edn. Returns `{:ok? true :name :dir}`, or `{:ok? false
  :error :not-live|:not-running|:already-recording}`. The live source emits
  `{:type :recording ...}` events as the recording starts and is saved."
  [manager {:keys [note]}]
  (on-manager manager
              (fn [{:keys [handle source] :as state}]
                (if-not (and handle (= :live (:type source)))
                  {:ok? false :error :not-live}
                  ((:start-recording! handle)
                   (str (io/file (get-in state [:config :recordings-dir]) (config/new-recording-name)))
                   (cond-> {}
                     (:vehicle handle) (assoc :vehicle (:vehicle handle))
                     (seq note) (assoc :note note)))))))

(defn stop-recording!
  "Finishes the live source's recording. Returns `{:ok? true ...}` with the
  recording's result, or `{:ok? false :error :not-recording}`."
  [manager]
  (on-manager manager
              (fn [{:keys [handle]}]
                (if-let [result (some-> (:stop-recording! handle) (apply []))]
                  (assoc result :ok? true)
                  {:ok? false :error :not-recording}))))

(defn start-manager
  "Starts a manager that emits events with `emit` and switches to `source`.
  `recording` is the directory `{:type :replay}` replays when it names none,
  and `config` (from `config/read-config`) gives the recordings directory and
  vehicle descriptions."
  [{:keys [emit recording source config]}]
  (doto (agent {:emit emit
                :recording recording
                :config config
                :handle nil
                :source nil})
    (switch! source)))

(defn stop-manager
  "Stops the running source, finishing any recording, and waits for queued
  switches to finish."
  [manager]
  (send-off manager stop-current)
  (await manager))
