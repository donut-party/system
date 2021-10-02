(ns donut.system.repl.state
  (:require [clojure.tools.namespace.repl :as repl]))

(repl/disable-reload!)

(def system nil)
