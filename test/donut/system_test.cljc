(ns donut.system-test
  (:require [donut.system :as ds]
            [loom.graph :as lg]
            #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing] :include-macros true])
            [malli.core :as m]
            [loom.alg :as la])
  (:import [clojure.lang ExceptionInfo]))

(deftest merge-def-test
  (is (= {::ds/constant :foo}
         (ds/merge-def {} :foo)))

  (testing "coercion to map with ::ds/constant key"
    (is (= {::ds/constant :foo}
           (ds/merge-def :foo {})))))

(deftest merge-base-test
  (is (= #::ds{:base {:before-start [:foo]}
               :defs {:app {:http-server {:before-start [:foo]
                                          :after-start  [:bar]}}}}
         (#'ds/merge-base #::ds{:base {:before-start [:foo]}
                                :defs {:app {:http-server {:after-start [:bar]}}}})))

  (is (= #::ds{:base :foo
               :defs {:app {:http-server {:after-start  [:bar]
                                          ::ds/constant :foo}}}}
         (#'ds/merge-base #::ds{:base :foo
                                :defs {:app {:http-server {:after-start [:bar]}}}}))))

(deftest expand-refs-for-graph-test
  (is (= #::ds{:defs {:env {:http-port {:x (ds/ref [:env :bar])}}
                      :app {:http-server {:port (ds/ref [:env :http-port])}}}}
         (#'ds/expand-refs-for-graph
          #::ds{:defs {:env {:http-port {:x (ds/ref :bar)}}
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
                                      :app {:http-server {:deps {:port (ds/ref [:env :http-port])}}}}}
                         :topsort))))

(deftest gen-graphs-test
  (let [system (ds/gen-graphs #::ds{:defs {:env {:port-source nil
                                                 :http-port   (ds/ref :port-source)}
                                           :app {:http-server {:port (ds/ref [:env :http-port])}}}})]
    (is (= [[:app :http-server]
            [:env :http-port]
            [:env :port-source]]
           (la/topsort (get-in system [::ds/graphs :topsort]))))
    (is (= [[:env :port-source]
            [:env :http-port]
            [:app :http-server]]
           (la/topsort (get-in system [::ds/graphs :reverse-topsort]))))))

(deftest simple-signal-test
  (is (= #::ds{:instances {:app {:boop "boop"}}}
         (-> #::ds{:defs {:app {:boop {:start "boop"}}}}
             (ds/signal :start)
             (select-keys [::ds/instances]))))

  (is (= #::ds{:instances {:app {:boop "boop and boop again"}}}
         (-> #::ds{:defs {:app {:boop {:start "boop"
                                       :stop (fn [_ instance _]
                                               (str instance " and boop again"))}}}}
             (ds/signal :start)
             (ds/signal :stop)
             (select-keys [::ds/instances])))))

(deftest ref-test
  (testing "referred port number is passed to referrer"
    (is (= #::ds{:instances {:env {:http-port 9090}
                             :app {:http-server 9090}}}
           (-> #::ds{:defs {:env {:http-port {:start 9090}}
                            :app {:http-server {:start (fn [{:keys [port]} _ _]
                                                         port)
                                                :conf  {:port (ds/ref [:env :http-port])}}}}}
               (ds/signal :start)
               (select-keys [::ds/instances]))))))

(deftest local-ref-test
  (testing "ref of keyword resolves to component in same group"
    (is (= #::ds{:instances {:app {:http-server 9090
                                   :http-port   9090}}}
           (-> #::ds{:defs {:app {:http-server {:start (fn [{:keys [port]} _ _]
                                                        port)
                                                :conf  {:port (ds/ref :http-port)}}
                                  :http-port   9090}}}
               (ds/signal :start)
               (select-keys [::ds/instances]))))))

(deftest group-ref-test
  (testing "referred group is passed to referrer"
    (is (= #::ds{:instances {:env {:http-port 9090
                                   :timeout   5000}
                             :app {:http-server {:http-port 9090
                                                 :timeout   5000}}}}
           (-> #::ds{:defs {:env {:http-port {:start 9090}
                                  :timeout   {:start 5000}}
                            :app {:http-server {:start (fn [{:keys [env]} _ _]
                                                         env)
                                                :conf  {:env (ds/group-ref :env)}}}}}
               (ds/signal :start)
               (select-keys [::ds/instances]))))))


