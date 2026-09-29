(ns metabase-enterprise.data-apps.resource-load
  "Load a data app's resource files (see [[metabase-enterprise.data-apps.resource-files]]) into the app DB during a
  repository import.

  The repository owns these resources, so every import reloads them: an edit or trash made in Metabase is put back,
  and a card or action whose file is gone is deleted. Serdes load trusts what it reads (it updates whatever row carries
  an entity ID, and resolves references to any local entity), so [[validate-resources]] and [[check-ownership]] confine
  a load to the app's own collection before anything is written. That also means a card moved out of the collection
  is no longer the app's: the import refuses it rather than take it back."
  (:require
   [clojure.string :as str]
   [metabase-enterprise.data-apps.db :as data-apps.db]
   [metabase-enterprise.data-apps.resources :as data-app.resources]
   [metabase-enterprise.serialization.core :as serialization]
   [metabase.lib-be.core :as lib-be]
   [metabase.lib.core :as lib]
   [metabase.models.db :as models.db]
   [metabase.models.serialization :as serdes]
   [metabase.models.serialization.resolve :as resolve]
   [metabase.search.core :as search]
   [metabase.util.i18n :refer [tru]]))

(set! *warn-on-reflection* true)

(def ^:private external-dependency-models
  "Models a resource may reference outside the app. They must already exist: a load never creates them."
  #{"Database" "Table" "Field" "NativeQuerySnippet" "Segment" "Measure"})

(def ^:private card-types #{"question" "model" "metric"})

(def ^:private action-types #{"implicit" "query"})

(defn- leaf [path]
  (last path))

(defn- entity-leaf [entity]
  (leaf (:serdes/meta entity)))

(defn- identity-problems [{:keys [path model entity]}]
  (let [meta-path (:serdes/meta entity)
        {meta-model :model meta-id :id} (leaf meta-path)]
    (when-not (and (= 1 (count meta-path))
                   (= model meta-model)
                   (serdes/entity-id? (:entity_id entity))
                   (= meta-id (:entity_id entity)))
      [(tru "{0} must hold a single {1} whose serdes/meta ID is its entity_id." path model)])))

(defn- public-problems [{:keys [path entity]}]
  (when (or (some entity [:public_uuid :made_public_by_id :embedding_params :embedding_type])
            (:enable_embedding entity))
    [(tru "{0} must not be public or embedded." path)]))

(defn- type-name [entity]
  (let [t (:type entity)]
    (when (or (string? t) (keyword? t))
      (name t))))

(defn- action-children-problems
  "Serdes loads an action's query and HTTP rows whatever its type, so a file must carry exactly the ones its type uses."
  [{:keys [path entity]}]
  (let [counts   (update-vals (select-keys entity [:query :http]) count)
        expected (case (type-name entity)
                   "implicit" {}
                   "query"    {:query 1}
                   nil)]
    (when (and expected (not= expected (into {} (remove (comp zero? val)) counts)))
      [(tru "{0} must carry exactly the query rows its action type uses, and no HTTP request." path)])))

(defn- parameter-source-problems
  "An action's parameters can take their values from a card, which its dependencies don't include."
  [{:keys [path entity]}]
  (when (some (comp :card_id :values_source_config) (:parameters entity))
    [(tru "{0} must not take parameter values from a card." path)]))

(defn- model-problems [collection-entity-id model-entity-ids {:keys [path model entity] :as file}]
  (case model
    "Collection"
    (concat
     (when (not= collection-entity-id (:entity_id entity))
       [(tru "{0} must hold the collection {1} named in data_app.yaml." path collection-entity-id)])
     (when (or (some entity [:parent_id :personal_owner_id :namespace :authority_level :type :archive_operation_id])
               (:is_remote_synced entity)
               (:is_sample entity)
               (:archived entity))
       [(tru "{0} must be a plain root collection that is not remote-synced or archived." path)]))

    "Card"
    (concat
     (when (not= collection-entity-id (:collection_id entity))
       [(tru "{0} must be in the collection {1}." path collection-entity-id)])
     (when-not (card-types (type-name entity))
       [(tru "{0} must be a question, model, or metric." path)])
     (when-not (map? (:dataset_query entity))
       [(tru "{0} must hold a query." path)])
     (when (some entity [:dashboard_id :document_id])
       [(tru "{0} must not belong to a dashboard or document." path)])
     (public-problems file))

    "Action"
    (concat
     (when-not (action-types (type-name entity))
       [(tru "{0} must be an implicit or query action." path)])
     (when-not (contains? model-entity-ids (:model_id entity))
       [(tru "{0} must belong to a model in the app''s resources." path)])
     (action-children-problems file)
     (parameter-source-problems file)
     (public-problems file))))

(defn- dependencies
  "The serdes dependencies of the entity in `file`, or a problem naming the file when they can't be read."
  [{:keys [path entity]}]
  (try
    (serdes/deserialization-dependencies entity)
    (catch Exception e
      (throw (ex-info (tru "{0} is malformed: {1}" path (ex-message e)) {:status-code 400} e)))))

(defn- dependency-problems
  [collection-entity-id card-entity-ids {:keys [path] :as file}]
  (for [dependency (dependencies file)
        :let [{:keys [model id]} (leaf dependency)]
        :when (case model
                "Collection" (not= id collection-entity-id)
                "Card"       (not (contains? card-entity-ids id))
                (not (contains? external-dependency-models model)))]
    (tru "{0} references {1} {2}, which is not one of the app''s resources." path model id)))

(defn- duplicate-problems [resources]
  (for [[[model entity-id] files] (group-by (juxt :model (comp :entity_id :entity)) resources)
        :when (< 1 (count files))]
    (tru "{0} {1} is defined by more than one file: {2}." model entity-id (str/join ", " (map :path files)))))

(defn- throw-problems! [problems]
  (when (seq problems)
    (throw (ex-info (str/join " " (distinct problems)) {:status-code 400}))))

(defn validate-resources
  "Throw unless `resources` (as read by [[metabase-enterprise.data-apps.resource-files/read-resources]]) are exactly
  one collection with the entity ID `collection-entity-id`, plus cards in it and actions on its models, referencing
  nothing else of Metabase's but databases, tables, fields, snippets, segments, and measures."
  [collection-entity-id resources]
  (let [collections      (filter (comp #{"Collection"} :model) resources)
        cards            (filter (comp #{"Card"} :model) resources)
        card-entity-ids  (into #{} (map (comp :entity_id :entity)) cards)
        model-entity-ids (into #{}
                               (comp (map :entity)
                                     (filter (comp #{"model"} type-name))
                                     (map :entity_id))
                               cards)]
    (throw-problems!
     (concat
      (when (empty? collections)
        [(tru "The app''s resource collection is missing. Run `npm run sync-resources` and commit its output.")])
      (duplicate-problems resources)
      (mapcat identity-problems resources)))
    (throw-problems!
     (concat
      (mapcat (partial model-problems collection-entity-id model-entity-ids) resources)
      (mapcat (partial dependency-problems collection-entity-id card-entity-ids) resources)))))

(defn- external-dependency-problems [resources]
  (for [dependency (into #{} (mapcat dependencies) resources)
        :let [{:keys [model id]} (leaf dependency)]
        :when (and (contains? external-dependency-models model)
                   (nil? (serdes/load-find-local dependency)))]
    (tru "{0} {1} does not exist on this instance." model id)))

(defn- owned-by-app? [app-collection-id collection-id]
  (and (some? app-collection-id) (= app-collection-id collection-id)))

(defn- ownership-problems
  [{app-collection-id :resource_collection_id} resources]
  (let [entity-ids-of (fn [model]
                        (into [] (comp (filter (comp #{model} :model)) (map (comp :entity_id :entity))) resources))
        foreign       (fn [model rows]
                        (for [{:keys [entity_id collection_id]} rows
                              :when (not (owned-by-app? app-collection-id collection_id))]
                          (tru "{0} {1} already exists outside this data app''s collection, so the app can''t load it. Move it back if it belongs to this app."
                               model entity_id)))]
    (concat
     (for [{:keys [id entity_id]} (data-apps.db/collections-by-entity-ids (entity-ids-of "Collection"))
           :when (not (owned-by-app? app-collection-id id))]
       (tru "Collection {0} already exists and isn''t this data app''s collection. Give the app a new collection by removing \"collection\" from data_app.yaml and running `npm run sync-resources`."
            entity_id))
     (foreign "Card" (data-apps.db/cards-by-entity-ids (entity-ids-of "Card")))
     (foreign "Action" (data-apps.db/actions-by-entity-ids (entity-ids-of "Action"))))))

(defn- check-ownership
  "Throw unless every entity `resources` would update already belongs to `app`, and every entity they reference outside
  the app exists."
  [app resources]
  (throw-problems! (concat (ownership-problems app resources)
                           (external-dependency-problems resources))))

(defn- ingestable [entities]
  (let [by-path (into {} (map (juxt (comp serialization/strip-labels serdes/path) identity)) entities)]
    (reify serialization/Ingestable
      (ingest-list [_] (keys by-path))
      (ingest-one [_ path] (get by-path (serialization/strip-labels path)))
      (ingest-errors [_] []))))

(defn- strict-table-id [[db-name schema table-name :as path]]
  (let [database-id (models.db/database-id-by-name db-name)]
    (or (when database-id
          (models.db/table-id-by-name table-name schema database-id))
        (throw (ex-info (tru "Table {0} does not exist on this instance." (pr-str path))
                        {:status-code 400})))))

(defn- strict-field-id [table-id [_ _ _ & field-names :as path]]
  (or (models.db/field-pk-in-path table-id (reverse field-names))
      (throw (ex-info (tru "Field {0} does not exist on this instance." (pr-str path))
                      {:status-code 400}))))

(defn- strict-import-resolver
  "Resolve like `base`, but throw for a table or field that doesn't exist rather than creating an inactive one."
  [base]
  (let [table-id (memoize strict-table-id)
        field-id (memoize (fn [[db-name schema table-name :as path]]
                            (strict-field-id (table-id [db-name schema table-name]) path)))]
    (reify resolve/SerdesImportResolver
      (import-fk [_ eid model] (resolve/import-fk base eid model))
      (import-fk-keyed [_ portable model field] (resolve/import-fk-keyed base portable model field))
      (import-user [_ email] (resolve/import-user base email))
      (import-table-fk [_ path] (when path (table-id path)))
      (import-field-fk [_ path] (when path (field-id path))))))

(defn- load-entities! [entities]
  (let [creator (data-apps.db/internal-user-email)
        entities (for [entity entities]
                   (cond-> entity
                     (#{"Card" "Action"} (:model (entity-leaf entity))) (assoc :creator_id creator)))]
    (try
      (serdes/with-cache
        (binding [resolve/*import-resolver* (strict-import-resolver resolve/*import-resolver*)]
          (serialization/load-metabase! (ingestable entities) :reindex? false)))
      (catch Exception e
        (throw (ex-info (str/join ": " (distinct (keep ex-message (take-while some? (iterate ex-cause e)))))
                        {:status-code 400}
                        e))))))

(defn- prune!
  "Delete the cards in the collection with `collection-id`, and the actions on its models, that `resources` no longer
  define."
  [collection-id resources]
  (let [entity-ids (fn [model] (into #{} (comp (filter (comp #{model} :model)) (map (comp :entity_id :entity))) resources))
        action-ids (data-apps.db/collection-action-ids collection-id (entity-ids "Action"))
        card-ids   (data-apps.db/collection-card-ids collection-id (entity-ids "Card"))]
    (when (seq action-ids)
      (data-apps.db/delete-actions! action-ids)
      (search/delete! :model/Action (map str action-ids)))
    (when (seq card-ids)
      (data-apps.db/delete-cards! card-ids)
      (search/delete! :model/Card (map str card-ids)))))

(defn- query-table-ids [dataset-query]
  (let [query (lib/query (lib-be/application-database-metadata-provider (:database dataset-query)) dataset-query)]
    (into (set (lib/all-source-table-ids query))
          (lib/all-implicitly-joined-table-ids query))))

(defn- collection-table-ids
  "The tables the cards in the collection with `collection-id`, and the query actions on its models, read."
  [collection-id]
  (->> (data-apps.db/collection-dataset-queries collection-id)
       (into #{} (mapcat query-table-ids))
       sort
       vec))

(defn load-resources!
  "Load `resources` (as read and validated by [[metabase-enterprise.data-apps.resource-files/read-resources]] and
  [[validate-resources]]) for `app`, link its resource collection, delete the resources that are gone, and record the
  tables they read. Must run inside the app's sync transaction, so a failure leaves the app's previous resources in
  place."
  [app collection-entity-id resources]
  (check-ownership app resources)
  (load-entities! (map :entity resources))
  (let [collection-id (data-apps.db/collection-id-by-entity-id collection-entity-id)]
    (data-app.resources/link-resources! app collection-id)
    (prune! collection-id resources)
    (data-apps.db/update-data-app! (:id app) {:table_ids (collection-table-ids collection-id)})))
