(ns donut.system-test
  (:require [donut.system :as ds]
            [loom.graph :as lg]
            #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing] :include-macros true])))

{:base      {:boot {}
             :app  {:handlers {:init-before []}}
             :env  {}}
 :defs      {}
 :instances {}
 :signal    nil}

(deftest apply-base-test
  (is (= {:base {:app {:handlers {:init-before [:foo]}}}
          :defs {:app {:http-server {:handlers {:init-before [:foo]
                                                :init-after  [:bar]}}}}}
         (#'ds/apply-base {:base {:app {:handlers {:init-before [:foo]}}}
                           :defs {:app {:http-server {:handlers {:init-after [:bar]}}}}}))))


(deftest expnd-refs-test
  (is (= {:defs {:env {:http-port {:deps {:x (ds/ref [:env :bar])}}}
                 :app {:http-server {:deps {:port (ds/ref [:env :http-port])}}}}}
         (#'ds/expand-refs {:defs {:env {:http-port {:deps {:x (ds/ref :bar)}}}
                                   :app {:http-server {:deps {:port (ds/ref [:env :http-port])}}}}}))))

(deftest resolve-refs-test
  (is (= {:defs {:app {:http-server {:deps {:port 9090}}}},
          :instances  {:env {:http-port 9090}}}
         (#'ds/resolve-refs {:defs {:app {:http-server {:deps {:port (ds/ref [:env :http-port])}}}}
                             :instances  {:env {:http-port 9090}}}
                            [:app :http-server]))))

(deftest ref-edges-test
  (is (= [[[:env :http-port] [:env :bar]]
          [[:app :http-server] [:env :http-port]]]
         (#'ds/ref-edges {:defs {:env {:http-port {:deps {:x (ds/ref :bar)}}}
                                 :app {:http-server {:deps {:port (ds/ref [:env :http-port])}}}}}))))

(deftest gen-graph-test
  (let [system {:defs {:env {:http-port nil}
                       :app {:http-server nil}}}]
    (is (= (assoc system :graph (-> (lg/digraph)
                                    (lg/add-nodes [:env :http-port]
                                                  [:app :http-server])))
           (ds/gen-graph system))))

  (let [system {:defs {:env {:port-source nil
                             :http-port   (ds/ref :port-source)}
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
         (-> {:defs {:app {:boop {:handlers {:init "boop"}}}}}
             (ds/signal :init)
             (select-keys [:instances]))))

  (is (= {:instances {:app {:boop "boop and boop again"}}}
         (-> {:defs {:app {:boop {:handlers {:init "boop"
                                             :halt (fn [c _ _]
                                                     (str c " and boop again"))}}}}}
             (ds/signal :init)
             (ds/signal :halt)
             (select-keys [:instances])))))

(deftest ref-test
  (testing "referred port number is passed to referrer"
    (is (= {:instances {:env {:http-port 9090}
                        :app {:http-server 9090}}}
           (-> {:defs {:env {:http-port {:handlers {:init 9090}}}
                       :app {:http-server {:deps     {:port (ds/ref [:env :http-port])}
                                           :handlers {:init (fn [_ {:keys [port]} _]
                                                              port)}}}}}
               (ds/signal :init)
               (select-keys [:instances]))))))
