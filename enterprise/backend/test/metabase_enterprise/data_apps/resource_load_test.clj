(ns metabase-enterprise.data-apps.resource-load-test
  "Resources rendered by `POST /api/apps/resources/render` and loaded by a repository import."
  (:require
   [clojure.string :as str]
   [clojure.test :refer :all]
   [clojure.walk :as walk]
   [metabase-enterprise.data-apps.resource-files :as data-app.resource-files]
   [metabase-enterprise.data-apps.resource-load :as data-app.resource-load]
   [metabase-enterprise.data-apps.sync :as data-app.sync]
   [metabase-enterprise.data-apps.test-util :as data-apps.tu]
   [metabase.actions.core :as actions]
   [metabase.collections.models.collection :as collection]
   [metabase.config.core :as config]
   [metabase.lib.core :as lib]
   [metabase.lib.metadata :as lib.metadata]
   [metabase.test :as mt]
   [metabase.util.yaml :as yaml]
   [toucan2.core :as t2]))

(set! *warn-on-reflection* true)

(def ^:private collection-eid (data-apps.tu/collection-entity-id "shop"))

(def ^:private question-eid "shopQuestionListVenue")

(def ^:private metric-question-eid "shopQuestionMetricVen")

(defn- venues-query []
  {:stages [{:source {:type "table" :id (mt/id :venues)} :limit 5}]})

(defn- metric-query [metric-id]
  {:stages [{:source       {:type "table" :id (mt/id :venues)}
             :aggregations [{:type "metric" :id metric-id}]}]})

(defn- import! [resources & {:keys [collection]}]
  (data-app.sync/import-from-snapshot!
   (data-apps.tu/snapshot (data-apps.tu/app-files "shop" {:name "Shop" :path "index.js" :bundle "B"
                                                          :collection (or collection collection-eid)
                                                          :resources resources}))))

(defn- sync-error []
  (t2/select-one-fn :sync_error :model/DataApp :name "shop"))

(defn- app-collection-id []
  (t2/select-one-fn :resource_collection_id :model/DataApp :name "shop"))

(defn- card-by-eid [eid]
  (t2/select-one :model/Card :entity_id eid))

(defn- do-with-sources!
  "Call `f` with a venues metric, a venues model, and an implicit and a query action on the model, as
  `{:metric-id :model-id :implicit-id :query-action-id}`."
  [f]
  (let [mp    (mt/metadata-provider)
        query (lib/query mp (lib.metadata/table mp (mt/id :venues)))]
    (mt/with-temp [:model/Card {metric-id :id} {:name          "Venue count"
                                                :type          :metric
                                                :database_id   (mt/id)
                                                :dataset_query (lib/aggregate query (lib/count))}
                   :model/Card {model-id :id} {:name          "Venues model"
                                               :type          :model
                                               :database_id   (mt/id)
                                               :dataset_query query}]
      (f {:metric-id       metric-id
          :model-id        model-id
          :implicit-id     (actions/insert! {:name "Create venue" :type :implicit :kind :row/create
                                             :model_id model-id})
          :query-action-id (actions/insert! {:name          "Rename venue"
                                             :type          :query
                                             :model_id      model-id
                                             :database_id   (mt/id)
                                             :dataset_query (lib/native-query mp "UPDATE venues SET name = {{name}}")
                                             :parameters    [{:id "name" :slug "name" :type :string/=}]})}))))

(defn- render [metric-id action-ids]
  (data-apps.tu/render collection-eid
                       [{:entity_id question-eid :name "VenuesList" :query (venues-query)}
                        {:entity_id metric-question-eid :name "VenueCount" :query (metric-query metric-id)}]
                       action-ids))

(defn- copy-eid [kind source-id]
  (data-app.resource-files/derived-entity-id
   kind collection-eid (t2/select-one-fn :entity_id (if (= kind "action") :model/Action :model/Card) :id source-id)))

