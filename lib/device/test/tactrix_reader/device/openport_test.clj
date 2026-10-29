(ns tactrix-reader.device.openport-test
  (:require [clojure.test :refer [deftest is testing]]
            [tactrix-reader.device.openport :as openport]
            [tactrix-reader.device.test-support :refer [ascii message-bytes]]))

(deftest known-operations-have-confirmed-commands
  (is (true? (openport/known-operation? :pass-thru-connect)))
  (is (true? (openport/known-operation? "pass-thru-write-msgs")))
  (is (false? (openport/known-operation? :pass-thru-start-periodic-msg)))
  (is (false? (openport/known-operation? "adapter-ping")))
  (is (false? (openport/known-operation? nil))))

(deftest unsupported-operation-reports-whether-command-is-known
  (is (= {:ok? false
          :backend :mac-serial
          :operation/name :pass-thru-connect
          :operation/known? true
          :operation/command "ato"
          :state :unsupported
          :error :not-implemented
          :message "The OpenPort command for this operation is known, but the J2534 API is not implemented."}
         (openport/unsupported-operation :pass-thru-connect)))
  (is (= {:ok? false
          :backend :mac-serial
          :operation/name :pass-thru-start-periodic-msg
          :operation/known? false
          :state :unsupported
          :error :needs-openport-command
          :message "The OpenPort command for this operation has not been discovered."}
         (openport/unsupported-operation :pass-thru-start-periodic-msg))))

(deftest parses-adapter-stream
  (is (= {:events [{:type :line :text "aro"}
                   {:type :message
                    :channel 5
                    :status 0
                    :timestamp-us 0x0A57C6EF
                    :id 0x370
                    :data [0 0 0x20 5 0xFC 7 0 0]}
                   {:type :line :text "arf5 1 0"}]
          :remainder []}
         (openport/parse-adapter-stream
          (concat (ascii "aro\r\n")
                  (message-bytes 5 0 0x0A57C6EF 0x370 [0 0 0x20 5 0xFC 7 0 0])
                  (ascii "arf5 1 0\r\n")))))
  (testing "keeps incomplete events for the next read"
    (let [message (vec (message-bytes 6 0x40 1 0x7E8 [0x41 0]))]
      (is (= {:events []
              :remainder (subvec message 0 8)}
             (openport/parse-adapter-stream (subvec message 0 8))))
      (is (= {:events [{:type :line :text "aro"}]
              :remainder (vec (ascii "ar"))}
             (openport/parse-adapter-stream (ascii "aro\r\nar")))))))

