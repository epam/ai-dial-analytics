## Context

The enrichment registry models an evaluator as `{type, preset, model, request_template, input_vars,
response_schema, output_vars}` and a rule as a binding of that evaluator's output variables to columns
of a target enrichment. Everything about the shape already generalizes to a non-LLM evaluator — the
only things that are LLM-specific are `model`/`preset`/`request_template`/`response_schema` and the
JSONata that maps a model response onto output variables. Class-1 replaces the middle of that pipeline
(*where the value is computed*) and leaves the registry, the grain model, the authorization model, the
cursor, and the write target untouched.

Two facts discovered while designing this shape the whole change:

1. **The governance front end is a DSL lowering, not a SQL parser.** `SqlQueryFrontEnd.translate`
   parses with Calcite, validates against the catalog schema, converts to a `RelNode`, and then
   `RelToStructuredQueryTranslator` lowers that plan into ADAS's `StructuredQuery` model. Anything the
   DSL cannot represent fails closed. The scalar surface is seven functions plus `CAST`, columns, and
   literals — there is no JSON extraction, no string concatenation, no arithmetic, and no `CASE`.
2. **`SqlTranslation` already carries an inferred result type.** `translate` returns
   `List<ResultColumn>` with a `ResultColumnKind` per projected column, derived by
   `RelToStructuredQueryTranslator.fnKind`/`fieldKind` from ClickHouse runtime semantics. The plan
   listed "can Calcite infer the expression's type, or must the author declare it?" as an open
   question — it can, and the author still declares one, so the two are cross-checked.

## Goals / Non-Goals

**Goals:**

- Express a deterministic, row-grain derivation as a rule, with the same registry, grain,
  authorization, cursor, and provenance as a Class-2 rule.
- Keep the data in the database: no row payloads cross the wire, so sensitive bodies never leave
  ClickHouse and the work is set-based.
- Give an interrupted or retried materialize call an identical window, so its effect is exactly-once
  under the enrichment `ReplacingMergeTree`.
- Make body extraction actually expressible under the bounded grammar it is validated by.

**Non-Goals:**

- Aggregation. A group-grain deterministic rule is an aggregation and is rejected by construction.
- Insert-time latency. Class-1 latency is one runner cadence interval, not insert-time.
- Per-row dead-lettering. A malformed body yields the expression's type default.
- A general SQL surface. Class-1 authors write DSL expressions, not arbitrary ClickHouse.
- New invoker presets, cost/pricing, or multi-writer coalescing.

## Decisions

**D1 — Extend the shared function catalog rather than forking a Class-1 grammar.** JSON extraction is
added to the single closed catalog behind `ExprTranslator`, `RexToExprTranslator`,
`RelToStructuredQueryTranslator.fnKind`, `StructuredQueryToSqlRenderer`, and `QueryFunctionCatalog`.
Filters, `/v1/queries`, `execute-sql`, the translate pair, and Class-1 all gain it at once, and
Class-1 inherits unknown-column rejection, bounded grammar, and type inference for free.
*Alternative rejected:* a Class-1-only ClickHouse allowlist that bypasses the DSL — it unlocks more
functions faster but forks the grammar in two, reimplements column and type checking, and reintroduces
a raw-SQL surface the original design deliberately removed. *Also rejected:* shipping Class-1 on the
existing seven functions — honest to "reuse, don't fork", but it leaves the motivating use case
unbuildable.

**D2 — Four typed JSON functions, not one polymorphic `json_extract`.** The DSL's type inference is
static per function name (`fnKind`), and the enrichment write needs a concrete type to check against
the target column. `json_extract_string|long|double|bool` map to `JSONExtractString|Int|Float|Bool`
with fixed result kinds. Path arguments after the first are string literals, so nested extraction is
expressible without opening the grammar to computed paths. One rendering wrinkle found in testing:
`JSONExtractBool` yields ClickHouse `UInt8`, which reads back as `1`/`0`, so `json_extract_bool` renders
as `toBool(JSONExtractBool(…))` to make the advertised `boolean` return type true on the wire.
*Alternative rejected:* a single
`json_extract` returning a JSON-typed value plus separate casts — the DSL has no JSON value type and
`CAST` semantics over ClickHouse JSON strings are not something the type system can check.

