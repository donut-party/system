(ns donut.examples.printer
  (:require [donut.system :as ds]))

(def system
  {::ds/defs
   {:services {:stack {:start (fn [{:keys [items]} _ _]
                                (atom (vec (range items))))
                       :stop  (fn [_ instance _] (reset! instance []))
                       :conf  {:items 10}}}
    :app      {:printer {:start (fn [{:keys [stack]} _ _]
                                  (doto (Thread.
                                         (fn []
                                           (prn "peek:" (peek @stack))
                                           (swap! stack pop)
                                           (Thread/sleep 1000)
                                           (recur)))
                                    (.start)))
                         :stop  (fn [_ instance _]
                                  (.interrupt instance))
                         :conf  {:stack (ds/ref [:services :stack])}}}}})

;; start the system, let it run for 5 seconds, then stop it
(let [running-system (ds/signal system :start)]
  (Thread/sleep 5000)
  (ds/signal running-system :stop))