(deftest render-and-import-materializes-resources-test
  (mt/with-premium-features #{:data-apps}
    (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup :model/Card :model/Action]
      (do-with-sources!
       (fn [{:keys [metric-id model-id implicit-id query-action-id]}]
         (let [response  (render metric-id [implicit-id query-action-id])
               resources (data-apps.tu/resource-files response)]
           (testing "the response maps each source action to the entity ID of its copy"
             (is (= #{{:id implicit-id :entity_id (copy-eid "action" implicit-id)}
                      {:id query-action-id :entity_id (copy-eid "action" query-action-id)}}
                    (set (:actions response)))))
           (testing "rendering again produces the same files"
             (is (= resources (data-apps.tu/resource-files (render metric-id [implicit-id query-action-id])))))
           (testing "rendering writes nothing"
             (is (nil? (t2/select-one :model/Collection :entity_id collection-eid))))
           (is (=? {:synced 1 :changed 1} (import! resources)))
           (is (nil? (sync-error)))
           (let [collection-id (app-collection-id)
                 metric-copy   (card-by-eid (copy-eid "metric" metric-id))
                 model-copy    (card-by-eid (copy-eid "model" model-id))]
             (testing "the app is linked to the collection the files define"
               (is (= collection-id (t2/select-one-pk :model/Collection :entity_id collection-eid))))
             (testing "each query is a saved question in the collection, owned by the internal user"
               (is (=? {:type          :question
                        :name          "VenuesList"
                        :collection_id collection-id
                        :creator_id    config/internal-mb-user-id
                        :dataset_query {:stages [{:source-table (mt/id :venues) :limit 5}]}}
                       (card-by-eid question-eid))))
             (testing "a metric a query uses is copied, and the question reads the copy"
               (is (=? {:type :metric :collection_id collection-id} metric-copy))
               (is (= #{(:id metric-copy)}
                      (set (lib/all-source-card-ids
                            (lib/query (mt/metadata-provider) (:dataset_query (card-by-eid metric-question-eid))))))))
             (testing "the model behind the actions is copied, with a copy of each action on it"
               (is (=? {:type :model :collection_id collection-id} model-copy))
               (is (= {(copy-eid "action" implicit-id)     {:type :implicit :model_id (:id model-copy)}
                       (copy-eid "action" query-action-id) {:type :query :model_id (:id model-copy)}}
                      (into {}
                            (map (juxt :entity_id #(select-keys % [:type :model_id])))
                            (t2/select :model/Action :model_id (:id model-copy))))))
             (testing "the sources are untouched"
               (is (= 2 (t2/count :model/Action :model_id model-id))))
             (testing "the app records the tables its resources read"
               (is (= [(mt/id :venues)] (t2/select-one-fn :table_ids :model/DataApp :name "shop"))))
             (testing "importing the same files again changes nothing"
               (is (=? {:changed 0} (import! resources)))))))))))

(deftest import-reverts-manual-edits-test
  (mt/with-premium-features #{:data-apps}
    (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup :model/Card]
      (let [resources (data-apps.tu/resource-files
                       (data-apps.tu/render collection-eid
                                            [{:entity_id question-eid :name "VenuesList" :query (venues-query)}]
                                            []))]
        (import! resources)
        (let [collection-id (app-collection-id)
              card-id       (:id (card-by-eid question-eid))]
          (testing "edits to the collection and its cards are put back"
            (t2/update! :model/Card card-id {:name "Renamed" :display :bar})
            (t2/update! :model/Collection collection-id {:name "Renamed collection"})
            (import! resources)
            (is (nil? (sync-error)))
            (is (=? {:name "VenuesList" :display :table} (t2/select-one :model/Card card-id)))
            (is (= "Data App" (t2/select-one-fn :name :model/Collection collection-id))))
          (testing "a card trashed in Metabase comes back"
            (t2/update! :model/Card card-id {:archived true})
            (import! resources)
            (is (false? (t2/select-one-fn :archived :model/Card card-id))))
          (testing "a collection trashed in Metabase comes back with its cards"
            (mt/with-test-user :crowberto
              (collection/archive-or-unarchive-collection! (t2/select-one :model/Collection collection-id)
                                                           {:archived true}))
            (is (true? (t2/select-one-fn :archived :model/Card card-id)))
            (import! resources)
            (is (nil? (sync-error)))
            (is (false? (t2/select-one-fn :archived :model/Collection collection-id)))
            (is (false? (t2/select-one-fn :archived :model/Card card-id))))
          (testing "a card moved out of the collection is no longer the app's, so the import refuses it"
            (mt/with-temp [:model/Collection {elsewhere-id :id} {}]
              (t2/update! :model/Card card-id {:collection_id elsewhere-id})
              (import! resources)
              (is (str/includes? (sync-error) "Card shopQuestionListVenue already exists outside"))
              (is (= elsewhere-id (t2/select-one-fn :collection_id :model/Card card-id)))
              (t2/update! :model/Card card-id {:collection_id collection-id})
              (import! resources)
              (is (nil? (sync-error))))))))))

