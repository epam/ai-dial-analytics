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
- **Behaviour change:** `SAVED_QUERY_WRITE` and `SAVED_QUERY_PUBLISH` now each imply `SAVED_QUERY_READ`.
  These are new edges in the privilege implication DAG, which affect saved queries as well as dashboards.
  A write is a whole-document replace of something the writer has read, and a dashboard replace needs the
  `generation` that read returned. No bundle's authority widens, because every bundle with a write already
  had the read, but the three bundles that keep personal saved queries now declare only the write. A
  `roles-mapping` naming either write without `SAVED_QUERY_READ` starts reading what every
  `SAVED_QUERY_READ` holder reads:
  - `common` saved queries;
  - the full revision history of each currently-`common` saved query, including revisions from before
    it was published;
  - the caller's own `personal` saved queries and their revisions;
  - its own `private` dashboards;
  - `public` dashboards.
- **Behaviour change:** a `personal` saved query and a `private` dashboard are visible to their owner
  alone, whatever privileges another caller holds. `SAVED_QUERY_PUBLISH`, and so `FULL_ADMIN`, no longer
  passes the owner rule on the saved-query revision endpoint or in the activity feed. A deleted saved
  query's or dashboard's activities and snapshots, which only publishers could see, are now visible to no
  one in `oidc` mode. That removal is what lets the write privileges imply the read safely: no read check
  consults a write privilege any more.
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
  feed, narrowed for each caller by the same visibility rule the dashboard's own reads apply. Neither
  narrowing has a publisher bypass any more, and a deleted resource's activities are visible to no one.
- `authentication`: the `SAVED_QUERY_*` privileges also authorize the dashboards surface, and
  `SAVED_QUERY_WRITE → SAVED_QUERY_READ` and `SAVED_QUERY_PUBLISH → SAVED_QUERY_READ` join the implication
  DAG on a new admission ground. The closed privilege set is unchanged, and the bundles confer the same
  authority from smaller declared sets, except that `FULL_ADMIN` no longer sees other users' `personal`
  saved queries.
- `saved-queries`: a `personal` saved query is its owner's alone on every surface, the revision endpoint
  included; a publish-only caller reads `common` saved queries.

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
- **Privileges**: none added; `SAVED_QUERY_*` now also govern dashboards, and `SAVED_QUERY_WRITE` and
  `SAVED_QUERY_PUBLISH` imply `SAVED_QUERY_READ` (`PrivilegeImplications`, `RoleBundle`); the publisher
  bypass of the owner rule is removed (`SavedQueryAccess`, `SavedQueryController`, `AuditActivityService`).
- **Out of scope**: dashboard revision reads (`/v1/dashboards/{id}/revision/{n}`), catalog export/import
  of dashboards, and a query-result flag reporting that a row policy confined a result.
