## Context

A rule has no read source of its own. `RuleService#sourceOf` derives it as
`target_enrichment.source_table`, `RuleGovernanceValidator#validate` re-derives the same value, and
`SourceScopeGuard` then pins `filter_sql`, `ready_when.signal`, and every Class-1 `sql` expression to
that one table. Because an enrichment's `source_table` is simultaneously its **read-side** attachment —
`CatalogQueryService` builds `LEFT JOIN <enrichment> ON <source>.<grain_key> = <enrichment>.<grain_key>`
from it — the table an enrichment hangs off and the table its values are computed from are forced to be
the same table.

The shape this forbids is ordinary: `conversations_feedback` is an enrichment of `conversations`
(grain `chat_id`), but the evidence lives in `rate_analytics`, which carries `chat_id` too. Registering
that rule today fails the gate with 422 `rule_validation_failed` — "filter must read only its rule's
source 'conversations'".

Three facts constrain the fix:

- **Every rule-scoped read already funnels through one value.** `ScanService`, `GroupFetchService`, and
  `MaterializeService` all take `ResolvedRule#source()`; none resolves a source itself. Changing what
  that value is changes all three at once, with no change to their bodies.
- **A rule reads exactly one table.** The bounded Calcite grammar rejects joins, subqueries, and CTEs,
  and `ScanSourceMetadata#sourceModel` deliberately strips the enrichment joins. So the fix cannot be
  "let the filter mention another table" — it has to be "let the rule name which single table it reads".
- **The catalog is the authority on what is scannable.** `version_column`/`identity_column` are
  caller-declared per table; the read source has to satisfy that contract like any other source.

## Goals / Non-Goals

**Goals:**

- Let a rule declare the table it reads, independently of its target enrichment's `source_table`.
- Keep every existing rule byte-identical in behaviour, with no data migration and no client change.
- Keep the sensitive-column entitlement gate **total** — every referenced column resolvable in the
  catalog of the table the rule actually reads.
- Move what were execution-time failures (unscannable source, unjoinable grain) to registration.

**Non-Goals:**

- Multi-table rules. A rule still reads exactly one table; joins stay out of the bounded grammar.
- Changing `source_table`'s meaning, mutability, or any read-side behaviour it drives.
- Aggregating rules. A group-grain deterministic (Class-1) rule remains rejected.
- Per-rule *write* targets. `target_enrichment` and the one-rule-per-target invariant are untouched.

## Decisions

### 1. The read source lives on the rule, not on the enrichment

**Decision.** Add `rule.source_table` (nullable). Rejected alternatives:

- *A second column on `table_definition`* (e.g. `derive_from` beside `source_table`). This makes the
  enrichment's catalog identity carry two source-shaped facts that mean different things, and
  `source_table` is immutable while the derivation is exactly the part an operator wants to change.
- *Allowing `source_table` itself to point elsewhere.* It would silently repoint the grain auto-join,
  the flattened schema view, and the delete guard — the read side — which is the opposite of the intent.

Today one-rule-per-target makes per-rule and per-enrichment numerically equivalent, so this costs
nothing now and stays correct if that invariant is ever relaxed.

### 2. NULL means "derive", resolved on read — no backfill

**Decision.** A rule that does not declare a source stores `NULL`, and `RuleService#sourceOf` resolves
`declared ?? target.source_table`. Rejected: backfilling every existing row with its target's
`source_table` at migration time. Backfill would make `sourceOf` a plain column read and the
"rules reading table X" query a single indexed lookup, and it is safe today because `source_table` is
immutable — but it destroys the distinction between "follows its target" and "happens to point at the
same table", which is the distinction the API documents and the generation-bump rule below depends on.
The cost is one extra branch and a two-part query, both trivial.

### 3. `sourceOf` is the single resolution point; the read paths are untouched

**Decision.** Concentrate the change in `RuleService#sourceOf` / `#sync` / `#versionColumnsOf` and in
`RuleGovernanceValidator`, and change nothing in `ScanService`, `GroupFetchService`, or
`MaterializeService` — they consume `ResolvedRule#source()` and inherit the new value. `SourceScopeGuard`,
`FilterSqlValidator`, and `ExprSqlValidator` are already parameterized by a source string and need no
logic change either, only a different argument.

The one place that must be rewritten rather than inherited is `RuleGenerationService`, which finds rules
affected by a `version_column` repoint by walking `table_definition.source_table → enrichments → rules`.
Under the decoupling that set becomes: rules whose `source_table` equals the repointed table, **plus**
rules with a NULL `source_table` whose target enrichment hangs off it. A rule that declares another read
source must drop out of the set — bumping it would be a false change signal, and failing to bump a rule
that declares the repointed table would hand the runner a stale column name.

### 4. `SourceScopeGuard` is re-pointed, not removed

**Decision.** Keep both of its checks and apply them to the read source. The originating request was to
remove or soften the guard; that is rejected because the guard is not what forbids the use case — the
*derivation* is. Deleting the guard would leave the sensitive-column entitlement check resolving
referenced names against a catalog they are absent from (so it gates nothing), and would convert a 422
at registration into a ClickHouse error at scan time, since the rendered statement would still read one
`FROM` table. Re-pointing it keeps registration total and makes the intended rule expressible.