(deftest signal-constant-test
  (testing "can forego a map for component def if value should be a constant"
    (is (= #::ds{:instances {:env {:http-port 9090}
                             :app {:http-server 9090}}}
           (-> #::ds{:defs {:env {:http-port 9090}
                            :app {:http-server {:start (fn [{:keys [port]} _ _]
                                                         port)
                                                :conf  {:port (ds/ref [:env :http-port])}}}}}
               (ds/signal :start)
               (select-keys [::ds/instances]))))))

(deftest system-merge-test
  (let [handlers {:start (fn [{:keys [port]} _ _]
                           port)}]
    (is (= #::ds{:defs {:env {:http-port {:start 9090}}
                        :app {:http-server [{:port (ds/ref [:env :http-port])}
                                            handlers]}}}
           (-> (ds/system-merge
                #::ds{:defs {:app {:http-server [{:port (ds/ref [:env :http-port])}
                                                 handlers]}}}
                #::ds{:defs {:env {:http-port {:start 9090}}}})
               (select-keys [::ds/defs]))))))

(deftest validate-component
  (let [schema (m/schema int?)]
    (is (= #::ds{:out {:validation {:env {:http-port {:schema schema
                                                      :value  "9090"
                                                      :errors [{:path   []
                                                                :in     []
                                                                :schema schema
                                                                :value  "9090"}]}}}}}
           (-> #::ds{:base {:after-start ds/validate-with-malli}

                     :defs {:env {:http-port {:start  "9090"
                                              :schema schema}}
                            :app {:http-server {:start (fn [{:keys [port]} _ _]
                                                         port)
                                                :conf  {:port (ds/ref [:env :http-port])}}}}}
               (ds/signal :start)
               (select-keys [::ds/out]))))))

(deftest lifecycle-values-ignored-when-not-system
  (let [expected #::ds{:instances {:env {:http-port 9090}
                                   :app {:http-server 9090}}}
        system   #::ds{:defs {:env {:http-port {:start 9090}}
                              :app {:http-server {:start (fn [{:keys [port]} _ _]
                                                           port)
                                                  :conf  {:port (ds/ref [:env :http-port])}}}}}]
    (is (= expected
           (->  system
                (ds/system-merge #::ds{:defs {:app {:http-server {:before-start (constantly nil)}}}})
                (ds/signal :start)
                (select-keys [::ds/instances]))))
    (is (= expected
           (->  system
                (ds/system-merge #::ds{:defs {:app {:http-server {:after-start (constantly nil)}}}})
                (ds/signal :start)
                (select-keys [::ds/instances]))))))

(deftest gen-signal-computation-graph-test
  (let [system (ds/gen-graphs #::ds{:defs {:env {:http-port 9090}
                                           :app {:http-server {:port (ds/ref [:env :http-port])}}}})]
    (is (= (->> [[:env :http-port :before-start]
                 [:env :http-port :start]
                 [:env :http-port :after-start]
                 [:app :http-server :before-start]
                 [:app :http-server :start]
                 [:app :http-server :after-start]]
                (partition 2 1)
                (apply lg/add-edges (lg/digraph)))
           (ds/gen-signal-computation-graph system :start :reverse-topsort)))))


(deftest channel-fns-test
  (testing "can chain channel fns"
    (is (= #::ds{:instances {:app {:http-server 9090
                                   :http-port   9090}}
                 :out       {:info {:app {:http-server "info"}}}}
           (-> #::ds{:defs {:app {:http-server {:start (fn [{:keys [port]} _ {:keys [->instance ->info]}]
                                                         (-> (->instance port)
                                                             (->info "info")))
                                                :conf  {:port (ds/ref :http-port)}}
                                  :http-port   9090}}}
               (ds/signal :start)
               (select-keys [::ds/instances ::ds/out]))))))

