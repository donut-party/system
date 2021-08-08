(ns donut.system
  (:refer-clojure :exclude [ref])
  (:require [com.rpl.specter :as sp]
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

(defrecord Ref [key])
(defn ref? [x] (instance? Ref x))
(defn ref [k] (->Ref k))

;; signal

;; 1. apply base
;; 2. gen graph
;; 3. reverse topsort graph
;; 4. compute graph

(def config-collect-group-path
  [:configs sp/ALL (sp/collect-one sp/FIRST) sp/LAST])

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
       (sp/select [config-collect-group-path
                   sp/ALL (sp/collect-one sp/FIRST) sp/LAST
                   (sp/walker ref?) :key])
       (map (fn [[group-name component-name key]]
              [[group-name component-name]
               (if (keyword? key)
                 [group-name key]
                 key)]))))

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

(defn signal
  [system signal-name]
  (let [system (apply-base system)]
    ))

;; transitions

(defn computation-graph
  [system component-group build-config component-config])

#_(defn transition-with-lifecycle
    [system]
    (let [before-all-fns (:before-all build-config)
          before-vals    (reduce (fn [v f]
                                   (merge v (f (get system component-group))))
                                 {}
                                 before-all-fns)]
      (if (empty? before-vals))
      ))

#_(defn init
    [{:keys [build-config component-config]
      :as   system}]
    (let [constructor (get build-config :constructor)]
      (assoc system
             component-group
             (medley/map-vals constructor component-config))))

(defn transition-fn
  [f component-group]
  (fn [system build-config component-config]
    (f system
       component-group
       (get build-config component-group)
       (get component-config component-group))))

#_(def transition-config
    {[:start :running]     [(transition-fn build :env)
                            (transition-fn build :app)]
     [:running :halted]    [(transition-fn halt :app)]
     [:halted :running]    [(transition-fn build :app)]
     [:running :suspended] [(transition-fn suspend :app)]
     [:suspended :running] [(transition-fn suspend :app)]
     [:suspended :halted]  [(transition-fn halt :app)]})

(defn init-system
  [system]
  (cond-> system
    (not (:state system)) (assoc :state :start)))

(defn transition
  [{:keys [transition
           transition-config
           build-config
           component-config]
    :as   system}
   target-state]
  (loop [{:keys [state] :as system} (init-system system)
         transition-fns             (get transition-config [state target-state])]
    (let [[tfn & rest-tfns] transition-fns
          updated-system    (-> (tfn system build-config component-config)
                                (assoc :state target-state))]
      (if (empty? rest-tfns)
        updated-system
        (recur updated-system rest-tfns)))))
