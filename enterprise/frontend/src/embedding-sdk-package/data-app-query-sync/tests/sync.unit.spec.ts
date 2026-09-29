import fs from "node:fs";
import path from "node:path";

import { load as parseYaml } from "js-yaml";

import { discoverQueries } from "../discover";
import { getDerivedEntityId, isEntityId } from "../entity-ids";
import type { RenderRequest } from "../metabase-client";
import { checkResourcesSynced, syncResources } from "../sync";
import type { RenderResponse } from "../types";

import {
  MANIFEST,
  jsonResponse,
  makeApp,
  setupResourceSyncTests,
  writeAction,
  writeQuery,
} from "./setup";

const SOURCE_ACTION_ENTITY_ID = "sourceActionEntity051";

const ORDERS_QUERY = `export const Orders = defineQuery({ source: { type: "table", id: 1 }, limit: 5 });`;

const CREATE_ACTION = `export const Create = defineAction({ action: { id: 51, entityId: "${SOURCE_ACTION_ENTITY_ID}", parameters: [] } });`;

/** What the render endpoint returns for `request`: the collection, a question per query, and a copy per action. */
function resourcesResponse(request: RenderRequest): RenderResponse {
  const collectionEntityId = request.collection.entity_id;
  const actions = request.actions.map(({ id }) => ({
    id,
    entity_id: getDerivedEntityId(
      "action",
      collectionEntityId,
      SOURCE_ACTION_ENTITY_ID,
    ),
  }));

  return {
    files: [
      {
        path: "collection.yaml",
        model: "Collection",
        entity_id: collectionEntityId,
        content: `entity_id: ${collectionEntityId}\nname: ${JSON.stringify(request.collection.name)}\n`,
      },
      ...request.queries.map(({ entity_id, name }) => ({
        path: `cards/${name.toLowerCase()}_${entity_id}.yaml`,
        model: "Card" as const,
        entity_id,
        content: `entity_id: ${entity_id}\nname: ${name}\n`,
      })),
      ...actions.map(({ entity_id }) => ({
        path: `actions/create_${entity_id}.yaml`,
        model: "Action" as const,
        entity_id,
        content: `entity_id: ${entity_id}\n`,
      })),
    ],
    actions,
  };
}

function mockResourcesEndpoint(respond = resourcesResponse) {
  const requests: RenderRequest[] = [];

  jest.spyOn(global, "fetch").mockImplementation(async (input, init) => {
    const url = new URL(String(input));

    if (
      url.pathname !== "/api/apps/resources/render" ||
      init?.method !== "POST"
    ) {
      throw new Error(`Unexpected ${init?.method} ${url.pathname}`);
    }

    const request: RenderRequest = JSON.parse(String(init.body));
    requests.push(request);
    return jsonResponse(respond(request));
  });

  return requests;
}

const sync = (appRoot: string) =>
  syncResources({
    appRoot,
    metabaseUrl: "http://metabase.test",
    apiKey: "secret",
    log: jest.fn(),
  });

const read = (appRoot: string, file: string) =>
  fs.readFileSync(path.join(appRoot, file), "utf8");

const resourceFiles = (appRoot: string) =>
  ["", "cards", "actions"].flatMap((dir) => {
    const directory = path.join(appRoot, "resources", dir);
    return fs.existsSync(directory)
      ? fs
          .readdirSync(directory, { withFileTypes: true })
          .filter((entry) => entry.isFile())
          .map((entry) => path.join(dir, entry.name))
      : [];
  });

const manifestCollection = (appRoot: string) => {
  const manifest = parseYaml(read(appRoot, "data_app.yaml"));
  return manifest !== null &&
    typeof manifest === "object" &&
    "collection" in manifest
    ? manifest.collection
    : undefined;
};

