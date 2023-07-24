(ns donut.system.test.mock
  "helpers for mocking components"
  (:require
   [donut.system :as ds]))

(def mock-fn-component
  #::ds{:start  (fn [{:keys [::ds/component-id ::ds/config]}]
                  (let [{:keys [return mock-calls]} config]
                    (reset! mock-calls [])
                    (fn [& args]
                      (swap! mock-calls conj [component-id (vec args)])
                      (if (fn? return)
                        (return)
                        return))))
        :config {:return     nil
                 :mock-calls (ds/registry-ref ::mock-calls)}})

(defn called?
  "check that a component fn was called at all"
  ([component-id]
   (called? ds/*system* component-id))
  ([system component-id]
   (filter (fn [[called-component-id]]
             (= component-id called-component-id))
           @(ds/registry-instance system ::mock-calls))))

(defn called-with?
  "check that a component fn was called with args"
  ([component-id args]
   (called-with? ds/*system* component-id args))
  ([system component-id args]
   (->> @(ds/registry-instance system ::mock-calls)
        (filter (fn [called-with] (= [component-id args] called-with)))
        first)))

(def MockCallsComponent
  #::ds{:start (fn [_] (atom []))})

(def mock-calls-plugin
  {:donut.system.plugin/name
   ::mock-calls-plugin

   :donut.system/doc
   "Adds a component that can record calls to mock functions"

   :donut.system.plugin/system-defaults
   {::ds/registry {::mock-calls [:donut.system.test/mock :mock-calls]}
    ::ds/defs     {:donut.system.test/mock {:mock-calls MockCallsComponent}}}})
