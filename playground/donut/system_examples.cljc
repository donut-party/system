(ns donut.system-examples
  (:require
   [donut.system :as ds]))


(def system
  {:defs {:env {:http-port {:handlers {:start 9090}}}
          :app {:http-server {:deps     {:port (ds/ref [:env :http-port])}
                              :handlers {:start-before
                                         (fn [_ {:keys [port]} _]
                                           (println "starting server on port"
                                                    port))

                                         :start
                                         (fn [_ {:keys [port]} _]
                                           {:state :running
                                            :port  port})

                                         :stop
                                         (fn [_ _ _] {})}}}}})

;; starting a server
(ds/signal system :start)

;;---
;;; multiple server system
;;---
(def http-server-component
  {:deps     {:port (ds/ref [:env :http-port])}
   :handlers {:start
              (fn [_ {:keys [port]} _]
                {:state :running
                 :port  (port)})

              :start-after
              (fn [{:keys [port]} _ _]
                (prn "started http server on port "
                     port))

              :start-around
              (fn [_ _ {:keys [->info]}]
                (->info {:time 1}))

              :stop
              (fn [_ _ _]
                {})}})

(def multiple-server-system
  {:defs {:env {:http-port {:handlers {:start (fn [& _]
                                                (let [port-num (atom 9090)]
                                                  #(swap! port-num inc)))}}}
          :app {:http-server-1 http-server-component
                :http-server-2 http-server-component}}})

;;---
;;; relative refs
;;---

(def server-component-group
  {:http-server
   {:deps     {:port        (ds/ref [:env :http-port])
               :req-handler (ds/ref :req-handler)}
    :handlers {:start
               (fn [_ {:keys [port req-handler]} _]
                 {:state       :running
                  :port        (port)
                  :req-handler req-handler})

               :start-after
               (fn [{:keys [port]} _ _]
                 (prn "started http server on port "
                      port))

               :start-around
               (fn [_ _ {:keys [->info]}]
                 (->info {:time 1}))

               :stop
               (fn [_ _ _]
                 {})}}})

(def relative-ref-system
  {:defs {:env      {:http-port {:handlers {:start (fn [& _]
                                                     (let [port-num (atom 9090)]
                                                       #(swap! port-num inc)))}}}
          :server-1 (assoc-in server-component-group
                              [:req-handler :handlers :start]
                              (constantly (fn req-handler-1 [req] req)))
          :server-2 (assoc-in server-component-group
                              [:req-handler :handlers :start]
                              (constantly (fn req-handler-2 [req] req)))}})

(def relative-ref-system
  {:defs {:env      {:http-port {:handlers {:start (fn [& _]
                                                     (let [port-num (atom 9090)]
                                                       #(swap! port-num inc)))}}}
          :server-1 [server-component-group
                     {:req-handler {:handlers {:start (constantly (fn req-handler-1 [req] req))}}}]

          :server-2 [server-component-group
                     {:req-handler {:handlers {:start (constantly (fn req-handler-2 [req] req))}}}]}})


;;---
;;; groups
;;---

#::ds{:defs
      {:env
       {:app-name "foo.app"}

       :common-services
       {:job-queue {:start "job queue"}
        :db        {:start "db"}}

       :sub-systems
       {:system-1 (ds/subsystem-component
                   #::ds{:defs {:app {:job-queue (ds/ref [:common-services :job-queue])
                                      :db        (ds/ref [:common-services :db])}}})
        :system-2 (ds/subsystem-component
                   #::ds{:defs {:app {:job-queue (ds/ref [:common-services :job-queue])
                                      :db        (ds/ref [:common-services :db])}}})}}}