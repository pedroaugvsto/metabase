(ns metabase-enterprise.data-apps.api-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer :all]
   [metabase-enterprise.data-apps.config :as data-app.config]
   [metabase-enterprise.data-apps.query-definition :as query-definition]
   [metabase-enterprise.data-apps.resource-files :as data-app.resource-files]
   [metabase-enterprise.data-apps.sync :as data-app.sync]
   [metabase-enterprise.data-apps.test-util :as data-apps.tu]
   [metabase-enterprise.data-apps.user-access :as data-app.user-access]
   [metabase.actions.core :as actions]
   [metabase.api.macros.defendpoint.closed-schemas :as closed-schemas]
   [metabase.lib.core :as lib]
   [metabase.lib.metadata :as lib.metadata]
   [metabase.permissions.core :as perms]
   [metabase.test :as mt]
   [metabase.util.json :as json]
   [metabase.util.yaml :as yaml]
   [toucan2.core :as t2]))

(set! *warn-on-reflection* true)

;;; ---------------------------------------------- Helpers ----------------------------------------------

(defn- create-app! []
  (t2/insert! :model/DataApp
              :name         "demo"
              :display_name "Demo"
              :bundle_path  "data_apps/demo/index.js"
              :bundle       (.getBytes "BUNDLE" "UTF-8")
              :bundle_hash  "abc123"))

(def ^:private snapshot data-apps.tu/snapshot)

(def ^:private app-files data-apps.tu/app-files)

;;; ---------------------------------------------- Permissions ----------------------------------------------

(deftest data-app-access-requires-read-access-to-its-resource-collection-test
  ;; global mode so the `:data-apps` premium feature is visible to the real-HTTP
  ;; `user-real-request` calls below (which run on Jetty threads that don't inherit
  ;; a thread-local `binding`).
  (mt/test-helpers-set-global-values!
    (mt/with-premium-features #{:data-apps}
      (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup]
        (create-app!)
        (let [app (t2/select-one :model/DataApp :name "demo")
              {:keys [permission_group_id]} (data-apps.tu/ensure-resources! app)]
          (testing "a non-member cannot open a data app or load its bundle"
            (is (= [{:name "demo" :display_name "Demo"}]
                   (mt/user-http-request :rasta :get 200 "apps")))
            (is (= "You don't have permissions to do that."
                   (mt/user-http-request :rasta :get 403 "apps/demo")))
            (is (= "You don't have permissions to do that."
                   (mt/user-http-request :rasta :get 403 "apps/demo/bundle"))))
          (testing "a member can open a data app"
            (perms/add-user-to-group! (mt/user->id :rasta) permission_group_id)
            (is (= {:name "demo" :display_name "Demo"}
                   (mt/user-http-request :rasta :get 200 "apps/demo")))
            (is (str/includes?
                 (str (mt/user-real-request :rasta :get 200 "apps/demo/bundle"))
                 "BUNDLE"))))
        (testing "a non-superuser is still forbidden from managing data apps"
          (is (= "You don't have permissions to do that."
                 (mt/user-http-request :rasta :get 403 "apps/repo-status")))
          (is (= "You don't have permissions to do that."
                 (mt/user-http-request :rasta :put 403 "apps/demo" {:enabled false}))))))))

(deftest data-app-without-a-resource-collection-is-not-published-test
  (mt/test-helpers-set-global-values!
    (mt/with-premium-features #{:data-apps}
      (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup]
        (create-app!)
        (is (nil? (t2/select-one-fn :resource_collection_id :model/DataApp :name "demo"))
            "precondition: the app has no resource collection")
        (testing "an app with no resource collection is not published: neither its metadata nor its
                  bundle is served — to anyone — and a 409 lets the client show a \"not published\" screen"
          (doseq [user [:rasta :crowberto]]
            (is (= "This data app has not been published yet."
                   (mt/user-http-request user :get 409 "apps/demo")))
            (is (= "This data app has not been published yet."
                   (mt/user-http-request user :get 409 "apps/demo/bundle")))))))))

(deftest superuser-can-manage-and-view-test
  (mt/test-helpers-set-global-values!
    (mt/with-premium-features #{:data-apps}
      (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup]
        (create-app!)
        (let [app (t2/select-one :model/DataApp :name "demo")
              {:keys [resource_collection_id]} (data-apps.tu/ensure-resources! app)]
          (is (not (some #(= resource_collection_id (:id %))
                         (mt/user-http-request :rasta :get 200 "collection")))))
        (testing "a superuser can list, read metadata, and serve the bundle"
          (is (=? [{:name "demo" :display_name "Demo"}]
                  (mt/user-http-request :crowberto :get 200 "apps")))
          (is (=? {:name "demo"
                   :resource_collection_id pos-int?
                   :permission_group_id pos-int?}
                  (mt/user-http-request :crowberto :get 200 "apps/demo")))
          (is (str/includes?
               (str (mt/user-real-request :crowberto :get 200 "apps/demo/bundle"))
               "BUNDLE")))))))

(deftest deleting-a-data-app-removes-its-permission-group-test
  (testing "removing a data app through the admin API — clearing out one left behind after its
            repo was disconnected or a remote-sync branch switch — deletes its server-managed
            permission group and resource collection along with the row"
    (mt/with-premium-features #{:data-apps}
      (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup]
        (create-app!)
        (let [app (t2/select-one :model/DataApp :name "demo")
              {:keys [permission_group_id resource_collection_id]}
              (data-apps.tu/ensure-resources! app)]
          (is (t2/exists? :model/PermissionsGroup :id permission_group_id)
              "precondition: the app has a permission group")
          (mt/user-http-request :crowberto :delete 204 "apps/demo")
          (is (not (t2/exists? :model/DataApp :id (:id app)))
              "the app row is gone")
          (is (not (t2/exists? :model/PermissionsGroup :id permission_group_id))
              "its permission group is removed too")
          (is (not (t2/exists? :model/Collection :id resource_collection_id))
              "and so is its resource collection"))))))

(deftest ^:parallel query-definition-request-schema-is-closed-test
  (is (empty? (closed-schemas/findings ::query-definition/query-definition))))

;;; ---------------------------------------------- Rendering resources ----------------------------------------------

(def ^:private render-collection-eid (data-apps.tu/collection-entity-id "demo"))

(def ^:private render-question-eid "demoQuestion000000000")

(defn- render-request [queries action-ids]
  {:collection {:entity_id render-collection-eid :name "Data App: Demo"}
   :queries    queries
   :actions    (mapv (fn [id] {:id id}) action-ids)})

(defn- render-query [user status query]
  (mt/user-http-request user :post status "apps/resources/render"
                        (render-request [{:entity_id render-question-eid :name "Question" :query query}] [])))

(defn- rendered-card [response entity-id]
  (some (fn [{:keys [model content] :as file}]
          (when (and (= "Card" model) (= entity-id (:entity_id file)))
            (yaml/parse-string content)))
        (:files response)))

(defn- rendered-question [query]
  (:dataset_query (rendered-card (render-query :crowberto 200 query) render-question-eid)))

