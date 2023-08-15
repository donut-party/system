(ns donut.system.validation-test
  (:require
   #?(:clj [clojure.test :refer [deftest testing is]]
      :cljs [cljs.test :refer [deftest testing is] :include-macros true])
   [donut.system :as ds :include-macros true]
   [donut.system.validation :as dsv]
   [malli.core :as m]))

(deftest component-def-schema-validation-test
  (testing "use ::ds/component-def-schema to validate entire component def"
    (let [system #::ds{:defs
                       {:group-a
                        {:component-a
                         #::ds{:start (fn [_] 1)
                               :config {}
                               :component-def-schema [:map [::ds/config map?]]}}}
                       :plugins
                       [dsv/validation-plugin]}]
      (is (= {::ds/config ["should be a map"]}
             (-> (ds/start system {[:group-a :component-a ::ds/config] "not a map"})
                 ::ds/out
                 :validation
                 :group-a
                 :component-a
                 :spec-explain-human)))
      (is (nil? (::ds/out (ds/start system)))))))

(deftest config-schema-validation-test
  (testing "use ::ds/config-schema to validate just schema"
    (let [system #::ds{:defs
                       {:group-a
                        {:component-a
                         #::ds{:start :any-value
                               :config {:required-string "this string is required"}
                               :config-schema [:map [:required-string string?]]}}}

                       :plugins
                       [dsv/validation-plugin]}]
      (is (= {:required-string ["should be a string"]}
             (-> (ds/start system {[:group-a :component-a ::ds/config :required-string] 12})
                 ::ds/out
                 :validation
                 :group-a
                 :component-a
                 :spec-explain-human)))
      (is (nil? (::ds/out (ds/start system)))))))

(deftest instance-schema-validation-test
  (testing "use ::ds/instance-schema to validate instance returned by ::ds/start"
    (let [system #::ds{:defs
                       {:group-a
                        {:component-a
                         #::ds{:start 1
                               :config {}
                               :instance-schema (m/schema int?)}}}

                       :plugins
                       [dsv/validation-plugin]}]
      (is (= ["should be an int"]
             (-> (ds/start system {[:group-a :component-a ::ds/start] "not an int"})
                 ::ds/out
                 :validation
                 :group-a
                 :component-a
                 :spec-explain-human)))
      (is (nil? (::ds/out (ds/start system)))))))
