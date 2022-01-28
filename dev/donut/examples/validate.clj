(ns donut.examples.validate
  (:require
   [donut.system :as ds]
   [malli.core :as m]))

(defn validate-conf
  [conf _ {:keys [->validation ::ds/component-def]}]
  (let [schema (:schema component-def)]
    (when-let [errors (and schema (m/explain schema conf))]
      (->validation errors))))

(def system
  {::ds/defs
   {:group {:component-a {:before-start validate-conf
                          :start        "component a"
                          :schema       [:map [:foo any?] [:baz any?]]}
            :component-b {:before-start validate-conf
                          :start        "component b"
                          :schema       [:map [:foo any?] [:baz any?]]}
            :component-c {:start "component-c"}}}})