(defn- field-path [table field]
  [(:name (mt/db)) "PUBLIC" table field])

(deftest render-returns-the-collection-and-a-question-per-query-test
  (mt/with-premium-features #{:data-apps}
    (let [response (render-query :crowberto 200
                                 {:stages [{:source {:type "table" :id (mt/id :venues) :name "Venues"
                                                     :fields {:price {:type "column" :name "PRICE" :jsType "number"}}
                                                     :segments {} :measures {}
                                                     :columns [{:name "PRICE" :jsType "number"}]}
                                            :orderBys [{:type "column" :name "PRICE" :tableId (mt/id :venues)
                                                        :fieldId (mt/id :venues :price) :baseType "type/Integer"
                                                        :effectiveType "type/Integer" :defaultTemporalBucket nil
                                                        :direction "asc" :jsType "number" :description "Price tier"}]
                                            :filters [{:type "operator" :operator ">"
                                                       :args [{:type "column" :name "PRICE" :description "Price tier"}
                                                              {:type "literal" :value 2}]}]
                                            :limit 5}]})]
      (is (= [{:path "cards/question_demoQuestion000000000.yaml" :model "Card" :entity_id render-question-eid}
              {:path "collection.yaml" :model "Collection" :entity_id render-collection-eid}]
             (map #(dissoc % :content) (:files response))))
      (is (=? {:name          "Question"
               :type          "question"
               :collection_id render-collection-eid
               :dataset_query {:database (:name (mt/db))
                               :stages   [{:source-table [(:name (mt/db)) "PUBLIC" "VENUES"]
                                           :filters      [[">" {} ["field" {} (field-path "VENUES" "PRICE")] 2]]
                                           :order-by     [["asc" {} ["field" {} (field-path "VENUES" "PRICE")]]]
                                           :limit        5}]}}
              (rendered-card response render-question-eid)))
      (is (=? {:name "Data App: Demo" :entity_id render-collection-eid}
              (yaml/parse-string (:content (second (:files response))))))
      (is (= [] (:actions response))))))

(deftest render-normalizes-nested-binning-test
  (mt/with-premium-features #{:data-apps}
    (doseq [[field-name binning]
            [["PRICE" {:strategy "num-bins" :num-bins 10}]
             ["LATITUDE" {:strategy "bin-width" :bin-width 20.0}]]]
      (let [column {:type "column" :name field-name :description "A numeric field" :binning binning}]
        (is (=? {:stages [{:breakout [["field" {:binning binning} (field-path "VENUES" field-name)]]
                           :order-by [["asc" {} ["field" {:binning binning} (field-path "VENUES" field-name)]]]}]}
                (rendered-question {:stages [{:source {:type "table" :id (mt/id :venues)}
                                              :aggregations [{:type "operator" :operator "count" :args []}]
                                              :breakouts [column]
                                              :orderBys [(assoc column :direction "asc")]}]})))))))

(deftest render-rejects-unknown-nested-binning-options-test
  (mt/with-premium-features #{:data-apps}
    (is (= {:errors {:stages [{:breakouts [{:binning {:unexpected ["disallowed key"]}}]}]}}
           (render-query :crowberto 400
                         {:stages [{:source {:type "table" :id (mt/id :venues)}
                                    :breakouts [{:type "column" :name "PRICE"
                                                 :binning {:strategy "num-bins" :num-bins 10 :unexpected true}}]}]})))))

(deftest render-resolves-an-aggregation-with-sdk-metadata-test
  (mt/with-premium-features #{:data-apps}
    (is (=? {:stages [{:aggregation [["count" {}]]}]}
            (rendered-question {:stages [{:source {:type "table" :id (mt/id :venues)}
                                          :aggregations [{:type "operator"
                                                          :operator "count"
                                                          :args []
                                                          :columns [{:name "count"
                                                                     :displayName "Count"
                                                                     :jsType "number"}]}]}]})))))

(deftest render-ignores-the-enabled-option-test
  (mt/with-premium-features #{:data-apps}
    (is (=? {:stages [{:source-table [(:name (mt/db)) "PUBLIC" "VENUES"]}]}
            (rendered-question {:stages [{:source {:type "table" :id (mt/id :venues)}
                                          :enabled false}]})))))

(deftest render-rejects-unsupported-query-fields-test
  (mt/with-premium-features #{:data-apps}
    (doseq [[path expected-errors]
            [[[:unexpected] {:unexpected ["disallowed key"]}]
             [[:stages 0 :joins] {:stages [{:joins ["disallowed key"]}]}]
             [[:stages 0 :expressions] {:stages [{:expressions ["disallowed key"]}]}]
             [[:stages 0 :source :unexpected] {:stages [{:source {:unexpected ["disallowed key"]}}]}]
             [[:stages 0 :fields 0 :unexpected] {:stages [{:fields [{:unexpected ["disallowed key"]}]}]}]]]
      (testing (str "Reject unsupported fields at " path)
        (let [query {:stages [{:source {:type "table" :id (mt/id :venues)}
                               :fields [{:type "column" :name "PRICE"}]}]}]
          (is (= {:errors expected-errors}
                 (render-query :crowberto 400 (assoc-in query path [])))))))))

(deftest render-requires-a-valid-table-source-test
  (mt/with-premium-features #{:data-apps}
    (testing "a card source is refused"
      (is (some? (render-query :crowberto 400 {:stages [{:source {:type "card" :id 1}}]}))))
    (testing "an invalid source type is refused"
      (is (some? (render-query :crowberto 400 {:stages [{:source {:type 1 :id (mt/id :venues)}}]}))))
    (testing "a missing table is reported against the definition that names it"
      (is (= "Question: table 2147483647 does not exist."
             (render-query :crowberto 400 {:stages [{:source {:type "table" :id Integer/MAX_VALUE}}]}))))))

(deftest render-copies-the-metrics-a-query-uses-test
  (mt/with-premium-features #{:data-apps}
    (let [metadata-provider (mt/metadata-provider)
          venue-count-query (-> (lib/query metadata-provider (lib.metadata/table metadata-provider (mt/id :venues)))
                                (lib/aggregate (lib/count)))]
      (mt/with-temp [:model/Card {metric-id :id metric-eid :entity_id} {:name          "Venue count"
                                                                        :type          :metric
                                                                        :database_id   (mt/id)
                                                                        :dataset_query venue-count-query}]
        (let [response (render-query :crowberto 200
                                     {:stages [{:source {:type "table" :id (mt/id :venues)}
                                                :aggregations [{:type "metric" :id metric-id :name "Venue count"
                                                                :databaseId (mt/id) :sourceTableId (mt/id :venues)
                                                                :sourceCardId nil :mappedTableIds [(mt/id :venues)]
                                                                :columns {:count {:type "column" :name "count" :jsType "number"}}
                                                                :dimensions {}}]}]})
              copy-eid (data-app.resource-files/derived-entity-id "metric" render-collection-eid metric-eid)]
          (is (=? {:name "Venue count" :type "metric" :collection_id render-collection-eid}
                  (rendered-card response copy-eid)))
          (is (=? {:stages [{:aggregation [["metric" {} copy-eid]]}]}
                  (:dataset_query (rendered-card response render-question-eid)))
              "the question reads the copy, not the source metric"))))))

(deftest render-rejects-metrics-whose-definition-reads-another-card-test
  (testing "a metric copy keeps reading whatever its source reads, which the app's viewers can't, so rendering refuses it"
    (mt/with-premium-features #{:data-apps}
      (let [metadata-provider (mt/metadata-provider)
            venues            (lib.metadata/table metadata-provider (mt/id :venues))
            venue-count-query (-> (lib/query metadata-provider venues)
                                  (lib/aggregate (lib/count)))
            orders-query      (lib/query metadata-provider
                                         (lib.metadata/table metadata-provider (mt/id :orders)))
            app-query         (fn [metric-id]
                                {:stages [{:source       {:type "table" :id (mt/id :venues)}
                                           :aggregations [{:type "metric" :id metric-id}]}]})]
        (mt/with-temp [:model/Card {inner-metric-id :id}
                       {:name          "Venue count"
                        :type          :metric
                        :database_id   (mt/id)
                        :dataset_query venue-count-query}
                       :model/Card {question-id :id}
                       {:name          "Orders"
                        :type          :question
                        :database_id   (mt/id)
                        :dataset_query orders-query}]
          (let [provider               (mt/metadata-provider)
                nested-query           (lib/test-query provider (app-query inner-metric-id))
                question-sourced-query (lib/query provider (lib.metadata/card provider question-id))]
            (testing "the inner metric on its own renders"
              (is (some? (render-query :crowberto 200 (app-query inner-metric-id)))))
            (mt/with-temp [:model/Card {nested-metric-id :id}
                           {:name          "Nested venue count"
                            :type          :metric
                            :database_id   (mt/id)
                            :dataset_query nested-query}
                           :model/Card {question-metric-id :id}
                           {:name          "Orders question metric"
                            :type          :metric
                            :database_id   (mt/id)
                            :dataset_query question-sourced-query}]
              (testing "a metric that references another metric is refused"
                (is (= (str "Data app queries cannot use metrics that reference other saved questions"
                            " or metrics: Nested venue count")
                       (render-query :crowberto 400 (app-query nested-metric-id)))))
              (testing "so is one built on a saved question"
                (is (= (str "Data app queries cannot use metrics that reference other saved questions"
                            " or metrics: Orders question metric")
                       (render-query :crowberto 400 (app-query question-metric-id))))))))))))

(deftest render-rejects-trashed-metrics-test
  (mt/with-premium-features #{:data-apps}
    (let [metadata-provider (mt/metadata-provider)
          venue-count-query (-> (lib/query metadata-provider (lib.metadata/table metadata-provider (mt/id :venues)))
                                (lib/aggregate (lib/count)))]
      (mt/with-temp [:model/Card {metric-id :id} {:name          "Trashed venue count"
                                                  :type          :metric
                                                  :archived      true
                                                  :database_id   (mt/id)
                                                  :dataset_query venue-count-query}]
        (is (= "Data app queries cannot use metrics that are in the trash: Trashed venue count"
               (render-query :crowberto 400 {:stages [{:source       {:type "table" :id (mt/id :venues)}
                                                       :aggregations [{:type "metric" :id metric-id}]}]})))))))

(deftest render-rejects-query-actions-that-read-a-saved-question-test
  (mt/with-premium-features #{:data-apps}
    (let [metadata-provider (mt/metadata-provider)
          venues-query      (lib/query metadata-provider (lib.metadata/table metadata-provider (mt/id :venues)))]
      (mt/with-temp [:model/Card {model-id :id} {:type :model :database_id (mt/id) :dataset_query venues-query}
                     :model/Card {question-id :id} {:type :question :database_id (mt/id) :dataset_query venues-query}]
        (let [action-id (actions/insert!
                         {:name          "Reads a question"
                          :type          :query
                          :model_id      model-id
                          :database_id   (mt/id)
                          :dataset_query (lib/native-query
                                          (mt/metadata-provider)
                                          (str "SELECT * FROM {{#" question-id "-venues}}"))})]
          (try
            (is (= (format "Action %d reads a saved question and cannot be copied into a data app." action-id)
                   (mt/user-http-request :crowberto :post 400 "apps/resources/render" (render-request [] [action-id]))))
            (finally
              (t2/delete! :model/Action :id action-id))))))))

(deftest render-rejects-actions-whose-model-is-gone-test
  (mt/with-premium-features #{:data-apps}
    (let [metadata-provider (mt/metadata-provider)
          venues-query      (lib/query metadata-provider (lib.metadata/table metadata-provider (mt/id :venues)))]
      (doseq [[description changes status message] [["a model in the trash" {:archived true}
                                                     400 "Action %d does not belong to a model."]
                                                    ["a model turned back into a question, which archives its actions"
                                                     {:type :question} 404 "Not found."]]]
        (testing description
          (mt/with-temp [:model/Card {model-id :id} {:type :model :database_id (mt/id) :dataset_query venues-query}]
            (let [action-id (actions/insert! {:name "Create venue" :type :implicit :kind :row/create :model_id model-id})]
              (try
                (t2/update! :model/Card model-id changes)
                (is (= (format message action-id)
                       (mt/user-http-request :crowberto :post status "apps/resources/render"
                                             (render-request [] [action-id]))))
                (finally
                  (t2/delete! :model/Action :id action-id))))))))))

(deftest render-checks-the-caller-can-run-each-query-test
  (mt/with-premium-features #{:data-apps}
    (testing "a non-admin who can query the table renders its queries"
      (is (some? (render-query :rasta 200 {:stages [{:source {:type "table" :id (mt/id :venues)}}]}))))
    (testing "one who can't is refused, without echoing the query or their permissions"
      (mt/with-no-data-perms-for-all-users!
        (is (= "You don't have permission to run Question."
               (render-query :rasta 403 {:stages [{:source {:type "table" :id (mt/id :venues)}}]})))))))

(deftest render-copies-actions-onto-a-copy-of-their-model-test
  (mt/with-premium-features #{:data-apps}
    (let [metadata-provider (mt/metadata-provider)
          venues-query      (lib/query metadata-provider (lib.metadata/table metadata-provider (mt/id :venues)))]
      (mt/with-temp [:model/Collection {private-collection-id :id} {}
                     :model/Card {model-id :id model-eid :entity_id} {:type          :model
                                                                      :name          "Venues model"
                                                                      :database_id   (mt/id)
                                                                      :dataset_query venues-query}
                     :model/Card {private-model-id :id} {:type          :model
                                                         :collection_id private-collection-id
                                                         :database_id   (mt/id)
                                                         :dataset_query venues-query}
                     :model/Card {question-id :id} {:type          :question
                                                    :database_id   (mt/id)
                                                    :dataset_query venues-query}]
        (let [question-model-id (t2/insert-returning-pk! :model/Card
                                                         {:type          :model
                                                          :name          "Built on a question"
                                                          :database_id   (mt/id)
                                                          :creator_id    (mt/user->id :crowberto)
                                                          :display       :table
                                                          :visualization_settings {}
                                                          :dataset_query (lib/query metadata-provider
                                                                                    (lib.metadata/card metadata-provider question-id))})
              implicit-id (actions/insert! {:name "Create venue" :type :implicit :kind :row/create :model_id model-id})
              http-id     (actions/insert! {:name "Webhook" :type :http :model_id model-id
                                            :template {:method "GET" :url "https://example.com"}})
              nested-id   (actions/insert! {:name "Create" :type :implicit :kind :row/create :model_id question-model-id})
              private-id  (actions/insert! {:name "Create" :type :implicit :kind :row/create :model_id private-model-id})]
          (try
            (let [response  (mt/user-http-request :crowberto :post 200 "apps/resources/render"
                                                  (render-request [] [implicit-id]))
                  model-copy (data-app.resource-files/derived-entity-id "model" render-collection-eid model-eid)
                  action-copy (data-app.resource-files/derived-entity-id
                               "action" render-collection-eid (t2/select-one-fn :entity_id :model/Action implicit-id))]
              (is (= [{:id implicit-id :entity_id action-copy}] (:actions response)))
              (is (=? {:name "Venues model" :type "model" :collection_id render-collection-eid}
                      (rendered-card response model-copy)))
              (is (=? {:name "Create venue" :type "implicit" :model_id model-copy :implicit [{:kind "row/create"}]}
                      (some #(when (= action-copy (:entity_id %)) (yaml/parse-string (:content %)))
                            (:files response)))))
            (testing "an HTTP action is refused"
              (is (= (format "Action %d is an HTTP action, which data apps cannot run." http-id)
                     (mt/user-http-request :crowberto :post 400 "apps/resources/render" (render-request [] [http-id])))))
            (testing "an action on a model built on a saved question is refused"
              (is (str/includes? (mt/user-http-request :crowberto :post 400 "apps/resources/render"
                                                       (render-request [] [nested-id]))
                                 "depends on saved questions")))
            (testing "an action whose model the caller can't read is refused"
              (mt/with-non-admin-groups-no-collection-perms private-collection-id
                (is (= "You don't have permissions to do that."
                       (mt/user-http-request :rasta :post 403 "apps/resources/render" (render-request [] [private-id]))))))
            (finally
              (t2/delete! :model/Card :id question-model-id))))))))

(deftest user-permission-warnings-test
  (mt/with-premium-features #{:data-apps :advanced-permissions :sandboxes}
    (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup :model/Sandbox]
      (mt/with-no-data-perms-for-all-users!
        (create-app!)
        (let [{app-group-id :permission_group_id}
              (data-apps.tu/ensure-resources! (t2/select-one :model/DataApp :name "demo"))
              allowed-table-id (mt/id :venues)
              missing-table-id (mt/id :orders)
              user-id          (mt/user->id :rasta)]
          (testing "an app with no synchronized dependencies has no warnings"
            (is (= []
                   (mt/user-http-request :crowberto :post 200 "apps/demo/user-permission-warnings"
                                         {:user_ids [user-id]}))))
          (t2/update! :model/DataApp :name "demo" {:table_ids [allowed-table-id missing-table-id]})
          (perms/add-user-to-group! user-id app-group-id)
          (perms/set-table-permission! app-group-id
                                       missing-table-id
                                       :perms/view-data
                                       :unrestricted)
          (perms/set-table-permission! (perms/all-users-group)
                                       allowed-table-id
                                       :perms/view-data
                                       :unrestricted)
          (testing "returns only users who lack access from a non-data-app group"
            (is (=? [{:user_id user-id
                      :missing_tables [{:id missing-table-id
                                        :name "Orders"
                                        :database_id (mt/id)}]}]
                    (mt/user-http-request :crowberto :post 200 "apps/demo/user-permission-warnings"
                                          {:user_ids [user-id (mt/user->id :crowberto)]}))))
          (testing "unrestricted access from another group is adequate"
            (mt/with-temp [:model/PermissionsGroup {group-id :id} {}]
              (perms/add-user-to-group! user-id group-id)
              (perms/set-table-permission! group-id
                                           missing-table-id
                                           :perms/view-data
                                           :unrestricted)
              (is (= []
                     (mt/user-http-request :crowberto :post 200 "apps/demo/user-permission-warnings"
                                           {:user_ids [user-id]})))))
          (testing "sandboxed access is adequate"
            (mt/with-temp [:model/PermissionsGroup {group-id :id} {}
                           :model/Sandbox _ {:group_id group-id :table_id missing-table-id}]
              (perms/add-user-to-group! user-id group-id)
              (is (= []
                     (mt/user-http-request :crowberto :post 200 "apps/demo/user-permission-warnings"
                                           {:user_ids [user-id]}))))))))))

(deftest permission-warning-lookups-are-batched-test
  (mt/with-premium-features #{:data-apps :advanced-permissions :sandboxes}
    (mt/with-no-data-perms-for-all-users!
      (let [table-ids [(mt/id :venues) (mt/id :orders)]
            users     [{:id (mt/user->id :rasta) :is_superuser false}
                       {:id (mt/user->id :lucky) :is_superuser false}]
            query-count (fn [users]
                          (t2/with-call-count [call-count]
                            (data-app.user-access/permission-warnings table-ids users)
                            (call-count)))]
        (is (= 3 (query-count users)))
        (is (= (query-count (take 1 users))
               (query-count users))
            "permission warning query count must not grow with the number of users")))))

(deftest data-app-list-warning-lookups-are-batched-test
  (mt/with-premium-features #{:data-apps :advanced-permissions :sandboxes}
    (mt/with-no-data-perms-for-all-users!
      (mt/with-temp [:model/PermissionsGroup {first-group-id :id} {}
                     :model/PermissionsGroup {second-group-id :id} {}]
        (perms/add-user-to-group! (mt/user->id :rasta) first-group-id)
        (perms/add-user-to-group! (mt/user->id :lucky) second-group-id)
        (let [apps [{:permission_group_id first-group-id
                     :table_ids [(mt/id :venues)]}
                    {:permission_group_id second-group-id
                     :table_ids [(mt/id :orders)]}]
              warning-groups #(t2/with-call-count [call-count]
                                (let [result (data-app.user-access/groups-with-permission-warnings %)]
                                  {:result result :query-count (call-count)}))
              one-app (warning-groups (take 1 apps))
              two-apps (warning-groups apps)]
          (is (= #{first-group-id} (:result one-app)))
          (is (= #{first-group-id second-group-id} (:result two-apps)))
          (is (= 3 (:query-count one-app) (:query-count two-apps))
              "warning status query count must not grow with the number of apps"))))))

(deftest data-app-list-includes-user-permission-warning-status-test
  (mt/with-premium-features #{:data-apps :advanced-permissions :sandboxes}
    (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup]
      (mt/with-no-data-perms-for-all-users!
        (create-app!)
        (let [{app-group-id :permission_group_id}
              (data-apps.tu/ensure-resources! (t2/select-one :model/DataApp :name "demo"))
              table-id (mt/id :orders)
              user-id  (mt/user->id :rasta)
              warning? #(->> (mt/user-http-request :crowberto :get 200 "apps")
                             (filter (comp #{"demo"} :name))
                             first
                             :has_user_permission_warnings)]
          (t2/update! :model/DataApp :name "demo" {:table_ids [table-id]})
          (perms/add-user-to-group! user-id app-group-id)
          (is (true? (warning?)))
          (perms/set-table-permission! (perms/all-users-group)
                                       table-id
                                       :perms/view-data
                                       :unrestricted)
          (is (false? (warning?))))))))

(deftest data-app-list-warning-status-ignores-deactivated-members-test
  (mt/with-premium-features #{:data-apps :advanced-permissions :sandboxes}
    (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup]
      (mt/with-no-data-perms-for-all-users!
        (create-app!)
        (let [{app-group-id :permission_group_id}
              (data-apps.tu/ensure-resources! (t2/select-one :model/DataApp :name "demo"))
              table-id (mt/id :orders)]
          (mt/with-temp [:model/User {user-id :id} {:email "deactivated-data-app-user@example.com"}]
            (perms/add-user-to-group! user-id app-group-id)
            (t2/update! :model/User :id user-id {:is_active false})
            (t2/update! :model/DataApp :name "demo" {:table_ids [table-id]})
            (is (false? (->> (mt/user-http-request :crowberto :get 200 "apps")
                             (filter (comp #{"demo"} :name))
                             first
                             :has_user_permission_warnings)))))))))

(deftest deactivated-users-cannot-be-added-to-data-apps-test
  (mt/with-premium-features #{:data-apps}
    (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup]
      (create-app!)
      (let [{app-group-id :permission_group_id}
            (data-apps.tu/ensure-resources! (t2/select-one :model/DataApp :name "demo"))]
        (mt/with-temp [:model/User {user-id :id} {:email "deactivated-data-app-user@example.com"
                                                  :is_active false}]
          (is (= "Deactivated users cannot be added to data apps."
                 (mt/user-http-request :crowberto :post 400 "permissions/membership"
                                       {:group_id app-group-id :user_id user-id})))
          (is (= "Deactivated users cannot be added to data apps."
                 (mt/user-http-request :crowberto :post 400 "apps/demo/user-permission-warnings"
                                       {:user_ids [user-id]}))))))))

(deftest user-permission-warnings-validates-users-test
  (mt/with-premium-features #{:data-apps :tenants}
    (mt/with-model-cleanup [:model/DataApp]
      (create-app!)
      (testing "unknown users"
        (mt/user-http-request :crowberto :post 404 "apps/demo/user-permission-warnings"
                              {:user_ids [Integer/MAX_VALUE]}))
      (testing "tenant users"
        (mt/with-temp [:model/Tenant {tenant-id :id} {:name "Data app tenant" :slug "data-app-tenant"}
                       :model/User {user-id :id} {:email "data-app-tenant-user@example.com"
                                                  :tenant_id tenant-id}]
          (is (= "Tenant users cannot be added to data apps."
                 (mt/user-http-request :crowberto :post 400 "apps/demo/user-permission-warnings"
                                       {:user_ids [user-id]}))))))))

(deftest non-superuser-cannot-read-user-permission-warnings-test
  (mt/with-premium-features #{:data-apps}
    (mt/with-model-cleanup [:model/DataApp]
      (create-app!)
      (is (= "You don't have permissions to do that."
             (mt/user-http-request :rasta :post 403 "apps/demo/user-permission-warnings"
                                   {:user_ids [(mt/user->id :rasta)]}))))))

(deftest data-app-write-endpoints-require-feature-token-test
  (mt/with-premium-features #{}
    (mt/user-http-request :crowberto :post 402 "apps/demo/user-permission-warnings"
                          {:user_ids [(mt/user->id :rasta)]})
    (mt/user-http-request :crowberto :post 402 "apps/resources/render"
                          {:collection {:entity_id (data-apps.tu/collection-entity-id "demo") :name "Demo"}
                           :queries    []
                           :actions    []})))

(deftest data-app-membership-additions-require-feature-token-test
  (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup]
    (create-app!)
    (let [{group-id :permission_group_id}
          (data-apps.tu/ensure-resources! (t2/select-one :model/DataApp :name "demo"))]
      (mt/with-premium-features #{}
        (mt/user-http-request :crowberto :post 402 "permissions/membership"
                              {:group_id group-id :user_id (mt/user->id :lucky)})
        (is (not (t2/exists? :model/PermissionsGroupMembership :group_id group-id)))))))

(deftest data-app-membership-removal-without-feature-token-test
  (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup]
    (create-app!)
    (let [{group-id :permission_group_id}
          (data-apps.tu/ensure-resources! (t2/select-one :model/DataApp :name "demo"))
          user-id (mt/user->id :rasta)]
      (perms/add-user-to-group! user-id group-id)
      (let [membership-id (t2/select-one-pk :model/PermissionsGroupMembership
                                            :group_id group-id :user_id user-id)
            endpoint (format "permissions/membership/%d" membership-id)]
        (mt/with-premium-features #{}
          (mt/user-http-request :rasta :delete 403 endpoint)
          (is (t2/exists? :model/PermissionsGroupMembership :id membership-id))
          ;; memberships and collection grants still remain after the token expires,
          ;; so admins must still be able to remove a member without the feature token.
          (mt/user-http-request :crowberto :delete 204 endpoint)
          (is (not (t2/exists? :model/PermissionsGroupMembership :id membership-id))))))))

(deftest data-app-membership-clearing-without-feature-token-test
  (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup]
    (create-app!)
    (let [{group-id :permission_group_id}
          (data-apps.tu/ensure-resources! (t2/select-one :model/DataApp :name "demo"))
          endpoint (format "permissions/membership/%d/clear" group-id)]
      (perms/add-user-to-group! (mt/user->id :rasta) group-id)
      (perms/add-user-to-group! (mt/user->id :lucky) group-id)
      (mt/with-premium-features #{}
        (mt/user-http-request :rasta :put 403 endpoint)
        (is (= 2 (t2/count :model/PermissionsGroupMembership :group_id group-id)))
        ;; memberships and collection grants still remain after the token expires,
        ;; so admins must still be able to remove all members without the feature token.
        (mt/user-http-request :crowberto :put 204 endpoint)
        (is (not (t2/exists? :model/PermissionsGroupMembership :group_id group-id)))))))

(deftest data-app-group-reaches-only-copied-actions-test
  (testing "an action is reachable exactly when its model lives in the data app collection"
    (mt/with-premium-features #{:data-apps}
      (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup]
        (mt/with-non-admin-groups-no-root-collection-perms
          ;; Its own slug: the `Data App: <slug>` group outlives other tests in
          ;; this namespace, so sharing "demo" collides on the group name.
          (let [{:keys [permission_group_id resource_collection_id]}
                (data-apps.tu/ensure-resources!
                 (t2/insert-returning-instance! :model/DataApp {:name         "action-perms-app"
                                                                :display_name "Action perms app"
                                                                :bundle_path  "data_apps/action-perms-app/index.js"}))
                metadata-provider (mt/metadata-provider)
                venues            (lib.metadata/table metadata-provider (mt/id :venues))]
            (perms/add-user-to-group! (mt/user->id :rasta) permission_group_id)
            (mt/with-temp
              [:model/Collection {source-collection-id :id} {}
               :model/Card       {source-model-id :id}      {:type          :model
                                                             :collection_id source-collection-id
                                                             :dataset_query (lib/query metadata-provider venues)}
               ;; What `npm run sync-resources` produces: a copy of the model in the
               ;; app's own collection, carrying its own copy of the action.
               :model/Card       {copied-model-id :id}      {:type          :model
                                                             :collection_id resource_collection_id
                                                             :dataset_query (lib/query metadata-provider venues)}]
              (let [action     {:name "Create Venue", :type :implicit, :kind :row/create}
                    source-id  (actions/insert! (assoc action :model_id source-model-id))
                    copied-id  (actions/insert! (assoc action :model_id copied-model-id))]
                (testing "the app's group reads the action copied into its collection"
                  (is (=? {:id copied-id}
                          (mt/user-http-request :rasta :get 200 (str "action/" copied-id)))))
                (testing "the same group cannot read the source action it was copied from"
                  (is (= "You don't have permissions to do that."
                         (mt/user-http-request :rasta :get 403 (str "action/" source-id)))))
                (testing "copying does not widen access to the source model"
                  (is (= "You don't have permissions to do that."
                         (mt/user-http-request :rasta :get 403 (str "card/" source-model-id)))))
                (testing "a superuser still reads both"
                  (is (=? {:id source-id}
                          (mt/user-http-request :crowberto :get 200 (str "action/" source-id)))))))))))))

(deftest list-available-apps-test
  (mt/with-premium-features #{:data-apps}
    (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup]
      (t2/insert! :model/DataApp :name "ready" :display_name "Ready" :bundle_path "data_apps/ready/index.js")
      (t2/insert! :model/DataApp :name "disabled" :display_name "Disabled" :bundle_path "data_apps/disabled/index.js"
                  :enabled false)
      (t2/insert! :model/DataApp :name "failed" :display_name "Failed" :bundle_path "data_apps/failed/index.js"
                  :sync_error "Could not read bundle")
      (is (=? [{:name "ready" :display_name "Ready"}]
              (mt/user-http-request :rasta :get 200 "apps?available=true"))))))

