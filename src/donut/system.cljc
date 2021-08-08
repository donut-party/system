(ns donut.system
  (:require [medley.core :as medley]))

(def default-build-config
  {:env
   {:constructor :value
    :before-each []
    :after-each  []
    :before-all  []
    :after-all   []}

   :system
   {:constructor identity
    :before-each []
    :after-each  []
    :before-all  []
    :after-all   []}})

;; transitions

(defn computation-graph
  [system component-group build-config component-config])

(defn transition-with-lifecycle
  [system]
  (let [before-all-fns (:before-all build-config)
        before-vals    (reduce (fn [v f]
                                 (merge v (f (get system component-group))))
                               {}
                               before-all-fns)]
    (if (empty? before-vals))
    ))

(defn init
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

(def transition-config
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
