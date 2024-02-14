(ns donut.system
  (:refer-clojure :exclude [ref])
  (:require
   [clojure.walk :as walk]
   [clojure.zip :as zip]
   [donut.system.plugin :as dsp]
   [loom.alg :as la]
   [loom.derived :as ld]
   [loom.graph :as lg]
   [malli.core :as m]
   [malli.error :as me]))

(declare with-*system*)

;;---
;; helpers
;;---

(defn flat-get-in
  [m p]
  (get-in m (flatten p)))

(defn flat-assoc-in
  [m p v]
  (assoc-in m (vec (flatten p)) v))

(defn component-ids
  [system & [facet-key]]
  (let [component-facet ((or facet-key ::defs) system)]
    (for [k1 (keys component-facet)
          k2 (keys (get component-facet k1))]
      [k1 k2])))

(defn assoc-many
  "(assoc-many {} {[:foo :bar] :bux}) => {:foo {:bar :bux}"
  ([m assocs]
   (reduce-kv (fn [m path val] (assoc-in m path val))
              m
              assocs))
  ([m prefix assocs]
   (update-in m prefix assoc-many assocs)))

(defn update-many
  "Map of many paths to update, and their update fns"
  [m update-many-with-map]
  (reduce-kv (fn [m path f] (update-in m path f))
             m
             update-many-with-map))

(defn configure-component
  "Help in setting just the config of a component"
  [m configs]
  (assoc-many m [::config] configs))

;;---
;;; specs
;;---

(def default-signals
  "which graph sort order to follow to apply signal, and where to put result"
  {::start   {:order             :reverse-topsort
              :returns-instance? true}
   ::stop    {:order             :topsort
              :returns-instance? true}
   ::suspend {:order             :topsort
              :returns-instance? true}
   ::resume  {:order             :reverse-topsort
              :returns-instance? true}
   ::status  {:order :reverse-topsort}})

(def Component
  (->> default-signals
       keys
       (mapv (fn [k] [:map [k any?]]))
       (into [:or])))

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

(def PluginSystem
  [:map
   [::defs {:optional true} ComponentGroups]
   [::base {:optional true} [:map]]])

(def Plugin
  [:map
   [::dsp/name keyword?]
   [::doc {:optional true} string?]
   [::dsp/system-defaults {:optional true} PluginSystem]
   [::dsp/system-merge {:optional true} PluginSystem]
   [::dsp/system-update {:optional true} fn?]])

(def DonutSystem
  [:map
   [::defs ComponentGroups]
   [::base {:optional true} [:map]]
   [::resolved-defs {:optional true} ComponentGroups]
   [::graphs {:optional true} OrderGraphs]
   [::instances {:optional true} ComponentGroups]
   [::out {:optional true} ComponentGroups]
   [::signals {:optional true} [:map-of keyword? SignalConfig]]
   [::selected-component-ids {:optional true} [:set ComponentSelection]]
   [::plugins {:optional true} [:vector Plugin]]])

(def system? (m/validator DonutSystem))

(def DeepRefPathKey
  [:or keyword? string? symbol?])

(def LocalRefKey
  [:and
   [:vector :any]
   [:catn
    [:component-name ComponentName]
    [:deep-ref-path [:* DeepRefPathKey]]]])

(def LocalRef
  [:catn
   [:ref-type [:enum ::local-ref]]
   [:ref-key LocalRefKey]])

(def RefKey
  [:and
   [:vector :any]
   [:catn
    [:component-group-name ComponentGroupName]
    [:component-name [:? ComponentName]]
    [:deep-ref-path [:* DeepRefPathKey]]]])

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
(def ref-type (fn [v] (when (seqable? v) (first v))))

(defn- ensure-valid-ref [ref]
  (when-let [explanation (m/explain DonutRef ref)]
    (throw (ex-info (str "Invalid ref: " (pr-str ref))
                    {:spec-explain-human (me/humanize explanation)
                     :spec-explain       explanation})))
  ref)

(defn ref [k] (ensure-valid-ref [::ref k]))
(defn local-ref [k] (ensure-valid-ref [::local-ref k]))
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

(def MinComponent
  "Check that at least one of the signals is present"
  (->> default-signals
       keys
       (mapv (fn [k] [:map [k any?]]))
       (into [:or])))

