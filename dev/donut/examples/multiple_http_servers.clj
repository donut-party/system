(ns donut.examples.multiple-http-servers
  (:require
   [donut.system :as ds]
   [ring.adapter.jetty :as rj]))


(def HTTPServer
  #::ds{:start  (fn [{:keys [::ds/config]}]
                  (let [{:keys [handler options]} config]
                    (rj/run-jetty handler options)))
        :stop   (fn [{:keys [::ds/instance]}]
                  (.stop instance))
        :config {:handler (ds/local-ref [:handler])
                 :options {:port  (ds/local-ref [:port])
                           :join? false}}})

(def system
  {::ds/defs
   {:http-1 {:server  HTTPServer
             :handler (fn [_req]
                        {:status  200
                         :headers {"ContentType" "text/html"}
                         :body    "http server 1"})
             :port    8080}

    :http-2 {:server  HTTPServer
             :handler (fn [_req]
                        {:status  200
                         :headers {"ContentType" "text/html"}
                         :body    "http server 2"})
             :port    9090}}})