(deftest import-deletes-resources-whose-files-are-gone-test
  (mt/with-premium-features #{:data-apps}
    (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup :model/Card :model/Action]
      (do-with-sources!
       (fn [{:keys [metric-id model-id implicit-id query-action-id]}]
         (let [resources     (data-apps.tu/resource-files (render metric-id [implicit-id query-action-id]))
               file-of       (fn [eid] (some (fn [path] (when (str/includes? path eid) path)) (keys resources)))
               query-copy    (copy-eid "action" query-action-id)
               model-copy-id #(:id (card-by-eid (copy-eid "model" model-id)))]
           (import! resources)
           (let [collection-id (app-collection-id)]
             (testing "a card someone added to the collection by hand is deleted"
               (mt/with-temp [:model/Card {stray-id :id} {:collection_id collection-id}]
                 (import! resources)
                 (is (not (t2/exists? :model/Card stray-id))))))
           (testing "an action whose file is gone is deleted"
             (import! (dissoc resources (file-of query-copy)))
             (is (not (t2/exists? :model/Action :entity_id query-copy)))
             (is (t2/exists? :model/Action :entity_id (copy-eid "action" implicit-id))))
           (testing "a question whose file is gone is deleted"
             (import! (dissoc resources (file-of query-copy) (file-of question-eid)))
             (is (nil? (card-by-eid question-eid))))
           (testing "a model whose file is gone is deleted with its actions"
             (let [id (model-copy-id)]
               (import! (apply dissoc resources (keep file-of [query-copy question-eid
                                                               (copy-eid "action" implicit-id)
                                                               (copy-eid "model" model-id)])))
               (is (nil? (sync-error)))
               (is (not (t2/exists? :model/Card id)))
               (is (not (t2/exists? :model/Action :model_id id)))))))))))

(deftest changing-the-collection-replaces-the-previous-one-test
  (mt/with-premium-features #{:data-apps}
    (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup :model/Card]
      (let [render-into (fn [eid]
                          (data-apps.tu/resource-files
                           (data-apps.tu/render eid [{:entity_id question-eid :name "VenuesList" :query (venues-query)}] [])))
            other-eid   (data-apps.tu/collection-entity-id "shop2")]
        (import! (render-into collection-eid))
        (let [previous-id (app-collection-id)
              card-id     (:id (card-by-eid question-eid))]
          (import! (render-into other-eid) :collection other-eid)
          (is (nil? (sync-error)))
          (is (not= previous-id (app-collection-id)))
          (is (not (t2/exists? :model/Collection previous-id)))
          (is (= (app-collection-id) (t2/select-one-fn :collection_id :model/Card card-id))
              "the card moves to the new collection instead of being recreated"))))))

(defn- rendered-question []
  (data-apps.tu/resource-files
   (data-apps.tu/render collection-eid [{:entity_id question-eid :name "VenuesList" :query (venues-query)}] [])))