(def component? (m/validator MinComponent))
;;---
;;; util/ helpers / misc
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
  (if (component? component-def)
    (merge base component-def)
    component-def))

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
                "references undefined component group '" referenced-component-group-name "'")
           {:referencing-component-id        referencing-component-id
            :referenced-component-group-name referenced-component-group-name}))

(defn- resolve-ref
  [system referencing-component-id ref]
  (let [[component-group-name component-name :as rkey] (ref-key ref)
        returns-instance? (get-in system [::signals (::last-signal system) :returns-instance?])]
    (when returns-instance?
      (when-not (contains? (::instances system) component-group-name)
        (throw (group-ref-exception system
                                    (component-id ref)
                                    component-group-name)))
      (when (and component-name
                 (not (contains? (get-in system [::instances component-group-name])
                                 component-name)))
        (throw (ref-exception system
                              referencing-component-id
                              (component-id ref)))))
    (flat-get-in system [::instances rkey])))

;; ref resolution zipping
;;
;; this is all a bit opaque but the overall idea is that we want to visit every
;; node in the system defs and replace all refs with the instances they refer
;; too, EXCEPT in sub systems.
;;
;; We use zippers to visit every node and perform the replacement.

(defn- skip-system-next
  [loc]
  (if (= :end (loc 1))
    loc
    (or
     (zip/right loc)
     (loop [p loc]
       (if (zip/up p)
         (or (zip/right (zip/up p)) (recur (zip/up p)))
         [(zip/node p) :end])))))

(defn- zip-walk [f z]
  (cond
    (zip/end? z)
    (zip/root z)

    (system? (zip/node z))
    (recur f (skip-system-next z))

    :else
    (recur f (zip/next (f z)))))

(defn- map-vec-zipper [m]
  (zip/zipper
   (fn [x] (or (map? x) (sequential? x)))
   seq
   (fn [p xs]
     (cond
       (nil? p) nil
       (map-entry? p) (into [] xs)
       (seq? p) (seq xs)
       :else (into (empty p) xs)))
   m))

(defn- resolve-component-refs [system component-def component-id]
  (let [zipper (map-vec-zipper component-def)]
    (zip-walk (fn [loc]
                (let [node (zip/node loc)
                      rt   (ref-type node)]
                  (cond
                    (= ::ref rt)
                    (zip/replace
                     loc
                     (resolve-ref system component-id node))

                    (= ::local-ref rt)
                    (zip/replace
                     loc
                     (resolve-ref system component-id (ref (into [(first component-id)]
                                                                 (ref-key node)))))

                    :else
                    loc)))
              zipper)))

(defn- default-resolve-refs
  [system component-id]
  (-> system
      (flat-assoc-in [::resolved-defs component-id] (flat-get-in system [::defs component-id]))
      (update-in
       (into [::resolved-defs] component-id)
       #(resolve-component-refs system % component-id))))

;; end ref resolution zipping

(defn- resolve-refs
  "produces an updated component def where refs are replaced by the instance of
  the thing being ref'd. places result under ::resolved-defs. allows custom
  resolution fns to be defined with ::resolve-refs, a feature used to work with
  subsystems"
  [system component-id]
  (if-let [resolution-fn (flat-get-in system [::defs component-id ::resolve-refs])]
    (resolution-fn system component-id)
    (default-resolve-refs system component-id)))

(defn resolved
  [{:keys [::component-id ::resolved-defs]}]
  (get-in resolved-defs component-id))

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
       component-ids
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
  (reduce (fn [expanded-system component-id]
            (let [component-path (into [::defs] component-id)]
              ;; skip subsystem components
              (if (::subystem (get-in expanded-system component-path))
                expanded-system
                (update-in expanded-system
                           component-path
                           (fn [component-def]
                             (if (seqable? component-def)
                               (walk/postwalk (fn [x]
                                                (let [rt (ref-type x)]
                                                  (cond
                                                    (group-ref? x)
                                                    (let [group-name (first (ref-key x))]
                                                      {group-name
                                                       (->> (get-in system [::defs group-name])
                                                            keys
                                                            (reduce (fn [group-map k]
                                                                      (assoc group-map k (ref [group-name k])))
                                                                    {}))})

                                                    (= ::local-ref rt)
                                                    (ref (into [(first component-id)] (ref-key x)))

                                                    (= ::ref rt)
                                                    x

                                                    :else
                                                    x)))
                                              component-def)
                               component-def))))))
          system
          (component-ids system)))

(defn- component-refs
  "helper for ref-edges"
  [component-def]
  (when (seqable? component-def)
    (let [refs (atom [])]
      (walk/postwalk (fn [x]
                       (when (ref? x)
                         (swap! refs conj x))
                       x)
                     component-def)
      @refs)))

(defn- ref-edges
  "used to populate the component graph with directed edges"
  [system direction]
  (let [expanded-system (expand-refs-for-graph system)
        defs (::defs expanded-system)]
    (->>
     ;; collect all component refs for all components
     (for [k1  (keys defs)
           k2  (keys (get defs k1))
           ref (component-refs (get-in expanded-system [::defs k1 k2]))]
       [k1 k2 ref])

     ;; respect graph edge directionality
     (map (fn [[group-name component-name ref]]
            (let [parent [group-name component-name]
                  child  (component-id ref)]
              (if (= :topsort direction)
                [parent child]
                [child parent])))))))

(defn- component-graph-add-edges
  "uses refs to build a dependency map for components"
  [graph system direction]
  (reduce (fn [graph edge]
            (lg/add-edges graph edge))
          graph
          (ref-edges system direction)))

(defn- gen-graphs
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
     :pre          (keyword snns (str "pre-" snn))
     :post         (keyword snns (str "post-" snn))}))

