## MODIFIED Requirements

### Requirement: Rule CRUD binding an evaluator to a target enrichment

The system SHALL manage rules via `POST /v1/rules`, `PUT /v1/rules/{id}`, `DELETE /v1/rules/{id}`,
and `GET /v1/rules/{id}`. A rule SHALL bind an `evaluator_name` (+ optional `evaluator_version`,
null meaning `latest`) to a `target_enrichment`, plus `trigger_kind`, optional `trigger_cron`,
`group_by`, `ready_when`, `filter_sql`, `input_bindings`, `output_bindings`, and execution knobs
(`sampling`, `cadence`, `batch_scan_limit`, `batch_chunk`, `rate_rpm`, `priority`, `enabled`). The
rule's **scan source and grain SHALL be derived** from the target enrichment's `parent`/`grain` and
SHALL NOT be re-declared on the rule. Each mutating rule operation SHALL bump a `generation` counter
and `updated_at`. An unknown rule id SHALL be rejected with HTTP 404 `unknown_rule`.

`trigger_kind` SHALL be one of `on_ingest`, `schedule`, or `group`. A `group` rule fires once per
**group key** rather than once per row: it declares `group_by` (the group key) and `ready_when`
(`{signal?, idle?, max_staleness?, cost_ceiling?}`), the declaration of when a group is considered
complete enough to evaluate. A `group` rule SHALL NOT carry `trigger_cron` — its cadence is the
caller's readiness sweep, not a cron expression — which the existing cron-iff-`schedule` invariant
already enforces. A `group` rule writes exactly one enrichment row per group key through the existing
rule-scoped write-back, keyed by `group_by`, and the target enrichment's `ReplacingMergeTree` collapses
by that key.

#### Scenario: Create a rule derives its source and grain

- **WHEN** `POST /v1/rules` is called with a valid evaluator and `target_enrichment`
- **THEN** the rule is created with `source`/`grain` derived from the target enrichment, `enabled`
  per the request, `generation` initialized, and HTTP 201 returned

#### Scenario: Update bumps generation

- **WHEN** `PUT /v1/rules/{id}` changes a rule
- **THEN** the stored rule is updated, `generation` is incremented, and `updated_at` advances

#### Scenario: Delete removes the rule

- **WHEN** `DELETE /v1/rules/{id}` is called for an existing rule
- **THEN** the rule is removed and HTTP 204 returned

#### Scenario: Unknown rule is rejected

- **WHEN** any rule read/update/delete targets an id that does not exist
- **THEN** the request is rejected with HTTP 404 and error code `unknown_rule`

#### Scenario: Create a group rule

- **WHEN** `POST /v1/rules` is called with `trigger_kind: "group"`, a `group_by` equal to the target
  enrichment's grain key, and a `ready_when` carrying at least one readiness condition
- **THEN** the rule is created and HTTP 201 returned

### Requirement: Runner sync feed with resolved evaluator and change detection

The system SHALL expose `GET /v1/rules[?updated_since=…]` returning each **enabled** rule (the feed
is enabled-only; disabled rules are never returned) with its **resolved pinned evaluator inlined**
(the concrete version, resolving `latest` at sync time), the derived source/grain, and a
`generation` value the runner uses for change detection (surfaced as an ETag). `updated_since` SHALL
filter to rules changed at or after the given timestamp. For a `group` rule the feed SHALL additionally
inline `group_by` and `ready_when`, so a caller can route the rule to its group path and evaluate
readiness without a second round-trip.

#### Scenario: Sync returns enabled rules with inlined evaluators

- **WHEN** `GET /v1/rules` is called
- **THEN** each enabled rule is returned with its resolved evaluator version inlined, derived
  source/grain, and a `generation`

#### Scenario: Incremental sync filters by updated_since

- **WHEN** `GET /v1/rules?updated_since=<t>` is called
- **THEN** only enabled rules with `updated_at >= t` are returned

#### Scenario: Sync inlines the group declaration

- **WHEN** `GET /v1/rules` is called and an enabled rule has `trigger_kind: "group"`
- **THEN** that rule's `group_by` and `ready_when` are inlined alongside its resolved evaluator

### Requirement: Static governance gate on register and enable

The system SHALL statically validate a rule on register and on enable; each failure SHALL be
rejected with HTTP 422 `rule_validation_failed` (except entitlement and immutability, which use
their own codes) with detail identifying the failing check. The gate SHALL enforce: (1) the
`target_enrichment` exists, is `ACTIVE`, and is kind=enrichment, and every output-binding column
exists on it with a type matching the output-var type via the shared JSON→ClickHouse type-mapping
util; (2) `filter_sql` parses under the existing Calcite SQL front-end, is boolean over declared
source columns, uses the bounded grammar (no joins/subqueries/CTEs), with referenced columns
extracted; (3) the evaluator resolves; (4) `trigger_kind ∈ {on_ingest, schedule, group}` and the
trigger's declaration is internally consistent; (5) the evaluator-type specific checks; (6) the
group-trigger checks below.

