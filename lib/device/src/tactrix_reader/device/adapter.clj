(ns tactrix-reader.device.adapter
  "Adapter-local checks that send no vehicle bus traffic: battery voltage on
  OBD-II pin 16, host and vehicle connection status, and a description of
  this backend."
  (:require [clojure.string :as str]
            [tactrix-reader.device.serial :as serial]
            [tactrix-reader.device.usb :as usb]))

(def vbat-pin
  "OBD-II pin 16 carries vehicle battery +12V."
  16)

(def vbat-command (str "atr " vbat-pin "\r\n"))

(defn classify-vbat
  "Classifies an adapter-reported battery voltage."
  [volts]
  (cond
    (nil? volts) :unknown
    (>= volts 9.0) :present
    (< volts 3.0) :absent
    :else :unknown))

(defn parse-pin-voltage-response
  "Parses an OpenPort reply to `atr <pin>`.

  A successful read looks like `arr 16 12400`, where the value is in
  millivolts. Errors look like `are 19`, where the code matches a J2534 status
  code (19 is ERR_PIN_INVALID)."
  [response]
  (let [line (str/trim (str response))]
    (if-let [[_ pin millivolts] (re-matches #"arr (\d+) (\d+)" line)]
      {:ok? true
       :pin (Integer/parseInt pin)
       :millivolts (Integer/parseInt millivolts)}
      (if-let [[_ code] (re-matches #"are (\d+)" line)]
        {:ok? false
         :error :adapter-error
         :adapter/error-code (Integer/parseInt code)}
        {:ok? false
         :error :unexpected-response}))))

(defn- ascii->hex [s]
  (apply str (map #(format "%02X" (int %)) s)))

(defn read-vbat
  "Reads adapter-reported vehicle battery voltage from OBD-II pin 16.

  Sends the adapter-local `atr 16` command; no vehicle bus traffic is sent.
  Returns data instead of throwing so callers can distinguish a failed read
  from vehicle absence."
  ([device-or-path]
   (read-vbat device-or-path {}))
  ([device-or-path opts]
   (let [path (if (map? device-or-path) (:path device-or-path) device-or-path)
         exchange (serial/serial-exchange path (ascii->hex vbat-command) opts)
         response (apply str (map char (get-in exchange [:response :bytes])))
         parsed (when (:ok? exchange)
                  (parse-pin-voltage-response response))
         failure {:ok? false
                  :backend :mac-serial
                  :path path
                  :state :unknown
                  :volts nil}]
     (cond
       (not (:ok? exchange))
       (merge failure (select-keys exchange [:error :message]))

       (and (:ok? parsed) (= vbat-pin (:pin parsed)))
       (let [volts (/ (:millivolts parsed) 1000.0)]
         {:ok? true
          :backend :mac-serial
          :path path
          :pin vbat-pin
          :millivolts (:millivolts parsed)
          :volts volts
          :state (classify-vbat volts)
          :response response})

       (:ok? parsed)
       (assoc failure
              :error :unexpected-pin
              :response response)

       :else
       (merge failure
              (dissoc parsed :ok?)
              {:response response})))))

(defn- vehicle-power-status [vbat-result]
  (if (:ok? vbat-result)
    {:state (classify-vbat (:volts vbat-result))
     :volts (:volts vbat-result)}
    {:state (or (:state vbat-result) :unknown)
     :volts (:volts vbat-result)
     :error (:error vbat-result)}))

(defn vehicle-status
  "Returns host and vehicle connection status for the first detected device or
  a supplied device/path.

  On the current macOS serial backend this reports real host presence/openability
  and vehicle power from the adapter's pin 16 voltage. It sends only the
  adapter-local Vbat read and no vehicle bus traffic."
  ([]
   (vehicle-status (first (usb/scan-devices))))
  ([device-or-path]
   (let [device (when (map? device-or-path) device-or-path)
         path (if (map? device-or-path) (:path device-or-path) device-or-path)
         device-present? (boolean (or device path))
         open-result (when path (usb/open-device! path))
         openable? (true? (:ok? open-result))
         vbat-result (when openable? (read-vbat (or device path)))
         vehicle-power (vehicle-power-status vbat-result)
         reasons (cond-> []
                   (not device-present?)
                   (conj :device-not-detected)

                   (and device-present? (not path))
                   (conj :device-path-not-detected)

                   (and path (not openable?))
                   (conj (or (:error open-result) :device-open-failed))

                   openable?
                   (conj :host-device-openable)

                   (:error vehicle-power)
                   (conj (:error vehicle-power)))]
     {:backend :mac-serial
      :device (when device
                (select-keys device [:vendor :product :serial :vendor-id :product-id :path :paths :source]))
      :path path
      :host/device-present? device-present?
      :host/openable? openable?
      :host/open-result open-result
      :vehicle/power vehicle-power
      :vehicle/vbat-result vbat-result
      :vehicle/ecu {:state :unsupported}
      :confidence (if openable? :medium :low)
      :reasons reasons})))

(defn direct-backend-info
  "Describes the in-house adapter backend: what it implements, which operations
  send traffic on the vehicle bus, and the policy for protocol discovery."
  []
  {:backend :mac-serial
   :external-libraries? false
   :transport {:kind :macos-usbmodem
               :paths (usb/serial-device-paths)}
   :implemented [:scan-devices
                 :open-device
                 :vehicle-status-host-fields
                 :observe-device
                 :serial-exchange
                 :run-probe
                 :hex-request-parser
                 :openport-command-framing
                 :read-vbat
                 :obd-request
                 :read-pid
                 :read-all-pids
                 :read-vehicle-info
                 :read-trouble-codes
                 :ssm-init
                 :ssm-read
                 :can-session
                 :record
                 :export-recording
                 :live-source
                 :replay-source]
   :not-implemented [:j2534-pass-thru-api
                     :k-line-session]
   :vehicle-bus {:read-only? true
                 :operations [:obd-request
                              :read-pid
                              :read-all-pids
                              :read-vehicle-info
                              :read-trouble-codes
                              :ssm-init
                              :ssm-read
                              :record
                              :live-source]}
   :discovery-policy {:writes :explicit-request-only
                      :sweeps? false
                      :vehicle-bus-traffic? false}})
