# Data apps (backend)

A **data app** is a JS bundle, authored in a git repository, that Metabase serves and runs inside a
Near Membrane sandbox in the browser. This README covers the backend half: how apps get from the
repository into the app DB, and how they're served. The sandbox, the app runtime, and the host
iframe live on the frontend and are out of scope here.

## Where apps come from

Apps are **not uploaded**. A single repository is connected to Metabase through remote-sync
(Admin → Settings → Remote sync), and each app lives in its own directory under `data_apps/` at the
repo root:

```
data_apps/
  sales/                   # the directory name is the slug: /apps/sales
    data_app.yaml
    dist/index.js          # the built bundle, committed
    resources/             # the app's collection, saved questions, and actions, as serdes YAML
  ops/
    data_app.yaml
    dist/app.js
    resources/
```

`data_app.yaml` declares the display name, the bundle path relative to the app's own directory, and
`collection`, the entity ID of the app's resource collection, plus three optional fields: a one-line `description` shown beside the name in the admin UI, the
`version` of the data app contract the app was built for (1 when absent; an app below the version
this Metabase serves is outdated — badged for admins, hidden from everyone else, and a 409 to open),
and the origins the sandboxed bundle may `fetch`/XHR. See `config.clj` for the format and its
validation.

**The directory name is the slug.** Nothing in the config declares it. This is what makes slug
collisions structurally impossible — a repo can't hold two `data_apps/sales` directories, and
discovery takes at most one config per directory.

## The sync pipeline

Data apps have no sync of their own; they ride the remote-sync import. Every pull — the manual
"Pull changes" button, the auto-import poll, or startup — ends in
`remote-sync.impl/materialize-data-apps!`, which adapts the imported snapshot to plain reader fns
and calls `data-apps.sync/sync-from-snapshot!`.

```
remote-sync import  →  snapshot  →  sync-from-snapshot!  →  discover configs
                                                         →  materialize each app, loading its resources/
                                                         →  prune apps absent from the repo
```

`data_apps/` is deliberately **not** a serdes path, so apps are invisible to the serialization
layer and are counted separately in the pull summary (see `fold-data-app-changes` in
`remote_sync/impl.clj` — without it, a pull whose only change is a data app reports "no changes").

Apps are synced independently. Each app's row and its resource links commit together, and pruning
runs in its own transaction. A database or resource setup error rolls back that app's update, then
records `sync_error` separately.

## The repository is the source of truth

A sync **upserts every app it finds and deletes every row whose directory is gone.** The
consequences are worth stating explicitly, because they're the questions that come up:

| Event                               | What happens to apps                                                      |
| ----------------------------------- | ------------------------------------------------------------------------- |
| App directory removed from the repo | Row, cached bundle, collection, and its contents deleted on the next sync |
| Repo switched to a different one    | Previous repo's apps are absent from the new snapshot, so they're dropped |
| Repo unlinked                       | Nothing — unlinking runs no sync, so apps survive                         |
| Repo has no `data_apps/` at all     | All apps removed                                                          |
| Failed clone/fetch                  | Nothing — that throws before a snapshot exists, so deletion never fires   |

Pruning is by **directory presence**, not by successful parse. An app whose directory is still
there but whose `data_app.yaml` is momentarily broken keeps its row and its last-good bundle, and is
marked with a `sync_error` — see failure isolation below. Local `enabled` state does not protect a
row: an app disabled in the admin UI and then deleted from the repo is still removed, and so are its
collection, saved questions, and actions.

## Resources

An app's saved questions, the copies of the metrics and models they use, and the copies of the
actions it runs live in its resource collection. `npm run sync-resources` renders them with
`POST /api/apps/resources/render` (`render.clj`) and commits them as serdes YAML under the app's
`resources/`; every import loads them (`resource_load.clj`). So the repository owns them:

- **Every import reloads them.** An edit or trash made in Metabase is put back, and a card or action
  whose file is gone is deleted. A card moved out of the collection is no longer the app's, so the
  import refuses it rather than take it back.
- **The load is confined to the app.** Serdes load updates whatever row carries an entity ID and
  resolves references to any local entity, so before writing anything the import checks that the
  files hold exactly the collection `data_app.yaml` names, cards in it, and actions on its models;
  that every existing entity they name already belongs to the app; and that they reference nothing
  else but databases, tables, fields, snippets, segments, and measures that exist. A missing table
  fails the load instead of being created. Cards and actions are owned by Metabase's internal user.
- **The collection is not remote-synced**, so remote sync never tracks, exports, or merges it, and a
  remote-synced entity that depends on an app card can't be exported.

## Failure isolation

A sync must never take a working app offline, and one bad app must never abort the others. So
failures are recorded per app rather than thrown:

- **Malformed `data_app.yaml`** — collected into `:config-errors`. An app that already has a row is
  marked with `sync_error` (so the UI shows it as failed rather than silently presenting the
  last-good bundle as freshly synced); an app that has no row yet is simply not materialized.