describe("resource synchronization", () => {
  setupResourceSyncTests();

  it("renders the app's resources, writes them, and records the generated IDs", async () => {
    const appRoot = makeApp();
    writeQuery(appRoot, ORDERS_QUERY);
    writeAction(appRoot, CREATE_ACTION);
    const requests = mockResourcesEndpoint();

    await sync(appRoot);

    const [request] = requests;
    const collection = String(manifestCollection(appRoot));
    const [query] = await discoverQueries(appRoot);
    const actionCopy = getDerivedEntityId(
      "action",
      collection,
      SOURCE_ACTION_ENTITY_ID,
    );

    expect(isEntityId(collection)).toBe(true);
    expect(request).toEqual({
      collection: { entity_id: collection, name: "Data App: Orders" },
      queries: [
        {
          entity_id: query.savedQuestionEntityId,
          name: "Orders",
          query: { stages: [{ source: { type: "table", id: 1 }, limit: 5 }] },
        },
      ],
      actions: [{ id: 51 }],
    });
    expect(read(appRoot, "data_app.yaml").startsWith(MANIFEST)).toBe(true);
    expect(read(appRoot, "queries/orders.query.ts")).toContain(
      `savedQuestionEntityId: "${query.savedQuestionEntityId}"`,
    );
    expect(read(appRoot, "actions/orders.action.ts")).toContain(
      `copiedActionEntityId: "${actionCopy}"`,
    );
    expect(resourceFiles(appRoot).sort()).toEqual(
      [
        "collection.yaml",
        `cards/orders_${query.savedQuestionEntityId}.yaml`,
        `actions/create_${actionCopy}.yaml`,
      ].sort(),
    );
    expect(
      read(
        appRoot,
        `resources/cards/orders_${query.savedQuestionEntityId}.yaml`,
      ),
    ).toBe(
      `# data-app-query-hash: ${query.hash}\nentity_id: ${query.savedQuestionEntityId}\nname: Orders\n`,
    );
    await expect(checkResourcesSynced(appRoot)).resolves.toBeUndefined();
  });

  it("reuses the recorded IDs and removes the files of removed definitions", async () => {
    const appRoot = makeApp();
    writeQuery(appRoot, ORDERS_QUERY);
    writeAction(appRoot, CREATE_ACTION);
    mockResourcesEndpoint();
    await sync(appRoot);
    const collection = manifestCollection(appRoot);
    const [query] = await discoverQueries(appRoot);
    const manifest = read(appRoot, "data_app.yaml");

    fs.rmSync(path.join(appRoot, "actions/orders.action.ts"));
    const requests = mockResourcesEndpoint();
    await sync(appRoot);

    expect(requests[0].collection.entity_id).toBe(collection);
    expect(requests[0].queries[0].entity_id).toBe(query.savedQuestionEntityId);
    expect(read(appRoot, "data_app.yaml")).toBe(manifest);
    expect(resourceFiles(appRoot).sort()).toEqual(
      [
        "collection.yaml",
        `cards/orders_${query.savedQuestionEntityId}.yaml`,
      ].sort(),
    );
  });

  it("writes nothing when rendering fails", async () => {
    const appRoot = makeApp();
    const queryFile = writeQuery(appRoot, ORDERS_QUERY);
    jest.spyOn(global, "fetch").mockResolvedValue(
      new Response("You don't have permission to run Orders.", {
        status: 403,
      }),
    );

    await expect(sync(appRoot)).rejects.toThrow(
      "Metabase returned 403 for POST http://metabase.test/api/apps/resources/render: You don't have permission to run Orders.",
    );

    expect(fs.existsSync(path.join(appRoot, "resources"))).toBe(false);
    expect(read(appRoot, "data_app.yaml")).toBe(MANIFEST);
    expect(fs.readFileSync(queryFile, "utf8")).not.toContain(
      "savedQuestionEntityId",
    );
  });

  it("writes nothing when the render misses a definition", async () => {
    const appRoot = makeApp();
    const actionFile = writeAction(appRoot, CREATE_ACTION);
    mockResourcesEndpoint((request) => ({
      ...resourcesResponse(request),
      actions: [],
    }));

    await expect(sync(appRoot)).rejects.toThrow(
      "The rendered resources have no copy of action 51.",
    );

    expect(fs.existsSync(path.join(appRoot, "resources"))).toBe(false);
    expect(read(appRoot, "data_app.yaml")).toBe(MANIFEST);
    expect(fs.readFileSync(actionFile, "utf8")).not.toContain(
      "copiedActionEntityId",
    );
  });

  it("refuses a flow-style manifest before writing anything", async () => {
    const appRoot = makeApp();
    const manifest = "{ name: Orders, path: ./dist/index.js }";
    fs.writeFileSync(path.join(appRoot, "data_app.yaml"), manifest);
    const requests = mockResourcesEndpoint();

    await expect(sync(appRoot)).rejects.toThrow(
      'Could not add "collection" to data_app.yaml.',
    );

    expect(requests).toHaveLength(0);
    expect(read(appRoot, "data_app.yaml")).toBe(manifest);
    expect(fs.existsSync(path.join(appRoot, "resources"))).toBe(false);
  });

  it("writes nothing when the render response is malformed", async () => {
    const appRoot = makeApp();
    const queryFile = writeQuery(appRoot, ORDERS_QUERY);
    jest
      .spyOn(global, "fetch")
      .mockResolvedValue(jsonResponse({ files: "not a list" }));

    await expect(sync(appRoot)).rejects.toThrow(
      "resources this version of the CLI does not understand",
    );

    expect(fs.existsSync(path.join(appRoot, "resources"))).toBe(false);
    expect(read(appRoot, "data_app.yaml")).toBe(MANIFEST);
    expect(fs.readFileSync(queryFile, "utf8")).not.toContain(
      "savedQuestionEntityId",
    );
  });

  it.each([
    ["no data_app.yaml", null, "data_app.yaml is missing from"],
    [
      "a data_app.yaml without a name",
      "path: ./dist/index.js\n",
      'must declare a "name"',
    ],
    [
      "an invalid collection",
      `${MANIFEST}collection: not-an-entity-id\n`,
      'has an invalid "collection"',
    ],
  ])("refuses %s before rendering", async (_, manifest, message) => {
    const appRoot = makeApp();
    const manifestPath = path.join(appRoot, "data_app.yaml");
    if (manifest === null) {
      fs.rmSync(manifestPath);
    } else {
      fs.writeFileSync(manifestPath, manifest);
    }
    const requests = mockResourcesEndpoint();

    await expect(sync(appRoot)).rejects.toThrow(message);

    expect(requests).toHaveLength(0);
    expect(fs.existsSync(path.join(appRoot, "resources"))).toBe(false);
  });

  it("refuses a resource path outside the resource layout", async () => {
    const appRoot = makeApp();
    mockResourcesEndpoint((request) => {
      const response = resourcesResponse(request);
      return {
        ...response,
        files: [{ ...response.files[0], path: "../../../escape.yaml" }],
      };
    });

    await expect(sync(appRoot)).rejects.toThrow(
      "Refusing to write a resource to ../../../escape.yaml.",
    );
  });

  it("refuses entity IDs another app in the repository uses", async () => {
    const original = makeApp();
    writeQuery(original, ORDERS_QUERY);
    mockResourcesEndpoint();
    await sync(original);

    const copy = makeApp({ slug: "orders-copy", sibling: original });
    fs.copyFileSync(
      path.join(original, "data_app.yaml"),
      path.join(copy, "data_app.yaml"),
    );

    await expect(sync(copy)).rejects.toThrow(
      `orders already uses entity ID ${String(manifestCollection(original))}`,
    );
  });
});

