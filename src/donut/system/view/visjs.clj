(ns donut.system.view.visjs
  "Use vis.js to show display a system's dependency graph"
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :as sh]
   [clojure.string :as str]
   [donut.system :as ds]))

(defn- format-node
  [component-name]
  (format "{id: \"%s\", label: \"%s\"}" component-name component-name))

(defn- format-nodes
  [component-descriptions]
  (->> component-descriptions
       (map (comp format-node :name))
       (str/join ",")))

(defn- format-component-edges
  [component-description]
  (let [component-name (:name component-description)]
    (->> component-description
         :dependencies
         (map (fn [dep] (format "{from: \"%s\", to: \"%s\", arrows: \"to\"}" component-name dep)))
         (str/join ","))))

(defn- format-edges
  [component-descriptions]
  (->> component-descriptions
       (map format-component-edges)
       (remove empty?)
       (str/join ",")))

(defn format-data
  [system]
  (let [component-descriptions (ds/describe-components system)]
    (format "var nodes = new vis.DataSet([%s]);

var edges = new vis.DataSet([%s]);"
            (format-nodes component-descriptions)
            (format-edges component-descriptions))))

(defn- os
  "Returns :win, :mac, :unix, or nil"
  []
  (condp
      #(<= 0 (.indexOf ^String %2 ^String %1))
      (.toLowerCase (System/getProperty "os.name"))
    "win" :win
    "mac" :mac
    "nix" :unix
    "nux" :unix
    nil))


(defn- open
  "Opens the given file (a string, File, or file URI) in the default
  application for the current desktop environment. Returns nil"
  [f]
  (let [f (io/file f)]
    ;; There's an 'open' method in java.awt.Desktop but it hangs on Windows
    ;; using Clojure Box and turns the process into a GUI process on Max OS X.
    ;; Maybe it's ok for Linux?
    (condp = (os)
      :mac  (sh/sh "open" (str f))
      :win  (sh/sh "cmd" (str "/c start " (-> f .toURI .toURL str)))
      :unix (sh/sh "xdg-open" (str f)))
    nil))

(defn show
  [system]
  (let [data      (format-data system)
        template  (slurp (io/resource "donut/system/visjs.html"))
        html      (str/replace template #"(?s)// BEGIN.*END data" data)
        temp-file (java.io.File/createTempFile "donut-system" ".html")]
    (spit temp-file html)
    (open temp-file)))
