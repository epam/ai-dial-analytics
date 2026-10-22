# Audit trail for the table catalog

## Why

ADAS's PostgreSQL catalog is now a governed control plane: a table definition carries the role-based
`access` lists that decide who may write or modify it, and its columns carry the `sensitive` flags
that decide what a caller may read. Today every one of those mutations is unattributable — the
catalog holds only current state, so "who marked this column `sensitive`", "who added a role to this
table's `modify` list", and "what did this table's schema look like before that patch" have no
answer. That is a compliance gap for a component whose entire job is governing access to analytics
data, and it is the one thing an operator asks first when a query starts returning something
unexpected.

The sibling DIAL control planes (`ai-dial-admin-backend`, `ai-dial-admin-mcp-manager-backend`) both
solved this with the same shape, and `ai-dial-admin-frontend` already ships a client for it —
`ActivityAuditApi` and `DeploymentAuditApi` are the same class pointed at two different hosts, and a
`DIAL_ANALYTICS_API_URL` env var already exists. This change therefore adopts that contract rather
than inventing one, so ADAS becomes a third backend the existing feed can render.

## What Changes

- **Change tracking (storage)** — Hibernate Envers audits the two entities behind the table catalog:
  `table_definition` and `column_mapping`. Every insert/update/delete writes a full-state snapshot to
  a `*_aud` table within the caller's own transaction, so a rolled-back mutation leaves no audit
  record. Since `V25__denormalize_table_catalog` folded `enrichment_grain` and `table_ordering_key`
  into `table_definition` as the `grain_key` and `ordering_key` columns, an enrichment's grain and a
  table's MergeTree ordering are audited as part of the definition row rather than as entities of
  their own.
- **Revisions with author identity** — a custom revision entity (`revinfo`, the same table name the
  sibling control planes use) groups all changes made in one transaction under a single revision id
  and timestamp, stamped with the caller's principal and email read from the security context.
  Background work already authenticates as `adas-system` (`SystemIdentity`), so scheduler-driven
  changes attribute to it; an unauthenticated request (always the case in `none` security mode)
  records `unknown`.
- **Activity feed** — a denormalized `audit_activity` table records one entry per changed entity per
  revision, across two resource types: `Table` and `TableColumn`. Nothing is folded away — a schema
  patch touching twelve columns records twelve `TableColumn` activities naming exactly which columns
  changed. Column activities carry a `parentActivityId` pointing at the `Table` activity of the same
  revision, so a client can collapse them under the act that caused them. Within a revision, entries
  collapse per entity with `Create`/`Delete` taking precedence over `Update`.
- **Dependent deletes become visible** — `TableCatalogService.delete()` currently lets PostgreSQL
  remove a table's columns by `ON DELETE CASCADE`, which fires no Hibernate event and would leave
  those removals unrecorded. It now deletes them through their repository first, so the trail (and
  the snapshot surface, which decides membership from delete rows) stays correct.
- **Activity read API, matching the reference contract** — `POST /v1/activities` takes
  `{pageNumber, pageSize, sorts: [{column, direction}], filters: [{column, operator, value}]}` and
  returns `{total, totalPages, data}`; `GET /v1/activities/{activityId}` returns one entry. Filters
  are a flat AND-list over an allow-listed column set with the operators
  `eq ne co nc lt gt le ge in`. Both endpoints carry no gate of their own, inheriting the `/v1/**`
  application-role rule — the same bar as reading a table, and deliberately not `FULL_ADMIN`.
- **Historical snapshot on the table controller** — `GET /v1/tables/{name}/revision/{revision}`
  returns the table as it stood at that revision, in the **same** `TableDto` the live read returns,
  at the same authorization. A revision at which the table did not exist answers 404.
- **New migration** `V27__audit_trail.sql` creates `revinfo`, two `*_aud` tables
  (`table_definition_aud`, `column_mapping_aud`), and `audit_activity`.
- **New dependencies** `org.hibernate.orm:hibernate-envers` (Boot BOM-managed) and
  `com.github.f4b6a3:uuid-creator:6.0.0` (the UUIDv7 generator the reference services use).

Explicitly **not** in this change:

- **The enrichment control plane — rules and evaluators — is not audited.** That surface is still
  unstable; auditing it now would freeze an audit contract (resource types, resource-id formats,
  feed semantics) against entities whose shape is still moving, and every change to them would then
  drag a matching `*_aud` migration behind it. It is the natural second increment once the surface
  settles, and this change lands all the machinery it will need.
