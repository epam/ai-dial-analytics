## ADDED Requirements

### Requirement: SQL-computed evaluators

The system SHALL support evaluators of `type: "sql"` — deterministic, model-free derivations computed
inside ClickHouse. For a `sql` evaluator the system SHALL require `model`, `preset`,
`request_template`, `input_vars`, and `response_schema` to be **absent**, and SHALL require every
entry of `output_vars` to carry a non-blank `sql` expression and a logical `type`. For an `llm`
evaluator `model` and `preset` SHALL remain required, unchanged from the original registry. A `sql` evaluator's
`output_vars` SHALL NOT carry `jsonata` (there is no model response to map).

Evaluator versioning is unchanged: a `sql` evaluator version is immutable, and editing an expression
mints a new `evaluator_version`, which is what makes re-running a backfill over history meaningful.

Expressions SHALL NOT be validated at evaluator registration, because an evaluator does not name a
source; they are validated at **rule** registration against the source derived from the rule's target
enrichment.

#### Scenario: A sql evaluator registers without model, preset, or template

- **WHEN** `POST /v1/evaluators` is called with `type: "sql"`, no `model`/`preset`/`request_template`/
  `response_schema`, and output variables each carrying a `sql` expression and a `type`
- **THEN** the evaluator version is created and HTTP 201 is returned

#### Scenario: A sql evaluator carrying LLM-only fields is rejected

- **WHEN** `POST /v1/evaluators` is called with `type: "sql"` together with any of `model`, `preset`,
  `request_template`, `input_vars`, or `response_schema`
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: A sql evaluator with an output variable lacking an expression is rejected

- **WHEN** `POST /v1/evaluators` is called with `type: "sql"` and an output variable whose `sql` is
  blank or absent
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: An llm evaluator still requires model and preset

- **WHEN** `POST /v1/evaluators` is called with `type: "llm"` and a missing `model` or `preset`
- **THEN** the request is rejected and the existing behaviour is unchanged

### Requirement: Class-1 expression governance

The system SHALL statically validate every `sql` output-variable expression at rule register and
enable, against the source derived from the rule's target enrichment. Each failure SHALL be rejected
with HTTP 422 `rule_validation_failed` carrying the failing detail. Validation SHALL wrap the
expression as `SELECT <expr> FROM <source>` through the **same** Calcite SQL front end used for rule
filters, so the bounded grammar (no joins, subqueries, or CTEs), the closed function catalog, and
unknown-column rejection apply identically. On top of that the gate SHALL enforce:

1. **Scalar, not aggregate.** An expression that resolves to an aggregate or window computation SHALL
   be rejected — an aggregating expression is an *aggregation*, not a Class-1 enrichment, and would
   silently break the target's `1:0..1` grain.
2. **A single projected value.** The expression SHALL yield exactly one column.
3. **Type-compatible.** The result type inferred by the front end SHALL be compatible with the output
   variable's declared `type` under the shared enrichment type mapping, and the declared type SHALL in
   turn be compatible with the bound target column's type (the existing output-binding check). An
   array-typed result SHALL be rejected.
4. **Entitled.** The expression's referenced source columns SHALL run the same sensitive-column
   entitlement check as a rule `filter`: a `sensitive` column is admitted only for a caller entitled to
   see sensitive columns, and rejected with HTTP 403 `sensitive_column_not_entitled` otherwise.
5. **Row-grain trigger only.** A `type=sql` rule SHALL declare `on_ingest` or `schedule`; the `group`
   trigger SHALL be rejected, because a group-grain deterministic rule is an aggregation.
6. **No input bindings.** A `type=sql` rule SHALL declare no input bindings — there is no request to
   render.

Determinism is guaranteed by construction rather than by a denylist: non-deterministic functions such
as `now()`, `rand()`, and `generateUUIDv4()` are absent from the closed function catalog and are
therefore already rejected as unsupported functions. This matters because the exactly-once *effect*
guarantee below depends on re-running a window producing the same values.

#### Scenario: An aggregate expression is rejected

- **WHEN** a `type=sql` rule declares an output-variable expression containing an aggregate (e.g.
  `sum(total_tokens)`)
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: An unknown column in an expression is rejected

