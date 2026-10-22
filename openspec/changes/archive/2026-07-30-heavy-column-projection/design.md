## Context

Four query surfaces (`POST /v1/queries/execute`, `POST /v1/queries/execute-sql`, Flight SQL
`getFlightInfo`/`doGet`, and the two `translate*` endpoints) all resolve fields through one
`QueryEntityModel` produced by `CatalogQueryService#buildModel`. That model is already the
chokepoint for column-level access control (`sensitive`), so it is also the natural place to
publish a *cost* attribute.

Two places expand a "give me everything" projection today:

- `StructuredQueryBuilder#buildRow` — an omitted/empty row-mode `select` projects every non-enrichment
  binding (`src/main/java/.../service/query/translate/StructuredQueryBuilder.java:146`);
- Calcite's validator — `SELECT *` is expanded from `EntityTable#getRowType`, i.e. every flat field,
  enrichment columns included (`service/query/sql/CalciteQueryCatalog.java:100`).

A third place mirrors the first for round-tripping: `StructuredQueryToSqlRenderer#bareSelectList` /
`#bareProjectionNames` render an empty DSL `select` back to SQL.

External clients cannot express "everything except the payloads": the ADBC/pandas driver sends only
SQL text, and `SELECT * EXCEPT (...)` is outside the accepted subset (and outside the DSL). So the
opt-out has to live in the server's catalog, not in the request.

## Goals / Non-Goals

**Goals:**

- A wildcard projection returns only columns that are cheap to move, on every surface, from one
  catalog attribute.
- An explicit reference to a heavy column keeps working everywhere, in every clause.
- A client can discover which columns a wildcard omits, without trying it.
- Zero behavior change for a catalog that marks nothing heavy.

**Non-Goals:**

- Automatic heaviness detection (e.g. from `system.columns` compressed sizes). Explicit, admin-owned
  metadata only.
- Truncating or summarizing heavy values (returning the first N bytes, or a length instead). Values
  are returned verbatim or not at all.
- A per-request override (`include_heavy: true`, a `SET` variable, a header). The Flight/pandas client
  has no channel for one, so it would be a REST-only half-measure; naming the column is the override.
- Row-count/byte-budget governance. Limit governance (default 100 / max 1000) is unchanged and
  orthogonal.

## Decisions

### D1 — A per-column boolean `heavy`, mirroring `sensitive`

`column_mapping.heavy BOOLEAN NOT NULL DEFAULT FALSE`, catalog-only (never in the ClickHouse DDL),
declarable in `POST /v1/tables/{name}/schema` columns and in `PATCH /v1/tables/{name}/schema`
`add`/`update`, preserved across a `rename`, returned unfiltered by `GET /v1/tables/{name}`.

Rationale: the `sensitive` flag already established this exact plumbing path
(`ColumnInput` → `CatalogSchemaWriter` → `ColumnMappingEntity` → `QueryFieldBinding` /
`QuerySchemaFieldDto`), including the merge-patch semantics and rename preservation an operator
already knows. A second flag on the same rails costs no new concepts.

*Alternatives considered:* (a) infer heaviness from the declared type (`object`, or `string` above a
size) — wrong for a small JSON column and for a 30-byte `String`, and unfixable by an operator;
(b) an enum `size_class` (small/large/huge) — no second behavior to attach to a third value;
(c) reuse `tag` (a `payload` tag already groups these columns) — tags are explicitly documented as
descriptive-only display metadata, and one table's `payload` is another's core data.

### D2 — `heavy` is a cost hint, never access control, so it is not role-filtered

Unlike `sensitive`, a heavy column stays *present* in the model for every caller, with the flag
published in discovery. Only the wildcard expansion consults it. A caller who names the column gets
it (subject to the unchanged `sensitive` rules).

Consequence, stated deliberately: `heavy` grants no protection. `dial_usage_log.request_body` stays
restricted because it is `sensitive`, not because it is heavy.

### D3 — SQL `*` is expanded before validation, in the AST

A new `SqlStarExpander` runs in `SqlQueryFrontEnd#translate` between parse and
`SqlSelectAliasInjector`, replacing a top-level `*` (or `<alias>.*`) select item with the entity's
non-heavy source columns (D4) as bare `SqlIdentifier`s.

