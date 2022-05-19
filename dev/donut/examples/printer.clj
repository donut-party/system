(ns donut.examples.printer
  (:require [donut.system :as ds]))

(def system
  {::ds/defs
   {:services {:stack #::ds{:start  (fn [{:keys [::ds/config]}]
                                      (atom (vec (range (:items config)))))
                            :stop   (fn [{:keys [::ds/instance]}]
                                      (reset! instance []))
                            :config {:items 10}}}
    :app      {:printer #::ds{:start  (fn [{:keys [::ds/config]}]
                                        (let [{:keys [stack]} config]
                                          (doto (Thread.
                                                 (fn []
                                                   (prn "peek:" (peek @stack))
                                                   (swap! stack pop)
                                                   (Thread/sleep 1000)
                                                   (recur)))
                                            (.start))))
                              :stop   (fn [{:keys [::ds/instance]}]
                                        (.interrupt instance))
                              :config {:stack (ds/ref [:services :stack])}}}}})

;; start the system, let it run for 5 seconds, then stop it
(comment
  (let [running-system (ds/signal system ::ds/start)]
    (Thread/sleep 5000)
    (ds/signal running-system ::ds/stop)))


(def Stack
  #::ds{:start  (fn [{:keys [::ds/config]}] (atom (vec (range (:items config)))))
        :stop   (fn [{:keys [::ds/instance]}] (reset! instance []))
        :config {:items 10}})
