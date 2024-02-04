(ns donut.examples.tutorial.02-signal-handlers-b
  (:require
   [donut.system :as ds]))

(def APIPollerComponent
  {::ds/start  (fn [{:keys [::ds/config]}]
                 (let [poll-data (atom nil)]
                   {:poller    (future (loop [i 0]
                                         (println "polling")
                                         (reset! poll-data i)
                                         (Thread/sleep (:interval config))
                                         (recur (inc i))))
                    :poll-data poll-data}))
   ::ds/stop   (fn [{:keys [::ds/instance]}]
                 (println "stopping")
                 (update instance :poller future-cancel))
   ::ds/config {:interval 5000}})

(def system
  {::ds/defs
   {:services
    {:api-poller APIPollerComponent}}})

(comment
  ;; one way to update a config value
  (def running-system
    (ds/signal (assoc-in system [::ds/defs :services :api-poll ::ds/config :interval] 1000)
               ::ds/start))

  (ds/signal running-system ::ds/stop)

  (def running-system
    (ds/start system
              {[:services :api-poll ::ds/config] 1000})))


#_(def running-system
    (ds/start system
              {[:services :api-poll ::ds/config] 1000}))