### 5. Two replacement invariants, checked at register and enable

The invariant being given up (read source == target's `source_table`) was doing two jobs by accident.
Both are restated explicitly:

- **Grain key present and type-compatible.** The target's grain key must be a declared column of the
  read source (either spelling) whose type is compatible with the type the target is keyed at. Presence
  alone is not enough: a same-named column of another type would key the enrichment with values its
  grain join cannot match, and the failure would surface as an empty join rather than an error.
- **Scan metadata present.** The read source must declare `version_column` and `identity_column`.
  `ScanSourceMetadata#cursorColumns` already fails without them; checking at registration reports it to
  the author instead of to the runner. Reuse `ScanSourceMetadata` rather than reading
  `TableDefinitionEntity` directly, so registration and scan cannot disagree about what "scannable" means.

**Both checks run for a declared source only.** The first *does* hold by construction for a derived
source, so applying it there would only re-check a catalog fact. The second does **not**: declaring scan
metadata is optional, and a rule over an enrichment of an unscannable source registers today and is
refused on the scan path instead. Applying it to derived sources would invalidate rules that are valid
now — an unacceptable side effect of adding an optional field. Scoping both to a declared source makes
"no existing rule can fail the new gate" exactly true rather than nearly true.

### 6. Wire name `source`, column name `source_table`

**Decision.** The request field is `source`, matching the field `RuleDto` already returns; the column is
`rule.source_table`, matching `table_definition.source_table`. Rejected: a distinct wire name such as
`read_source`, which would give one fact two names on the same resource — the response would still have
to call it `source` for compatibility.

### 7. A declared read source is a delete dependency

**Decision.** `TableCatalogService#delete` gains a rule check beside its existing enrichment and
pipeline-input checks, rejecting with the same HTTP 409 and naming the rule. The FK on the new column is
plain `REFERENCES table_definition (name)` (RESTRICT), acting as the database backstop for a race that
slips past the pre-check — the pattern `RuleService#save` already uses for the unique constraints.
Deliberately **not** `ON DELETE CASCADE`: that is right for `target_enrichment` (a rule without its
target is meaningless) but wrong here, where silently deleting rules as a side effect of dropping a
table would be a surprising loss of governed configuration.

### 8. Migration `V26__rule_read_source.sql`

Nullable column, FK, and one index for the repoint-bump query:

```sql
ALTER TABLE rule ADD COLUMN source_table TEXT REFERENCES table_definition (name);
CREATE INDEX rule_source_table_idx ON rule (source_table);
```

No backfill (decision 2), no `NOT NULL`, no data rewrite — the migration is additive and instantly
reversible by dropping the column. It goes in `db/migration/postgres/`; there is no ClickHouse side.

## Risks / Trade-offs

- **A rule's read source changes on `PUT`, invalidating cursors in flight.** → `CursorCodec` already
  binds a cursor to its source entity and rejects a mismatch with 400 `invalid_cursor`, and any rule
  mutation bumps `generation`/`updated_at`. The runner therefore restarts paging cleanly rather than
  silently mis-seeking. Worth an explicit test, since it is the one place the decoupling can produce a
  cursor whose entity no longer matches a valid rule.

- **Two tables now govern one rule, and they can drift.** The target enrichment's grain column type is
  resolved from *its* `source_table` when its schema is materialized, while rows are derived from the
  read source. → Decision 5's type check ties them at registration; `PATCH .../schema` already refuses
  to drop or re-sensitize a declared `version_column`/`identity_column`, and the grain key of a
  materialized enrichment is likewise not droppable.

- **The authorizing table is no longer the table being read.** A caller with access to
  `conversations_feedback` can drive a rule that reads `rate_analytics`. → This is not a new hole: the
  `access` model has no read grant to bypass (it delegates `write` and `modify` only), rule registration
  is `FULL_ADMIN`-only, and per-column exposure is still decided by the sensitive-column re-check applied
  against the read source at every read. It is nonetheless a real change in what a target-enrichment
  grant reaches, so it is stated in the `enrichment-runtime` spec rather than left implicit.

- **A group rule over a read source can be silently empty** if the grain key exists but never matches
  (e.g. `chat_id` populated in one table and blank in the other). → Registration cannot detect this and
  should not pretend to; the group-assembly endpoint already returns an empty group as a normal 200,
  and the operator sees zero rows written. Documented, not enforced.

## Migration Plan

1. Ship `V26` (additive column + index). Existing rows keep `NULL`, meaning "derive from the target" —
   which is exactly what they mean today, so the service behaves identically before and after.
2. Deploy the service. `source` is optional on write and identical on read for every existing rule, so
   runners need no coordinated release; a runner that ignores `source` and re-derives it stays correct
   until someone declares one.
3. **Rollback**: redeploy the previous service version. Rules that declared a `source` would then be
   silently re-derived, so rollback is only safe before any rule declares one; after that, repoint or
   delete those rules first. Dropping the column is a separate, later step and is not required for
   rollback.

## Open Questions

None blocking. One deliberate deferral: whether a future change should let a **rule** be repointed to a
new target enrichment and read source in one operation without a `generation`-bump storm — today `PUT`
already rewrites both and bumps once, so the question only arises if bulk repointing is ever needed.
