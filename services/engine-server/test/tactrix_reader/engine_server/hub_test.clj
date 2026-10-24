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
            :data {:samples [(dissoc coolant :type) (dissoc rpm-2 :type)]}}
           (take-within subscription 1000)))
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
            :samples [(dissoc coolant :type)]}
           (hub/snapshot hub)))
    (hub/stop-hub hub)))

(deftest unsubscribed-channels-are-closed
  (let [hub (hub/start-hub)
        subscription (hub/subscribe hub)]
    (hub/unsubscribe hub subscription)
    (is (nil? (take-within subscription 1000)))
    (hub/stop-hub hub)))
