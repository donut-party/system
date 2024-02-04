(ns donut.examples.tutorial.04-environment-configuration
  (:require
   [aero.core :as aero]
   [clojure.java.io :as io]
   [donut.system :as ds]))

(defn env-config [& [profile]]
  (aero/read-config (io/resource "config/env.edn")
                    (when profile {:profile profile})))

(def DataStoreComponent
  {::ds/start (fn [_] (atom nil))})

(def APIPollerComponent
  {::ds/start  (fn [{:keys [::ds/config]}]
                 (let [{:keys [data-store source]} config]
                   (future (loop [i 0]
                             (println (str "polling " source))
                             (reset! data-store i)
                             (Thread/sleep (:interval config))
                             (recur (inc i))))))
   ::ds/stop   (fn [{:keys [::ds/instance]}]
                 (future-cancel instance))
   ::ds/config {:interval   (ds/ref [:env :api-poller :interval])
                :source     (ds/ref [:env :api-poller :source])
                :data-store (ds/ref [:services :data-store])}})

(def base-system
  {::ds/defs
   {:env      {}
    :services {:api-poller APIPollerComponent
               :data-store DataStoreComponent}}})

(defmethod ds/named-system :base
  [_]
  base-system)

(defmethod ds/named-system :dev
  [_]
  (ds/system :base {[:env] (env-config :dev)}))


(defmethod ds/named-system :prod
  [_]
  (ds/system :base {[:env] (env-config :prod)}))
