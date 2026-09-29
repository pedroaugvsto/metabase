import { USERS, WRITABLE_DB_ID } from "e2e/support/cypress_data";
import {
  addUserToGroup,
  createDataAppApiKey,
  dataAppIframe,
  dataAppPermissionGroupId,
  mockDataApp,
  publishDataApp,
  syncDataAppResources,
} from "e2e/support/helpers";

const { H } = cy;

const TEST_TABLE = "scoreboard_actions";
const MODEL_NAME = "Scoreboard model";

const APP_SLUG = "synced-actions-app";
const APP_DISPLAY_NAME = "Synced Actions App";

const APP_ROOT = () =>
  `${Cypress.config("projectRoot")}/e2e/support/assets/data-apps/${APP_SLUG}`;
const ACTION_FILE = () => `${APP_ROOT()}/actions/orders.action.ts`;
const MANIFEST_FILE = () => `${APP_ROOT()}/data_app.yaml`;
const RESOURCES_DIR = () => `${APP_ROOT()}/resources`;

const ENTITY_ID_PATTERN = /copiedActionEntityId: "([A-Za-z0-9_-]{21})"/;

/** The manifest as source control holds it. */
const AUTHORED_MANIFEST = `name: ${APP_DISPLAY_NAME}\npath: ./dist/index.js\n`;

/**
 * The declaration as source control holds it, with no generated ID yet. The
 * source action's entity ID comes from the generated schema.
 */
const declaration = (sourceActionId: number, sourceEntityId?: string) =>
  [
    'import { defineAction } from "@metabase/embedding-sdk-react/data-app";',
    "",
    "export const CreateScore = defineAction({",
    sourceEntityId === undefined
      ? `  action: { id: ${sourceActionId}, parameters: [] },`
      : `  action: { id: ${sourceActionId}, entityId: "${sourceEntityId}", parameters: [] },`,
    "});",
    "",
  ].join("\n");

/**
 * The action half of the production path. Outside the dev preview
 * `toExecutableActionId` runs `copiedActionEntityId` — the copy hanging off the
 * copied model — because that is the only action an app's viewers may execute.
 * The copies reach Metabase through `resources/` and a repository pull.
 */
describe(
  "scenarios > data apps > sync-resources in production (actions)",
  { tags: ["@external", "@actions"] },
  () => {
    // Restore the source files and remove what synchronization wrote.
    const restoreAuthoredFixture = () => {
      cy.writeFile(ACTION_FILE(), declaration(1));
      cy.writeFile(MANIFEST_FILE(), AUTHORED_MANIFEST);
      cy.task("removeDataAppPaths", { paths: [RESOURCES_DIR()] });
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

      restoreAuthoredFixture();
      createDataAppApiKey().as("apiKey");
    });

    after(() => {
      restoreAuthoredFixture();
    });

    /**
     * Declares the model's action, synchronizes and publishes the app, and
     * returns the authored action's ID and its copy's entity ID.
     */
    const syncApp = () =>
      cy.get<number>("@modelId").then((modelId) =>
        H.createImplicitAction({ model_id: modelId, kind: "create" }).then(
          ({ body: action }) => {
            cy.writeFile(
              ACTION_FILE(),
              declaration(action.id, action.entity_id),
            );

            return cy.get<string>("@apiKey").then((apiKey) =>
              syncDataAppResources(apiKey, APP_ROOT()).then(({ ok, error }) => {
                expect(error, "sync-resources failed").to.eq(null);
                expect(ok).to.eq(true);

                publishDataApp(APP_ROOT(), APP_SLUG);

                return cy.readFile(ACTION_FILE()).then((source: string) => {
                  const copiedActionEntityId =
                    ENTITY_ID_PATTERN.exec(source)?.[1];

                  if (!copiedActionEntityId) {
                    throw new Error("The sync wrote no copiedActionEntityId.");
                  }

                  return cy.wrap(
                    { sourceActionId: action.id, copiedActionEntityId },
                    { log: false },
                  );
                });
              }),
            );
          },
        ),
      );

    it("executes the synchronized copy rather than the authored action", () => {
      syncApp().then(({ copiedActionEntityId }) => {
        cy.intercept("POST", "/api/action/*/execute").as("execute");
        mockDataApp(APP_SLUG, { displayName: APP_DISPLAY_NAME });
        cy.visit(`/apps/${APP_SLUG}`);

        dataAppIframe(APP_DISPLAY_NAME).within(() => {
          cy.findByTestId("action-execute", { timeout: 30000 }).click();
          cy.findByTestId("action-output").should("have.text", "executed");
        });

        // The proof that production took the synchronized path.
        cy.wait("@execute")
          .its("request.url")
          .should("contain", `/api/action/${copiedActionEntityId}/execute`);
      });
    });

    it("lets a member of the app's group execute it", () => {
      syncApp().then(({ copiedActionEntityId }) => {
        dataAppPermissionGroupId(APP_SLUG).then((groupId) => {
          addUserToGroup(groupId, USERS.normal.email);

          cy.signInAsNormalUser();
          cy.intercept("POST", "/api/action/*/execute").as("execute");
          mockDataApp(APP_SLUG, { displayName: APP_DISPLAY_NAME });
          cy.visit(`/apps/${APP_SLUG}`);

          dataAppIframe(APP_DISPLAY_NAME).within(() => {
            cy.findByTestId("action-execute", { timeout: 30000 }).click();
            cy.findByTestId("action-output").should("have.text", "executed");
          });

          cy.wait("@execute")
            .its("request.url")
            .should("contain", `/api/action/${copiedActionEntityId}/execute`);
        });
      });
    });
  },
);
