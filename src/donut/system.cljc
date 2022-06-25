(ns donut.system
  (:refer-clojure :exclude [ref])
  (:require
   [com.rpl.specter :as sp]
   [loom.alg :as la]
   [loom.derived :as ld]
   [loom.graph :as lg]
   [malli.core :as m]
   [malli.error :as me]))

;;---
;;; specs
;;---

(def Component
  [:map
   [::start any?]])

(def ComponentLike
  "Component-like data shows up in `::defs`, `::resolved-defs`, and `::instances`. None
  of these are the component per se, but they are the way the component
  manifests in that context."
  [:orn
   [:map map?]
   [:const any?]])

(def ComponentName keyword?)

(def ComponentGroup
  "Components are grouped. This theoretically helps if publishing a component
  library. It also helps when creating multiple instances of the same collection
  of components because components are allowed to have local refs. See TODO for
  more details."
  [:map-of
   {:error/message "should be a map with keyword keys"}
   ComponentName
   ComponentLike])

(def ComponentGroupName keyword?)
(def ComponentId [:tuple ComponentGroupName ComponentName])
(def ComponentGroups [:map-of ComponentGroupName ComponentGroup])

(def Graph
  "Graphs are used to specify component dependency order and signal application
  order."
  [:map
   [:nodeset set?]
   [:adj map?]
   [:in map?]])

(def OrderGraphs
  "Topsorted and reverse-topsorted graphs are generated once when a system is
  initialized and assoc'c into the system. These graphs are used when applying
  signals."
  [:map
   [:topsort Graph]
   [:reverse-topsort Graph]])

(def SignalConfig
  "The set of signals your system can respond to is configurable. To apply a
  signal, the system needs to know in what order components should be
  traversed."
  [:map
   [:order [:enum :topsort :reverse-topsort]]])

(def ComponentSelection
  [:or ComponentGroupName ComponentId])

(def DonutSystem
  [:map
   [::defs ComponentGroups]
   [::base {:optional true} [:map]]
   [::resolved-defs {:optional true} ComponentGroups]
   [::graphs {:optional true} OrderGraphs]
   [::instances {:optional true} ComponentGroups]
   [::out {:optional true} ComponentGroups]
   [::signals {:optional true} [:map-of keyword? SignalConfig]]
   [::selected-component-ids {:optional true} [:set ComponentSelection]]])

(def system? (m/validator DonutSystem))


(def RefKey
  [:vector {:min 1} keyword?])

(def LocalRef
  [:catn
   [:ref-type [:enum ::local-ref]]
   [:ref-key RefKey]])

(def Ref
  [:catn
   [:ref-type [:enum ::ref]]
   [:ref-key RefKey]])

(def DonutRef
  [:or Ref LocalRef])

;;---
;;; schema predicates
;;---

(def ref? (m/validator DonutRef))
(def ref-type first)

(defn ref [k] [::ref k])
(defn local-ref [k] [::local-ref k])
(def ref-key second)

(defn group-ref?
  [ref]
  (and (= ::ref (ref-type ref))
       (= 1 (count (ref-key ref)))))

(defn component-id [ref]
  (->> ref
       ref-key
       (take 2)
       vec))

(def component? (m/validator Component))
;;---
;;; util/ helpers / misc
;;---

(def config-collect-group-path
  "specter path that retains a component's group name"
  [::defs sp/ALL (sp/collect-one sp/FIRST) sp/LAST])

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

;;---
;;; merge base
;;---

(defn- component-paths
  [defs]
  (reduce-kv (fn [ps group-name components]
               (reduce (fn [ps component-name]
                         (conj ps [group-name component-name]))
                       ps
                       (keys components)))
             #{}
             defs))

(defn- base-merge
  "handles merging a system base when a component is a constant"
  [component-def base]
  (cond (and (map? base) (map? component-def)) (merge base component-def)
        (map? base)                            (merge base {::start (constantly component-def)})
        :else                                  (or base component-def)))

(defn- merge-base
  "::base gives you a way to apply default config options to all components"
  [{:keys [::base ::defs] :as system}]
  (assoc system ::defs (reduce (fn [system path] (update-in system path base-merge base))
                               defs
                               (component-paths defs))))

