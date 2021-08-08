(ns donut.system
  (:require [loom.graph :as lg]
            [medley.core :as medley]
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

;; signal

;; 1. apply base
;; 2. gen graph
;; 3. reverse topsort graph
;; 4. compute graph

(defn- apply-base
  [{:keys [base configs] :as system}]
  (assoc system :configs
         (reduce-kv (fn [configs group-name components]
                      (assoc configs
                             group-name
                             (reduce-kv (fn [group-config component-name component-config]
                                          (assoc group-config
                                                 component-name
                                                 (mm/meta-merge (group-name base) component-config)))
                                        {}
                                        components)))
                    {}
                    configs)))

(defn gen-graph-add-nodes
  [{:keys [configs] :as system}]
  (assoc system :graph (let [graph (lg/digraph)]
                         (reduce-kv (fn [graph group-name components]
                                      (reduce (fn [graph component-name]
                                                (lg/add-nodes graph [group-name component-name]))
                                              graph
                                              (keys components)))
                                    graph
                                    configs))))

(defn gen-graph-add-edges
  [{:keys [configs] :as system}]
  system
  #_(update system :graph (fn [graph]
                            (reduce-kv (fn [graph group-name components]
                                         (reduce (fn [graph component]
                                                   (lg/add-nodes graph [group-name component-name]))
                                                 graph
                                                 (vals components)))
                                       graph
                                       configs))))

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
