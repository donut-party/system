(ns donut.system
  (:refer-clojure :exclude [ref])
  (:require
   [com.rpl.specter :as sp]
   [loom.alg :as la]
   [loom.derived :as ld]
   [loom.graph :as lg]
   [malli.core :as m]
   [meta-merge.core :as mm]))

;; TODO specs for:
;; - component id (group name, component name)

(def ComponentDefinition
  [:map])

(def ComponentName any?)

(def ComponentDefinitions
  [:map-of ComponentName ComponentDefinition])

(def ComponentDefGroupName
  any?)

(def ComponentDefGroups
  [:map-of ComponentDefGroupName ComponentDefinitions])

(def DonutSystem
  [:map
   [::defs ComponentDefGroups]
   [::base {:optional true} [:map]]
   [::resolved {:optional true} [:map]]
   [::graph {:optional true} any?]
   [::instances {:optional true} any?]])

(def system? (m/validator DonutSystem))

(defrecord Ref [key])
(defn ref? [x] (instance? Ref x))
(defn ref [k] (->Ref k))

(defn- resolve-refs
  "resolve component def to ::resolved path"
  [system component-id]
  (->> system
       (sp/setval [::resolved component-id]
                  (sp/select-one [::defs component-id] system))
       (sp/transform [::resolved component-id (sp/walker ref?)]
                     (fn [{:keys [key]}]
                       (sp/select-one [::instances key] system)))))

(def config-collect-group-path
  [::defs sp/ALL (sp/collect-one sp/FIRST) sp/LAST])

(defn- expand-refs
  "Transforms all refs of a local component name to a full component id"
  [system]
  (->> system
       (sp/transform [config-collect-group-path (sp/walker ref?)]
                     (fn [group-name r]
                       (if (keyword? (:key r))
                         (->Ref [group-name (:key r)])
                         r)))))

(defn- apply-base
  "merge common component configs"
  [{:keys [::base] :as system}]
  (sp/transform
   [config-collect-group-path sp/MAP-VALS]
   (fn [group-name component-config]
     (mm/meta-merge (group-name base) component-config))
   system))

;;---
;;; generate component graphs
;;---

(defn- component-graph-nodes
  [system]
  (->> system
       (sp/select [config-collect-group-path sp/MAP-KEYS])
       (reduce (fn [graph node]
                 (lg/add-nodes graph node))
               (lg/digraph))))

(defn- ref-edges
  [system direction]
  (->> system
       expand-refs
       (sp/select [config-collect-group-path
                   sp/ALL (sp/collect-one sp/FIRST) sp/LAST
                   (sp/walker ref?) :key])
       (map (fn [[group-name component-name key]]
              (if (= :topsort direction)
                [[group-name component-name]
                 key]
                [key
                 [group-name component-name]])))))

(defn- component-graph-add-edges
  [graph system direction]
  (reduce (fn [graph edge]
            (lg/add-edges graph edge))
          graph
          (ref-edges system direction)))

(defn gen-graphs
  [system]
  (let [g (component-graph-nodes system)]
    (-> system
        (assoc-in [::graphs :topsort]
                  (component-graph-add-edges g system :topsort))
        (assoc-in [::graphs :reverse-topsort]
                  (component-graph-add-edges g system :reverse-topsort)))))

(def default-component-order
  "which graph to follow to apply signal"
  {:init   :reverse-topsort
   :resume :reverse-topsort})

;;---
;;; signal application
;;---

(defn strk
  "Like `str` but with keywords"
  [& xs]
  (->> xs
       (reduce (fn [s x]
                 (str s
                      (if (keyword? x)
                        (subs (str x) 1)
                        x)))
               "")
       keyword))

(defn- handler-lifecycle-names
  [signal-name]
  {:apply-signal signal-name
   :before       (strk signal-name :-before)
   :after        (strk signal-name :-after)
   :around       (strk signal-name :-around)})

