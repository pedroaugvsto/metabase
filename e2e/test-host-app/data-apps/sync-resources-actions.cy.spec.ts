import { USERS, WRITABLE_DB_ID } from "e2e/support/cypress_data";
import {
  addUserToGroup,
  createDataAppApiKey,
  dataAppHostAppRoot,
  dataAppPermissionGroupId,
  declareDataAppActions,
  moveDataAppModelToCollection,
  publishDataApp,
  removeDataAppActionDeclaration,
  resetDataAppHostAppSources,
  syncDataAppResources,
} from "e2e/support/helpers";
import type { Card } from "metabase-types/api";

const { H } = cy;

const TEST_TABLE = "scoreboard_actions";
const MODEL_NAME = "Scoreboard model";

/** The app is published under its directory's name. */
const APP_SLUG = "vite-6-data-app-host-app";

const APP_ROOT = () => dataAppHostAppRoot();
const RESOURCES_DIR = () => `${APP_ROOT()}/resources`;
const ACTIONS_FILE = () => `${APP_ROOT()}/actions/orders.action.ts`;
const MANIFEST_FILE = () => `${APP_ROOT()}/data_app.yaml`;

const AUTHORED_MANIFEST = `name: Vite 6 Data App
version: 1
path: ./dist/index.js
allowed_hosts:
  - https://allowed.data-app.test
`;

const ENTITY_ID_PATTERN = /copiedActionEntityId: "([A-Za-z0-9_-]{21})"/g;

const SCORE = { team_name: "Data App FC", score: 7 };

/** The copied action entity IDs synchronization wrote, in declaration order. */
const copiedActionEntityIds = () =>
  cy
    .readFile(ACTIONS_FILE())
    .then((source: string) =>
      [...source.matchAll(ENTITY_ID_PATTERN)].map(([, entityId]) => entityId),
    );

const filesIn = (dir: string) =>
  cy
    .exec(`ls "${RESOURCES_DIR()}/${dir}"`)
    .then(({ stdout }) => stdout.split("\n").filter(Boolean));

/** The entity ID of the model copy, from its file name. */
const copiedModelEntityId = () =>
  filesIn("cards").then((files) => {
    const [file] = files.filter((name) => name.startsWith("scoreboard_model_"));
    return file.slice("scoreboard_model_".length, -".yaml".length);
  });

/**
 * Drives the real `sync-resources` CLI against a live instance: it renders the
 * copies of a model and its actions into `resources/`, and a repository pull is
 * what creates them. The unit suites cover the decision logic; this covers the
 * contract end to end.
 */
