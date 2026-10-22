## Context

See proposal.md (Why). Today:

- `Privilege.TABLE_READ_SENSITIVE` is the top of the read ladder (`→ TABLE_READ → TABLE_GET → TABLE_LIST`).
  `SensitiveColumnAccess.maySeeSensitive(table)` asks for it, and `CatalogQueryService.buildModel` uses that
  check to filter sensitive columns out of every query surface.
- `TableSchemaService.requireGrantAuthority` makes any `sensitive` flag change on `update` require
  global `TABLE_GRANT`. `add` and `POST .../schema` may declare a column sensitive under `TABLE_MODIFY` alone.
- `GET /v1/tables[/{name}]` returns every column with its flag (`TableMapper`), regardless of the caller.
- `table_definition.access` is a JSONB document `{get, read, read_sensitive, write, modify}`, and
  `table_definition` is `@Audited`, so `table_definition_aud.access` holds historical copies of the
  same document.
- `SensitiveGrantInvariant` requires `derived.read_sensitive ⊆ source.read_sensitive`.

## Goals / Non-Goals

**Goals:**
- One clearance privilege whose meaning is the same for every verb it modifies.
- Full delegation of a table, flag authority included, through per-table grants alone.
- The same column visibility on every surface: catalog read, schema mutation, discovery, query,
  translation and Flight.

**Non-Goals:**
- Per-column classification levels, or more than one clearance.
- Making column names secret. The audit trail, the pipeline registry and a table's structural
  fields (`ordering_key`, `partition_by`, `grain_key`) still name columns (see D4).
- Changing `TABLE_WRITE`, `TABLE_CREATE`, `TABLE_DELETE`, or the global-only status of `TABLE_GRANT`.
- Filtering the audit feed by column visibility. It is already unfiltered by table visibility, a
  stated trade-off.

## Decisions

### D1. Sensitivity is an orthogonal clearance, not a rung

`TABLE_SENSITIVE` is table-scoped and grantable (key `sensitive`). It implies nothing and nothing
implies it. `FULL_ADMIN` has it because the bundle is `allOf`.

*Alternatives rejected:*
- `TABLE_MODIFY → TABLE_READ_SENSITIVE`. It has one small edge, but it breaks disclosure
  monotonicity by design. It also forces the laundering rule to treat `modify` as read-conferring,
  and it cements the modelling error.
- Per-verb duplicates (`GET_SENSITIVE`, `MODIFY_SENSITIVE`). That is three privileges and three grant
  keys for one concept, and every new verb would need its own twin.
- A classification ladder (public/internal/restricted). Nothing requires more than two levels (YAGNI).

### D2. Evaluation is `holds(verb, T) ∧ holds(SENSITIVE, T)`, each resolved independently

Each side is the union of global and per-table, as for every table-scoped privilege. A role with
global `TABLE_READ` and a per-table `sensitive` grant therefore reads that table's sensitive values.
Requiring both to come from the same scope was rejected, because it would be the only place in the
model where union does not hold.

On the query, discovery, translation, Flight and catalog surfaces the verb side is already enforced
where the check runs, by the endpoint gate or the `findModel(entity, bar)` bar. There,
`SensitiveColumnAccess.maySeeSensitive(T) = isAllowed(T, TABLE_SENSITIVE)` is enough, and behaviour is unchanged
apart from the name.

Other paths apply **no table verb bar of their own**: pipeline authoring (`REGISTRY_ADMIN`), the
runner data-plane guards (`SensitiveProjectionGuard`, the scan and group-assembly re-checks, the
materialize check on columns owned by an enrichment reached through the grain join). Saved-query
publishing needs no check of its own: the bound body resolves at the `TABLE_READ` bar. There the old `TABLE_READ_SENSITIVE` check carried the read implicitly. A clearance-only
check would let an author holding the clearance but no read reference sensitive values. Those paths use
a second predicate,
`SensitiveColumnAccess.mayReadSensitive(T) = isAllowed(T, TABLE_READ) ∧ isAllowed(T, TABLE_SENSITIVE)`,
evaluated per owning table. The two predicates sit side by side so that the choice is made
deliberately at each call site.

### D3. Three chokepoints, one predicate

1. **Query model**: `CatalogQueryService.buildModel`, unchanged apart from the predicate.
2. **Catalog read**: the `TableMapper` column inventory and `column_count` filter by `maySeeSensitive`. The
   list and single-get responses stay consistent (`column_count == columns.length` per caller). The
   revision read goes through the same mapper, filtering on the snapshot's flags with the caller's
   current clearance.
3. **Schema mutation**: `TableSchemaService` / `TableSchemaCreationService` resolve the patch against
   the **visible** column set. A hidden column named by `drop`, `rename.from` or `update` is
   `422 unknown column`, the same message as for a column that does not exist. Declaring or changing a
   `sensitive` flag without the clearance is `403 forbidden`. The column involved is visible, so the
   denial reveals nothing.