- **WHEN** an expression references a column not declared on the rule's source
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: A non-catalog function in an expression is rejected

- **WHEN** an expression calls a function outside the closed query function catalog (including any
  non-deterministic function such as `now()`)
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: A type-incompatible expression is rejected

- **WHEN** an expression's inferred result type is incompatible with the output variable's declared
  type, or the result is array-typed
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: A sensitive reference by an entitled author is accepted

- **WHEN** a `FULL_ADMIN` caller (or any caller in `none` mode) registers a `type=sql` rule whose
  expression reads a `sensitive` source column such as `request_body`
- **THEN** the rule is accepted

#### Scenario: A sensitive reference by a non-entitled author is rejected

- **WHEN** a caller who may not see sensitive columns registers the same rule
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`

#### Scenario: A group trigger or input bindings on a sql rule are rejected

- **WHEN** a `type=sql` rule declares `trigger_kind: "group"`, or declares any input binding
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

### Requirement: Set-based materialize endpoint

The system SHALL expose `POST /v1/rules/{id}/materialize` accepting a body
`{cursor?, limit?, from?, to?}` and returning `{rowsWritten, nextCursor, hasMore, lagSeconds}`. The
`cursor` is the caller-held opaque token from a prior window, with the same encoding, verification, and
statelessness as the scan's: ADAS signs and decodes it but holds no per-caller position, so the caller
persists it and advances **only on a successful response**. It SHALL resolve the rule's source,
filter, grain, expressions, and target enrichment from the stored rule; the caller SHALL NOT supply
SQL, a table name, a filter, or a column list, and **no row payloads SHALL cross the wire in either
direction** — the source data, including any sensitive body column, never leaves ClickHouse.

The endpoint SHALL be authorized by the rule's **target-enrichment access, write flavour** — the same
grant as `POST /v1/rules/{id}/rows`, because it writes. A caller lacking it SHALL be rejected with
HTTP 403 `rule_access_denied`. Independently of that grant, the sensitive-column rule SHALL be
re-applied against the **calling** identity over the columns the rule's expressions and filter
reference, and a non-entitled caller SHALL be rejected with HTTP 403
`sensitive_column_not_entitled` — so a non-`FULL_ADMIN` runner cannot cause sensitive data to be read
and derived even though no body is returned to it.

Because a `sensitive` column is *hidden* from a non-entitled caller's schema view, such a caller cannot
parse an expression that reads one: validation would fail first with an unknown-column error. The system
SHALL NOT surface that as a 422 "invalid expression"; when a governed-expression validation fails for a
caller who may not read the source's sensitive columns, it SHALL be reported as HTTP 403
`sensitive_column_not_entitled` instead. An entitled caller's validation failure SHALL be reported
accurately, so a genuinely broken rule (e.g. a source column dropped since registration) still reaches
the caller who can act on it.

`from` and `to` SHALL carry the same shape and `(from, to]` semantics as the scan's bounded window
(see [enrichment-runtime](../enrichment-runtime/spec.md)); `limit` SHALL bound the window's row count,
with a configured default when omitted and a configured maximum above which the request is rejected.
An unknown rule SHALL return HTTP 404 `unknown_rule`. Calling `materialize` on a `type=llm` rule, or
calling the row scan on a `type=sql` rule, SHALL be rejected with HTTP 422
`rule_validation_failed` — the two execution paths are not interchangeable.

#### Scenario: Materialize writes the window and reports the count

- **WHEN** `POST /v1/rules/{id}/materialize` is called for an enabled or paused `type=sql` rule with
  matching source rows below the safety-lag horizon
- **THEN** the derived values are written to the target enrichment and the response reports
  `rowsWritten`, an advanced `nextCursor`, `hasMore`, and `lagSeconds`

#### Scenario: Materialize returns no rows to the caller

- **WHEN** any materialize call succeeds
- **THEN** the response carries counts and cursor bookkeeping only, and never source or derived row
  contents

#### Scenario: Materialize on an LLM rule is rejected

- **WHEN** `POST /v1/rules/{id}/materialize` is called for a rule whose evaluator is `type=llm`
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: Scan on a SQL rule is rejected

- **WHEN** `POST /v1/rules/{id}/scan` is called for a rule whose evaluator is `type=sql`
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: A non-entitled caller cannot materialize a sensitive-reading rule

- **WHEN** a caller holding the target enrichment's grant but not entitled to see sensitive columns calls
  `materialize` for a rule whose expression reads a `sensitive` source column
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`, not with
  a 422 about an invalid expression

