(ns donut.doodles
  "a dev scratch pad"
  (:require
   [donut.system :as ds]))

;;---
;;; subsystems
;;---

(def subsystem
  {::ds/defs
   {:env {:port 9090}

    :app
    {:server {:job-queue  (ds/ref [:common-services :job-queue])
              :db         (ds/ref [:common-services :db])
              :port       (ds/ref [:env :port])
              :init       (fn [resolved _ _]
                            (select-keys resolved [:job-queue :db :port]))
              :halt       (fn [_ instance _]
                            {:prev instance
                             :now  :halted})}}}})

(def system
  {::ds/defs
   {:env
    {:app-name "foo.app"}

    :common-services
    {:job-queue "job queue"
     :db        "db"}

    :sub-systems
    {:system-1 (ds/subsystem-component
                subsystem
                #{(ds/group-ref :common-services)})
     :system-2 (ds/subsystem-component
                subsystem
                #{(ds/group-ref :common-services)})}}})
