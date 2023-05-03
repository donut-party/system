(ns donut.system.repl.state
  (:require [clojure.tools.namespace.repl :as repl]))

(repl/disable-reload!)

(def system
  "keep a reference to a running system"
  nil)

(def system-args
  "used to restart a system"
  nil)
