(ns donut.system.dev
  (:require
   [donut.error :as de]
   [donut.error.dev :as ded]
   [donut.system.validation :as dsv]
   [malli.dev.virhe :as v]))

;;---
;; error reporting
;;---

(defn signal-meta-block
  [{:keys [:donut.system/signal-meta]} printer]
  (when signal-meta
    [(ded/-block "donut.system signal handling metadata" (v/-visit signal-meta printer) printer)]))

(defmethod v/-format ::apply-signal-exception
  [_ {:keys [message] :as data} printer]
  {:title "Error Applying Signal for Component"
   :body  (de/build-group
           [(de/-block "Exception message" message printer)]
           (signal-meta-block data printer)
           (de/donut-footer data printer))})

(defmethod v/-format ::dsv/invalid-component-config [_ {:keys [schema-path config-path explanation] :as data} printer]
  {:title "Component Config Validation Error"
   :body  (de/build-group
           (de/schema-explain-body explanation printer)
           [(de/-block "Schema path" (v/-visit schema-path printer) printer)]
           [(de/-block "Config path" (v/-visit config-path printer) printer)]
           (signal-meta-block data printer)
           (de/donut-footer data printer))})

(defmethod v/-format ::dsv/invalid-instance [_ {:keys [schema-path explanation] :as data} printer]
  {:title "Component Instance Validation Error"
   :body  (de/build-group
           (de/schema-explain-body explanation printer)
           [(de/-block "Schema path" (v/-visit schema-path printer) printer)]
           (signal-meta-block data printer)
           (de/donut-footer data printer))})
