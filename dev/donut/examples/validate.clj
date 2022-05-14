(ns donut.examples.validate
  (:require
   [donut.system :as ds]
   [malli.core :as m]))

(defn validate-config
  [{:keys [->validation schema] :as config}]
  (when-let [errors (and schema (m/explain schema config))]
    (->validation errors)))

(def system
  {::ds/defs
   {:group {:component-a #::ds{:before-start validate-config
                               :start        "component a"
                               :config       {:schema [:map [:foo any?] [:baz any?]]}}
            :component-b #::ds{:before-start validate-config
                               :start        "component b"
                               :config       {:schema [:map [:foo any?] [:baz any?]]}}
            :component-c #::ds{:start "component-c"}}}})
