(ns tactrix-reader.device.pids
  "OBD-II mode 01 PIDs: the `mode-01-pids` table with each PID's SAE J1979
  formula, and decoding of mode 01 replies. Add a PID to the table and to
  docs/obd-pids.md together."
  (:require [tactrix-reader.device.codec :as codec]))

(defn supported-pids
  "Decodes a 4-byte supported-PIDs bitmap. Bit 7 of the first byte is PID
  `base + 1`."
  [base]
  (fn [[a b c d]]
    (let [bits (codec/u32 [a b c d])]
      (vec (for [i (range 32)
                 :when (bit-test bits (- 31 i))]
             (+ base 1 i))))))

(defn round-to [places x]
  (let [scale (Math/pow 10 places)]
    (/ (Math/round (* x scale)) scale)))

(defn percent [[a]]
  (round-to 1 (/ (* 100.0 a) 255)))

(defn- fuel-trim [[a]]
  (round-to 1 (/ (* 100.0 (- a 128)) 128)))

(defn- lambda
  "Air-fuel equivalence ratio from two bytes; 1.0 is stoichiometric."
  [bytes]
  (round-to 3 (/ (* 2.0 (codec/word bytes)) 65536)))

(defn- catalyst-temperature [bytes]
  (round-to 1 (- (/ (codec/word bytes) 10.0) 40)))

(def ^:private fuel-system-states
  {0 :none
   1 :open-loop-cold
   2 :closed-loop
   4 :open-loop-load-or-decel
   8 :open-loop-failure
   16 :closed-loop-feedback-fault})

(defn- fuel-system-status
  "Bytes A/B are fuel systems 1/2. Unrecognised codes are returned as numbers."
  [[a b]]
  {:system-1 (get fuel-system-states a a)
   :system-2 (get fuel-system-states b b)})

(defn- oxygen-sensors-present
  "Bits 0-3 are bank 1 sensors 1-4; bits 4-7 are bank 2 sensors 1-4."
  [[a]]
  (vec (for [bit (range 8)
             :when (bit-test a bit)]
         (keyword (str "bank-" (inc (quot bit 4)) "-sensor-" (inc (mod bit 4)))))))

(defn- narrowband-oxygen-sensor
  "A narrowband sensor's voltage and the fuel trim it drives. A trim byte of
  0xFF means the sensor is not used for trim."
  [[a b]]
  {:volts (/ a 200.0)
   :fuel-trim-percent (when (not= 0xFF b) (fuel-trim [b]))})

(defn- wideband-oxygen-sensor-voltage [[a b c d]]
  {:lambda (lambda [a b])
   :volts (round-to 3 (/ (* 8.0 (codec/word [c d])) 65536))})

(defn- wideband-oxygen-sensor-current [[a b c d]]
  {:lambda (lambda [a b])
   :milliamps (round-to 3 (- (/ (codec/word [c d]) 256.0) 128))})

(def ^:private fuel-types
  [:not-available :gasoline :methanol :ethanol :diesel :lpg :cng :propane
   :electric :bifuel-gasoline :bifuel-methanol :bifuel-ethanol :bifuel-lpg
   :bifuel-cng :bifuel-propane :bifuel-electric :bifuel-electric-and-combustion
   :hybrid-gasoline :hybrid-ethanol :hybrid-diesel :hybrid-electric
   :hybrid-electric-and-combustion :hybrid-regenerative :bifuel-diesel])

(defn- fuel-type
  "Unrecognised codes are returned as numbers."
  [[a]]
  (get fuel-types a a))

(def ^:private obd-standards
  {1 :obd-ii-carb
   2 :obd-epa
   3 :obd-and-obd-ii
   4 :obd-i
   5 :not-obd-compliant
   6 :eobd
   7 :eobd-and-obd-ii
   8 :eobd-and-obd
   9 :eobd-obd-and-obd-ii
   10 :jobd
   11 :jobd-and-obd-ii
   12 :jobd-and-eobd
   13 :jobd-eobd-and-obd-ii
   17 :emd
   18 :emd-plus
   19 :hd-obd-c
   20 :hd-obd
   21 :wwh-obd})

(defn- obd-standard
  "Unrecognised codes are returned as numbers."
  [[a]]
  (get obd-standards a a))

(defn- auxiliary-io-status
  "Byte A says which inputs are reported; byte B holds their states. Only
  reported inputs are listed. The transmission bits are 0 in neutral, which
  was confirmed on a manual vehicle."
  [[a b]]
  (into {}
        (for [[bit key] [[0 :pto-active?]
                         [1 :automatic-transmission-in-drive?]
                         [2 :manual-transmission-in-gear?]
                         [3 :glow-plug-lamp-on?]]
              :when (bit-test a bit)]
          [key (bit-test b bit)])))

