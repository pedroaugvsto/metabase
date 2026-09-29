(ns metabase-enterprise.data-apps.resource-files
  "The serdes YAML files that hold a data app's resources, under `data_apps/<slug>/resources/`:

     resources/
       collection.yaml      # the app's resource collection
       cards/*.yaml         # saved questions, and copies of the models and metrics the app uses
       actions/*.yaml       # copies of the actions the app runs

   `npm run sync-resources` writes them from [[metabase-enterprise.data-apps.render]], and every repository import
   loads them (see [[metabase-enterprise.data-apps.resource-load]])."
  (:require
   [clojure.string :as str]
   [metabase-enterprise.serialization.core :as serialization]
   [metabase.util.i18n :refer [tru]]
   [metabase.util.yaml :as yaml])
  (:import
   (java.nio.charset StandardCharsets)
   (java.security MessageDigest)
   (org.apache.commons.codec.binary Hex)))

(set! *warn-on-reflection* true)

(def resources-dir
  "Directory inside an app's directory that holds its resource files."
  "resources")

(def collection-file
  "The resource collection's file, relative to [[resources-dir]]."
  "collection.yaml")

(def ^:private model->dir
  {"Card"   "cards"
   "Action" "actions"})

(def ^:private dir->model
  (into {} (map (fn [[model dir]] [dir model])) model->dir))

(def ^:private nano-id-alphabet
  "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-")

(defn derived-entity-id
  "The entity ID of an app's copy of the `kind` entity with `source-entity-id`, in the app whose resource collection is
  `collection-entity-id`. Derived rather than random so rendering again addresses the same copy."
  [kind collection-entity-id source-entity-id]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes (str/join "\u0000" [kind collection-entity-id source-entity-id])
                                   StandardCharsets/UTF_8))]
    (apply str (for [i (range 21)]
                 (.charAt ^String nano-id-alphabet (bit-and (aget digest i) 63))))))

(defn file-path
  "The path, relative to [[resources-dir]], of the file holding `entity`, a serdes-extracted Collection, Card, or
  Action."
  [entity]
  (let [{:keys [model id label]} (last (:serdes/meta entity))]
    (if (= model "Collection")
      collection-file
      (str (model->dir model) "/" (serialization/slugify-name label) "_" id ".yaml"))))

(defn parse-content
  "Parse the text of a resource file into the entity serdes loads."
  [content]
  (serialization/read-timestamps (yaml/parse-string content {:key-fn serialization/parse-key})))

(defn- visible? [^String path]
  (not (str/starts-with? (subs path (inc (str/last-index-of path "/"))) ".")))

(defn- yaml-files [list-dir dir]
  (for [path (list-dir dir)
        :when (visible? path)]
    (if (str/ends-with? path ".yaml")
      path
      (throw (ex-info (tru "{0} is not a YAML file." path) {:status-code 400})))))

(defn- resource-paths
  "Every resource file path under `resources` with the model its location says it holds. Throws for anything that
  doesn't belong."
  [list-dir resources]
  (let [collection-path (str resources "/" collection-file)]
    (into []
          (mapcat (fn [path]
                    (let [child (subs path (inc (count resources)))]
                      (cond
                        (= path collection-path) [{:path path :model "Collection"}]
                        (dir->model child)       (for [file (yaml-files list-dir path)]
                                                   {:path file :model (dir->model child)})
                        :else                    (throw (ex-info (tru "{0} is not a data app resource." path)
                                                                 {:status-code 400}))))))
          (filter visible? (list-dir resources)))))

(defn- read-resource [read-file {:keys [path] :as file}]
  (let [content (or (read-file path)
                    (throw (ex-info (tru "Could not read {0}." path) {:status-code 400})))]
    (assoc file
           :content content
           :entity  (try
                      (parse-content content)
                      (catch Exception e
                        (throw (ex-info (tru "Could not parse {0}: {1}" path (ex-message e))
                                        {:status-code 400}
                                        e)))))))

(defn read-resources
  "Read and parse the resource files of the app in `app-dir` from a snapshot's `list-dir` and `read-file` fns. Returns
  `[{:path <repo path> :model <model the location holds> :content <text> :entity <ingested entity>} ...]`. Throws for
  a file that is out of place or does not parse."
  [list-dir read-file app-dir]
  (mapv (partial read-resource read-file)
        (resource-paths list-dir (str app-dir "/" resources-dir))))

(defn resources-hash
  "A hash of `resources` as returned by [[read-resources]], to tell whether an import changed them."
  [resources]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (doseq [{:keys [path content]} (sort-by :path resources)
            ^String part [path "\u0000" content "\u0000"]]
      (.update digest (.getBytes part StandardCharsets/UTF_8)))
    (Hex/encodeHexString (.digest digest))))
