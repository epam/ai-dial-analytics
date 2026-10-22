## Context

`EnrichmentJoins.keyed()` renders a grain join as a derived table: the enrichment restricted to the grain
keys (and, since `bound-and-prune-pipeline-reads`, the partition values) the source predicates select,
projecting the grain key plus `binding.field()` for every referenced exposed name. `binding.field()` for an
`object` column is the whole native `JSON` column.

`ExprTranslator` already renders a *single-path* read of an `object` field as native subcolumn access
(`subcolumnExtract`, line ~1402: the binding's field with the literal path appended as `DSL.name` segments,
wrapped in `toString`), and falls back to the serialized text form (`documentSource` → `toJSONString`) for
`json_extract_string_ci` and `json_extract_array`, which have no statically known subcolumn to address.
Its own javadoc measures the difference at ~20x.

The two never meet. `keyed()` materializes the whole column before the translated expression runs, so the
subcolumn rendering only pays off where the join is unkeyed. In practice this is the difference between a filter
that returns instantly (unkeyed preview) and one that cannot complete a four-minute window (keyed probe) —
same expression, same column, same rows.

Separately, `pipeline.budget.*` covers six reads and deliberately exempts the Class-1 materialize write.
Because each statement emits the grain join only for the enrichments *its own clauses* reference, a
pipeline that declares a `filter` naming an enrichment is bounded at its probe, and one that declares no
filter is not bounded at all — its whole enrichment read happens inside the exempt write. When a
ClickHouse node is saturated, the largest single consumer can be exactly that: an
`INSERT INTO usage_client_identity` taking hundreds of MiB for tens of seconds, while the node sits pinned
at its server memory limit shedding queries.

## Goals / Non-Goals

**Goals:**

- A keyed grain join is never more expensive than the same join unkeyed.
- Whether a pipeline's enrichment read is bounded does not depend on whether it declares a `filter`.
- An author choosing the paths × parts form of a JSON read is told so at registration.
- `usage-request-baggage-live` runs again with its current (case-sensitive) declaration.

**Non-Goals:**

- Making `json_extract_*_ci` cheap. It has no statically known subcolumn by construction; it keeps the
  whole-column projection and is bounded by the budget like anything else.
- Re-keying or re-partitioning `dial_usage_log_payload`.
- Changing `pipeline.budget.*` defaults, or adding any config key. The ceilings stay where they are.
- The Enrichment Runner. Tracked separately.

## Decisions

### D1 — The derived table projects addressed paths, aliased to the dotted path

**Decision.** Thread a per-statement *object-path usage* map — exposed column name → either a set of
literal paths or `WHOLE_COLUMN` — from the expression analysis into `EnrichmentJoins.addReferenced`.
`keyed()` consults it per column: for a path set it projects one field per path, each aliased to the
**dotted path rendered as a single quoted identifier** (`"request_tags.baggage"`); for `WHOLE_COLUMN` it
projects `binding.field()` exactly as today. `ExprTranslator.subcolumnExtract` addresses that same alias
when the column was pruned, via one shared helper that builds the alias from (column, path) so both sides
cannot disagree.

**Why the dotted-path alias.** The outer statement currently renders
`toString("dial_usage_log_payload"."request_tags"."baggage")` — a three-part reference into the derived
table. Once the derived table projects a subcolumn, that reference has nothing to resolve against, because
`"request_tags"` is no longer a column of it. Aliasing to the single identifier `"request_tags.baggage"`
keeps the reference two-part and unambiguous, needs no name mangling, and stays readable in a logged
statement. jOOQ quotes it as one name, so the dot is inert.

**Alternatives rejected.**
- *Push the whole translated expression into the derived table.* The derived table is built from referenced
  **names**, not expressions, and one column is often addressed by several different expressions in filter
  and outputs; the projection would have to be keyed by expression identity and deduplicated. More coupling
  for no additional pruning.
- *Reconstruct a pruned JSON object in the derived table* so the outer path reference keeps working
  untouched. ClickHouse offers no cheap sub-object projection of the `JSON` type, and rebuilding one per
  row reintroduces the serialization this change exists to avoid.
- *Let `EnrichmentJoins` parse the expressions itself.* It would duplicate `pathKey`'s literal-only,
  backslash-rejecting validation, which is exactly the rule that guarantees a path renders as one
  identifier segment. One owner for that rule.

**Fallback is per column, not per statement.** A statement mixing a literal-path read of one `object`
column with a `json_extract_array` over another prunes the first and not the second. Making the fallback
statement-wide would be simpler and would silently lose the pruning in the mixed case, which is the common
one as declarations grow.

### D2 — Budget the write too, and let it narrow

**Decision.** Option (a), amended: the materialize `INSERT … SELECT` carries `pipeline.budget.*` like every
other statement, and a write refused by the ceiling **halves its window and retries**, down to a floor of
one source row, reporting the narrowed window through the cursor and `hasMore` it already returns. Only a
refusal at that floor surfaces as `422 pipeline_read_budget_exceeded`.

**Why.** The existing exemption rests on "its cost is bounded by the window its probe already fixed". That
holds only if probe and write read alike, and they demonstrably do not — the per-statement join rule makes
the unfiltered pipeline's write the one unbounded statement in the system, and it can be the top consumer
on a saturated node. Bounding it needs an escape hatch, because a legitimately large window
genuinely can exceed any ceiling and the runner cannot narrow it (ADAS picks the window). `hasMore` is
already the vocabulary for "I wrote a prefix, call again", so narrowing costs no new contract and strands
nothing. The narrowed window is a prefix of the probed one, so the exactly-once-effect guarantee and the
cursor ordering survive untouched.

