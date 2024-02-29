(ns donut.system-test
  (:require
   #?(:clj [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer [deftest is testing] :include-macros true])
   [donut.system :as ds :include-macros true]
   [loom.alg :as la]
   [loom.graph :as lg]
   [clojure.string :as str]))

(defn config-port
  [opts]
  (get-in opts [::ds/config :port]))

(deftest configure-component-test
  (testing "sets config path vals"
    (is (= #::ds{:config {:foo :bar
                          :baz {:bux :bleh}}}
           (ds/configure-component
            {}
            {[:foo] :bar
             [:baz :bux] :bleh})))))

(deftest merge-base-test
  (testing "merges with components that have signals"
    (is (= #::ds{:base {::ds/pre-start [:foo]}
                 :defs {:app {:http-server {::ds/pre-start  [:foo]
                                            ::ds/start identity}}}}
           (#'ds/merge-base #::ds{:base {::ds/pre-start [:foo]}
                                  :defs {:app {:http-server {::ds/start identity}}}}))))

  (testing "does not merge with components that don't have signals"
    (is (= #::ds{:base {::ds/pre-start [:foo]}
                 :defs {:app {:http-server {:no-signal [:bar]}}}}
           (#'ds/merge-base #::ds{:base {::ds/pre-start [:foo]}
                                  :defs {:app {:http-server {:no-signal [:bar]}}}})))))

(deftest expand-refs-for-graph-test
  (is (= #::ds{:defs {:env {:http-port {:x (ds/ref [:env :bar])}}
                      :app {:http-server {:port (ds/ref [:env :http-port])}}}}
         (#'ds/expand-refs-for-graph
          #::ds{:defs {:env {:http-port {:x (ds/local-ref [:bar])}}
                       :app {:http-server {:port (ds/ref [:env :http-port])}}}}))))

(deftest resolve-refs-test
  (is (= #::ds{:defs          {:app {:http-server {:port (ds/ref [:env :http-port])}}
                               :env {:http-port 9090}}
               :resolved-defs {:app {:http-server {:port 9090}}},
               :instances     {:env {:http-port 9090}}}
         (#'ds/resolve-refs #::ds{:defs      {:app {:http-server {:port (ds/ref [:env :http-port])}}
                                              :env {:http-port 9090}}
                                  :instances {:env {:http-port 9090}}}
                            [:app :http-server])))
  (is (= #::ds{:defs          {:app {:http-server {:args (seq [(ds/ref [:env :http-port]) (ds/ref [:env :http-host])])}}
                               :env {:http-host "localhost" :http-port 9090}}
               :resolved-defs {:app {:http-server {:args [9090 "localhost"]}}},
               :instances     {:env {:http-host "localhost" :http-port 9090}}}
         (#'ds/resolve-refs #::ds{:defs      {:app {:http-server {:args (seq [(ds/ref [:env :http-port]) (ds/ref [:env :http-host])])}}
                                              :env {:http-host "localhost" :http-port 9090}}
                                  :instances {:env {:http-host "localhost" :http-port 9090}}}
                            [:app :http-server]))
      "Refs in seqs are resolved in the correct order"))

(deftest ref-edges-test
  (is (= [[[:env :http-port] [:env :bar]]
          [[:app :http-server] [:env :http-port]]]
         (#'ds/ref-edges #::ds{:defs {:env {:http-port {:deps {:x (ds/local-ref [:bar])}}}
                                      :app {:http-server {:deps {:port (ds/ref [:env :http-port])}}}}}
                         :topsort))))

