(ns donut.system.repl-test
  (:require
   [donut.system :as ds]
   [donut.system.repl :as sut]
   [clojure.test :refer [deftest is]]))

(defn system-throws-exception-during-start
  [messages]
  #::ds{:defs
        {:group
         {:starts-successfully
          #::ds{:start (fn [_] (swap! messages conj :start))
                :stop (fn [_] (swap! messages conj :stop))}

          :throws-exception
          #::ds{:start (fn [_] (throw (ex-info "expected exception" {})))
                :config {:for-start-order [::ds/local-ref [:starts-successfully]]}}}}})

(deftest test-stop-system-on-exception
  (let [messages (atom [])]
    (try (sut/start (system-throws-exception-during-start messages))
         (catch clojure.lang.ExceptionInfo _e
           (is (= [:start :stop]
                  @messages))))))
