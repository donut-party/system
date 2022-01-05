(ns donut.examples.multiple-http-servers
  (:require
   [donut.system :as ds]
   [ring.adapter.jetty :as rj]))


(def HTTPServer
  {:start (fn [{:keys [handler options]} _ _]
            (rj/run-jetty handler options))
   :stop  (fn [_ instance _]
            (.stop instance))
   :conf  {:handler (ds/ref :handler)
           :options {:port  (ds/ref :port)
                     :join? false}}})

(def system
  {::ds/defs
   {:http-1 {:server  HTTPServer
             :handler (fn [_req]
                        {:status  200
                         :headers {"ContentType" "text/html"}
                         :body    "http server 1"})
             :conf    {:port 8080}}

    :http-2 {:server  HTTPServer
             :handler (fn [_req]
                        {:status  200
                         :headers {"ContentType" "text/html"}
                         :body    "http server 2"})
             :conf    {:port 9090}}}})