(deftest outdated-apps-are-hidden-from-users-and-badged-for-admins-test
  (mt/test-helpers-set-global-values!
    (mt/with-premium-features #{:data-apps}
      (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup]
        (t2/insert! :model/DataApp :name "old" :display_name "Old" :bundle_path "data_apps/old/index.js"
                    :bundle (.getBytes "BUNDLE" "UTF-8") :bundle_hash "abc123" :version 1)
        (t2/insert! :model/DataApp :name "current" :display_name "Current" :bundle_path "data_apps/current/index.js"
                    :bundle (.getBytes "BUNDLE" "UTF-8") :bundle_hash "def456" :version 2)
        (doseq [slug ["old" "current"]
                :let [{:keys [permission_group_id]}
                      (data-apps.tu/ensure-resources! (t2/select-one :model/DataApp :name slug))]]
          (perms/add-user-to-group! (mt/user->id :rasta) permission_group_id))
        (with-redefs [data-app.config/supported-app-version 2]
          (testing "a regular user is never told about the outdated app in a list"
            (doseq [url ["apps" "apps?available=true"]]
              (is (= [{:name "current" :display_name "Current"}]
                     (mt/user-http-request :rasta :get 200 url)))))
          (testing "for a regular user, opening an outdated app is a 409 that says what to do"
            (doseq [url ["apps/old" "apps/old/bundle"]]
              (is (=? {:error-code "data-app-outdated"
                       :message    #"This app was built for version 1 of data apps.*"}
                      (mt/user-http-request :rasta :get 409 url)))))
          (testing "the current app still opens"
            (is (= {:name "current" :display_name "Current"}
                   (mt/user-http-request :rasta :get 200 "apps/current")))
            (is (str/includes?
                 (str (mt/user-real-request :crowberto :get 200 "apps/current/bundle"))
                 "BUNDLE")))
          (testing "an admin sees the outdated app flagged, and can still read it to manage its users"
            (is (=? [{:name "current" :version 2 :outdated false}
                     {:name "old" :version 1 :outdated true}]
                    (mt/user-http-request :crowberto :get 200 "apps")))
            (is (=? [{:name "current"}]
                    (mt/user-http-request :crowberto :get 200 "apps?available=true"))
                "but the navbar's available list leaves it out for admins too")
            (is (=? {:name "old" :version 1 :outdated true :permission_group_id pos-int?}
                    (mt/user-http-request :crowberto :get 200 "apps/old"))))
          (testing "nobody gets an outdated bundle"
            (is (=? {:error-code "data-app-outdated"}
                    (mt/user-http-request :crowberto :get 409 "apps/old/bundle"))))
          (testing "a management response carries the flag too"
            (is (=? {:name "old" :outdated true}
                    (mt/user-http-request :crowberto :put 200 "apps/old" {:enabled false})))))))))

