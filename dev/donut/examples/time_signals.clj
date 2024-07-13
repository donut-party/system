(ns donut.examples.time-signals
  (:require
   [donut.system :as ds]))

(defn record-start-time-millis
  [{:keys [::ds/component-meta]}]
  (reset! component-meta (System/currentTimeMillis)))

(defn record-elapsed-time-millis
  [{:keys [::ds/component-meta]}]
  (swap! component-meta (fn [start-time-millis]
                          (- (System/currentTimeMillis) start-time-millis))))

(def record-elapsed-time-lifecycle
  #::ds{:pre-start  {:record-start-time-millis record-start-time-millis}
        :post-start {:record-elapsed-time-millis record-elapsed-time-millis}})

(def system
  #::ds{:base record-elapsed-time-lifecycle
        :defs {:group-a
               {:component-a
                #::ds{:start (fn [_] (println "Sleeping for 1 seconds") (Thread/sleep 1000))}}}})

(select-keys (ds/start system) [::ds/component-meta])
