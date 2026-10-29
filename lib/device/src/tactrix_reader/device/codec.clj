(ns tactrix-reader.device.codec
  "Byte-level helpers shared by the device namespaces: hex parsing and
  formatting, and big-endian integers."
  (:require [clojure.string :as str]))

(defn byte->hex [b]
  (format "%02X" (bit-and b 0xFF)))

(defn hex->bytes
  "Parses a hex byte string into unsigned byte values.

  Whitespace is ignored. Separators such as `0x`, commas, and dashes are not
  accepted so discovery commands stay explicit and copy/paste friendly."
  [s]
  (let [hex (str/replace (str s) #"\s+" "")]
    (cond
      (empty? hex)
      {:ok? false
       :error :empty-hex}

      (odd? (count hex))
      {:ok? false
       :error :odd-length-hex
       :hex hex}

      (not (re-matches #"[0-9A-Fa-f]+" hex))
      {:ok? false
       :error :invalid-hex
       :hex hex}

      :else
      {:ok? true
       :hex (str/upper-case hex)
       :bytes (mapv (fn [pair]
                      (Integer/parseInt pair 16))
                    (map str/join (partition 2 hex)))})))

(defn byte-values->byte-array [values]
  (byte-array (map unchecked-byte values)))

(defn unsigned-bytes [^bytes bytes]
  (mapv #(bit-and % 0xFF) bytes))

(defn u32 [bytes]
  (reduce (fn [acc b] (+ (* acc 256) b)) 0 bytes))

(defn u32->bytes [n]
  (mapv #(bit-and (bit-shift-right n %) 0xFF) [24 16 8 0]))

(defn word [[a b]]
  (+ (* 256 a) b))
