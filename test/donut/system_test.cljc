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

(deftest signal-test
  (is (= {:instances {:app {:boop "boop"}}}
         (-> {:configs {:app {:boop {:lifecycle {:init "boop"}}}}}
             (ds/signal :init)
             (select-keys [:instances]))))

  (is (= {:instances {:app {:boop "boop and boop again"}}}
         (-> {:configs {:app {:boop {:lifecycle {:init "boop"
                                                 :halt (fn [c _ _]
                                                         (str c " and boop again"))}}}}}
             (ds/signal :init)
             (ds/signal :halt)
             (select-keys [:instances])))))