**D3 — `coalesce` is not added.** Calcite expands `COALESCE` to `CASE` during rel conversion, and the
DSL has no `CASE` node, so it would fail closed with a message about an unrepresentable plan rather
than an unsupported function. Adding it properly means adding a conditional node to the DSL, which is
a change to the query language, not to enrichment. Deferred and recorded.

**D4 — Expressions are validated at rule registration, not at evaluator registration.** An evaluator
does not know its source; the rule's target enrichment does (`table_definition.source_table`). Evaluator
registration therefore performs shape checks only (a `SQL` evaluator has no model/preset/template and
every output var carries a non-blank `sql`), and `RuleGovernanceValidator` runs `ExprSqlValidator`
against the resolved source. This also means the same evaluator cannot be silently reused against a
source lacking the referenced columns — the second rule fails its own gate. *Alternative rejected:*
validating at evaluator registration against a caller-supplied source — it duplicates the rule's
source resolution and lets the two disagree.

**D5 — Aggregate and non-deterministic expressions are rejected by the existing machinery, not by new
checks.** `SELECT sum(x) FROM t` lowers to `QueryMode.AGGREGATE`, so asserting `mode == ROW` is the
whole aggregate check. `now()`, `rand()`, and `generateUUIDv4()` are not in the closed catalog, so
`fnKind` already rejects them with "function 'x' is not in the query function catalog". A hand-written
denylist would be dead code that silently rots as the catalog grows. The spec states *why* the
guarantee holds rather than pretending a check enforces it. *Alternative rejected:* an explicit
denylist — it implies the catalog is open, which it is not.

**D6 — The inferred type is checked against the declared output-var type; the existing binding check
then covers the target column.** `ExprSqlValidator` maps `ResultColumnKind` → `QueryFieldType` and
compares it to `EvaluatorVar.type` through `EnrichmentTypeMapping.isCompatible`; `validateOutputBindings`
already compares `EvaluatorVar.type` to the bound column's type. Chaining the two existing checks
avoids a third notion of type compatibility. Array results are rejected outright — an enrichment
column bound to a `1:0..1` grain has no array write path here. *Alternative rejected:* checking the
inferred type straight against the column and dropping the declared type — the declared type is what
the runner and the sync feed see, so it must be true.

**D7 — Probe-then-write, with the window chosen before it is executed.** The materialize call runs two
statements: a two-column keyset probe that picks `windowEnd = (version, identity)` of the last row it
would touch, then one `INSERT … SELECT` bounded by `(cursorFrom, windowEnd]`. Because the window is
decided *before* the write, a call that times out, fails, or is retried re-executes an identical
window; a single ClickHouse `INSERT` is atomic and the enrichment `ReplacingMergeTree` collapses by
grain, so a repeated window is a no-op in effect. This yields the same exactly-once *effect* as the row
path without returning rows to the caller to compute a cursor from. *Alternative rejected:* writing
first and deriving the cursor from what was written — `INSERT … SELECT` does not report which rows it
read, so the cursor would have to come from a third query over a window that may have moved.

**D8 — ADAS executes the SQL; the runner supplies only cadence, window, and cursor bookkeeping.** The
runner never sends SQL, never learns the source table, and never sees a row. *Alternative rejected:* an
ADAS-managed refreshable materialized view per rule — cheapest at steady state, but ADAS grows a
per-rule DDL lifecycle and Class-1 falls outside the runner's cadence/backfill/run-history machinery;
deferred as the latency/scale follow-up. *Also rejected:* a runner row-loop with a local transform — it
pulls sensitive bodies over HTTP twice per row, which is the opposite of the point.

**D9 — Materialize is authorized by the target-enrichment WRITE grant, like `/rows`.** It writes, so it
takes the write flavour; `RuleAuthorizer.authorize` is reused unchanged. The sensitive-column
entitlement is then re-checked against the **calling** identity over the expression's and filter's
referenced columns, exactly as the scan does at its egress — which is why a body-extracting Class-1
rule requires a `FULL_ADMIN` runner even though no body ever reaches it. *Alternative rejected:*
skipping the sensitive re-check because no rows are returned — the check is about who may cause
sensitive data to be read and derived, not only about who may see it.

