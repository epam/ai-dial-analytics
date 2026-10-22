## Context

An aggregate pipeline is declared today as `group_by` + `measures` (+ `inputs`, `target`, `filter`).
The original design (`archive/2026-07-30-add-aggregate-pipeline/design.md`, Decision 2) rejected a raw-query wire
shape. This change reverses that for **authoring only**. The refresh contract stays as it is: probe, then a full
recompute of the dirty keys into `ReplacingMergeTree(_updated_at)`, with the cursor advancing after the write. Every
governance gate is kept.

Three open risks were checked before proposing:
- **Parsing.** Babel `INSERT` parsing was spiked.
- **Real pipelines.** The existing aggregate pipelines were rewritten as `INSERT … SELECT` and validated.
- **Dependency guards.** The token-scan guards were audited for false positives and false negatives.

The findings are folded in below.

## Goals / Non-Goals

**Goals:**
- Accept a pasted `INSERT INTO <target> (cols…) SELECT …` as an aggregate declaration and refresh it
  incrementally. The result must be identical to a full recompute.
- Keep every gate the form path has: sensitive access and lineage, determinism, the function catalog, enrichment
  clampability, and type/nullability parity.
- Store the SQL verbatim as the declaration, and return it from GET, export and audit.

**Non-Goals:**
- Full-recompute mode, explicit JOINs, multiple inputs, UNION, or user-supplied incremental macros.
- Multi-block statements (nested aggregate/DISTINCT). The sealing cases found so far, nested-hop durations and
  per-trace token dedup, can already be built as a rollup of `turns`.
- Exposing the generated refresh INSERT. Preview keeps returning the sample's own statement, and `view=compiled`
  stays enrich-only.
- A Flyway migration, a new config property, or a change to the `adas-mcp` snapshot in this repo. The snapshot
  refresh is a follow-up.

## Decisions

### D1. Parse and re-render, never pass through
The statement is parsed with the existing Babel front end, translated into the structured-query model, validated,
and rendered by `StructuredQueryBuilder`.
- *Rejected: passing the raw text through.* It cannot be governed: sensitive gate, determinism and the function
  catalog.
- *Rejected: user macros for the incremental window.* Users get the window wrong, and it duplicates `CursorPredicates`.

### D2. Single block only (v1)
The source must plan to exactly one `SqlTranslation`. CTEs and derived tables that only filter or compute already
fold into one block (`RelToStructuredQueryTranslator`). A `SqlCompositeTranslation` is refused with a 422.
- Why: with one block, the dirty-key restriction and the user's WHERE apply at the same level, so a dirty group is
  recomputed in full.
- Keys and measures are read straight from the inlined select expressions, so there is no `getColumnOrigins` and no
  `RelRoot` retention.
- *Rejected: expression substitution through derived blocks.* That machinery does not exist, and no existing pipeline
  needs it.

### D3. Statement extraction
`planInsert` works in these steps:
1. Strips the trailing `;`.
2. Runs `SqlFinalModifier.rewrite`.
3. Calls `parseStmtList` and requires exactly one `SqlInsert`, with a non-null column list and a query source.
4. Cuts the **raw** text just past the column list's closing `)`. The `SqlNodeList` position includes both parens.
5. Passes the cut to `plan()`.

Spike results:
- `getSource()`'s position cannot be used. For a `SqlOrderBy` it covers only the last sort item, and for a `SqlWith`
  only the WITH list.
- The rewrite inserts only after FINAL tokens, and those come after the column list, so the raw offset is valid. The
  spike confirmed this with FINAL, `'--'`, `'x)'` and multi-line inputs.
- The INSERT itself is never Calcite-validated and never goes through `screen()`.
- A `FORMAT` token is refused before parsing, because Calcite reads `expr FORMAT JSON` as JSON-value syntax and
  reports a misleading error.

### D4. Nullable group keys are refused
Under the pinned `transform_null_in=0`, the dirty-key tuple `IN` never matches a NULL component. A group with a NULL
key would therefore never be written.
- The 422 suggests `coalesce(key, <default>)`.
- The "enrichment key can represent absence" rule applies to the **enrichment column** under the key expression, not
  to the key's final nullability, because the coalesce is the author's explicit mapping of absence.
- This is the one existing pipeline that needs a rewrite: `sessions_rollup` → `coalesce("usage_client_identity.client_session_id", '')`.
- *Rejected: carrying the form path's "nullable key needs a filter" rule over.* The WHERE is free SQL there, so the
  check has nothing to anchor on.

### D5. Dirty-key restriction is passed into `build`, not ANDed afterwards
The restriction is passed as an extra condition, so it reaches `EnrichmentJoins.addReferenced(…, keyPredicates)`.
Otherwise the grain join is unkeyed and hashes the whole enrichment every tick, which is the `sessions_rollup` cost
regression.
- The pushdown has to work when the key is an **expression** over an enrichment column (the `coalesce` from D4).
  `sessions_rollup` in SQL form is the regression fixture.
