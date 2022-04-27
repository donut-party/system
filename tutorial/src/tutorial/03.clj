(ns tutorial.03
  "Global refs, constant components"
  (:require
   [donut.system :as ds]
   [ring.adapter.jetty :as rj]))

;; You can also refer to components in different groups using the syntax
;; `(ds/ref [:group-name :component-name])`. We do this in the example below
;; with (ds/ref [:env :http-port]).
;;
;; Notice that the `:http-port` "component" is just the constant 9000. If you
;; provide a non-map value for a component definition, then that's treated as a
;; "constant component" - it doesn't have any behavior, it's just used as an
;; instance to get passed to other components.

(def system
  {::ds/defs
   {:env
    {:http-port 9000}

    :http
    {:server {:start (fn [{:keys [port req-handler]} _ _]
                       (rj/run-jetty req-handler {:port  port
                                                  :join? false}))
              :stop  (fn [_ instance _]
                       (.stop instance))
              :conf  {:port        (ds/ref [:env :http-port])
                      :req-handler (ds/ref :req-handler)}}

     :req-handler {:start (fn [_ _ _]
                            (fn handler [_] {:status 200
                                             :body   "hello!"}))}}}})

(comment
  (def running-system (ds/signal system :start))
  (ds/signal running-system :stop)
  )
