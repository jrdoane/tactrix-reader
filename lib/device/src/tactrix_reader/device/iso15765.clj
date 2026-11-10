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

(defn- connect!
  "Opens the ISO15765 channel with a flow-control filter for `rx-id` and
  `tx-id`. Returns nil, or the error."
  [session {:keys [tx-id rx-id baud command-timeout-ms]}]
  (let [channel iso15765-channel
        connect (str "ato" channel " 0 " baud)
        flow-control (str "atf" channel " 3 " iso15765-frame-pad " 4")]
    ;; Channels persist in the adapter between port opens, so start closed.
    (openport/send-command! session (str "atc" channel) [] command-timeout-ms)
    (let [reply (openport/send-command! session connect [] command-timeout-ms)]
      (if (not= "aro" reply)
        (openport/reply-error :connect connect reply)
        (let [reply (openport/send-command! session flow-control
                                            (concat [0xFF 0xFF 0xFF 0xFF]
                                                    (codec/u32->bytes rx-id)
                                                    (codec/u32->bytes tx-id))
                                            command-timeout-ms)]
          (when-not (and reply (str/starts-with? reply (str "arf" channel " ")))
            (openport/reply-error :flow-control-filter flow-control reply)))))))

(defn- disconnect! [session {:keys [command-timeout-ms]}]
  (openport/send-command! session (str "atc" iso15765-channel) [] command-timeout-ms))

(defn- exchange!
  "Sends `request` on the open channel and waits for the reply message."
  [session request {:keys [tx-id rx-id timeout-ms command-timeout-ms]}]
  (let [channel iso15765-channel
        transmit (str "att" channel " " (+ 4 (count request)) " " iso15765-frame-pad)
        reply? (fn [event]
                 (and (= :message (:type event))
                      (= channel (:channel event))
                      (= rx-id (:id event))
                      (seq (:data event))))
        reply (openport/send-command! session transmit
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
             :message message}))))))

(defn- run-obd-request
  "Opens an ISO15765 channel, sends `request`, and waits for the reply message.
  Always closes the channel afterwards."
  [session request opts]
  (try
    (or (connect! session opts)
        (exchange! session request opts))
    (finally
      (disconnect! session opts))))

(defn- drain!
  "Discards whatever the adapter has sent and not been read."
  [session]
  (openport/read-events! session)
  (reset! (:events session) [])
  (reset! (:remainder session) []))

(def iso15765-defaults
  {:tx-id 0x7E0
   :rx-id 0x7E8
   :baud 500000
   :timeout-ms default-obd-timeout-ms
   :command-timeout-ms default-obd-command-timeout-ms
   :drain? true})

(defn channel?
  "True for a channel from `with-channel`."
  [x]
  (and (map? x) (contains? x ::state)))

(defn- open-channel!
  "Opens the device and connects a channel's ISO15765 channel the first time a
  request is sent on it. Returns `{:session ...}`, with `:failure` when the
  channel did not connect, or `{:exception e}` when the device did not open."
  [{:keys [path opts] ::keys [state]}]
  (or @state
      (reset! state
              (try
                (let [session (openport/open-session path)]
                  (try
                    (when (:drain? opts)
                      (drain! session))
                    {:session session
                     :failure (connect! session opts)}
                    (catch Throwable e
                      {:session session
                       :exception e})))
                (catch Throwable e
                  {:exception e})))))

(defn with-channel
  "Calls `f` with a channel to `device-or-path` that `iso15765-request`, and so
  `obd-request`, `read-did`, `ssm-init`, and the functions built on them,
  accept in place of a device. Requests on it share one open device and one
  ISO15765 channel, instead of each opening the device and connecting,
  setting the flow-control filter, and closing again. The device is opened at
  the first request, so `f` opens nothing if it sends none, and closed when
  `f` returns. Returns what `f` returns.

  The channel uses the `:tx-id`, `:rx-id`, `:baud`, and `:drain?` in `opts`
  for every request; a request's own `:timeout-ms` and `:command-timeout-ms`
  still apply. When the device does not open or the channel does not
  connect, every request on it returns that error. Given a channel, it calls
  `f` with that channel."
  [device-or-path opts f]
  (if (channel? device-or-path)
    (f device-or-path)
    (let [channel {:path (if (map? device-or-path) (:path device-or-path) device-or-path)
                   :opts (merge iso15765-defaults opts)
                   ::state (atom nil)}]
      (try
        (f channel)
        (finally
          (when-let [session (:session @(::state channel))]
            (try
              (disconnect! session (:opts channel))
              (catch Throwable _ nil))
            (try
              (openport/close-session session)
              (catch Throwable _ nil))))))))

(defn- exception-result [base failed-error ^Throwable e]
  (merge base {:ok? false
               :error (if (instance? java.io.FileNotFoundException e)
                        :not-found-or-permission-denied
                        failed-error)
               :message (.getMessage e)}))

(defn- channel-request
  "Sends `request` on an open channel, first discarding anything the adapter
  sent since the last request, such as a reply that came after its timeout."
  [channel request opts]
  (let [{:keys [session failure exception]} (open-channel! channel)]
    (cond
      exception {:exception exception}
      failure failure
      :else (do
              (openport/read-events! session)
              (reset! (:events session) [])
              (exchange! session request (merge (:opts channel)
                                                (select-keys opts [:timeout-ms
                                                                   :command-timeout-ms])))))))

(defn- device-request
  "Opens the device at `path` for one request and closes it again."
  [path request {:keys [drain?] :as opts}]
  (let [session (openport/open-session path)]
    (try
      (when drain?
        (drain! session))
      (run-obd-request session request
                       (select-keys opts [:tx-id :rx-id :baud :timeout-ms :command-timeout-ms]))
      (finally
        (openport/close-session session)))))

(defn iso15765-request
  "Sends `request` bytes over ISO15765 and checks that the reply's first byte
  is the request's plus 0x40. `device` is a path, a device map, or a channel
  from `with-channel`; a path or device map is opened for this request only.
  `base` is merged into every result, and `failed-error` is the `:error` for
  unexpected exceptions."
  [device request base failed-error opts]
  (let [service (first request)
        request-summary {:bytes request
                         :hex (mapv codec/byte->hex request)}]
    (try
      (let [result (if (channel? device)
                     (channel-request device request opts)
                     (device-request (if (map? device) (:path device) device) request opts))
            {:keys [data status timestamp-us]} (:message result)
            response {:bytes data
                      :hex (mapv codec/byte->hex data)
                      :status status
                      :timestamp-us timestamp-us}]
        (cond
          (:exception result)
          (exception-result base failed-error (:exception result))

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
      (catch Throwable e
        (exception-result base failed-error e)))))