(defn- gen-signal-computation-graph
  "creates the graph that should be traversed to call handler (and lifecycle) fns
  for the given signal"
  [system signal order]
  (let [component-graph        (get-in system [::graphs order])
        {:keys [pre post]} (handler-lifecycle-names signal)
        sorted-graph (la/topsort component-graph)]
    (when-not sorted-graph
      (throw (ex-info "Cycle detected" {})))
    (reduce (fn [computation-graph component-node]
              (let [;; generate nodes and edges just for the lifecycle of this
                    ;; component's signal handler
                    computation-graph (->> [pre signal post]
                                           (map #(conj component-node %))
                                           (partition 2 1)
                                           (apply lg/add-edges computation-graph))
                    successors        (lg/successors component-graph component-node)]
                (reduce (fn [computation-graph successor-component-node]
                          (lg/add-edges computation-graph
                                        [(conj component-node post)
                                         (conj successor-component-node pre)]))
                        computation-graph
                        successors)))
            (lg/digraph)
            sorted-graph)))

(defn- init-signal-computation-graph
  [system signal]
  (assoc system
         ::signal-computation-graph
         (gen-signal-computation-graph system
                                       signal
                                       (get-in system [::signals signal :order]))))

(defn- handler-stage?
  "The stage corresponds to a signal (eg ::start), not a signal lifecycle (eg ::pre-start)"
  [stage]
  (not (re-find #"(^pre-|^post-)" (name stage))))

(defn- apply-stage-fn
  [system stage-fn component-id]
  (let [resolved-def (flat-get-in system [::resolved-defs component-id])]
    (stage-fn
     ;; construct map to pass to the `stage-fn`
     (cond-> {::instance     (flat-get-in system [::instances component-id])
              ::system       system
              ::component-id component-id}
       (map? resolved-def) (merge resolved-def)))))

(defn- stage-result-valid?
  [system]
  (-> system
      ::out
      (select-keys [:error :validation])
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
        stage-fn     (or (flat-get-in system [::resolved-defs computation-stage-node])
                         system-identity)]
    (fn [system]
      (let [stage-result (apply-stage-fn system stage-fn component-id)]
        (if (system? stage-result)
          stage-result
          system)))))

(defn- handler-stage-fn
  "returns function for a handler (e.g. ::start) as opposed to a lifecycle
  fn (e.g. ::pre-start)"
  [system computation-stage-node]
  (let [component-id (vec (take 2 computation-stage-node))
        resolved-def (flat-get-in system [::resolved-defs component-id])
        signal-fn    (cond
                       ;; this can happen if you have a ref to a nonexistent
                       ;; component. it allows signal application to progress to
                       ;; the point that we can throw an exception saying that
                       ;; the ref is invalid
                       (not resolved-def)
                       system-identity

                       (component? resolved-def)
                       (or (flat-get-in system [::resolved-defs computation-stage-node])
                           ;; catchall handler is used by subsystem to forward
                           ;; signals
                           (when-let [catchall-handler (flat-get-in system [::resolved-defs component-id ::mk-signal-handler])]
                             (catchall-handler (last computation-stage-node)))
                           system-identity)

                       :else
                       (constantly resolved-def))

        ;; accomodates signal handlers that are constant values, not functions
        signal-fn (if (fn? signal-fn)
                    signal-fn
                    (constantly signal-fn))]

    (fn [system]
      (let [stage-result (apply-stage-fn system signal-fn component-id)
            result-key   (if (get-in system [::signals (last computation-stage-node) :returns-instance?])
                           ::instances
                           (last computation-stage-node))]
        (if (system? stage-result)
          stage-result
          (assoc-in system (into [result-key] component-id) stage-result))))))

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
  (-> system
      (assoc ::component-id component-id
             ::component-def (get-in system (into [::defs] component-id)))
      (resolve-refs component-id)))

(defn- apply-signal-exception
  "provide a more specific exception for signal application to help narrow down the source of the exception"
  [system computation-stage t]
  (ex-info (str "Error on " computation-stage " when applying signal")
           {:component      (vec (take 2 computation-stage))
            :signal-handler (last computation-stage)
            :message        #?(:clj (.getMessage t)
                               :cljs (. t -message))
            ::system        system}
           t))

(defn- apply-signal-stage
  [system computation-stage-node]
  (let [component-id   (vec (take 2 computation-stage-node))
        prepped-system (prep-system-for-apply-signal-stage system component-id)
        new-system     (try ((computation-stage-fn prepped-system computation-stage-node) prepped-system)
                            (catch #?(:clj Throwable
                                      :cljs :default) t
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

(defn select-components
  "You can scope down what component keys to use. In subsequent interactions with
  a system, use only those component keys."
  [system component-keys]
  (assoc system
         ::selected-component-ids
         (set
          (if (empty? component-keys)
            (component-ids system)
            (reduce (fn [cks ck]
                      (if (vector? ck)
                        (conj cks ck)
                        (into cks (->> (get-in system [::defs ck])
                                       keys
                                       (map vector (repeat ck))))))
                    #{}
                    component-keys)))))

(defn init-system
  [maybe-system signal-name]
  (-> maybe-system
      (update ::signals #(merge default-signals %))
      dsp/apply-plugins
      merge-base
      (assoc ::last-signal signal-name)
      gen-graphs))

(defn signal
  [system signal-name]
  (when-let [explanation (m/explain DonutSystem system)]
    (throw (ex-info "Invalid system"
                    {:reason             :system-spec-validation-error
                     :spec-explain-human (me/humanize explanation)
                     :spec-explain       explanation})))

  (let [inited-system (init-system system signal-name)]
    (when-let [explanation (m/explain (into [:enum] (->> inited-system ::signals keys))
                                      signal-name)]
      (throw (ex-info (str "Signal " signal-name " is not recognized. Add it to ::ds/signals")
                      {:reason             :signal-not-recognized
                       :spec-explain-human (me/humanize explanation)})))
    (-> inited-system
        (init-signal-computation-graph signal-name)
        (apply-signal-computation-graph))))

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
            (assoc-in refmap (ref-key ref) ref))
          {}
          imports))

(defn- merge-imports
  "Copies ref'd instances from parent-system into subsystem so that subsystem's
  imported refs will resolve correctly"
  [{:keys [::imports] :as system-component} parent-system]
  (reduce (fn [system {:keys [key]}]
            (assoc-in system
                      (into [::subsystem ::instances] key)
                      (get-in parent-system (into [::instances] key))))
          system-component
          imports))

(defn- subsystem-resolver
  [parent-system component-id]
  (-> (default-resolve-refs parent-system component-id)
      (update-in (into [::resolved-defs] component-id)
                 (fn [system]
                   (merge-imports system parent-system)))))

(defn- forward-start-signal
  [signal-name]
  (fn [{:keys [::subsystem ::system ::component-id]}]
    (assoc-in system
              (into [::instances] component-id)
              (signal subsystem signal-name))))

(defn- forward-signal
  [signal-name]
  (fn [{:keys [::instance ::system ::component-id]}]
    (assoc-in system
              (into [::instances] component-id)
              (signal instance signal-name))))

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
                                                          {:component-def-overrides component-def-overrides})))))
  ([sconf component-def-overrides selected-components]
   (-> sconf
       (system component-def-overrides)
       (select-components selected-components))))

