(ns tactrix-reader.device.can-test
  (:require [clojure.test :refer [deftest is]]
            [tactrix-reader.device.can :as can]))

(deftest reply-sample-decodes-single-frame-mode-01-replies
  (is (= {:pid 0x0C :name :engine-rpm :value 800.0 :unit :rpm :timestamp-us 5}
         (can/reply-sample 0x7E8 {:type :message :id 0x7E8 :timestamp-us 5
                                  :data [4 0x41 0x0C 0x0C 0x80 0 0 0]})))
  (is (nil? (can/reply-sample 0x7E8 {:type :message :id 0x7E0 :timestamp-us 5
                                     :data [2 1 0x0C 0 0 0 0 0]})))
  (is (nil? (can/reply-sample 0x7E8 {:type :line :text "aro"}))))

