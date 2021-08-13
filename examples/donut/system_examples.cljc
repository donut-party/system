(ns donut.system-examples
  (:require
   [donut.system :as ds]))


(def system
  {:defs {:env {:http-port {:handlers {:init 9090}}}
          :app {:http-server {:deps     {:port (ds/ref [:env :http-port])}
                              :handlers {:init-before
                                         (fn [_ {:keys [port]} _]
                                           (println "starting server on port"
                                                    port))

                                         :init
                                         (fn [_ {:keys [port]} _]
                                           {:state :running
                                            :port  port})

                                         :halt
                                         (fn [_ _ _]
                                           {})}}}}})

;; starting a server
(ds/signal system :init)