The gate SHALL be **dispatched on the resolved evaluator's `type`**:

- **`llm`** — the resolved evaluator SHALL carry a non-blank `model`; all input/output JSONata
  expressions SHALL parse (static parse only — runtime evaluation is the runner's).
- **`sql`** — `model` SHALL NOT be required and JSONata SHALL NOT be parsed (there is none); every
  output variable's `sql` expression SHALL pass Class-1 expression governance; the rule SHALL declare
  no input bindings; and the `group` trigger SHALL be rejected, because a group-grain deterministic
  rule is an aggregation.

The **group-trigger checks** SHALL be:

1. `trigger_kind = group` ⇒ `group_by` is present and **equal to the target enrichment's grain key**.
   A mismatch SHALL be rejected, because the enrichment is physically keyed on its grain and collapses
   by it, so a rule grouping by another column would write many groups onto one key.
2. `group_by` and `ready_when` SHALL be rejected on a non-`group` trigger. This is the enforceable
   form of "`on_ingest` stays row-grain": a grain key carries no row-vs-group marker, so the gate
   constrains the *declaration* rather than inferring the grain's nature.
3. `ready_when.signal`, when present, SHALL parse under the same bounded Calcite grammar as
   `filter_sql`, SHALL be boolean over the **source's** declared columns, and its referenced columns
   SHALL run the sensitive-column entitlement check — a `sensitive` reference is admitted for an
   entitled author and rejected with HTTP 403 `sensitive_column_not_entitled` otherwise.
4. `ready_when.idle` and `ready_when.max_staleness`, when present, SHALL parse as durations (both the
   short form `10m` and ISO-8601 `PT10M` are accepted); `ready_when.cost_ceiling`, when present, SHALL
   be a positive integer.
5. `trigger_kind = group` ⇒ `ready_when` SHALL declare at least one of `signal`, `idle`, or
   `max_staleness`. A readiness declaration with none would mark a group dirty forever and never
   ready, so the rule would silently do nothing.

One rule per `target_enrichment` is unchanged, and applies to group rules identically.

Note (check 3): the gate does NOT verify that an `llm` evaluator's `model` names a live DIAL
deployment — ADAS has no deployment-listing client to reuse (only caller-key introspection). Live
deployment verification is deferred; only evaluator resolution and a non-blank model are enforced here.

#### Scenario: Bad output binding column or type is rejected

- **WHEN** a rule declares an output binding to a column absent on the target enrichment or with a
  mismatched type
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: Unparseable or unbounded filter is rejected

- **WHEN** `filter_sql` fails to parse, is not boolean, references undeclared columns, or uses a
  join/subquery/CTE
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: Unresolvable evaluator or missing model on an llm rule is rejected

- **WHEN** the rule's evaluator (name/version) does not resolve, or the resolved `llm` evaluator has a
  blank `model`
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: Disallowed trigger kind is rejected

- **WHEN** a rule declares a `trigger_kind` outside `{on_ingest, schedule, group}` (e.g. `push`), or
  declares `group` for a `type=sql` evaluator
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: Unparseable JSONata expression is rejected

- **WHEN** any input or output JSONata expression of an `llm` rule fails to parse
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: A sql rule is validated without a model or JSONata

- **WHEN** a rule binds a `type=sql` evaluator that carries no `model` and no JSONata
- **THEN** the model and JSONata checks are skipped, the `sql` expressions are validated instead, and
  a well-formed rule is accepted

#### Scenario: A group_by not equal to the target grain is rejected

- **WHEN** a `group` rule declares a `group_by` different from the target enrichment's grain key
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: group_by or ready_when on a non-group trigger is rejected

- **WHEN** an `on_ingest` or `schedule` rule declares `group_by` or `ready_when`
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: An unparseable or non-boolean readiness signal is rejected

- **WHEN** a `group` rule's `ready_when.signal` fails to parse, is not boolean, references undeclared
  columns, or uses a join/subquery/CTE
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: An unparseable readiness duration or non-positive ceiling is rejected

- **WHEN** a `group` rule's `ready_when.idle`/`max_staleness` is not a parseable duration, or its
  `cost_ceiling` is zero or negative
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: A readiness declaration with no condition is rejected

- **WHEN** a `group` rule's `ready_when` declares none of `signal`, `idle`, or `max_staleness`
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: A group rule referencing a sensitive column is accepted for an entitled author

- **WHEN** a `FULL_ADMIN` caller (or any caller in `none` mode) registers a `group` rule whose
  `ready_when.signal` or `filter_sql` references a `sensitive` source column
- **THEN** the rule is accepted

#### Scenario: A group rule referencing a sensitive column is rejected for a non-entitled author

- **WHEN** a caller who may not see sensitive columns registers the same rule
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`
