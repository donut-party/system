(ns donut.examples.testing
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [donut.system :as ds]))

;; The fixture returned by `ds/system-fixture` will use the `ds/with-*system*`
;; macro to populate the `ds/*system*` dynamic var for each test
(use-fixtures :each (ds/system-fixture ::test))

;; define the ::test system referenced in the line above
(defmethod ds/named-system ::test
  [_]
  {::ds/defs
   {:group-a
    {:component-a
     {::ds/start (fn [_] (atom []))}}

    :group-b
    {:component-b
     {::ds/start  (fn [opts]
                    ;; add an element to the `[:group-a :component-a]` atom on
                    ;; start
                    (swap! (get-in opts [::ds/config :component-a])
                           conj
                           :foo))
      ::ds/config {:component-a (ds/ref [:group-a :component-a])}}}}})

(deftest retrieving-instances
  (ds/with-*system* ::test
    ;; one way to retrieve an instance
    (is (= [:foo]
           @(get-in ds/*system* [::ds/instances :group-a :component-a])))

    ;; another way to retrieve an instance
    (is (= [:foo]
           @(ds/instance ds/*system* [:group-a :component-a])))))


(deftest using-with-*system*
  (ds/with-*system*
    (is (= [:foo]
           @(get-in ds/*system* [::ds/instances :group-a :component-a])))))

(deftest your-test
  (let [system (ds/start ::test)]
    (is (= [:foo]
           @(get-in system [::ds/instances :group-a :component-a])))
    (ds/stop system)))


(deftest with-override
  ;; method 1
  (let [test-atom (atom [])]
    (ds/start ::test {[:group-a :component-a] test-atom})
    (is (= [:foo] @test-atom)))

  ;; method 2 - the first argument to `ds/with-*system*` can be either a system
  ;; name or a system map. In this example we're getting a system map.
  (let [test-atom (atom [])]
    (ds/with-*system* (ds/system ::test {[:group-a :component-a] test-atom})
      (is (= [:foo] @test-atom)))))
