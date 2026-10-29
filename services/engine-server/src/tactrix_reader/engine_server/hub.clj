(ns tactrix-reader.engine-server.hub
  "Collects source events and fans them out to stream subscribers.

  Samples and signals are batched: every `batch-ms`, subscribers get the
  latest sample of each PID and the latest value of each inferred signal that
  arrived since the last batch. Status and info events are forwarded
  immediately. A
  `:reset` event, sent when the source changes, clears everything kept so far
  and sends subscribers an empty snapshot. A subscriber that falls behind drops
  old messages instead of slowing the source."
  (:require [clojure.core.async :as async]))

(defn- sorted-samples [samples-by-key]
  (mapv val (sort-by key samples-by-key)))

(def ^:private empty-state
  {:status nil
   :info nil
   :samples {}
   :signals {}})

(def ^:private no-pending
  {:samples {} :signals {}})

(defn- batch [{:keys [samples signals]}]
  {:samples (sorted-samples samples)
   :signals (sorted-samples signals)})

(defn- state->snapshot [{:keys [status info] :as state}]
  (merge {:status status
          :info info}
         (batch state)))

(defn start-hub
  ([]
   (start-hub {}))
  ([{:keys [batch-ms]
     :or {batch-ms 100}}]
   (let [in (async/chan (async/sliding-buffer 4096))
         out (async/chan (async/sliding-buffer 64))
         state (atom empty-state)]
     (async/go-loop [pending no-pending
                     ticker (async/timeout batch-ms)]
       (let [[event port] (async/alts! [in ticker])]
         (cond
           (= port ticker)
           (do
             (when (not= no-pending pending)
               (async/>! out {:event :samples
                              :data (batch pending)}))
             (recur no-pending (async/timeout batch-ms)))

           (nil? event)
           (async/close! out)

           (= :sample (:type event))
           (let [sample (dissoc event :type)]
             (swap! state assoc-in [:samples (:pid sample)] sample)
             (recur (assoc-in pending [:samples (:pid sample)] sample) ticker))

           (= :signal (:type event))
           (let [signal (dissoc event :type)]
             (swap! state assoc-in [:signals (:signal signal)] signal)
             (recur (assoc-in pending [:signals (:signal signal)] signal) ticker))

           (= :status (:type event))
           (let [status (dissoc event :type)]
             (swap! state assoc :status status)
             (async/>! out {:event :status
                            :data status})
             (recur pending ticker))

           (= :info (:type event))
           (let [info (dissoc event :type)]
             (swap! state assoc :info info)
             (async/>! out {:event :info
                            :data info})
             (recur pending ticker))

           (= :reset (:type event))
           (do
             (reset! state empty-state)
             (async/>! out {:event :snapshot
                            :data (state->snapshot empty-state)})
             (recur no-pending ticker))

           :else
           (recur pending ticker))))
     {:in in
      :mult (async/mult out)
      :state state})))

(defn emit-fn
  "Returns a function a source can call with each event."
  [hub]
  (fn [event]
    (async/put! (:in hub) event)))

(defn snapshot
  "The latest status and info, and the latest sample of every PID and value
  of every signal seen since the last reset."
  [hub]
  (state->snapshot @(:state hub)))

(defn subscribe
  "Returns a channel of `{:event :samples|:status|:info|:snapshot, :data ...}`
  messages."
  [hub]
  (let [subscription (async/chan (async/sliding-buffer 64))]
    (async/tap (:mult hub) subscription)
    subscription))

(defn unsubscribe [hub subscription]
  (async/untap (:mult hub) subscription)
  (async/close! subscription))

(defn stop-hub [hub]
  (async/close! (:in hub)))
