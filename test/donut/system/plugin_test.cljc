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

(def test-plugin
  #::dsp{:name
         ::test-harness-plugin

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
           (assoc system :test :system-update))})

(def test-system
  #::ds{:defs    {::config {:default-method :post}
                  :routing {:routes [:a :b]}}
        :plugins [test-plugin]})

(deftest apply-plugins-test
  (is (= {::ds/registry {:donut/endpoint-router [:routing :router]
                         :donut/http-handler    [:http :handler]}
          ::ds/defs     {::config {:default-request-content-type :transit-json
                                   :default-method               :post}
                         :routing {:routes [:a :b :c :d]}}
          ::ds/plugins  [test-plugin]
          :test         :system-update}
         (dsp/apply-plugins test-system))))

(deftest describe-plugin-test
  (is (= [(assoc test-plugin
                 ::dsp/system-diff
                 [nil
                  {:donut.system/defs     {:routing                         {:routes [nil nil :c :d]}
                                           :donut.system.plugin-test/config {:default-request-content-type
                                                                             :transit-json}}
                   :donut.system/registry #:donut{:endpoint-router [:routing :router]
                                                  :http-handler    [:http :handler]}
                   :test                  :system-update}]

                 ::dsp/system-update
                 :function)]
         (dsp/describe-plugins test-system))))
