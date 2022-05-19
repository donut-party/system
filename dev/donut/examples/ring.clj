(ns donut.examples.ring
  (:require [donut.system :as ds]
            [ring.adapter.jetty :as rj]))

(def system
  {::ds/defs
   {:env  {:http-port 8080}
    :http {:server  #::ds{:start  (fn [{:keys [::ds/config]}]
                                    (let [{:keys [handler options]} config]
                                      (rj/run-jetty handler options)))
                          :stop   (fn [{:keys [::ds/instance]}]
                                    (.stop instance))
                          :config {:handler (ds/local-ref [:handler])
                                   :options {:port  (ds/ref [:env :http-port])
                                             :join? false}}}
           :handler (fn [_req]
                      {:status  200
                       :headers {"ContentType" "text/html"}
                       :body    "It's donut.system, baby!"})}}})


(def base-system
  {::ds/defs
   {:env
    {:http {:port 8080}}

    :http
    {:server
     #::ds{:start  (fn [{:keys [handler options]}]
                     (rj/run-jetty handler options))
           :config {:handler (ds/local-ref [:handler])
                    :options {:port  (ds/ref [:env :http :port])
                              :join? false}}}}}})
