import fs from "node:fs";
import path from "node:path";

import {
  ACTION_DEFINITIONS,
  QUERY_DEFINITIONS,
  injectGeneratedId,
} from "./ast/query-source";
import { discoverActions, discoverQueries } from "./discover";
import { generateEntityId, getDerivedEntityId, isEntityId } from "./entity-ids";
import {
  MANIFEST_FILE,
  manifestWithCollection,
  readManifest,
  writeManifest,
} from "./manifest";
import { getRelativeDefinitionLocation } from "./messages";
import { MetabaseClient } from "./metabase-client";
import { RESOURCES_DIR, readResources, writeResources } from "./resources";
import type {
  DiscoveredAction,
  DiscoveredQuery,
  RenderResponse,
} from "./types";

export interface SyncResourcesOptions {
  appRoot: string;
  metabaseUrl: string;
  apiKey: string;
  log?: (message: string) => void;
}

function queryWithoutGeneratedId(query: DiscoveredQuery) {
  const authored = { ...query.query };
  delete authored[QUERY_DEFINITIONS.idKey];
  return authored;
}

/** Best effort: Metabase refuses a collision too, so a sibling that can't be read is skipped rather than failing this app. */
function readUsedEntityIds(appRoot: string): string[] {
  try {
    return [
      readManifest(appRoot).collection,
      ...readResources(appRoot).map((file) => file.entityId),
    ].filter(isEntityId);
  } catch {
    return [];
  }
}

/**
 * Metabase loads every app's resources by entity ID, so an ID another app in
 * `data_apps/` already uses (a copied app directory, a copied definition) would
 * let one app take over the other's collection or questions.
 */
