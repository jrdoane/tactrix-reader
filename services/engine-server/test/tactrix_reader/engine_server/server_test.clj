(ns tactrix-reader.engine-server.server-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [cognitect.transit :as transit]
            [tactrix-reader.device.adapter :as adapter]
            [tactrix-reader.device.obd :as obd]
            [tactrix-reader.device.source :as source]
            [tactrix-reader.device.uds :as uds]
            [tactrix-reader.device.usb :as usb]
            [tactrix-reader.engine-server.main :as main]
            [tactrix-reader.engine-server.server :as server]))

(defn- u32-bytes [n]
  (map #(bit-and (bit-shift-right n %) 0xFF) [24 16 8 0]))

(defn- message-bytes [timestamp-us id data]
  (let [body (concat [0] (u32-bytes timestamp-us) (u32-bytes id) data)]
    (concat (map int "ar5") [(count body)] body)))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "recordings" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- recording-dir
  "A tiny recording with one RPM and one coolant reply, in `root` (by default
  a new temporary directory) under `name`."
  ([]
   (recording-dir (temp-dir) "tiny" {}))
  ([root name meta]
   (let [dir (java.io.File. ^java.io.File root ^String name)]
    (.mkdirs dir)
    (java.nio.file.Files/write (.toPath (java.io.File. dir "raw.bin"))
                               (byte-array (map unchecked-byte
                                                (concat (message-bytes 1000000 0x7E8 [4 0x41 0x0C 0x0C 0x80 0 0 0])
                                                        (message-bytes 1010000 0x7E8 [3 0x41 0x05 0x55 0 0 0 0]))))
                               (make-array java.nio.file.OpenOption 0))
    (spit (java.io.File. dir "meta.edn") (pr-str (merge {:polling {:rx-id 0x7E8}} meta)))
    (str dir))))

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
  (let [system (main/start-system {:source {:type :replay :recording (recording-dir)}
                                   :port 0})
        port (:port system)
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

(defn- fake-live-source
  "Stands in for `source/start-live-source!`, recording the options it was
  started with in `started` and keeping a pretend recording."
  [started]
  (fn [path {:keys [emit] :as opts}]
    (swap! started conj (dissoc opts :emit))
    (emit {:type :status :source :live :path path :state :running :firmware "1.17.4877"})
    (let [recording (atom nil)
          finish! (fn [stopped-by]
                    (when-let [{:keys [dir]} (first (reset-vals! recording nil))]
                      (emit {:type :recording :state :saved :dir dir :stopped-by stopped-by})
                      {:out-dir dir :stopped-by stopped-by}))]
      {:stop! (fn []
                (finish! :source-stopped)
                (emit {:type :status :source :live :path path :state :stopped}))
       :start-recording! (fn [dir meta]
                           (if @recording
                             {:ok? false :error :already-recording}
                             (do (reset! recording {:dir dir :meta meta})
                                 (emit {:type :recording :state :recording :dir dir :meta meta})
                                 {:ok? true :name (.getName (java.io.File. ^String dir)) :dir dir})))
       :stop-recording! #(finish! :stop)})))

(defn- event-where [events event-name pred]
  (some #(when (and (= event-name (:event %)) (pred (:data %))) (:data %)) events))

(deftest serves-an-asset-that-changed-while-running
  ;; Assets are served from the classpath, so the test writes one there.
  (let [asset (io/file (.getParentFile (.getParentFile (io/file (io/resource "public/assets/css/app.css"))))
                       "changing-test-asset.css")
        system (main/start-system {:source {:type :none} :port 0})
        client (java.net.http.HttpClient/newHttpClient)
        fetch #(let [response (get-response client (str "http://localhost:" (:port system) "/assets/changing-test-asset.css")
                                            (java.net.http.HttpResponse$BodyHandlers/ofString))]
                 [(.statusCode response) (.body response)])]
    (try
      (spit asset "a { color: red; }")
      (is (= [200 "a { color: red; }"] (fetch)))
      (spit asset "a { color: red; }\nb { color: blue; }")
      (is (= [200 "a { color: red; }\nb { color: blue; }"] (fetch)))
      (finally
        (.delete asset)
        (main/stop-system system)))))

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
                source/start-live-source! (fake-live-source (atom []))]
    (let [root (temp-dir)
          system (main/start-system {:source {:type :replay :recording (recording-dir)}
                                     :port 0
                                     :config {:recordings-dir (str root) :vehicles {}}})
          port (:port system)
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
        (let [after-switch (drop-while #(not= {:event "snapshot" :data {:status nil :info nil :recording nil :position nil :samples [] :signals [] :dids [] :frames []}} %)
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

(defn- get-transit [client url]
  (let [response (get-response client url (java.net.http.HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :body (read-transit (.body response))}))

(deftest lists-and-replays-recordings
  (let [root (temp-dir)
        _ (recording-dir root "2026-10-24-idle" {:recorded-at "2026-10-24T19:30:41Z"
                                                 :duration-ms 60000
                                                 :stopped-by :duration
                                                 :vehicle {:calibration-ids ["EA1T002C"]
                                                           :state "idling"}
                                                 :counts {:frames 2}})
        newer (recording-dir root "2026-10-30-did-drive" {:recorded-at "2026-10-30T12:04:43Z"
                                                          :vehicle {:vin "TESTVIN0123456789"}
                                                          :note "highway"})
        system (main/start-system {:source {:type :none}
                                   :port 0
                                   :config {:recordings-dir (str root)
                                            :vehicles {"EA1T002C" {:description "2015 Subaru Impreza"}}}})
        port (:port system)
        client (java.net.http.HttpClient/newHttpClient)
        base (str "http://localhost:" port)
        [events close] (open-events client (str base "/stream"))]
    (try
      (let [{:keys [status body]} (get-transit client (str base "/api/recordings"))]
        (is (= 200 status))
        (is (= (str root) (:dir body)))
        (is (= ["2026-10-30-did-drive" "2026-10-24-idle"] (map :name (:recordings body))))
        (is (= {:calibration-ids ["EA1T002C"] :state "idling" :description "2015 Subaru Impreza"}
               (:vehicle (second (:recordings body)))))
        (is (not (contains? (second (:recordings body)) :counts))))
      (let [{:keys [status body]} (get-transit client (str base "/api/catalog"))]
        (is (= 200 status))
        (is (= {:kind :signal :name :gear :can-id 0x141 :label "Gear" :group :driver :format :gear}
               (some #(when (= :gear (:name %)) %) (:signals body))))
        (is (= "Supported-DID bitmap" (get-in body [:did-notes 0x1000]))))
      (is (= {:status 400 :body {:error :unknown-recording}}
             (post-transit client (str base "/api/source") "application/transit+json"
                           {:type :replay :recording "../../etc"})))
      (is (= {:status 200
              :body {:name "2026-10-30-did-drive"
                     :bucket-ms 100
                     :duration-ms 10
                     :channels {:pid/engine-rpm {:unit :rpm}
                                :pid/coolant-temp {:unit :celsius}}
                     :series {:pid/engine-rpm {:t [0] :v [800.0]}
                              :pid/coolant-temp {:t [10] :v [45]}}}}
             (get-transit client (str base "/api/recordings/2026-10-30-did-drive/series"))))
      (is (= {:status 404 :body {:error :unknown-recording}}
             (get-transit client (str base "/api/recordings/missing/series"))))
      (is (= {:status 409 :body {:error :not-replaying}}
             (post-transit client (str base "/api/replay") "application/transit+json" {:action :pause})))
      (is (= {:status 202 :body {:source {:type :replay :recording newer}}}
             (post-transit client (str base "/api/source") "application/transit+json"
                           {:type :replay :recording "2026-10-30-did-drive"})))
      (is (wait-for #(event-where @events "info" (comp #{:replay} :source)) 5000))
      ;; The replay reports where it is, and can be paused.
      (is (wait-for #(event-where @events "position" some?) 5000))
      (is (= {:status 202 :body {}}
             (post-transit client (str base "/api/replay") "application/transit+json" {:action :pause})))
      (is (wait-for #(event-where @events "position" :paused?) 5000))
      (is (= {:duration-ms 10 :paused? true :speed 1.0}
             (dissoc (event-where @events "position" :paused?) :position-ms)))
      (is (= {:status 400 :body {:error :invalid-command}}
             (post-transit client (str base "/api/replay") "application/transit+json" {:action :seek})))
      (is (= {:status 400 :body {:error :invalid-command}}
             (post-transit client (str base "/api/replay") "application/transit+json" [:pause])))
      (is (= {:source :replay
              :adapter {}
              :vehicle {:vin "TESTVIN0123456789"}
              :recording {:dir newer
                          :name "2026-10-30-did-drive"
                          :recorded-at "2026-10-30T12:04:43Z"
                          :duration-ms nil
                          :note "highway"}}
             (event-where @events "info" (comp #{:replay} :source))))
      (is (= {:status 409 :body {:error :not-live}}
             (post-transit client (str base "/api/recording") "application/transit+json" {:action :start})))
      (is (= {:status 400 :body {:error :unknown-action}}
             (post-transit client (str base "/api/recording") "application/transit+json" {:action :delete})))
      (finally
        (close)
        (main/stop-system system)))))

(deftest records-the-live-source
  (let [started (atom [])
        root (temp-dir)]
    (with-redefs [usb/scan-devices (fn [] [fake-adapter])
                  adapter/read-vbat (fn [_] {:ok? true :volts 12.3 :state :present})
                  obd/read-vehicle-info (fn [path]
                                          {:ok? true :path path :vin "TESTVIN0123456789"
                                           :calibration-ids ["EA1T002C"] :ecu-id nil
                                           :errors {:ecu-id {:error :timeout}}})
                  obd/read-trouble-codes (fn [path] {:ok? true :path path :stored []})
                  source/start-live-source! (fake-live-source started)]
      (let [system (main/start-system {:source {:type :none}
                                       :port 0
                                       :config {:recordings-dir (str root) :vehicles {}}})
            port (:port system)
            client (java.net.http.HttpClient/newHttpClient)
            base (str "http://localhost:" port)
            post #(post-transit client (str base %1) "application/transit+json" %2)
            [events close] (open-events client (str base "/stream"))]
        (try
          (is (= 202 (:status (post "/api/source" {:type :live :path "/dev/cu.usbmodemTAlgJ3P81" :dids? true}))))
          (is (wait-for #(event-where @events "status" (comp #{:running} :state)) 5000))
          (is (= [{:dids uds/scanned-dids :frames? true}] @started) "Poll DIDs adds the scanned DIDs")
          (let [{:keys [status body]} (post "/api/recording" {:action :start :note "commute"})]
            (is (= 202 status))
            (is (re-matches #"\d{4}-\d{2}-\d{2}-\d{6}" (:name body)))
            (is (= (str (java.io.File. root ^String (:name body))) (:dir body))))
          (is (= {:status 409 :body {:error :already-recording}}
                 (post "/api/recording" {:action :start})))
          (is (= {:vehicle {:vin "TESTVIN0123456789" :calibration-ids ["EA1T002C"]}
                  :note "commute"}
                 (:meta (event-where @events "recording" (comp #{:recording} :state)))))
          (is (= {:status 202 :body {:stopped-by :stop}}
                 (update (post "/api/recording" {:action :stop}) :body select-keys [:stopped-by])))
          (is (= {:status 409 :body {:error :not-recording}}
                 (post "/api/recording" {:action :stop})))

          (is (= 202 (:status (post "/api/recording" {:action :start}))))
          (is (= 202 (:status (post "/api/source" {:type :none}))))
          (is (wait-for #(event-where @events "recording" (comp #{:source-stopped} :stopped-by)) 5000)
              "switching away saves the recording")
          (finally
            (close)
            (main/stop-system system)))))))