**Alternatives rejected.**
- *(b) Exempt a probe whose write would carry the same read unbudgeted.* Perverse: it removes the ceiling
  precisely when the probe is expensive, and makes "is this statement bounded?" depend on a property of a
  different statement.
- *(c) Stop the probe joining for its filter; over-shoot and let the write filter.* Genuinely cheaper, and
  tempting. Rejected because `hasMore` is specified as "the probe filled its limit" — an over-shooting
  probe fills its limit on rows the filter excludes, so a filtered pipeline would report `hasMore: true`
  forever and its drained horizon, which `class1-enrichment` specifies at length as the thing that releases
  a filtered pipeline's dependents, would never be recorded. That is a large semantic change to buy a
  saving D1 already delivers for the single-path case.
- *Raise the ceiling.* Moves the number, keeps the asymmetry, and the failure it prevents is a shared-node
  outage rather than one pipeline's.

### D3 — Report `json_extract_*_ci` over an `object` column; never refuse it

**Decision.** Registration and patch accept the declaration and report the choice back — that the
expression reads the whole document, one stream per path per part, and that `json_extract_string` is the
single-path alternative where one exists.

**Why not refuse.** Affordability is a property of the table's physical layout, not of the declaration: the
same expression is cheap on a compacted table and ruinous on a fragmented one, and the layout moves under
the declaration without the declaration changing. A static refusal would reject affordable uses and still
admit unaffordable ones. It would also strand shipped declarations — including a system pipeline — behind a
gate they cannot pass, since evaluator versions are immutable and a patch would have to clear the same
gate. The budget is the enforcement point; registration's job is to make sure nobody picks the expensive
form unaware. This is a real trap and not a theoretical one: the `_ci` and case-sensitive forms can select
byte-identical row sets while only one of them is able to run.

## Risks / Trade-offs

- **The alias couples two classes.** `EnrichmentJoins` and `ExprTranslator` must build the same alias from
  the same (column, path) pair, and a divergence renders SQL naming a column that does not exist →
  *Mitigation*: one shared helper owning the alias, and a render test asserting the emitted derived table's
  aliases against the outer statement's references for a keyed single-path read.
- **Pruning changes what a LEFT JOIN yields for an absent path.** A projected subcolumn of a missing path
  and a `toString` over the whole column's missing path must agree → *Mitigation*: both go through
  `subcolumnExtract`'s existing untyped-plus-`toString` rendering, which is already the shipped answer for
  the unkeyed case; a scenario asserts the pruned and unpruned forms return the same value for the same row.
- **Window halving can mask a pathological declaration** by making it crawl instead of fail →
  *Mitigation*: the floor of one row surfaces the refusal, and the narrowing is observable in the returned
  cursor and `hasMore`, so a pipeline advancing one row per call is visible rather than silent.
- **Budgeting the write could refuse a large legitimate backfill** that previously succeeded →
  *Mitigation*: that is what the narrowing exists for; the call still writes, just in more passes.
- **Re-derivation is not free.** `usage-request-baggage-live` re-derives from the start of history
  on its first materialize after the generation bump already applied → *Mitigation*: the
  unbounded read through the cheap path returns tens of thousands of rows instantly, and the write is
  idempotent by grain.

## Migration Plan

No migration, no new config key, no credential. Deploy is a rolling restart; the statements ADAS renders
change shape, and nothing persisted changes. Rollback is the previous image — the pruned projection is a
render-time decision with no stored artefact, and the mitigating pipeline declarations (the case-sensitive
evaluator version and filter) are valid against both builds.

Order: deploy, then re-enable `usage-request-baggage-live`, then confirm it drains.

## Open Questions

- ~~Should the registration report surface as a response body member, an `activities` entry, or both?~~
  **Settled: a response body member, `notes`, on the write responses only** (`POST /v1/pipelines`,
  `PATCH /v1/pipelines/{name}`), plus an `INFO` log line. The codebase already returns non-fatal
  information from a control-plane call exactly this way and in two places —
  `PipelineResponses.EnrichPreviewDto.notes` ("what a reader should know about why this response looks
  like it does") and `SqlValidationResponseDto.warnings` ("heuristics, never verdicts") — so this costs
  one member spelled the way the surface already spells it. The `activities` feed was rejected because it
  is Envers-driven: every entry there is a persisted entity mutation, so an advisory would be the first
  thing in it that is neither a stored fact nor reconstructable from one, and it would need its own
  resource type, its own mapper case and a `*_aud` story — new vocabulary for a reader who is not the
  author being warned. "Both" buys nothing the log line does not. Between the two body precedents,
  `List<String>` was taken over `SqlIssueDto`'s `{code, message, remedy}`: the issue shape carries Calcite
  line/column positions that a stored declaration's fragment cannot produce usefully, and the pipeline
  surface's own precedent is the plain list.

  Served on the **write** responses only, and absent (not `[]`) elsewhere: the note answers "what did I
  just declare", which is a question about an act rather than about stored state, and deriving it costs one
  SQL front-end translation per governed fragment — which a listing would pay per entry for an answer that
  has not changed since it was reported.
- Does the aggregate refresh probe reach `keyed()` with an `object` column in practice? If so it gets D1
  for free and needs no separate task; if not, the task list should say so rather than leave it implied.
