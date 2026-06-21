(ns tactrix-reader.device-test-runner
  (:require [clojure.test :as test]
            [tactrix-reader.device-test]))

(defn -main [& _]
  (let [{:keys [fail error]} (test/run-tests 'tactrix-reader.device-test)]
    (when (pos? (+ fail error))
      (System/exit 1))))