describe("resource synchronization build check", () => {
  setupResourceSyncTests();

  async function syncedApp() {
    const appRoot = makeApp();
    writeQuery(appRoot, ORDERS_QUERY);
    writeAction(appRoot, CREATE_ACTION);
    mockResourcesEndpoint();
    await sync(appRoot);
    return appRoot;
  }

  it("fails for an app that was never synchronized", async () => {
    await expect(checkResourcesSynced(makeApp())).rejects.toThrow(
      "The app's resource collection is not synchronized.",
    );
  });

  it.each([
    [
      "a file outside the resource layout",
      "notes.md",
      "is not a data app resource.",
    ],
    [
      "a directory outside the resource layout",
      "extra/",
      "is not a data app resource.",
    ],
    ["a file that isn't YAML", "cards/notes.txt", "is not a YAML file."],
    ["a directory inside cards/", "cards/nested/", "is not a YAML file."],
  ])(
    "fails for %s, which an import would refuse",
    async (_, entry, message) => {
      const appRoot = await syncedApp();
      const target = path.join(appRoot, "resources", entry);
      if (entry.endsWith("/")) {
        fs.mkdirSync(target);
      } else {
        fs.writeFileSync(target, "x");
      }

      await expect(checkResourcesSynced(appRoot)).rejects.toThrow(
        `resources/${entry.replace(/\/$/, "")} ${message}`,
      );
    },
  );

  it("ignores hidden entries, as an import does", async () => {
    const appRoot = await syncedApp();
    fs.writeFileSync(path.join(appRoot, "resources/cards/.DS_Store"), "x");

    await expect(checkResourcesSynced(appRoot)).resolves.toBeUndefined();
  });

  it("fails when a query changed after it was synchronized", async () => {
    const appRoot = await syncedApp();
    const queryFile = path.join(appRoot, "queries/orders.query.ts");
    fs.writeFileSync(
      queryFile,
      fs.readFileSync(queryFile, "utf8").replace("limit: 5", "limit: 10"),
    );

    await expect(checkResourcesSynced(appRoot)).rejects.toThrow(
      "queries/orders.query.ts:Orders is not synchronized.",
    );
  });

  it("fails for a new query", async () => {
    const appRoot = await syncedApp();
    fs.appendFileSync(
      path.join(appRoot, "queries/orders.query.ts"),
      `export const Products = defineQuery({ source: { type: "table", id: 2 } });\n`,
    );

    await expect(checkResourcesSynced(appRoot)).rejects.toThrow(
      "queries/orders.query.ts:Products is not synchronized.",
    );
  });

  it("fails when an action definition points at another source action", async () => {
    const appRoot = await syncedApp();
    const actionFile = path.join(appRoot, "actions/orders.action.ts");
    fs.writeFileSync(
      actionFile,
      fs
        .readFileSync(actionFile, "utf8")
        .replace(SOURCE_ACTION_ENTITY_ID, "otherSourceActionEnt1"),
    );

    await expect(checkResourcesSynced(appRoot)).rejects.toThrow(
      "actions/orders.action.ts:Create is not synchronized.",
    );
  });

  it("fails for an action named without an entity ID", async () => {
    const appRoot = await syncedApp();
    const actionFile = path.join(appRoot, "actions/orders.action.ts");
    fs.writeFileSync(
      actionFile,
      fs
        .readFileSync(actionFile, "utf8")
        .replace(`entityId: "${SOURCE_ACTION_ENTITY_ID}", `, ""),
    );

    await expect(checkResourcesSynced(appRoot)).rejects.toThrow(
      "actions/orders.action.ts:Create names an action without an entity ID.",
    );
  });

  it("fails when a query's export is renamed", async () => {
    const appRoot = await syncedApp();
    const queryFile = path.join(appRoot, "queries/orders.query.ts");
    fs.writeFileSync(
      queryFile,
      fs
        .readFileSync(queryFile, "utf8")
        .replace("export const Orders", "export const RecentOrders"),
    );

    await expect(checkResourcesSynced(appRoot)).rejects.toThrow(
      "queries/orders.query.ts:RecentOrders is not synchronized.",
    );
  });

  it("fails when a removed query's saved question is still committed", async () => {
    const appRoot = await syncedApp();
    fs.writeFileSync(
      path.join(appRoot, "queries/orders.query.ts"),
      `import { defineQuery } from "@metabase/embedding-sdk-react/data-app";\n`,
    );

    await expect(checkResourcesSynced(appRoot)).rejects.toThrow(
      /resources\/cards\/orders_.*\.yaml belongs to a removed definition/,
    );
  });
});
