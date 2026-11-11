## Context

A sessions view needs one instance-wide answer: which catalog entity holds session rows and
which of its flat fields play the session roles. ADAS already owns the vocabulary the answer is written in:
the entity catalog and the flat schema under `/v1/queries/entities` (see `query-schema-discovery`), built at
the single read chokepoint `CatalogQueryService.buildModel`/`findModel(entity, bar)`. What is missing is a
place to store an administrator-authored value and a door that lets every analyst read it.

The catalog's other authored documents (saved queries, dashboards) are per-owner, carry visibility, and are
authorized through the `SAVED_QUERY_*` privileges. This setting has no owner and no visibility: there is one
value, an administrator writes it, everyone with analytics access reads it.

## Goals / Non-Goals

**Goals:**

- `GET`/`PUT /v1/settings/sessions-source` with a fixed, documented body.
- Validation at write time against the same flat schema a client would query, so a stored value is one the
  writer could have queried when it was written.
- Read open to every analytics bundle, write to administrators only, expressed in the privilege model
  rather than by branching on a bundle.
- A trail of who changed the setting, through the existing audit feed.

**Non-Goals:**

- Revalidating the stored value when the catalog changes, or blocking catalog changes that break it.
- Checking that the base table shares a session id with `dial_usage_log`.
- Carrying the setting in catalog export/import.
- Running any query: the client composes and runs the sessions queries through the existing query endpoints.
- A generic settings registry API (`GET /v1/settings`, arbitrary keys). Storage is keyed so more settings can
  join later, but each one gets its own typed endpoint and validation.

## Decisions

### Storage: a keyed JSON document table, audited

A new Flyway migration creates `instance_setting (name TEXT PRIMARY KEY, value JSONB NOT NULL, updated_at
TIMESTAMPTZ NOT NULL, updated_by TEXT)` and its Envers mirror `instance_setting_aud`. The JPA entity
`InstanceSettingEntity` sits in a new `service/setting/` package with its repository and
`SessionsSourceSettingService`; the controller is `web/controller/setting/SettingsController`, DTOs under
`web/dto/setting/`. The `value` is held as text and converted with the application `JsonMapper`, the way
`DashboardJson` does, because Hibernate's JSON mapper is Jackson 2. A value class mapped onto the audited
entity must carry `@EqualsAndHashCode` so dirty-checking does not record phantom `Update`s.

The `name` column holds the closed set of setting names (`sessions-source` only), checked by a `CHECK`
constraint so a stray row cannot appear outside the service.

- *Alternative: a dedicated `sessions_source_setting` table with typed columns.* Rejected: it buys column
  types the service does not need (the body is validated against the catalog, not the database) and every
  future setting would need its own table and mirror.
- *Alternative: configuration property (`application.yml`).* Rejected: it must be writable at
  runtime by an administrator, and the value refers to catalog entities that are themselves runtime state.
- *Alternative: not auditing.* Rejected: the setting changes what every analyst sees, and the catalog's
  convention is that every administered document is attributable. Auditing costs one mirror table and one
  `AuditResourceMapper` case.

### Concurrency: last writer wins, single-row upsert

`PUT` is whole-document create-or-replace. There is no `generation` token: the body has none, and
the only writers are administrators editing a single small form. The write runs in one transaction that
loads-or-creates the row by primary key and saves it; a concurrent first write that loses the insert race
surfaces as a primary-key violation, which the service retries once as an update rather than mapping to a
`5xx`. Both outcomes leave one complete value.

- *Alternative: optimistic locking with `If-Match`.* Deferred: it would change the client contract. The
  entity can gain a version column later without a body change.

### Validation against the writer's view of the flat schema

The validator resolves `base_table` through `CatalogQueryService.findModel(base_table, Privilege.TABLE_GET)`
under the writing caller's authorities — the same chokepoint and bar as `GET /v1/queries/entities/schema/{name}`.
`findModel` already returns only `ACTIVE` `source` entities and applies both the per-owning-table read bar and
the `sensitive` filter, so "not a source", "not visible" and "does not exist" collapse to one `422` naming
`base_table`, and a hidden field is indistinguishable from an absent one. Each bound role is then looked up by
flat name in the model and checked against the role's accepted `QueryFieldType` set. `ARRAY` is excluded by
the type table already; the explicit array rule in the spec is kept as its own message because "array"
explains the refusal better than a type list does. Projection-only (computed) fields are refused because the
sessions view filters, sorts or groups on every role, and a projection-only field supports none of those.
Sensitive fields are refused even for a writer cleared to see them: the setting is read by every analyst, and
a reader without `TABLE_SENSITIVE` has no such field in its model, so the query built from the setting would
fail as an unknown field.

