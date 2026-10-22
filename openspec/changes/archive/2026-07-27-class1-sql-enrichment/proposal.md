## Why

Every enrichment ADAS can express today is **Class-2**: a rule sends a row to an LLM and writes back
a label. A large share of the enrichment demand is **Class-1** — deterministic derivation that needs
no model at all, most importantly pulling structured fields out of the stored `request_body` /
`response_body` JSON. Routing that through the Class-2 path is wrong three times over: it pays for a
model call per row to compute something SQL computes for free, it drags megabytes of **sensitive**
request/response bodies over HTTP to the runner and back, and it inherits a per-row retry/DLQ
machinery built for expensive non-idempotent calls that a set-based `INSERT … SELECT` does not have.

The registry is already shaped for this — `evaluator_version` carries a `type`, and rules already bind
an evaluator's output variables to target-enrichment columns — but `EvaluatorType` has exactly one
constant (`LLM`) and there is no execution path that computes in the database.

There is also a gap the plan for this work did not anticipate. The governance front end
(`FilterSqlValidator` → `SqlQueryFrontEnd`) does not merely parse SQL: it lowers it into ADAS's closed
query DSL, whose entire scalar surface is `lower`, `upper`, `length`, `trim`, `abs`, `date_bin`,
`width_bucket`, plus `CAST`, column references, and literals. **There is no JSON extraction anywhere
in it**, so the motivating Class-1 use case is not expressible under the grammar it is supposed to be
validated by. Adding JSON extraction to that shared catalog is therefore part of this change rather
than a follow-up.

## What Changes

- **JSON extraction in the shared query function catalog.** Four scalar functions —
  `json_extract_string`, `json_extract_long`, `json_extract_double`, `json_extract_bool` — taking a
  JSON-valued string expression plus one or more string-literal path keys. They are added to the one
  closed catalog that already backs the structured-query DSL, `execute-sql`, the translate pair, rule
  filters, and now Class-1 expressions, so all of them gain the capability together and no second
  grammar is introduced. `GET /v1/queries/functions` advertises them.
- **`type=sql` evaluators.** `EvaluatorType` gains `SQL`; `EvaluatorVar` gains a `sql` field holding a
  scalar expression, the `type=SQL` sibling of the existing `jsonata`. A `SQL` evaluator carries **no
  model, no preset, no request template, no input variables, no response schema** — `model` and
  `preset` become nullable and are required only for `LLM`. Immutable versioning is unchanged: editing
  an expression mints a new `evaluator_version`, which is what makes re-running a backfill meaningful.
- **Class-1 governance (`ExprSqlValidator`).** The scalar sibling of `FilterSqlValidator`: each
  expression is wrapped as `SELECT <expr> FROM <source>` through the same Calcite front end, so
  bounded grammar and unknown-column rejection come free. On top it rejects aggregate expressions
  (which would silently break the 1:0..1 grain), rejects array-typed results, and checks the inferred
  result type against the output variable's declared type. Referenced columns run the **existing**
  sensitive-column entitlement check. A `type=sql` rule must be row-grain (`on_ingest` or `schedule`),
  must declare no input bindings, and may not use the `group` trigger.
- **Set-based materialize endpoint.** `POST /v1/rules/{id}/materialize` with body `{from?, to?, limit?}`
  → `{rowsWritten, nextCursor, hasMore, lagSeconds}`, authorized by the same target-enrichment grant
  as the write-back. ADAS **probes the window before it writes it** — a cheap two-column keyset query
  picks the window end, then one `INSERT … SELECT` writes exactly that window. No row payloads cross
  the wire in either direction; bodies never leave ClickHouse.
- **Sensitive-data posture recorded, not enforced by new code.** The runner runs as `FULL_ADMIN` for
  any rule touching a sensitive column, superseding the original least-privilege stance. Derived
  columns are **not** automatically marked sensitive and such a rule is **not** rejected — the
  laundering path and its mitigation (declare the target column `sensitive` via the table schema API)
  are recorded so the choice is explicit rather than emergent.

Out of scope (locked): no new invoker presets; no aggregation (an aggregate expression is rejected by
construction); no ADAS-managed refreshable materialized views (the deferred latency/scale follow-up);
no per-row dead-lettering on this path; no seeded rules or system tables — the worked example is a
spec example and a test fixture, not a migration seed.

## Capabilities

### New Capabilities

- `class1-enrichment`: deterministic, model-free enrichment computed set-based inside ClickHouse from
  a governed scalar expression over the source's declared columns, driven window-by-window by the
  runner through a probe-then-write materialize endpoint.

### Modified Capabilities

- `enrichment-registry`: evaluators gain `type=sql` with nullable `model`/`preset` and per-output-var
  `sql` expressions; the static governance gate becomes type-dispatched; the runner-identity and
  derived-column sensitivity posture is recorded.
- `structured-query`: four JSON extraction functions added to the closed function catalog.
- `query-schema-discovery`: those functions are advertised by `GET /v1/queries/functions`.

## Impact

**New code:** `service/enrichment/validation/ExprSqlValidator`, a shared `ExprColumnCollector`
extracted from `FilterSqlValidator`, `service/enrichment/MaterializeService`,
`service/enrichment/support/SensitiveProjectionGuard` (extracted from `ScanService`),
`web/dto/enrichment/Materialize{Request,Response}`, `configuration/MaterializeProperties`, and a
write-side ClickHouse statement executor on the existing writer credential.

**Migration:** Postgres `V14__class1_sql_evaluators.sql` — relax the `evaluator_version.type` CHECK
to `('LLM','SQL')`, make `model` and `preset` nullable, and add CHECKs tying each to `type = 'LLM'`.
No ClickHouse DDL.

**Reuses:** `SqlQueryFrontEnd` and its bounded grammar, `ExprTranslator.toField` for rendering,
`EnrichmentTypeMapping` for type compatibility, `RuleAuthorizer` for authorization,
`ProvenanceColumns` + `TableDataService.physicalColumns` for skip-what-the-target-lacks provenance,
the scan's cursor codec, safety-lag, and `{from?, to?}` window shape.

**Config:** `enrichment.materialize.default-limit` / `max-limit`, documented in `docs/configuration.md`.

**DDL/credentials:** the materialize `INSERT … SELECT` runs on the writer credential, which therefore
needs `SELECT` on scan sources in addition to `INSERT` — recorded in `openspec/project.md`.

**Cross-change dependency:** depends on `enrichment-scan-generalization` for the `{from?, to?}` window
vocabulary and the catalog-configured cursor columns.
