(ns tactrix-reader.dashboard.bus
  "The latest frame of each CAN ID and which of its bits have changed, for
  the bus view. Pure, so it has tests.

  A frame is `{:id :data [byte ...] :count :first-us :timestamp-us}` as the
  hub sends it, plus `:flips`, how many batches each bit has changed in since
  the baseline (eight counts per byte, bit 0 first), and, once a baseline is
  marked, `:baseline`, the data then, and `:noise`, a mask per byte of the
  bits that had already changed before it. A bit that changes after the
  baseline and is not noise is new: it is what moved when a control was
  operated.")

(defn- zeros [n]
  (vec (repeat n 0)))

(defn- add-flips
  "Counts one more change for each bit set in `changed`, a mask per byte."
  [flips changed]
  (reduce-kv (fn [flips i mask]
               (if (zero? mask)
                 flips
                 (reduce (fn [flips bit]
                           (cond-> flips
                             (bit-test mask bit) (update (+ (* 8 i) bit) inc)))
                         flips
                         (range 8))))
             flips
             (vec changed)))

(defn- merge-frame [previous {:keys [data changed] :as frame}]
  (let [size (count data)
        same-size? (= size (count (:data previous)))
        flips (if same-size? (:flips previous) (zeros (* 8 size)))]
    (cond-> (-> (dissoc frame :changed)
                (assoc :flips (if (= size (count changed)) (add-flips flips changed) flips)))
      (and same-size? (:baseline previous)) (assoc :baseline (:baseline previous)
                                                   :noise (:noise previous)))))

(defn merge-frames
  "Adds the hub's `batch-frames` to `frames`, a map by CAN ID."
  [frames batch-frames]
  (reduce (fn [frames {:keys [id] :as frame}]
            (assoc frames id (merge-frame (get frames id) frame)))
          (or frames {})
          batch-frames))

(defn- flipped-mask
  "A mask per byte of the bits `flips` counts a change for."
  [flips]
  (mapv (fn [counts]
          (reduce (fn [mask bit] (cond-> mask (pos? (nth counts bit)) (bit-or (bit-shift-left 1 bit))))
                  0
                  (range 8)))
        (partition 8 flips)))

(defn mark-baseline
  "Starts over from now: every bit that has changed so far becomes noise,
  and the counts restart."
  [frames]
  (update-vals frames
               (fn [{:keys [data flips noise] :as frame}]
                 (assoc frame
                        :baseline data
                        :noise (mapv bit-or (or noise (zeros (count data))) (flipped-mask flips))
                        :flips (zeros (* 8 (count data)))))))

(defn clear-baseline [frames]
  (update-vals frames #(-> (dissoc % :baseline :noise)
                           (assoc :flips (zeros (* 8 (count (:data %))))))))

(defn bit-state
  "How a bit of a frame should be shown: `:new` when it has changed since
  the baseline and had not before, `:active` when it has changed, else
  `:quiet`."
  [{:keys [flips noise]} byte-index bit]
  (cond
    (zero? (nth flips (+ (* 8 byte-index) bit) 0)) :quiet
    (and noise (not (bit-test (nth noise byte-index 0) bit))) :new
    :else :active))

(defn byte-state
  "The most notable state of a byte's bits."
  [frame byte-index]
  (let [states (set (map #(bit-state frame byte-index %) (range 8)))]
    (some states [:new :active :quiet])))

(defn bit-name
  "A bit as the docs write it, such as `b3.7` for byte 3 bit 7."
  [byte-index bit]
  (str "b" byte-index "." bit))

(defn new-bits
  "Every new bit in `frames`, as `{:id :byte :bit :value :flips}`, by ID,
  byte, and bit."
  [frames]
  (for [[id {:keys [data flips] :as frame}] (sort-by key frames)
        byte-index (range (count data))
        bit (range 8)
        :when (= :new (bit-state frame byte-index bit))]
    {:id id
     :byte byte-index
     :bit bit
     :value (if (bit-test (nth data byte-index) bit) 1 0)
     :flips (nth flips (+ (* 8 byte-index) bit))}))

(defn period-ms
  "The mean time between a CAN ID's frames, or nil with fewer than two."
  [{:keys [first-us timestamp-us] frame-count :count}]
  (when (and first-us timestamp-us (> frame-count 1) (> timestamp-us first-us))
    (/ (- timestamp-us first-us) (dec frame-count) 1000)))

(defn frames-per-second [frames]
  (reduce + 0 (keep #(some->> (period-ms %) (/ 1000)) (vals frames))))