(defn temperature [[a]]
  (- a 40))

(def ^:private spark-ignition-monitors
  "Monitors in bytes C/D of PIDs 01 and 41, by bit, for gasoline engines."
  [:catalyst :heated-catalyst :evaporative-system :secondary-air-system
   :ac-refrigerant :oxygen-sensor :oxygen-sensor-heater :egr-system])

(def ^:private compression-ignition-monitors
  "Monitors in bytes C/D of PIDs 01 and 41, by bit, for diesel engines. Bits 2
  and 4 are reserved."
  [:nmhc-catalyst :nox-scr nil :boost-pressure
   nil :exhaust-gas-sensor :pm-filter :egr-vvt-system])

(defn- monitor-status
  "Decodes bytes B-D of PIDs 01 and 41. Only monitors the vehicle has are
  listed, each as `:complete` or `:incomplete`."
  [[_ b c d]]
  (let [compression? (bit-test b 3)
        common (for [[bit monitor] [[0 :misfire] [1 :fuel-system] [2 :components]]
                     :when (bit-test b bit)]
                 [monitor (if (bit-test b (+ bit 4)) :incomplete :complete)])
        specific (for [[bit monitor] (map-indexed vector (if compression?
                                                           compression-ignition-monitors
                                                           spark-ignition-monitors))
                       :when (and monitor (bit-test c bit))]
                   [monitor (if (bit-test d bit) :incomplete :complete)])]
    {:ignition (if compression? :compression :spark)
     :monitors (into (sorted-map) (concat common specific))}))

(defn- monitor-status-since-codes-cleared [[a :as bytes]]
  (merge {:mil-on? (bit-test a 7)
          :dtc-count (bit-and a 0x7F)}
         (monitor-status bytes)))

