(ns tutorial.02
  "Local refs"
  (:require
   [donut.system :as ds]
   [ring.adapter.jetty :as rj]))

;; _refs_ allow one component's signal handlers to receive another component
;; instance as an argument. This is a form of dependency injection, and it's how
;; you let the components in a system use each other.
;;
;; For example, let's say you want your http server component to receive its
;; `handler` as an argument. The example below shows how you would do that.
;;
;; The `:conf` for `[:http :server]` contains `{:handler (ds/ref :handler)}`.
;; (ds/ref :component-name) returns a _local ref_, a value that represents a
;; reference to a component in the same group. `:server` and `:req-handler` are
;; in the same group.
;;
;; When `:server`'s `:start` signal is called, it will receive the _instance_
;; for `:req-handler` in its first argument as part of its conf.
;;
;; donut.system handles starting components in the correct order: if Component A
;; refers to Component B, then Component B is started before Component A. This
;; ordering is reversed for the `:stop` signal.
;;
;; There are many reasons why you might want to decompose your components like
;; this. In this example, one reason to break out the request handler is so that
;; you can retrieve it in tests.


(def system
  {::ds/defs
   {:http
    {:server {:start (fn [{:keys [port req-handler]} _ _]
                       (rj/run-jetty req-handler {:port  port
                                                  :join? false}))
              :stop  (fn [_ instance _]
                       (.stop instance))
              :conf  {:port        9000
                      :req-handler (ds/ref :req-handler)}}

     :req-handler {:start (fn [_ _ _]
                            (fn handler [_] {:status 200
                                             :body   "hello!"}))}}}})

(comment
  (def running-system (ds/signal system :start))
  (ds/signal running-system :stop)
  )
