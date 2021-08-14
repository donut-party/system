(ns donut.doodles
  "a dev scratch pad"
  (:require
   [donut.system :as ds]))

{:defs {:env {:http-port {:init 9090}}
        :app {:http-server
              {:port        (ds/ref [:env :http-port])
               :init-before (fn [_ {:keys [port]} _]
                              (println "starting server on port"
                                       port))
               :init        (fn [_ {:keys [port]} _]
                              {:state :running
                               :port  port})
               :halt        (fn [_ _ _] {})}}}}

{:defs {:env {:http-port {:init 9090}}
        :app {:http-server {:port        (ds/ref [:env :http-port])
                            :init-before (fn [_ {:keys [port]} _]
                                           (println "starting server on port"
                                                    port))
                            :init        (fn [_ {:keys [port]} _]
                                           {:state :running
                                            :port  port})
                            :halt        (fn [_ _ _] {})}}}}

{:env {:http-port {:init   9090
                   :schema [:foo]}}}

[{:port (ds/ref [:env :http-port])
  :init "foo"}]

(def HttpComponent
  {:port (ds/ref [:env :http-port])
   :init-before (fn [_ {:keys [port]} _]
                  (println "starting server on port"
                           port))
   :init        (fn [_ {:keys [port]} _]
                  {:state :running
                   :port  port})
   :halt        (fn [_ _ _] {})})

(def HttpComponent2
  [{:port (ds/ref [:env :http-port])}
   HttpComponent])