(deftest gen-graphs-test
  (let [system (#'ds/gen-graphs #::ds{:defs {:env {:port-source nil
                                                   :http-port   (ds/local-ref [:port-source])}
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
         (-> #::ds{:defs {:app {:boop {::ds/start "boop"}}}}
             (ds/signal ::ds/start)
             (select-keys [::ds/instances]))))

  (is (= #::ds{:instances {:app {:boop "boop and boop again"}}}
         (-> #::ds{:defs {:app {:boop #::ds{:start "boop"
                                            :stop  (fn [{:keys [::ds/instance]}]
                                                     (str instance " and boop again"))}}}}
             (ds/signal ::ds/start)
             (ds/signal ::ds/stop)
             (select-keys [::ds/instances])))))

(deftest ref-test
  (testing "referred port number is passed to referrer"
    (is (= #::ds{:instances {:env {:http-port 9090}
                             :app {:http-server 9090}}}
           (-> #::ds{:defs {:env {:http-port #::ds{:start 9090}}
                            :app {:http-server #::ds{:start  config-port
                                                     :config {:port (ds/ref [:env :http-port])}}}}}
               (ds/signal ::ds/start)
               (select-keys [::ds/instances]))))))

(deftest deep-ref-test
  (testing "refs can be of arbitrary depth"
    (is (= #::ds{:instances {:env {:http {:port 9090}}
                             :app {:http-server 9090}}}
           (-> #::ds{:defs {:env {:http {:port 9090}}
                            :app {:http-server #::ds{:start  config-port
                                                     ;; [:env :http :port] reaches into the :http "component"
                                                     :config {:port (ds/ref [:env :http :port])}}}}}
               (ds/signal ::ds/start)
               (select-keys [::ds/instances])))))
  (testing "Refs can contain symbols and strings"
    (is (= #::ds{:instances {:env {:http {"host" "localhost" 'port 9090}}
                             :app {:http-server {:host "localhost" :port 9090}}}}
           (-> #::ds{:defs {:env {:http {"host" "localhost" 'port 9090}}
                            :app {:http-server #::ds{:start #(::ds/config %)
                                                       ;; [:env :http 'port] reaches into the :http "component"
                                                     :config {:host (ds/ref [:env :http "host"])
                                                              :port (ds/ref [:env :http 'port])}}}}}
               (ds/signal ::ds/start)
               (select-keys [::ds/instances])))))
  (testing "components with deep refs are started in the correct order"
    (let [vref-comp (fn [comp-name]
                      #::ds{:config {:ref (ds/ref [:group comp-name :v])}
                            :start  (fn [{{ref :ref} ::ds/config}]
                                      {:v ref})})]
      (is (= #::ds{:instances {:group {:c1 {:v :x} :c2 {:v :x} :c3 {:v :x} :c4 {:v :x}}}}
             (-> #::ds{:defs {:group {:c1 {:v :x}
                                      :c2 (vref-comp :c4)
                                      :c3 (vref-comp :c2)
                                      :c4 (vref-comp :c1)}}}
                 (ds/signal ::ds/start)
                 (select-keys [::ds/instances])))))))

(deftest local-ref-test
  (testing "ref of keyword resolves to component in same group"
    (is (= #::ds{:instances {:app {:http-server 9090
                                   :http-port   9090}}}
           (-> #::ds{:defs {:app {:http-server #::ds{:start  config-port
                                                     :config {:port (ds/local-ref [:http-port])}}
                                  :http-port   9090}}}
               (ds/signal ::ds/start)
               (select-keys [::ds/instances]))))))

(deftest group-ref-test
  (testing "referred group is passed to referrer"
    (is (= #::ds{:instances {:env {:http-port 9090
                                   :timeout   5000}
                             :app {:http-server {:http-port 9090
                                                 :timeout   5000}}}}
           (-> #::ds{:defs {:env {:http-port #::ds{:start 9090}
                                  :timeout   #::ds{:start 5000}}
                            :app {:http-server #::ds{:start  (fn [opts] (get-in opts [::ds/config :env]))
                                                     :config {:env (ds/ref [:env])}}}}}
               (ds/signal ::ds/start)
               (select-keys [::ds/instances]))))))


