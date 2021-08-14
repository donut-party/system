(ns donut.system-test
  (:require [donut.system :as ds]
            [loom.graph :as lg]
            #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing] :include-macros true])))

(deftest apply-base-test
  (is (= #::ds{:base {:app {:init-before [:foo]}}
               :defs {:app {:http-server {:init-before [:foo]
                                          :init-after  [:bar]}}}}
         (#'ds/apply-base #::ds{:base {:app {:init-before [:foo]}}
                                :defs {:app {:http-server {:init-after [:bar]}}}}))))


(deftest expand-refs-test
  (is (= #::ds{:defs {:env {:http-port {:x (ds/ref [:env :bar])}}
                      :app {:http-server {:port (ds/ref [:env :http-port])}}}}
         (#'ds/expand-refs #::ds{:defs {:env {:http-port {:x (ds/ref :bar)}}
                                        :app {:http-server {:port (ds/ref [:env :http-port])}}}}))))

(deftest resolve-refs-test
  (is (= #::ds{:defs      {:app {:http-server {:port (ds/ref [:env :http-port])}}}
               :resolved  {:app {:http-server {:port 9090}}},
               :instances {:env {:http-port 9090}}}
         (#'ds/resolve-refs #::ds{:defs      {:app {:http-server {:port (ds/ref [:env :http-port])}}}
                                  :instances {:env {:http-port 9090}}}
                            [:app :http-server]))))

(deftest ref-edges-test
  (is (= [[[:env :http-port] [:env :bar]]
          [[:app :http-server] [:env :http-port]]]
         (#'ds/ref-edges #::ds{:defs {:env {:http-port {:deps {:x (ds/ref :bar)}}}
                                      :app {:http-server {:deps {:port (ds/ref [:env :http-port])}}}}}))))

(deftest gen-graph-test
  (let [system #::ds{:defs {:env {:http-port nil}
                            :app {:http-server nil}}}]
    (is (= (assoc system ::ds/graph (-> (lg/digraph)
                                        (lg/add-nodes [:env :http-port]
                                                      [:app :http-server])))
           (ds/gen-graph system))))

  (let [system #::ds{:defs {:env {:port-source nil
                                  :http-port   (ds/ref :port-source)}
                            :app {:http-server {:port (ds/ref [:env :http-port])}}}}]
    (is (= (assoc system ::ds/graph (-> (lg/digraph)
                                        (lg/add-nodes [:env :http-port]
                                                      [:env :port-source]
                                                      [:app :http-server])
                                        (lg/add-edges [[:env :http-port] [:env :port-source]]
                                                      [[:app :http-server] [:env :http-port]])))
           (ds/gen-graph system)))))

(deftest simple-signal-test
  (is (= #::ds{:instances {:app {:boop "boop"}}}
         (-> #::ds{:defs {:app {:boop {:init "boop"}}}}
             (ds/signal :init)
             (select-keys [::ds/instances]))))

  (is (= #::ds{:instances {:app {:boop "boop and boop again"}}}
         (-> #::ds{:defs {:app {:boop {:init "boop"
                                       :halt (fn [c _ _]
                                               (str c " and boop again"))}}}}
             (ds/signal :init)
             (ds/signal :halt)
             (select-keys [::ds/instances])))))

(deftest ref-test
  (testing "referred port number is passed to referrer"
    (is (= #::ds{:instances {:env {:http-port 9090}
                             :app {:http-server 9090}}}
           (-> #::ds{:defs {:env {:http-port {:init 9090}}
                            :app {:http-server {:port (ds/ref [:env :http-port])
                                                :init (fn [_ {:keys [port] :as res} _]
                                                        port)}}}}
               (ds/signal :init)
               (select-keys [::ds/instances]))))))


{:defs {:env {:http-port {:handlers {:init 9090}}}
        :app {:http-server {:deps     {:port (ds/ref [:env :http-port])}
                            :handlers {:init (fn [_ {:keys [port]} _]
                                               port)}}}}}
