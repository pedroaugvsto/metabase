(ns metabase-enterprise.data-apps.db
  "Application database queries for the data-apps module. Every function here is a direct Toucan 2 call with no
  additional logic, so the rest of the module only touches `toucan2.core` for model definitions and hydration methods."
  (:require
   [metabase-enterprise.data-apps.schema :as data-apps.schema]
   [metabase.config.core :as config]
   [metabase.util :as u]
   [metabase.util.malli :as mu]
   [metabase.util.malli.schema :as ms]
   [metabase.warehouse-schema-overlay.core :as warehouse-schema-overlay]
   [toucan2.core :as t2]))

(def ^:private non-blob-columns
  "Columns to select for normal data-app metadata reads, excluding the raw bundle blob."
  [:model/DataApp :id :name :display_name :description :version :bundle_path :enabled :allowed_hosts
   :resource_collection_id :permission_group_id :table_ids
   :bundle_hash :last_synced_sha :last_synced_at :sync_error
   :created_at :updated_at])

(mu/defn non-blob-data-app
  "The DataApp with `data-app-id` without its bundle, or nil."
  [data-app-id :- ms/PositiveInt]
  (t2/select-one non-blob-columns :id data-app-id))

(mu/defn non-blob-data-app-by-slug
  "The DataApp named `slug` without its bundle, or nil."
  [slug :- :string]
  (t2/select-one non-blob-columns :name slug))

(mu/defn enabled-non-blob-data-app-by-slug
  "The enabled DataApp named `slug` without its bundle, or nil."
  [slug :- :string]
  (t2/select-one non-blob-columns :name slug :enabled true))

(mu/defn non-blob-data-apps
  "Every DataApp without its bundle, ordered by display name; only the enabled, error-free ones when `available?`."
  [available? :- [:maybe :boolean]]
  (t2/select non-blob-columns
             (cond-> {:order-by [[:display_name :asc]]}
               available? (assoc :where [:and
                                         [:= :enabled true]
                                         [:= :sync_error nil]]))))

(mu/defn data-app-bundle
  "The bundle bytes of the DataApp with `data-app-id`."
  [data-app-id :- ms/PositiveInt]
  (t2/select-one-fn :bundle :model/DataApp :id data-app-id))

(mu/defn data-apps-sync-info
  "The sync-relevant columns of every DataApp."
  []
  (t2/select [:model/DataApp :name :display_name :description :version :allowed_hosts :bundle_path
              :bundle_hash :resources_hash :sync_error]))

(defn table-database-id
  "The database ID for the Table with `table-id`, or nil."
  [table-id]
  (t2/select-one-fn :db_id :model/Table :id table-id
                    {:from [(warehouse-schema-overlay/table-query {:user-settings? false})]}))

(defn users-for-permission-warnings
  "The fields needed to calculate permission warnings for Users with `user-ids`."
  [user-ids]
  (t2/select [:model/User :id :is_superuser :is_active :tenant_id] :id [:in user-ids]))

(defn metrics-by-ids
  "The metric Cards with `metric-ids`."
  [metric-ids]
  (if (seq metric-ids)
    (t2/select :model/Card :id [:in metric-ids] :type "metric")
    []))

(mu/defn data-app-exists?
  "Whether a DataApp named `slug` exists."
  [slug :- :string]
  (t2/exists? :model/DataApp :name slug))

(mu/defn insert-data-app!
  "Insert the DataApp `row`."
  [row :- ::data-apps.schema/data-app.update]
  (t2/insert! :model/DataApp row))

(mu/defn update-data-app!
  "Apply `changes` to the DataApp with `data-app-id`."
  [data-app-id :- ms/PositiveInt
   changes     :- ::data-apps.schema/data-app.update]
  (t2/update! :model/DataApp :id data-app-id changes))

(mu/defn update-data-app-by-slug!
  "Apply `changes` to the DataApp named `slug`."
  [slug    :- :string
   changes :- ::data-apps.schema/data-app.update]
  (t2/update! :model/DataApp :name slug changes))

(mu/defn delete-data-app-by-slug!
  "Delete the DataApp named `slug`, returning the number deleted."
  [slug :- :string]
  (t2/delete! :model/DataApp :name slug))

(mu/defn delete-data-apps-not-named!
  "Delete the DataApps whose name is not one of `slugs`, returning the number deleted."
  [slugs :- [:set :string]]
  (t2/delete! :model/DataApp :name [:not-in slugs]))

(mu/defn delete-all-data-apps!
  "Delete every DataApp, returning the number deleted."
  []
  (t2/delete! :model/DataApp))

