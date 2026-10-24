(ns tactrix-reader.engine-server.test-runner
  (:require [clojure.test :as test]
            [tactrix-reader.engine-server.hub-test]
            [tactrix-reader.engine-server.server-test]))

(defn -main [& _]
  (let [{:keys [fail error]} (test/run-tests 'tactrix-reader.engine-server.hub-test
                                             'tactrix-reader.engine-server.server-test)]
    (shutdown-agents)
    (System/exit (if (pos? (+ fail error)) 1 0))))
