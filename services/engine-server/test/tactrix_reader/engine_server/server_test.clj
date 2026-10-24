(ns tactrix-reader.engine-server.server-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [cognitect.transit :as transit]
            [tactrix-reader.engine-server.main :as main]))

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

(defn- stream-events
  "Reads SSE events from `url` until `done?` returns true for the events so
  far, or `timeout-ms` passes. Returns [{:event name :data decoded}]."
  [client url done? timeout-ms]
  (let [response (get-response client url (java.net.http.HttpResponse$BodyHandlers/ofLines))
        lines (.iterator ^java.util.stream.Stream (.body response))
        reader (future
                 (loop [events []
                        event-name nil]
                   (if (or (done? events) (not (.hasNext lines)))
                     events
                     (let [line (.next lines)]
                       (cond
                         (str/starts-with? line "event: ") (recur events (subs line 7))
                         (str/starts-with? line "data: ") (recur (conj events {:event event-name
                                                                               :data (read-transit (subs line 6))})
                                                                 nil)
                         :else (recur events event-name))))))]
    (try
      (deref reader timeout-ms ::timeout)
      (finally
        (future-cancel reader)
        (.close ^java.util.stream.Stream (.body response))))))

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
