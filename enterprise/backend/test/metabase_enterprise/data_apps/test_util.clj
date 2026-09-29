(ns metabase-enterprise.data-apps.test-util
  (:require
   [metabase-enterprise.data-apps.resources :as data-app.resources]
   [metabase-enterprise.remote-sync.source :as source]
   [metabase.test :as mt]
   [metabase.util.yaml :as yaml]
   [toucan2.core :as t2]))

(def fake-sha
  "A commit SHA for snapshots that don't care which commit they are."
  "0123456789abcdef0123456789abcdef01234567")

(defn snapshot
  "Build a snapshot (as the remote-sync import passes one) from a path->content map. `read-file` returns file text or
  nil; `list-dir` reuses the derivation the real non-git snapshots use, so the fake can't drift from it."
  [path->content & {:keys [sha] :or {sha fake-sha}}]
  {:sha       sha
   :list-dir  (fn [dir] (source/paths->children (keys path->content) dir))
   :read-file (fn [p] (get path->content p))})

(defn collection-entity-id
  "A stable resource collection entity ID for the app in `data_apps/<slug>`."
  [slug]
  (subs (str slug "-" (apply str (repeat 21 "c"))) 0 21))

(defn collection-file
  "The text of a `resources/collection.yaml` for the collection with `entity-id`."
  [entity-id collection-name]
  (yaml/generate-string {:name        collection-name
                         :entity_id   entity-id
                         :serdes/meta [{:model "Collection" :id entity-id :label "data_app"}]}))

(defn app-files
  "Repo files for one data app under `data_apps/<dir>/`: its data_app.yaml, a bundle at `path` with `bundle` content,
  and its resource files. `resources` maps paths relative to `resources/` to file text and defaults to just the
  collection."
  [dir {:keys [name path bundle description version allowed_hosts collection resources]}]
  (let [collection (or collection (collection-entity-id dir))
        resources  (or resources {"collection.yaml" (collection-file collection (str "Data App: " name))})]
    (merge
     {(format "data_apps/%s/data_app.yaml" dir)
      (str (format "name: %s\npath: %s\ncollection: %s\n" name path collection)
           (when description (format "description: %s\n" description))
           (when version (format "version: %s\n" version))
           (when (seq allowed_hosts)
             (apply str "allowed_hosts:\n" (map #(format "  - %s\n" %) allowed_hosts))))
      (format "data_apps/%s/%s" dir path) bundle}
     (update-keys resources #(format "data_apps/%s/resources/%s" dir %)))))

(defn render
  "`POST /api/apps/resources/render` as an admin, for an app whose collection has `collection-entity-id`. `queries` are
  `{:entity_id :name :query}` maps."
  [collection-entity-id queries action-ids]
  (mt/user-http-request :crowberto :post 200 "apps/resources/render"
                        {:collection {:entity_id collection-entity-id :name "Data App"}
                         :queries    queries
                         :actions    (mapv (fn [id] {:id id}) action-ids)}))

(defn resource-files
  "The files of a [[render]] response, keyed by their path relative to `resources/`."
  [response]
  (into {} (map (juxt :path :content)) (:files response)))

(defn ensure-resources!
  "Give `app` a resource collection, when it has none, and its permission group, as a repository import would.
  Returns the app's `:resource_collection_id` and `:permission_group_id`."
  [app]
  (let [app           (t2/select-one :model/DataApp :id (:id app))
        collection-id (or (:resource_collection_id app)
                          (t2/insert-returning-pk! :model/Collection {:name     (str "Data App: " (:name app))
                                                                      :location "/"}))]
    (data-app.resources/link-resources! app collection-id)
    (t2/select-one [:model/DataApp :resource_collection_id :permission_group_id] :id (:id app))))
