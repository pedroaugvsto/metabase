(ns metabase-enterprise.data-apps.resources-test
  (:require
   [clojure.test :refer :all]
   [metabase-enterprise.data-apps.test-util :as data-apps.tu]
   [metabase.collections.models.collection :as collection]
   [metabase.permissions.core :as perms]
   [metabase.sso.core :as sso]
   [metabase.test :as mt]
   [toucan2.core :as t2]))

(set! *warn-on-reflection* true)

(defn- create-data-app!
  [name]
  (t2/insert-returning-instance! :model/DataApp
                                 {:name name
                                  :display_name name
                                  :bundle_path (format "data_apps/%s/index.js" name)}))

(deftest link-resources-blocks-the-app-groups-view-data-test
  (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup]
    (let [app (create-data-app! "birds")
          {:keys [permission_group_id]} (data-apps.tu/ensure-resources! app)
          perm (fn [perm-type] (t2/select [:model/DataPermissions :table_id :perm_value]
                                          :group_id permission_group_id
                                          :db_id (mt/id)
                                          :perm_type perm-type))]
      (testing "the app group is flagged as a data-app group (its own namespace)"
        (is (true? (t2/select-one-fn :is_data_app_group :model/PermissionsGroup :id permission_group_id))))
      (testing "the app group is blocked at the database level, granting no data access of its own"
        (is (=? [{:table_id nil, :perm_value :blocked}] (perm :perms/view-data)))
        (testing "view-data :blocked cascades download-results/transforms to :no"
          (is (=? [{:table_id nil, :perm_value :no}] (perm :perms/download-results)))
          (is (=? [{:table_id nil, :perm_value :no}] (perm :perms/transforms)))))
      (testing "a manual table-level view-data grant is swept back to the database-wide block on the next sync"
        (perms/set-table-permissions! permission_group_id :perms/view-data
                                      {(mt/id :venues) :unrestricted})
        (data-apps.tu/ensure-resources! app)
        (is (=? [{:table_id nil, :perm_value :blocked}] (perm :perms/view-data))))
      (testing "a manual download-results grant that left view-data blocked is swept back too"
        (perms/set-database-permission! permission_group_id (mt/id) :perms/download-results :one-million-rows)
        (data-apps.tu/ensure-resources! app)
        (is (=? [{:table_id nil, :perm_value :no}] (perm :perms/download-results)))
        (is (=? [{:table_id nil, :perm_value :blocked}] (perm :perms/view-data)))))))

(deftest data-app-groups-hidden-from-the-groups-api-test
  (testing "GET /api/permissions/group never lists data-app groups — they are a server-managed namespace"
    (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup]
      (mt/with-temp [:model/PermissionsGroup {normal-group-id :id} {:name "Normal Group"}]
        (let [{app-group-id :permission_group_id} (data-apps.tu/ensure-resources! (create-data-app! "some-app"))
              listed-ids (into #{} (map :id) (mt/user-http-request :crowberto :get 200 "permissions/group"))]
          (is (contains? listed-ids normal-group-id) "a normal group is listed")
          (is (not (contains? listed-ids app-group-id)) "the data-app group is hidden"))))))

(deftest sso-group-sync-does-not-add-a-user-to-a-data-app-group-test
  (testing "a data app's permission group is server-managed: membership is granted by an admin, not
            an IdP claim. SSO group sync must not add a user to it, or an IdP `groups` value naming
            \"Data App: <slug>\" would hand the app's collection to whoever it names."
    (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup]
      (mt/with-temp [:model/User {user-id :id} {}]
        (let [app (create-data-app! "birds")
              {:keys [permission_group_id]} (data-apps.tu/ensure-resources! app)]
          ;; As a name-based SSO sync would, ask to put the user in the data-app group.
          (sso/sync-group-memberships! user-id [permission_group_id])
          (is (not (t2/exists? :model/PermissionsGroupMembership
                               :user_id user-id :group_id permission_group_id))
              "SSO group sync must not add a user to a data-app group"))))))

(deftest link-resources-preserves-collection-grants-test
  (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup]
    (let [app (create-data-app! "birds")
          {group-id :permission_group_id collection-id :resource_collection_id} (data-apps.tu/ensure-resources! app)
          read-path (perms/collection-read-path collection-id)
          grant (t2/select-one :model/Permissions :group_id group-id :object read-path)]
      (is (some? grant))
      (data-apps.tu/ensure-resources! app)
      ; correct grants should not be changed after sync
      (is (= grant (t2/select-one :model/Permissions :group_id group-id :object read-path))))))

(deftest link-resources-repairs-collection-grants-test
  (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup]
    (mt/with-temp [:model/PermissionsGroup {other-group-id :id} {}]
      (let [app (create-data-app! "birds")
            {group-id :permission_group_id collection-id :resource_collection_id} (data-apps.tu/ensure-resources! app)
            read-path (perms/collection-read-path collection-id)
            write-path (perms/collection-readwrite-path collection-id)]
        ; grant incorrect permission: write access to the data app collection
        (perms/grant-collection-readwrite-permissions! group-id collection-id)
        ; grant incorrect permission: let other groups read the data app collection
        (perms/grant-collection-read-permissions! other-group-id collection-id)
        (data-apps.tu/ensure-resources! app)
        ; the two bad grants should be removed after sync.
        ; only the correct grants should remain.
        (is (= [{:group_id group-id :object read-path}]
               (t2/select [:model/Permissions :group_id :object]
                          :object [:in [read-path write-path]])))))))

(deftest link-resources-restores-missing-collection-grant-test
  (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup]
    (let [app (create-data-app! "birds")
          {group-id :permission_group_id collection-id :resource_collection_id} (data-apps.tu/ensure-resources! app)]
      (perms/revoke-collection-permissions! group-id collection-id)
      (data-apps.tu/ensure-resources! app)
      ; read access should be restored after sync
      (is (t2/exists? :model/Permissions :group_id group-id :object (perms/collection-read-path collection-id))))))

(deftest link-resources-does-not-keep-grants-copied-from-the-root-collection-test
  (testing "a collection created at the root copies the root collection's grants, and linking removes them"
    (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup]
      (mt/with-temp [:model/PermissionsGroup {other-group-id :id} {}]
        (perms/grant-collection-read-permissions! other-group-id collection/root-collection)
        (let [{group-id :permission_group_id collection-id :resource_collection_id}
              (data-apps.tu/ensure-resources! (create-data-app! "birds"))]
          (is (= #{group-id}
                 (t2/select-fn-set :group_id :model/Permissions
                                   :object [:in [(perms/collection-read-path collection-id)
                                                 (perms/collection-readwrite-path collection-id)]]
                                   :group_id [:not= (:id (perms/admin-group))]))))))))
