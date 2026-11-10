(ns tactrix-reader.dashboard.catalog
  "Turns the server's catalog (`tactrix-reader.device.catalog/catalog`) into
  what the dashboard draws: the tiles in each group of each section. Pure, so
  it has tests."
  (:require [clojure.string :as str]))

(defn name-label
  "A label made from a name, for values the catalog does not describe."
  [value-name]
  (str/capitalize (str/replace (name value-name) "-" " ")))

(defn labels
  "Labels by name for every PID, signal, and DID in the catalog."
  [{:keys [signals dids]}]
  (into {} (map (juxt :name :label)) (concat dids signals)))

(defn can-labels
  "The labels of the PIDs and signals decoded from each CAN ID, by ID."
  [{:keys [signals]}]
  (reduce (fn [by-id {:keys [can-id label]}]
            (cond-> by-id
              can-id (update can-id (fnil conj []) label)))
          {}
          signals))

(defn entries
  "The catalog's `:signals` plus an entry in `:other` for each name in
  `seen`, `{:pid #{names} :signal #{names}}`, that the catalog does not
  have, such as a PID an old recording polled."
  [{:keys [signals]} seen]
  (let [known (into #{} (map (juxt :kind :name)) signals)]
    (into (vec signals)
          (for [kind [:pid :signal]
                value-name (sort (get seen kind))
                :when (not (known [kind value-name]))]
            {:kind kind :name value-name :label (name-label value-name) :group :other}))))

(defn series-key
  "The key of a tile's values in a `series` log, such as `:pid/engine-rpm` or
  `:signal/wheel-speeds.front-left`."
  [kind value-name part]
  (keyword (name kind) (cond-> (name value-name) part (str "." (name part)))))

(defn tiles
  "The tiles an entry is shown as: one, or one for each of its `:parts`. A
  lone part keeps the entry's label."
  [{:keys [kind name label unit parts] :as entry}]
  (if (seq parts)
    (for [{:keys [part] :as part-entry} parts]
      (merge (select-keys entry [:kind :name :format :raw? :can-id :pid])
             {:id (series-key kind name part)
              :label (or (:label part-entry) label)
              :unit (or (:unit part-entry) unit)
              :part part}
             (select-keys part-entry [:decimals])))
    [(merge (select-keys entry [:kind :name :format :raw? :can-id :pid])
            {:id (series-key kind name nil) :label label :unit unit})]))

(defn primary-tiles
  "Tiles for the catalog's `:primary` names."
  [{:keys [primary]} entries]
  (let [by-name (into {} (map (juxt :name identity)) entries)]
    (mapcat #(some-> (get by-name %) tiles) primary)))

(defn- shown?
  "Whether an entry gets tiles: a signal, or a PID that is polled by default
  or has arrived, unless it is shown another way or is a primary value."
  [{:keys [kind name polled? tile?]} primary seen]
  (and (not (false? tile?))
       (not (contains? primary name))
       (or (= :signal kind) polled? (contains? (:pid seen) name))))

(defn sections
  "The catalog's sections, each with `:groups` of `{:id :title :tiles}`, in
  catalog order. Groups with no tiles are left out, and an entry whose group
  its section does not have goes in `:other`."
  [{:keys [primary groups] :as catalog} entries seen]
  (let [primary (set primary)
        section-of {:pid :obd :signal :can}
        group-ids (into #{} (map (juxt :section :id)) groups)
        by-group (group-by (fn [{:keys [kind group]}]
                             (let [section (section-of kind)]
                               [section (if (group-ids [section group]) group :other)]))
                           (filter #(shown? % primary seen) entries))]
    (for [{:keys [id] :as section} (:sections catalog)]
      (assoc section :groups
             (for [{group-id :id :keys [title]} (filter #(= id (:section %)) groups)
                   :let [group-tiles (mapcat tiles (get by-group [id group-id]))]
                   :when (seq group-tiles)]
               {:id group-id :title title :tiles (vec group-tiles)})))))

(defn compare-value
  "The polled value a DID is compared with, from the samples by name:
  `compare` is `{:pid name}`, with `:part` for a PID that decodes to a map,
  or `{:derived :air-per-revolution}` for MAF over revolutions per second."
  [{:keys [pid part derived]} samples]
  (cond
    pid (cond-> (get-in samples [pid :value])
          part (get part))
    (= :air-per-revolution derived)
    (let [maf (get-in samples [:maf-air-flow :value])
          rpm (get-in samples [:engine-rpm :value])]
      (when (and maf rpm (pos? rpm))
        (/ maf (/ rpm 60))))))
