(ns dev
  {:clj-kondo/config {:linters {:unused-namespace {:level :off}}}}
  (:require
   [clojure.tools.namespace.repl :as nsrepl]
   [dev.repl :as dev-repl]
   [donut.system :as ds]
   [donut.system.repl :as dsr]
   [donut.system.repl.state :as dsrs]
   [fluree.http-api.system :as sys])
  (:refer-clojure :exclude [test]))

(nsrepl/set-refresh-dirs "dev/src" "src" "test")

(defn routes
  []
  (get-in dsrs/system [::ds/defs :env :http/routes]))

(def start dsr/start)
(def stop dsr/stop)
(def restart dsr/restart)

(defmethod ds/named-system :donut.system/repl
  [_]
  (ds/system :dev))

(when-not dsrs/system
  (dsr/start))
