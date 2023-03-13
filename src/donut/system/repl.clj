(ns donut.system.repl
  "reloaded repl tools"
  (:require
   [clojure.pprint :as pprint]
   [clojure.tools.namespace.repl :as repl]
   [donut.system :as ds]
   [donut.system.repl.state :as state])
  (:import
   [clojure.lang ExceptionInfo]))

(defn signal
  [signal-name]
  (when (not= signal-name (::ds/last-signal state/system))
    (alter-var-root #'state/system (fn [sys] (some-> sys (ds/signal signal-name)))))
  signal-name)

(defn start
  [& [maybe-system & args]]
  (try
    (let [[system args] (if (or (keyword? maybe-system)
                                (ds/system? maybe-system))
                          [maybe-system args]
                          [:donut.system/repl (into [maybe-system] args)])]
      (alter-var-root #'state/system (fn [sys]
                                       (when (and (ds/system? sys)
                                                  (not= ::ds/stop (::ds/last-signal sys)))
                                         (ds/stop sys))
                                       (apply ds/start system args))))
    ::ds/start
    (catch ExceptionInfo e
      (ds/stop-failed-system)
      (throw e))))

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

(defn instance
  "Retrieve instance from started system"
  ([]
   (ds/instance state/system))
  ([component-path]
   (if-let [component-instance (ds/instance state/system component-path)]
     component-instance
     (do (prn "Could not find instance at " component-path)
         (pprint/pprint (instance))))))
