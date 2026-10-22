## Context

See `proposal.md` — Why, including the measured sizes. Three existing mechanisms decide most of the
shape:

- **The catalog already models "extra columns attached 1:0..1 to a source"** as an `ENRICHMENT`:
  `table_definition.source_table` + `grain_key`, joined by `CatalogQueryService.buildModel` as
  `source.grain_key = enrichment.grain_key` and published under the qualified flat name
  `<enrichment>.<column>`. Every read surface resolves through that one model, so nothing downstream
  needs to learn about the split.
- **Data-sync is already multi-target**: `sync.targets` is a list of `(table, mapping-file)`, and
  `BlobIngestor` offers each parsed record to every target whose `match:` predicate accepts it.
- **Every read runs with `final=1`** (`StructuredQueryExecutor.FINAL_SETTINGS`), which applies `FINAL`
  to *both* sides of a join, so a `ReplacingMergeTree` payload table dedups on read for free.

Constraints that shape the rest:

- `TableDdlRenderer` renders **no `PARTITION BY` for an enrichment**, so an API-created one cannot be
  partitioned — but this table is created by a migration and its DDL is hand-written.
- `ttl_only_drop_parts = 1` (ClickHouse `V9`) drops a part only once its **newest** row has expired,
  so a table under it must be partitioned on the TTL column or expiry degrades to "never".
- `BlobIngestor.missingDedupKey` inspects the **row** a target produced and drops the record for that
  target unless `trace_id`, `core_span_id` and `request_time` are all non-blank.
- **`EnrichSpecValidator` refuses a non-nullable enrichment column** as a pipeline read: an unmatched
  grain join renders it as a type default rather than `NULL` (`join_use_nulls` defaults to `0`). The
  shared condition is `EntityFields.absenceIsUnrepresentable`, which already exempted `array` and
  `map`. This is what D2 had to resolve.
- **ClickHouse has no `Nullable(JSON)`**, and `ClickHouseTypeMapper` has always silently declined to
  render one — so the catalog's `nullable` flag on an `object` column described nothing physical.

## Goals / Non-Goals

**Goals:**

- Two retentions, one ingest pass, no new service component and no query-engine change.
- One query path per value — no parallel spelling to keep working.
- Per-column catalog attributes survive the move, so `/v1/tables` and discovery do not regress.
- The payload table is as inert to the control plane as the two existing seeded tables (`system = TRUE`).

**Non-Goals:**

- No configurable retention: the TTL is DDL in a migration, like the one-year TTLs beside it.
- No dual write: the copy is one-shot (see D4).
- No compatibility alias for the bare names.
- The payload table is not a queryable entity of its own: `CatalogQueryService.findModel` filters to
  `type == SOURCE`, so the columns are reachable only through their parent's grain join.

## Decisions

### D1 — Enrichment of `dial_usage_log`, not a second source

Keeps one entity for consumers: the qualified name sits in the same flat schema as `request_time`, so
the row-mode DSL — which has no join syntax — can still reach the bodies, and the join fires only for
queries that name one.

*Alternative — a second `SOURCE` joined explicitly in SQL*: puts the bodies permanently out of reach of
the DSL and forces every existing SQL query into a join. Rejected.

*Alternative — expose both the bare and the qualified spelling*: would make this non-breaking, but two
names for one value means every consumer, spec scenario and test has to choose one, and the enrichment
mechanism — which exists for exactly this shape — would be shadowed by a parallel path. Rejected.

### D2 — All five verbatim-content columns move, and `object` joins the nullability exemption

The three bodies are `Nullable(String)` and move without ceremony. The two `JSON` columns needed a
rule change first, and the shape of that change is the substance of this decision.

**The obstacle.** `EnrichSpecValidator` refuses a **non-nullable** enrichment column as a
value-producing pipeline read, because an unmatched grain join renders it as a type default rather
than `NULL` — so a derived value could not distinguish "no payload row" from a real value. ClickHouse
has no `Nullable(JSON)`, so these columns cannot be declared nullable to satisfy it. Left alone, the
move would have made claims and headers **unreadable by every `enrich`/Class-1 pipeline**.

**Why the exemption is correct rather than convenient.** `absenceIsUnrepresentable` already exempts
`array` and `map`, on two grounds: ClickHouse has no `Nullable` form for them, so the rule would refuse
them permanently; and their type default is *empty*, so absence and emptiness are indistinguishable by
declaration rather than by accident. `object` satisfies both. Measured on ClickHouse 26.3 over a
`LEFT JOIN` whose right side is a non-nullable `JSON` column, an unmatched row reads as `{}`, and every
accessor ADAS exposes returns precisely what it returns for a present document lacking the key:

| accessor | over `{}` | over a present document missing the key |
|---|---|---|
| `toJSONString` | `'{}'` | `'{}'` … for an empty one |
| subcolumn access (the four scalar extractors) | `NULL` | `NULL` |
| `json_extract_array` | `[]` | `[]` |
| `json_extract_string_ci` | `''` | `''` |