function assertNotUsedByOtherApps(appRoot: string, entityIds: Set<string>) {
  const appsRoot = path.dirname(appRoot);
  const siblings = fs
    .readdirSync(appsRoot, { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => path.join(appsRoot, entry.name))
    .filter(
      (dir) => dir !== appRoot && fs.existsSync(path.join(dir, MANIFEST_FILE)),
    );

  for (const sibling of siblings) {
    const shared = readUsedEntityIds(sibling).find((entityId) =>
      entityIds.has(entityId),
    );

    if (shared) {
      throw new Error(
        `${path.relative(appsRoot, sibling)} already uses entity ID ${shared}. If this app was copied from it, remove "collection" from data_app.yaml and every generated entity ID from the definitions, then run sync-resources again.`,
      );
    }
  }
}

/** Everything the render must return before any file is written, so a bad response writes nothing. */
function copiedActionEntityIdsOf(
  rendered: RenderResponse,
  actions: DiscoveredAction[],
  questionEntityIds: string[],
) {
  const copiedActionEntityIds = new Map(
    rendered.actions.map(({ id, entity_id }) => [id, entity_id]),
  );
  const cardEntityIds = new Set(
    rendered.files
      .filter((file) => file.model === "Card")
      .map((file) => file.entity_id),
  );
  const missingAction = actions.find(
    (action) => !copiedActionEntityIds.has(action.sourceActionId),
  );
  const missingQuestion = questionEntityIds.find(
    (entityId) => !cardEntityIds.has(entityId),
  );

  if (missingAction) {
    throw new Error(
      `The rendered resources have no copy of action ${missingAction.sourceActionId}.`,
    );
  }

  if (missingQuestion) {
    throw new Error(
      `The rendered resources have no saved question ${missingQuestion}.`,
    );
  }

  return copiedActionEntityIds;
}

export async function syncResources({
  appRoot: appDirectory,
  metabaseUrl,
  apiKey,
  log = (message) => process.stdout.write(`${message}\n`),
}: SyncResourcesOptions) {
  const appRoot = path.resolve(appDirectory);
  const [queries, actions] = await Promise.all([
    discoverQueries(appRoot),
    discoverActions(appRoot),
  ]);
  const manifest = readManifest(appRoot);
  const collectionEntityId = manifest.collection ?? generateEntityId();
  const nextManifest = manifest.collection
    ? undefined
    : manifestWithCollection(appRoot, collectionEntityId);
  const questions = queries.map((query) => ({
    query,
    entityId: query.savedQuestionEntityId ?? generateEntityId(),
  }));

  assertNotUsedByOtherApps(
    appRoot,
    new Set([collectionEntityId, ...questions.map(({ entityId }) => entityId)]),
  );

  const client = new MetabaseClient(metabaseUrl, apiKey);
  const rendered = await client.renderResources({
    collection: {
      entity_id: collectionEntityId,
      name: `Data App: ${manifest.name}`,
    },
    queries: questions.map(({ query, entityId }) => ({
      entity_id: entityId,
      name: query.exportName,
      query: { stages: [queryWithoutGeneratedId(query)] },
    })),
    actions: [...new Set(actions.map((action) => action.sourceActionId))].map(
      (id) => ({ id }),
    ),
  });

  const copiedActionEntityIds = copiedActionEntityIdsOf(
    rendered,
    actions,
    questions.map(({ entityId }) => entityId),
  );
  const { written, removed } = writeResources(
    appRoot,
    rendered.files,
    new Map(questions.map(({ query, entityId }) => [entityId, query.hash])),
  );

  if (nextManifest) {
    writeManifest(appRoot, nextManifest);
  }

  for (const { query, entityId } of questions) {
    if (query.savedQuestionEntityId !== entityId) {
      injectGeneratedId(query, QUERY_DEFINITIONS, entityId);
    }
  }

  for (const action of actions) {
    const entityId = copiedActionEntityIds.get(action.sourceActionId);

    if (entityId && action.copiedActionEntityId !== entityId) {
      injectGeneratedId(action, ACTION_DEFINITIONS, entityId);
    }
  }

  log(
    `Wrote ${written} resource files to ${RESOURCES_DIR}/ and removed ${removed}. Commit them, then pull the repository to apply them.`,
  );
}

function notSynchronized(
  appRoot: string,
  definition: DiscoveredQuery | DiscoveredAction,
): never {
  throw new Error(
    `${getRelativeDefinitionLocation(appRoot, definition)} is not synchronized. Run \`npm run sync-resources\`, commit its output, and rebuild.`,
  );
}

/**
 * Fails the build when the committed resource files no longer describe the
 * app's definitions. Reads nothing from Metabase.
 */
export async function checkResourcesSynced(appDirectory: string) {
  const appRoot = path.resolve(appDirectory);
  const [queries, actions] = await Promise.all([
    discoverQueries(appRoot),
    discoverActions(appRoot),
  ]);
  const { collection } = readManifest(appRoot);
  const files = readResources(appRoot);
  const filesByEntityId = new Map(
    files.map((file) => [`${file.model}:${String(file.entityId)}`, file]),
  );

  if (!collection || !filesByEntityId.has(`Collection:${collection}`)) {
    throw new Error(
      `The app's resource collection is not synchronized. Run \`npm run sync-resources\`, commit its output, and rebuild.`,
    );
  }

  for (const query of queries) {
    const file = filesByEntityId.get(`Card:${query.savedQuestionEntityId}`);

    if (!query.savedQuestionEntityId || file?.queryHash !== query.hash) {
      notSynchronized(appRoot, query);
    }
  }

  for (const action of actions) {
    const { copiedActionEntityId, sourceActionEntityId } = action;

    if (!sourceActionEntityId) {
      throw new Error(
        `${getRelativeDefinitionLocation(appRoot, action)} names an action without an entity ID. Regenerate the data app schema, run \`npm run sync-resources\`, and rebuild.`,
      );
    }

    if (
      !copiedActionEntityId ||
      copiedActionEntityId !==
        getDerivedEntityId("action", collection, sourceActionEntityId) ||
      !filesByEntityId.has(`Action:${copiedActionEntityId}`)
    ) {
      notSynchronized(appRoot, action);
    }
  }

  const definedEntityIds = new Set<unknown>([
    ...queries.map((query) => query.savedQuestionEntityId),
    ...actions.map((action) => action.copiedActionEntityId),
  ]);
  const removed = files.find(
    (file) =>
      (file.model === "Action" || file.queryHash !== undefined) &&
      !definedEntityIds.has(file.entityId),
  );

  if (removed) {
    throw new Error(
      `${RESOURCES_DIR}/${removed.path} belongs to a removed definition. Run \`npm run sync-resources\`, commit its output, and rebuild.`,
    );
  }
}
