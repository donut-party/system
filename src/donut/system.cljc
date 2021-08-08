(ns donut.system
  (:refer-clojure :exclude [ref])
  (:require [com.rpl.specter :as sp]
            [loom.alg :as la]
            [loom.graph :as lg]
            [meta-merge.core :as mm]))

(comment
  {:app
   {:http-server
    {:config    {}
     :lifecycle {:init        {}
                 :init-before []
                 :init-after  []
                 :halt        (fn [])
                 :halt-before []
                 :half-after  []
                 :suspend     (fn [])
                 :resume      (fn [])}}}}

  {:app
   {:http-server
    {:lifecycle {:init           {}
                 [:init :before] {}}}}})

(defrecord DonutSystem [base configs instances graph signal component-order])
(defn system? [x] (instance? System x))

(defrecord Ref [key])
(defn ref? [x] (instance? Ref x))
(defn ref [k] (->Ref k))

#_(defn- resolve-refs
    [system component-id]
    (sp/transform [:configs component-id]
                  system))

(def config-collect-group-path
  [:configs sp/ALL (sp/collect-one sp/FIRST) sp/LAST])

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
  (assoc system :graph
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
  (update system :graph (fn [graph]
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
                 (str s (if (keyword? x)
                          (subs (str x) 1)
                          x))))
       keyword))

(defn- continue-applying-signal?
  [system]
  (-> system :out :errors empty?))

(defn- lifecycle-names
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

(defn- lifecycle-stage-fn
  [base-fn component-id stage-name]
  (let [base-fn (or base-fn system-identity)]
    (fn [system]
      (if (continue-applying-signal? system)
        (let [stage-result (base-fn
                            (get-in system (into [:instances] component-id))
                            (get-in system (-> [:components]
                                               (into component-id)
                                               (conj :config)))
                            (merge system (channel-fns system component-id stage-name)))]
          ;; if before or after returns a non-system, disregard it
          ;; accommodating side-effecting fns we want to ignore
          (if (system? stage-result)
            stage-result
            system))
        system))))

(defn- around-fn
  [around-f signal-apply-fn component-id stage-name]
  (fn [system]
    (let [around-f        (lifecycle-stage-fn around-f component-id stage-name)
          signal-apply-fn (if (fn? signal-apply-fn)
                            signal-apply-fn
                            (constantly signal-apply-fn))]
      (around-f
       (if (continue-applying-signal? system)
         (let [stage-result (signal-apply-fn
                             (get-in system (into [:instances] component-id))
                             (get-in system (-> [:components]
                                                (into component-id)
                                                (conj :config)))
                             (merge system (channel-fns system component-id stage-name)))]
           ;; by default the signal apply fn updates the component's instance
           (if (system? stage-result)
             stage-result
             (assoc-in system (into [:instances] component-id) stage-result)))
         system)))))

(defn- lifecycle-fns
  [{:keys [configs]} component-id signal-name]
  (let [component-lifecycle (get-in configs (conj component-id :lifecycle))
        {:keys [apply-signal
                before
                around
                after]}     (lifecycle-names signal-name)]
    {:around (around-fn (around component-lifecycle)
                        (apply-signal component-lifecycle)
                        component-id
                        apply-signal)
     :before (lifecycle-stage-fn (before component-lifecycle) component-id before)
     :after  (lifecycle-stage-fn (after component-lifecycle) component-id after)}))

(defn apply-signal-to-component
  [system component-id signal-name]
  (let [{:keys [before around after]} (lifecycle-fns system component-id signal-name)]
    (-> system
        before
        around
        after
        (assoc :signal signal-name))))

(defn initialize-system
  [maybe-system]
  (if (system? maybe-system)
    maybe-system
    (map->DonutSystem (merge maybe-system
                             {:component-order default-component-order}))))

(defn signal
  [{:keys [component-order] :as system} signal-name]
  (let [system (-> system
                   (initialize-system)
                   (apply-base)
                   (gen-graph))
        order  (get component-order signal-name identity)]
    (loop [system               system
           [component-id & ids] (order (la/topsort (:graph system)))]
      (if component-id
        (recur (apply-signal-to-component system component-id signal-name)
               ids)
        system))))