;;---
;;; ref resolution
;;---
;; ref resolution makes ref'd component instances available to the ref'ing
;; component for signal application

(defn ref-exception
  [_system referencing-component-id referenced-component-id]
  (ex-info (str "Invalid ref: '" referencing-component-id "' "
                "references undefined component '" referenced-component-id "'")
           {:referencing-component-id referencing-component-id
            :referenced-component-id  referenced-component-id}))

(defn group-ref-exception
  [_system referencing-component-id referenced-component-group-name]
  (ex-info (str "Invalid group ref: '" referencing-component-id "' "
                "references empty component group '" referenced-component-group-name "'")
           {:referencing-component-id        referencing-component-id
            :referenced-component-group-name referenced-component-group-name}))

(defn- resolve-ref
  [system referencing-component-id ref]
  (let [[component-group-name component-name :as rkey] (ref-key ref)]
    (when-not (contains? (::instances system)
                         component-group-name)
      (throw (group-ref-exception system
                                  (component-id ref)
                                  component-group-name)))
    (when (and component-name
               (not (contains? (get-in system [::instances component-group-name])
                               component-name)))
      (throw (ref-exception system
                            referencing-component-id
                            (component-id ref))))
    (sp/select-one [::instances rkey] system)))

(defn- default-resolve-refs
  "for all refs R within component def D, replaces R with the instance of the
  component that R refers to. places the result in `::resolved-defs`"
  [system component-id]
  (->> system
       (sp/setval [::resolved-defs component-id]
                  (sp/select-one [::defs component-id] system))
       (sp/transform [::resolved-defs component-id (sp/walker (some-fn ref? system?))]
                     (fn [ref-or-system]
                       (let [rt (ref-type ref-or-system)]
                         (cond
                           ;; don't descend into subsystems
                           (system? ref-or-system)
                           ref-or-system

                           (= ::ref rt)
                           (resolve-ref system component-id ref-or-system)

                           ;; local refs get converted to a regular ref by using
                           ;; the group name of the current component
                           (= ::local-ref rt)
                           (resolve-ref system component-id (ref (into [(first component-id)]
                                                                       (ref-key ref-or-system))))))))))

(defn- resolve-refs
  "produces an updated component def where refs are replaced by the instance of
  the thing being ref'd. places result under ::resolved-defs. allows custom
  resolution fns to be defined with ::resolve-refs, a feature used to work with
  subsystems"
  [system component-id]
  (if-let [resolution-fn (sp/select-one [::defs component-id ::resolve-refs] system)]
    (resolution-fn system component-id)
    (default-resolve-refs system component-id)))

(defn resolved
  [{:keys [::component-id ::resolved-defs]}]
  (sp/select-one component-id resolved-defs))

;;---
;;; generate component signal apply order graphs
;;---
;;
;; The order in which signals are applied is important. For example, if an http
;; server component depends on a db component, then when you're applying the the
;; `::start` signal you should apply it to the db and then the http server. If
;; you're applying the `::stop` signal, the order is reversed: http server, then
;; db.
;;
;; These helpers create two digraphs to capture both orderings, using
;; components' refs to determine graph edges.

(defn- component-graph-nodes
  [system]
  (->> system
       (sp/select [config-collect-group-path sp/MAP-KEYS])
       (reduce (fn [graph node]
                 (lg/add-nodes graph node))
               (lg/digraph))))

