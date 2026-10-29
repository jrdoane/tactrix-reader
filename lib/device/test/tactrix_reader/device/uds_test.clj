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
