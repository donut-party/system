(ns donut.cljs-test-runner
  (:require [doo.runner :refer-macros [doo-tests]]
            [donut.system-test]))

(doo-tests 'donut.system-test)
