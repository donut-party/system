(ns donut.system.test.mock
  "helpers for mocking components"
  (:require
   [donut.system :as ds]))

(def COMPONENT-GROUP-NAME :donut.system.test/mock)
(def COMPONENT-ID [COMPONENT-GROUP-NAME :mock-calls])

(def MockFnComponent
  "For mocking components whose instances are a function. Records all calls to the
  function as a tuple of

  [[:component-group-name :component-name] [arg-1 arg-2 ...]]

  Set value the mock function should return under [::ds/config :return].
  - If this is a function, then the function will be called
  - If you need to return a literal function, use (constantly (fn []))
  - Use something like (cycle [1 2 3]) to return different values on consecutive c
    alls"
  #::ds{:start  (fn [{:keys [::ds/component-id ::ds/config]}]
                  (let [{:keys [return mock-calls]} config]
                    (reset! mock-calls [])
                    (fn [& args]
                      (swap! mock-calls conj [component-id (vec args)])
                      (if (fn? return)
                        (return)
                        return))))
        :config {:return     nil
                 :mock-calls (ds/ref COMPONENT-ID)}})

(defn called?
  "check that a component fn was called at all"
  ([component-id]
   (called? ds/*system* component-id))
  ([system component-id]
   (filter (fn [[called-component-id]]
             (= component-id called-component-id))
           @(ds/instance system COMPONENT-ID))))

(defn called-with?
  "check that a component fn was called with args"
  ([component-id args]
   (called-with? ds/*system* component-id args))
  ([system component-id args]
   (->> @(ds/instance system COMPONENT-ID)
        (filter (fn [called-with] (= [component-id args] called-with)))
        first)))

(def MockCallsComponent
  "Records all mock calls"
  #::ds{:start (fn [_] (atom []))})

(def mock-calls-plugin
  "Add MockCallsComponent to a system"
  {:donut.system.plugin/name
   ::mock-calls-plugin

   :donut.system/doc
   "Adds a component that can record calls to mock functions"

   :donut.system.plugin/system-defaults
   {::ds/defs {COMPONENT-GROUP-NAME {:mock-calls MockCallsComponent}}}})
