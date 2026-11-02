## Why

The Admin console's custom dashboards — widgets on a 12-column grid, each running a query and drawing it as
a table, chart or single value, under a dashboard-wide time range and period comparison — are kept in the
Admin process's memory. They are lost on every restart and differ between replicas, so the feature cannot
be enabled anywhere it matters. ADAS already owns saved queries, the other authored analytics object; it
should own dashboards the same way, so Admin's switch is one HTTP client.

## What Changes

- New `dashboard` entity stored in the PostgreSQL catalog (new Flyway migration), with its widgets held as
  one JSON document on the row.
- New CRUD surface under `/v1/dashboards`: `GET` (list of every dashboard the caller may see, as list
  items), `POST`, `GET /{id}`, `PUT /{id}` (whole-document replace), `DELETE /{id}` (`204`).
- A dashboard carries `name`, `description`, `visibility` (`private` | `public`), a server-assigned
  `owner_id` / `owner_email`, the dashboard's `time` intent (the saved-query `time` model), `compare`
  (`off` | `previous-period` | `previous-month` | `previous-year`), `widgets`, `generation`, timestamps,
  and a per-caller `permissions: { write, publish }` block on both the full read and each list item; the
  list response also carries top-level `permissions: { create_private, create_public }`.
- A widget references a saved query by `saved_query_id` **or** holds an `inline_query` (the saved-query
  body, result-view and chart models) — exactly one. Inline bodies are validated by the same translation a
  saved query body is; a `saved_query_id` is stored without an existence check and the dashboard is never
  touched when that saved query later changes or disappears.
- Authorization reuses the saved-query privileges rather than adding one: `SAVED_QUERY_READ` to see,
  `SAVED_QUERY_WRITE` to write a `private` dashboard, `SAVED_QUERY_PUBLISH` to write a `public` one or to
  change `visibility` in either direction. A publish-only caller curates `public` dashboards as it curates
  `common` saved queries. A dashboard the caller may not see answers `404`; a refused
  write on a visible one answers `403`.
- **Behaviour change:** `SAVED_QUERY_WRITE` now implies `SAVED_QUERY_READ`. That is a new edge in the
  privilege implication DAG, which affects saved queries as well as dashboards. A write is a whole-document
  replace of something the writer has read, and a dashboard replace needs the `generation` that read
  returned. No bundle's authority changes, because every bundle with the write already had the read, but
  the three bundles now declare only the write. A `roles-mapping` naming `SAVED_QUERY_WRITE` without
  `SAVED_QUERY_READ` starts reading what every `SAVED_QUERY_READ` holder reads:
  - `common` saved queries;
  - the full revision history of each currently-`common` saved query, including revisions from before
    it was published;
  - the caller's own `personal` saved queries and their revisions, which it could already create and
    replace but not list or read;
  - its own `private` dashboards;
  - `public` dashboards.

  `SAVED_QUERY_PUBLISH` still implies nothing, so a publish-only caller replaces a `public` dashboard
  only with a `generation` from its own earlier write.
- Optimistic concurrency, new to this kind of object: `PUT` must carry the `generation` it read; a stale
  one answers `409 dashboard_conflict` and changes nothing.
- Dashboards are `@Audited` from the first release: every create, replace and delete is recorded in the
  activity feed under a new `Dashboard` resource type, with the saved-query visibility narrowing applied.
  The point-in-time read endpoint for a dashboard is not part of this change.
- A dashboard stores no query results and the service caches none; every widget is executed by the client
  through `/v1/queries/execute` or `/v1/queries/execute-sql` under the viewer's own credential.

## Capabilities

### New Capabilities

- `dashboards`: the dashboard object, its widgets and layout, validation, visibility and privilege rules,
  per-caller `permissions`, generation-checked replace, and the `/v1/dashboards` API.

### Modified Capabilities

- `audit-trail`: dashboards join the audited resources; `Dashboard` becomes a resource type of the activity
  feed, narrowed for each caller by the same visibility rule the dashboard's own reads apply.
- `authentication`: the `SAVED_QUERY_*` privileges also authorize the dashboards surface, and
  `SAVED_QUERY_WRITE → SAVED_QUERY_READ` joins the implication DAG on a new admission ground. The closed
  privilege set is unchanged, and the bundles confer the same authority from smaller declared sets.

## Impact

- **API**: new `/v1/dashboards` endpoints; the audit activity feed gains the `Dashboard` resource type.
  No existing endpoint changes shape.
- **Catalog schema**: one new PostgreSQL table `dashboard` and its Envers mirror `dashboard_aud`, in one
  new migration under `db/migration/postgres`.
- **Code**: a new `service/dashboard/` package (entity, repository, service, access, validator), a
  controller, DTOs and mapper under `web/`, `DefaultExceptionHandler` cases, and `ActivityResourceType` /
  `AuditResourceMapper` / `AuditActivityService` extended for `Dashboard`. Reuses
  `SavedQueryBodyValidator`, `SavedQueryTime`, `SavedQueryChart` and `SavedQueryResultView`.
- **Config**: none — no new property.
- **Privileges**: none added; `SAVED_QUERY_*` now also govern dashboards, and `SAVED_QUERY_WRITE` implies
  `SAVED_QUERY_READ` (`PrivilegeImplications`, `RoleBundle`).
- **Out of scope**: dashboard revision reads (`/v1/dashboards/{id}/revision/{n}`), catalog export/import
  of dashboards, and a query-result flag reporting that a row policy confined a result.
