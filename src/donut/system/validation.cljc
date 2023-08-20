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

(defn validate-def-pre-start
  [{:keys [->validation ::ds/pre-start-schema] :as component-def}]
  (validate ->validation pre-start-schema component-def))

(defn validate-instance
  [{:keys [->validation ::ds/instance ::ds/instance-schema]}]
  (validate ->validation instance-schema instance))

(def validation-plugin
  #::dsp{:name
         ::validation-plugin

         :doc
         "Updates pre-start and post-start to validate configs and instances"

         :system-defaults
         {::ds/base {::ds/pre-start  validate-def-pre-start
                     ::ds/post-start validate-instance}}})
