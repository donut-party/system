(ns donut.examples.validate
  (:require
   [donut.system :as ds]
   [donut.system.validation :as dsv]))

(def system
  {::ds/defs
   {:group {:component-a #::ds{:start           (fn [_] "this doesn't get called because config is invalid")
                               :config          {:max "100"}
                               :config-schema   [:map [:max pos-int?]]
                               :instance-schema pos-int?}}}
   ::ds/plugins [dsv/validation-plugin]})

(ds/start system)
