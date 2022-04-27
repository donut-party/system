(ns tutorial.04
  "Coordination through components"
  (:require
   [donut.system :as ds]))

;; This example shows how you can use components to coordinate work. The system
;; has a "worker", a "job loader", and a "queue". The worker and job loader
;; share the queue, using it to communicate.

(def system
  {::ds/defs
   {:queues
    {:work-queue {:start (fn [_ _ _]
                           (atom clojure.lang.PersistentQueue/EMPTY))}}

    :workers
    {:processor {:start (fn [{:keys [queue]} _ _]
                          (let [stop-prom (promise)]
                            (future ;; run on separate thread
                              (while (not (realized? stop-prom))
                                (if-let [message (peek @queue)]
                                  (do
                                    (swap! queue pop)
                                    (println "got message!" message))
                                  (println "no message :("))
                                (Thread/sleep 1000)))
                            stop-prom))
                 :stop  (fn [_ stop-prom _]
                          (deliver stop-prom true))
                 :conf  {:queue (ds/ref [:queues :work-queue])}}

     :loader {:start (fn [{:keys [queue]} _ _]
                       (let [stop-prom (promise)]
                         (future ;; run on separate thread
                           (while (not (realized? stop-prom))
                             (swap! queue conj (str "message! " (rand-int 1000)))
                             (Thread/sleep 1100)))
                         stop-prom))
              :stop  (fn [_ stop-prom _]
                       (deliver stop-prom true))
              :conf  {:queue (ds/ref [:queues :work-queue])}}}}})

(comment
  (def running-system (ds/signal system :start))
  (ds/signal running-system :stop)
  )