Rationale: expanding into caller-shaped identifiers means everything downstream — validation, the
`RelNode` plan, `ResultColumn` naming, the produced DSL document — behaves exactly as if the client
had typed the list, so the `sql-query-translation` naming contract ("star-expanded columns keep their
own names") holds with no special cases. It also keeps the expansion visible through
`POST /v1/queries/translate-sql`, which becomes the answer to "what will `SELECT *` actually give
me?".

*Alternatives considered:* (a) omit heavy fields from `EntityTable#getRowType` — they would then be
unresolvable when named explicitly, defeating the point; (b) filter the `Project` after planning —
by then a star is indistinguishable from an explicit list, so an explicit
`SELECT request_body, …` would be silently pruned too; (c) Calcite's system-column mechanism
(`getRowTypeSansSystemColumns`) — undocumented for `AbstractTable` under a `Frameworks` root schema,
and system columns bring unrelated semantics.

Ordering matters: star expansion runs **before** alias injection, so the injected names are computed
over the already-expanded list (a bare identifier is "already named", so nothing changes for it, but
the derived-name uniqueness pass must see the real list).

`FROM` shapes the expander does not recognize (a join, a derived table, a subquery) are left alone —
those are rejected downstream as unsupported constructs, except the one legal shape
(`SELECT * FROM (SELECT …)`), where the expander recurses into the inner select so the exclusion is
applied where the base table is referenced. `COUNT(*)` is untouched: it is a function operand, not a
top-level select item.

### D4 — One wildcard rule, one helper: non-heavy **source** columns

`service.query.WildcardProjection#sourceFields(model)` returns the entity's non-heavy,
non-enrichment fields in catalog order, and is the single derivation behind all three expansion
sites: the DSL empty `select` (`StructuredQueryBuilder`), rendering that empty `select` back to SQL
(`StructuredQueryToSqlRenderer`), and the SQL star (`SqlStarExpander`).

This makes SQL `*` **stop expanding to enrichment columns**, which it does today because the Calcite
row type is the whole flat schema. Rationale:

- the DSL's bare projection already excludes them, and one rule for "give me everything" beats two;
- an enrichment column in a star expansion silently drags a `LEFT JOIN` per attached enrichment into
  the cheapest-looking query in the surface — the opposite of this change's goal — and the DSL spec
  already states the join is opt-in ("a query must name one to pull its enrichment in");
- nothing becomes unreachable: an enrichment column is addressable in SQL only as a quoted dotted
  identifier (`"deployment_ref.display_name"`), so a client that wants one already had to name it;
- it is nonetheless **breaking** for a SQL client relying on `SELECT *` to return enrichment columns —
  called out in the proposal and in the delta specs.

`StructuredQueryToSqlRenderer#bareProjectionNames` (the array/object projection-only guard) uses the
same helper: a heavy `object` column that the wildcard no longer projects must not trigger a
projection-only rejection. Its `bareSelectList` keeps its existing shape — a literal `*` for an
enrichment-less entity, the explicit column list when enrichments exist — which after this change
denote the same set either way; keeping the conditional keeps the rendered output unchanged for
today's queries.

### D5 — An empty wildcard expansion is a 400, not an empty projection

If every field a wildcard would expand to is heavy, the request is rejected with a message naming the
entity and telling the caller to list columns explicitly (`QueryValidationException` on the DSL path,
`SqlTranslationException` on the SQL paths → `400 bad_request` / gRPC `INVALID_ARGUMENT` through the
existing handlers; no new error codes). Emitting `SELECT FROM` or a zero-column result would surface
as a raw parse/ClickHouse failure instead.

### D6 — Seed only the two payload columns

`dial_usage_log.request_body` and `dial_usage_log.response_body` are seeded heavy. Both are already
`sensitive`, so the visible change is limited to `FULL_ADMIN` callers and `security.mode=none`
deployments. `jwt_claims` / `request_tags` stay non-heavy: they are small header/claim objects, and
`rate_analytics.comment` is a short free-text field.

Migration slot: `V18` — the next number after merged history (the default branch tops out at
`V17__add_table_ttl.sql`); in-flight branches numbering higher renumber on rebase, per the project's
numbering rule.

## Risks / Trade-offs

- **`SELECT *` silently returning fewer columns than the table has contradicts SQL semantics** → The
  default is `heavy = false` for every column, so nothing changes until an operator opts a column in;
  the flag is published in schema discovery and in `GET /v1/tables/{name}`;
  `POST /v1/queries/translate-sql` shows the expanded list; the behavior is documented in
  `docs/heavy-columns.md` with the "name the column to get it" answer. This is a governed analytics
  gateway that already deviates from plain SQL (mandatory limits, no joins, `sensitive` columns hidden
  from `*`), so the deviation is in keeping with the surface.
- **A notebook that relied on `SELECT *` including payloads breaks** → Only for entitled callers on
  `dial_usage_log`; the fix is naming the column, and the column is still discoverable with
  `heavy: true` telling the analyst why it vanished.
- **An operator marks every column heavy and every wildcard 400s** → D5's message names the entity and
  the remedy; the flag is reversible with one `PATCH` and no data movement.
- **A dashboard or notebook relying on `SELECT *` to return enrichment columns loses them** → the
  columns stay queryable by their quoted qualified name and stay listed in schema discovery; the change
  is called out as BREAKING in the proposal and the `sql-query-translation` delta. It also removes a
  hidden per-enrichment `LEFT JOIN` from every star query, which is the same problem this change is
  about.
- **The DSL and SQL wildcards could drift apart again** → both derive from the one
  `WildcardProjection#sourceFields`, and a test asserts a DSL empty `select` and a SQL `SELECT *`
  return the same columns for a source that has an enrichment attached.
- **The AST expander must not disturb `COUNT(*)`, aliases, or `ORDER BY`** → it rewrites only
  top-level select-list items whose node is a star `SqlIdentifier`, before validation, and the
  existing Flight/REST-SQL integration suites cover the untouched shapes.

## Migration Plan

1. `V18__add_heavy_columns.sql`: add the column with a `FALSE` default (no rewrite of existing rows'
   semantics), then seed the two `dial_usage_log` payload columns.
2. Deploy. No ClickHouse DDL, no data movement, no config.
3. Rollback: revert the code; the extra Postgres column is inert for an older build (Hibernate maps
   only declared fields), and heaviness can be cleared with `UPDATE column_mapping SET heavy = FALSE`
   or a schema `PATCH` without touching data.
