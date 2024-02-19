(ns donut.system.validation
  (:require
   [donut.error :as de]
   [donut.system :as ds]
   [donut.system.plugin :as dsp]
   [malli.core :as m]
   [malli.dev.virhe :as v]
   [malli.error :as me]))

(defn- common-format-body
  [{:keys [explanation schema-path]} printer]
  [:group
   (v/-block "Value" (v/-visit (me/error-value explanation printer) printer) printer) :break :break
   (v/-block "Errors" (v/-visit (me/humanize (me/with-spell-checking explanation)) printer) printer) :break :break
   (v/-block "Schema" (v/-visit (:schema explanation) printer) printer) :break :break
   (v/-block "Schema path" (v/-visit schema-path printer) printer)])

(defmethod v/-format ::invalid-component-config [_ {:keys [value-path] :as data} printer]
  {:title "Component Config Validation Error"
   :body (into (common-format-body data printer)
               [:break :break
                (v/-block "Config path" (v/-visit value-path printer) printer)])})

(defmethod v/-format ::invalid-instance [_ data printer]
  {:title "Component Instance Validation Error"
   :body  (common-format-body data printer)})

(defn validate
  [{:keys [type schema value] :as data}]

  (when-let [explanation (and schema (m/explain schema value))]
    (m/-fail! type
              (-> (dissoc data :type :schema :value)
                  (assoc :explanation explanation)))))

(defn validate-config
  [{:keys [::ds/config-schema ::ds/config ::ds/component-id] :as _component-def}]
  (when config-schema
    (de/validate! config-schema config
      {:schema-path (into [::ds/defs] (conj component-id ::ds/config-schema))
       :value-path  (into [::ds/defs] (conj component-id ::ds/config))})))

(defn validate-instance
  [{:keys [::ds/instance ::ds/instance-schema ::ds/component-id]}]
  (when instance-schema
    (de/validate! instance-schema instance
      {:schema-path (into [::ds/defs] (conj component-id ::ds/config-schema))})))

(def validation-plugin
  #::dsp{:name
         ::validation-plugin

         :doc
         "Updates pre-start and post-start to validate configs and instances"

         :system-defaults
         {::ds/base {::ds/pre-start  {::validate-config validate-config}
                     ::ds/post-start {::validate validate-instance}}}})