(def mode-01-pids
  "Common OBD-II mode 01 PIDs with their SAE J1979 formulas."
  {0x00 {:name :supported-pids-01-20 :byte-count 4 :decode (supported-pids 0x00) :pid-list? true}
   0x01 {:name :monitor-status :byte-count 4 :decode monitor-status-since-codes-cleared}
   0x03 {:name :fuel-system-status :byte-count 2 :decode fuel-system-status}
   0x04 {:name :engine-load :byte-count 1 :unit :percent :decode percent}
   0x05 {:name :coolant-temp :byte-count 1 :unit :celsius :decode temperature}
   0x06 {:name :short-term-fuel-trim-bank-1 :byte-count 1 :unit :percent :decode fuel-trim}
   0x07 {:name :long-term-fuel-trim-bank-1 :byte-count 1 :unit :percent :decode fuel-trim}
   0x0B {:name :intake-manifold-pressure :byte-count 1 :unit :kpa :decode first}
   0x0C {:name :engine-rpm :byte-count 2 :unit :rpm :decode #(/ (codec/word %) 4.0)}
   0x0D {:name :vehicle-speed :byte-count 1 :unit :km-per-hour :decode first}
   0x0E {:name :timing-advance :byte-count 1 :unit :degrees :decode (fn [[a]] (- (/ a 2.0) 64))}
   0x0F {:name :intake-air-temp :byte-count 1 :unit :celsius :decode temperature}
   0x10 {:name :maf-air-flow :byte-count 2 :unit :grams-per-second :decode #(/ (codec/word %) 100.0)}
   0x11 {:name :throttle-position :byte-count 1 :unit :percent :decode percent}
   0x13 {:name :oxygen-sensors-present :byte-count 1 :decode oxygen-sensors-present}
   0x15 {:name :oxygen-sensor-bank-1-sensor-2 :byte-count 2 :decode narrowband-oxygen-sensor}
   0x1C {:name :obd-standard :byte-count 1 :decode obd-standard}
   0x1F {:name :run-time :byte-count 2 :unit :seconds :decode codec/word}
   0x20 {:name :supported-pids-21-40 :byte-count 4 :decode (supported-pids 0x20) :pid-list? true}
   0x21 {:name :distance-with-mil-on :byte-count 2 :unit :km :decode codec/word}
   0x24 {:name :wideband-oxygen-sensor-1-voltage :byte-count 4 :decode wideband-oxygen-sensor-voltage}
   0x2C {:name :commanded-egr :byte-count 1 :unit :percent :decode percent}
   0x2D {:name :egr-error :byte-count 1 :unit :percent :decode fuel-trim}
   0x2E {:name :commanded-evap-purge :byte-count 1 :unit :percent :decode percent}
   0x2F {:name :fuel-level :byte-count 1 :unit :percent :decode percent}
   0x30 {:name :warm-ups-since-codes-cleared :byte-count 1 :unit :count :decode first}
   0x31 {:name :distance-since-codes-cleared :byte-count 2 :unit :km :decode codec/word}
   0x33 {:name :barometric-pressure :byte-count 1 :unit :kpa :decode first}
   0x34 {:name :wideband-oxygen-sensor-1-current :byte-count 4 :decode wideband-oxygen-sensor-current}
   0x3C {:name :catalyst-temp-bank-1-sensor-1 :byte-count 2 :unit :celsius :decode catalyst-temperature}
   0x3D {:name :catalyst-temp-bank-2-sensor-1 :byte-count 2 :unit :celsius :decode catalyst-temperature}
   0x3E {:name :catalyst-temp-bank-1-sensor-2 :byte-count 2 :unit :celsius :decode catalyst-temperature}
   0x3F {:name :catalyst-temp-bank-2-sensor-2 :byte-count 2 :unit :celsius :decode catalyst-temperature}
   0x40 {:name :supported-pids-41-60 :byte-count 4 :decode (supported-pids 0x40) :pid-list? true}
   0x41 {:name :monitor-status-this-drive-cycle :byte-count 4 :decode monitor-status}
   0x42 {:name :control-module-voltage :byte-count 2 :unit :volts :decode #(/ (codec/word %) 1000.0)}
   0x43 {:name :absolute-load :byte-count 2 :unit :percent :decode #(round-to 1 (/ (* 100.0 (codec/word %)) 255))}
   0x44 {:name :commanded-lambda :byte-count 2 :unit :lambda :decode lambda}
   0x45 {:name :relative-throttle-position :byte-count 1 :unit :percent :decode percent}
   0x46 {:name :ambient-air-temp :byte-count 1 :unit :celsius :decode temperature}
   0x47 {:name :absolute-throttle-position-b :byte-count 1 :unit :percent :decode percent}
   0x48 {:name :absolute-throttle-position-c :byte-count 1 :unit :percent :decode percent}
   0x49 {:name :accelerator-pedal-position-d :byte-count 1 :unit :percent :decode percent}
   0x4A {:name :accelerator-pedal-position-e :byte-count 1 :unit :percent :decode percent}
   0x4B {:name :accelerator-pedal-position-f :byte-count 1 :unit :percent :decode percent}
   0x4C {:name :commanded-throttle-actuator :byte-count 1 :unit :percent :decode percent}
   0x4D {:name :time-with-mil-on :byte-count 2 :unit :minutes :decode codec/word}
   0x4E {:name :time-since-codes-cleared :byte-count 2 :unit :minutes :decode codec/word}
   0x51 {:name :fuel-type :byte-count 1 :decode fuel-type}
   0x53 {:name :evap-vapor-pressure-absolute :byte-count 2 :unit :kpa :decode #(round-to 3 (/ (codec/word %) 200.0))}
   0x5A {:name :relative-accelerator-pedal-position :byte-count 1 :unit :percent :decode percent}
   0x5C {:name :oil-temp :byte-count 1 :unit :celsius :decode temperature}
   0x60 {:name :supported-pids-61-80 :byte-count 4 :decode (supported-pids 0x60) :pid-list? true}
   0x65 {:name :auxiliary-io-status :byte-count 2 :decode auxiliary-io-status}})

(def mode-01-pids-by-name
  "Mode 01 PID numbers by name, such as `:engine-rpm`."
  (into {} (map (fn [[pid {:keys [name]}]] [name pid])) mode-01-pids))

(defn decode-mode-01
  "Decodes a positive mode 01 reply such as `[0x41 0x0C 0x0C 0x80]`."
  [data]
  (let [[service pid & args] data
        args (vec args)
        {:keys [name byte-count unit decode pid-list?]} (get mode-01-pids pid)]
    (cond
      (not= 0x41 service)
      {:ok? false
       :error :not-mode-01-reply}

      (nil? name)
      {:ok? false
       :pid pid
       :error :unknown-pid
       :raw args}

      (< (count args) byte-count)
      {:ok? false
       :pid pid
       :name name
       :error :short-reply
       :raw args}

      :else
      (let [value (decode (subvec args 0 byte-count))]
        (cond-> {:ok? true
                 :pid pid
                 :name name
                 :value value}
          unit (assoc :unit unit)
          pid-list? (assoc :value-hex (mapv codec/byte->hex value)))))))
