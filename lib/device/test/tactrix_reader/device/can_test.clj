(ns tactrix-reader.device.can-test
  (:require [clojure.test :refer [are deftest is]]
            [tactrix-reader.device.can :as can]))

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

(deftest reply-sample-decodes-single-frame-mode-01-replies
  (is (= {:pid 0x0C :name :engine-rpm :value 800.0 :unit :rpm :timestamp-us 5}
         (can/reply-sample 0x7E8 {:type :message :id 0x7E8 :timestamp-us 5
                                  :data [4 0x41 0x0C 0x0C 0x80 0 0 0]})))
  (is (nil? (can/reply-sample 0x7E8 {:type :message :id 0x7E0 :timestamp-us 5
                                     :data [2 1 0x0C 0 0 0 0 0]})))
  (is (nil? (can/reply-sample 0x7E8 {:type :line :text "aro"}))))

