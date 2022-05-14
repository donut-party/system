(ns donut.examples.validate
  (:require
   [donut.system :as ds]
   [malli.core :as m]))

(defn validate-conf
  [{:keys [->validation ::ds/system] :as config}]
  (let [schema (get-in system [::ds/component-def ::ds/schema])]
    (when-let [errors (and schema (m/explain schema config))]
      (->validation errors))))

(def system
  {::ds/defs
   {:group {:component-a #::ds{:before-start validate-conf
                               :start        "component a"
                               :schema       [:map [:foo any?] [:baz any?]]}
            :component-b #::ds{:before-start validate-conf
                               :start        "component b"
                               :schema       [:map [:foo any?] [:baz any?]]}
            :component-c #::ds{:start "component-c"}}}})