- **Bundle missing or over the size cap, or resource files invalid** — the row's metadata is still
  upserted with `sync_error` set, and the previously cached bundle and loaded resources are kept.
- **Resources that fail to load** — the app's transaction rolls back to its savepoint, leaving its
  previous bundle and resources in place, and `sync_error` records why.
- **Anything thrown at all** — `sync-from-snapshot!` catches and logs it, returning nil, so a
  data-app failure can't break the surrounding remote-sync import.

Because the bundle is cached in the app DB rather than read from the repo per request, a broken sync
degrades to "the app still serves its last good bundle, and the admin sees why it's stale."

## What survives a sync

`enabled` is admin-owned and is never written by a sync — an app disabled in the admin UI stays
disabled across pulls, and new rows get the DB default of true.

`:changed` accounting deliberately ignores `last_synced_sha` / `last_synced_at`: re-syncing
identical content at a new commit is not a change, so the pull summary reports real edits only.

## Serving

Routes are mounted at `/api/apps` (`api.clj`). Not `/app/*` — the server reserves that for static
assets (`metabase.server.routes/static-files-handler`).

- `GET /api/apps` — list; `?available=true` filters to enabled apps with no sync error.
- `GET /api/apps/:slug` — metadata for one enabled app.
- `GET /api/apps/:slug/bundle` — the cached bytes, with a content-hash ETag and `If-None-Match`
  → 304. Carries `X-Metabase-Data-App-Allowed-Hosts`, which the iframe reads to configure its
  sandbox fetch allowlist.
- `GET /api/apps/sandbox-host` — the empty document loaded as the Near-Membrane realm iframe,
  carrying the CSP that confines `'unsafe-eval'` to that realm.
- `PUT /api/apps/:slug` — toggle `enabled` (superuser).
- `DELETE /api/apps/:slug` — drop a row, its bundle, and its owned resources (superuser).
- `POST /api/apps/resources/render` — render an app's resource files from its query and action
  definitions, writing nothing. Allowed to anyone who can run every query and read every action and
  its model.
- `GET /api/apps/repo-status` — whether a repo is connected (superuser).

Responses are field-filtered by role: superusers get full metadata, everyone else gets `name` and
`display_name` only. The bundle blob is never serialized into JSON, and metadata reads go through
`data-apps.db`'s non-blob helpers so listing apps doesn't drag the bundles out of the DB.

`csp.clj` exposes an app's `allowed_hosts` to the core security middleware through a `defenterprise`
hook, which drives the `connect-src` of the iframe document's CSP. It's a separate namespace so the
middleware's lookup doesn't pull in route code.

## Permissions

Each app has two resources (`resources.clj`), reasserted on every sync: a **collection** holding
what the app is served from (saved questions, action models, table-sourced metrics), loaded from its
`resources/`, and a server-managed **permissions group** its users belong to.

The group is set database-level `view-data :blocked` on every database, so it grants **no data
access of its own** (which cascades `create-queries`/`download-results` to `:no`); every group but
admins is revoked from the collection before the app group gets read access. Deleting an app deletes
both resources and everything in the collection.

**Viewing an app** requires read access to its resource collection. You have to be a member in
the app's group or be an admin. An app without a linked resource collection is considered _unpublished_.
The app's metadata and bundle endpoint returns HTTP 409 for all signed-in users. The frontend
shows the error "This data app isn’t published yet".

**A viewer sees an app's data only through access they already hold.** The app group grants no
view-data of its own, so a viewer without access to an app's tables (e.g. a sandboxed user) sees no
data from it — their own groups' permissions and sandboxes apply unchanged. Because the group grants
nothing, it can never lift another group's sandbox, so sandboxing needs no data-app special-casing.

**Managing is superuser-only** — enabling, disabling, deleting, permission warnings, and repo status.

## Namespace map

| Namespace             | Responsibility                                                                                      |
| --------------------- | --------------------------------------------------------------------------------------------------- |
| `sync.clj`            | Discovery, materialization, pruning. The entry point remote-sync calls.                             |
| `config.clj`          | `data_app.yaml` parsing and validation; the `data_apps/` layout constants.                          |
| `api.clj`             | The `/api/apps` endpoints, bundle serving, ETag handling.                                           |
| `resources.clj`       | The app's permission group, and its link to the resource collection: view-data blocking, deletion.  |
| `resource_files.clj`  | The `resources/` layout, parsing, and the entity IDs of copies.                                     |
| `resource_load.clj`   | Validating and loading an app's resource files, pruning, and recording the tables they read.       |
| `render.clj`          | Rendering resource files from query and action definitions.                                         |
| `core.clj`            | What other modules may ask: which cards are an app's resources.                                     |
| `models/data_app.clj` | The `:model/DataApp` Toucan model, permissions, blob coercion.                                      |
| `csp.clj`             | `allowed_hosts` lookup for the core CSP middleware.                                                 |
| `init.clj`            | Loads the above so endpoints, models, and hooks register.                                           |