describe(
  "Embedding SDK: data-app sync-resources (actions)",
  { tags: ["@external", "@actions"] },
  () => {
    const resetHostAppSources = () => {
      resetDataAppHostAppSources();
      cy.writeFile(MANIFEST_FILE(), AUTHORED_MANIFEST);
    };

    beforeEach(() => {
      H.restore("postgres-writable");
      cy.signInAsAdmin();
      H.activateToken("bleeding-edge");

      H.resetTestTable({ type: "postgres", table: TEST_TABLE });
      H.resyncDatabase({ dbId: WRITABLE_DB_ID, tableName: TEST_TABLE });
      H.setActionsEnabledForDB(WRITABLE_DB_ID);
      H.createModelFromTableName({
        tableName: TEST_TABLE,
        modelName: MODEL_NAME,
      });

      resetHostAppSources();
      createDataAppApiKey().as("apiKey");
    });

    after(() => {
      resetHostAppSources();
    });

    const sync = () =>
      cy.get<string>("@apiKey").then((apiKey) => {
        syncDataAppResources(apiKey, APP_ROOT()).then(({ ok, error }) => {
          expect(error, "sync-resources failed").to.eq(null);
          expect(ok).to.eq(true);
        });
      });

    /** Declares a create and an update action on the fixture model. */
    const declareTwoActions = () =>
      cy.get<number>("@modelId").then((modelId) =>
        H.createImplicitAction({ model_id: modelId, kind: "create" }).then(
          ({ body: create }) =>
            H.createImplicitAction({ model_id: modelId, kind: "update" }).then(
              ({ body: update }) => {
                declareDataAppActions(APP_ROOT(), [create.id, update.id]);
                return cy.wrap({ modelId, create, update }, { log: false });
              },
            ),
        ),
      );

    /** The actions Metabase holds on the model copy. */
    const actionsOnCopiedModel = () =>
      copiedModelEntityId().then((entityId) =>
        cy
          .request<Card>(`/api/card/${entityId}`)
          .then(({ body: model }) =>
            cy
              .request<Array<{ entity_id: string }>>(
                `/api/action?model-id=${model.id}`,
              )
              .then(({ body }) => body.map((action) => action.entity_id)),
          ),
      );

    it("renders one copy of the model for two actions, and a copy of each action on it", () => {
      declareTwoActions();
      sync();

      filesIn("cards").should("have.length", 1);
      filesIn("actions").should("have.length", 2);
      copiedActionEntityIds().then((entityIds) => {
        expect(entityIds).to.have.length(2);
        filesIn("actions").then((files) => {
          entityIds.forEach((entityId) =>
            expect(files.some((file) => file.includes(entityId))).to.eq(true),
          );
        });
      });

      // Nothing reaches Metabase until the repository is pulled.
      copiedModelEntityId().then((entityId) =>
        cy
          .request({ url: `/api/card/${entityId}`, failOnStatusCode: false })
          .its("status")
          .should("eq", 404),
      );
    });

    it("lets the app's group execute a published copy but not the action it was copied from", () => {
      declareTwoActions().then(({ modelId, create }) => {
        sync();
        publishDataApp(APP_ROOT(), APP_SLUG);

        // An action is readable through its model, and the fixture model sits
        // in the root collection the normal user's groups can read.
        moveDataAppModelToCollection({
          modelId,
          name: "Source models",
          access: "none",
        });
        dataAppPermissionGroupId(APP_SLUG).then((groupId) =>
          addUserToGroup(groupId, USERS.normal.email),
        );

        copiedActionEntityIds().then(([copiedCreate]) => {
          cy.signInAsNormalUser();
          cy.request({
            method: "POST",
            url: `/api/action/${copiedCreate}/execute`,
            body: { parameters: SCORE },
          })
            .its("status")
            .should("be.oneOf", [200, 204]);

          cy.request({
            method: "POST",
            url: `/api/action/${create.id}/execute`,
            body: { parameters: SCORE },
            failOnStatusCode: false,
          })
            .its("status")
            .should("eq", 403);
        });
      });
    });

    it("deletes a copied action on the next pull once its declaration is removed", () => {
      declareTwoActions().then(({ update }) => {
        sync();
        publishDataApp(APP_ROOT(), APP_SLUG);

        copiedActionEntityIds().then(([copiedCreate, copiedUpdate]) => {
          actionsOnCopiedModel().should("have.members", [
            copiedCreate,
            copiedUpdate,
          ]);

          removeDataAppActionDeclaration(APP_ROOT(), update.id);
          sync();
          filesIn("actions").should("have.length", 1);

          publishDataApp(APP_ROOT(), APP_SLUG, { initializeRepo: false });
          actionsOnCopiedModel().should("deep.equal", [copiedCreate]);
        });
      });
    });

    it("refuses to copy a model that is based on a saved question, and writes nothing", () => {
      cy.get<number>("@modelId").then((modelId) => {
        H.createImplicitAction({ model_id: modelId, kind: "create" }).then(
          ({ body: action }) => declareDataAppActions(APP_ROOT(), [action.id]),
        );

        cy.request<Card>(`/api/card/${modelId}`)
          .then(({ body: model }) =>
            cy.request<Card>("POST", "/api/card", {
              name: "Scoreboard source question",
              display: "table",
              visualization_settings: {},
              dataset_query: model.dataset_query,
            }),
          )
          .then(({ body: question }) =>
            cy.request("PUT", `/api/card/${modelId}`, {
              dataset_query: {
                database: WRITABLE_DB_ID,
                type: "query",
                query: { "source-table": `card__${question.id}` },
              },
            }),
          );
      });

      cy.get<string>("@apiKey").then((apiKey) => {
        syncDataAppResources(apiKey, APP_ROOT()).should(({ ok, error }) => {
          expect(ok, "sync-resources should have refused").to.eq(false);
          expect(error).to.contain(
            "depends on saved questions and cannot be copied into a data app",
          );
        });
      });

      cy.exec(`test ! -e "${RESOURCES_DIR()}"`);
      cy.readFile(MANIFEST_FILE()).should("eq", AUTHORED_MANIFEST);
    });
  },
);
