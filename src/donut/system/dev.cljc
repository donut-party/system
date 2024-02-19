(ns donut.system.dev
  (:require
   [donut.error :as de]
   [malli.dev.virhe :as v]))

;;---
;; error reporting
;;---

(defn signal-meta-block
  [{:keys [:donut.system/signal-meta]} printer]
  (when signal-meta
    [(de/-block "donut.system signal handling metadata" (v/-visit signal-meta printer) printer)]))

(defmethod v/-format ::apply-signal-exception
  [_ {:keys [message] :as data} printer]
  {:title "Error Applying Signal for Component"
   :body  (de/build-group
           [(de/-block "Exception message" message printer)]
           (signal-meta-block data printer)
           (de/donut-footer data printer))})
