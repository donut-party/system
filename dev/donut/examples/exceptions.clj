(ns donut.examples.exceptions
  "recover from exceptions"
  (:require
   [donut.system :as ds]))

(def good-atom (atom nil))

(def system
  {::ds/defs
   {:good {:atom #::ds{:start (fn [_] (reset! good-atom :started))
                       :stop  (fn [_] (reset! good-atom :stopped))}}
    :bad  {:fail #::ds{:start  (fn [_] (throw (ex-info "intentional failure" {})))
                       :stop   (fn [_] (throw (ex-info "should not throw" {})))
                       :config {:for-order (ds/ref [:good :atom])}}}}})

(comment
  (ds/start system)
  (ds/stop-failed-system))
