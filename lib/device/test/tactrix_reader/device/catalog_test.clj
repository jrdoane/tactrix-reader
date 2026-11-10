(ns tactrix-reader.device.catalog-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.walk :as walk]
            [tactrix-reader.device.broadcast :as broadcast]
            [tactrix-reader.device.can :as can]
            [tactrix-reader.device.catalog :as catalog]
            [tactrix-reader.device.pids :as pids]
            [tactrix-reader.device.uds :as uds]))

(defn- by-name [kind]
  (into {} (comp (filter (comp #{kind} :kind)) (map (juxt :name identity)))
        (:signals (catalog/catalog))))

(deftest describes-every-polled-pid
  (let [entries (by-name :pid)]
    (doseq [pid (distinct can/default-poll-schedule)
            :let [pid-name (:name (get pids/mode-01-pids pid))]]
      (is (:polled? (get entries pid-name)) (str pid-name)))
    (is (= {:kind :pid :name :engine-rpm :pid 0x0C :label "RPM" :group :air :unit :rpm
            :polled? true :can-id 0x140 :bits [0 0 0xFF 0x3F]}
           (:engine-rpm entries)))
    (is (= [{:part :lambda :unit :lambda}] (:parts (:wideband-oxygen-sensor-1-current entries))))
    (is (false? (:tile? (:monitor-status entries))))
    ;; A PID with no display entry is labelled from its name.
    (is (= {:kind :pid :name :absolute-load :pid 0x43 :label "Absolute load" :group :other :unit :percent}
           (:absolute-load entries)))
    (is (not-any? #(re-find #"^supported-pids" (name %)) (keys entries)))))

(deftest describes-every-inferred-signal
  (let [entries (by-name :signal)]
    (is (= (set (for [[_ signals] broadcast/inferred-signals, {:keys [signal]} signals] signal))
           (set (keys entries))))
    (is (= {:kind :signal :name :gear :can-id 0x141 :bits [0 0 0 0 0 0 0x07]
            :label "Gear" :group :driver :format :gear
            :override {:signal :reverse :text "R"}}
           (:gear entries)))
    ;; An override names a signal that exists.
    (is (every? entries (keep (comp :signal :override) (vals entries))))
    (is (= {:kind :signal :name :fuel-gauge :can-id 0x156 :bits [0 0 0 0 0 0 0 0xFF]
            :label "Fuel gauge" :group :counters :raw? true}
           (:fuel-gauge entries)))
    ;; Each part of a value that is a map has its own bits.
    (is (= [{:part :front-left :label "Front left" :bits [0xFF 0xFF]}
            {:part :front-right :label "Front right" :bits [0 0 0xFF 0xFF]}
            {:part :rear-left :label "Rear left" :bits [0 0 0 0 0xFF 0xFF]}
            {:part :rear-right :label "Rear right" :bits [0 0 0 0 0 0 0xFF 0xFF]}]
           (:parts (:wheel-speeds entries))))))

(deftest values-decoded-from-one-id-read-different-bits
  ;; A display colours each bit of a frame by the value it belongs to.
  (doseq [[can-id entries] (group-by :can-id (filter :can-id (:signals (catalog/catalog))))
          :let [masks (map :bits entries)]]
    (is (every? seq masks) (format "%03X" can-id))
    (is (every? zero? (for [[a & others] (take-while seq (iterate rest masks))
                            b others
                            overlap (map bit-and a b)]
                        overlap))
        (format "%03X" can-id))))

(deftest every-group-and-primary-name-exists
  (let [{:keys [sections groups primary signals]} (catalog/catalog)
        group-ids (set (map (juxt :section :id) groups))]
    (is (every? (set (map :id sections)) (map :section groups)))
    (doseq [{:keys [kind group name]} signals]
      (is (contains? group-ids [(if (= :pid kind) :obd :can) group]) (str name)))
    (is (every? (set (map :name signals)) primary))
    ;; Names are unique, so a display can look a value up by name alone.
    (is (apply distinct? (map :name signals)))))

(deftest describes-the-dids
  (let [{:keys [dids did-notes]} (catalog/catalog)]
    (is (= (set (keys uds/did-signals)) (set (map :did dids))))
    (is (= {:did 0x10BE :name :lambda :label "Measured λ" :unit :lambda :matches 0x34
            :compare {:pid :wideband-oxygen-sensor-1-current :part :lambda}}
           (some #(when (= 0x10BE (:did %)) %) dids)))
    (is (= "Possibly cam timing; pairs with 10ED" (get did-notes 0x10EC)))
    (is (= "Supported-DID bitmap" (get did-notes 0x1020)))))

(deftest holds-only-data
  (let [functions (atom [])]
    (walk/postwalk #(do (when (fn? %) (swap! functions conj %)) %) (catalog/catalog))
    (is (empty? @functions))))
