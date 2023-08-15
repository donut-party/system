(ns donut.system.validation
  (:require
   [donut.system :as ds]
   [donut.system.plugin :as dsp]
   [malli.core :as m]
   [malli.error :as me]))

(defn validate
  [->validation schema x]
  (when-let [explanation (and schema (m/explain schema x))]
    (->validation
     {:spec-explain-human (me/humanize explanation)
      :spec-explain       explanation})))

(defn validate-def
  [{:keys [->validation ::ds/config ::ds/component-def-schema ::ds/config-schema] :as component-def}]
  (or (validate ->validation component-def-schema component-def)
      (validate ->validation config-schema config)))

(defn validate-instance
  [{:keys [->validation ::ds/instance ::ds/system]}]
  (validate ->validation
            (get-in system [::ds/current-resolved-component ::ds/instance-schema])
            instance))

(def validation-plugin
  #::dsp{:name
         ::validation-plugin

         :doc
         "Updates pre-start and post-start to validate configs and instances"

         :system-defaults
         {::ds/base {::ds/pre-start  validate-def
                     ::ds/post-start validate-instance}}})
