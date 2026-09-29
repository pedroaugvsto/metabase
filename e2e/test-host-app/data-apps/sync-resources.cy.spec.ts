import { USERS, USER_GROUPS } from "e2e/support/cypress_data";
import { SAMPLE_DATABASE } from "e2e/support/cypress_sample_database";
import {
  addUserToGroup,
  buildDataAppHostApp,
  createDataAppApiKey,
  dataAppHostAppRoot,
  dataAppPermissionGroupId,
  declareDataAppQueries,
  publishDataApp,
  removeDataAppQueryDeclaration,
  resetDataAppHostAppSources,
  syncDataAppResources,
} from "e2e/support/helpers";
import type { Card, CollectionItem, DataApp } from "metabase-types/api";

const { H } = cy;
const { ORDERS_ID } = SAMPLE_DATABASE;

/** The app is published under its directory's name. */
const APP_SLUG = "vite-6-data-app-host-app";

const APP_ROOT = () => dataAppHostAppRoot();
const RESOURCES_DIR = () => `${APP_ROOT()}/resources`;
const QUERIES_FILE = () => `${APP_ROOT()}/queries/orders.query.ts`;
const MANIFEST_FILE = () => `${APP_ROOT()}/data_app.yaml`;

const AUTHORED_MANIFEST = `name: Vite 6 Data App
version: 1
path: ./dist/index.js
allowed_hosts:
  - https://allowed.data-app.test
`;

const ENTITY_ID_PATTERN = /savedQuestionEntityId: "([A-Za-z0-9_-]{21})"/g;

/** The saved question entity IDs synchronization wrote, in declaration order. */
const questionEntityIds = () =>
  cy
    .readFile(QUERIES_FILE())
    .then((source: string) =>
      [...source.matchAll(ENTITY_ID_PATTERN)].map(([, entityId]) => entityId),
    );

/** The file names under `resources/cards`. */
const cardFiles = () =>
  cy
    .exec(`ls "${RESOURCES_DIR()}/cards"`)
    .then(({ stdout }) => stdout.split("\n").filter(Boolean));

/**
 * The query half of `sync-resources`, run against the dev host app: a real vite
 * data app with the published SDK installed, so `defineQuery` resolves through
 * the package an author actually consumes rather than a stub. Synchronization
 * only writes files; a repository pull is what reaches Metabase.
 */