There is no accessor that could tell the two apart, so there is no derived value the type default
could silently corrupt — which is the entire hazard the rule exists to prevent.

**The alternative is worse, and was measured.** Storing them as `Nullable(String)` to earn a real
`NULL` fails: `JSONExtract(<Nullable(String)>, 'k', 'Array(String)')` raises
`Code 43: Nested type Array(String) cannot be inside Nullable type`. It would buy the NULL by removing
array extraction from the columns entirely, and would change their catalog type from `object` to
`string`, which is a louder break than the one this change already makes.

**A latent divergence closes with it.** `TableColumnRules.resolveNullable` defaulted an unspecified
`object` column on an ENRICHMENT to `nullable = true`, and nothing refused an explicit `nullable: true`
— but `ClickHouseTypeMapper` never renders `Nullable(JSON)`. So the catalog could assert a nullability
the physical column did not have, and `absenceIsUnrepresentable` reads exactly that flag: the guard was
already disabled for API-created enrichment `object` columns, by a lie rather than by a decision.
`requireValidObjectColumn` now refuses the flag and `resolveNullable` forces non-nullable, matching
`array` and `map`. That is a behaviour change for the table API, and it makes the catalog honest.

*Alternative — set `join_use_nulls = 1` on the grain join*: would make an unmatched non-nullable column
read as `NULL` and retire the rule outright. Rejected as far out of scope: it changes the semantics of
every join the engine emits, not just this one.

### D3 — Grain key `event_id`, ordering key `(event_id)`, partitioned on `request_time`