- **Aggregate pipelines are not audited**, for the same instability reason as rules — and, unlike
  rules, only for that reason. `PipelineStateWriter` rewrites the pipeline row on every scheduler run
  (`lastRunAt` and `updatedAt` always, plus the cursor, `hasMore`, `lastError`, `nextRunAt`), which
  at the default one-minute tick would be roughly 8,600 rows per pipeline per replica per day. That
  volume is **not** an obstacle, contrary to what an earlier draft of this proposal claimed:
  `@NotAudited` on those runtime fields suppresses the write entirely rather than merely omitting the
  columns, so a tick that changes no configuration produces no audit row and no revision (see
  `design.md` — "Ignoring a change"). Auditing pipelines therefore costs one annotation per runtime
  field, not a table split; what it still costs is deciding, per field, which side of the
  runtime/configuration line it falls on, against an entity whose shape is still moving.
- **No rollback.** Restoring a resource to a past revision (the deployment manager's
  `020-revision-rollback`, and the frontend's `POST /history/rollback`) is a separate, much larger
  change — every restored state would have to re-pass the table validation gates and reconcile
  against ClickHouse DDL that cannot be un-issued.
- **No revision-list endpoint.** The reference's `POST /history/revisions` exists mainly to drive the
  rollback UI; with rollback out of scope its standalone value is browsing, which the activity feed's
  `revision` filter already covers.
- **Row writes are not audited.** `POST /v1/tables/{name}/rows` writes analytics data to ClickHouse,
  not catalog state; auditing it would put a data-plane volume of rows into a control-plane table.
- **Saved queries are not audited.** `@Audited` is per-entity, not per-row, so it would retain every
  user's `personal` query bodies, names, and `owner_email` indefinitely in a table nothing prunes —
  a delete would stop meaning delete. Scoping the trail to `common` rows would require splitting the
  table, which is its own change.
- **No retention/pruning policy.** Audit rows accumulate; pruning is deferred until there is
  operational evidence of the growth rate.

## Capabilities

### New Capabilities

- `audit-trail`: change tracking for the table catalog — what is audited, how authorship and
  revisions are captured, how activities are derived, deduplicated, and linked to a parent, and the
  read surface for the activity feed and historical snapshots.

<!-- The capability is named for the table catalog it covers, deliberately narrower than this
     change's branch name (`audit-tables-and-rules`): rules are excluded for the reasons above and
     are the natural second increment. -->

### Modified Capabilities

<!-- None. Table behavior is unchanged: no request or response of an existing endpoint changes shape,
     and no existing validation or authorization rule is altered. The snapshot endpoint is an
     additive route on an existing controller; the delete-path change alters how rows are removed
     (through the ORM rather than by database cascade) without changing what is removed or what the
     endpoint answers. The audit trail is a new observing capability layered underneath; `audit-trail`
     names the entities it covers so the coverage contract lives in one spec. -->

## Impact

- **New code** (`service/audit/`, `web/controller/audit/`, `web/dto/audit/`, `web/dto/page/`):
  `AuditRevisionEntity` + `AuditRevisionListener` (author capture, activity derivation, dedup, parent
  linking), `AuditActivityEntity` + `ActivityType` / `ActivityResourceType`, `AuditResourceMapper`,
  `AuditActivityService` + repository, `AuditSnapshotService` (Envers `AuditReader` queries), the
  generic paging/filtering layer (`PageRequestDto`, `FilterDto`, `SortDto`, `PageDto`,
  `PageEntityMapper`) transplanted from the reference services, `ActivityController`, and the
  activity DTO + MapStruct mapper.
- **Touched existing code**: `@Audited` on `TableDefinitionEntity` and `ColumnMappingEntity`;
  `TableCatalogService.delete()` removes a table's columns through their repository instead of
  leaving them to `ON DELETE CASCADE`; `TableController` gains the snapshot route;
  `AuditActivityRepository` is ADAS's first `JpaSpecificationExecutor`; `DefaultExceptionHandler`
  gains handlers for `activity_not_found`, `revision_not_found`, and the filter-validation failures.
  `CatalogSchemaWriter` is deliberately **not** touched: its delete-all-then-insert-all re-define is
  resolved correctly by Envers' own work-unit merge, which a test pins rather than a code change.
  `TableMapper.toDto` already takes a `TableView` aggregate (definition + columns) rather than
  reaching into repositories, so the snapshot path reuses it as it stands — the denormalization
  removed the factoring this change previously had to do.
- **Database**: PostgreSQL only. Four new tables in `V27`. No change to any existing table's shape,
  so no data migration and no backfill — the trail starts at deployment.
- **Runtime cost**: one extra insert per audited transaction plus one per changed entity, and one
  `COUNT(*)` per feed request (inherent to the reference's `{total, totalPages}` envelope). The
  catalog mutation path is administrative and low-volume; the query path is untouched.
- **Frontend**: `ai-dial-admin-frontend` can render ADAS activities, but not for free — it hardcodes
  `api/v1` as its base path where ADAS serves `/v1`, and its view switch, server actions, and
  detail-route resolver are a hardcoded two-way branch. Those are additive FE changes, out of scope
  here.
- **Docs**: `docs/configuration.md` unchanged (no new env vars); `README.md` gains the activity
  surface in its endpoint overview.
