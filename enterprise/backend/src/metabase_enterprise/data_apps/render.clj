(ns metabase-enterprise.data-apps.render
  "Render a data app's resource files (see [[metabase-enterprise.data-apps.resource-files]]) from its source-controlled
  query and action definitions, without writing to the app DB.

  An app's viewers can read its resource collection and nothing else, so everything the app runs lives there: a
  saved question per query, a copy of each metric those questions use, a copy of each model whose actions the app
  runs, and a copy of each of those actions on the copied model. Copies get entity IDs derived from the source, so
  rendering again addresses the same copies."
  (:require
   [clojure.string :as str]
   [metabase-enterprise.data-apps.db :as data-apps.db]
   [metabase-enterprise.data-apps.resource-files :as data-app.resource-files]
   [metabase-enterprise.data-apps.resource-load :as data-app.resource-load]
   [metabase-enterprise.serialization.dump :as serialization.dump]
   [metabase.api.common :as api]
   [metabase.lib-be.core :as lib-be]
   [metabase.lib.core :as lib]
   [metabase.models.serialization :as serdes]
   [metabase.models.serialization.resolve :as resolve]
   [metabase.query-permissions.core :as query-perms]
   [metabase.util.i18n :refer [tru]]))

(set! *warn-on-reflection* true)

(defn- source-card-ids [card]
  (-> (lib-be/application-database-metadata-provider (:database_id card))
      (lib/query (:dataset_query card))
      lib/all-source-card-ids))

(defn- test-query
  "The query `query` names, with an error that names the definition when it can't be built."
  [name database-id query]
  (try
    (lib/test-query (lib-be/application-database-metadata-provider database-id) query)
    (catch Exception e
      (throw (ex-info (tru "{0}: {1}" name (ex-message e)) {:status-code 400} e)))))

(defn- resolve-query
  "Resolve an authored query definition into a query against its table, checking the current user can run it."
  [{:keys [name query]}]
  (let [{source-type :type, table-id :id} (get-in query [:stages 0 :source])
        _           (api/check-400 (= (keyword source-type) :table)
                                   (tru "{0}: data app queries must use a table source." name))
        database-id (api/check-400 (data-apps.db/table-database-id table-id)
                                   (tru "{0}: table {1} does not exist." name (str table-id)))
        resolved    (test-query name database-id query)]
    (try
      (query-perms/check-run-permissions-for-query resolved)
      (catch clojure.lang.ExceptionInfo e
        (throw (if (= 403 (:status-code (ex-data e)))
                 (ex-info (tru "You don''t have permission to run {0}." name) {:status-code 403})
                 e))))
    resolved))

(defn- referenced-metrics
  "The metrics `query` reads, refusing any whose own definition reads another card. A copy keeps pointing at whatever
  its source reads, which the app's viewers can't read."
  [query]
  (let [metrics  (data-apps.db/metrics-by-ids (lib/all-source-card-ids query))
        archived (filter :archived metrics)
        nested   (filter (comp seq source-card-ids) metrics)]
    (api/check-400 (empty? archived)
                   (tru "Data app queries cannot use metrics that are in the trash: {0}"
                        (str/join ", " (sort (map :name archived)))))
    (api/check-400 (empty? nested)
                   (tru "Data app queries cannot use metrics that reference other saved questions or metrics: {0}"
                        (str/join ", " (sort (map :name nested)))))
    metrics))

