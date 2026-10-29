(ns tactrix-reader.device.iso15765
  "Single request-reply exchanges with the ECU over an ISO15765 channel (6),
  shared by OBD-II and SSM requests. The adapter handles flow control and
  multi-frame reassembly. Sends traffic on the vehicle bus."
  (:require [clojure.string :as str]
            [tactrix-reader.device.codec :as codec]
            [tactrix-reader.device.openport :as openport]))

(def default-obd-timeout-ms 1000)
(def default-obd-command-timeout-ms 1000)

(def iso15765-channel
  "The adapter uses J2534 protocol IDs as channel IDs; 6 is ISO15765."
  6)

(def iso15765-frame-pad
  "J2534 ISO15765_FRAME_PAD TxFlag. Some ECUs ignore unpadded frames."
  64)

(defn- response-pending?
  "True for the negative response NRC 0x78, which means the ECU needs more time."
  [data]
  (and (= 3 (count data))
       (= 0x7F (data 0))
       (= 0x78 (data 2))))

(defn- run-obd-request
  "Opens an ISO15765 channel, sends `request`, and waits for the reply message.
  Always closes the channel afterwards."
  [session request {:keys [tx-id rx-id baud timeout-ms command-timeout-ms]}]
  (let [channel iso15765-channel
        close (str "atc" channel)
        connect (str "ato" channel " 0 " baud)
        flow-control (str "atf" channel " 3 " iso15765-frame-pad " 4")
        transmit (str "att" channel " " (+ 4 (count request)) " " iso15765-frame-pad)
        reply? (fn [event]
                 (and (= :message (:type event))
                      (= channel (:channel event))
                      (= rx-id (:id event))
                      (seq (:data event))))]
    ;; Channels persist in the adapter between port opens, so start closed.
    (openport/send-command! session close [] command-timeout-ms)
    (try
      (let [reply (openport/send-command! session connect [] command-timeout-ms)]
        (if (not= "aro" reply)
          (openport/reply-error :connect connect reply)
          (let [reply (openport/send-command! session flow-control
                                              (concat [0xFF 0xFF 0xFF 0xFF]
                                                      (codec/u32->bytes rx-id)
                                                      (codec/u32->bytes tx-id))
                                              command-timeout-ms)]
            (if-not (and reply (str/starts-with? reply (str "arf" channel " ")))
              (openport/reply-error :flow-control-filter flow-control reply)
              (let [reply (openport/send-command! session transmit
                                                  (concat (codec/u32->bytes tx-id) request)
                                                  command-timeout-ms)]
                (if (not= "aro" reply)
                  (openport/reply-error :transmit transmit reply)
                  (loop []
                    (let [message (openport/take-event! session reply? timeout-ms)]
                      (cond
                        (nil? message)
                        {:ok? false
                         :step :response
                         :error :timeout}

                        (response-pending? (:data message))
                        (recur)

                        :else
                        {:ok? true
                         :message message})))))))))
      (finally
        (openport/send-command! session close [] command-timeout-ms)))))

(def iso15765-defaults
  {:tx-id 0x7E0
   :rx-id 0x7E8
   :baud 500000
   :timeout-ms default-obd-timeout-ms
   :command-timeout-ms default-obd-command-timeout-ms
   :drain? true})

(defn iso15765-request
  "Opens the device, sends `request` bytes over ISO15765, and checks that the
  reply's first byte is the request's plus 0x40. `base` is merged into every
  result, and `failed-error` is the `:error` for unexpected exceptions."
  [path request base failed-error {:keys [drain?] :as opts}]
  (let [service (first request)
        request-summary {:bytes request
                         :hex (mapv codec/byte->hex request)}]
    (try
      (let [session (openport/open-session path)]
        (try
          (when drain?
            (openport/read-events! session)
            (reset! (:events session) [])
            (reset! (:remainder session) []))
          (let [result (run-obd-request session request
                                        (select-keys opts [:tx-id :rx-id :baud :timeout-ms
                                                           :command-timeout-ms]))
                {:keys [data status timestamp-us]} (:message result)
                response {:bytes data
                          :hex (mapv codec/byte->hex data)
                          :status status
                          :timestamp-us timestamp-us}]
            (cond
              (not (:ok? result))
              (merge base {:request request-summary} result)

              (= 0x7F (first data))
              (merge base {:ok? false
                           :error :negative-response
                           :nrc (get data 2)
                           :request request-summary
                           :response response})

              (not= (+ service 0x40) (first data))
              (merge base {:ok? false
                           :error :unexpected-response
                           :request request-summary
                           :response response})

              :else
              (merge base {:ok? true
                           :request request-summary
                           :response response})))
          (finally
            (openport/close-session session))))
      (catch java.io.FileNotFoundException e
        (merge base {:ok? false
                     :error :not-found-or-permission-denied
                     :message (.getMessage e)}))
      (catch Throwable e
        (merge base {:ok? false
                     :error failed-error
                     :message (.getMessage e)})))))
