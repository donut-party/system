(ns donut.examples.single-component
  (:require
   [donut.system :as ds]))

(def system
  {::ds/defs
   {:app {:printer #::ds{:start (fn [_]
                                  (future
                                    (loop []
                                      (println "hello!")
                                      (Thread/sleep 1000)
                                      (recur))))
                         :stop  (fn [{:keys [::ds/instance]}]
                                  (future-cancel instance))}}}})

;; start the system, let it run for 5 seconds, then stop it
(comment
  (let [running-system (ds/signal system ::ds/start)]
    (Thread/sleep 5000)
    (ds/signal running-system ::ds/stop)))


(def Stack
  #::ds{:start  (fn [{:keys [::ds/config]}] (atom (vec (range (:items config)))))
        :stop   (fn [{:keys [::ds/instance]}] (reset! instance []))
        :config {:items 10}})