(defn- resolve-action
  "The action with `action-id` and the model it belongs to, checking the current user can read them."
  [action-id]
  (let [action (api/read-check (api/check-404 (data-apps.db/action action-id)))
        model  (api/read-check (api/check-404 (data-apps.db/card (:model_id action))))]
    (api/check-400 (#{:implicit :query} (:type action))
                   (tru "Action {0} is an HTTP action, which data apps cannot run." action-id))
    (api/check-400 (and (= :model (:type model)) (not (:archived model)))
                   (tru "Action {0} does not belong to a model." action-id))
    (api/check-400 (empty? (source-card-ids model))
                   (tru "Model {0} depends on saved questions and cannot be copied into a data app. Use a model without saved question sources or joins."
                        (:name model)))
    (when-let [dataset-query (some :dataset_query (:query (data-apps.db/action-children action-id)))]
      (api/check-400 (empty? (source-card-ids {:database_id   (:database dataset-query)
                                               :dataset_query dataset-query}))
                     (tru "Action {0} reads a saved question and cannot be copied into a data app." action-id)))
    {:action action :model model}))

(def ^:private instance-fields
  "Fields of a source card or action that describe where and how that instance is used, not what it does."
  {:collection_id       nil
   :collection_position nil
   :dashboard_id        nil
   :document_id         nil
   :archived            false
   :archived_directly   false
   :public_uuid         nil
   :made_public_by_id   nil
   :enable_embedding    false
   :embedding_params    nil
   :embedding_type      nil
   :creator_id          nil
   :created_at          nil})

(defn- card-copy [card entity-id]
  (merge card instance-fields {:entity_id entity-id}))

(defn- question [{:keys [entity_id name]} query]
  (merge instance-fields
         {:entity_id              entity_id
          :name                   name
          :type                   :question
          :display                :table
          :description            nil
          :collection_preview     true
          :card_schema            nil
          :database_id            (:database query)
          :dataset_query          (lib/prepare-for-serialization query)
          :visualization_settings {}
          :parameters             []
          :parameter_mappings     []
          :result_metadata        nil
          :dimensions             nil
          :dimension_mappings     nil}))

(defn- action-copy [action entity-id]
  (merge (select-keys action [:name :description :type :parameters :parameter_mappings :visualization_settings])
         (select-keys instance-fields [:archived :public_uuid :made_public_by_id :creator_id :created_at])
         (data-apps.db/action-children (:id action))
         {:entity_id entity-id
          :model_id  nil}))

(defn- collection [{:keys [entity_id name]}]
  {:entity_id            entity_id
   :name                 name
   :slug                 nil
   :description          nil
   :location             "/"
   :namespace            nil
   :type                 nil
   :authority_level      nil
   :archived             false
   :archived_directly    false
   :archive_operation_id nil
   :is_remote_synced     false
   :is_sample            false
   :personal_owner_id    nil
   :created_at           nil})

(defn- copy-overriding-resolver
  "Export like `base`, but export a reference to a copied card as the copy's entity ID."
  [base card-id->copy-entity-id]
  (reify resolve/SerdesExportResolver
    (export-fk [_ id model]
      (or (when (= "Card" (name model))
            (card-id->copy-entity-id id))
          (resolve/export-fk base id model)))
    (export-fk-keyed [_ id model field] (resolve/export-fk-keyed base id model field))
    (export-user [_ id] (resolve/export-user base id))
    (export-table-fk [_ id] (resolve/export-table-fk base id))
    (export-field-fk [_ id] (resolve/export-field-fk base id))))

(defn- extract
  "Serialize the `model-name` entity `instance` into the app's collection with `collection-entity-id`. The collection
  and copied models don't exist yet, so `instance` refers to neither and they're filled in here."
  [model-name collection-entity-id instance & {:keys [model-entity-id]}]
  (cond-> (serdes/extract-one model-name {} instance)
    (= model-name "Card")   (assoc :collection_id collection-entity-id)
    (= model-name "Action") (assoc :model_id model-entity-id)))

(defn render
  "Render the resource files of the app whose resource collection is `collection`, running the query definitions
  `queries` and the actions with IDs `action-ids`. Returns
  `{:files [{:path :content :model :entity_id}] :actions [{:id :entity_id}]}`, where `:path` is relative to the app's
  `resources/` directory and `:actions` maps each source action to its copy."
  [{collection-entity-id :entity_id :as collection-spec} queries action-ids]
  (let [questions  (for [query-spec queries]
                     (let [query (resolve-query query-spec)]
                       {:spec query-spec :query query :metrics (referenced-metrics query)}))
        metrics    (vals (into {} (comp (mapcat :metrics) (map (juxt :id identity))) questions))
        actions    (map resolve-action (distinct action-ids))
        models     (vals (into {} (map (juxt (comp :id :model) :model)) actions))
        copy-id    (fn [kind source]
                     (data-app.resource-files/derived-entity-id kind collection-entity-id (:entity_id source)))
        copy-ids   (merge (into {} (map (juxt :id (partial copy-id "metric"))) metrics)
                          (into {} (map (juxt :id (partial copy-id "model"))) models))
        entities   (serdes/with-cache
                     (binding [resolve/*export-resolver* (copy-overriding-resolver resolve/*export-resolver* copy-ids)]
                       (doall
                        (concat
                         [(extract "Collection" collection-entity-id (collection collection-spec))]
                         (for [{:keys [spec query]} questions]
                           (extract "Card" collection-entity-id (question spec query)))
                         (for [card (concat metrics models)]
                           (extract "Card" collection-entity-id (card-copy card (copy-ids (:id card)))))
                         (for [{:keys [action model]} actions]
                           (extract "Action" collection-entity-id (action-copy action (copy-id "action" action))
                                    :model-entity-id (copy-ids (:id model))))))))
        files      (sort-by :path (for [entity entities
                                        :let [{:keys [model id]} (last (:serdes/meta entity))
                                              content (serialization.dump/yaml-content entity)]]
                                    {:path      (data-app.resource-files/file-path entity)
                                     :content   content
                                     :model     model
                                     :entity_id id
                                     :entity    (data-app.resource-files/parse-content content)}))]
    (data-app.resource-load/validate-resources collection-entity-id files)
    {:files   (mapv #(dissoc % :entity) files)
     :actions (vec (for [{:keys [action]} actions]
                     {:id (:id action) :entity_id (copy-id "action" action)}))}))
