(ns donut.system-test
  (:require [donut.system :as ds]
            [loom.graph :as lg]
            #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing] :include-macros true])))

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


(deftest expnd-refs-test
  (is (= {:configs {:env {:http-port {:config {:x (ds/ref [:env :bar])}}}
                    :app {:http-server {:config {:port (ds/ref [:env :http-port])}}}}}
         (#'ds/expand-refs {:configs {:env {:http-port {:config {:x (ds/ref :bar)}}}
                                      :app {:http-server {:config {:port (ds/ref [:env :http-port])}}}}}))))

(deftest resolve-refs-test
  (is (= {:configs   {:app {:http-server {:config {:port 9090}}}},
          :instances {:env {:http-port 9090}}}
         (#'ds/resolve-refs {:configs   {:app {:http-server {:config {:port (ds/ref [:env :http-port])}}}}
                             :instances {:env {:http-port 9090}}}
                            [:app :http-server]))))

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

(deftest simple-signal-test
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

(deftest ref-test
  (testing "referred port number is passed to referrer"
    (is (= {:instances {:env {:http-port 9090}
                        :app {:http-server 9090}}}
           (-> {:configs {:env {:http-port {:lifecycle {:init 9090}}}
                          :app {:http-server {:config    {:port (ds/ref [:env :http-port])}
                                              :lifecycle {:init (fn [_ {:keys [port]} _]
                                                                  port)}}}}}
               (ds/signal :init)
               (select-keys [:instances]))))))
