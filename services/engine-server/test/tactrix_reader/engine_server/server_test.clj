(ns tactrix-reader.engine-server.server-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [cognitect.transit :as transit]
            [tactrix-reader.device.adapter :as adapter]
            [tactrix-reader.device.obd :as obd]
            [tactrix-reader.device.source :as source]
            [tactrix-reader.device.usb :as usb]
            [tactrix-reader.engine-server.main :as main]
            [tactrix-reader.engine-server.server :as server]))

(defn- u32-bytes [n]
  (map #(bit-and (bit-shift-right n %) 0xFF) [24 16 8 0]))

(defn- message-bytes [timestamp-us id data]
  (let [body (concat [0] (u32-bytes timestamp-us) (u32-bytes id) data)]
    (concat (map int "ar5") [(count body)] body)))

(defn- recording-dir
  "A tiny recording with one RPM and one coolant reply."
  []
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "recording" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (java.nio.file.Files/write (.toPath (java.io.File. dir "raw.bin"))
                               (byte-array (map unchecked-byte
                                                (concat (message-bytes 1000000 0x7E8 [4 0x41 0x0C 0x0C 0x80 0 0 0])
                                                        (message-bytes 1010000 0x7E8 [3 0x41 0x05 0x55 0 0 0 0]))))
                               (make-array java.nio.file.OpenOption 0))
    (spit (java.io.File. dir "meta.edn") (pr-str {:polling {:rx-id 0x7E8}}))
    (str dir)))

(defn- free-port []
  (with-open [socket (java.net.ServerSocket. 0)]
    (.getLocalPort socket)))

(defn- read-transit [s]
  (transit/read (transit/reader (java.io.ByteArrayInputStream. (.getBytes ^String s "UTF-8")) :json)))

(defn- get-response [client url handler]
  (.send ^java.net.http.HttpClient client
         (.build (java.net.http.HttpRequest/newBuilder (java.net.URI. url)))
         handler))

(defn- wait-for [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 10) (recur))))))

(defn- open-events
  "Reads SSE events from `url` in the background. Returns an atom of
  [{:event name :data decoded}] and a function that closes the stream."
  [client url]
  (let [response (get-response client url (java.net.http.HttpResponse$BodyHandlers/ofLines))
        lines (.iterator ^java.util.stream.Stream (.body response))
        events (atom [])
        reader (future
                 (loop [event-name nil]
                   (when (.hasNext lines)
                     (let [line (.next lines)]
                       (cond
                         (str/starts-with? line "event: ") (recur (subs line 7))
                         (str/starts-with? line "data: ") (do (swap! events conj {:event event-name
                                                                                  :data (read-transit (subs line 6))})
                                                              (recur nil))
                         :else (recur event-name))))))]
    [events (fn []
              (future-cancel reader)
              (.close ^java.util.stream.Stream (.body response)))]))

