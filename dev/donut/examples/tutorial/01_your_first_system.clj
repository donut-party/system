(ns donut.examples.tutorial.01-your-first-system
  (:require
   [donut.system :as ds]))

(def system
  {::ds/defs
   {:services
    {:printer #::ds{:start (fn [_] (print "donuts are yummy!"))}}}})

(ds/signal system ::ds/start)