(deftest signal-constant-test
  (testing "can forego a map for component def if value should be a constant"
    (is (= #::ds{:instances {:env {:http-port 9090}
                             :app {:http-server 9090}}}
           (-> #::ds{:defs {:env {:http-port 9090}
                            :app {:http-server #::ds{:start  config-port
                                                     :config {:port (ds/ref [:env :http-port])}}}}}
               (ds/signal ::ds/start)
               (select-keys [::ds/instances]))))))

(deftest lifecycle-values-ignored-when-not-system
  (let [expected #::ds{:instances {:env {:http-port 9090}
                                   :app {:http-server 9090}}}
        system   #::ds{:defs {:env {:http-port #::ds{:start 9090}}
                              :app {:http-server #::ds{:start  config-port
                                                       :config {:port (ds/ref [:env :http-port])}}}}}]
    (is (= expected
           (->  system
                (assoc-in [::ds/defs :app :http-server ::ds/pre-start] (constantly nil))
                (ds/signal ::ds/start)
                (select-keys [::ds/instances]))))
    (is (= expected
           (->  system
                (assoc-in [::ds/defs :app :http-server ::ds/post-start] (constantly nil))
                (ds/signal ::ds/start)
                (select-keys [::ds/instances]))))))

(deftest gen-signal-computation-graph-test
  (let [system (#'ds/gen-graphs #::ds{:defs {:env {:http-port 9090}
                                             :app {:http-server {:port (ds/ref [:env :http-port])}}}})]
    (is (= (->> [[:env :http-port ::ds/pre-start]
                 [:env :http-port ::ds/start]
                 [:env :http-port ::ds/post-start]
                 [:app :http-server ::ds/pre-start]
                 [:app :http-server ::ds/start]
                 [:app :http-server ::ds/post-start]]
                (partition 2 1)
                (apply lg/add-edges (lg/digraph)))
           (#'ds/gen-signal-computation-graph system ::ds/start :reverse-topsort)))))

(deftest pre-and-post-test
  (testing "pre- and post- stages run in correct order when present"
    (let [store (atom [])]
      (ds/start {::ds/defs
                 {:group {:component {::ds/pre-start (fn [_] (swap! store conj :pre-start))
                                      ::ds/start (fn [_] (swap! store conj :start))
                                      ::ds/post-start (fn [_] (swap! store conj :post-start))}}}})
      (is (= [:pre-start :start :post-start] @store)))))

(def subsystem
  #::ds{:defs
        {:local {:port 9090}

         :app
         {:local  #::ds{:start (fn [_] :local)}
          :server #::ds{:start      (fn [{:keys [::ds/config]}] config)
                        :stop       (fn [{:keys [::ds/instance]}]
                                      {:prev instance
                                       :now  :stopped})
                        :config     {:job-queue (ds/ref [:common-services :job-queue])
                                     :db        (ds/ref [:common-services :db])
                                     :port      (ds/ref [:local :port])
                                     :local     (ds/local-ref [:local])}}}}})

(def system-with-subsystem
  #::ds{:defs
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
                     #{(ds/ref [:common-services])})}}})

(deftest subsystem-ref-edges
  (is (= [[[:sub-systems :system-1] [:common-services :job-queue]]
          [[:sub-systems :system-1] [:common-services :db]]
          [[:sub-systems :system-2] [:common-services :job-queue]]
          [[:sub-systems :system-2] [:common-services :db]]]
         (#'ds/ref-edges system-with-subsystem :topsort))))

(deftest subsystem-component-nodes-test
  (is (= (-> (lg/digraph)
             (lg/add-nodes [:env :app-name])
             (lg/add-edges [[:common-services :job-queue] [:sub-systems :system-1]]
                           [[:common-services :db] [:sub-systems :system-1]]
                           [[:common-services :job-queue] [:sub-systems :system-2]]
                           [[:common-services :db] [:sub-systems :system-2]]))
         (let [graph (#'ds/component-graph-nodes system-with-subsystem)]
           (#'ds/component-graph-add-edges graph system-with-subsystem :reverse-topsort)))))

(deftest subsystem-graph-test
  (is (= (-> (lg/digraph)
             (lg/add-nodes [:env :app-name])
             (lg/add-edges [[:common-services :job-queue] [:sub-systems :system-1]]
                           [[:common-services :db] [:sub-systems :system-1]]
                           [[:common-services :job-queue] [:sub-systems :system-2]]
                           [[:common-services :db] [:sub-systems :system-2]]))
         (-> system-with-subsystem
             (ds/init-system ::ds/start)
             ::ds/graphs
             :reverse-topsort))))

(deftest subsystem-test
  (let [start-count (atom 0)
        started     (ds/start system-with-subsystem
                              {[:common-services :job-queue] {::ds/start (fn [_]
                                                                           (swap! start-count inc)
                                                                           "job queue")}})]
    (is (= {:job-queue "job queue"
            :db        "db"
            :port      9090
            :local     :local}
           (get-in started [::ds/instances :sub-systems :system-1 ::ds/instances :app :server])
           (get-in started [::ds/instances :sub-systems :system-2 ::ds/instances :app :server])))

    (is (= 1 @start-count)
        "parent system signals only applied once")

    (let [stopped (ds/signal started ::ds/stop)]
      (is (= {:prev {:job-queue "job queue"
                     :db        "db"
                     :port      9090
                     :local     :local}
              :now  :stopped}
             (get-in stopped [::ds/instances :sub-systems :system-1 ::ds/instances :app :server])
             (get-in stopped [::ds/instances :sub-systems :system-2 ::ds/instances :app :server]))))))

(deftest select-components-test
  (testing "if you specify components, the union of their subgraphs is used"
    (let [system-def {::ds/defs {:env {:http-port #::ds{:start 9090}}
                                 :app {:http-server #::ds{:start  config-port
                                                          :stop   "stopped http-server"
                                                          :config {:port (ds/ref [:env :http-port])}}
                                       :db          #::ds{:start "db"
                                                          :stop  "stopped db"}}}}
          started    (ds/signal (ds/select-components system-def  #{[:app :http-server]})
                                ::ds/start)]
      (is (= {::ds/instances {:app {:http-server 9090}
                              :env {:http-port 9090}}}
             (select-keys started [::ds/instances])))

      (testing "the selected components are retained beyond the start"
        (is (= {::ds/instances {:app {:http-server "stopped http-server"}
                                :env {:http-port 9090}}}
               (-> started
                   (ds/signal ::ds/stop)
                   (select-keys [::ds/instances])))))

      (testing "groups you can select groups"
        (is (= {::ds/instances {:env {:http-port 9090}}}
               (-> (ds/signal (ds/select-components system-def #{:env})
                              ::ds/start)
                   (select-keys [::ds/instances]))))))))

(deftest ref-undefined-test
  (is (thrown-with-msg?
       #?(:clj clojure.lang.ExceptionInfo
          :cljs js/Object)
       #"Invalid ref"
       (ds/signal {::ds/defs {:group-a {:foo :foo}
                              :group-b {:component {:ref-1 (ds/ref [:group-a :foo])
                                                    :ref-2 (ds/ref [:group-a :nonexistent-component])}}}}
                  ::ds/start)))

  (is (thrown-with-msg?
       #?(:clj clojure.lang.ExceptionInfo
          :cljs js/Object)
       #"Invalid group ref"
       (ds/signal {::ds/defs {:group {:component {:ref (ds/ref [:nonexistent :ref])}}}}
                  ::ds/start)))

  (is (thrown-with-msg?
       #?(:clj clojure.lang.ExceptionInfo
          :cljs js/Object)
       #"Invalid group ref"
       (ds/signal {::ds/defs {:group {:component {:ref (ds/ref [:nonexistent])}}}}
                  ::ds/start))))

(deftest ref-cycle-test
  (is (thrown-with-msg?
       #?(:clj clojure.lang.ExceptionInfo
          :cljs js/Object)
       #"Cycle"
       (ds/signal {::ds/defs {:group-a {:foo (ds/ref [:group-b :component])}
                              :group-b {:component {:foo (ds/ref [:group-a :foo])}}}}
                  ::ds/start)))

  (is (thrown-with-msg?
       #?(:clj clojure.lang.ExceptionInfo
          :cljs js/Object)
       #"Cycle"
       (ds/signal {::ds/defs {:group-a {:foo (ds/ref [:group-b :component])
                                        :bar (ds/ref [:group-a :foo])}
                              :group-b {:component {:foo (ds/ref [:group-a :bar])}}}}
                  ::ds/start))))

(defmethod ds/named-system ::system-config-test
  [_]
  {::ds/defs {:group {:component-a 1
                      :component-b 2
                      :component-c 3}}})

(deftest assoc-many-test
  (is (= {:a {:b 1}
          :c {:d 2}}
         (ds/assoc-many {} {[:a :b] 1
                            [:c :d] 2})))

  (is (= {:foo {:a {:b 1}
                :c {:d 2}}}
         (ds/assoc-many {}
                        [:foo]
                        {[:a :b] 1
                         [:c :d] 2}))))

(deftest system-config-test
  (is (= {::ds/defs {:group {:component-a 1
                             :component-b 2
                             :component-c 4
                             :component-d 5}}}
         (ds/system ::system-config-test
           {[:group :component-c] 4
            [:group :component-d] 5}))))

(deftest signal-helper-test
  (testing "basic signal helpers work"
    (is (= {::ds/instances {:app {:boop "boop"}}}
           (-> {::ds/defs {:app {:boop #::ds{:start "boop"}}}}
               (ds/start)
               (select-keys [::ds/instances]))))

    (is (= {::ds/instances {:app {:boop "boop and boop again"}}}
           (-> {::ds/defs {:app {:boop #::ds{:start "boop"
                                             :stop  (fn [{:keys [::ds/instance]}]
                                                      (str instance " and boop again"))}}}}
               (ds/start)
               (ds/stop)
               (select-keys [::ds/instances]))))))

(deftest signal-helper-overrides-test
  (is (= {::ds/instances {:app {:boop "boop"}}}
         (-> {::ds/defs {:app {:boop #::ds{:start "no boop"}}}}
             (ds/start {[:app :boop ::ds/start] "boop"})
             (select-keys [::ds/instances])))))

(deftest recognized-signals-exception-test
  (is (thrown-with-msg?
       #?(:clj clojure.lang.ExceptionInfo
          :cljs js/Object)
       #"Signal :foo is not recognized"
       (ds/signal {::ds/defs {:group {:component "constant"}}}
                  :foo)))

  (testing "should not throw exception"
    (is (ds/signal {::ds/defs    {:group {:component "constant"}}
                    ::ds/signals (merge ds/default-signals {:foo {:order :topsort}})}
                   :foo))))

(deftest required-component-test
  (is (thrown?
       #?(:clj clojure.lang.ExceptionInfo
          :cljs js/Object)
       (ds/signal {::ds/defs {:group {:component ds/required-component}}}
                  ::ds/start)))
  (try (ds/signal {::ds/defs {:group {:component ds/required-component}}}
                  ::ds/start)
       (catch #?(:clj Exception :cljs :default) e
         (is (= [:group :component]
                (-> e
                    ex-data
                    ::ds/signal-meta
                    :component-id))))))

(deftest component-ids-test
  (is (= [[:group-a :a]
          [:group-a :b]
          [:group-b :a]
          [:group-b :b]
          [:group-b :c]]
         (ds/component-ids {::ds/defs {:group-a {:a nil
                                                 :b nil}
                                       :group-b {:a nil
                                                 :b nil
                                                 :c nil}}}))))

(deftest status-signal-test
  (is (= {:group-a {:a :status-a}}
         (::ds/status
          (ds/signal {::ds/defs
                      {:group-a
                       {:a #::ds{:status (fn [_] :status-a)}}}}
                     ::ds/status)))))

(deftest describe-system-test
  (is (= {:group-a
          {:a
           {:name            [:group-a :a]
            :config          nil
            :resolved-config nil
            :instance        ["a-component"]
            :status          :status-a
            :doc             "a component doc"
            :dependencies    #{}}
           :b
           {:name            [:group-a :b]
            :config          {:dep (ds/local-ref [:a])}
            :resolved-config {:dep ["a-component"]}
            :instance        "b-component"
            :status          :b-component
            :doc             nil
            :dependencies    #{[:group-a :a]}}}}
         (ds/describe-system
          (ds/start
           {::ds/defs {:group-a {:a #::ds{:start  (with-meta ["a-component"] {:doc "a component doc"})
                                          :status (fn [_] :status-a)}
                                 :b #::ds{:start  (fn [_] "b-component")
                                          :status (fn [{:keys [::ds/instance]}]
                                                    (keyword instance))
                                          :config {:dep (ds/local-ref [:a])}}}}})))))

(deftest describe-unstarted-system-test
  (is (= {:group-a
          {:a
           {:name            [:group-a :a]
            :config          nil
            :resolved-config nil
            :instance        nil
            :status          :status-a
            :doc             nil
            :dependencies    #{}}
           :b
           {:name            [:group-a :b]
            :config          {:dep (ds/local-ref [:a])}
            :resolved-config {:dep nil}
            :instance        nil
            :status          :status-b
            :doc             nil
            :dependencies    #{[:group-a :a]}}}}
         (ds/describe-system
          {::ds/defs {:group-a {:a #::ds{:start  (with-meta ["a-component"] {:doc "a component doc"})
                                         :status (fn [_] :status-a)}
                                :b #::ds{:start  (fn [_] "b-component")
                                         :status :status-b
                                         :config {:dep (ds/local-ref [:a])}}}}}))))

(deftest with-*system*-test
  (is (= {:group-a {:a "component a"
                    :b "component b"}}
         (ds/with-*system*
           {::ds/defs {:group-a {:a #::ds{:start  "component a"}
                                 :b #::ds{:start  "component b"}}}}
           (::ds/instances ds/*system*)))))

(deftest update-many-test
  (is (= {:a {:b "FOO"
              :c 1}}
         (ds/update-many
          {:a {:b "foo"
               :c 0}}
          {[:a :b] str/upper-case
           [:a :c] inc}))))

(deftest caching
  (reset! ds/component-instance-cache {})
  (let [counter (atom 0)
        system  {::ds/defs
                 {:group
                  {:component (ds/cache-component
                               {::ds/start (fn [_] (swap! counter inc))
                                ::ds/stop  (fn [_] (swap! counter + 10))})}}}]
    (ds/start system)
    (is (= 1 @counter))
    (ds/stop system)
    (is (= 1 @counter))

    (ds/start system)
    (is (= 1 @counter))

    ;; if you clear the cache then the stop signal will go through
    (reset! ds/component-instance-cache {})
    (ds/stop system)
    (is (= 11 @counter))))

(deftest custom-signal-test
  (is (= {:group-a
          {:a
           {:name            [:group-a :a]
            :config          nil
            :resolved-config nil
            :instance        {:custom/signal true :started true}
            :dependencies    #{}
            :status          nil
            :doc             nil}}}
         (-> {::ds/defs {:group-a {:a #::ds{:start         (fn [_] {:started true})
                                            :custom/signal (fn [{::ds/keys [instance]}] (assoc instance :custom/signal true))}}}
              ::ds/signals
              {:custom/signal {:order             :topsort
                               :returns-instance? true}}}
             ds/start
             (ds/signal :custom/signal)
             ds/describe-system))))

(deftest seq-ref-order-test
  (is (= {:resolved [{:A 1} {:inner 2} 3]}
         (-> {::ds/defs {:group-a {:a #::ds{:start {:A 1}}
                                   :b #::ds{:start {:B {:inner 2}}}
                                   :c #::ds{:start {:C {:inner 3}}}
                                   :e #::ds{:config {:args (seq [(ds/local-ref [:a]) (ds/local-ref [:b :B]) (ds/ref [:group-a :c :C :inner])])}
                                            :start  (fn [{{:keys [args]} ::ds/config}] {:resolved (vec args)})}}}}
             ds/start
             ds/describe-system
             :group-a :e :instance))
      "Refs are resolved in the correct order inside of seqs"))

(deftest stop-failed-system-test
  #?(:clj
     ;; I cannot for the life of me figure out why this doesn't work with cljs
     ;; for whatever reason the try/catch around (start ~system) in
     ;; with-*system* doesn't catch the exception that gets thrown when starting
     (testing "stops when there's an exception during start"
       (let [stop-check (atom nil)]
         (try
           (ds/with-*system* {::ds/defs {:group-a {:a #::ds{:start (fn [_])
                                                            :stop (fn [_] (reset! stop-check true))}
                                                   :b #::ds{:start (fn [_] (throw (ex-info "test" {})))
                                                            :config {:foo (ds/local-ref [:a])}}}}})
           (catch #?(:clj Exception :cljs :default) _))
         (is (= true @stop-check))))

     (testing "stops when there are two components"
       (let [no-second-throw (atom false)]
         (try
           (ds/start {::ds/defs {:group {:component-b {::ds/start (fn [_] (throw (RuntimeException. "Error starting component-b")))}
                                         :component-c {::ds/config {:component-b (ds/local-ref [:component-b])}
                                                       ::ds/start (fn [_] (println "Starting component-c"))}}}})
           (catch Exception #?(:clj Exception :cljs :default)
             (ds/stop-failed-system e)
             (reset! no-second-throw true)))
         (is @no-second-throw)))

     (testing "stops when there are three components"
       (let [no-second-throw (atom false)]
         (try
           (ds/start
            {::ds/defs {:group {:component-a {::ds/start (fn [_] (println "Starting component-a"))}
                                :component-b {::ds/config {:component-a (ds/local-ref [:component-a])}
                                              ::ds/start  (fn [_] (throw (RuntimeException. "Error starting component-b")))}
                                :component-c {::ds/config {:component-b (ds/local-ref [:component-b])}
                                              ::ds/start  (fn [_] (println "Starting component-c"))}}}})
           (catch Exception #?(:clj Exception :cljs :default)
             (ds/stop-failed-system)))
         (is @no-second-throw))))

  (testing "stops when there's an exception in body of with-*system*"
    (let [stop-check (atom nil)]
      (try
        (ds/with-*system* {::ds/defs {:group-a {:a #::ds{:start (fn [_])
                                                         :stop (fn [_] (reset! stop-check true))}}}}
          (throw (ex-info "test" {})))
        (catch #?(:clj Exception :cljs :default) _))
      (is (= true @stop-check)))))

(deftest component-meta-test
  (let [system {::ds/defs {:group {:component #::ds{:pre-start  (fn [{:keys [::ds/component-meta]}]
                                                                  (reset! component-meta [::ds/pre-start]))
                                                    :start      (fn [{:keys [::ds/component-meta]}]
                                                                  (swap! component-meta conj ::ds/start))
                                                    :post-start (fn [{:keys [::ds/component-meta]}]
                                                                  (swap! component-meta conj ::ds/post-start))}}}}]
    (is (= [::ds/pre-start ::ds/start ::ds/post-start]
           (get-in (ds/start system) [::ds/component-meta :group :component])))))