(defn- question-path [resources]
  (some #(when (str/starts-with? % "cards/") %) (keys resources)))

(defn- edit-question [resources f]
  (update resources (question-path resources) #(yaml/generate-string (f (yaml/parse-string %)))))

(deftest import-refuses-resources-outside-the-app-test
  (mt/with-premium-features #{:data-apps}
    (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup :model/Card]
      (let [resources (rendered-question)
            refused   (fn [resources message]
                        (import! resources)
                        (is (str/includes? (str (sync-error)) message))
                        (is (nil? (app-collection-id)) "nothing is loaded"))]
        (testing "a card whose entity ID belongs to a card elsewhere"
          (mt/with-temp [:model/Card {foreign-id :id} {:name "Someone else's" :entity_id question-eid}]
            (refused resources "Card shopQuestionListVenue already exists outside")
            (is (= "Someone else's" (t2/select-one-fn :name :model/Card foreign-id)))))
        (testing "a card outside the app's collection"
          (refused (edit-question resources #(assoc % :collection_id "someOtherCollection01"))
                   "must be in the collection"))
        (testing "a public card"
          (refused (edit-question resources #(assoc % :public_uuid "8e2a9c55-6b0f-4b43-a1c4-fb5c0c1b2b0a"))
                   "must not be public or embedded"))
        (testing "a card that reads a card outside the app"
          (mt/with-temp [:model/Card {other-id :id} {:dataset_query {:database (mt/id)
                                                                     :type     :query
                                                                     :query    {:source-table (mt/id :venues)}}}]
            (let [other-eid (t2/select-one-fn :entity_id :model/Card other-id)]
              (refused (edit-question resources #(update % :dataset_query assoc :stages
                                                         [{:lib/type "mbql.stage/mbql" :source-card other-eid}]))
                       (str "references Card " other-eid)))))
        (testing "a file whose model doesn't match its directory"
          (refused (edit-question resources #(assoc % :serdes/meta [{:model "Dashboard" :id question-eid}]))
                   "must hold a single Card"))
        (testing "a setting smuggled in as a resource"
          (refused (assoc resources "cards/setting.yaml"
                          (yaml/generate-string {:key "site-name" :value "Pwned"
                                                 :serdes/meta [{:model "Setting" :id "site-name"}]}))
                   "cards/setting.yaml must hold a single Card"))
        (testing "a file outside the resource layout"
          (refused (assoc resources "extra.yaml" "name: x") "extra.yaml is not a data app resource."))
        (testing "a collection other than the one data_app.yaml names"
          (import! resources :collection (data-apps.tu/collection-entity-id "other"))
          (is (str/includes? (str (sync-error)) "must hold the collection"))
          (is (nil? (app-collection-id))))))))

(deftest import-never-creates-missing-tables-test
  (mt/with-premium-features #{:data-apps}
    (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup :model/Card]
      (let [table-count (t2/count :model/Table)
            resources   (edit-question (rendered-question)
                                       #(assoc-in % [:dataset_query :stages 0 :source-table]
                                                  [(:name (mt/db)) "PUBLIC" "NO_SUCH_TABLE"]))]
        (import! resources)
        (is (str/includes? (str (sync-error)) "NO_SUCH_TABLE"))
        (is (= table-count (t2/count :model/Table)))
        (is (nil? (card-by-eid question-eid)))))))

(deftest ^:parallel derived-entity-id-test
  (testing "the CLI's offline build check derives the same ID (see `entity-ids.unit.spec.ts`)"
    (is (= "JIon8kCXvwU9wR4WtI5Aa"
           (data-app.resource-files/derived-entity-id "action" "shop-cccccccccccccccc" "sourceActionEntity01")))))

(defn- edit-file [resources path-prefix f]
  (let [path (some #(when (str/starts-with? % path-prefix) %) (sort (keys resources)))]
    (update resources path #(yaml/generate-string (f (yaml/parse-string %))))))

(deftest import-refuses-actions-that-reach-outside-the-app-test
  (mt/with-premium-features #{:data-apps}
    (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup :model/Card :model/Action]
      (do-with-sources!
       (fn [{:keys [metric-id implicit-id]}]
         (let [resources (data-apps.tu/resource-files (render metric-id [implicit-id]))
               refused   (fn [resources message]
                           (import! resources)
                           (is (str/includes? (str (sync-error)) message))
                           (is (nil? (app-collection-id)) "nothing is loaded"))]
           (testing "an implicit action carrying a query"
             (refused (edit-file resources "actions/"
                                 #(assoc % :query [{:database_id (:name (mt/db))
                                                    :dataset_query {:lib/type "mbql/query"
                                                                    :database (:name (mt/db))
                                                                    :stages   [{:lib/type "mbql.stage/native"
                                                                                :native   "DELETE FROM venues"}]}}]))
                      "must carry exactly the query rows its action type uses"))
           (testing "an action carrying an HTTP request"
             (refused (edit-file resources "actions/" #(assoc % :http [{:template {:method "GET" :url "https://example.com"}}]))
                      "must carry exactly the query rows its action type uses"))
           (testing "an HTTP action"
             (refused (edit-file resources "actions/" #(assoc % :type "http"))
                      "must be an implicit or query action"))
           (testing "an action on a model that isn't one of the app's resources"
             (refused (edit-file resources "actions/" #(assoc % :model_id "someOtherModelEntity"))
                      "must belong to a model in the app's resources"))
           (testing "an action whose parameter values come from a card"
             (refused (edit-file resources "actions/"
                                 #(assoc % :parameters [{:id "name" :type "string/=" :slug "name"
                                                         :values_source_type "card"
                                                         :values_source_config {:card_id "someOtherCardEntity01"}}]))
                      "must not take parameter values from a card"))))))))

(deftest import-refuses-a-collection-in-the-trash-or-owned-elsewhere-test
  (mt/with-premium-features #{:data-apps}
    (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup :model/Card]
      (let [resources (rendered-question)]
        (testing "a collection archived in its file"
          (import! (edit-file resources "collection.yaml" #(assoc % :archived true)))
          (is (str/includes? (str (sync-error)) "must be a plain root collection that is not remote-synced or archived"))
          (is (nil? (app-collection-id))))
        (testing "a collection that already belongs to something else"
          (mt/with-temp [:model/Collection _ {:entity_id collection-eid}]
            (import! resources)
            (is (str/includes? (str (sync-error))
                               (str "Collection " collection-eid " already exists and isn't this data app's collection")))))))))

(deftest validate-resources-names-a-malformed-file-test
  (doseq [[description entity] [["an empty file" nil]
                                ["a list" [1 2]]
                                ["a numeric type" {:type 5 :entity_id question-eid
                                                   :serdes/meta [{:model "Card" :id question-eid}]}]
                                ["a scalar query" {:type "question" :entity_id question-eid :collection_id collection-eid
                                                   :dataset_query "SELECT 1"
                                                   :serdes/meta [{:model "Card" :id question-eid}]}]]]
    (testing description
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cards/q\.yaml"
                            (data-app.resource-load/validate-resources
                             collection-eid
                             [{:path "collection.yaml" :model "Collection"
                               :entity {:entity_id collection-eid :serdes/meta [{:model "Collection" :id collection-eid}]}}
                              {:path "cards/q.yaml" :model "Card" :entity entity}]))))))

(deftest renaming-an-app-directory-keeps-its-resources-in-one-pull-test
  (mt/with-premium-features #{:data-apps}
    (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup :model/Card]
      (let [resources (rendered-question)
            files     (fn [slug]
                        (data-apps.tu/app-files slug {:name "Shop" :path "index.js" :bundle "B"
                                                      :collection collection-eid :resources resources}))]
        (data-app.sync/import-from-snapshot! (data-apps.tu/snapshot (files "shop")))
        (is (=? {:removed 1}
                (data-app.sync/import-from-snapshot! (data-apps.tu/snapshot (files "shop-renamed")))))
        (is (=? {:sync_error nil :resource_collection_id pos-int?}
                (t2/select-one :model/DataApp :name "shop-renamed")))
        (is (some? (card-by-eid question-eid)))))))

(deftest import-refuses-out-of-place-card-files-test
  (mt/with-premium-features #{:data-apps}
    (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup :model/Card]
      (let [resources (rendered-question)
            refused   (fn [resources message]
                        (import! resources)
                        (is (str/includes? (str (sync-error)) message))
                        (is (nil? (app-collection-id)) "nothing is loaded"))]
        (testing "a card that is not a question, model, or metric"
          (refused (edit-question resources #(assoc % :type "dashboard")) "must be a question, model, or metric"))
        (testing "a card on a dashboard"
          (refused (edit-question resources #(assoc % :dashboard_id "someDashboardEntity1"))
                   "must not belong to a dashboard or document"))
        (testing "the same card in two files"
          (refused (assoc resources "cards/copy.yaml" (get resources (question-path resources)))
                   (str "Card " question-eid " is defined by more than one file")))
        (testing "no collection file"
          (refused (dissoc resources "collection.yaml") "The app's resource collection is missing."))
        (testing "a file in a resource directory that isn't YAML"
          (refused (assoc resources "cards/notes.txt" "notes") "cards/notes.txt is not a YAML file."))
        (testing "a file that doesn't parse"
          (refused (assoc resources "cards/broken.yaml" "name: [unclosed") "Could not parse"))))))

(deftest import-ignores-hidden-files-test
  (mt/with-premium-features #{:data-apps}
    (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup :model/Card]
      (import! (assoc (rendered-question) "cards/.DS_Store" "not yaml"))
      (is (nil? (sync-error)))
      (is (some? (card-by-eid question-eid))))))

(deftest import-never-resolves-a-missing-field-test
  (mt/with-premium-features #{:data-apps}
    (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup :model/Card]
      (let [field-count (t2/count :model/Field)
            resources   (data-apps.tu/resource-files
                         (data-apps.tu/render collection-eid
                                              [{:entity_id question-eid
                                                :name      "VenueNames"
                                                :query     {:stages [{:source {:type "table" :id (mt/id :venues)}
                                                                      :fields [{:type "column" :name "NAME" :tableId (mt/id :venues)}]}]}}]
                                              []))]
        (import! (edit-question resources #(walk/postwalk-replace {"NAME" "NO_SUCH_FIELD"} %)))
        (is (str/includes? (str (sync-error)) "NO_SUCH_FIELD"))
        (is (= field-count (t2/count :model/Field)))
        (is (nil? (card-by-eid question-eid)))))))

(deftest a-pull-that-only-changes-resources-counts-as-a-change-test
  (mt/with-premium-features #{:data-apps}
    (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup :model/Card]
      (let [resources (rendered-question)]
        (import! resources)
        (is (=? {:changed 1} (import! (edit-question resources #(assoc % :name "Renamed")))))
        (is (= "Renamed" (:name (card-by-eid question-eid))))))))
