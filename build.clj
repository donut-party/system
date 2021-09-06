(ns build
  "donut/system's build script. Builds on https://github.com/seancorfield/honeysql/blob/develop/build.clj

  Run tests:
  clojure -X:test
  clojure -X:test:master
  For more information, run:
  clojure -A:deps -T:build help/doc"

  (:require [clojure.tools.build.api :as b]
            [org.corfield.build :as bb]))

(def lib 'club.donutpower/system)
(def version (format "0.0.%s" (b/git-count-revs nil)))

(defn deploy "Deploy the JAR to Clojars"
  [opts]
  (-> opts
      (assoc :lib lib :version version)
      (bb/deploy)))


(defn jar "build a jar"
  [opts]
  (-> opts
      (assoc :lib lib :version version)
      (bb/clean)
      (bb/jar)))
