(ns donut.system.repl
  "reloaded repl tools"
  (:require [clojure.tools.namespace.repl :as repl]
            [donut.system :as ds]
            [donut.system.repl.state :as state]))

(defn signal
  [signal-name]
  (when (not= signal-name (::ds/last-signal state/system))
    (alter-var-root #'state/system (fn [sys] (some-> sys (ds/signal signal-name)))))
  signal-name)

(defn start
  [& args]
  (alter-var-root #'state/system (fn [sys]
                                   (when (and (ds/system? sys)
                                              (not= ::ds/stop (::ds/last-signal sys)))
                                     (ds/stop sys))
                                   (apply ds/start :donut.system/repl args)))
  ::ds/start)

(defn stop
  []
  (signal ::ds/stop))

(defn restart
  []
  (stop)
  (repl/refresh :after 'donut.system.repl/start))

(defn clear!
  []
  (alter-var-root #'state/system (constantly nil)))