(defn- expand-refs-for-graph
  "We use refs to determine the edges for the component order graphs. However,
  there's a wrinkle in that in the graphs, nodes are component ids (a tuple of
  `[:component-group-name :component-name]), whereas refs can take the form of
  local refs -- (ref :component-name) -- or group refs
  -- (group-ref :group-name). This function desugars both kinds of refs. "
  [system]
  ;; walk over all component defs, filtering for refs and systems, to apply
  ;; transformation
  (sp/transform [config-collect-group-path (sp/walker (some-fn ref? system?))]
                (fn [group-name x]
                  (let [rt (ref-type x)]
                    (cond
                      ;; don't descend into subsystems
                      (system? x)
                      x

                      (group-ref? x)
                      (let [group-name (first (ref-key x))]
                        {group-name
                         (->> (sp/select [::defs group-name sp/MAP-KEYS] system)
                              (reduce (fn [group-map k]
                                        (assoc group-map k (ref [group-name k])))
                                      {}))})

                      (= ::local-ref rt)
                      (ref (into [group-name] (ref-key x)))

                      (= ::ref rt)
                      x)))
                system))

(defn- ref-edges
  "used to populate the component graph with directed edges"
  [system direction]
  (->> system
       expand-refs-for-graph
       (sp/select [config-collect-group-path
                   sp/ALL
                   (sp/collect-one sp/FIRST)
                   sp/LAST
                   (sp/walker ref?)])
       (map (fn [[group-name component-name ref]]
              (let [parent [group-name component-name]
                    child  (component-id ref)]
                (if (= :topsort direction)
                  [parent child]
                  [child parent]))))))

(defn- component-graph-add-edges
  "uses refs to build a dependency map for components"
  [graph system direction]
  (reduce (fn [graph edge]
            (lg/add-edges graph edge))
          graph
          (ref-edges system direction)))

(defn gen-graphs
  "Generates order graphs. If `::selected-component-ids` is specified, graphs are
  filtered to the union of all subgraphs reachable by the selected component
  ids."
  [{:keys [::selected-component-ids] :as system}]
  (let [g         (component-graph-nodes system)
        topsorted (component-graph-add-edges g system :topsort)
        selected  (if (empty? selected-component-ids)
                    topsorted
                    (ld/nodes-filtered-by (reduce (fn [nodes component-id]
                                                    (->> component-id
                                                         (ld/subgraph-reachable-from topsorted)
                                                         lg/nodes
                                                         (into nodes)))
                                                  #{}
                                                  selected-component-ids)
                                          topsorted))
        reversed  (->> (lg/edges selected)
                       (map (fn [[a b]] [b a]))
                       (apply lg/add-edges
                              (reduce lg/add-nodes
                                      (lg/digraph)
                                      (lg/nodes selected))))]
    (-> system
        (assoc-in [::graphs :topsort] selected)
        (assoc-in [::graphs :reverse-topsort] reversed))))

(def default-signals
  "which graph to follow to apply signal"
  {::start   {:order :reverse-topsort}
   ::stop    {:order :topsort}
   ::suspend {:order :topsort}
   ::resume  {:order :reverse-topsort}})

;;---
;;; channel fns
;;---

(defn- channel-fn
  [system channel component-id]
  (fn ->channel
    ([v]
     (->channel system v))
    ([s v]
     (sp/setval [channel component-id] v s))))

(defn- channel-fns
  [system component-id]
  {:->info       (channel-fn system [::out :info] component-id)
   :->error      (channel-fn system [::out :error] component-id)
   :->warn       (channel-fn system [::out :warn] component-id)
   :->validation (channel-fn system [::out :validation] component-id)
   :->instance   (channel-fn system [::instances] component-id)})

;;---
;;; computation graph
;;---
;;
;; Signal application works by generating a "signal computation graph", where
;; each node corresponds to a function that... TODO

(defn- system-identity
  "used by stage functions to return the current system"
  [{:keys [::system]}]
  system)

(defn- handler-lifecycle-names
  [signal-name]
  (let [snns (namespace signal-name)
        snn  (name signal-name)]
    {:apply-signal signal-name
     :before       (keyword snns (str "before-" snn))
     :after        (keyword snns (str "after-" snn))}))

(defn- gen-signal-computation-graph
  "creates the graph that should be traversed to call handler (and lifecycle) fns
  for the given signal"
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

(defn- init-signal-computation-graph
  [system signal]
  (assoc system
         ::signal-computation-graph
         (gen-signal-computation-graph system
                                       signal
                                       (get-in system [::signals signal :order]))))

(defn- handler-stage?
  "The stage corresponds to a signal (eg ::start), not a signal lifecycle (eg ::before-start)"
  [stage]
  (not (re-find #"(^before-|^after-)" (name stage))))

(defn- apply-stage-fn
  [system stage-fn component-id]
  (let [resolved-def (sp/select-one [::resolved-defs component-id] system)]
    (stage-fn
     ;; construct map to pass to the `stage-fn`
     (cond-> {::instance (sp/select-one [::instances component-id] system)
              ::system   system}
       (map? resolved-def) (merge resolved-def)
       true                (merge (channel-fns system component-id))))))

(defn- stage-result-valid?
  [system]
  (-> system
      ::out
      (select-keys [:errors :validation])
      empty?))

;; copied from loom.graph to work around its bizarre cljs (:in g) issue
(defn- remove-adj-nodes [m nodes adjacents remove-fn]
  (reduce
   (fn [m n]
     (if (m n)
       (update-in m [n] #(apply remove-fn % nodes))
       m))
   (apply dissoc m nodes)
   adjacents))

(defn- remove-nodes
  [g nodes]
  (let [ins (mapcat #(lg/predecessors g %) nodes)
        outs (mapcat #(lg/successors g %) nodes)]
    (-> g
        (update-in [:nodeset] #(apply disj % nodes))
        (assoc :adj (remove-adj-nodes (:adj g) nodes ins disj))
        (assoc :in (remove-adj-nodes (:in g) nodes outs disj)))))

(defn- prune-signal-computation-graph
  "remove subgraph reachable from computation-stage-node to prevent those
  handlers/lifecycles from being applied"
  [system computation-stage-node]
  (update system
          ::signal-computation-graph
          (fn [graph]
            (->> computation-stage-node
                 (ld/subgraph-reachable-from graph)
                 (lg/nodes)
                 (remove-nodes graph)))))

(defn- remove-signal-computation-stage-node
  [system computation-stage-node]
  (update system
          ::signal-computation-graph
          remove-nodes
          [computation-stage-node]))

(defn- lifecycle-stage-fn
  [system computation-stage-node]
  (let [component-id (vec (take 2 computation-stage-node))
        stage-fn     (or (sp/select-one [::resolved-defs computation-stage-node] system)
                         system-identity)]
    (fn [system]
      (let [stage-result (apply-stage-fn system stage-fn component-id)]
        (if (system? stage-result)
          stage-result
          system)))))

(defn- handler-stage-fn
  "returns function for a handler (e.g. ::start) as opposed to a lifecycle
  fn (e.g. ::before-start)"
  [system computation-stage-node]
  (let [component-id (vec (take 2 computation-stage-node))
        resolved-def (sp/select-one [::resolved-defs component-id] system)
        signal-fn    (cond
                       ;; this can happen if you have a ref to a nonexistent
                       ;; component. it allows signal application to progress to
                       ;; the point that we can throw an exception saying that
                       ;; the ref is invalid
                       (not resolved-def)
                       system-identity

                       (component? resolved-def)
                       (or (sp/select-one [::resolved-defs computation-stage-node] system)
                           ;; catchall handler is used by subsystem to forward
                           ;; signals
                           (when-let [catchall-handler (sp/select-one [::resolved-defs component-id ::mk-signal-handler]
                                                                      system)]
                             (catchall-handler (last computation-stage-node)))
                           system-identity)

                       :else
                       (constantly resolved-def))

        ;; accomodates signal handlers that are constant values, not functions
        signal-fn (if (fn? signal-fn)
                    signal-fn
                    (constantly signal-fn))]

    (fn [system]
      (let [stage-result (apply-stage-fn system signal-fn component-id)]
        (if (system? stage-result)
          stage-result
          (sp/setval [::instances component-id] stage-result system))))))

(defn- computation-stage-fn
  [system [_ _ stage :as computation-stage-node]]
  (if (handler-stage? stage)
    (handler-stage-fn system computation-stage-node)
    (lifecycle-stage-fn system computation-stage-node)))

(defn- prep-system-for-apply-signal-stage
  "Updates system to
  a) keep track of the current component
  b) resolve all refs for that component
  c) track the updated component def which has refs resolved"
  [system component-id]
  (let [part-prepped (-> system
                         (assoc ::component-id component-id
                                ::component-def (get-in system (into [::defs] component-id)))
                         (resolve-refs component-id))]
    (assoc part-prepped ::current-resolved-component (resolved part-prepped))))

(defn- apply-signal-exception
  "provide a more specific exception for signal application to help narrow down the source of the exception"
  [_system computation-stage t]
  (ex-info (str "Error on " computation-stage " when applying signal")
           {:component      (vec (take 2 computation-stage))
            :signal-handler (last computation-stage)
            :message        #?(:clj (.getMessage t)
                               :cljs (. t -message))}
           t))

(defn- apply-signal-stage
  [system computation-stage-node]
  (let [component-id   (vec (take 2 computation-stage-node))
        prepped-system (prep-system-for-apply-signal-stage system component-id)
        new-system     (try ((computation-stage-fn prepped-system computation-stage-node) prepped-system)
                            (catch #?(:clj Throwable
                                      :cljs js/Error) t
                              (throw (apply-signal-exception prepped-system
                                                             computation-stage-node
                                                             t))))]
    (if (stage-result-valid? new-system)
      (remove-signal-computation-stage-node new-system computation-stage-node)
      (prune-signal-computation-graph new-system computation-stage-node))))

(defn- apply-signal-computation-graph
  [system]
  (loop [{:keys [::signal-computation-graph] :as system} system]
    (let [[computation-stage-node] (la/topsort signal-computation-graph)]
      (if-not computation-stage-node
        system
        (recur (apply-signal-stage system computation-stage-node))))))

;;---
;;; init system, apply signal
;;---

(defn- set-component-keys
  "TODO docs"
  [system signal-name component-keys]
  (assoc system
         ::selected-component-ids
         (set
          (cond
            ;; if not starting, scope component keys to started instances
            (not= ::start signal-name)
            (sp/select [(assoc config-collect-group-path 0 ::instances)
                        sp/MAP-KEYS]
                       system)

            (empty? component-keys)
            (sp/select [config-collect-group-path sp/MAP-KEYS] system)

            ;; starting and specified component keys; expand groups
            :else
            (reduce (fn [cks ck]
                      (if (vector? ck)
                        (conj cks ck)
                        (into cks (->> system
                                       (sp/select [::defs ck sp/MAP-KEYS])
                                       (map vector (repeat ck))))))
                    #{}
                    component-keys)))))

(defn init-system
  [maybe-system signal-name component-keys]
  (-> maybe-system
      (update ::signals #(merge default-signals %))
      merge-base
      (set-component-keys signal-name component-keys)
      (assoc ::last-signal signal-name)
      gen-graphs))

(defn signal
  [system signal-name & [component-keys]]
  (when-let [explanation (m/explain DonutSystem system)]
    (throw (ex-info "Invalid system"
                    {:reason             :system-spec-validation-error
                     :spec-explain-human (me/humanize explanation)
                     :spec-explain       explanation})))

  (let [inited-system (init-system system signal-name component-keys)]
    (when-let [explanation (m/explain (into [:enum] (sp/select [::signals sp/MAP-KEYS] inited-system))
                                      signal-name)]
      (throw (ex-info (str "Signal " signal-name " is not recognized. Add it to ::ds/signals")
                      {:reason             :signal-not-recognized
                       :spec-explain-human (me/humanize explanation)})))
    (-> inited-system
        (init-signal-computation-graph signal-name)
        (apply-signal-computation-graph))))

(defn validate-instance-with-malli
  "helper function for validating component instances with malli if a schema is
  present."
  [{:keys [::instance ->validation ::system]}]
  (let [{:keys [::schema]} (::current-resolved-component system)]
    (some-> (and schema (m/explain schema instance))
            ->validation)))

;;---
;;; subsystems
;;---

(defn- mapify-imports
  "Subsystems can 'import' instances from the parent system. Imports are specified
  as a set of refs; this converts that to a `ComponentGroups` so that it can be
  merged into subsystem's ::instances, thus making the parent instances
  available for ref resolution."
  [imports]
  (reduce (fn [refmap ref]
            (sp/setval [(ref-key ref)] ref refmap))
          {}
          imports))

(defn- merge-imports
  "Copies ref'd instances from parent-system into subsystem so that subsystem's
  imported refs will resolve correctly"
  [{:keys [::imports] :as system-component} parent-system]
  (reduce (fn [system {:keys [key]}]
            (sp/setval [::subsystem ::instances key]
                       (sp/select-one [::instances key] parent-system)
                       system))
          system-component
          imports))

(defn- subsystem-resolver
  [parent-system component-id]
  (->> (default-resolve-refs parent-system component-id)
       (sp/transform [::resolved-defs component-id]
                     (fn [system]
                       (merge-imports system parent-system)))))

(defn- forward-channel
  "used to make all channel 'output' available at the top level"
  [parent-system channel component-id]
  (if-let [chan-val (sp/select-one [::instances component-id channel] parent-system)]
    (sp/setval [channel component-id]
               chan-val
               parent-system)
    parent-system))

(defn- forward-channels
  [{:keys [::component-id] :as parent-system}]
  (-> parent-system
      (forward-channel [::out :info] component-id)
      (forward-channel [::out :error] component-id)
      (forward-channel [::out :warn] component-id)
      (forward-channel [::out :validation] component-id)))

(defn- forward-start-signal
  [signal-name]
  (fn [{:keys [->instance ::system]}]
    (-> system
        ::current-resolved-component
        ::subsystem
        (signal signal-name)
        ->instance
        forward-channels)))

(defn- forward-signal
  [signal-name]
  (fn [{:keys [::instance ->instance]}]
    (-> instance
        (signal signal-name)
        ->instance
        forward-channels)))

(defn subsystem-component
  "Decorates a subsystem so that it can respond to signals when embedded in a
  parent component."
  [subsystem & [imports]]
  {::start             (forward-start-signal ::start)
   ::mk-signal-handler forward-signal
   ::resolve-refs      subsystem-resolver
   ::imports           (mapify-imports imports)
   ::subsystem         (-> subsystem expand-refs-for-graph)})

(defn alias-component
  "creates a component that just provides an instance defined elsewhere in the system"
  [component-id]
  {::start             (fn [{:keys [::aliased-component]}]
                         aliased-component)
   ::aliased-component (ref component-id)})

;;---
;;; sugar; system config helper, lift signals to fns
;;---

(defmulti named-system
  "A way to name different system, e.g. :test, :dev, :prod, etc."
  identity)

(defn assoc-many
  "(assoc-many {} {[:foo :bar] :bux}) => {:foo {:bar :bux}"
  ([m assocs]
   (reduce-kv (fn [m path val] (assoc-in m path val))
              m
              assocs))
  ([m prefix assocs]
   (update-in m prefix assoc-many assocs)))

(defn system
  "specify a system or a system named registered with `config`, and optionally
  provide overrides"
  ([sconf]
   (cond (system? sconf)  sconf
         (keyword? sconf) (named-system sconf)))
  ([sconf component-def-overrides]
   (let [system (system sconf)]
     (cond (map? component-def-overrides) (assoc-many system [::defs] component-def-overrides)
           (fn? component-def-overrides)  (component-def-overrides system)
           (not component-def-overrides)  system
           :else                          (throw (ex-info "component def overrides must be a map or function"
                                                          {:custom-config component-def-overrides}))))))

(defn start
  ([config-name]
   (signal (system config-name) ::start))
  ([config-name custom-config]
   (signal (system config-name custom-config) ::start))
  ([config-name custom-config component-ids]
   (signal (system config-name custom-config) ::start component-ids)))

(defn stop [system] (signal system ::stop))
(defn suspend [system] (signal system ::suspend))
(defn resume [system] (signal system ::resume))


;;---
;;; component helpers
;;---

(def required-component
  "Communicates that a component needs to be provided."
  {::start (fn [_ _ {:keys [::component-id]}]
             (throw (ex-info "Need to define required component"
                             {:component-id component-id})))})
