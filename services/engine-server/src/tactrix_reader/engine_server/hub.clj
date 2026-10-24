(ns tactrix-reader.engine-server.hub
  "Collects source events and fans them out to stream subscribers.

  Samples are batched: every `batch-ms`, subscribers get the latest sample of
  each PID that changed. Status events are forwarded immediately. A subscriber
  that falls behind drops old messages instead of slowing the source."
  (:require [clojure.core.async :as async]))

(defn- sorted-samples [samples-by-pid]
  (mapv val (sort-by key samples-by-pid)))

(defn start-hub
  ([]
   (start-hub {}))
  ([{:keys [batch-ms]
     :or {batch-ms 100}}]
   (let [in (async/chan (async/sliding-buffer 4096))
         out (async/chan (async/sliding-buffer 64))
         state (atom {:status nil
                      :samples {}})]
     (async/go-loop [pending {}
                     ticker (async/timeout batch-ms)]
       (let [[event port] (async/alts! [in ticker])]
         (cond
           (= port ticker)
           (do
             (when (seq pending)
               (async/>! out {:event :samples
                              :data {:samples (sorted-samples pending)}}))
             (recur {} (async/timeout batch-ms)))

           (nil? event)
           (async/close! out)

           (= :sample (:type event))
           (let [sample (dissoc event :type)]
             (swap! state assoc-in [:samples (:pid sample)] sample)
             (recur (assoc pending (:pid sample) sample) ticker))

           (= :status (:type event))
           (let [status (dissoc event :type)]
             (swap! state assoc :status status)
             (async/>! out {:event :status
                            :data status})
             (recur pending ticker))

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
  "The latest status and the latest sample of every PID seen so far."
  [hub]
  (let [{:keys [status samples]} @(:state hub)]
    {:status status
     :samples (sorted-samples samples)}))

(defn subscribe
  "Returns a channel of `{:event :samples|:status, :data ...}` messages."
  [hub]
  (let [subscription (async/chan (async/sliding-buffer 64))]
    (async/tap (:mult hub) subscription)
    subscription))

(defn unsubscribe [hub subscription]
  (async/untap (:mult hub) subscription)
  (async/close! subscription))

(defn stop-hub [hub]
  (async/close! (:in hub)))
