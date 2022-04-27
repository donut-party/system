(ns tutorial.01
  "Basic component definition and signaling"
  (:require
   [donut.system :as ds]
   [ring.adapter.jetty :as rj]))

;; The purpose of donut.system is to give you a structure for defining
;; components and their behavior, and for triggering that behavior. In this
;; example we look at how to define the behavior for an HTTP server component:
;; when the system starts, the HTTP server component should start a new server
;; that listens on port 9000 and returns the response "hello!" for every
;; request.

;; You start with a system map_ that contains _component definitions_. These are
;; placed under the `::ds/defs` key.
;;
;; `::ds/defs` should be map of _component group names_ to _component groups_.
;; In the example below, `:http` is a component group name, and the component
;; group is a map.
;;
;; Within a component group, the keys are _component names_ and the values are
;; _component definitions_. `:server` is a component name, and its value is map
;; that contains _signal handlers_ and a `:conf`.
;;
;; Signal handlers are what we use to define component behavior. In the example
;; below, the `:start` signal handler calls `rj/run-jetty` to start a jetty
;; server.
;;
;; Signal handlers are called when you call the `ds/signal` function on a system
;; map. `(ds/signal system :start)` will call the `:start` signal handler for
;; every component that has one.
;;
;; When a signal handler gets called, the first argument it receives is the
;; value of `:conf` from the component definition. Passing in `:conf` like this
;; allows us to customize component behavior for different environments. For
;; example, you might want to run integration tests from your REPL while a
;; development server is running. You could do this by running two jetty servers
;; on two different ports.
;;
;; When you call `ds/signal`, it returns an updated system map. This updated
;; system map contains _component instances_. When a component's signal handler
;; gets called, its return value becomes the component instance.
;;
;; In this case, the `:start` signal handler returns a
;; `org.eclipse.jetty.server.Server` object.
;;
;; We use these instances on subsequent calls to `ds/signal`. When we call
;; `(ds/signal running-system :stop)`, then each components `:stop` signal
;; handler will get called. The component instance gets passed in as the second
;; argument to the signal handler. In this example, we use that to stop the
;; server.

(def system
  {::ds/defs
   {:http
    {:server {:start (fn [{:keys [port]} _ _]
                       (rj/run-jetty
                        (fn handler [_] {:status 200
                                         :body   "hello!"})
                        {:port  port
                         :join? false}))
              :stop  (fn [_ instance _]
                       (.stop instance))
              :conf  {:port 9000}}}}})

(comment
  (def running-system (ds/signal system :start))
  (ds/signal running-system :stop)
  )
