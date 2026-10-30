(ns tactrix-reader.device.uds-test
  (:require [clojure.test :refer [are deftest is]]
            [tactrix-reader.device.openport :as openport]
            [tactrix-reader.device.test-support :refer [ascii message-bytes]]
            [tactrix-reader.device.uds :as uds]))

(defn- run-fake-read-did
  "Runs `uds/read-did` for `did` against canned adapter output whose reply
  message carries `reply-data`. Returns the result and the bytes written to the
  adapter."
  [did reply-data]
  (let [replies (concat (ascii "aro\r\naro\r\narf6 9 0\r\naro\r\n")
                        (message-bytes 6 0x40 100 0x7E8 reply-data)
                        (ascii "aro\r\n"))
        output (java.io.ByteArrayOutputStream.)
        input (java.io.ByteArrayInputStream. (byte-array (map unchecked-byte replies)))]
    (with-redefs [openport/open-session (fn [_]
                                          (#'openport/stream-session input output))]
      {:result (uds/read-did "/dev/cu.usbmodemTAlgJ3P81" did {:drain? false})
       :written (mapv #(bit-and % 0xFF) (.toByteArray output))})))

(deftest read-did-validates-before-opening-the-device
  (let [opened? (atom false)]
    (with-redefs [openport/open-session (fn [_] (reset! opened? true))]
      (is (= :missing-path (:error (uds/read-did nil 0x1155))))
      (are [did] (= :invalid-did (:error (uds/read-did "/dev/cu.usbmodemTAlgJ3P81" did)))
        nil
        -1
        0x10000
        "1155")
      (is (false? @opened?)))))

(deftest read-did-sends-22-and-returns-the-data
  (let [{:keys [result written]} (run-fake-read-did 0x1155 [0x62 0x11 0x55 0x80 0x3F])]
    (is (= (vec (concat (ascii "atc6\r\n")
                        (ascii "ato6 0 500000\r\n")
                        (ascii "atf6 3 64 4\r\n")
                        [0xFF 0xFF 0xFF 0xFF 0 0 7 0xE8 0 0 7 0xE0]
                        (ascii "att6 7 64\r\n")
                        [0 0 7 0xE0 0x22 0x11 0x55]
                        (ascii "atc6\r\n")))
           written))
    (is (= {:ok? true
            :did 0x1155
            :did-hex "1155"
            :data {:bytes [0x80 0x3F] :hex ["80" "3F"]}}
           (select-keys result [:ok? :did :did-hex :data])))))

(deftest read-did-pads-low-identifiers
  (let [{:keys [result]} (run-fake-read-did 0x30 [0x62 0x00 0x30 0x07])]
    (is (= ["22" "00" "30"] (get-in result [:request :hex])))
    (is (= "0030" (:did-hex result)))
    (is (= [0x07] (get-in result [:data :bytes])))))

(deftest read-did-rejects-replies-for-another-identifier
  (are [reply] (= {:ok? false :error :unexpected-response}
                  (select-keys (:result (run-fake-read-did 0x1155 reply)) [:ok? :error]))
    [0x62 0x11 0x56 0x80]
    [0x62 0x11]
    [0x62]))

(deftest read-did-names-negative-responses
  (are [nrc nrc-name] (= {:ok? false :error :negative-response :nrc nrc :nrc-name nrc-name}
                         (select-keys (:result (run-fake-read-did 0x1155 [0x7F 0x22 nrc]))
                                      [:ok? :error :nrc :nrc-name]))
    0x31 :request-out-of-range
    0x11 :service-not-supported
    0x33 :security-access-denied)
  (is (nil? (:nrc-name (:result (run-fake-read-did 0x1155 [0x7F 0x22 0x99]))))))

(defn- fake-read-did
  "Stands in for `uds/read-did`: DIDs in `replies` get that result, and the
  rest are out of range. Records each DID read in `reads`."
  [reads replies]
  (fn [_ did _]
    (swap! reads conj did)
    (merge {:did did :did-hex (format "%04X" did)}
           (get replies did {:ok? false
                             :error :negative-response
                             :nrc 0x31
                             :nrc-name :request-out-of-range
                             :response {:bytes [0x7F 0x22 0x31]}}))))

(deftest scan-dids-validates-before-reading
  (let [reads (atom [])]
    (with-redefs [uds/read-did (fake-read-did reads {})]
      (is (= :missing-path (:error (uds/scan-dids nil 0x1000 0x10FF))))
      (are [start end] (= :invalid-did-range
                          (:error (uds/scan-dids "/dev/cu.usbmodemTAlgJ3P81" start end)))
        nil 0x10FF
        0x1000 nil
        0x10FF 0x1000
        0x1000 0x10000)
      (is (= [] @reads)))))

(deftest scan-dids-reports-everything-but-out-of-range
  (let [reads (atom [])
        seen (atom [])
        replies {0x1001 {:ok? true
                         :data {:bytes [0x80] :hex ["80"]}
                         :response {:bytes [0x62 0x10 0x01 0x80]}}
                 0x1003 {:ok? false
                         :error :negative-response
                         :nrc 0x33
                         :nrc-name :security-access-denied
                         :response {:bytes [0x7F 0x22 0x33]}}}]
    (with-redefs [uds/read-did (fake-read-did reads replies)]
      (let [result (uds/scan-dids "/dev/cu.usbmodemTAlgJ3P81" 0x1000 0x1004
                                  {:on-result #(swap! seen conj (:did %))})]
        (is (= [0x1000 0x1001 0x1002 0x1003 0x1004] @reads @seen))
        (is (= {:ok? true
                :start-hex "1000"
                :end-hex "1004"
                :scanned 5
                :stopped-early? false
                :out-of-range 3}
               (select-keys result [:ok? :start-hex :end-hex :scanned :stopped-early?
                                    :out-of-range])))
        (is (= [[0x1001 true {:bytes [0x80] :hex ["80"]} nil]
                [0x1003 false nil :security-access-denied]]
               (mapv (juxt :did :ok? :data :nrc-name) (:found result))))))))

(deftest scan-dids-stops-once-the-ecu-goes-quiet
  (let [reads (atom [])
        timeout {:ok? false :step :response :error :timeout}
        replies {0x1001 timeout
                 0x1002 timeout
                 0x1004 timeout
                 0x1005 timeout
                 0x1006 timeout
                 0x1007 timeout}]
    (with-redefs [uds/read-did (fake-read-did reads replies)]
      (let [result (uds/scan-dids "/dev/cu.usbmodemTAlgJ3P81" 0x1000 0x10FF)]
        ;; The reply to 1003 resets the count, so it stops after 1006.
        (is (= [0x1000 0x1001 0x1002 0x1003 0x1004 0x1005 0x1006] @reads))
        (is (= {:ok? false
                :error :no-reply
                :scanned 7
                :stopped-early? true}
               (select-keys result [:ok? :error :scanned :stopped-early?])))
        (is (= [0x1001 0x1002 0x1004 0x1005 0x1006] (mapv :did (:found result))))))))

(defn- fake-bitmaps
  "Stands in for `uds/read-did`, answering each DID in `bitmaps` with its
  bytes and any other with out of range. Records each DID read in `reads`."
  [reads bitmaps]
  (fn [_ did _]
    (swap! reads conj did)
    (merge {:did did :did-hex (format "%04X" did)}
           (if-let [data (bitmaps did)]
             {:ok? true :data {:bytes data :hex (mapv #(format "%02X" %) data)}}
             {:ok? false :error :negative-response :nrc 0x31 :nrc-name :request-out-of-range}))))

(deftest bitmap-dids-reads-bits-in-order
  ;; The 10A0 bitmap from the 2015 Impreza.
  (is (= [0x10A1 0x10A3 0x10A5 0x10A6 0x10AE 0x10B1 0x10B2 0x10B4 0x10B5 0x10B6
          0x10B7 0x10B8 0x10B9 0x10BA 0x10BC 0x10BE 0x10C0]
         (uds/bitmap-dids 0x10A0 [0xAC 0x04 0xDF 0xD5])))
  (is (= [] (uds/bitmap-dids 0x1040 [0 0 0 0]))))

(deftest supported-dids-follows-the-chain-until-the-last-bit-is-clear
  (let [reads (atom [])]
    (with-redefs [uds/read-did (fake-bitmaps reads {0x1000 [0xC0 0 0 0x01]
                                                    0x1020 [0 0 0 0x01]
                                                    0x1040 [0x80 0 0 0x02]})]
      (let [result (uds/supported-dids "/dev/cu.usbmodemTAlgJ3P81")]
        (is (= [0x1000 0x1020 0x1040] @reads))
        (is (true? (:ok? result)))
        (is (= ["1000" "1020" "1040"] (mapv :did-hex (:bitmaps result))))
        (is (= ["1001" "1002" "1041" "105F"] (:dids-hex result)))))))

(deftest supported-dids-keeps-what-it-found-when-a-read-fails
  (let [reads (atom [])]
    (with-redefs [uds/read-did (fake-bitmaps reads {0x1000 [0x80 0 0 0x01]})]
      (is (= {:ok? false
              :error :negative-response
              :nrc-name :request-out-of-range
              :failed-at "1020"
              :dids-hex ["1001"]}
             (select-keys (uds/supported-dids "/dev/cu.usbmodemTAlgJ3P81")
                          [:ok? :error :nrc-name :failed-at :dids-hex]))))
    (with-redefs [uds/read-did (fake-bitmaps reads {0x1000 [0x80 0 0x01]})]
      (is (= {:ok? false :error :unexpected-bitmap :failed-at "1000" :dids-hex []}
             (select-keys (uds/supported-dids "/dev/cu.usbmodemTAlgJ3P81")
                          [:ok? :error :failed-at :dids-hex]))))))

(deftest supported-dids-stops-at-the-end-of-the-did-range
  (let [reads (atom [])]
    (with-redefs [uds/read-did (fake-bitmaps reads {0xFFE0 [0 0 0 0x03]})]
      (let [result (uds/supported-dids "/dev/cu.usbmodemTAlgJ3P81" 0xFFE0)]
        (is (= [0xFFE0] @reads))
        (is (true? (:ok? result)))
        (is (= ["FFFF"] (:dids-hex result)))))))

(deftest supported-dids-validates-before-reading
  (let [reads (atom [])]
    (with-redefs [uds/read-did (fake-bitmaps reads {})]
      (is (= :missing-path (:error (uds/supported-dids nil))))
      (is (= :invalid-did (:error (uds/supported-dids "/dev/cu.usbmodemTAlgJ3P81" 0x10000))))
      (is (= [] @reads)))))

(deftest did-sample-decodes-fitted-single-byte-dids
  (is (= {:did 0x10DF :did-hex "10DF" :data [0x43] :timestamp-us 5
          :name :throttle-position :matches 0x11 :value 13.6 :unit :percent}
         (uds/did-sample {:did 0x10DF :did-hex "10DF" :data [0x43] :timestamp-us 5})))
  (are [did data value] (= value (:value (uds/did-sample {:did did :data data})))
    0x10BE [0x80] 1.0
    0x10BA [0x70] -2.0
    0x10A6 [0x00] 0.0
    0x10A3 [0x09] 0.171)
  (is (= {:did 0x10A1 :data [0x37] :name :mass-air-flow :matches 0x10}
         (uds/did-sample {:did 0x10A1 :data [0x37]}))
      "a DID with no fitted scale is named but not decoded")
  (is (= {:did 0x10A5 :data [0x80]}
         (uds/did-sample {:did 0x10A5 :data [0x80]}))))
