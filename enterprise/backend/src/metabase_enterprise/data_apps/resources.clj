(ns metabase-enterprise.data-apps.resources
  "Lifecycle for the permission group a data app owns, and its link to the resource collection loaded from the
  repository (see [[metabase-enterprise.data-apps.resource-load]])."
  (:require
   [metabase-enterprise.data-apps.db :as data-apps.db]
   [metabase-enterprise.data-apps.permissions :as data-app.permissions]
   [metabase.permissions.core :as perms]
   [metabase.search.core :as search]))

(set! *warn-on-reflection* true)

(defn- group-name [app]
  (format "Data App: %s" (:name app)))

(defn- create-permission-group! [app]
  (let [group (data-apps.db/insert-permission-group! {:name (group-name app)
                                                      :is_data_app_group true})]
    (data-apps.db/update-data-app! (:id app) {:permission_group_id (:id group)})
    group))

(defn- permission-group! [app]
  (or (some->> (:permission_group_id app)
               (data-apps.db/permission-group))
      (create-permission-group! app)))

(defn- apply-collection-permissions!
  "Gives the group read access. Preserves permission grants that are already correct."
  [group collection]
  (let [read-path (perms/collection-read-path collection)
        write-path (perms/collection-readwrite-path collection)
        permissions-by-group (group-by :group_id
                                       (data-apps.db/permissions-for-paths-excluding-group
                                        ["/" read-path write-path] (:id (perms/admin-group))))
        app-read-only? (= #{read-path} (set (map :object (get permissions-by-group (:id group)))))]
    ;; Remove write grants and access from other groups
    (doseq [group-id (keys permissions-by-group)
            :when (not (and (= group-id (:id group)) app-read-only?))]
      (perms/revoke-collection-permissions! group-id collection))
    (when-not app-read-only?
      (perms/grant-collection-read-permissions! group collection))))

(defn- delete-resource-collection!
  [collection-id]
  (let [card-ids   (data-apps.db/collection-card-ids collection-id #{})
        action-ids (data-apps.db/collection-action-ids collection-id #{})]
    (data-apps.db/delete-resource-collection! collection-id)
    (when (seq action-ids)
      (search/delete! :model/Action (map str action-ids)))
    (when (seq card-ids)
      (search/delete! :model/Card (map str card-ids)))))

(defn link-resources!
  "Link `app` to the resource collection with `collection-id`, give the app's permission group read access to it and
  nothing else, and delete the collection the app was linked to before, if it changed."
  [app collection-id]
  (perms/with-global-permissions-lock
    (let [app        (data-apps.db/non-blob-data-app (:id app))
          group      (permission-group! app)
          previous   (:resource_collection_id app)]
      (data-apps.db/update-data-app! (:id app) {:resource_collection_id collection-id})
      (data-apps.db/update-permission-group! (:id group) {:name (group-name app)})
      (data-app.permissions/reconcile-app-group-permissions! (:id group) (data-apps.db/non-router-database-ids))
      (apply-collection-permissions! group (data-apps.db/resource-collection collection-id))
      (when (and previous (not= previous collection-id))
        (delete-resource-collection! previous)))))

(defn delete-resources!
  "Delete the resource collection and permission group referenced by `app`."
  [{:keys [permission_group_id resource_collection_id]}]
  (when resource_collection_id
    (delete-resource-collection! resource_collection_id))
  (when permission_group_id
    (data-apps.db/delete-permission-group! permission_group_id)))
