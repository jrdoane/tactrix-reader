(ns tactrix-reader.dashboard.catalog-test
  (:require [cljs.test :refer [deftest is]]
            [tactrix-reader.dashboard.catalog :as catalog]))

(def ^:private server-catalog
  {:sections [{:id :obd :title "OBD-II PIDs"}
              {:id :can :title "From the CAN bus" :provenance :inferred}]
   :groups [{:id :air :section :obd :title "Air and load"}
            {:id :fuel :section :obd :title "Fuel"}
            {:id :other :section :obd :title "Other PIDs"}
            {:id :wheels :section :can :title "Wheel speeds"}
            {:id :other :section :can :title "Other signals"}]
   :primary [:engine-rpm :gear]
   :signals [{:kind :pid :name :engine-rpm :pid 0x0C :label "RPM" :group :air :unit :rpm
              :polled? true :can-id 0x140}
             {:kind :pid :name :engine-load :pid 0x04 :label "Engine load" :group :air :unit :percent
              :polled? true}
             {:kind :pid :name :wideband :pid 0x34 :label "Measured λ" :group :fuel :polled? true
              :parts [{:part :lambda :unit :lambda}]}
             {:kind :pid :name :monitor-status :pid 0x01 :label "Monitor status" :group :other
              :polled? true :tile? false}
             {:kind :pid :name :absolute-load :pid 0x43 :label "Absolute load" :group :other :unit :percent}
             {:kind :signal :name :gear :can-id 0x141 :label "Gear" :group :driver :format :gear}
             {:kind :signal :name :wheel-speeds :can-id 0x0D4 :label "Wheel speeds" :group :wheels
              :unit :km-per-hour
              :parts [{:part :front-left :label "Front left"} {:part :front-right :label "Front right"}]}]
   :dids [{:did 0x10BE :name :lambda :label "Measured λ"}]})

(defn- group-names [sections]
  (into {} (for [{:keys [id groups]} sections]
             [id (mapv (juxt :id #(mapv :id (:tiles %))) groups)])))

(deftest builds-sections-of-groups-of-tiles
  (let [seen {:pid #{} :signal #{}}
        sections (catalog/sections server-catalog (catalog/entries server-catalog seen) seen)]
    ;; Primary values and `:tile? false` are left out, as is a PID that is
    ;; neither polled by default nor arrived, and so is its empty group.
    (is (= {:obd [[:air [:pid/engine-load]]
                  [:fuel [:pid/wideband.lambda]]]
            :can [[:wheels [:signal/wheel-speeds.front-left :signal/wheel-speeds.front-right]]]}
           (group-names sections)))
    (is (= :inferred (:provenance (second sections))))
    (is (= {:id :pid/wideband.lambda :kind :pid :name :wideband :pid 0x34 :part :lambda
            :label "Measured λ" :unit :lambda}
           (-> sections first :groups second :tiles first)))
    (is (= {:label "Front left" :unit :km-per-hour :part :front-left}
           (-> sections second :groups first :tiles first (select-keys [:label :unit :part]))))))

(deftest arrived-values-the-catalog-lacks-go-in-other
  (let [seen {:pid #{:absolute-load :run-time} :signal #{:hatch-open}}
        sections (catalog/sections server-catalog (catalog/entries server-catalog seen) seen)]
    (is (= [:other [:pid/absolute-load :pid/run-time]] (last (:obd (group-names sections)))))
    (is (= [:other [:signal/hatch-open]] (last (:can (group-names sections)))))
    (is (= "Hatch open" (-> sections second :groups last :tiles first :label)))))

(deftest a-group-the-section-lacks-falls-back-to-other
  ;; :driver is not one of this catalog's groups, and gear is primary; a
  ;; second signal there lands in :other.
  (let [catalog (update server-catalog :signals conj
                        {:kind :signal :name :brake :can-id 0x0D3 :label "Brake" :group :driver})
        seen {:pid #{} :signal #{}}]
    (is (= [:other [:signal/brake]]
           (last (:can (group-names (catalog/sections catalog (catalog/entries catalog seen) seen))))))))

(deftest primary-tiles-follow-the-catalog-order
  (is (= [:pid/engine-rpm :signal/gear]
         (map :id (catalog/primary-tiles server-catalog (:signals server-catalog))))))

(deftest labels-cover-signals-and-dids
  (is (= {:engine-rpm "RPM" :lambda "Measured λ"}
         (select-keys (catalog/labels server-catalog) [:engine-rpm :lambda]))))

(deftest compare-value-reads-the-matched-polled-value
  (let [samples {:engine-rpm {:value 3000}
                 :maf-air-flow {:value 25}
                 :wideband {:value {:lambda 0.98 :milliamps 0.1}}}]
    (is (= 3000 (catalog/compare-value {:pid :engine-rpm} samples)))
    (is (= 0.98 (catalog/compare-value {:pid :wideband :part :lambda} samples)))
    (is (= 0.5 (catalog/compare-value {:derived :air-per-revolution} samples)))
    (is (nil? (catalog/compare-value {:derived :air-per-revolution} {})))
    (is (nil? (catalog/compare-value nil samples)))))
