## Context

See proposal.md — Why. The mechanics that shape the approach:

- `TableColumnRules.requireKeyable(field, columnName, type)` is the existing single chokepoint for
  "may this column key a table", today rejecting only `map`. It is called from **two** layers, and the
  duplication is deliberate: `CatalogSchemaWriter.persistDraft` checks before writing the catalog rows,
  and `TableSchemaCreationService.sourceSpec`/`enrichmentSpec` re-check when building the `CREATE` spec
  from the persisted definition. The second guards a definition that was persisted by an earlier
  version of the rules (a `FAILED` table re-materialized after a rule tightened).
- Both call sites already hold the physical column row (`ColumnMappingEntity`), which carries
  `isNullable()` — the resolved flag, after `TableColumnRules.resolveNullable` has applied the
  kind-based default. No new lookup is needed.
- `TableTtlRules.requireValid` is the shape to copy: it takes a `PhysicalColumn(type, nullable)` and
  refuses a nullable TTL column with a message that says which ClickHouse rule it is protecting.

## Goals / Non-Goals

**Goals:**

- One rule, enforced at both existing chokepoints, so a nullable key can neither be persisted nor
  re-materialized.
- The refusal is a 422 `ErrorView` naming the field and the column, before any catalog write or DDL.

**Non-Goals:**

- Enabling ClickHouse's `allow_nullable_key` to make nullable keys work instead. It is off for good
  reason: a `NULL` in a sorting key makes range pruning and the primary index reason about a value
  with no ordering, and a `NULL` partition lands rows in a partition that no `PARTITION BY` predicate
  selects. This change codifies the refusal rather than widening what the engine accepts.
- Any rule over an enrichment's `grain_key` (see Decisions).
- Repairing definitions already stuck in `FAILED` with a nullable key. There can be none in
  ClickHouse — the `CREATE` was refused — but a catalog row may exist; re-posting the schema with the
  column non-nullable is the existing repair path and is unchanged.

## Decisions

**1. Extend `requireKeyable` rather than add a parallel rule.**
The signature becomes `requireKeyable(field, columnName, type, nullable)`. Every caller already has
the nullability in hand, and folding it in means a future physical key gets both checks by construction
instead of one. *Alternative rejected*: a separate `requireNonNullableKey` called next to
`requireKeyable` at all six sites — same coverage, but it makes "may this column key a table" answerable
in two places, which is how the nullability gap arose in the first place.

**2. Enforce it for `ordering_key` and `partition_by`, not for `grain_key`.**
`requireKeyable` is shared by all three fields, so the parameter is passed at all three sites — but the
grain-key site passes the value that makes it a no-op: `enrichmentSpec` looks up the grain column on the
**source** table and renders the enrichment's own grain column with `nullable = false` unconditionally, so
the physical `ORDER BY (grain_key)` is never over a `Nullable(...)` column no matter what the source
declares. Passing the source column's flag would reject enrichments that materialize fine today, for a
constraint ClickHouse does not impose. The grain-key call therefore passes `false` with a comment
explaining that it is the *enrichment's* physical column being described, not the source's.
*Alternative rejected*: requiring a non-nullable source column anyway, on the argument that a null grain
value can never match through the grain join. That is a data-quality argument, not a materialization one,
and it would be a breaking change to a shipped surface — out of scope for this change.

**3. Message text names the ClickHouse rule, per field.**
`ordering_key column 'x' must be non-nullable (ClickHouse rejects a nullable sorting key)` and
`partition_by column 'x' must be non-nullable (ClickHouse rejects a nullable partition key)` — the
wording ClickHouse itself uses (`Sorting key` / `Partition key`), so an operator searching the engine's
error finds ours. Derived from the `field` argument rather than written per call site, keeping the six
call sites uniform. *Alternative rejected*: one generic "must be non-nullable" message; it loses the
pointer to *why*, which is the part a caller cannot guess (nullable is legal for the same column
everywhere else in the schema).

**4. No new exception type or error code.**
`TableValidationException` → the existing 422 with the established `error` code, exactly as the
`map`-key, TTL and scan-metadata refusals already do. Nothing about this failure needs to be
distinguishable by machine code.

## Risks / Trade-offs

- **[A caller's working request starts failing]** → It cannot: the same request already fails, at
  `CREATE TABLE`, with a worse outcome. Verified against clickhouse-server 26.3 (proposal.md — Why).
  The only observable change for such a caller is a 422 instead of a ClickHouse error plus a `FAILED`
  definition.
- **[A `FAILED` definition persisted before this change now refuses to re-materialize]** → That is the
  intent, and it is not a regression: the `CREATE` it would issue is the one ClickHouse refuses. The
  message now says which column to fix. Repair is the existing re-post of the schema.
- **[The rule is bypassed if a future key field skips `requireKeyable`]** → Mitigated by decision 1
  (single chokepoint) and by a test that asserts the built-in seeded sources satisfy the rule, which
  would catch a key field added without validation.
- **[Divergence from ClickHouse if `allow_nullable_key` is ever enabled server-side]** → The rule
  would then be stricter than the engine. Accepted: ADAS does not set the setting, and a nullable key
  is a correctness hazard regardless of whether the engine tolerates it (see Non-Goals).

## Migration Plan

None. No migration, no new config key, no credential change. Nullability is fixed at DDL and the rule
is evaluated only when a schema is declared, so no existing ClickHouse table or catalog row is touched.
Rollback is reverting the commit.
