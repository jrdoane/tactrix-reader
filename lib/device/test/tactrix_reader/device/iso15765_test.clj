(ns tactrix-reader.device.iso15765-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tactrix-reader.device.iso15765 :as iso15765]
            [tactrix-reader.device.obd :as obd]
            [tactrix-reader.device.openport :as openport]
            [tactrix-reader.device.ssm :as ssm]
            [tactrix-reader.device.test-support :refer [ascii fake-adapter message-bytes]]
            [tactrix-reader.device.uds :as uds]))

(def ^:private path "/dev/cu.usbmodemTEST")

(defn- iso15765-responder
  "Answers ISO15765 channel commands, recording each command line in `lines`.
  `reply` takes a transmitted request's bytes and returns the reply messages'
  data, one vector per message."
  [lines reply]
  (let [clock (atom 1000)]
    (fn [line payload]
      (swap! lines conj line)
      (cond
        (str/starts-with? line "ato") (ascii "aro\r\n")
        (str/starts-with? line "atc") (ascii "aro\r\n")
        (str/starts-with? line "atf") (ascii "arf6 0\r\n")
        (str/starts-with? line "att")
        (concat (ascii "aro\r\n")
                (mapcat #(message-bytes 6 0x40 (swap! clock + 1000) 0x7E8 %)
                        (reply (subvec payload 4))))
        :else []))))

(defn- dtc-reply [request]
  (case (first request)
    0x03 [[0x43 0x01 0x01 0x33]]
    0x07 [[0x47 0x00]]
    0x0A [[0x4A 0x00]]))

(deftest requests-in-a-channel-share-one-device-and-connection
  (let [lines (atom [])
        opens (atom 0)]
    (with-redefs [openport/open-session (fn [_]
                                          (swap! opens inc)
                                          (fake-adapter (iso15765-responder lines dtc-reply)))]
      (is (= {:ok? true
              :path path
              :stored ["P0133"]
              :pending []
              :permanent []
              :errors {}}
             (obd/read-trouble-codes path)))
      (is (= 1 @opens))
      (is (= ["atc6" "ato6 0 500000" "atf6 3 64 4"
              "att6 5 64" "att6 5 64" "att6 5 64"
              "atc6"]
             @lines)))))

(deftest a-channel-passed-in-is-reused
  (let [lines (atom [])
        opens (atom 0)]
    (with-redefs [openport/open-session (fn [_]
                                          (swap! opens inc)
                                          (fake-adapter (iso15765-responder lines dtc-reply)))]
      (iso15765/with-channel path {}
                             (fn [channel]
                               (is (:ok? (obd/read-trouble-codes channel)))
                               (is (:ok? (obd/obd-request channel "03")))))
      (is (= 1 @opens))
      (is (= 2 (count (filter #{"atc6"} @lines))) "closed once at the start and once at the end")
      (is (= 4 (count (filter #(str/starts-with? % "att6") @lines)))))))

(deftest a-channel-opens-nothing-until-a-request-is-sent
  (with-redefs [openport/open-session (fn [_] (throw (ex-info "should not open" {})))]
    (is (= :done (iso15765/with-channel path {} (fn [_] :done))))
    (testing "validation still happens before opening"
      (is (= :service-not-read-only
             (:error (iso15765/with-channel path {} #(obd/obd-request % "04"))))))))

(deftest a-reply-left-from-the-last-request-is-discarded
  (let [lines (atom [])]
    ;; The 1000 reply arrives twice, so a copy is still queued when 1001 is sent.
    (with-redefs [openport/open-session
                  (fn [_]
                    (fake-adapter (iso15765-responder
                                   lines
                                   (fn [[_ hi lo]]
                                     (let [reply [0x62 hi lo 0x80]]
                                       (if (= 0x00 lo) [reply reply] [reply]))))))]
      (iso15765/with-channel path {}
                             (fn [channel]
                               (is (:ok? (uds/read-did channel 0x1000)))
                               (is (= {:ok? true :did-hex "1001" :data {:bytes [0x80] :hex ["80"]}}
                                      (select-keys (uds/read-did channel 0x1001)
                                                   [:ok? :did-hex :data]))))))))

(deftest every-request-on-a-channel-that-failed-to-connect-reports-it
  (let [opens (atom 0)]
    (with-redefs [openport/open-session (fn [_]
                                          (swap! opens inc)
                                          (fake-adapter (fn [line _]
                                                          (if (str/starts-with? line "ato")
                                                            (ascii "are 8\r\n")
                                                            (ascii "aro\r\n")))))]
      (let [result (obd/read-trouble-codes path)]
        (is (= {:ok? false :error :adapter-error} (select-keys result [:ok? :error])))
        (is (= {:stored {:error :adapter-error}
                :pending {:error :adapter-error}
                :permanent {:error :adapter-error}}
               (:errors result))))
      (is (= 1 @opens))))
  (with-redefs [openport/open-session (fn [p] (throw (java.io.FileNotFoundException. p)))]
    (is (= {:ok? false :error :not-found-or-permission-denied}
           (select-keys (obd/read-trouble-codes path) [:ok? :error])))))

(deftest read-vehicle-info-waits-briefly-for-ssm
  (let [timeouts (atom [])]
    (with-redefs [obd/obd-request (fn [_ _ _] {:ok? false :error :timeout})
                  ssm/ssm-init (fn [_ opts]
                                 (swap! timeouts conj (:timeout-ms opts))
                                 {:ok? false :error :timeout})]
      (obd/read-vehicle-info path)
      (obd/read-vehicle-info path {:ssm-init-timeout-ms 2000})
      (is (= [obd/ssm-init-timeout-ms 2000] @timeouts)))))
