(ns donut.system.validation-test
  (:require
   #?(:clj [clojure.test :refer [deftest testing is]]
      :cljs [cljs.test :refer [deftest testing is] :include-macros true])
   [donut.system :as ds :include-macros true]
   [donut.system.validation :as dsv]
   [malli.core :as m]))

(deftest component-def-schema-validation-test
  (testing "use ::ds/pre-start-schema to validate entire component def"
    (let [system  #::ds{:defs
                        {:group-a
                         {:component-a
                          #::ds{:start            (fn [_] 1)
                                :config           {}
                                :pre-start-schema [:map [::ds/config map?]]}}}
                        :plugins
                        [dsv/validation-plugin]}
          thrown? (atom false)]
      (try (ds/start system {[:group-a :component-a ::ds/config] "not a map"})
           (catch #?(:clj clojure.lang.ExceptionInfo
                     :cljs js/Object)
               e
             (is (= "scheme found invalid component data"
                    (-> e ex-data :message)))
             (reset! thrown? true)))
      (ds/start system))))

(deftest instance-schema-validation-test
  (testing "use ::ds/instance-schema to validate instance returned by ::ds/start"
    (let [system #::ds{:defs
                       {:group-a
                        {:component-a
                         #::ds{:start           1
                               :config          {}
                               :instance-schema (m/schema int?)}}}

                       :plugins
                       [dsv/validation-plugin]}
          thrown? (atom false)]
      (try (ds/start system {[:group-a :component-a ::ds/start] "not an int"})
           (catch #?(:clj clojure.lang.ExceptionInfo
                     :cljs js/Object)
               e
             (is (= "scheme found invalid component data"
                    (-> e ex-data :message)))
             (reset! thrown? true)))

      (ds/start system))))

(deftest config-schema-validation-test
  (testing "use ::ds/instance-schema to validate instance returned by ::ds/start"
    (let [system #::ds{:defs
                       {:group-a
                        {:component-a
                         #::ds{:start         1
                               :config        {}
                               :config-schema (m/schema int?)}}}

                       :plugins
                       [dsv/validation-plugin]}
          thrown? (atom false)]
      (try (ds/start system)
           (catch #?(:clj clojure.lang.ExceptionInfo
                     :cljs js/Object)
               e
             (is (= "scheme found invalid component data"
                    (-> e ex-data :message)))
             (reset! thrown? true)))
      ;; satisfy spec
      (ds/start system {[:group-a :component-a ::ds/config] 10}))))
