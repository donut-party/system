(ns donut.system
  (:refer-clojure :exclude [ref])
  (:require
   [com.rpl.specter :as sp]
   [loom.alg :as la]
   [loom.graph :as lg]
   [meta-merge.core :as mm]))

(defrecord DonutSystem [base defs instances graph signal component-order])
(defn system? [x] (instance? DonutSystem x))

(defrecord Ref [key])
(defn ref? [x] (instance? Ref x))
(defn ref [k] (->Ref k))

(defn- resolve-refs
  [system component-id]
  (sp/transform [:defs component-id (sp/walker ref?)]
                (fn [{:keys [key]}]
                  (sp/select-one [:instances key] system))
                system))

(defn- component-deps
  [system component-id]
  (sp/select-one [:defs component-id :deps] system))

(defn- assoc-component-deps
  [system component-id deps]
  (sp/setval [:defs component-id :deps] deps system))

(def config-collect-group-path
  [:defs sp/ALL (sp/collect-one sp/FIRST) sp/LAST])

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
  [{:keys [base] :as system}]
  (sp/transform
   [config-collect-group-path sp/MAP-VALS]
   (fn [group-name component-config]
     (mm/meta-merge (group-name base) component-config))
   system))

(defn- gen-graph-add-nodes
  [system]
  (assoc system
   :graph
   (->> system
        (sp/select [config-collect-group-path sp/MAP-KEYS])
        (reduce (fn [graph node]
                  (lg/add-nodes graph node))
         (lg/digraph)))))

(defn- ref-edges
  [system]
  (->> system
       expand-refs
       (sp/select [config-collect-group-path
                   sp/ALL (sp/collect-one sp/FIRST) sp/LAST
                   (sp/walker ref?) :key])
       (map (fn [[group-name component-name key]]
              [[group-name component-name]
               key]))))

(defn- gen-graph-add-edges
  [system]
  (update system
          :graph
          (fn [graph]
            (->> system
                 ref-edges
                 (reduce (fn [graph edge]
                           (lg/add-edges graph edge))
                  graph)))))

(defn gen-graph
  [system]
  (-> (gen-graph-add-nodes system)
      (gen-graph-add-edges)))

(def default-component-order
  "Function to be applied on the topsorted graph of components"
  {:init   reverse
   :resume reverse})

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

(defn- continue-applying-signal?
  [system]
  (-> system
      :out
      :errors
      empty?))

(defn- handler-lifecycle-names
  [signal-name]
  {:apply-signal signal-name
   :before       (strk signal-name :-before)
   :after        (strk signal-name :-after)
   :around       (strk signal-name :-around)})

(defn channel-fn
  [system channel component-id stage-name]
  #(assoc-in system
    (-> channel
        (into component-id)
        (conj stage-name))
    %1))

(defn- channel-fns
  [system component-id stage-name]
  {:->info     (channel-fn system [:out :info] component-id stage-name)
   :->error    (channel-fn system [:out :error] component-id stage-name)
   :->warn     (channel-fn system [:out :warn] component-id stage-name)
   :->instance (channel-fn system [:instances] component-id stage-name)})

(defn- system-identity
  [_ _ system]
  system)

(defn- handler-stage-fn
  [base-fn component-id stage-name]
  (let [base-fn (or base-fn system-identity)]
    (fn [system]
      (if (continue-applying-signal? system)
        (let [stage-result (base-fn
                            (sp/select-one [:instances component-id] system)
                            (sp/select-one [:defs component-id :deps] system)
                            (merge system
                                   (channel-fns system
                                                component-id
                                                stage-name)))]
          ;; if before or after returns a non-system, disregard it. this
          ;; accommodates side-effecting fns where we almost always want to ignore
          ;; the return value
          (if (system? stage-result)
            stage-result
            system))
        system))))

(defn- around-fn
  [around-f signal-apply-fn component-id stage-name]
  (fn [system]
    (let [around-f        (handler-stage-fn around-f component-id stage-name)
          signal-apply-fn (if (fn? signal-apply-fn)
                            signal-apply-fn
                            (constantly signal-apply-fn))]
      (around-f
       (if (continue-applying-signal? system)
         (let [stage-result (signal-apply-fn
                             (sp/select-one [:instances component-id] system)
                             (sp/select-one [:defs component-id :deps] system)
                             (merge system
                                    (channel-fns system
                                                 component-id
                                                 stage-name)))]
           ;; by default the signal apply fn updates the component's instance
           (if (system? stage-result)
             stage-result
             (assoc-in system (into [:instances] component-id) stage-result)))
         system)))))

(defn- handler-lifecycle
  [{:keys [defs]} component-id signal-name]
  (let [component-handlers (get-in defs (conj component-id :handlers))
        {:keys [apply-signal
                before
                around
                after]}
        (handler-lifecycle-names signal-name)]
    {:around (around-fn (around component-handlers)
                        (apply-signal component-handlers)
                        component-id
                        apply-signal)
     :before (handler-stage-fn (before component-handlers) component-id before)
     :after  (handler-stage-fn (after component-handlers) component-id after)}))

(defn apply-signal-to-component
  [system component-id signal-name]
  (let [orig-deps                     (component-deps system component-id)
        system                        (resolve-refs system component-id)
        {:keys [before around after]} (handler-lifecycle system
                                                         component-id
                                                         signal-name)]
    (-> system
        before
        around
        after
        (assoc :signal signal-name)
        ;; restore deps; we don't want them to remain resolved
        (assoc-component-deps component-id orig-deps))))

(defn initialize-system
  [maybe-system]
  (if (system? maybe-system)
    maybe-system
    (map->DonutSystem (merge maybe-system
                             {:component-order default-component-order}))))

(defn- clean-after-signal-apply
  [system]
  (dissoc system :->error :->info :->instance :->warn))

(defn signal
  [system signal-name]
  (let [{:keys [component-order] :as system} (-> system
                                                 (initialize-system)
                                                 (apply-base)
                                                 (gen-graph))
        order                                (get component-order
                                                  signal-name
                                                  identity)]
    (clean-after-signal-apply
     (loop [system               system
            [component-id & ids] (order (la/topsort (:graph system)))]
       (if component-id
         (recur (apply-signal-to-component system component-id signal-name)
                ids)
         system)))))
