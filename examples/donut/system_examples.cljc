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
                                         (fn [_ _ _] {})}}}}})

;; starting a server
(ds/signal system :init)

;;---
;;; multiple server system
;;---
(def http-server-component
  {:deps     {:port (ds/ref [:env :http-port])}
   :handlers {:init
              (fn [_ {:keys [port]} _]
                {:state :running
                 :port  (port)})

              :init-after
              (fn [{:keys [port]} _ _]
                (prn "started http server on port "
                     port))

              :init-around
              (fn [_ _ {:keys [->info]}]
                (->info {:time 1}))

              :halt
              (fn [_ _ _]
                {})}})

(def multiple-server-system
  {:defs {:env {:http-port {:handlers {:init (fn [& _]
                                               (let [port-num (atom 9090)]
                                                 #(swap! port-num inc)))}}}
          :app {:http-server-1 http-server-component
                :http-server-2 http-server-component}}})
