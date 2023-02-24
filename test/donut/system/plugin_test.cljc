(ns donut.system.plugin-test
  (:require
   #?(:clj [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer [deftest is testing] :include-macros true])
   [donut.system :as ds]
   [donut.system.plugin :as dsp]))

(deftest merge-plugin-test
  (is (= {:a [:baz :foo :bar]}
         (#'dsp/merge-plugin {:a [:foo :bar]} {:a ^:prepend [:baz]})))

  (is (= {:a [:foo :bar :baz]}
         (#'dsp/merge-plugin {:a [:foo :bar]} {:a ^:append [:baz]})))

  (is (= {:a [:baz]}
         (#'dsp/merge-plugin {:a [:foo :bar]} {:a [:baz]})))

  (is (= {::ds/defs {:routing {:routes [:a :b :c :d]}}}
         (#'dsp/merge-plugin
          {::ds/defs {:routing {:routes [:a :b]}}}
          {::ds/defs {:routing {:routes ^:append [:c :d]}}})))

  (is (= {::ds/defs {:routing {:routes [:c :d :a :b]}}}
         (#'dsp/merge-plugin
          {::ds/defs {:routing {:routes [:a :b]}}}
          {::ds/defs {:routing {:routes ^:prepend [:c :d]}}}))))

(deftest apply-plugins-test
  (let [plugin #::dsp{:name
                      ::test-harness-plgun

                      :doc
                      "Foo"

                      :system-defaults
                      {::ds/registry {:donut/endpoint-router [:routing :router]
                                      :donut/http-handler    [:http :handler]}
                       ::ds/defs     {::config {:default-request-content-type :transit-json
                                                :default-method               :get}}}

                      :system-merge
                      {::ds/defs {:routing {:routes ^:append [:c :d]}}}

                      :system-update
                      (fn [system]
                        (assoc system :test :system-update))}]

    (is (= {::ds/registry {:donut/endpoint-router [:routing :router]
                           :donut/http-handler    [:http :handler]}
            ::ds/defs     {::config {:default-request-content-type :transit-json
                                     :default-method               :post}
                           :routing {:routes [:a :b :c :d]}}
            ::ds/plugins  [plugin]
            :test         :system-update}
           (dsp/apply-plugins
            #::ds{:defs    {::config {:default-method :post}
                            :routing {:routes [:a :b]}}
                  :plugins [plugin]})))))
