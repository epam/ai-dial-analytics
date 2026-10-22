## Context

`TableDdlRenderer` currently picks the ClickHouse engine from `TableType` alone: an `ENRICHMENT`
becomes `ReplacingMergeTree(_updated_at)` ordered by its grain, and everything else becomes a plain
`MergeTree` ordered by its declared `ordering_key`. There is no way to ask for a **keyed, replacing**
`source`.

That is exactly what a rollup needs. The follow-up `add-aggregate-pipeline` change recomputes a group
key's row and re-inserts it; unless the target collapses by key, every refresh leaves another copy
behind. A ClickHouse engine is fixed by `CREATE TABLE` and cannot be `ALTER`ed, so this facet has to
exist before the first rollup table is created — retrofitting means dropping and recreating tables
that by then hold data.

The same ADR (0006) that introduces the facet also removes the `AGGREGATION` table type: aggregation
is a *pipeline* kind whose output is an ordinary `source`. The type has been accepted-but-inert since
it was specified (`TableType.AGGREGATION`, three references), and a `type` the catalog stores but no
path materializes, queries, or joins is worse than no type at all.

Constraints: the table API is unreleased, so a breaking narrowing is acceptable now and cheap; the
catalog is the single source of truth for the query path; `PATCH …/schema` must stay incapable of
changing physical identity.

## Goals / Non-Goals

**Goals:**

- A `source` can be declared to collapse writes by its `ordering_key`, materializing as
  `ReplacingMergeTree(_updated_at)`.
- The facet is immutable and declared once, at create, next to the other identity fields.
- Zero behaviour change for every existing caller (absent `write` ⇒ `append` ⇒ today's DDL byte for
  byte).
- The dead `AGGREGATION` type is gone, and the specs that referenced it stop describing a type that
  does not exist.
- Reads of a keyed source are correct with no query-path change.

**Non-Goals:**

