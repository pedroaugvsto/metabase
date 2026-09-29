import fs from "node:fs";
import path from "node:path";

import { load as parseYaml } from "js-yaml";

import { isRecord } from "./guards";
import type { RenderedResourceFile, ResourceModel } from "./types";

export const RESOURCES_DIR = "resources";

const COLLECTION_FILE = "collection.yaml";

type FileModel = Exclude<ResourceModel, "Collection">;

const MODEL_DIRS: Record<FileModel, string> = {
  Card: "cards",
  Action: "actions",
};

const FILE_MODELS: readonly FileModel[] = ["Card", "Action"];

const QUERY_HASH_HEADER = "# data-app-query-hash: ";

export interface ResourceFile {
  /** Relative to the app's `resources/` directory. */
  path: string;
  model: ResourceModel;
  entityId: unknown;
  /** The fingerprint of the query definition a saved question was rendered from. */
  queryHash?: string;
}

const listYamlFiles = (directory: string): string[] =>
  fs.existsSync(directory)
    ? fs
        .readdirSync(directory, { withFileTypes: true })
        .filter((entry) => entry.isFile() && entry.name.endsWith(".yaml"))
        .map((entry) => entry.name)
        .sort()
    : [];

function listResourcePaths(
  appRoot: string,
): Array<{ path: string; model: ResourceModel }> {
  const resourcesRoot = path.join(appRoot, RESOURCES_DIR);
  const collection: Array<{ path: string; model: ResourceModel }> =
    fs.existsSync(path.join(resourcesRoot, COLLECTION_FILE))
      ? [{ path: COLLECTION_FILE, model: "Collection" }]
      : [];

  return [
    ...collection,
    ...FILE_MODELS.flatMap((model) =>
      listYamlFiles(path.join(resourcesRoot, MODEL_DIRS[model])).map(
        (name) => ({ path: `${MODEL_DIRS[model]}/${name}`, model }),
      ),
    ),
  ];
}

function readResourceFile(
  appRoot: string,
  file: { path: string; model: ResourceModel },
): ResourceFile {
  const content = fs.readFileSync(
    path.join(appRoot, RESOURCES_DIR, file.path),
    "utf8",
  );
  const [firstLine] = content.split("\n", 1);
  let parsed: unknown;

  try {
    parsed = parseYaml(content);
  } catch (error) {
    throw new Error(
      `Could not parse ${RESOURCES_DIR}/${file.path}: ${String(error)}`,
    );
  }

  return {
    ...file,
    entityId: isRecord(parsed) ? parsed.entity_id : undefined,
    queryHash: firstLine.startsWith(QUERY_HASH_HEADER)
      ? firstLine.slice(QUERY_HASH_HEADER.length).trim()
      : undefined,
  };
}

/** The app's committed resource files. */
export function readResources(appRoot: string): ResourceFile[] {
  return listResourcePaths(appRoot).map((file) =>
    readResourceFile(appRoot, file),
  );
}

function assertResourcePath(file: RenderedResourceFile) {
  const valid =
    file.model === "Collection"
      ? file.path === COLLECTION_FILE
      : new RegExp(`^${MODEL_DIRS[file.model]}/[^/\\\\]+\\.yaml$`).test(
          file.path,
        );

  if (!valid) {
    throw new Error(`Refusing to write a resource to ${file.path}.`);
  }
}

/**
 * Replaces the app's resource files with `files`. A saved question's file
 * records the fingerprint of the query it was rendered from, so the build can
 * tell a stale file without calling Metabase.
 */
export function writeResources(
  appRoot: string,
  files: RenderedResourceFile[],
  queryHashes: ReadonlyMap<string, string>,
) {
  files.forEach(assertResourcePath);

  const resourcesRoot = path.join(appRoot, RESOURCES_DIR);
  const nextPaths = new Set(files.map((file) => file.path));
  const removed = listResourcePaths(appRoot).filter(
    (file) => !nextPaths.has(file.path),
  );

  for (const file of removed) {
    fs.rmSync(path.join(resourcesRoot, file.path));
  }

  for (const file of files) {
    const filePath = path.join(resourcesRoot, file.path);
    const queryHash = queryHashes.get(file.entity_id);
    const header =
      file.model === "Card" && queryHash
        ? `${QUERY_HASH_HEADER}${queryHash}\n`
        : "";

    fs.mkdirSync(path.dirname(filePath), { recursive: true });
    fs.writeFileSync(filePath, `${header}${file.content}`);
  }

  return { written: files.length, removed: removed.length };
}
