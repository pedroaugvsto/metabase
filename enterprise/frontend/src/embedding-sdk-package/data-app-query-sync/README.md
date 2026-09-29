# Resource sync

A data app runs in a browser sandbox as one of its viewers, not as its author. Those viewers reach
Metabase through a permission group that can read exactly one collection: the app's own. So a query
the app runs, and an action it triggers, have to be reachable **through that collection** or they
fail on permissions.

Resource sync makes that true without touching Metabase. `npm run sync-resources`
(`embedding-sdk-react data-apps sync-resources`) reads the definitions an app commits, asks Metabase to
render them as serdes YAML, and writes that YAML into the app's `resources/` directory. Nothing changes
in Metabase until the repository is pulled: the pull loads the files (see
`metabase_enterprise/data_apps/resource_load.clj`), so a local experiment can't break the running app,
and the saved questions never get ahead of the app code that is deployed.

## What an app declares

Two directories at the data-app root, beside `package.json`:

```
queries/orders.query.ts     export const RevenueQuery = defineQuery({ source: schema.tables.orders })
actions/orders.action.ts    export const CreateOrder = defineAction({ action: schema.models.orders.actions.create })
```

Nothing else is scanned — definitions under `src/` are invisible to sync, which is a common
authoring mistake.

## What sync writes

```
data_app.yaml                      collection: <entity ID>, appended on the first run
resources/collection.yaml          the app's resource collection
resources/cards/*.yaml             a saved question per query, and copies of the metrics and models they use
resources/actions/*.yaml           a copy of each action, on the copy of its model
queries/*.query.ts                 savedQuestionEntityId written into each definition
actions/*.action.ts                copiedActionEntityId written into each definition
```

Commit all of it. The bundle addresses the saved questions and actions by those entity IDs, and the
pull that delivers the bundle delivers the YAML with it.

## The pipeline

`syncResources` (`sync.ts`):

1. **Discover** every `defineQuery` / `defineAction` in those directories (`discover.ts`).
2. **Assign IDs.** The collection and each new query get a random entity ID. IDs already written are
   kept, so renaming an export keeps its saved question.
3. **Check siblings.** Another app in `data_apps/` using one of those IDs (a copied app directory) is
   refused: Metabase loads by entity ID, so the copy would take over the original's content.
4. **Render** with `POST /api/apps/resources/render`. Metabase resolves each query, checks the caller
   can run it and read each action and its model, and returns the files. It writes nothing.
5. **Write** the files, removing any resource file the render didn't return, then append
   `collection` to `data_app.yaml` and inject the entity IDs into the definitions. A failed render
   writes nothing at all.

## Why actions copy a whole model

An action's permissions resolve through its parent model's collection
(`metabase.actions.models`, `perms-objects-set`), so making an action reachable means copying its
model into the app's collection and the action onto that copy. Metrics a query uses are copied for the
same reason, and the question reads the copy.

Copies get entity IDs derived from the app's collection and the source's entity ID
(`entity-ids.ts`, mirroring the backend's `derived-entity-id`). Rendering again addresses the same copies
without storing a mapping.

## Discovery is deliberately strict

`discover.ts` parses each file twice over. The TypeScript AST locates `defineQuery`/`defineAction`
calls and enforces that each one directly initializes a **named exported** variable with a single
object literal. The file is then bundled with esbuild and evaluated — **twice** — and the two results
compared, so a definition that isn't deterministic can't produce a fingerprint that changes under it.

Write-back re-parses from disk rather than reusing the AST from discovery, because injection runs
sequentially and an earlier injection into the same file shifts every offset after it.

## The build check is offline

`checkResourcesSynced` runs at `buildStart` for production builds and fails the build when the
committed files no longer describe the definitions:

- a definition without its generated entity ID;
- a query whose fingerprint (its export name, table, and clauses) differs from the one in its saved
  question's file header (`# data-app-query-hash: …`; serdes ignores YAML comments);
- an action whose generated ID isn't the copy of the action it now names, or that names an action the
  generated schema gave no entity ID;
- a saved question or action file no definition claims any more.

`npm run build` never calls Metabase, so it needs no credentials and can't mint IDs in CI. What it
can't see is an edit made upstream to a source model, action, or metric; re-run sync-resources to
pick that up.

## Dev preview vs production build

Generated IDs only take effect in a production build. `isDataAppDev()` gates both swaps, so an app
runs against the original table and the authored action before it has ever been synchronized:

|              | Dev preview         | Production                                   |
| ------------ | ------------------- | -------------------------------------------- |
| query source | the table           | the saved question (`savedQuestionEntityId`) |
| action       | the authored action | the copy (`copiedActionEntityId`)            |

Both swaps live in the bundle — `toSourceInput` and `toExecutableActionId` — not in app code, so an
author passes the definition and never branches on the environment.
