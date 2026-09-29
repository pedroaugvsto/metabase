import fs from "node:fs";

import {
  ACTION_DEFINITIONS,
  QUERY_DEFINITIONS,
  injectGeneratedId,
} from "../ast/query-source";
import { discoverActions, discoverQueries } from "../discover";

import {
  makeApp,
  setupResourceSyncTests,
  writeAction,
  writeQuery,
} from "./setup";

const QUESTION_10 = "questionEntityId00010";
const QUESTION_20 = "questionEntityId00020";
const QUESTION_30 = "questionEntityId00030";
const ACTION_91 = "actionEntityId0000091";
const ACTION_92 = "actionEntityId0000092";

describe("query discovery", () => {
  setupResourceSyncTests();

  it("discovers a direct named definition", async () => {
    const appRoot = makeApp();
    writeQuery(
      appRoot,
      `export const Orders = defineQuery({ savedQuestionEntityId: "${QUESTION_10}", source: { type: "table", id: 1 }, limit: 5 });`,
    );

    await expect(discoverQueries(appRoot)).resolves.toEqual([
      expect.objectContaining({
        exportName: "Orders",
        savedQuestionEntityId: QUESTION_10,
        hash: expect.stringMatching(/^v1:sha256:[a-f0-9]{64}$/),
      }),
    ]);
  });

  it("rejects copied saved question IDs", async () => {
    const appRoot = makeApp();

    writeQuery(
      appRoot,
      `export const First = defineQuery({ savedQuestionEntityId: "${QUESTION_10}", source: { type: "table", id: 1 } });
       export const Second = defineQuery({ savedQuestionEntityId: "${QUESTION_10}", source: { type: "table", id: 1 } });`,
    );

    await expect(discoverQueries(appRoot)).rejects.toThrow(
      `Saved question ${QUESTION_10} is referenced by`,
    );
  });

  it("rejects a hand-edited saved question ID", async () => {
    const appRoot = makeApp();

    writeQuery(
      appRoot,
      `export const Orders = defineQuery({ savedQuestionEntityId: 10, source: { type: "table", id: 1 } });`,
    );

    await expect(discoverQueries(appRoot)).rejects.toThrow(
      "has an invalid savedQuestionEntityId",
    );
  });

  it("replaces a quoted saved question ID", async () => {
    const appRoot = makeApp();
    const filePath = writeQuery(
      appRoot,
      `export const Orders = defineQuery({ "savedQuestionEntityId": "${QUESTION_10}", source: { type: "table", id: 1 } });`,
    );
    const [query] = await discoverQueries(appRoot);

    injectGeneratedId(query, QUERY_DEFINITIONS, QUESTION_20);

    const contents = fs.readFileSync(filePath, "utf8");
    expect(contents).toContain(`savedQuestionEntityId: "${QUESTION_20}"`);
    expect(contents).not.toContain(QUESTION_10);
  });

  it.each([
    ["a shorthand ID", "savedQuestionEntityId"],
    ["a spread ID", "...defaults"],
    [
      "an explicit ID followed by a spread",
      `savedQuestionEntityId: "${QUESTION_10}", ...defaults`,
    ],
  ])("reuses the generated query ID instead of %s", async (_, properties) => {
    const appRoot = makeApp();

    writeQuery(
      appRoot,
      `const savedQuestionEntityId = "${QUESTION_10}";
       const defaults = { savedQuestionEntityId };
       export const Orders = defineQuery({ ${properties}, source: { type: "table", id: 1 } });`,
    );

    const [query] = await discoverQueries(appRoot);
    injectGeneratedId(query, QUERY_DEFINITIONS, QUESTION_20);

    const [updatedQuery] = await discoverQueries(appRoot);
    expect(updatedQuery.savedQuestionEntityId).toBe(QUESTION_20);
    injectGeneratedId(updatedQuery, QUERY_DEFINITIONS, QUESTION_30);

    const [recoveredQuery] = await discoverQueries(appRoot);
    expect(recoveredQuery.savedQuestionEntityId).toBe(QUESTION_30);
  });
});

describe("action discovery", () => {
  setupResourceSyncTests();

  it("discovers named definitions and the action each references", async () => {
    const appRoot = makeApp();

    writeAction(
      appRoot,
      `export const Create = defineAction({ copiedActionEntityId: "${ACTION_91}", action: { id: 51, entityId: "sourceActionEntity051", parameters: [] } });
       export const Update = defineAction({ action: { id: 52, parameters: [] } });`,
    );

    await expect(discoverActions(appRoot)).resolves.toEqual([
      expect.objectContaining({
        exportName: "Create",
        sourceActionId: 51,
        sourceActionEntityId: "sourceActionEntity051",
        copiedActionEntityId: ACTION_91,
      }),
      expect.objectContaining({
        exportName: "Update",
        sourceActionId: 52,
        sourceActionEntityId: undefined,
        copiedActionEntityId: undefined,
      }),
    ]);
  });

  it("reuses the generated action ID instead of shorthand and spread IDs", async () => {
    const appRoot = makeApp();

    writeAction(
      appRoot,
      `const copiedActionEntityId = "${ACTION_91}";
       const defaults = { copiedActionEntityId };
       export const Create = defineAction({ copiedActionEntityId, ...defaults, action: { id: 51, parameters: [] } });`,
    );

    const [action] = await discoverActions(appRoot);
    injectGeneratedId(action, ACTION_DEFINITIONS, ACTION_92);

    const [updatedAction] = await discoverActions(appRoot);
    expect(updatedAction.copiedActionEntityId).toBe(ACTION_92);
  });

  it("rejects two definitions claiming the same source action", async () => {
    const appRoot = makeApp();

    writeAction(
      appRoot,
      `export const First = defineAction({ action: { id: 51, parameters: [] } });
       export const Second = defineAction({ action: { id: 51, parameters: [] } });`,
    );

    await expect(discoverActions(appRoot)).rejects.toThrow(
      "Action 51 is referenced by",
    );
  });

  it("rejects a copied definition that carries another's generated ID", async () => {
    const appRoot = makeApp();

    writeAction(
      appRoot,
      `export const First = defineAction({ copiedActionEntityId: "${ACTION_91}", action: { id: 51, parameters: [] } });
       export const Second = defineAction({ copiedActionEntityId: "${ACTION_91}", action: { id: 52, parameters: [] } });`,
    );

    await expect(discoverActions(appRoot)).rejects.toThrow(
      `Generated action ${ACTION_91} is referenced by`,
    );
  });

  it("rejects a definition that does not reference a generated action", async () => {
    const appRoot = makeApp();

    writeAction(
      appRoot,
      `export const Create = defineAction({ action: { name: "not a schema entry" } });`,
    );

    await expect(discoverActions(appRoot)).rejects.toThrow(
      "must reference a generated action",
    );
  });

  it("rejects a hand-edited generated ID", async () => {
    const appRoot = makeApp();

    writeAction(
      appRoot,
      `export const Create = defineAction({ copiedActionEntityId: 91, action: { id: 51, parameters: [] } });`,
    );

    await expect(discoverActions(appRoot)).rejects.toThrow(
      "has an invalid copiedActionEntityId",
    );
  });

  it("requires a definition to initialize an exported variable", async () => {
    const appRoot = makeApp();

    writeAction(
      appRoot,
      `const Create = defineAction({ action: { id: 51, parameters: [] } });`,
    );

    await expect(discoverActions(appRoot)).rejects.toThrow(
      "defineAction must directly initialize a named exported variable",
    );
  });
});