**D10 — Derived-column sensitivity is not propagated and not validated.** A rule whose expression reads
a `sensitive` source column and writes a non-`sensitive` target column is accepted as registered. Rule
registration must not mutate catalog metadata as a side effect — a caller creating a rule would
otherwise silently change which callers can read an existing column — and rejecting the combination
would force an authoring dance for derivations that are genuinely non-identifying (a body's byte
length, a boolean "had an error"). The laundering path is real and is recorded in the spec with its
mitigation: mark the target column `sensitive` when defining or patching the enrichment's schema.
*Alternative rejected:* auto-marking bound target columns sensitive with a per-binding opt-out — the
surprising-catalog-mutation cost outweighs the default-safety benefit here. *Also rejected:* rejecting
the combination unless explicitly acknowledged — same authoring cost, and it turns an existing valid
rule invalid on re-validation.

**D11 — The runner runs as `FULL_ADMIN` for any sensitive-touching rule.** `SensitiveColumnAccess`
is role-based with no narrower grant available, and both the registration check and the read-egress
check consult it. This supersedes the original least-privilege stance ("per-target grants only, no
global admin") for those rules; non-sensitive rules are unaffected. What still bounds the runner is
that the surface stays rule-scoped in both directions — the scan projects only rule-referenced
columns, and the write-back rejects anything outside the rule's declared outputs. A narrower per-rule
or service-identity sensitive grant is the named follow-up.

**D12 — No dead-letter path, and a malformed body yields the expression's type default.** The DLQ
exists for expensive, non-idempotent, per-row failures; a deterministic set-based statement has none.
Any non-2xx fails the whole call, leaves the cursor unmoved, and is retried with an identical window.
The cost is that a body which fails to parse silently produces `''`/`0`/`false` rather than an error.
Accepted and stated in the spec: Class-1 authors must write total expressions, and the operational
signal is a per-column null/default rate.

## Risks / Trade-offs

- **A Class-1 expression silently yields type defaults on bad input** → accepted and documented;
  governance rejects the non-total constructs it can detect; monitor per-column default rate.
- **A long `INSERT … SELECT` ties up an ADAS request thread** → the probed window is `limit`-bounded
  and sits below the safety-lag; the window *is* the throttle, and the runner drains many small
  windows rather than one large one. `max-limit` caps the worst case.
- **Adding JSON functions widens the public query surface** → they are read-only scalar functions over
  columns the caller can already select, and sensitive columns remain hidden from non-entitled callers
  by `CatalogQueryService`, so the functions grant no new data reach.
- **The writer credential now needs `SELECT` on scan sources** → a real widening of that credential;
  recorded in `openspec/project.md` so deployments grant it deliberately.
- **Derived columns can launder sensitive data into a non-sensitive column** (D10) → accepted with the
  mitigation recorded; the enrichment author owns the column's `sensitive` flag.
- **`FULL_ADMIN` runner widens blast radius** (D11) → the rule-scoped surface still bounds what it can
  read and write; narrower grant named as the follow-up.

## Migration Plan

1. Add the four JSON functions across the five query-layer classes plus `QueryFunctionCatalog`, and
   extend the structured-query spec's function table. `QueryFunctionCatalogParityTest` enforces that
   every advertised descriptor is accepted by the translator.
2. `V14__class1_sql_evaluators.sql` relaxes the `type` CHECK and makes `model`/`preset` nullable with
   CHECKs tying them to `type = 'LLM'`. Existing rows are all `LLM` with both populated, so the new
   CHECKs hold on legacy data without a backfill.
3. Registry, then governance, then the materialize endpoint — each independently testable.
4. **Rollback:** the migration only widens constraints, so the prior code runs unchanged against the
   new schema as long as no `SQL` evaluator has been registered. Reverting after one exists requires
   deleting those rows first.

## Open Questions

- The default materialize window `limit` and the runner's cadence are a tuning pair; the defaults here
  are a starting point to be measured against real body sizes.
- Whether `CASE`/conditional support should be added to the DSL (which would also unlock `coalesce`,
  D3) is a query-language decision worth taking on its own merits rather than as Class-1 fallout.
