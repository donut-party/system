(ns donut.system-examples
  (:require [donut.system :as ds]))

;; starting a server
(ds/signal
 {:defs {:env {:http-port {:handlers {:init 9090}}}
         :app {:http-server {:deps     {:port (ds/ref [:env :http-port])}
                             :handlers {:init-before
                                        (fn [_ {:keys [port]} _]
                                          (println "starting server on port" port))

                                        :init
                                        (fn [_ {:keys [port]} _]
                                          {:state :running})

                                        :halt
                                        (fn [_ _ _]
                                          {})}}}}}
 :init)
