(ns build
  "donut/system's build script. Builds on https://github.com/seancorfield/honeysql/blob/develop/build.clj

  Run tests:
  clojure -X:test
  clojure -X:test:master
  For more information, run:
  clojure -A:deps -T:build help/doc"

  (:require
   [clojure.tools.build.api :as b]
   [deps-deploy.deps-deploy :as dd])
  (:refer-clojure :exclude [test]))

(def lib 'party.donut/system)
(def version (format "1.0.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))

(defn- pom-template [version]
  [[:description "dependency injection lib / framework foundation"]
   [:url "https://github.com/donut-power/system"]
   [:licenses
    [:license
     [:name "MIT"]
     [:url "https://opensource.org/license/mit/"]]]
   [:developers
    [:developer
     [:name "Daniel Higginbotham"]]]
   [:scm
    [:url "https://github.com/donut-power/system"]
    [:connection "scm:git:https://github.com/donut-power/system.git"]
    [:developerConnection "scm:git:ssh:git@github.com:donut-power/system.git"]
    [:tag (str "v" version)]]])

(defn- jar-opts [opts]
  (assoc opts
         :lib lib
         :version version
         :jar-file (format "target/%s-%s.jar" lib version)
         :basis basis
         :class-dir class-dir
         :target "target"
         :src-dirs ["src" "resources"]
         :pom-data (pom-template version)))

(defn jar "build a jar"
  [opts]
  (let [opts (jar-opts opts)]
    (b/delete {:path "target"})
    (b/write-pom opts)
    (b/copy-dir {:src-dirs ["src" "resources"]
                 :target-dir class-dir})
    (b/jar opts)))

(defn deploy "Deploy the JAR to Clojars." [opts]
  (let [{:keys [jar-file] :as opts} (jar-opts opts)]
    (dd/deploy {:installer :remote :artifact (b/resolve-path jar-file)
                :pom-file (b/pom-path (select-keys opts [:lib :class-dir]))}))
  opts)