(defn- stream-events
  "Reads SSE events from `url` until `done?` returns true for the events so
  far, or `timeout-ms` passes. Returns [{:event name :data decoded}]."
  [client url done? timeout-ms]
  (let [[events close] (open-events client url)]
    (try
      (wait-for #(done? @events) timeout-ms)
      @events
      (finally
        (close)))))

(defn- post-transit [client url content-type body]
  (let [response (.send ^java.net.http.HttpClient client
                        (-> (java.net.http.HttpRequest/newBuilder (java.net.URI. url))
                            (.header "Content-Type" content-type)
                            (.POST (java.net.http.HttpRequest$BodyPublishers/ofString (server/->transit body)))
                            .build)
                        (java.net.http.HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :body (read-transit (.body response))}))

(defn- raw-status-line
  "Sends a raw HTTP request, for headers HttpClient will not set, and returns
  the response's status line."
  [port request]
  (with-open [socket (java.net.Socket. "localhost" (int port))]
    (doto (.getOutputStream socket)
      (.write (.getBytes ^String request "UTF-8"))
      (.flush))
    (first (line-seq (io/reader (.getInputStream socket))))))

(deftest streams-replayed-samples-over-sse
  (let [port (free-port)
        system (main/start-system {:source {:type :replay :recording (recording-dir)}
                                   :port port})
        client (java.net.http.HttpClient/newHttpClient)
        base (str "http://localhost:" port)]
    (try
      (let [page (get-response client (str base "/") (java.net.http.HttpResponse$BodyHandlers/ofString))]
        (is (= 200 (.statusCode page)))
        (is (str/includes? (.body page) "/assets/js/main.js"))
        (is (= "default-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'"
               (.orElse (.firstValue (.headers page) "Content-Security-Policy") nil))))
      (let [events (stream-events client (str base "/stream")
                                  (fn [events]
                                    (some #(some #{:engine-rpm} (map :name (get-in % [:data :samples])))
                                          (filter (comp #{"samples"} :event) events)))
                                  5000)]
        (is (vector? events))
        (is (= "snapshot" (:event (first events))))
        (is (= {:pid 0x0C :name :engine-rpm :value 800.0 :unit :rpm}
               (-> (filter (comp #{"samples"} :event) events)
                   (->> (mapcat #(get-in % [:data :samples])))
                   (->> (filter (comp #{:engine-rpm} :name)))
                   first
                   (dissoc :timestamp-us)))))
      (let [snapshot (get-response client (str base "/api/snapshot") (java.net.http.HttpResponse$BodyHandlers/ofString))]
        (is (= {:source :replay :state :running}
               (select-keys (:status (read-transit (.body snapshot))) [:source :state]))))
      (finally
        (main/stop-system system)))))

(def ^:private fake-adapter
  {:vendor "Tactrix Inc."
   :product "OpenPort 2.0"
   :serial "TAlgJ3P8"
   :path "/dev/cu.usbmodemTAlgJ3P81"
   :paths ["/dev/cu.usbmodemTAlgJ3P81"]
   :source :ioreg})

(defn- fake-live-source [path {:keys [emit]}]
  (emit {:type :status :source :live :path path :state :running :firmware "1.17.4877"})
  {:stop! (fn []
            (emit {:type :status :source :live :path path :state :stopped}))})

(defn- event-where [events event-name pred]
  (some #(when (and (= event-name (:event %)) (pred (:data %))) (:data %)) events))

(deftest lists-adapters-and-switches-sources
  (with-redefs [usb/scan-devices (fn [] [fake-adapter])
                adapter/read-vbat (fn [_] {:ok? true :volts 12.3 :state :present})
                obd/read-vehicle-info (fn [path]
                                        {:ok? true
                                         :path path
                                         :vin "TESTVIN0123456789"
                                         :calibration-ids ["EA1T002C"]
                                         :ecu-name nil
                                         :ecu-id nil
                                         :errors {:ecu-name {:error :negative-response :nrc 0x12}
                                                  :ecu-id {:error :timeout}}})
                obd/read-trouble-codes (fn [path]
                                         {:ok? true
                                          :path path
                                          :stored ["P0133"]
                                          :pending []
                                          :permanent nil
                                          :errors {:permanent {:error :timeout}}})
                source/start-live-source! fake-live-source]
    (let [port (free-port)
          system (main/start-system {:source {:type :replay :recording (recording-dir)}
                                     :port port})
          client (java.net.http.HttpClient/newHttpClient)
          base (str "http://localhost:" port)
          [events close] (open-events client (str base "/stream"))]
      (try
        (let [adapters (get-response client (str base "/api/adapters") (java.net.http.HttpResponse$BodyHandlers/ofString))]
          (is (= 200 (.statusCode adapters)))
          (is (= {:adapters [(dissoc fake-adapter :source)]}
                 (read-transit (.body adapters)))))
        (is (= {:status 415 :body {:error :unsupported-content-type}}
               (post-transit client (str base "/api/source") "text/plain" {:type :none})))
        (is (= {:status 400 :body {:error :unknown-adapter}}
               (post-transit client (str base "/api/source") "application/transit+json"
                             {:type :live :path "/dev/cu.Bluetooth-Incoming-Port"})))
        (is (= {:status 400 :body {:error :unknown-source-type}}
               (post-transit client (str base "/api/source") "application/transit+json" {:type :file})))
        (is (str/includes? (raw-status-line port (str "GET /api/adapters HTTP/1.1\r\n"
                                                      "Host: attacker.example:" port "\r\n"
                                                      "Connection: close\r\n\r\n"))
                           " 403"))

        (is (wait-for #(or (event-where @events "status" (comp #{:replay} :source))
                           (event-where @events "snapshot" (comp #{:replay} :source :status)))
                      5000))
        (is (= {:status 202 :body {:source {:type :live :path "/dev/cu.usbmodemTAlgJ3P81"}}}
               (post-transit client (str base "/api/source") "application/transit+json"
                             {:type :live :path "/dev/cu.usbmodemTAlgJ3P81"})))
        (is (wait-for #(event-where @events "status" (comp #{:running} :state)) 5000))
        (let [after-switch (drop-while #(not= {:event "snapshot" :data {:status nil :info nil :samples [] :signals [] :dids []}} %)
                                       @events)]
          (is (seq after-switch) "the switch resets the dashboard")
          (is (= {:source :live
                  :adapter {:vendor "Tactrix Inc."
                            :product "OpenPort 2.0"
                            :serial "TAlgJ3P8"
                            :path "/dev/cu.usbmodemTAlgJ3P81"
                            :battery-volts 12.3}
                  :vehicle {:vin "TESTVIN0123456789"
                            :calibration-ids ["EA1T002C"]
                            :ecu-name nil
                            :ecu-id nil
                            :errors {:ecu-name {:error :negative-response :nrc 0x12}
                                     :ecu-id {:error :timeout}}}
                  :trouble-codes {:stored ["P0133"]
                                  :pending []
                                  :permanent nil
                                  :errors {:permanent {:error :timeout}}}}
                 (event-where after-switch "info" (constantly true))))
          (is (= [:connecting :identifying :running]
                 (map :state (filter (comp #{:live} :source) (keep #(when (= "status" (:event %)) (:data %)) after-switch))))))

        (is (= 202 (:status (post-transit client (str base "/api/source") "application/transit+json" {:type :none}))))
        (is (wait-for #(event-where @events "status" (comp #{:none} :source)) 5000))
        (is (event-where @events "status" #(= {:source :live :state :stopped} (select-keys % [:source :state])))
            "the live source is stopped before the switch")
        (finally
          (close)
          (main/stop-system system))))))