(deftest bundle-includes-allowed-hosts-header-test
  (mt/with-premium-features #{:data-apps}
    (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup]
      (t2/insert! :model/DataApp
                  :name          "demo"
                  :display_name  "Demo"
                  :bundle_path   "data_apps/demo/index.js"
                  :bundle        (.getBytes "BUNDLE" "UTF-8")
                  :bundle_hash   "abc123"
                  :allowed_hosts ["https://api.example.com"])
      ;; publish the app so its bundle is served (crowberto, as a superuser, can read the collection)
      (data-apps.tu/ensure-resources! (t2/select-one :model/DataApp :name "demo"))
      (testing "the bundle response normalizes the configured product analytics origin"
        (mt/with-temporary-setting-values
          [metaplow-url "HTTPS://product-analytics-ingestion.metabase.com/api/send"]
          (let [resp (mt/user-http-request-full-response :crowberto :get 200 "apps/demo/bundle")]
            (is (= (json/encode ["https://api.example.com"
                                 "https://product-analytics-ingestion.metabase.com"])
                   (get-in resp [:headers "X-Metabase-Data-App-Allowed-Hosts"]))))))
      (testing "the configured staging origin replaces the production origin"
        (mt/with-temporary-setting-values [metaplow-url "https://product-analytics-ingestion.staging.metabase.com/api/send"]
          (let [resp (mt/user-http-request-full-response :crowberto :get 200 "apps/demo/bundle")]
            (is (= (json/encode ["https://api.example.com"
                                 "https://product-analytics-ingestion.staging.metabase.com"])
                   (get-in resp [:headers "X-Metabase-Data-App-Allowed-Hosts"]))))))
      (testing "an unset product analytics URL leaves the app's allowlist unchanged"
        (mt/with-temporary-setting-values [metaplow-url nil]
          (let [resp (mt/user-http-request-full-response :crowberto :get 200 "apps/demo/bundle")]
            (is (= (json/encode ["https://api.example.com"])
                   (get-in resp [:headers "X-Metabase-Data-App-Allowed-Hosts"])))))))))