#### Scenario: An entitled caller may materialize the same rule

- **WHEN** a `FULL_ADMIN` caller (or any caller in `none` mode) calls the same `materialize`
- **THEN** the derivation runs normally

#### Scenario: A bounded window materializes only its range

- **WHEN** `materialize` is called with `from` and `to`
- **THEN** only source rows whose version is inside `(from, to]` contribute to the write

### Requirement: Probe-then-write window semantics

A materialize call SHALL choose its window **before** it executes it, in two statements. First a cheap
two-column probe SHALL select the source's `(version, identity)` tuples matching the rule's filter,
the cursor keyset, the safety-lag, and any `from`/`to` bound, ordered by that tuple and limited to
`limit`; the **last** returned tuple SHALL be the window end, and `hasMore` SHALL be true exactly when
the probe filled its limit. Second, a single `INSERT … SELECT` SHALL write the derived values for
source rows in `(cursorFrom, windowEnd]` only. Both statements SHALL read with `final=1`.

Because the window is fixed before the write, a call that times out, fails, or is retried SHALL
re-execute an **identical** window. A single ClickHouse `INSERT` is atomic and the target enrichment
is a `ReplacingMergeTree` collapsing by grain, so re-running a window SHALL be a no-op in effect: the
`final=1` result is unchanged. This is the exactly-once *effect* guarantee at window granularity, the
counterpart of the row path's per-grain guarantee.

An empty probe SHALL write nothing, return `rowsWritten: 0`, `hasMore: false`, and echo the input
cursor unchanged, so a caller that advances only on success never skips a window.

The write SHALL stamp the provenance columns the target physically carries — `evaluator_version` with
the rule's resolved pinned version and `enriched_at` with the write time — and SHALL skip `model`
(there is no model) and any provenance column the target lacks, matching the write-back path's
best-effort lineage. The version column `_updated_at` SHALL be stamped by ClickHouse, never by ADAS.

#### Scenario: Re-running an identical call leaves the result unchanged

- **WHEN** the same materialize call is issued twice against an unchanged source
- **THEN** the second call re-executes the same window and a subsequent `final=1` read returns the
  same values, with one row per grain key

#### Scenario: An interrupted call leaves the cursor unmoved

- **WHEN** a materialize call fails or times out
- **THEN** no cursor advance is reported to the caller, and retrying re-executes the same window

#### Scenario: An empty window echoes the cursor

- **WHEN** no source rows match above the cursor and below the safety-lag horizon
- **THEN** the response is `rowsWritten: 0`, `hasMore: false`, and `nextCursor` equal to the input cursor

#### Scenario: Provenance absent from the target is skipped, not failed

- **WHEN** the target enrichment does not physically carry `enriched_at` or `evaluator_version`
- **THEN** those columns are omitted from the write and the write still succeeds

### Requirement: Class-1 failure model

Class-1 SHALL have exactly one failure level — the call. Any failure SHALL fail the whole request,
leave the target unchanged for that window (the `INSERT` is atomic), and leave the caller's cursor
unmoved so the identical window is retried. There SHALL be **no per-row dead-letter path** on this
path: the dead-letter machinery exists for expensive, non-idempotent, per-row failures, which a
deterministic set-based statement does not have.

A source value that an expression cannot parse — a malformed or absent JSON body, for example — SHALL
yield the expression's type default (empty string, zero, false) rather than failing the row or the
call. This is a deliberate trade-off: Class-1 authors SHALL write total expressions, and the
operational signal for a broken expression is a per-column default/null rate, not an error. Class-1
latency is likewise one caller cadence interval, not insert-time.

#### Scenario: A failed call is atomic and retryable

- **WHEN** a materialize call fails partway
- **THEN** the target enrichment reflects either all or none of that window, and an identical retry
  is safe

#### Scenario: An unparseable source value yields the type default

- **WHEN** an expression extracts a field from a row whose body is malformed or missing the field
- **THEN** the row is written with the expression's type default and neither the row nor the call fails
