(ns donut.system.parallel-test
  (:require
   [clojure.test :refer [deftest is]]
   [donut.system :as ds])
  (:import [java.util.concurrent Executors]))

(deftest parallel-start-test
  (let [a (promise)
        b (promise)
        ; Provide enough threads to allow the signal to send all 6 signals at
        ; once, to make sure that it won't.
        executor (Executors/newFixedThreadPool 6)]
    (is (= {:app {:beep "boop"
                  :boop "beep"}}
           ; Create a system that can't start unless run concurrently
           (-> #::ds{:defs {:app {:beep {::ds/start (fn [_] (deliver b "beep") @a)}
                                  :boop {::ds/start (fn [_] (deliver a "boop") @b)}}}
                     :execute (ds/execute-fn executor)}
               (ds/signal ::ds/start)
               future (deref 1000 {::ds/instances :timeout})
               ::ds/instances))
        "System can be started by sending signals in parallel")
    (.shutdown executor)))