(deftest list-includes-allowed-hosts-test
  (mt/with-premium-features #{:data-apps}
    (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup]
      (t2/insert! :model/DataApp
                  :name "withhosts" :display_name "With"
                  :bundle_path "data_apps/withhosts/index.js"
                  :allowed_hosts ["https://api.example.com"])
      ;; inserted without the column → stored NULL, exercising the read coercion
      (t2/insert! :model/DataApp
                  :name "nohosts" :display_name "No"
                  :bundle_path "data_apps/nohosts/index.js")
      (testing "the list endpoint returns allowed_hosts, always a list (NULL → [])"
        (let [by-name (->> (mt/user-http-request :crowberto :get 200 "apps")
                           (into {} (map (juxt :name identity))))]
          (is (= ["https://api.example.com"]
                 (get-in by-name ["withhosts" :allowed_hosts])))
          (is (= [] (get-in by-name ["nohosts" :allowed_hosts]))))))))

;;; ----------------------------------------------------- Sync -----------------------------------------------------

(deftest import-materializes-apps-test
  (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup]
    (let [result (data-app.sync/import-from-snapshot!
                  (snapshot (merge (app-files "sales" {:name "Sales" :path "dist/index.js" :bundle "SALES-BUNDLE"})
                                   (app-files "ops"   {:name "Ops"   :path "dist/app.js"   :bundle "OPS-BUNDLE"}))))]
      (is (=? {:synced 2} result))
      (is (= #{"sales" "ops"} (t2/select-fn-set :name :model/DataApp)))
      (let [sales (t2/select-one :model/DataApp :name "sales")]
        (is (= "SALES-BUNDLE" (String. ^bytes (:bundle sales) "UTF-8")))
        (is (= "Sales" (:display_name sales)))
        (is (= "data_apps/sales/dist/index.js" (:bundle_path sales)))
        (is (true? (:enabled sales)))
        (is (= data-apps.tu/fake-sha (:last_synced_sha sales)))
        (is (nil? (:sync_error sales)))))))

(deftest import-stores-allowed-hosts-test
  (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup]
    (testing "allowed_hosts from data_app.yaml are persisted on the row"
      (data-app.sync/import-from-snapshot!
       (snapshot (app-files "sales" {:name "Sales" :path "dist/index.js" :bundle "B"
                                     :allowed_hosts ["https://api.example.com" "https://*.acme.com"]})))
      (is (= ["https://api.example.com" "https://*.acme.com"]
             (:allowed_hosts (t2/select-one :model/DataApp :name "sales")))))
    (testing "re-syncing without allowed_hosts clears them to an empty list"
      (data-app.sync/import-from-snapshot!
       (snapshot (app-files "sales" {:name "Sales" :path "dist/index.js" :bundle "B"})))
      (is (= [] (:allowed_hosts (t2/select-one :model/DataApp :name "sales")))))))