- **Absence-value hole.** Restricting the enrichment rows to dirty keys turns every non-dirty input row into
  "unmatched". Its key then evaluates to the absence value (`coalesce(NULL, '')` = `''`). If `''` is itself dirty,
  those rows leak into the `''` group. The form path is safe only because NULL never satisfies `IN`.
  - Rule: the compiler evaluates the key with its enrichment columns NULL. If that value is among the dirty keys, the
    grain join for that tick runs unrestricted.
  - How: the dirty set is a sub-select the service never sees, so the choice is made inside the statement. The key
    is rendered a second time against bindings whose enrichment columns are the literal `NULL` (the engine folds it
    to a constant, `''` for the `coalesce`), and the derived table's `WHERE` is `keys IN dirty OR image IN dirty`.
  - Cost: such ticks lose the pushdown saving. `sessions_rollup` avoids it in practice, because its WHERE
    (`length("enr.col") > 0`) drops unmatched rows. A fixture must cover both a statement with that WHERE and one
    without it.
- `dirtyKeys`, `bucketBound` and `groupEnrichment` are shared with `AggregateCompiler`, not copied.
- The root is rendered with `LimitPolicy.NESTED`, so no page `LIMIT` is added.
- `withFinalRead(false)` keeps the `FINAL` keyword out; reads use the `final=1` setting only.

### D6. Bucket step and UTC
- A bucket with step n≠1 (`date_bin(2, 'hour', t)`) is refused in v1. `bucketBound` hard-codes
  `+ INTERVAL 1 unit`, so a 2-hour bucket would silently drop rows.
- A recognised bucket function with no explicit zone is rendered UTC-pinned, like the form path. That keeps form and
  SQL twins equivalent.
- No existing pipeline uses a bucket, so both rules are covered by synthetic tests only.

### D7. Dependency guards token-scan; they never parse
`declaredFieldsOf` gets one SQL branch. It returns the union of three sets:
- `QualifiedColumnTokens.of(sql)`;
- the contents of every double-quoted identifier, lowercased. This closes the false negative where `alias."enr.col"`
  matches as `alias.enr`;
- `identifiers(body)`, where `body` is the text with the `INSERT INTO <t> (cols)` prefix stripped by an anchored
  regex. If the regex doesn't match, the full text is scanned, so the result only ever over-approximates.

Effects:
- Bare names are fully covered, so the rename-repoint tolerance (PGS:210-216) does not apply in SQL mode.
- *Rejected: parsing in guards.* It is costly, one unparseable declaration would fail unrelated operations, and the
  lenient `referencedEnrichments` would turn a parse failure into a silently passed delete.
- *Rejected: persisting the resolved read set at registration.* It works for armed pipelines, but drafts would still
  need the scan, so it is two code paths for little gain. It is a possible v2.

### D8. Storage and wire
- `sql` lives in `AggregateSpec`, inside the existing `spec` JSONB (`ignoreUnknown`). There is no migration and no
  Envers change, and `filter_sql` stays null.
- `target` stays required (`NOT NULL UNIQUE` FK) and must equal the INSERT target. `input_table` is derived from the
  parse.
- PATCH replaces the declaration and switches mode explicitly (see the pipeline-registry delta).
- `CatalogBundle.FORMAT_VERSION` is bumped, because the bundle parser binds strictly and an older ADAS must give the
  readable newer-format 422.

### D9. Validator and compiler are new classes over shared helpers
`AggregateSqlValidator` and `AggregateSqlCompiler` sit next to the form classes. The String/`EntityFields`-typed
helpers are extracted from `AggregateSpecValidator`:
- input and target checks
- enrichment aggregatability
- the sensitive gate
- grant widening
- sensitivity propagation

The type checks are rewritten over `RelationColumn` with the same rules.
- *Rejected: translating SQL into a form `AggregateSpec`.* Expression measures and expression keys have no form
  representation.

## Risks / Trade-offs

- [The token scan gives spurious 409s, e.g. a column name in a string literal or a comment] → Accepted and
  documented. Only refusals can be wrong, never a silent pass.
- [Single block may be too narrow for future queries] → Validated against all existing aggregate pipelines. A rollup of a rollup
  covers the nested cases, and v2 can add substitution through derived blocks.
- [Probe and refresh cost scale with the WHERE's selectivity] → Same as `filter_sql`. Size rebuilds with the rollup
  cost formula over the WHERE-matching subset.
- [Parse limits] → Documented in the `@Schema` authoring rules:
  - no backticks (lexical error)
  - `SETTINGS` gives a parse error
  - `; -- comment` fails in the existing `plan()`
  - `count()` without `*`, `countIf` and `FILTER (WHERE …)` are unsupported; use `CASE WHEN` inside the aggregate
- [Refresh runs as `SystemIdentity` and sees more columns than the declarer] → The full gate re-runs on every
  refresh. The declarer is checked at their own bar, and a sensitive-hidden column surfaces as a 403.

## Migration Plan

Additive. Existing form pipelines are unchanged. No migration and no config change. Older bundles still import, while
a newer bundle on an older ADAS gets the format 422. The `adas-mcp` OpenAPI snapshot is refreshed in that repo after
release.

## Open Questions

None that block. Possible v2 items:
- persisting the resolved read set
- expression substitution through derived blocks
- a stepped `bucketBound`