- The pipeline object, aggregate specs, refresh execution — all `add-aggregate-pipeline`.
- Column roles deriving `ORDER BY`/partition/TTL (the alignment doc's `introduce-column-roles`); this
  change keeps the caller-declared `ordering_key` exactly as it is.
- Type-vocabulary alignment, `allowed_roles` read RBAC, tuple grain, `table_audit`, properties bag.
- Changing enrichment behaviour in any way.

## Decisions

**1. `write` is declared at create, not at define-schema.**
The facet selects the engine, and the engine is chosen by the `CREATE TABLE` that `POST …/schema`
issues — so define-schema is where it is *used*. It is nevertheless declared at *create*, alongside
`type` and an enrichment's `source_table`, because it is immutable identity: a table that collapses by
key is a different kind of thing from one that accumulates, and callers should not discover that at
materialization time. This also keeps `DefineSchemaInput` purely physical-schema-shaped and means a
`FAILED` table retried through `POST …/schema` cannot silently change discipline between attempts.
*Alternative considered:* declaring it in the schema body — rejected, it would make the facet
re-declarable on every retry and blur the identity/schema split the capability is built on.

**2. Engine selection becomes a function of `(type, write)`, expressed as one switch.**
`TableDdlRenderer.CreateSpec` already carries `kind`; it gains `write`, and the engine expression plus
the `_updated_at` column decision are derived from the pair. The enrichment arm keeps its current
behaviour by construction (an enrichment is always version-collapsing), so the change is additive to
the renderer rather than a rewrite. The `UPDATED_AT` / `UPDATED_AT_TYPE` constants already in the
renderer are reused verbatim — one version-column definition for both callers.

**3. `write` is rejected on an enrichment rather than accepted-and-ignored.**
An enrichment is inherently `upsert_by_key` on its grain. Accepting the member would create two
sources of truth for the same fact (and a way to declare `append` on a table that cannot be append);
rejecting it with 422 keeps one truth per kind. The catalog column still stores a value for every row
(`APPEND` default) — it is simply not caller-settable for enrichments and not read for them.

**4. `_updated_at` stays a hidden system column, with no new rules.**
Everything an upsert source needs already exists generically: the reserved `_`-prefix rule in schema
validation, the "system columns are not caller-settable" rule on row writes, and the exclusion of
system columns from exposed `columns`/`column_count`. So this change adds no new reservation
mechanics — it only puts a second kind of table behind the same rules, which is why the spec delta
states the consequence rather than inventing a requirement.

**5. Reads need nothing.**
`StructuredQueryExecutor` attaches `final = 1` to every statement it executes (`FINAL_SETTINGS`),
which is documented in `structured-query` as applying to "any table backed by a
`ReplacingMergeTree`". An upsert source therefore deduplicates latest-per-key on read the day it
exists, including through the REST SQL and Flight SQL surfaces that delegate to the same pipeline. The
spec delta makes this explicit with a scenario so it becomes a tested guarantee instead of an
inference. *Alternative considered:* rendering `FINAL` into the SQL per-table — rejected, the existing
setting-based approach already covers it and per-table rendering would fragment the guarantee.

**6. Dropping `AGGREGATION` is done as a narrowed CHECK constraint, not a data migration.**
No rows can exist with `type='AGGREGATION'` in practice (the type materialized
nothing, so such a row would be a `PENDING` shell), but the migration is written defensively: it
deletes any `AGGREGATION` definition before narrowing the CHECK, so the constraint cannot fail on a
dirty database and take startup down with it. A deleted shell holds no ClickHouse table and no rows.

**7. Wire name is `write`, stored column is `write_mode`.**
`write` matches the design vocabulary and reads naturally in the API; `write_mode` avoids colliding
with SQL/reserved-word awkwardness in Postgres and in JPA attribute naming. The mapper does the
translation, as it already does for `source_name`/`name`.

## Risks / Trade-offs

- **A caller who wanted the old `aggregation` type gets a 422** → The type materialized nothing and
  the API is unreleased; the proposal's migration note gives the one-line replacement
  (`{type: "source", write: "upsert_by_key"}`). Called out as BREAKING.
- **`FINAL` on a large upsert source costs more than on a plain `MergeTree`** → Inherent to
  version-collapsing engines and already accepted for enrichments; rollup targets are orders of
  magnitude smaller than the sources they roll up, which is the entire point of materializing them.
- **`ordering_key` becomes semantically load-bearing for an upsert source** (it *is* the unique key,
  not just a sort order) → Stated in the spec; the follow-up change validates that a rollup target's
  `ordering_key` equals the pipeline's `group_by`, so the two cannot drift.
- **A default of `append` means a caller can forget the facet and get an accumulating rollup** →
  Mitigated in the follow-up change, which rejects a pipeline whose target is not `upsert_by_key`
  rather than silently producing duplicates.
- **Enum narrowing plus a new NOT NULL column in one migration** → Both are additive-with-default and
  guarded (`DEFAULT 'APPEND'`, defensive delete before the CHECK narrows), so the migration is
  idempotent in effect and safe to run against a populated catalog.

## Migration Plan

1. `V19__table_write_facet.sql`: add `write_mode TEXT NOT NULL DEFAULT 'APPEND'` with its CHECK;
   delete any `type='AGGREGATION'` definitions; drop and re-add the `type` CHECK narrowed to
   `('SOURCE','ENRICHMENT')`.
2. Deploy: existing definitions read back as `append` and re-materialize to identical DDL. No
   ClickHouse DDL is issued by the migration.
3. Rollback: the previous image ignores `write_mode` (an unmapped column) and the widened `type`
   CHECK is a superset, so the old code runs unchanged against the new schema. Only a table already
   created as `upsert_by_key` would be mis-described by the old code — none can exist before the
   follow-up change lands.

## Open Questions

None blocking. Two deliberately deferred to `add-aggregate-pipeline`: whether an `upsert_by_key`
source should also be allowed a `partition_by` (no reason to forbid it, and no reason to test it until
a rollup wants it), and whether the `ordering_key`-is-the-unique-key property should be surfaced in
discovery output.