(deftest import-prunes-apps-absent-from-snapshot-test
  (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup]
    (data-app.sync/import-from-snapshot!
     (snapshot (merge (app-files "keep" {:name "Keep" :path "index.js" :bundle "KEEP"})
                      (app-files "gone" {:name "Gone" :path "index.js" :bundle "GONE"}))))
    (is (= #{"keep" "gone"} (t2/select-fn-set :name :model/DataApp)))
    ;; The connected repo is the source of truth: an app whose directory is gone
    ;; from a later snapshot is pruned. (An admin can also remove one explicitly
    ;; via DELETE /api/apps/:slug.)
    (is (=? {:removed 1}
            (data-app.sync/import-from-snapshot!
             (snapshot (app-files "keep" {:name "Keep" :path "index.js" :bundle "KEEP"})))))
    (is (= #{"keep"} (t2/select-fn-set :name :model/DataApp))
        "the app absent from the later snapshot is pruned")))

(deftest delete-endpoint-test
  (mt/test-helpers-set-global-values!
    (mt/with-premium-features #{:data-apps}
      (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup]
        (create-app!)
        (let [{:keys [resource_collection_id permission_group_id]}
              (data-apps.tu/ensure-resources! (t2/select-one :model/DataApp :name "demo"))]
          (mt/with-temp [:model/Card {card-id :id} {:collection_id resource_collection_id}
                         :model/PermissionsGroupMembership _ {:user_id  (mt/user->id :rasta)
                                                              :group_id permission_group_id}]
            (testing "a non-superuser cannot remove an app"
              (is (= "You don't have permissions to do that."
                     (mt/user-http-request :rasta :delete 403 "apps/demo")))
              (is (t2/exists? :model/DataApp :name "demo")))
            ;; each data app owns a permission group and a collection
            ;; containing saved questions and models
            (testing "a superuser removes the app, its resources, and their contents"
              (is (nil? (mt/user-http-request :crowberto :delete 204 "apps/demo")))
              (is (not (t2/exists? :model/DataApp :name "demo")))
              (is (not (t2/exists? :model/Collection :id resource_collection_id)))
              (is (not (t2/exists? :model/Card :id card-id)))
              (is (not (t2/exists? :model/PermissionsGroup :id permission_group_id)))
              (is (not (t2/exists? :model/PermissionsGroupMembership :group_id permission_group_id))))
            (testing "removing a non-existent app 404s"
              (mt/user-http-request :crowberto :delete 404 "apps/missing"))))))))

(deftest import-preserves-enabled-across-syncs-test
  (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup]
    (data-app.sync/import-from-snapshot!
     (snapshot (app-files "a" {:name "A" :path "index.js" :bundle "V1"})))
    (t2/update! :model/DataApp :name "a" {:enabled false})
    ;; a new bundle must not flip the admin's enabled toggle back on
    (data-app.sync/import-from-snapshot!
     (snapshot (app-files "a" {:name "A" :path "index.js" :bundle "V2"})))
    (let [a (t2/select-one :model/DataApp :name "a")]
      (is (false? (:enabled a)) "the disabled toggle is preserved")
      (is (= "V2" (String. ^bytes (:bundle a) "UTF-8")) "the bundle is still updated"))))

