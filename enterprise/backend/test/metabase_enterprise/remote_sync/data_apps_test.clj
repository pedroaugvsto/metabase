(ns metabase-enterprise.remote-sync.data-apps-test
  "Integration coverage for the data-apps hook in the remote-sync import pipeline:
   importing a repo materializes data apps from its `data_apps/` directory."
  (:require
   [clojure.test :refer :all]
   [metabase-enterprise.data-apps.models.data-app]
   [metabase-enterprise.data-apps.test-util :as data-apps.tu]
   [metabase-enterprise.remote-sync.impl :as impl]
   [metabase-enterprise.remote-sync.source.protocol :as source.p]
   [metabase-enterprise.remote-sync.spec :as spec]
   [metabase-enterprise.remote-sync.test-helpers :as test-helpers]
   [metabase.test :as mt]
   [metabase.test.fixtures :as fixtures]
   [toucan2.core :as t2]))

(use-fixtures :once (fixtures/initialize :db))
(use-fixtures :each (fn [f] (test-helpers/clean-remote-sync-state f)))
(comment metabase-enterprise.data-apps.models.data-app/keep-me)

(defn- import! [files]
  ;; the import's data-app materialization is gated on :data-apps; enable it so apps sync.
  (mt/with-premium-features #{:data-apps}
    (let [source  (test-helpers/create-mock-source :initial-files files)
          task-id (t2/insert-returning-pk! :model/RemoteSyncTask
                                           {:sync_task_type "import" :initiated_by (mt/user->id :rasta)})
          ;; force: the mock source reports a constant version, so without this the
          ;; second import in a test would hit the "version unchanged" skip branch
          result  (impl/import! (source.p/snapshot source) task-id :force? true)]
      ;; calling import! directly leaves the task "running"; mark it ended so a
      ;; subsequent import in the same test isn't blocked by the running-task guard
      (t2/update! :model/RemoteSyncTask task-id {:ended_at :%now})
      result)))

(deftest import-materializes-data-apps-test
  (testing "a remote-sync import materializes data apps from data_apps/ alongside serdes content"
    (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup]
      (let [files  {"main" (merge {"collections/c/c.yaml"
                                   (test-helpers/generate-collection-yaml "data-apps-test-collx" "DA Coll")}
                                  (data-apps.tu/app-files "sales" {:name "Sales" :path "dist/index.js"
                                                                   :bundle "SALESBUNDLE"}))}
            result (import! files)]
        (is (= :success (:status result)))
        (let [app (t2/select-one :model/DataApp :name "sales")]
          (is (some? app) "the data app from the repo was materialized by the import")
          (is (= "Sales" (:display_name app)))
          (is (= "data_apps/sales/dist/index.js" (:bundle_path app)))
          (is (true? (:enabled app)))
          (is (= "SALESBUNDLE" (String. ^bytes (:bundle app) "UTF-8"))))))))

(deftest import-prunes-data-apps-absent-from-repo-test
  (testing "an import whose repo no longer has an app dir prunes that app (the repo is the source of truth)"
    (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup]
      (import! {"main" (merge (data-apps.tu/app-files "gone" {:name "Gone" :path "i.js" :bundle "X"})
                              (data-apps.tu/app-files "kept" {:name "Kept" :path "i.js" :bundle "K"}))})
      (is (= #{"gone" "kept"} (t2/select-fn-set :name :model/DataApp)))
      (import! {"main" (data-apps.tu/app-files "kept" {:name "Kept" :path "i.js" :bundle "K"})})
      (is (nil? (t2/select-one :model/DataApp :name "gone"))
          "the app absent from the later import is pruned")
      (is (some? (t2/select-one :model/DataApp :name "kept"))
          "the still-present app is kept"))))

(deftest deletion-only-pull-counts-the-removal-test
  (testing "a pull whose only change is removing an app still reports as a pull — the removal is counted, not a no-op"
    (mt/with-model-cleanup [:model/DataApp :model/Collection :model/PermissionsGroup]
      ;; No serdes content, so the outcome's count comes purely from data apps.
      (import! {"main" (merge {"README.md" "x"}
                              (data-apps.tu/app-files "gone" {:name "Gone" :path "i.js" :bundle "X"}))})
      (is (= #{"gone"} (t2/select-fn-set :name :model/DataApp)))
      ;; Same repo minus the app dir: nothing is upserted (`:changed` 0), one app removed.
      (let [result (import! {"main" {"README.md" "x"}})]
        (is (= :success (:status result)))
        (is (empty? (t2/select-fn-set :name :model/DataApp))
            "the app is pruned")
        (is (pos? (:count (:outcome result)))
            "the removal is folded into the pull outcome, so it isn't reported as pull-skipped/0")))))

(deftest export-refuses-remote-synced-content-that-uses-data-app-resources-test
  (testing "only an app's own repository files define its resources, so remote-synced content that reads one can't be exported"
    (mt/with-temp [:model/Collection {synced-id :id} {:name "Synced" :is_remote_synced true :location "/"}
                   :model/Collection {app-collection-id :id} {:name "App" :location "/"}
                   :model/DataApp    _ {:name                   "reads-app"
                                        :display_name           "Reads app"
                                        :bundle_path            "data_apps/reads-app/index.js"
                                        :resource_collection_id app-collection-id}
                   :model/Card       {app-card-id :id} {:collection_id app-collection-id
                                                        :dataset_query {:database (mt/id)
                                                                        :type     :query
                                                                        :query    {:source-table (mt/id :venues)}}}
                   :model/Card       _ {:collection_id synced-id
                                        :dataset_query {:database (mt/id)
                                                        :type     :query
                                                        :query    {:source-table (str "card__" app-card-id)}}}]
      (let [targets (spec/exportable-entities)]
        (is (contains? (set (get targets "Card")) app-card-id)
            "reading local state for a pull still works, so a pull can bring the fix")
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              (re-pattern (str "Remote-synced content uses data app resources.*cards " app-card-id))
                              (spec/check-no-data-app-resources! (get targets "Card"))))))))
