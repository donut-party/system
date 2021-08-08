(ns donut.system-test
  (:require [donut.system :as ds]
            #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer [deftest is] :include-macros true])))


{:transition-config {}
 :build-config      {}
 :component-config  {}
 :instances         {}}

(def test-component-config
  {:env {:http-port {:value 9090}}
   :app {:server {:constructor identity
                  :config      {:port [:env :http-port]}
                  :before      [(fn [])]
                  :after       [(fn [])]}}})

(deftest transition-test
  (is (= {:state :running
          :env   {:http-port 9090}
          :app   {:server nil}}
         (ds/transition {}
                        :running
                        ds/transition-config
                        ds/default-build-config
                        test-component-config))))
