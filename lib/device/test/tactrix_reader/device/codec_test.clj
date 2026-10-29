(ns tactrix-reader.device.codec-test
  (:require [clojure.test :refer [deftest is]]
            [tactrix-reader.device.codec :as codec]))

(deftest parses-hex-request-bytes
  (is (= {:ok? true
          :hex "0102FF0A"
          :bytes [1 2 255 10]}
         (codec/hex->bytes "01 02 ff\n0a")))
  (is (= {:ok? false
          :error :empty-hex}
         (codec/hex->bytes "")))
  (is (= {:ok? false
          :error :odd-length-hex
          :hex "ABC"}
         (codec/hex->bytes "AB C")))
  (is (= {:ok? false
          :error :invalid-hex
          :hex "0x01"}
         (codec/hex->bytes "0x01"))))

