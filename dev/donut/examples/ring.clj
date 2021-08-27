(ns donut.examples.ring
  (:require [donut.system :as ds]
            [ring.adapter.jetty :as rj]))

(def system
  {::ds/defs
   {:env  {:http-port 8080
           :app-name  "example"}
    :http {:server  {:init    (fn [{:keys [handler options]} _instance _system]
                                (prn "startup" handler options)
                                (rj/run-jetty handler options))
                     :halt    (fn [_ instance _]
                                (.stop instance))
                     :handler (ds/ref :handler)
                     :options {:port  (ds/ref [:env :http-port])
                               :join? false}}
           :handler (fn [req]
                      (prn "handler!")
                      {:status  200
                       :headers {"ContentType" "text/html"}
                       :body    "boosh!"})}}})
