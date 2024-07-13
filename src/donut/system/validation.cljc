(ns donut.system.validation
  (:require
   [donut.error :as de]
   [donut.system :as ds]
   [donut.system.plugin :as dsp]))

(defn- signal-meta
  "Used in ex-info when validation fails"
  [{:keys [::ds/component-id ::ds/system]}]
  {:component-id component-id
   :handler-name (::ds/handler-name system)})

(defn validate-config
  [{:keys [::ds/config-schema ::ds/config ::ds/component-id] :as handler-arg}]
  (when config-schema
    (de/validate!
     config-schema
     config
     {::de/id          ::invalid-component-config
      ::de/url         (de/url ::invalid-component-config)
      ::ds/signal-meta (signal-meta handler-arg)
      :schema-path     (into [::ds/defs] (conj component-id ::ds/config-schema))
      :config-path     (into [::ds/defs] (conj component-id ::ds/config))})))

(defn validate-instance
  [{:keys [::ds/instance ::ds/instance-schema ::ds/component-id handler-arg]}]
  (when instance-schema
    (de/validate!
     instance-schema
     instance
     {::de/id          ::invalid-instance
      ::de/url         (de/url ::invalid-instance)
      ::ds/signal-meta (signal-meta handler-arg)
      :schema-path     (into [::ds/defs] (conj component-id ::ds/instance-schema))})))

(def validation-plugin
  #::dsp{:name
         ::validation-plugin

         :doc
         "Updates pre-start and post-start to validate configs and instances"

         :system-defaults
         {::ds/base {::ds/pre-start  {::validate-config validate-config}
                     ::ds/post-start {::validate validate-instance}}}})
