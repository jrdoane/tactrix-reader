(ns tactrix-reader.dashboard.core
  (:require [re-frame.core :as rf]
            [reagent.dom.client :as rdc]
            [tactrix-reader.dashboard.events :as events]
            [tactrix-reader.dashboard.views :as views]))

(defonce ^:private root
  (rdc/create-root (.getElementById js/document "app")))

(defn mount []
  (rf/clear-subscription-cache!)
  (rdc/render root [views/app]))

(defn init []
  (rf/dispatch-sync [::events/initialize])
  (mount))