describe("Embedding SDK: data-app sync-resources (queries)", () => {
  const resetHostAppSources = () => {
    resetDataAppHostAppSources();
    cy.writeFile(MANIFEST_FILE(), AUTHORED_MANIFEST);
  };

  beforeEach(() => {
    H.restore();
    cy.signInAsAdmin();
    H.activateToken("bleeding-edge");

    // The query and actions specs share a checked-in host app, so start clean.
    resetHostAppSources();
    createDataAppApiKey().as("apiKey");
  });

  after(() => {
    resetHostAppSources();
  });

  const syncWith = (apiKey: string) =>
    syncDataAppResources(apiKey, APP_ROOT()).then(({ ok, error }) => {
      expect(error, "sync-resources failed").to.eq(null);
      expect(ok).to.eq(true);
    });

  const sync = () => cy.get<string>("@apiKey").then(syncWith);

  const syncExpectingRefusal = (message: string, apiKey?: string) =>
    cy.get<string>("@apiKey").then((adminKey) => {
      syncDataAppResources(apiKey ?? adminKey, APP_ROOT()).should(
        ({ ok, error }) => {
          expect(ok, "sync-resources should have refused").to.eq(false);
          expect(error).to.contain(message);
        },
      );
    });

  const expectNothingWritten = () => {
    cy.exec(`test ! -e "${RESOURCES_DIR()}"`);
    cy.readFile(MANIFEST_FILE()).should("eq", AUTHORED_MANIFEST);
    cy.readFile(QUERIES_FILE()).should("not.contain", "savedQuestionEntityId");
  };

  it("names the definition whose query Metabase could not resolve, and writes nothing", () => {
    declareDataAppQueries(APP_ROOT(), [{ name: "Orders", tableId: 999999 }]);

    syncExpectingRefusal("Orders: table 999999 does not exist.");

    expectNothingWritten();
  });

  it("writes the app's resources and generated IDs without changing Metabase", () => {
    declareDataAppQueries(APP_ROOT(), [
      { name: "Orders", tableId: ORDERS_ID, limit: 5 },
    ]);
    sync();

    cy.readFile(MANIFEST_FILE())
      .should("contain", AUTHORED_MANIFEST)
      .and("match", /\ncollection: [A-Za-z0-9_-]{21}\n$/);
    cy.readFile(`${RESOURCES_DIR()}/collection.yaml`).should(
      "contain",
      "Data App: Vite 6 Data App",
    );

    questionEntityIds().then(([entityId]) => {
      expect(entityId, "the generated saved question ID").to.be.a("string");
      cardFiles().should("deep.equal", [`orders_${entityId}.yaml`]);

      // Nothing reaches Metabase until the repository is pulled.
      cy.request({ url: `/api/apps/${APP_SLUG}`, failOnStatusCode: false })
        .its("status")
        .should("eq", 404);
      cy.request({ url: `/api/card/${entityId}`, failOnStatusCode: false })
        .its("status")
        .should("eq", 404);

      // Synchronizing again reuses every ID and rewrites the same bytes.
      cy.exec(
        `cat "${RESOURCES_DIR()}"/*.yaml "${RESOURCES_DIR()}"/cards/*.yaml`,
      )
        .its("stdout")
        .then((before) => {
          sync();
          questionEntityIds().should("deep.equal", [entityId]);
          cy.exec(
            `cat "${RESOURCES_DIR()}"/*.yaml "${RESOURCES_DIR()}"/cards/*.yaml`,
          )
            .its("stdout")
            .should("eq", before);
        });
    });
  });

  it("removes a saved question's file when its declaration is removed", () => {
    declareDataAppQueries(APP_ROOT(), [
      { name: "Orders", tableId: ORDERS_ID },
      { name: "OrdersSample", tableId: ORDERS_ID, limit: 10 },
    ]);
    sync();

    questionEntityIds().then(([ordersId, sampleId]) => {
      removeDataAppQueryDeclaration(APP_ROOT(), "OrdersSample");
      sync();

      cardFiles().should("deep.equal", [`orders_${ordersId}.yaml`]);
      cy.exec(`ls "${RESOURCES_DIR()}/cards"`)
        .its("stdout")
        .should("not.contain", sampleId);
    });
  });

  it("publishes the saved questions with a pull, readable by the app's group alone", () => {
    declareDataAppQueries(APP_ROOT(), [{ name: "Orders", tableId: ORDERS_ID }]);
    sync();
    publishDataApp(APP_ROOT(), APP_SLUG);

    questionEntityIds().then(([entityId]) => {
      cy.request<DataApp>(`/api/apps/${APP_SLUG}`).then(({ body: app }) => {
        expect(app.table_ids).to.deep.eq([ORDERS_ID]);

        cy.request<Card>(`/api/card/${entityId}`).then(({ body: card }) => {
          expect(card.collection_id).to.eq(app.resource_collection_id);
          expect(card.name).to.eq("Orders");

          dataAppPermissionGroupId(APP_SLUG).then((groupId) => {
            addUserToGroup(groupId, USERS.normal.email);
          });

          cy.signInAsNormalUser();
          cy.request(`/api/card/${entityId}`)
            .its("body.id")
            .should("eq", card.id);

          cy.signIn("nocollection");
          cy.request({ url: `/api/card/${entityId}`, failOnStatusCode: false })
            .its("status")
            .should("eq", 403);
        });
      });
    });
  });

  it("publishes a copy of the metric a query uses, readable by the app's group alone", () => {
    H.createQuestion({
      name: "Orders count",
      type: "metric",
      query: { "source-table": ORDERS_ID, aggregation: [["count"]] },
    }).then(({ body: metric }) => {
      declareDataAppQueries(APP_ROOT(), [
        { name: "OrdersCount", tableId: ORDERS_ID, metricId: metric.id },
      ]);
      sync();
      publishDataApp(APP_ROOT(), APP_SLUG);

      questionEntityIds().then(([entityId]) => {
        cy.request<DataApp>(`/api/apps/${APP_SLUG}`).then(({ body: app }) => {
          cy.request<{ data: CollectionItem[] }>(
            `/api/collection/${app.resource_collection_id}/items?models=metric`,
          ).then(({ body: { data: metrics } }) => {
            expect(metrics.map(({ name }) => name)).to.deep.eq([
              "Orders count",
            ]);
            const [copy] = metrics;
            expect(copy.id, "a copy, not the source metric").not.to.eq(
              metric.id,
            );

            dataAppPermissionGroupId(APP_SLUG).then((groupId) => {
              addUserToGroup(groupId, USERS.normal.email);
            });

            cy.signInAsNormalUser();
            cy.request(`/api/card/${copy.id}`).its("status").should("eq", 200);
            cy.request<Card>(`/api/card/${entityId}`).then(({ body: card }) => {
              cy.request("POST", `/api/card/${card.id}/query`)
                .its("body.data.rows.0.0")
                .should("be.greaterThan", 0);
            });

            cy.signIn("nocollection");
            cy.request({ url: `/api/card/${copy.id}`, failOnStatusCode: false })
              .its("status")
              .should("eq", 403);
          });
        });
      });
    });
  });

  it("renders for a non-admin key that can run the queries, and refuses one that can't", () => {
    declareDataAppQueries(APP_ROOT(), [{ name: "Orders", tableId: ORDERS_ID }]);

    cy.request("POST", "/api/api-key", {
      name: `data-app-sync-e2e-no-data-${Date.now()}`,
      group_id: USER_GROUPS.COLLECTION_GROUP,
    }).then(({ body: key }) => {
      syncExpectingRefusal(
        "You don't have permission to run Orders.",
        key.unmasked_key,
      );
      expectNothingWritten();
    });

    cy.request("POST", "/api/api-key", {
      name: `data-app-sync-e2e-data-${Date.now()}`,
      group_id: USER_GROUPS.DATA_GROUP,
    }).then(({ body: key }) => {
      syncWith(key.unmasked_key);
      questionEntityIds().should("have.length", 1);
    });
  });

  describe("the build guard", () => {
    /** `cy.exec` reports the command's output; vite prints this only on success. */
    const expectBuildToSucceed = () =>
      buildDataAppHostApp().should((result) => {
        expect(`${result.stdout}${result.stderr}`).to.contain("built in");
      });

    // `metabase-resource-sync-check` runs on buildStart and never calls
    // Metabase, so a stale app is refused before it can be bundled.
    it("refuses to build when a definition changed after synchronization", () => {
      declareDataAppQueries(APP_ROOT(), [
        { name: "Orders", tableId: ORDERS_ID },
      ]);
      sync();
      expectBuildToSucceed();

      questionEntityIds().then(([entityId]) => {
        // Edit the definition in place, keeping its generated ID.
        cy.readFile(QUERIES_FILE()).then((source: string) =>
          cy.writeFile(
            QUERIES_FILE(),
            source.replace(
              `id: ${ORDERS_ID} }`,
              `id: ${ORDERS_ID} }, limit: 3`,
            ),
          ),
        );
        cy.readFile(QUERIES_FILE()).should("contain", "limit: 3");
        questionEntityIds().should("deep.equal", [entityId]);
      });

      buildDataAppHostApp().should((result) => {
        const output = `${result.stdout}${result.stderr}`;
        expect(output, "the build is refused").to.contain(
          "is not synchronized",
        );
        expect(output).not.to.contain("built in");
      });

      // Synchronizing makes it buildable again.
      sync();
      expectBuildToSucceed();
    });
  });
});
