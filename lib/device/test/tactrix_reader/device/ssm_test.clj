(ns tactrix-reader.device.ssm-test
  (:require [clojure.test :refer [are deftest is]]
            [tactrix-reader.device.openport :as openport]
            [tactrix-reader.device.ssm :as ssm]
            [tactrix-reader.device.test-support :refer [ascii message-bytes]]))

(defn- run-fake-ssm
  "Runs `request`, an SSM function such as `ssm/ssm-init`, against canned
  adapter output. `args` go between the device path and the options. Returns
  the result and the bytes written to the adapter."
  [replies request & args]
  (let [output (java.io.ByteArrayOutputStream.)
        input (java.io.ByteArrayInputStream. (byte-array (map unchecked-byte replies)))]
    (with-redefs [openport/open-session (fn [_]
                                          (#'openport/stream-session input output))]
      {:result (apply request "/dev/cu.usbmodemTAlgJ3P81" (concat args [{:drain? false}]))
       :written (mapv #(bit-and % 0xFF) (.toByteArray output))})))

(deftest ssm-read-validates-before-opening-the-device
  (let [opened? (atom false)]
    (with-redefs [openport/open-session (fn [_] (reset! opened? true))]
      (is (= :missing-path (:error (ssm/ssm-read nil [0x22]))))
      (are [addresses] (= :invalid-ssm-addresses
                          (:error (ssm/ssm-read "/dev/cu.usbmodemTAlgJ3P81" addresses)))
        nil
        []
        [0x1000000]
        [-1]
        ["000022"])
      (is (false? @opened?)))))

(deftest ssm-read-sends-a8-and-pairs-bytes-with-addresses
  (let [{:keys [result written]}
        (run-fake-ssm (concat (ascii "aro\r\naro\r\narf6 9 0\r\naro\r\n")
                              (message-bytes 6 0x40 100 0x7E8 [0xE8 0x80 0x3F])
                              (ascii "aro\r\n"))
                      ssm/ssm-read [0x000022 0xFF6A04])]
    (is (= (vec (concat (ascii "atc6\r\n")
                        (ascii "ato6 0 500000\r\n")
                        (ascii "atf6 3 64 4\r\n")
                        [0xFF 0xFF 0xFF 0xFF 0 0 7 0xE8 0 0 7 0xE0]
                        (ascii "att6 12 64\r\n")
                        [0 0 7 0xE0 0xA8 0 0 0 0x22 0xFF 0x6A 0x04]
                        (ascii "atc6\r\n")))
           written))
    (is (true? (:ok? result)))
    (is (= ["A8" "00" "00" "00" "22" "FF" "6A" "04"] (get-in result [:request :hex])))
    (is (= [{:address 0x22 :address-hex "000022" :value 0x80}
            {:address 0xFF6A04 :address-hex "FF6A04" :value 0x3F}]
           (:values result)))))

(deftest ssm-read-reports-replies-of-the-wrong-length
  (let [{:keys [result]}
        (run-fake-ssm (concat (ascii "aro\r\naro\r\narf6 9 0\r\naro\r\n")
                              (message-bytes 6 0x40 100 0x7E8 [0xE8 0x80])
                              (ascii "aro\r\n"))
                      ssm/ssm-read [0x000022 0x000023])]
    (is (= {:ok? false :error :unexpected-response}
           (select-keys result [:ok? :error])))
    (is (nil? (:values result)))))

(deftest ssm-read-reports-negative-responses
  (let [{:keys [result]}
        (run-fake-ssm (concat (ascii "aro\r\naro\r\narf6 9 0\r\naro\r\n")
                              (message-bytes 6 0x40 100 0x7E8 [0x7F 0xA8 0x31])
                              (ascii "aro\r\n"))
                      ssm/ssm-read [0x000022])]
    (is (= {:ok? false :error :negative-response :nrc 0x31}
           (select-keys result [:ok? :error :nrc])))))

(def ^:private fake-ssm-init-reply
  (vec (concat [0xEA 0xA2 0x10 0x11 0x73 0x34 0x38 0x41 0x07]
               [0xF3 0xFA 0xC9 0x8E])))

(deftest ssm-init-sends-aa-and-reads-the-ecu-id
  (let [{:keys [result written]}
        (run-fake-ssm (concat (ascii "aro\r\naro\r\narf6 9 0\r\naro\r\n")
                              (message-bytes 6 0x40 100 0x7E8 fake-ssm-init-reply)
                              (ascii "aro\r\n"))
                      ssm/ssm-init)]
    (is (= (vec (concat (ascii "atc6\r\n")
                        (ascii "ato6 0 500000\r\n")
                        (ascii "atf6 3 64 4\r\n")
                        [0xFF 0xFF 0xFF 0xFF 0 0 7 0xE8 0 0 7 0xE0]
                        (ascii "att6 5 64\r\n")
                        [0 0 7 0xE0 0xAA]
                        (ascii "atc6\r\n")))
           written))
    (is (= {:ok? true
            :ecu-id "7334384107"
            :capabilities {:bytes [0xF3 0xFA 0xC9 0x8E]
                           :hex ["F3" "FA" "C9" "8E"]}}
           (select-keys result [:ok? :ecu-id :capabilities])))))

(deftest ssm-init-reports-short-and-missing-replies
  (with-redefs [openport/open-session (fn [_] (throw (ex-info "should not open" {})))]
    (is (= :missing-path (:error (ssm/ssm-init nil)))))
  (let [{:keys [result]}
        (run-fake-ssm (concat (ascii "aro\r\naro\r\narf6 9 0\r\naro\r\n")
                              (message-bytes 6 0x40 100 0x7E8 (subvec fake-ssm-init-reply 0 8))
                              (ascii "aro\r\n"))
                      ssm/ssm-init)]
    (is (= {:ok? false :error :unexpected-response}
           (select-keys result [:ok? :error])))
    (is (nil? (:ecu-id result)))))

