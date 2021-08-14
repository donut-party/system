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

;;---
;;; relative refs
;;---

(def server-component-group
  {:http-server
   {:deps     {:port        (ds/ref [:env :http-port])
               :req-handler (ds/ref :req-handler)}
    :handlers {:init
               (fn [_ {:keys [port req-handler]} _]
                 {:state       :running
                  :port        (port)
                  :req-handler req-handler})

               :init-after
               (fn [{:keys [port]} _ _]
                 (prn "started http server on port "
                      port))

               :init-around
               (fn [_ _ {:keys [->info]}]
                 (->info {:time 1}))

               :halt
               (fn [_ _ _]
                 {})}}})

(def relative-ref-system
  {:defs {:env      {:http-port {:handlers {:init (fn [& _]
                                                    (let [port-num (atom 9090)]
                                                      #(swap! port-num inc)))}}}
          :server-1 (assoc-in server-component-group
                     [:req-handler :handlers :init]
                     (constantly (fn req-handler-1 [req] req)))
          :server-2 (assoc-in server-component-group
                     [:req-handler :handlers :init]
                     (constantly (fn req-handler-2 [req] req)))}})

(def relative-ref-system
  {:defs {:env      {:http-port {:handlers {:init (fn [& _]
                                                    (let [port-num (atom 9090)]
                                                      #(swap! port-num inc)))}}}
          :server-1 [server-component-group
                     {:req-handler {:handlers {:init (constantly (fn req-handler-1 [req] req))}}}]

          :server-2 [server-component-group
                     {:req-handler {:handlers {:init (constantly (fn req-handler-2 [req] req))}}}]}})
