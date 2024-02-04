(ns donut.examples.tutorial.02-signal-handlers-a
  (:require
   [donut.system :as ds]))

(def APIPollerComponent
  {::ds/start (fn [_]
                (let [poll-data (atom nil)]
                  {:poller    (future (loop [i 0]
                                        (println "polling")
                                        (reset! poll-data i)
                                        (Thread/sleep 5000)
                                        (recur (inc i))))
                   :poll-data poll-data}))
   ::ds/stop  (fn [{:keys [::ds/instance]}]
                (println "stopping")
                (update instance :poller future-cancel))})

(def system
  {::ds/defs
   {:services
    {:api-poller APIPollerComponent}}})

(comment
  ;; evaluate these manually at your REPL
  (def running-system (ds/signal system ::ds/start))
  (ds/signal running-system ::ds/stop)

  ;; alternatively, use these convenience functions:
  (def running-system (ds/start system))
  (ds/stop running-system)


  (::ds/instances running-system))
