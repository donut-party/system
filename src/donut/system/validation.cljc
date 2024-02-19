(ns donut.system.validation
  (:require
   [donut.error :as de]
   [donut.system :as ds]
   [donut.system.plugin :as dsp]
   [malli.dev.virhe :as v]))

(defmethod v/-format ::invalid-component-config [_ {:keys [schema-path config-path explanation] :as data} printer]
  {:title "Component Config Validation Error"
   :body  (de/build-group
           (de/schema-explain-body explanation printer)
           [(de/-block "Schema path" (v/-visit schema-path printer) printer)]
           [(de/-block "Config path" (v/-visit config-path printer) printer)]
           (de/donut-footer data printer))})

(defmethod v/-format ::invalid-instance [_ {:keys [schema-path explanation] :as data} printer]
  {:title "Component Instance Validation Error"
   :body  (de/build-group
           (de/schema-explain-body explanation printer)
           [(de/-block "Schema path" (v/-visit schema-path printer) printer)]
           (de/donut-footer data printer))})

(defn validate-config
  [{:keys [::ds/config-schema ::ds/config ::ds/component-id] :as _component-def}]
  (when config-schema
    (de/validate!
     config-schema
     config
     {::de/id      ::invalid-component-config
      ::de/url     (de/url ::invalid-component-config)
      :schema-path (into [::ds/defs] (conj component-id ::ds/config-schema))
      :config-path (into [::ds/defs] (conj component-id ::ds/config))})))

(defn validate-instance
  [{:keys [::ds/instance ::ds/instance-schema ::ds/component-id]}]
  (when instance-schema
    (de/validate!
     instance-schema
     instance
     {::de/id      ::invalid-instance
      ::de/url     (de/url ::invalid-instance)
      :schema-path (into [::ds/defs] (conj component-id ::ds/instance-schema))})))

(def validation-plugin
  #::dsp{:name
         ::validation-plugin

         :doc
         "Updates pre-start and post-start to validate configs and instances"

         :system-defaults
         {::ds/base {::ds/pre-start  {::validate-config validate-config}
                     ::ds/post-start {::validate validate-instance}}}})
