(ns donut.examples.basic
  (:require
   [donut.system :as ds]))

(def system
  {::ds/defs
   {:group-1
    {:component-a #::ds{:start (fn [_])
                        :stop (fn [])}
     :component-b #::ds{:start (fn [_])
                        :stop (fn [])}}

    :group-2
    {:component-c #::ds{:start (fn [_])
                        :stop (fn [])}}}})

(def bad-system
  {::ds/defs
   {:group-1
    {:sub-group-1
     ;; component definition is not directly under :group-1
     {:component-a ...}}

    ;; component definition is not in a group
    :component-b ...
    }})
