(ns tactrix-reader.dashboard.stream
  "Connects to the server's Server-Sent Events stream and dispatches each
  event into re-frame."
  (:require [cognitect.transit :as transit]
            [re-frame.core :as rf]))

(defonce ^:private event-source (atom nil))

(def ^:private reader (transit/reader :json))

(defn- listen! [^js source event-name handler]
  (.addEventListener source event-name
                     (fn [^js event]
                       (rf/dispatch [handler (transit/read reader (.-data event))]))))

(rf/reg-fx
 :stream/connect
 (fn [url]
   (when-let [^js existing @event-source]
     (.close existing))
   (let [source (js/EventSource. url)]
     (set! (.-onopen source) #(rf/dispatch [:stream/connected]))
     ;; EventSource reconnects by itself after an error.
     (set! (.-onerror source) #(rf/dispatch [:stream/disconnected]))
     (listen! source "snapshot" :stream/snapshot)
     (listen! source "samples" :stream/samples)
     (listen! source "status" :stream/status)
     (reset! event-source source))))
