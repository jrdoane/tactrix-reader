(ns tactrix-reader.device.can-test
  (:require [clojure.string :as str]
            [clojure.test :refer [are deftest is testing]]
            [tactrix-reader.device.can :as can]
            [tactrix-reader.device.serial :as serial]
            [tactrix-reader.device.test-support :refer [ascii fake-adapter fake-vehicle-responder
                                                        message-bytes]]))

(deftest did-reply-decodes-single-frame-service-22-replies
  (is (= {:did 0x10A5 :did-hex "10A5" :data [0x80] :timestamp-us 5}
         (can/did-reply 0x7E8 {:type :message :id 0x7E8 :timestamp-us 5
                               :data [4 0x62 0x10 0xA5 0x80 0 0 0]})))
  (is (= [0xFF 0xC0 0x00 0x0D]
         (:data (can/did-reply 0x7E8 {:type :message :id 0x7E8 :timestamp-us 5
                                      :data [7 0x62 0x10 0x00 0xFF 0xC0 0x00 0x0D]}))))
  (are [id data] (nil? (can/did-reply 0x7E8 {:type :message :id id :timestamp-us 5 :data data}))
    ;; A mode 01 reply, a negative response, the first frame of a multi-frame
    ;; reply, the request itself, and another ECU.
    0x7E8 [4 0x41 0x0C 0x0C 0x80 0 0 0]
    0x7E8 [3 0x7F 0x22 0x31 0 0 0 0]
    0x7E8 [0x10 0x09 0x62 0x10 0x1E 0x6E 0x98 0x00]
    0x7E0 [3 0x22 0x10 0xA5 0 0 0 0]
    0x7E9 [4 0x62 0x10 0xA5 0x80 0 0 0])
  (is (nil? (can/did-reply 0x7E8 {:type :line :text "aro"}))))

(deftest message-events-include-did-replies
  (is (= [{:type :did :did 0x10DF :did-hex "10DF" :data [0x43] :timestamp-us 5
           :name :throttle-position :matches 0x11 :value 13.6 :unit :percent}]
         (can/message-events 0x7E8 {} {} {:type :message :id 0x7E8 :timestamp-us 5
                                         :data [4 0x62 0x10 0xDF 0x43 0 0 0]})))
  (is (= [:sample]
         (map :type (can/message-events 0x7E8 {} {} {:type :message :id 0x7E8 :timestamp-us 5
                                                      :data [4 0x41 0x0C 0x0C 0x80 0 0 0]})))))

(deftest reply-sample-decodes-single-frame-mode-01-replies
  (is (= {:pid 0x0C :name :engine-rpm :value 800.0 :unit :rpm :timestamp-us 5}
         (can/reply-sample 0x7E8 {:type :message :id 0x7E8 :timestamp-us 5
                                  :data [4 0x41 0x0C 0x0C 0x80 0 0 0]})))
  (is (nil? (can/reply-sample 0x7E8 {:type :message :id 0x7E0 :timestamp-us 5
                                     :data [2 1 0x0C 0 0 0 0 0]})))
  (is (nil? (can/reply-sample 0x7E8 {:type :line :text "aro"}))))


(defn- poll-for
  "Runs a CAN session against `respond`, polling for `ms` once the channel
  is open, and returns its result."
  [respond ms opts]
  (let [deadline (atom nil)]
    (can/run-can-session! (fake-adapter respond)
                          (merge {:pids [0x0C 0x05]
                                  :dids []
                                  :interval-ms 1
                                  :reply-timeout-ms 1000
                                  :tx-id 0x7E0
                                  :baud 500000
                                  :continue? #(< (System/currentTimeMillis) @deadline)
                                  :on-bytes (fn [_])
                                  :on-connected (fn [_]
                                                  (reset! deadline (+ (System/currentTimeMillis) ms)))}
                                 opts))))

(defn- replying-with
  "A vehicle that answers each polling request with the frame data
  `reply-data` returns, given the request's service and parameter bytes, or
  sends nothing when it returns nil."
  [reply-data]
  (let [vehicle (fake-vehicle-responder)]
    (fn [line payload]
      (if (str/starts-with? line "att")
        (let [frame (subvec payload 4)
              data (reply-data (subvec frame 1 (inc (first frame))))]
          (concat (ascii "aro\r\n")
                  (when data
                    (message-bytes 5 0 1000 0x7E8 (take 8 (concat data (repeat 0)))))))
        (vehicle line payload)))))

(deftest polling-sends-the-next-request-once-the-reply-arrives
  (let [{:keys [ok? request-count unanswered-count]} (poll-for (fake-vehicle-responder) 200 {})]
    (is ok?)
    ;; A fixed 15 ms interval would send about 13.
    (is (< 40 request-count))
    (is (zero? unanswered-count))))

(deftest polling-keeps-the-minimum-interval
  (let [{:keys [request-count]} (poll-for (fake-vehicle-responder) 200 {:interval-ms 50})]
    (is (<= 3 request-count 5))))

(deftest polling-is-paced-by-the-monotonic-clock-alone
  ;; The system's clock can be set while polling, so no wait may be measured
  ;; on it. Here the monotonic clock stands still once the channel is open,
  ;; then moves on by one interval, while real time passes throughout.
  (let [real-ms serial/monotonic-ms
        stopped (atom nil)
        phase-ends (atom nil)
        counts (atom [])
        requests (atom 0)
        vehicle (fake-vehicle-responder)
        respond (fn [line payload]
                  (when (str/starts-with? line "att")
                    (swap! requests inc))
                  (vehicle line payload))]
    (with-redefs [serial/monotonic-ms #(or @stopped (real-ms))]
      (poll-for respond 0
                {:interval-ms 50
                 :on-connected (fn [_]
                                 (reset! stopped (real-ms))
                                 (reset! phase-ends (+ (real-ms) 150)))
                 :continue? (fn []
                              (when (>= (real-ms) @phase-ends)
                                (swap! counts conj @requests)
                                (swap! stopped + 50)
                                (reset! phase-ends (+ (real-ms) 150)))
                              (or (< (count @counts) 3)
                                  ;; Let the clock run again for the close.
                                  (reset! stopped nil)))}))
    (is (= [1 2 3] @counts))))

(deftest polling-waits-out-the-reply-timeout-without-an-answer
  (are [reply-data] (let [{:keys [request-count unanswered-count]}
                          (poll-for (replying-with reply-data) 250 {:reply-timeout-ms 100})]
                      (and (<= 2 request-count 4)
                           (= (dec request-count) unanswered-count)))
    ;; No reply.
    (fn [_] nil)
    ;; Response pending: the ECU will reply again.
    (fn [[service]] [3 0x7F service 0x78])
    ;; A reply to another PID.
    (fn [_] [3 0x41 0x0D 0x20])))

(deftest polling-counts-negative-replies-and-first-frames-as-answers
  (testing "a negative reply"
    (is (< 40 (:request-count (poll-for (replying-with (fn [[service]] [3 0x7F service 0x12]))
                                        200 {})))))
  (testing "the first frame of a DID reply too long for one frame"
    (is (< 40 (:request-count (poll-for (replying-with (fn [[_ hi lo]] [0x10 0x14 0x62 hi lo 1 2 3]))
                                        200 {:pids [] :dids [0x101E]})))))
  (testing "a reply from another ID is not an answer"
    (is (<= 2 (:request-count (poll-for (replying-with (fn [[_ pid]] [3 0x41 pid 0]))
                                        250 {:rx-id 0x7E9 :reply-timeout-ms 100}))
            4))))
