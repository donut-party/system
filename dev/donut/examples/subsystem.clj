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
    {:print-worker {:start (fn [{:keys [stack]} _ _]
                             (mk-print-thread print-prefix stack))
                    :stop  (fn [_ instance _]
                             (.stop instance))
                    :stack (ds/ref [:services :stack])}}}})

(def system
  {::ds/defs
   {:services {:stack {:start (fn [_ _ _] (atom (vec (range 20))))
                       :stop  (fn [_ instance _] (reset! instance []))}}

    :printers {:printer-1 (ds/subsystem-component
                           (print-worker-system ":printer-1")
                           #{(ds/group-ref :services)})
               :printer-2 (ds/subsystem-component
                           (print-worker-system ":printer-2")
                           #{(ds/ref [:services :stack])})}}})
