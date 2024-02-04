(ns donut.examples.tutorial.03-multiple-components
  (:require
   [donut.system :as ds]))

(def DataStoreComponent
  {::ds/start (fn [_] (atom nil))})

(def APIPollerComponent
  {::ds/start  (fn [{:keys [::ds/config]}]
                 (let [data-store (:data-store config)]
                   (future (loop [i 0]
                             (println "polling")
                             (reset! data-store i)
                             (Thread/sleep (:interval config))
                             (recur (inc i))))))
   ::ds/stop   (fn [{:keys [::ds/instance]}]
                 (println "stopping")
                 (future-cancel instance))
   ::ds/config {:interval 5000
                :data-store (ds/ref [:services :data-store])}})

(def system
  {::ds/defs
   {:services
    {:api-poller APIPollerComponent
     :data-store DataStoreComponent}}})
