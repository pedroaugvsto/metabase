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

const isVisible = (entry: fs.Dirent) => !entry.name.startsWith(".");

const listVisibleEntries = (directory: string): fs.Dirent[] =>
  fs.existsSync(directory)
    ? fs
        .readdirSync(directory, { withFileTypes: true })
        .filter(isVisible)
        .sort((a, b) => a.name.localeCompare(b.name))
    : [];

// Metabase refuses the same layout on import, so a tree it would reject never
// passes a sync or a build here.
function listModelFiles(resourcesRoot: string, model: FileModel) {
  const directory = MODEL_DIRS[model];

  return listVisibleEntries(path.join(resourcesRoot, directory)).map(
    (entry) => {
      const filePath = `${directory}/${entry.name}`;

      if (!entry.isFile() || !entry.name.endsWith(".yaml")) {
        throw new Error(`${RESOURCES_DIR}/${filePath} is not a YAML file.`);
      }

      return { path: filePath, model };
    },
  );
}

function listResourcePaths(
  appRoot: string,
): Array<{ path: string; model: ResourceModel }> {
  const resourcesRoot = path.join(appRoot, RESOURCES_DIR);
  const modelDirs = new Set<string>(Object.values(MODEL_DIRS));
  let hasCollection = false;

  for (const entry of listVisibleEntries(resourcesRoot)) {
    if (entry.name === COLLECTION_FILE && entry.isFile()) {
      hasCollection = true;
    } else if (!(modelDirs.has(entry.name) && entry.isDirectory())) {
      throw new Error(
        `${RESOURCES_DIR}/${entry.name} is not a data app resource.`,
      );
    }
  }

  return [
    ...(hasCollection
      ? [{ path: COLLECTION_FILE, model: "Collection" as const }]
      : []),
    ...FILE_MODELS.flatMap((model) => listModelFiles(resourcesRoot, model)),
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
