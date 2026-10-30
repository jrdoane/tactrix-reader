(ns tactrix-reader.engine-server.hub-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is]]
            [tactrix-reader.engine-server.hub :as hub]))

(defn- take-within [channel ms]
  (async/alt!! channel ([message] message)
               (async/timeout ms) ::timeout))

(def ^:private rpm-1 {:type :sample :pid 0x0C :name :engine-rpm :value 650.0 :unit :rpm :timestamp-us 1})
(def ^:private rpm-2 {:type :sample :pid 0x0C :name :engine-rpm :value 655.0 :unit :rpm :timestamp-us 3})
(def ^:private coolant {:type :sample :pid 0x05 :name :coolant-temp :value 90 :unit :celsius :timestamp-us 2})

(deftest batches-the-latest-sample-of-each-pid
  (let [hub (hub/start-hub {:batch-ms 50})
        subscription (hub/subscribe hub)
        emit (hub/emit-fn hub)]
    (emit rpm-1)
    (emit coolant)
    (emit rpm-2)
    (is (= {:event :samples
            :data {:samples [(dissoc coolant :type) (dissoc rpm-2 :type)]
                   :signals []
                   :dids []}}
           (take-within subscription 1000)))
    (hub/stop-hub hub)))

(def ^:private gear-1 {:type :signal :signal :gear :value 2 :unit nil :can-id 0x141 :timestamp-us 1})
(def ^:private gear-2 {:type :signal :signal :gear :value 3 :unit nil :can-id 0x141 :timestamp-us 3})
(def ^:private brake {:type :signal :signal :brake :value true :unit nil :can-id 0x0D3 :timestamp-us 2})

(deftest batches-the-latest-value-of-each-signal
  (let [hub (hub/start-hub {:batch-ms 50})
        subscription (hub/subscribe hub)
        emit (hub/emit-fn hub)]
    (emit gear-1)
    (emit brake)
    (emit coolant)
    (emit gear-2)
    (is (= {:event :samples
            :data {:samples [(dissoc coolant :type)]
                   :signals [(dissoc brake :type) (dissoc gear-2 :type)]
                   :dids []}}
           (take-within subscription 1000)))
    (is (= [(dissoc brake :type) (dissoc gear-2 :type)]
           (:signals (hub/snapshot hub))))
    (emit {:type :reset})
    (is (= [] (:signals (:data (take-within subscription 1000)))))
    (hub/stop-hub hub)))

(def ^:private throttle-did-1 {:type :did :did 0x10DF :did-hex "10DF" :data [0x43] :timestamp-us 1
                               :name :throttle-position :matches 0x11 :value 13.6 :unit :percent})
(def ^:private throttle-did-2 {:type :did :did 0x10DF :did-hex "10DF" :data [0x44] :timestamp-us 3
                               :name :throttle-position :matches 0x11 :value 14.4 :unit :percent})
(def ^:private lambda-did {:type :did :did 0x10BE :did-hex "10BE" :data [0x80] :timestamp-us 2
                           :name :lambda :matches 0x34 :value 1.0 :unit :lambda})

(deftest batches-the-latest-reply-for-each-did
  (let [hub (hub/start-hub {:batch-ms 50})
        subscription (hub/subscribe hub)
        emit (hub/emit-fn hub)]
    (emit throttle-did-1)
    (emit lambda-did)
    (emit throttle-did-2)
    (is (= {:event :samples
            :data {:samples []
                   :signals []
                   :dids [(dissoc lambda-did :type) (dissoc throttle-did-2 :type)]}}
           (take-within subscription 1000)))
    (is (= [(dissoc lambda-did :type) (dissoc throttle-did-2 :type)]
           (:dids (hub/snapshot hub))))
    (emit {:type :reset})
    (is (= [] (:dids (:data (take-within subscription 1000)))))
    (hub/stop-hub hub)))

(deftest forwards-status-and-keeps-a-snapshot
  (let [hub (hub/start-hub {:batch-ms 50})
        subscription (hub/subscribe hub)
        emit (hub/emit-fn hub)]
    (emit {:type :status :source :replay :state :running})
    (is (= {:event :status
            :data {:source :replay :state :running}}
           (take-within subscription 1000)))
    (emit coolant)
    (is (= :samples (:event (take-within subscription 1000))))
    (is (= {:status {:source :replay :state :running}
            :info nil
            :samples [(dissoc coolant :type)]
            :signals []
            :dids []}
           (hub/snapshot hub)))
    (hub/stop-hub hub)))

(deftest forwards-info-and-clears-everything-on-reset
  (let [hub (hub/start-hub {:batch-ms 50})
        subscription (hub/subscribe hub)
        emit (hub/emit-fn hub)
        info {:source :live :vehicle {:vin "TESTVIN0123456789"}}]
    (emit (assoc info :type :info))
    (is (= {:event :info :data info}
           (take-within subscription 1000)))
    (emit coolant)
    (is (= :samples (:event (take-within subscription 1000))))
    (emit rpm-1)
    (emit {:type :reset})
    (is (= {:event :snapshot
            :data {:status nil :info nil :samples [] :signals [] :dids []}}
           (take-within subscription 1000)))
    (is (= ::timeout (take-within subscription 200))
        "samples pending at the reset are dropped")
    (is (= {:status nil :info nil :samples [] :signals [] :dids []}
           (hub/snapshot hub)))
    (hub/stop-hub hub)))

(deftest unsubscribed-channels-are-closed
  (let [hub (hub/start-hub)
        subscription (hub/subscribe hub)]
    (hub/unsubscribe hub subscription)
    (is (nil? (take-within subscription 1000)))
    (hub/stop-hub hub)))
