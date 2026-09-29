import fs from "node:fs";
import os from "node:os";
import path from "node:path";

export function setupResourceSyncTests(): void {
  const repoRoots: string[] = [];

  afterEach(() => {
    jest.restoreAllMocks();

    repoRoots.forEach((repoRoot) =>
      fs.rmSync(repoRoot, { recursive: true, force: true }),
    );
  });

  trackedRepoRoots = repoRoots;
}

let trackedRepoRoots: string[] | undefined;

export const MANIFEST = `# The app's name, shown in Metabase.
name: Orders

path: ./dist/index.js
`;

/** Creates `data_apps/<slug>/` in a fresh repository, or next to `sibling`. */
export function makeApp({
  slug = "orders",
  sibling,
}: { slug?: string; sibling?: string } = {}) {
  const repoRoot = sibling
    ? path.dirname(path.dirname(sibling))
    : fs.mkdtempSync(path.join(os.tmpdir(), "data-app-query-sync-"));

  if (!sibling) {
    trackedRepoRoots?.push(repoRoot);
  }

  const appRoot = path.join(repoRoot, "data_apps", slug);

  fs.mkdirSync(path.join(appRoot, "queries"), { recursive: true });
  fs.mkdirSync(path.join(appRoot, "actions"));
  fs.writeFileSync(path.join(appRoot, "data_app.yaml"), MANIFEST);

  const packageRoot = path.join(
    appRoot,
    "node_modules/@metabase/embedding-sdk-react",
  );

  fs.mkdirSync(packageRoot, { recursive: true });
  fs.writeFileSync(
    path.join(packageRoot, "package.json"),
    JSON.stringify({
      name: "@metabase/embedding-sdk-react",
      exports: { "./data-app": "./data-app.js" },
    }),
  );

  fs.writeFileSync(
    path.join(packageRoot, "data-app.js"),
    [
      "exports.defineQuery = (query) => query;",
      "exports.defineAction = (definition) => definition;",
    ].join("\n"),
  );

  return appRoot;
}

export function writeAction(appRoot: string, body: string) {
  const filePath = path.join(appRoot, "actions/orders.action.ts");
  fs.writeFileSync(
    filePath,
    `import { defineAction } from "@metabase/embedding-sdk-react/data-app";\n${body}\n`,
  );
  return filePath;
}

export function writeQuery(appRoot: string, body: string) {
  const filePath = path.join(appRoot, "queries/orders.query.ts");
  fs.writeFileSync(
    filePath,
    `import { defineQuery } from "@metabase/embedding-sdk-react/data-app";\n${body}\n`,
  );
  return filePath;
}

export function jsonResponse(body: unknown, status = 200) {
  return new Response(status === 204 ? null : JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}
