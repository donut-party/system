(ns donut.system.dev
  (:require
   [donut.error.dev :as ded]
   [donut.system :as ds]
   [donut.system.validation :as dsv]
   [malli.dev.virhe :as v]))

;;---
;; error reporting
;;---

(defn signal-meta-block
  [{:keys [:donut.system/signal-meta]} printer]
  (when signal-meta
    [(ded/-block "donut.system signal handling metadata" (v/-visit signal-meta printer) printer)]))

(defmethod v/-format ::ds/apply-signal-exception
  [_ {:keys [message] :as data} printer]
  {:title "Error Applying Signal for Component"
   :body  (ded/build-group
           [(ded/-block "Exception message" message printer)]
           (signal-meta-block data printer)
           (ded/donut-footer data printer))})

(defmethod v/-format ::ds/invalid-system
  [_ {:keys [explanation] :as data} printer]
  {:title "System doesn't match schema"
   :body  (ded/build-group
           (ded/schema-explain-body explanation printer)
           (ded/donut-footer data printer))})

(defmethod v/-format ::dsv/invalid-component-config [_ {:keys [schema-path config-path explanation] :as data} printer]
  {:title "Component Config Validation Error"
   :body  (ded/build-group
           (ded/schema-explain-body explanation printer)
           [(ded/-block "Schema path" (v/-visit schema-path printer) printer)]
           [(ded/-block "Config path" (v/-visit config-path printer) printer)]
           (signal-meta-block data printer)
           (ded/donut-footer data printer))})

(defmethod v/-format ::dsv/invalid-instance [_ {:keys [schema-path explanation] :as data} printer]
  {:title "Component Instance Validation Error"
   :body  (ded/build-group
           (ded/schema-explain-body explanation printer)
           [(ded/-block "Schema path" (v/-visit schema-path printer) printer)]
           (signal-meta-block data printer)
           (ded/donut-footer data printer))})
