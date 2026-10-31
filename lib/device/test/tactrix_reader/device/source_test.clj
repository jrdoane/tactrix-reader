(ns tactrix-reader.device.source-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [tactrix-reader.device.openport :as openport]
            [tactrix-reader.device.recording :as recording]
            [tactrix-reader.device.source :as source]
            [tactrix-reader.device.test-support :refer [ascii fake-adapter fake-vehicle-responder
                                                        message-bytes temp-dir wait-for]]))

(deftest live-source-emits-status-and-samples
  (let [events (atom [])]
    (with-redefs [openport/open-session (fn [_] (fake-adapter (fake-vehicle-responder)))]
      (let [source (source/start-live-source! "/dev/fake" {:emit #(swap! events conj %)
                                                          :schedule [0x0C 0x05]
                                                          :interval-ms 10})]
        (is (wait-for #(<= 4 (count (filter (comp #{:sample} :type) @events))) 3000))
        ((:stop! source))))
    (let [statuses (filter (comp #{:status} :type) @events)
          samples (filter (comp #{:sample} :type) @events)]
      (is (= [{:type :status :state :running :source :live :path "/dev/fake" :firmware "1.17.4877"}
              {:type :status :state :stopped :source :live :path "/dev/fake"}]
             statuses))
      (is (= {:type :sample :pid 0x0C :name :engine-rpm :value 800.0 :unit :rpm}
             (dissoc (first samples) :timestamp-us)))
      (is (= #{:engine-rpm :coolant-temp} (set (map :name samples)))))))

(deftest live-source-emits-broadcast-samples
  (let [events (atom [])
        respond (fake-vehicle-responder)
        broadcasting (fn [line payload]
                       (concat (respond line payload)
                               (when (str/starts-with? line "att")
                                 (message-bytes 5 0 2000000 0x140 [0x00 0x0A 0x92 0x42 0x00 0x00 0x09 0x81]))))]
    (with-redefs [openport/open-session (fn [_] (fake-adapter broadcasting))]
      (let [source (source/start-live-source! "/dev/fake" {:emit #(swap! events conj %)
                                                          :schedule [0x05]
                                                          :interval-ms 10})]
        (is (wait-for #(some :can-id @events) 3000))
        ((:stop! source))))
    (is (= {:type :sample :pid 0x0C :name :engine-rpm :value 658.0 :unit :rpm
            :can-id 0x140 :timestamp-us 2000000}
           (first (filter :can-id @events))))))

(deftest live-source-emits-inferred-signals
  (let [events (atom [])
        respond (fake-vehicle-responder)
        broadcasting (fn [line payload]
                       (concat (respond line payload)
                               (when (str/starts-with? line "att")
                                 (message-bytes 5 0 2000000 0x141 [0x3E 0x28 0x19 0x28 0x5D 0x0A 0x05 0x00]))))]
    (with-redefs [openport/open-session (fn [_] (fake-adapter broadcasting))]
      (let [source (source/start-live-source! "/dev/fake" {:emit #(swap! events conj %)
                                                          :schedule [0x05]
                                                          :interval-ms 10})]
        (is (wait-for #(some (comp #{:signal} :type) @events) 3000))
        ((:stop! source))))
    (is (= {:type :signal :signal :gear :value 5 :unit nil :can-id 0x141 :timestamp-us 2000000}
           (first (filter (comp #{:signal} :type) @events))))))

(deftest live-source-reports-a-missing-path
  (let [events (atom [])]
    ((:stop! (source/start-live-source! nil {:emit #(swap! events conj %)})))
    (is (= [{:type :status :state :failed :source :live :path nil :error :missing-path}]
           @events))))

(deftest live-source-polls-dids
  (let [events (atom [])]
    (with-redefs [openport/open-session (fn [_] (fake-adapter (fake-vehicle-responder)))]
      (let [source (source/start-live-source! "/dev/fake" {:emit #(swap! events conj %)
                                                          :schedule [0x0C]
                                                          :dids [0x10A5]
                                                          :interval-ms 10})]
        (is (wait-for #(some (comp #{:did} :type) @events) 3000))
        ((:stop! source))))
    (is (= {:type :did :did 0x10A5 :did-hex "10A5" :data [0x80]}
           (select-keys (first (filter (comp #{:did} :type) @events)) [:type :did :did-hex :data])))))

(deftest live-source-records-without-interrupting-polling
  (let [events (atom [])
        dir (java.io.File. (temp-dir) "drive")]
    (with-redefs [openport/open-session (fn [_] (fake-adapter (fake-vehicle-responder)))]
      (let [source (source/start-live-source! "/dev/fake" {:emit #(swap! events conj %)
                                                          :schedule [0x0C 0x05]
                                                          :interval-ms 10})
            sample-count #(count (filter (comp #{:sample} :type) @events))]
        (is (wait-for #(some (comp #{:running} :state) @events) 3000))
        (is (= {:ok? true :name "drive" :dir (str dir)}
               ((:start-recording! source) (str dir) {:note "test drive"})))
        (is (= {:ok? false :error :already-recording}
               ((:start-recording! source) (str dir) {})))
        (is (true? (:in-progress? (recording/recording-meta dir))))
        (let [n (sample-count)]
          (is (wait-for #(<= (+ n 6) (sample-count)) 3000)))
        (let [result ((:stop-recording! source))]
          (is (= :stop (:stopped-by result)))
          (is (pos? (get-in result [:counts :pid-samples]))))
        (is (nil? ((:stop-recording! source))))
        (let [n (sample-count)]
          (is (wait-for #(< n (sample-count)) 3000)))
        ((:stop! source))))
    (let [meta (recording/recording-meta dir)
          recordings (filter (comp #{:recording} :type) @events)]
      (is (= [:recording :saved] (map :state recordings)))
      (is (= {:name "drive" :dir (str dir) :stopped-by :stop}
             (select-keys (second recordings) [:name :dir :stopped-by])))
      (is (= "test drive" (:note meta)))
      (is (= "1.17.4877" (get-in meta [:adapter :firmware])))
      (is (= ["0C" "05"] (get-in meta [:polling :pids])))
      (is (nil? (:in-progress? meta)))
      (is (= #{:engine-rpm :coolant-temp} (set (map :name (recording/recording-samples dir))))))))

(deftest live-source-finishes-its-recording-when-stopped
  (let [events (atom [])
        dir (java.io.File. (temp-dir) "drive")]
    (with-redefs [openport/open-session (fn [_] (fake-adapter (fake-vehicle-responder)))]
      (let [source (source/start-live-source! "/dev/fake" {:emit #(swap! events conj %)
                                                          :schedule [0x0C]
                                                          :interval-ms 10})]
        (is (wait-for #(some (comp #{:running} :state) @events) 3000))
        (is (= {:ok? false :error :not-running}
               ((:start-recording! (source/start-live-source! nil {:emit (fn [_])})) (str dir) {})))
        (is (:ok? ((:start-recording! source) (str dir) {})))
        ((:stop! source))))
    (is (= :source-stopped (:stopped-by (recording/recording-meta dir))))
    (is (= [:recording :saved :stopped]
           (keep #(case (:type %) :recording (:state %) :status (#{:stopped} (:state %)) nil) @events)))))

(deftest live-recording-starts-at-a-message-boundary
  (let [dir (temp-dir)
        writer (#'source/recording-writer (fn [_]))
        decode (#'source/message-decoder (fn [_]) (:write! writer))
        first-reply (vec (message-bytes 5 0 1000000 0x7E8 [4 0x41 0x0C 0x0C 0x80 0 0 0]))
        second-reply (vec (message-bytes 5 0 1001000 0x7E8 [3 0x41 0x05 0x55 0 0 0 0]))
        chunk #(byte-array (map unchecked-byte %))]
    ;; The recording starts while the second reply is half received.
    (decode (chunk (concat first-reply (take 5 second-reply))))
    ((:start! writer) (str dir) {:polling {:rx-id 0x7E8}})
    (decode (chunk (drop 5 second-reply)))
    ((:finish! writer) {:stopped-by :stop})
    (is (= second-reply (vec (recording/recording-bytes dir))))
    (is (= [:coolant-temp] (map :name (recording/recording-samples dir))))
    (is (= 1 (:pid-samples (:counts (edn/read-string (slurp (java.io.File. dir "meta.edn")))))))))

(defn- write-recording! [dir messages]
  (java.nio.file.Files/write (.toPath (java.io.File. dir "raw.bin"))
                             (byte-array (map unchecked-byte (apply concat messages)))
                             (make-array java.nio.file.OpenOption 0))
  (spit (java.io.File. dir "meta.edn") (pr-str {:polling {:rx-id 0x7E8}})))

(deftest replay-source-emits-recorded-samples-in-order
  (let [dir (temp-dir)
        events (atom [])]
    (write-recording! dir [(message-bytes 5 0 1000000 0x372 [0 0 0 0 0 0 0 0])
                           (message-bytes 5 0 1001000 0x7E8 [4 0x41 0x0C 0x0C 0x80 0 0 0])
                           (message-bytes 5 0 1011000 0x7E8 [3 0x41 0x05 0x55 0 0 0 0])])
    (let [source (source/start-replay-source! (str dir) {:emit #(swap! events conj %)
                                                         :loop? false})]
      (is (wait-for #(some (comp #{:stopped} :state) @events) 2000))
      ((:stop! source)))
    (is (= [{:type :status :state :running :source :replay :recording (str dir) :sample-count 2}
            {:type :sample :pid 0x0C :name :engine-rpm :value 800.0 :unit :rpm :timestamp-us 1001000}
            {:type :sample :pid 0x05 :name :coolant-temp :value 45 :unit :celsius :timestamp-us 1011000}
            {:type :status :state :stopped :source :replay :recording (str dir)}]
           @events))))

(deftest replay-source-includes-broadcast-signals
  (let [dir (temp-dir)
        frames [(message-bytes 5 0 1000000 0x360 [0xAC 0x00 0x84 0x89 0x0E 0x00 0x01 0x00])
                (message-bytes 5 0 1001000 0x7E8 [4 0x41 0x0C 0x0C 0x80 0 0 0])]
        replay (fn [opts]
                 (let [events (atom [])
                       source (source/start-replay-source! (str dir) (merge {:emit #(swap! events conj %)
                                                                             :loop? false}
                                                                            opts))]
                   (is (wait-for #(some (comp #{:stopped} :state) @events) 2000))
                   ((:stop! source))
                   (keep :name @events)))]
    (write-recording! dir frames)
    (is (= [:oil-temp :coolant-temp :intake-manifold-pressure :engine-rpm] (replay {:signals {}})))
    (is (= [:engine-rpm] (replay {:broadcast {} :signals {}})))))

(deftest replay-source-includes-inferred-signals
  (let [dir (temp-dir)
        events (atom [])]
    (write-recording! dir [(message-bytes 5 0 1000000 0x141 [0x3E 0x28 0x19 0x28 0x5D 0x0A 0x05 0x00])
                           (message-bytes 5 0 1001000 0x7E8 [4 0x41 0x0C 0x0C 0x80 0 0 0])])
    (let [source (source/start-replay-source! (str dir) {:emit #(swap! events conj %)
                                                         :loop? false})]
      (is (wait-for #(some (comp #{:stopped} :state) @events) 2000))
      ((:stop! source)))
    (is (= [{:type :signal :signal :gear :value 5 :unit nil :can-id 0x141 :timestamp-us 1000000}
            {:type :sample :pid 0x0C :name :engine-rpm :value 800.0 :unit :rpm :timestamp-us 1001000}]
           (remove (comp #{:status} :type) @events)))
    (is (= 1 (:sample-count (first @events))))))

(deftest replay-source-includes-did-replies
  (let [dir (temp-dir)
        events (atom [])]
    (write-recording! dir [(message-bytes 5 0 1000000 0x7E8 [4 0x41 0x0C 0x0C 0x80 0 0 0])
                           (message-bytes 5 0 1015000 0x7E8 [4 0x62 0x10 0xA5 0x80 0 0 0])])
    (let [source (source/start-replay-source! (str dir) {:emit #(swap! events conj %)
                                                         :loop? false})]
      (is (wait-for #(some (comp #{:stopped} :state) @events) 2000))
      ((:stop! source)))
    (is (= [{:type :sample :pid 0x0C :name :engine-rpm :value 800.0 :unit :rpm :timestamp-us 1000000}
            {:type :did :did 0x10A5 :did-hex "10A5" :data [0x80] :timestamp-us 1015000}]
           (remove (comp #{:status} :type) @events)))))

(deftest replay-source-loops-with-increasing-timestamps
  (let [dir (temp-dir)
        events (atom [])]
    (write-recording! dir [(message-bytes 5 0 1000000 0x7E8 [4 0x41 0x0C 0x0C 0x80 0 0 0])
                           (message-bytes 5 0 1010000 0x7E8 [3 0x41 0x05 0x55 0 0 0 0])])
    (let [source (source/start-replay-source! (str dir) {:emit #(swap! events conj %)
                                                         :speed 10.0})]
      (is (wait-for #(<= 6 (count (filter (comp #{:sample} :type) @events))) 2000))
      ((:stop! source)))
    (let [timestamps (keep :timestamp-us @events)]
      (is (apply < timestamps))
      (is (= [1000000 1010000 1035000 1045000] (take 4 timestamps))))))

(deftest replay-source-reports-an-empty-recording
  (let [dir (temp-dir)
        events (atom [])]
    (write-recording! dir [(ascii "aro\r\n")])
    ((:stop! (source/start-replay-source! (str dir) {:emit #(swap! events conj %)})))
    (is (= [{:type :status :state :failed :source :replay :recording (str dir) :error :no-samples}]
           @events))))