`event_id` is the only single column identifying a `dial_usage_log` row (a deterministic `hash-uuid` of
`trace.trace_id` + `trace.core_span_id`, already the source's `identity_column`), and the grain model
is single-key by construction; the parent's five-column sort key cannot serve.

`ORDER BY (event_id)` therefore *is* the payload table's unique key, which makes the
`ReplacingMergeTree(_updated_at)` collapse correct. `PARTITION BY toYYYYMMDD(request_time)` is required
by the `ttl_only_drop_parts` constraint above.

Beyond the five moved columns the table carries `event_id` (grain key), `request_time` (partition +
TTL), `trace_id`/`core_span_id`, and `_updated_at` — none exposed in the catalog. The two id columns
are load-bearing: without them `missingDedupKey` sees blanks and drops **every** payload row, and the
rule matters on its own terms, since a record with a blank `trace_id` hashes to a *constant*
`event_id` that would collapse every such payload into one row.

### D4 — Create, copy, drop — all in migrations

`V10` creates the table, `V11` copies every row inside the 90-day window into it, `V12` drops the five
columns from `dial_usage_log`.

The copy is bounded to the retention window rather than the whole table: copying older rows would write
rows the TTL then expires, paying for the transfer and the merge to delete them again. On a deployment
whose whole table is younger than 90 days everything is copied; on an older
installation the pre-window tail is dropped with the columns, which is what the shorter retention
means.

**The cost is a transient duplicate, not a permanent one.** Between `V11` and `V12` the bodies exist in
both tables — tens of GB compressed against hundreds of GB uncompressed — and `V12`
releases the originals. `ALTER TABLE … DROP COLUMN` detaches each column's files per part rather than
rewriting the survivors, so it reclaims space without needing free space first. Net disk after both is
about where it started, and lower from there on as rows age past 90 days rather than a year.

**Idempotence is what makes a failure recoverable.** ClickHouse gives the migration no transaction, so
an interrupted copy leaves rows behind and Flyway records the migration failed, blocking startup until
`flyway repair`. The target collapses on `event_id` (`ReplacingMergeTree` ordered by it) and reads run
with `final=1`, so re-running the copy merges away rather than duplicating content — which makes
`flyway repair` + restart a correct recovery rather than a doubling. That property is asserted against
real ClickHouse in `PayloadBackfillMigrationIntegrationTest`.

*Alternative — no backfill at all*: was the plan for one revision, and would have deleted every body
the system holds at deploy. Rejected once the shape was clear: it trades an operational cost that ends
in minutes for permanent data loss.

*Alternative — an operator-run copy outside Flyway*: never blocks startup and needs no repair path, but
leaves the table silently empty on any deployment where the step is skipped — and the drop would then
have destroyed the content it was supposed to preserve. Rejected: the copy and the drop must not be
separable by an operator's oversight.

The residual risk is that `V11` is slow and startup waits for it. That is documented in the migration
header, along with the out-of-band route for an installation that cannot absorb the peak.

### D5 — Move the `column_mapping` rows rather than re-declare them

`column_mapping`'s primary key is `(table_name, source_name)`, so re-keying is legal and `sensitive`,
`heavy`, `nullable`, `description`, `display_name` and `tag` ride along untouched. Re-declaring them by
hand would restate six attributes across five columns — thirty chances to silently drop `sensitive`
from a column holding raw user content. The vacated ordinals are left as gaps: `ordinal` only orders,
and renumbering would rewrite the column order every client sees.

A raw SQL `UPDATE` fires no Hibernate event, so the move produces no `audit_trail` rows. That matches
every other seeding migration.

### D6 — Retention: 90 days, expressed as the parent's TTL is

`TTL toDateTime(request_time) + INTERVAL 90 DAY` with `SETTINGS ttl_only_drop_parts = 1` in
`CREATE TABLE`. `toDateTime(...)` is required around a `DateTime64` or ClickHouse rejects the
expression (`BAD_TTL_EXPRESSION`).

It expires nothing for the first 90 days after deployment, because the table starts empty; the storage
curve falls only once rows begin aging out. The catalog's `ttl_*` fields stay `NULL`, following `V17`'s
rule that a seeded system table's retention is owned by its migration.

## Risks / Trade-offs

- **Every stored declaration naming a bare payload column breaks at once**, and a pipeline's failure
  surfaces on its next run rather than at deploy → mitigated by being loud (proposal, migration
  headers, `docs/heavy-columns.md`) and by the failure naming the column. Deliberately not mitigated
  by an alias.

  **The failure is clear on the DSL and poor on REST SQL**, verified at runtime: the DSL returns
  `unknown field 'jwt_claims'`, while `execute-sql` returns Calcite's
  `The definition of column 'jwt_claims' depends on itself through the following columns:
  'jwt_claims'`. That message is unhelpful for an author who needs to learn the qualified spelling —
  but it is **pre-existing and not caused by this change**: `SELECT no_such_column FROM
  rate_analytics` produces the identical wording, so every unknown column on the SQL surface has
  always read this way. Left alone here because fixing Calcite's unknown-identifier reporting is a
  separate change with its own blast radius; recorded so the breaking-change mitigation is not
  overstated.
- **`V11` blocks startup for as long as the copy takes, and a failure needs `flyway repair`** → the
  copy is idempotent, so the repair path is correct rather than duplicating; the header documents both
  that and the out-of-band route. Worst case is a long startup on the first deploy after the change.
- **Between `V11` and `V12` the bodies are stored twice** → transient by construction, released by the
  drop; an operator applying the three out of band should not leave a long gap between them.
- **Bodies older than the 90-day window are deleted, not copied** → deliberate: they are past the
  retention this change imposes. Stated in `V12`'s header rather than left to be discovered.
- **A query naming a body column now joins a table with unbounded rows.** ClickHouse builds the
  right-hand side of a hash join before filtering → mitigated by the 90-day retention and by partition
  pruning when the query carries a `request_time` predicate; the existing query budget refuses the
  unbounded case, as it already did for projecting `assembled_response` across an open window.
- **A claim/header column reads `{}`, never `NULL`, when the payload row is gone.** After 90 days the
  event survives and its documents do not, and the difference is invisible to `IS NULL` → deliberate
  and documented (D2, `docs/heavy-columns.md`); a body column is `Nullable(String)` and does read
  `NULL`, so a query that must distinguish the two has a column that can. The risk is a consumer
  reading `{}` as "the caller sent no claims" rather than "the claims have expired".
- **`object` columns lost a guard that was never actually protecting them.** Exempting `object` from
  `absenceIsUnrepresentable` means a pipeline can derive a value from one and see `{}` for an
  unreached row → accepted, because every accessor already collapses that case (D2), and because the
  guard was inoperative for API-created enrichment `object` columns anyway.
- **An existing enrichment with an `object` column declared `nullable: true` now describes itself
  differently.** The stored flag is unchanged by this migration — only newly created or updated
  columns resolve non-nullable → the catalog row keeps whatever V-era value it holds; nothing reads it
  for `object` any more except `absenceIsUnrepresentable`, which now short-circuits on the type.
- **A test fixture writing a payload row at a historical timestamp silently loses it.** The 90-day TTL
  expires it on arrival and ClickHouse drops the part, leaving the grain join with nothing → the
  integration fixtures stamp payload rows `now()` and say why; the two tables join on `event_id`,
  never on time.

## Migration Plan

With `clickhouse.migrations.enabled=true` the order is automatic. Out of band, apply **both**
ClickHouse migrations **before** deploying the new build:

1. `V10` (`CREATE TABLE dial_usage_log_payload`) — needs the server-side encryption key
   `encryption_codecs` for `AES_256_GCM_SIV`, exactly as `V1`/`V7` do.
2. `V11` (the copy) — the expensive step; safe to re-run, and worth checking free space before and
   after.
3. `V12` (the five `DROP COLUMN`s) — the edited `dial_usage_log` mapping no longer declares them, and
   `SchemaValidator` refuses a mapping that names a column the table lacks. Releases the duplicate.
4. Deploy. Postgres `V38` re-keys the catalog and the cutover is live on the next request.

**Rollback**: reverting the build and the catalog restores the *shape* — `ALTER TABLE … ADD COLUMN` is
metadata-only and reads back `NULL` — but the content now lives on `dial_usage_log_payload`, so a real
rollback means copying it back the other way. It is possible (the inverse `INSERT … SELECT` joined on
`event_id`) and it is not cheap. Treat the change as one-way in planning.