Strict type sets are deliberate: `enum` and `uuid` fields are refused for the `string` roles even though both
read as strings on every query surface. Widening the sets later is additive.

- *Alternative: validate against the unfiltered catalog.* Rejected: the writer could then probe for sensitive
  columns or unreadable enrichments by watching which binding validates. Under `FULL_ADMIN` the two views are
  identical, so the stricter rule costs an administrator nothing.
- *Alternative: answer an invisible `base_table` with `404`.* Rejected: the resource addressed is the setting,
  which exists; the table is a body member, and the convention for an unknown or hidden body reference
  (schema patches, dashboard widgets) is a `422` that does not distinguish the two.

### Privileges: a new `SETTINGS` object class

Two global-only privileges, `SETTINGS_READ` and `SETTINGS_WRITE`, with the implication
`SETTINGS_WRITE → SETTINGS_READ` admitted on the whole-document-replace ground the saved-query edges use.
`SETTINGS_READ` joins `READ_ONLY_ADMIN`, `READ_ONLY_ANALYTIC` and `READ_ONLY_SENSITIVE_ANALYTIC`; `FULL_ADMIN`
gets both by construction. The endpoints carry `@RequiresPrivilege(SETTINGS_READ)` / `(SETTINGS_WRITE)`,
non-`deferred`, so `EndpointAuthorizationCoverageTest` covers them unchanged. Refusal is `403 forbidden` on
both: neither is a read of a table the caller named.

- *Alternative: read at `@AuthenticatedOnly`.* Rejected: the `authentication` spec reserves that for endpoints
  carrying no tenant data, and table and field names are tenant data.
- *Alternative: reuse `SAVED_QUERY_READ` for read and `REGISTRY_ADMIN` (or `TABLE_GRANT`) for write.* Rejected:
  it couples an unrelated surface to an object class whose meaning the specs pin down, and an operator who
  narrows saved-query or registry access would silently change who can see or edit the setting.
- *Alternative: gate read on `TABLE_LIST` of the stored `base_table`.* Rejected: `TABLE_LIST` is not
  per-table grantable, and the answer to "may I read the setting" would flip when an administrator changes it.

### No read-time filtering of the stored value

`GET` returns the stored body as written, to every `SETTINGS_READ` holder, regardless of table grants. The
disclosure is a table name and up to six field names that an administrator chose to publish to analysts; this
is the same kind of trade-off the audit feed states for table names. Filtering per caller would make the
client unable to tell "not configured" from "configured, but you cannot see it", and the data behind the
names stays behind `TABLE_READ`.

### Error codes

`404 setting_not_found` is new (a `SettingNotFoundException` mapped in `DefaultExceptionHandler`). Validation
reuses `422 validation_error` through a `SettingValidationException`, matching dashboards. Unknown and
response-only members are refused `400` by the strict request mapper already in place; the request record
deliberately omits `updated_at`/`updated_by`, so they are unknown members on input.

## Risks / Trade-offs

- [The stored value goes stale after a column drop or table deletion] → A client surfaces the query error
  and an administrator re-saves; the spec states the non-revalidation so it is not discovered as a bug. A
  later change may add a computed validity flag to `GET`.
- [Raw-privilege `roles-mapping`s lose nothing but gain nothing, so analysts on them get `403` on read] →
  Called out in the proposal's impact and the `authentication` delta; operators add `SETTINGS_READ`.
- [The setting discloses a table name to a reader who may not list that table] → Accepted and stated in the
  spec; only an administrator chooses what is published.
- [Last-writer-wins can drop one of two simultaneous administrator edits] → Acceptable for a single-value form;
  the audit trail records both writes, so the lost one is recoverable from its activity.

## Migration Plan

- One new PostgreSQL Flyway migration (`V53__instance_setting.sql`), with a one-line header linking
  `openspec/specs/instance-settings/spec.md`. It creates the table and its `_aud` mirror; it touches no existing row and needs no backfill.
- No ClickHouse migration, no new credential, no new configuration property.
- Rollback: the previous build ignores the new table; dropping it is optional. A rolled-back build rejects the
  new privilege names in a `roles-mapping` at startup, so an operator who named `SETTINGS_READ` explicitly must
  remove it before rolling back.

## Open Questions

- Should `string` roles also accept `enum` and `uuid` fields? The design keeps them
  `string` only; widening is additive if a client needs it.
- Should `GET` later report whether the stored value still validates against the current catalog?
