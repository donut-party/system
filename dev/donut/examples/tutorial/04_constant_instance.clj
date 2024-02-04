(ns donut.examples.tutorial.04-constant-instance
  (:require
   [donut.system :as ds]))

(def system
  {::ds/defs
   {:env      {:db-conn-string "//localhost:5032etcetc"}
    :services {:db {::ds/start  (fn [{:keys [::ds/config ::ds/system]}]
                                  (prn "system" (ds/instance system [:env :db-conn-string]))
                                  (prn "db-conn-string" (:db-conn-string config)))
                    ::ds/config {:db-conn-string (ds/ref [:env :db-conn-string])}}}}})

(ds/start system)