(deftest subsystem-test
  (let [subsystem #::ds{:defs
                        {:local {:port 9090}

                         :app
                         {:server {:start       (fn [resolved _ _]
                                                  (select-keys resolved [:job-queue :db :port]))
                                   :after-start (fn [_ _ {:keys [->info]}]
                                                  (->info "started"))
                                   :stop        (fn [_ instance _]
                                                  {:prev instance
                                                   :now  :stopped})
                                   :after-stop  (fn [_ _ {:keys [->info]}]
                                                  (->info "stopped"))
                                   :conf        {:job-queue (ds/ref [:common-services :job-queue])
                                                 :db        (ds/ref [:common-services :db])
                                                 :port      (ds/ref [:local :port])}}}}}

        started (-> #::ds{:defs
                          {:env
                           {:app-name "foo.app"}

                           :common-services
                           {:job-queue "job queue"
                            :db        "db"}

                           :sub-systems
                           {:system-1 (ds/subsystem-component
                                       subsystem
                                       #{(ds/ref [:common-services :job-queue])
                                         (ds/ref [:common-services :db])})
                            :system-2 (ds/subsystem-component
                                       subsystem
                                       #{(ds/group-ref :common-services)})}}}
                    (ds/signal :start))]

    (is (= {:job-queue "job queue"
            :db        "db"
            :port      9090}
           (get-in started [::ds/instances :sub-systems :system-1 ::ds/instances :app :server])
           (get-in started [::ds/instances :sub-systems :system-2 ::ds/instances :app :server])))
    (is (= "started"
           (get-in started [::ds/out :info :sub-systems :system-1 :app :server])
           (get-in started [::ds/out :info :sub-systems :system-2 :app :server])))

    (let [stopped (ds/signal started :stop)]
      (is (= {:prev {:job-queue "job queue"
                     :db        "db"
                     :port      9090}
              :now  :stopped}
             (get-in stopped [::ds/instances :sub-systems :system-1 ::ds/instances :app :server])
             (get-in stopped [::ds/instances :sub-systems :system-2 ::ds/instances :app :server])))

      (is (= "stopped"
             (get-in stopped [::ds/out :info :sub-systems :system-1 :app :server])
             (get-in stopped [::ds/out :info :sub-systems :system-2 :app :server]))))))

(deftest select-components-test
  (testing "if you specify components, the union of their subgraphs is used"
    (let [system-def {::ds/defs {:env {:http-port {:start 9090}}
                                 :app {:http-server {:start (fn [{:keys [port]} _ _]
                                                              port)
                                                     :stop  "stopped http-server"
                                                     :conf  {:port (ds/ref [:env :http-port])}}
                                       :db          {:start "db"
                                                     :stop  "stopped db"}}}}
          started    (ds/signal system-def :start #{[:app :http-server]})]
      (is (= {::ds/instances {:app {:http-server 9090}
                              :env {:http-port 9090}}}
             (select-keys started [::ds/instances])))

      (testing "the selected components are retained beyond the start"
        (is (= {::ds/instances {:app {:http-server "stopped http-server"}
                                :env {:http-port 9090}}}
               (-> started
                   (ds/signal :stop)
                   (select-keys [::ds/instances])))))

      (testing "groups you can select groups"
        (is (= {::ds/instances {:env {:http-port 9090}}}
               (-> (ds/signal system-def :start #{:env})
                   (select-keys [::ds/instances]))))))))

(deftest ref-undefined-test
  (is (thrown-with-msg?
       ExceptionInfo
       #"Invalid ref"
       (ds/signal {::ds/defs {:group {:component {:ref (ds/ref [:nonexistent :ref])}}}}
                  :start)))

  (is (thrown-with-msg?
       ExceptionInfo
       #"Invalid group ref"
       (ds/signal {::ds/defs {:group {:component {:ref (ds/group-ref :nonexistent)}}}}
                  :start))))

(deftest signal-arity-exception-test
  (is (thrown?
       ExceptionInfo
       (ds/signal {::ds/defs {:group {:component {:start (fn [])}}}}
                  :start))))

(deftest signal-helper-test
  (is (= {::ds/instances {:app {:boop "boop"}}}
         (-> {::ds/defs {:app {:boop {:start "boop"}}}}
             (ds/start)
             (select-keys [::ds/instances]))))

  (is (= {::ds/instances {:app {:boop "boop and boop again"}}}
         (-> {::ds/defs {:app {:boop {:start "boop"
                                      :stop (fn [_ instance _]
                                              (str instance " and boop again"))}}}}
             (ds/start)
             (ds/stop)
             (select-keys [::ds/instances])))))

(deftest recognized-signals-exception-test
  (is (thrown-with-msg?
       ExceptionInfo
       #"Signal :foo not recognized"
       (ds/signal {::ds/defs {:group {:component "constant"}}}
                  :foo)))

  (testing "should not throw exception"
    (is (ds/signal {::ds/defs {:group {:component "constant"}}
                    ::ds/signals (merge ds/default-signals {:foo {:order :topsort}})}
                   :foo))))