`requireGrantAuthority` is deleted. The cursor-column rule (`ScanMetadataRules.requireNotSensitive`)
and the laundering check stay where they are.

### D4. Hidden, not secret; the collisions are accepted

A caller without the clearance can still learn that a hidden name exists. It can do so from an `add`
or `rename.to` that collides with it (`422 ... already exists`), from structural fields, from the
audit feed, and from registry declarations. We state this in the spec rather than engineer around it.
Hiding the collision would mean letting the DDL fail opaquely. That is a worse error for a real
author, and it would still leak through the other channels. The goal of D3 is a consistent working
view for a caller without clearance, not confidentiality of names.

### D5. Declaring a column sensitive also needs the clearance

This was decided deliberately. The alternative was to allow it because a new column hides nothing yet.
The rule is simpler to state ("anything touching sensitivity needs the clearance"), and it avoids a
caller creating a column it immediately cannot see. `POST /v1/tables` accepts no columns, so the only
definition-time path is `POST .../schema` on a `PENDING` table, where a per-table grant qualifies.

### D6. Laundering rule: `derived.sensitive ⊆ source.read ∩ source.sensitive`

Under the ladder model a role on the source's `read_sensitive` list could read the source's sensitive
values. The `sensitive` list alone no longer means that, because clearance without `read` reads
nothing. So "the source lists it" becomes "the source lists it under both `read` and `sensitive`".
The derived side stays conservative. Every role on the derived `sensitive` list counts, whatever its
`read` grant, because that role might hold `TABLE_READ` globally. The check is list-based, as today,
and `roles-mapping` is still not consulted.

The derived table's `modify` list is **not** checked. `modify` alone reaches no sensitive column
there, and `modify` + `sensitive` is covered because `sensitive` is checked.

*Accepted consequence:* a holder of `modify` + `sensitive` on a derived table may now declassify a
derived column, which previously needed `TABLE_GRANT`. Through per-table grants that holder is, by
this rule, a sensitive reader of every source.

*Globally cleared roles:* the clearance can be held globally without a read, a state the old rung
could not express. Such a role reads a derived table's sensitive values with nothing but a `read` grant
there, and is on no `sensitive` list. So a derived table carrying a sensitive column may list a role
under `read` that `roles-mapping` maps to global `TABLE_SENSITIVE` only if every table it reads lists
that role under `read`. The mapping is consulted only to refuse, never to exempt. A later remap of a
role to global clearance is not re-examined, which is accepted and documented.

### D7. `permissions.sensitive` reports the clearance, not "can read sensitive values"

The `permissions` flags mirror privileges and grant keys one-to-one. A consumer derives "sees
sensitive values" as `read && sensitive`. The alternative, a computed flag, would be the only flag
that does not correspond to a key.

### D8. `sensitive_without_verb` ineffective grant

`PUT .../access` reports a role on the `sensitive` list that holds none of `get`, `read` or `modify`
on the table, by any list or globally, because such a grant changes nothing. This matches the
existing `already_global` and `enrichment_read_inert` reporting and does not fail the request.

### D9. No data migration, no alias

Per-table sensitive grants have not been used yet, so no stored `access` document carries
`read_sensitive` and there is nothing to migrate. No compatibility alias for `read_sensitive` or
`TABLE_READ_SENSITIVE` either: a request body that still sends `read_sensitive` gets `400` naming the
field, and a `roles-mapping` naming the old privilege fails startup naming it.

### D10. Non-disclosure rule

`TABLE_SENSITIVE` is never an endpoint gate. The read-side set that answers `404` becomes
`TABLE_LIST`/`GET`/`READ`. A missing clearance never hides a table, only columns.

## Risks / Trade-offs

- [A table owner without clearance sees a narrower `GET` than before, and clients that audit flags
  through `GET` stop seeing sensitive columns] → State it in the spec. `READ_ONLY_SENSITIVE_ANALYTIC`
  and `FULL_ADMIN` are unaffected, and an operator auditing sensitivity needs `get` + `sensitive`.
- [The laundering rule is stricter on the source side for a role that has `sensitive` but not `read`
  there] → That role could not read source values anyway. Refusing is correct, and the
  `sensitive_grant_widens` message names both required keys.
- [A global `TABLE_SENSITIVE` + per-table `modify` holder can declassify a derived column] →
  Accepted (D6), and documented in `enrichment-runtime`.
- [A hidden-column name leaks through collisions] → Accepted and specified (D4).

## Migration Plan

1. Operators edit `roles-mapping` values from `TABLE_READ_SENSITIVE` to `TABLE_READ` +
   `TABLE_SENSITIVE`. Bundle users need no change, and startup names any leftover value.
2. MCP `adas_*` table tools switch to the `sensitive` key in the same release.

Rollback: redeploy the previous build.
