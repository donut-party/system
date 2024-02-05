(ns donut.system.test.mock-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [donut.system :as ds]
   [donut.system.test.mock :as dstm]))

(def test-system
  #::ds{:defs
        {:group-a {:component-a dstm/MockFnComponent
                   :component-b #::ds{:start  (fn [{:keys [::ds/config]}]
                                                (let [{:keys [component-a]} config]
                                                  (component-a 1 2 3)))
                                      :config {:component-a (ds/local-ref [:component-a])}}}}
        :plugins
        [dstm/mock-calls-plugin]})

(deftest test-called?
  (testing "records and finds calls"
    (let [system (ds/start test-system)]
      (is (dstm/called? system [:group-a :component-a]))
      (is (dstm/called-with? system [:group-a :component-a] [1 2 3])))))

(deftest test-called?-with-*system*
  (testing "records and finds calls"
    (ds/with-*system* test-system
      (is (dstm/called? [:group-a :component-a]))
      (is (dstm/called-with? [:group-a :component-a] [1 2 3])))))

(deftest test-caled-multiple-times?
  (let [system (ds/start test-system {[:group-a :component-c] #::ds{:start  (fn [{:keys [::ds/config]}]
                                                                              (let [{:keys [component-a]} config]
                                                                                (component-a :component-c)))
                                                                    :config {:component-a (ds/local-ref [:component-a])}}})]
    (is (= [[[:group-a :component-a] [1 2 3]]
            [[:group-a :component-a] [:component-c]]]
           @(ds/instance system [dstm/COMPONENT-GROUP-NAME :mock-calls])))))

(deftest test-new-atom-per-system-start
  (testing "does not reuse atom between system starts"
    (let [system (-> test-system
                     (ds/start)
                     (ds/stop)
                     (ds/start))]
      ;; would have two recordings instead of just one of item were reused
      (is (= [[[:group-a :component-a] [1 2 3]]]
             @(ds/instance system [dstm/COMPONENT-GROUP-NAME :mock-calls]))))))
