(ns donut.system.plugin
  (:require
   [clojure.data :as data]))

(defn- merge-plugin
  "Recursively merges maps together. If all the maps supplied have nested maps
  under the same keys, these nested maps are merged. Otherwise the value is
  overwritten, as in `clojure.core/merge`."
  ([])
  ([a] a)
  ([a b]
   (when (or a b)
     (letfn [(merge-entry [m e]
               (let [k  (key e)
                     v' (val e)]
                 (if (contains? m k)
                   (assoc m k (let [v (get m k)]
                                (cond (and (map? v) (map? v'))
                                      (merge-plugin v v')

                                      (and (vector? v) (vector? v') (:append (meta v')))
                                      (into v v')

                                      (and (vector? v) (vector? v') (:prepend (meta v')))
                                      (into v' v)

                                      :else
                                      v')))
                   (assoc m k v'))))]
       (reduce merge-entry (or a {}) (seq b)))))
  ([a b & more]
   (reduce merge-plugin (or a {}) (cons b more))))

(defn apply-plugin
  [system {:keys [::system-defaults
                  ::system-merge
                  ::system-update]}]
  (-> system-defaults
      (merge-plugin system)
      (merge-plugin system-merge)
      ((or system-update identity))))

(defn apply-plugins
  "update system map according to plugin instructions"
  [{:keys [:donut.system/plugins] :as system}]
  (reduce apply-plugin system plugins))

(defn describe-plugins
  "provide insight into how plugins are changing the system"
  [{:keys [:donut.system/plugins] :as system}]
  (->> plugins
       (reduce (fn [m plugin]
                 (let [new-system (apply-plugin system plugin)]
                   (-> m
                       (assoc :system new-system)
                       (update :descriptions conj (-> plugin
                                                      (update ::system-update #(when % :function))
                                                      (assoc ::system-diff (take 2 (data/diff system new-system))))))))
               {:system system
                :descriptions []})
       :descriptions))