(defn resource-card-ids
  "The IDs among `card-ids` of cards in a collection linked to a data app."
  [card-ids]
  (into #{}
        (comp (partition-all 1000)
              (mapcat (fn [ids]
                        (t2/query {:select [:c.id]
                                   :from   [[:report_card :c]]
                                   :join   [[:data_app :d] [:= :d.resource_collection_id :c.collection_id]]
                                   :where  [:in :c.id ids]})))
              (map :id))
        card-ids))

(defn permission-group
  "The permission group with `group-id`, or nil."
  [group-id]
  (t2/select-one :model/PermissionsGroup :id group-id))

(defn insert-permission-group!
  "Insert a permission group and return it."
  [row]
  (t2/insert-returning-instance! :model/PermissionsGroup row))

(defn update-permission-group!
  "Apply `changes` to the permission group with `group-id`."
  [group-id changes]
  (t2/update! :model/PermissionsGroup :id group-id changes))

(defn delete-permission-group!
  "Delete the permission group with `group-id`."
  [group-id]
  (t2/delete! :model/PermissionsGroup :id group-id))

(defn data-app-group-ids
  "The IDs of permission groups owned by data apps."
  []
  (t2/select-pks-set :model/PermissionsGroup :is_data_app_group true))

(defn databases-with-legacy-permissions
  "Database IDs with legacy View Data permissions from groups not owned by apps."
  [database-ids]
  (if (seq database-ids)
    (t2/select-fn-set :db_id :model/DataPermissions
                      {:select [:p.db_id]
                       :from [[:data_permissions :p]]
                       :join [[:permissions_group :g] [:= :g.id :p.group_id]]
                       :where [:and
                               [:in :p.db_id database-ids]
                               [:= :p.perm_type "perms/view-data"]
                               [:= :p.perm_value "legacy-no-self-service"]
                               [:= :g.is_data_app_group false]]})
    #{}))

(defn resource-collection
  "The resource collection with `collection-id`, or nil."
  [collection-id]
  (t2/select-one :model/Collection :id collection-id))

(defn collection-id-by-entity-id
  "The ID of the collection with `entity-id`, or nil."
  [entity-id]
  (t2/select-one-pk :model/Collection :entity_id entity-id))

(defn collections-by-entity-ids
  "The `:id` and `:entity_id` of the collections with `entity-ids`."
  [entity-ids]
  (if (seq entity-ids)
    (t2/query {:select [:id :entity_id] :from [:collection] :where [:in :entity_id entity-ids]})
    []))

(defn cards-by-entity-ids
  "The `:entity_id` and `:collection_id` of the cards with `entity-ids`."
  [entity-ids]
  (if (seq entity-ids)
    (t2/query {:select [:entity_id :collection_id] :from [:report_card] :where [:in :entity_id entity-ids]})
    []))

(defn actions-by-entity-ids
  "The `:entity_id` of the actions with `entity-ids`, with the `:collection_id` of the model each belongs to."
  [entity-ids]
  (if (seq entity-ids)
    (t2/query {:select [:a.entity_id :c.collection_id]
               :from   [[:action :a]]
               :join   [[:report_card :c] [:= :c.id :a.model_id]]
               :where  [:in :a.entity_id entity-ids]})
    []))

(defn collection-card-ids
  "The IDs of the cards in the collection with `collection-id`, except those whose entity ID is in `keep-entity-ids`."
  [collection-id keep-entity-ids]
  (mapv :id (t2/query {:select [:id]
                       :from   [:report_card]
                       :where  (cond-> [:and [:= :collection_id collection-id]]
                                 (seq keep-entity-ids) (conj [:not-in :entity_id keep-entity-ids]))})))

(defn collection-action-ids
  "The IDs of the actions on models in the collection with `collection-id`, except those whose entity ID is in
  `keep-entity-ids`."
  [collection-id keep-entity-ids]
  (mapv :id (t2/query {:select [:a.id]
                       :from   [[:action :a]]
                       :join   [[:report_card :c] [:= :c.id :a.model_id]]
                       :where  (cond-> [:and [:= :c.collection_id collection-id]]
                                 (seq keep-entity-ids) (conj [:not-in :a.entity_id keep-entity-ids]))})))

(defn delete-cards!
  "Delete the cards with `card-ids`."
  [card-ids]
  (t2/delete! :model/Card :id [:in card-ids]))

(defn delete-actions!
  "Delete the actions with `action-ids`."
  [action-ids]
  (t2/delete! :model/Action :id [:in action-ids]))

(defn collection-dataset-queries
  "The queries of the cards in the collection with `collection-id` and of the query actions on its models."
  [collection-id]
  (let [card-queries (t2/select-fn-vec :dataset_query :model/Card :collection_id collection-id)
        model-ids    (t2/select-pks-vec :model/Card :collection_id collection-id :type "model")]
    (into card-queries
          (when (seq model-ids)
            (t2/select-fn-vec :dataset_query :model/QueryAction
                              {:select [:qa.*]
                               :from   [[:query_action :qa]]
                               :join   [[:action :a] [:= :a.id :qa.action_id]]
                               :where  [:in :a.model_id model-ids]})))))

(defn card
  "The Card with `card-id`, or nil."
  [card-id]
  (t2/select-one :model/Card :id card-id))

(defn action
  "The unarchived Action with `action-id`, or nil."
  [action-id]
  (t2/select-one :model/Action :id action-id :archived false))

(defn action-children
  "The type-specific rows of the Action with `action-id`, keyed as serdes nests them."
  [action-id]
  {:query    (t2/select :model/QueryAction :action_id action-id)
   :implicit (t2/select :model/ImplicitAction :action_id action-id)
   :http     (t2/select :model/HTTPAction :action_id action-id)})

(defn internal-user-email
  "The email of Metabase's internal user, which owns the cards and actions data apps load."
  []
  (or (t2/select-one-fn :email :model/User :id config/internal-mb-user-id)
      (throw (ex-info "The internal user is missing." {}))))

(defn delete-resource-collection!
  "Delete the resource collection with `collection-id`."
  [collection-id]
  (t2/delete! :model/Collection :id collection-id))

(defn non-router-database-ids
  "The IDs of databases that are not routed through another database."
  []
  (t2/select-pks-set :model/Database :router_database_id nil))

(defn permissions-for-paths-excluding-group
  "Permission grants for `paths`, excluding `group-id`."
  [paths group-id]
  (t2/select [:model/Permissions :group_id :object]
             :object [:in paths]
             :group_id [:not= group-id]))

(defn table-details
  "Table names and database details for `table-ids`."
  [table-ids]
  (t2/select :model/Table
             {:select [:t.id
                       [:t.display_name :name]
                       :t.schema
                       [:t.db_id :database_id]
                       [:d.name :database_name]]
              :from [(warehouse-schema-overlay/table-query {:alias :t})]
              :join [[:metabase_database :d] [:= :d.id :t.db_id]]
              :where [:in :t.id table-ids]
              :order-by [[:d.name :asc] [:t.schema :asc] [:t.display_name :asc]]}))

(defn sandboxed-user-table-access
  "Sandboxed table access from non-data-app groups for the requested users and tables."
  [user-ids table-ids]
  (t2/query {:select-distinct [[:pgm.user_id :user_id]
                               [:s.table_id :table_id]]
             :from [[:permissions_group_membership :pgm]]
             :join [[:sandboxes :s] [:= :s.group_id :pgm.group_id]
                    [:permissions_group :pg] [:= :pg.id :pgm.group_id]]
             :where [:and
                     [:in :pgm.user_id user-ids]
                     [:in :s.table_id table-ids]
                     [:not :pg.is_data_app_group]]}))

(defn unrestricted-user-table-access
  "Unrestricted table access from non-data-app groups for the requested users and tables."
  [user-ids table-ids]
  (t2/query {:select-distinct [[:pgm.user_id :user_id]
                               [:t.id :table_id]]
             :from [[:permissions_group_membership :pgm]]
             :join [[:data_permissions :dp] [:= :dp.group_id :pgm.group_id]
                    [:permissions_group :pg] [:= :pg.id :pgm.group_id]
                    (warehouse-schema-overlay/table-query {:alias :t, :user-settings? false})
                    [:and
                     [:= :t.db_id :dp.db_id]
                     [:or
                      [:= :dp.table_id nil]
                      [:= :dp.table_id :t.id]]]]
             :where [:and
                     [:in :pgm.user_id user-ids]
                     [:in :t.id table-ids]
                     [:not :pg.is_data_app_group]
                     [:= :dp.perm_type (u/qualified-name :perms/view-data)]
                     [:= :dp.perm_value "unrestricted"]]}))

(defn active-group-members
  "Active user memberships for `group-ids`, including API-key users."
  [group-ids]
  (t2/query {:select [[:pgm.group_id :group_id]
                      [:u.id :id]
                      :u.is_superuser
                      :u.email]
             :from [[:permissions_group_membership :pgm]]
             :join [[:core_user :u] [:= :u.id :pgm.user_id]]
             :where [:and
                     [:in :pgm.group_id group-ids]
                     [:= :u.is_active true]]}))
