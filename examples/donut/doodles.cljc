(ns donut.doodles
  "a dev scratch pad"
  (:require
   [donut.system :as ds]))

{:defs {:env {:http-port {::ds/init 9090}}
        :app {:http-server
              #::ds{:deps        {:port (ds/ref [:env :http-port])}
                    :init-before (fn [_ {:keys [port]} _]
                                   (println "starting server on port"
                                            port))
                    :init        (fn [_ {:keys [port]} _]
                                   {:state :running
                                    :port  port})
                    :halt        (fn [_ _ _] {})}}}}
