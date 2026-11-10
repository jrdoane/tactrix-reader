(ns tactrix-reader.engine-server.hub
  "Collects source events and fans them out to stream subscribers.

  Samples, signals, DID replies, and CAN frames are batched: every
  `batch-ms`, subscribers get the latest sample of each PID, the latest
  value of each inferred signal, the latest reply for each UDS data
  identifier, and the latest frame of each CAN ID that arrived since the
  last batch. A frame comes with how many of its ID have arrived, when the
  first did, and, in a batch, which bits changed between any two of them
  since the last batch, so a change shorter than a batch is not lost. Status, info, recording, and replay
  position events are forwarded immediately. A `:reset` event, sent when the source changes, clears
  everything kept so far and sends subscribers an empty snapshot. A subscriber
  that falls behind drops old messages instead of slowing the source."
  (:require [clojure.core.async :as async]))

(defn- sorted-samples [samples-by-key]
  (mapv val (sort-by key samples-by-key)))

(def ^:private empty-state
  {:status nil
   :info nil
   :recording nil
   :position nil
   :samples {}
   :signals {}
   :dids {}
   :frames {}})

(def ^:private no-pending
  {:samples {} :signals {} :dids {} :frames {}})

(defn- batch [{:keys [samples signals dids frames]}]
  {:samples (sorted-samples samples)
   :signals (sorted-samples signals)
   :dids (sorted-samples dids)
   :frames (sorted-samples frames)})

(defn- changed-bits
  "For each byte of `data`, the bits that differ from `previous`, as a mask,
  ORed with `earlier`, the changes already seen. A frame with no previous
  one, or of another length, counts as unchanged."
  [earlier previous data]
  (let [changes (if (= (count previous) (count data))
                  (mapv bit-xor previous data)
                  (vec (repeat (count data) 0)))]
    (if (= (count earlier) (count changes))
      (mapv bit-or earlier changes)
      changes)))

(defn- next-frame
  "The frame kept for a CAN ID once `event` arrives after `previous`."
  [previous {:keys [id data timestamp-us]}]
  {:id id
   :data data
   :timestamp-us timestamp-us
   :first-us (:first-us previous timestamp-us)
   :count (inc (:count previous 0))})

(defn- state->snapshot [{:keys [status info recording position] :as state}]
  (merge {:status status
          :info info
          :recording recording
          :position position}
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

           (= :did (:type event))
           (let [reply (dissoc event :type)]
             (swap! state assoc-in [:dids (:did reply)] reply)
             (recur (assoc-in pending [:dids (:did reply)] reply) ticker))

           (= :frame (:type event))
           (let [id (:id event)
                 previous (get-in @state [:frames id])
                 frame (next-frame previous event)]
             (swap! state assoc-in [:frames id] frame)
             (recur (update-in pending [:frames id]
                               (fn [waiting]
                                 (assoc frame :changed (changed-bits (:changed waiting) (:data previous) (:data frame)))))
                    ticker))

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

           (= :recording (:type event))
           (let [recording (dissoc event :type)]
             (swap! state assoc :recording recording)
             (async/>! out {:event :recording
                            :data recording})
             (recur pending ticker))

           (= :position (:type event))
           (let [position (dissoc event :type)]
             (swap! state assoc :position position)
             (async/>! out {:event :position
                            :data position})
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
  "The latest status, info, and recording event, and the latest sample of every PID, value of
  every signal, reply for every DID, and frame of every CAN ID seen since the
  last reset."
  [hub]
  (state->snapshot @(:state hub)))

(defn subscribe
  "Returns a channel of `{:event :samples|:status|:info|:recording|:position|:snapshot, :data ...}`
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