(defn channel-fn
  [system channel component-id]
  #(sp/setval [channel component-id] % system))

(defn- channel-fns
  [system component-id]
  {:->info       (channel-fn system [::out :info] component-id)
   :->error      (channel-fn system [::out :error] component-id)
   :->warn       (channel-fn system [::out :warn] component-id)
   :->validation (channel-fn system [::out :validation] component-id)
   :->instance   (channel-fn system [::instances] component-id)})

(defn- system-identity
  [_ _ system]
  system)

;;---
;;; computation graph
;;---

(defn gen-signal-computation-graph
  [system signal order]
  (let [component-graph        (get-in system [::graphs order])
        {:keys [before after]} (handler-lifecycle-names signal)]
    (reduce (fn [computation-graph component-node]
              (let [;; generate nodes and edges just for the lifecycle of this
                    ;; component's signal handler
                    computation-graph (->> [before signal after]
                                           (map #(conj component-node %))
                                           (partition 2 1)
                                           (apply lg/add-edges computation-graph))
                    successors        (lg/successors component-graph component-node)]
                (reduce (fn [computation-graph successor-component-node]
                          (lg/add-edges computation-graph
                                        [(conj component-node after)
                                         (conj successor-component-node before)]))
                        computation-graph
                        successors)))
            (lg/digraph)
            (la/topsort component-graph))))

(defn init-signal-computation-graph
  [system signal]
  (assoc system
         ::signal-computation-graph
         (gen-signal-computation-graph system
                                       signal
                                       (get-in system
                                               [::component-order signal]
                                               :topsort))))

(defn around-stage?
  [stage]
  (not (re-find #"(-before$|-after$)" (str stage))))

(defn- apply-stage-fn
  [system stage-fn component-id]
  (stage-fn (sp/select-one [::resolved component-id] system)
            (sp/select-one [::instances component-id] system)
            (merge system (channel-fns system component-id))))

(defn- stage-result-valid?
  [system]
  (not (or (sp/select-one [::out :errors] system)
           (sp/select-one [::out :validation] system))))

(defn prune-signal-computation-graph
  [system computation-node]
  (update system
          ::signal-computation-graph
          (fn [graph]
            (->> computation-node
                 (ld/subgraph-reachable-from graph)
                 (lg/nodes)
                 (apply lg/remove-nodes graph)))))

(defn remove-signal-computation-node
  [system computation-node]
  (update system
          ::signal-computation-graph
          lg/remove-nodes
          computation-node))

(defn handler-stage-fn
  [system computation-node]
  (let [component-id (vec (take 2 computation-node))
        stage-fn     (or (sp/select-one [::resolved computation-node] system)
                         system-identity)]
    (fn [system]
      (let [stage-result (apply-stage-fn system stage-fn component-id)]
        (if (system? stage-result)
          stage-result
          system)))))

(defn around-stage-fn
  "computation node will be e.g. [:env :http-port :init]"
  [system computation-node]
  (let [component-id (vec (take 2 computation-node))
        signal-name  (last computation-node)
        signal-fn    (or (sp/select-one [::resolved computation-node] system)
                         system-identity)
        ;; accomodate setting a constant value for a signal
        signal-fn    (if (fn? signal-fn)
                       signal-fn
                       (constantly signal-fn))
        around-fn    (->> signal-name
                          handler-lifecycle-names
                          :around
                          (conj component-id)
                          (handler-stage-fn system))]
    (fn [system]
      (around-fn
       (let [stage-result (apply-stage-fn system signal-fn component-id)]
         (if (system? stage-result)
           stage-result
           (sp/setval [::instances component-id] stage-result system)))))))

(defn- computation-stage-fn
  [system [_ _ stage :as computation-node]]
  (if (around-stage? stage)
    (around-stage-fn system computation-node)
    (handler-stage-fn system computation-node)))

(defn apply-signal-stage
  [system computation-node]
  (let [component-id (vec (take 2 computation-node))
        system       (resolve-refs system component-id)
        new-system   ((computation-stage-fn system computation-node)
                      system)]
    (if (stage-result-valid? new-system)
      (remove-signal-computation-node new-system computation-node)
      (prune-signal-computation-graph new-system computation-node))))

(defn apply-signal-computation-graph
  [system]
  (loop [{:keys [::signal-computation-graph] :as system} system]
    (let [[computation-node] (la/topsort signal-computation-graph)]
      (if-not computation-node
        system
        (recur (apply-signal-stage system computation-node))))))

;;---
;;; init, apply, etc
;;---

(defn- merge-component-defs
  "Components defined as vectors of maps get merged into a single map"
  [system]
  (sp/transform [::defs sp/MAP-VALS sp/MAP-VALS]
                (fn [component-def]
                  (if (sequential? component-def)
                    (apply merge component-def)
                    component-def))
                system))

(defn initialize-system
  [maybe-system]
  (->> (merge {::component-order default-component-order}
              maybe-system)
       merge-component-defs
       apply-base
       gen-graphs))

(defn- clean-after-signal-apply
  [system]
  (dissoc system :->error :->info :->instance :->warn))

(defn signal
  [system signal-name]
  (-> system
      initialize-system
      (init-signal-computation-graph signal-name)
      (apply-signal-computation-graph)
      (clean-after-signal-apply)))

(defn system-merge
  [& systems]
  (reduce (fn [system system-def]
            (mm/meta-merge system (initialize-system system-def)))
          {}
          systems))

(defn validate-with-malli
  [{:keys [schema]} instance-val {:keys [->validation]}]
  (some-> (and schema (m/explain schema instance-val))
          ->validation))
