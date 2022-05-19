(ns donut.examples.subsystem
  (:require [donut.system :as ds]))

(defn mk-print-thread
  [prefix stack]
  (doto (Thread.
         (fn []
           (prn prefix (peek @stack))
           (swap! stack pop)
           (Thread/sleep 1000)
           (recur)))
    (.start)))

(defn print-worker-system
  [print-prefix]
  {::ds/defs
   {:workers
    {:print-worker #::ds{:start  (fn [{:keys [stack]}]
                                   (mk-print-thread print-prefix stack))
                         :stop   (fn [{:keys [::ds/instance]}]
                                   (.stop instance))
                         :config {:stack (ds/ref [:services :stack])}}}}})

(def system
  {::ds/defs
   {:services {:stack #::ds{:start (fn [_] (atom (vec (range 20))))
                            :stop  (fn [{:keys [::ds/instance]}] (reset! instance []))}}
    :printers {:printer-1 (ds/subsystem-component
                           (print-worker-system ":printer-1")
                           #{(ds/ref [:services])})
               :printer-2 (ds/subsystem-component
                           (print-worker-system ":printer-2")
                           #{(ds/ref [:services :stack])})}}})