(deftest import-per-app-error-test
  (testing "a missing bundle file fails just that app, not the whole import"
    (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup]
      (data-app.sync/import-from-snapshot!
       (snapshot (merge (app-files "good" {:name "Good" :path "index.js" :bundle "GOOD"})
                        ;; "bad" declares a path that doesn't exist
                        (dissoc (app-files "bad" {:name "Bad" :path "missing.js"}) "data_apps/bad/missing.js"))))
      (is (= #{"good" "bad"} (t2/select-fn-set :name :model/DataApp)))
      (let [good (t2/select-one :model/DataApp :name "good")
            bad  (t2/select-one :model/DataApp :name "bad")]
        (is (= "GOOD" (String. ^bytes (:bundle good) "UTF-8")))
        (is (nil? (:sync_error good)))
        (is (nil? (:bundle bad)))
        (is (str/includes? (:sync_error bad) "missing.js"))))))

(deftest import-serves-each-app-from-its-directory-test
  (testing "the directory an app lives in is the slug it's served at — two apps can't collide on one"
    (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup]
      (data-app.sync/import-from-snapshot!
       (snapshot (merge (app-files "one" {:name "One" :path "a.js" :bundle "A"})
                        (app-files "two" {:name "Two" :path "b.js" :bundle "B"}))))
      (is (= #{"one" "two"} (t2/select-fn-set :name :model/DataApp))))))

(deftest import-isolates-bad-config-test
  (testing "a malformed data_app.yaml is isolated: sibling apps in the same repo still sync and are not pruned, the bad one is reported"
    (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup]
      (data-app.sync/import-from-snapshot!
       (snapshot (app-files "existing" {:name "Existing" :path "i.js" :bundle "E"})))
      ;; The repo still holds "existing" and adds "good", alongside a broken "bad".
      ;; The broken config must not abort the others, nor prune its siblings.
      (let [result (data-app.sync/import-from-snapshot!
                    (snapshot (merge (app-files "existing" {:name "Existing" :path "i.js" :bundle "E"})
                                     (app-files "good" {:name "Good" :path "i.js" :bundle "GOOD"})
                                     {"data_apps/bad/data_app.yaml" "name: [unterminated"})))]
        (is (=? {:synced 2 :removed 0} result))
        (is (= 1 (count (:config-errors result))))
        (is (= #{"existing" "good"} (t2/select-fn-set :name :model/DataApp))
            "the bad config neither aborts nor prunes its sibling apps, and doesn't materialize itself")))))

(deftest sync-from-snapshot!-never-throws-test
  (mt/with-premium-features #{:data-apps}
    (testing "a malformed data_app.yaml is isolated into :config-errors; the app just doesn't appear, the sync doesn't throw"
      (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup]
        (let [result (data-app.sync/sync-from-snapshot!
                      (snapshot {"data_apps/x/data_app.yaml" "name: [unterminated"}))]
          (is (seq (:config-errors result)))
          (is (empty? (t2/select-fn-set :name :model/DataApp))))))
    (testing "a clean sync materializes the app with no config errors"
      (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup]
        (let [result (data-app.sync/sync-from-snapshot!
                      (snapshot (app-files "a" {:name "A" :path "index.js" :bundle "A"})))]
          (is (empty? (:config-errors result)))
          (is (= #{"a"} (t2/select-fn-set :name :model/DataApp))))))))

;;; ----------------------------------------------------- API -----------------------------------------------------

(deftest list-and-bundle-endpoints-test
  (mt/test-helpers-set-global-values!
    (mt/with-premium-features #{:data-apps}
      (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup]
        (data-app.sync/import-from-snapshot!
         (snapshot (app-files "demo" {:name "Demo app" :path "dist/index.js" :bundle "DEMOBUNDLE"})))
        (testing "GET / lists the synced apps"
          (is (=? [{:name "demo" :display_name "Demo app"
                    :bundle_path "data_apps/demo/dist/index.js" :enabled true}]
                  (mt/user-http-request :crowberto :get 200 "apps"))))
        (testing "GET /:slug/bundle serves the cached bytes"
          (is (str/includes?
               (str (mt/user-real-request :crowberto :get 200 "apps/demo/bundle"))
               "DEMOBUNDLE")))))))

(deftest repo-status-endpoint-test
  (mt/with-premium-features #{:data-apps}
    (testing "reports no repository when none is connected"
      (mt/with-dynamic-fn-redefs [data-app.sync/repo-url (constantly nil)]
        (is (=? {:configured false :url nil}
                (mt/user-http-request :crowberto :get 200 "apps/repo-status")))))
    (testing "reports the connected repository URL"
      (mt/with-dynamic-fn-redefs [data-app.sync/repo-url (constantly "https://github.com/metabase/stats-remote-sync")]
        (is (=? {:configured true :url "https://github.com/metabase/stats-remote-sync"}
                (mt/user-http-request :crowberto :get 200 "apps/repo-status")))))))

(deftest enable-disable-endpoint-test
  (mt/test-helpers-set-global-values!
    (mt/with-premium-features #{:data-apps}
      (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup]
        (data-app.sync/import-from-snapshot!
         (snapshot (app-files "demo" {:name "Demo" :path "index.js" :bundle "BUNDLE"})))
        (testing "PUT /:slug can disable an app"
          (is (=? {:name "demo" :enabled false}
                  (mt/user-http-request :crowberto :put 200 "apps/demo" {:enabled false}))))
        (testing "a disabled app is not served"
          (is (= "Not found." (mt/user-http-request :crowberto :get 404 "apps/demo")))
          (mt/user-real-request :crowberto :get 404 "apps/demo/bundle"))
        (testing "re-enabling restores serving"
          (is (=? {:enabled true}
                  (mt/user-http-request :crowberto :put 200 "apps/demo" {:enabled true})))
          (is (=? {:name "demo"} (mt/user-http-request :crowberto :get 200 "apps/demo"))))))))

(deftest sandbox-host-endpoint-test
  (mt/with-premium-features #{:data-apps}
    (let [resp    (mt/user-http-request-full-response :crowberto :get 200 "apps/sandbox-host")
          headers (:headers resp)]
      (testing "serves a minimal HTML document"
        (is (str/includes? (:body resp) "<!doctype html>"))
        (is (str/starts-with? (get headers "Content-Type") "text/html")))
      (testing "carries the per-document CSP that confines 'unsafe-eval' to the realm"
        ;; This grant is why the data-app document itself can drop 'unsafe-eval'
        ;; (see `data-app-unsafe-eval-test`), and `default-src 'none'` means the
        ;; realm has no network of its own rather than inheriting the data-app
        ;; document's `connect-src` (which includes the instance origin).
        (let [csp (get headers "Content-Security-Policy")]
          (is (some? csp))
          (is (str/includes? csp "default-src 'none'"))
          (is (str/includes? csp "script-src 'unsafe-eval'"))
          (is (str/includes? csp "frame-ancestors 'self'"))
          (testing "and the endpoint's CSP wins over the global middleware one"
            (is (not (str/includes? csp "'nonce-"))))))
      (testing "is framable same-origin, overriding the global X-Frame-Options"
        (is (= "SAMEORIGIN" (get headers "X-Frame-Options"))))
      (testing "hardening headers are present"
        (is (= "nosniff"     (get headers "X-Content-Type-Options")))
        (is (= "no-referrer" (get headers "Referrer-Policy")))
        (is (= "same-origin" (get headers "Cross-Origin-Resource-Policy"))))))
  ;; `slug-regex` must exclude this literal, or `/apps/sandbox-host` would be
  ;; routed as a data app named "sandbox-host" and 404.
  (testing "the route is not shadowed by the /:slug route"
    (mt/with-premium-features #{:data-apps}
      (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup]
        (create-app!)
        (is (= 200 (:status (mt/user-http-request-full-response
                             :crowberto :get 200 "apps/sandbox-host"))))))))
