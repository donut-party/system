(ns donut.system.validation
  (:require
   [donut.system :as ds]
   [donut.system.plugin :as dsp]
   [malli.core :as m]
   [malli.dev.virhe :as v]
   [malli.error :as me]))


(defmethod v/-format ::invalid [_ {:keys [explanation schema-path value-path] :as data} printer]
  {:title "System Validation Error"
   :body  [:group
           (v/-block "Value" (v/-visit (me/error-value explanation printer) printer) printer) :break :break
           (v/-block "Errors" (v/-visit (me/humanize (me/with-spell-checking explanation)) printer) printer) :break :break
           (v/-block "Schema" (v/-visit (:schema explanation) printer) printer) :break :break
           (v/-block "Schema path" (v/-visit schema-path printer) printer) :break :break
           (v/-block "Value path" (v/-visit value-path printer) printer)]})

(defn validate
  [{:keys [schema schema-path value value-path]}]
  (when-let [explanation (and schema (m/explain schema value))]
    (m/-fail! ::invalid
              {:explanation explanation
               :schema-path schema-path
               :value-path  value-path})))

(defn validate-config
  [{:keys [::ds/config-schema ::ds/config ::ds/component-id] :as _component-def}]
  (validate {:schema      config-schema
             :schema-path (into [::ds/defs] (conj component-id ::ds/config-schema))
             :value       config
             :value-path  (into [::ds/defs] (conj component-id ::ds/config))}))

(defn validate-instance
  [{:keys [::ds/instance ::ds/instance-schema ::ds/component-id]}]
  (validate {:schema      instance-schema
             :schema-path (into [::ds/defs] (conj component-id ::ds/instance-schema))
             :value       instance
             :value-path  (into [::ds/instances] component-id)}))

(def validation-plugin
  #::dsp{:name
         ::validation-plugin

         :doc
         "Updates pre-start and post-start to validate configs and instances"

         :system-defaults
         {::ds/base {::ds/pre-start  {::validate-config validate-config}
                     ::ds/post-start {::validate validate-instance}}}})