(defn start
  ([config-name]
   (signal (system config-name) ::start))
  ([config-name component-def-overrides]
   (signal (system config-name component-def-overrides) ::start))
  ([config-name component-def-overrides component-ids]
   (signal (system config-name component-def-overrides component-ids) ::start)))

(defn stop [system] (signal system ::stop))
(defn suspend [system] (signal system ::suspend))
(defn resume [system] (signal system ::resume))

(defn stop-failed-system
  "Will attempt to stop a system that threw an exception when starting"
  ([] (stop-failed-system *e))
  ([e]
   (when-let [system (and e (::system (ex-data e)))]
     (stop system))))

;;---
;;; component helpers
;;---

(def required-component
  "A placeholder component used to communicate that a component needs to be
  provided."
  {::start (fn [{:keys [::system]}]
             (throw (ex-info "Need to define required component"
                             {:component-id (::component-id system)})))})

(defn instance
  "Get a specific component instance. With no arguments returns set of all
  component names."
  ([system]
   (into {}
         (for [[k m] (::instances system)]
           [k (set (keys m))])))
  ([system [component-group component-name :as component-id]]
   (or (flat-get-in system [::instances  component-id])
       (when-not (contains? (get-in system [::defs component-group])
                            component-name)
         (throw (ex-info "Component not defined" {:component-id component-id}))))))

