(ns donut.system-test
  (:require [donut.system :as ds]
            [loom.graph :as lg]
            #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer [deftest is] :include-macros true])))

{:base      {:boot {}
             :app  {:lifecycle {:init-before []}}
             :env  {}}
 :configs   {}
 :instances {}
 :signal    nil}

(deftest apply-base-test
  (is (= {:base    {:app {:lifecycle {:init-before [:foo]}}}
          :configs {:app {:http-server {:lifecycle {:init-before [:foo]
                                                    :init-after  [:bar]}}}}}
         (#'ds/apply-base {:base    {:app {:lifecycle {:init-before [:foo]}}}
                           :configs {:app {:http-server {:lifecycle {:init-after [:bar]}}}}}))))

(deftest ref-edges-test
  (is (= [[[:env :http-port] [:env :bar]]
          [[:app :http-server] [:env :http-port]]]
         (#'ds/ref-edges {:configs {:env {:http-port {:config {:x (ds/ref :bar)}}}
                                    :app {:http-server {:config {:port (ds/ref [:env :http-port])}}}}}))))

(deftest gen-graph-test
  (let [system {:configs {:env {:http-port nil}
                          :app {:http-server nil}}}]
    (is (= (assoc system :graph (-> (lg/digraph)
                                    (lg/add-nodes [:env :http-port]
                                                  [:app :http-server])))
           (ds/gen-graph system))))

  (let [system {:configs {:env {:port-source nil
                                :http-port (ds/ref :port-source)}
                          :app {:http-server {:port (ds/ref [:env :http-port])}}}}]
    (is (= (assoc system :graph (-> (lg/digraph)
                                    (lg/add-nodes [:env :http-port]
                                                  [:env :port-source]
                                                  [:app :http-server])
                                    (lg/add-edges [[:env :http-port] [:env :port-source]]
                                                  [[:app :http-server] [:env :http-port]])))
           (ds/gen-graph system)))))

(def test-component-config
  {:env {:http-port {:value 9090}}
   :app {:server {:constructor identity
                  :config      {:port [:env :http-port]}
                  :before      [(fn [])]
                  :after       [(fn [])]}}})

#_(deftest transition-test
    (is (= {:state :running
            :env   {:http-port 9090}
            :app   {:server nil}}
           (ds/transition {}
                          :running
                          ds/transition-config
                          ds/default-build-config
                          test-component-config))))
