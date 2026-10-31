(ns tactrix-reader.device-test-runner
  (:require [clojure.test :as test]
            [tactrix-reader.device.adapter-test]
            [tactrix-reader.device.broadcast-test]
            [tactrix-reader.device.can-test]
            [tactrix-reader.device.codec-test]
            [tactrix-reader.device.config-test]
            [tactrix-reader.device.obd-test]
            [tactrix-reader.device.openport-test]
            [tactrix-reader.device.pids-test]
            [tactrix-reader.device.probe-test]
            [tactrix-reader.device.recording-test]
            [tactrix-reader.device.serial-test]
            [tactrix-reader.device.source-test]
            [tactrix-reader.device.ssm-test]
            [tactrix-reader.device.uds-test]
            [tactrix-reader.device.usb-test]))

(defn -main [& _]
  (let [{:keys [fail error]} (test/run-tests 'tactrix-reader.device.adapter-test
                                             'tactrix-reader.device.broadcast-test
                                             'tactrix-reader.device.can-test
                                             'tactrix-reader.device.codec-test
                                             'tactrix-reader.device.config-test
                                             'tactrix-reader.device.obd-test
                                             'tactrix-reader.device.openport-test
                                             'tactrix-reader.device.pids-test
                                             'tactrix-reader.device.probe-test
                                             'tactrix-reader.device.recording-test
                                             'tactrix-reader.device.serial-test
                                             'tactrix-reader.device.source-test
                                             'tactrix-reader.device.ssm-test
                                             'tactrix-reader.device.uds-test
                                             'tactrix-reader.device.usb-test)]
    (when (pos? (+ fail error))
      (System/exit 1))))
