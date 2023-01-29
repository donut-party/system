(ns donut.examples.printer
  (:require [donut.system :as ds]))

(def system
  {::ds/defs
   {:services
    {:stack #::ds{:start  (fn [{:keys [::ds/config]}]
                            (atom (vec (range (:items config)))))
                  :config {:items 10}}}

    :app
    {:printer #::ds{:start  (fn [opts]
                              (let [stack (get-in opts [::ds/config :stack])]
                                (future
                                  (loop []
                                    (prn "peek:" (peek @stack))
                                    (swap! stack pop)
                                    (Thread/sleep 1000)
                                    (recur)))))
                    :stop   (fn [{:keys [::ds/instance]}]
                              (prn "stopping")
                              (future-cancel instance))
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
