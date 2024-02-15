(ns donut.system.validation
  (:require
   [donut.system :as ds]
   [donut.system.plugin :as dsp]
   [malli.core :as m]
   [malli.error :as me]))

(defn validate
  [schema x scheme-key]
  (when-let [explanation (and schema (m/explain schema x))]
    (throw (ex-info "scheme found invalid component data"
                    {:scheme-key         scheme-key
                     :spec-explain-human (me/humanize explanation)
                     :spec-explain       explanation}))))

(defn validate-def-pre-start
  [{:keys [::ds/pre-start-schema] :as component-def}]
  (validate pre-start-schema component-def ::ds/pre-start-schema))

(defn validate-instance
  [{:keys [::ds/instance ::ds/instance-schema]}]
  (validate instance-schema instance ::ds/instance-schema))

(defn validate-config
  [{:keys [::ds/config-schema ::ds/config] :as _component-def}]
  (validate config-schema config ::ds/config-schema))

(def validation-plugin
  #::dsp{:name
         ::validation-plugin

         :doc
         "Updates pre-start and post-start to validate configs and instances"

         :system-defaults
         {::ds/base {::ds/pre-start  {::validate-pre-start validate-def-pre-start
                                      ::validate-config    validate-config}
                     ::ds/post-start {::validate validate-instance}}}})