(defn component-doc
  [system component-id]
  (or (:doc (meta (instance system component-id)))
      (flat-get-in system [::defs component-id ::doc])))

(defn component-dependencies
  "Set of all references to other components"
  [system component-id]
  (let [deps (atom [])]
    (walk/postwalk (fn [x]
                     (when (ref? x)
                       (swap! deps conj x))
                     x)
                   (flat-get-in system [::defs component-id]))
    (->> @deps
         (map (fn [[reftype ref]]
                (if (= reftype ::local-ref)
                  (into [(first component-id)] ref)
                  ref)))
         (set))))

(defn dependency-pairs
  "Pairs of [A B], where A is component that depends on B"
  [system]
  (for [component-id (component-ids system)
        dep          (component-dependencies system component-id)]
    [component-id dep]))

(defn describe-component
  [system component-id]
  {:name            component-id
   :config          (flat-get-in system [::defs component-id ::config])
   :resolved-config (flat-get-in system [::resolved-defs component-id ::config])
   :instance        (instance system component-id)
   :status          (flat-get-in system [::status component-id])
   :doc             (component-doc system component-id)
   :dependencies    (component-dependencies system component-id)})

(defn describe-system
  [system]
  (let [system (signal system ::status)]
    (reduce (fn [m component-id]
              (assoc-in m component-id (describe-component system component-id)))
            {}
            (component-ids system))))

(defn describe-components
  [system]
  (let [desc (describe-system system)]
    (for [[_group-name components] desc
          [_component-name component-desc] components]
      component-desc)))

;; useful for tests

(def ^:dynamic *system* nil)

(defmacro with-*system*
  "Start a system and bind it to *system*. Stop system after body."
  [system & body]
  `(binding [*system* (try
                        (start ~system)
                        (catch #?(:clj Throwable
                                  :cljs :default) e#
                          (stop-failed-system e#)
                          (throw e#)))]
     (try
       ~@body
       (finally (stop *system*)))))

(defn system-fixture
  "To be used with `use-fixtures`"
  [system]
  (fn [f] (with-*system* system (f))))

(def component-instance-cache
  (atom {}))

(defn reset-component-instance-cache!
  []
  (reset! component-instance-cache {}))

;; TODO cache conflicts across different systems
;; TODO updated named-system to include a ::system-id

(defn cache-component
  "cache component instance so that it's preserved between start/stop. takes as
  its argument a component def which must at least have a `::start` signal handler
  that's a function. Useful for e.g. not restarting a threadpool for every test.

  ::stop signal won't fire unless instance is removed from
  `component-instance-cache`

  instances are cached by [group-name component-name cache-key]"
  [{:keys [::start ::stop] :as component-def} & [cache-key]]
  (merge component-def
         {::start     (fn [{:keys [::component-id ::cache-key] :as signal-args}]
                        (let [cache-key (cond-> component-id
                                          cache-key (conj cache-key))]
                          (swap! component-instance-cache
                                 update
                                 cache-key
                                 (fn [component-instance]
                                   (or component-instance
                                       (start signal-args)
                                       true)))))
          ::stop      (fn [{:keys [::component-id ::cache-key] :as signal-args}]
                        (let [cache-key (cond-> component-id
                                          cache-key (conj cache-key))]
                          (when (and stop
                                     (not (get @component-instance-cache cache-key)))
                            (stop signal-args))))
          ::cache-key cache-key}))
