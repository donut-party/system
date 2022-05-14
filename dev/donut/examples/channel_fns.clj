(ns donut.examples.channel-fns
  (:require [donut.system :as ds]))

(def system
  {::ds/defs
   {:group {:component-a #::ds{:start       "component a"
                               :after-start (fn [{:keys [->info]}]
                                              (->info "component a is valid"))}
            :component-b #::ds{:start       "component b"
                               :after-start (fn [{:keys [->validation]}]
                                              (->validation "component b is invalid"))
                               ;; This `:config` is only here to create the
                               ;; dependency order for demonstration purpose
                               :config      {:ref (ds/ref :component-a)}}
            :component-c #::ds{:start       "component-c"
                               :after-start (fn [_]
                                              (prn "this won't print"))
                               ;; This `:config` is only here to create the
                               ;; dependency order for demonstration purpose
                               :config      {:ref (ds/ref :component-b)}}}}})
