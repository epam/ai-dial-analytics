## ADDED Requirements

### Requirement: A rule's read source is declarable

A rule SHALL have a **read source**: the single table it reads to derive its values. It MAY be
declared as `source` on `POST /v1/rules` / `PUT /v1/rules/{id}`, and when omitted SHALL default to the
target enrichment's `source_table`. Every rule registered before this capability therefore keeps its
exact behaviour, and `source` is an additive, optional field.

A declared `source` SHALL name an existing table of kind `source`; an unknown name, or a name of kind
`enrichment`, SHALL be rejected with HTTP 422 `rule_validation_failed`.

The read source is what every governed read of the rule resolves: `filter_sql`, `ready_when.signal`,
and a `sql` evaluator's output expressions are validated against it, and the incremental scan, the
group-input assembly, and the Class-1 materialize page it. It is **not** the same fact as the target
enrichment's `source_table`, which remains the enrichment's immutable read-side attachment — the table
the enrichment auto-joins into on the shared grain key, whose schema view flattens it, and which cannot
be deleted while it has enrichments. Decoupling the two is what allows an enrichment to hang off one
table while being derived from another: `conversations_feedback` is an enrichment of `conversations`
keyed on `chat_id`, computed by a `group` rule reading `rate_analytics`, which carries `chat_id` too.

Because a declared read source is no longer guaranteed to be join-compatible with the target by
construction, the gate SHALL enforce two invariants in its place on a **declared** read source, on
register and on enable, each rejected with HTTP 422 `rule_validation_failed`:

1. **The grain key is present on the read source, at a compatible type.** The target enrichment's grain
   key SHALL be a declared column of the read source (matched on either its logical `name` or its
   `source_name`), and its declared type SHALL be compatible with the type the target enrichment is
   keyed at. The rule's derived rows are keyed by that column, and the target is read through a grain
   join on it, so a read source that does not carry it could produce no addressable row, and one
   carrying a same-named column of another type would key the enrichment with values its grain join
   cannot match.
2. **The read source is scannable.** The read source SHALL declare scan metadata — both
   `version_column` and `identity_column` (see [enrichment-runtime](../enrichment-runtime/spec.md)).
   Every rule-scoped read already requires the pair; checking it at registration reports the
   misdeclaration to the author rather than at the runner's first scan.

Neither invariant SHALL be applied to a **derived** read source. The first holds there by construction —
an enrichment's grain key is validated to be a column of its `source_table` when its schema is written —
so applying it would only re-check a catalog fact. The second is a genuinely new requirement that a
derived source need not meet: declaring scan metadata is optional, a rule over an enrichment of an
unscannable source registers today and is refused on the scan path instead (HTTP 422), and that rule
SHALL remain registrable. Declaring a read source must not invalidate rules that are valid without one.

#### Scenario: A rule omitting source derives it from the target enrichment

- **WHEN** `POST /v1/rules` is called without `source`
- **THEN** the rule's read source is the target enrichment's `source_table`, and the created rule
  reports that table as its `source`

#### Scenario: A rule declares a read source other than the target's source table

- **WHEN** `POST /v1/rules` is called for a target enrichment of `conversations` keyed on `chat_id`,
  with `source: "rate_analytics"`, a `filter_sql` over `rate_analytics` columns, and `group_by: "chat_id"`
- **THEN** the rule is created with HTTP 201, its `source` is `rate_analytics`, and the target
  enrichment's own `source_table` is unchanged

#### Scenario: A declared read source that is not a source table is rejected

- **WHEN** `POST /v1/rules` declares a `source` that does not exist, or that names an enrichment
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: A read source lacking the target's grain key is rejected

- **WHEN** a rule declares a `source` that carries no column matching the target enrichment's grain key
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`, naming the
  grain key the read source must carry

#### Scenario: A read source whose grain-key column has an incompatible type is rejected

- **WHEN** a rule declares a `source` that carries the target enrichment's grain key as a column of a
  type incompatible with the type the target is keyed at
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: A declared read source without scan metadata is rejected at registration

- **WHEN** a rule declares a `source` whose `table_definition` declares no `version_column` or no
  `identity_column`
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed` at
  registration, rather than being accepted and failing at the first scan

#### Scenario: A rule deriving an unscannable read source still registers

- **WHEN** a rule declares no `source` and its target enrichment's `source_table` declares no scan
  metadata
- **THEN** the rule is created, unchanged from the behaviour before `source` was declarable, and the
  scan path is what refuses it

#### Scenario: Governed SQL is scoped to the declared read source

- **WHEN** a rule declares `source: "rate_analytics"` and a `filter_sql` referencing a column of
  `conversations` that `rate_analytics` does not carry
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed` — source-scoping
  follows the read source, not the target enrichment's `source_table`

## MODIFIED Requirements

### Requirement: Rule CRUD binding an evaluator to a target enrichment

The system SHALL manage rules via `POST /v1/rules`, `PUT /v1/rules/{id}`, `DELETE /v1/rules/{id}`,
and `GET /v1/rules/{id}`. A rule SHALL bind an `evaluator_name` (+ optional `evaluator_version`,
null meaning `latest`) to a `target_enrichment`, plus an optional `source`, `trigger_kind`, optional
`trigger_cron`, `group_by`, `ready_when`, `filter_sql`, `input_bindings`, `output_bindings`, and
execution knobs (`sampling`, `cadence`, `batch_scan_limit`, `batch_chunk`, `rate_rpm`, `priority`,
`enabled`). The rule's **grain SHALL be derived** from the target enrichment's `grain` and SHALL NOT be
re-declared on the rule. The rule's **read source MAY be declared** as `source` and SHALL default to the
target enrichment's `source_table` when omitted (see "A rule's read source is declarable"). Each mutating
rule operation SHALL bump a `generation` counter and `updated_at`. An unknown rule id SHALL be rejected
with HTTP 404 `unknown_rule`.

`enabled` SHALL be **required** on a create/update request and SHALL carry no implicit default: whether
a rule starts live is an operator decision, and an omitted or null `enabled` SHALL be rejected as a
validation failure (HTTP 400 `bad_request`) rather than silently defaulting to `false`.

A returned rule SHALL carry `source` — its read source, declared or derived — and SHALL additionally
carry `version_column`: the **physical name of the read source's version column**, derived from that
source's catalog scan metadata (`table_definition.version_column`) — the same fact the scan path
resolves, so the two cannot disagree. `version_column` SHALL NOT be declarable on the rule. A scan
page projects that column under its own name, and for a `group` rule its value is the group's activity
timestamp; surfacing the name is what lets a caller read it per source rather than holding one
configured name for every source it scans. When the read source is unknown or declares no scan metadata,
`version_column` SHALL be **absent** and the read SHALL still succeed — the rule is valid, and an
unscannable source is rejected on the scan path (HTTP 422) rather than on a registry read.

Because `version_column` is derived rather than declared, it can change without any rule being mutated:
`PATCH /v1/tables/{source}/schema` MAY rename the column and repoint the stored name (see
[table-management](../table-management/spec.md)). A rule mutation is therefore **not** the only event
that bumps `generation`: when a source's `version_column` is repointed, every rule **whose read source
is that source** SHALL have its `generation` incremented and its `updated_at` advanced, in the same
transaction as the catalog repoint. The bump SHALL follow the read source, so a rule that declares one
is bumped by the table it actually scans and not by its target enrichment's `source_table`. Both
`generation` and `updated_at`, not just `generation` — the sync feed selects by `updated_at`, so a
bumped generation the feed does not return would be no signal at all. Without this a runner detecting
change by `generation` would keep the pre-rename name and read a key its scan pages no longer carry.

`trigger_kind` SHALL be one of `on_ingest`, `schedule`, or `group`. A `group` rule fires once per
**group key** rather than once per row: it declares `group_by` (the group key) and `ready_when`
(`{signal?, idle?, max_staleness?, cost_ceiling?}`), the declaration of when a group is considered
complete enough to evaluate. A `group` rule SHALL NOT carry `trigger_cron` — its cadence is the
caller's readiness sweep, not a cron expression — which the existing cron-iff-`schedule` invariant
already enforces. A `group` rule writes exactly one enrichment row per group key through the existing
rule-scoped write-back, keyed by `group_by`, and the target enrichment's `ReplacingMergeTree` collapses
by that key.

#### Scenario: Create a rule derives its grain and defaults its source

- **WHEN** `POST /v1/rules` is called with a valid evaluator and `target_enrichment` and no `source`
- **THEN** the rule is created with `grain` derived from the target enrichment and `source` defaulted to
  that enrichment's `source_table`, `enabled` per the request, `generation` initialized, and HTTP 201
  returned

#### Scenario: Omitted enabled is rejected

- **WHEN** `POST /v1/rules` or `PUT /v1/rules/{id}` is called with `enabled` absent or null
- **THEN** the request is rejected with HTTP 400 `bad_request` and no rule is created or updated

#### Scenario: A returned rule carries the read source's version column

- **WHEN** a rule is created, updated, or read and its read source declares scan metadata
- **THEN** the response carries `version_column` equal to that source's declared version column

#### Scenario: A source without scan metadata omits version_column

- **WHEN** a rule is read and its read source declares no version column (or the source is unknown)
- **THEN** `version_column` is absent from the response and the read succeeds

#### Scenario: Update bumps generation

- **WHEN** `PUT /v1/rules/{id}` changes a rule
- **THEN** the stored rule is updated, `generation` is incremented, and `updated_at` advances

#### Scenario: Repointing the read source's version column bumps generation

- **WHEN** `PATCH /v1/tables/{source}/schema` renames the column named by that source's
  `version_column`, and a rule's read source is that source
- **THEN** that rule's `generation` is incremented and its `updated_at` advances in the same
  transaction as the catalog repoint, and a subsequent `GET /v1/rules/{id}` carries the renamed
  `version_column`
- **AND** a rule whose read source is a different table is untouched, including a rule whose target
  enrichment hangs off the repointed source but which declares another read source

#### Scenario: A repointed version column reaches an incremental sync

- **WHEN** a runner has synced with `updated_since` and the source then repoints its `version_column`
- **THEN** the next sync at that watermark returns the affected rules, each carrying the new
  `version_column` and a higher `generation`

#### Scenario: Renaming a non-version column of the source bumps nothing

- **WHEN** `PATCH /v1/tables/{source}/schema` renames a source column that is neither the source's
  `version_column` nor its `identity_column`
- **THEN** no rule's `generation` or `updated_at` changes

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
(the concrete version, resolving `latest` at sync time), the rule's read source and derived grain, the
read source's `version_column` (absent when that source declares none), and a `generation` value the
runner uses for change detection (surfaced as an ETag). `updated_since` SHALL filter to rules changed at
or after the given timestamp. For a `group` rule the feed SHALL additionally inline `group_by` and
`ready_when`, so a caller can route the rule to its group path and evaluate readiness without a second
round-trip.

The `source` the feed reports SHALL be the table the runner is to scan — the rule's declared read
source when it has one, otherwise the target enrichment's `source_table` — so a runner needs no second
lookup and cannot scan the wrong table.

The feed SHALL resolve every rule in a **constant number of queries** regardless of how many rules it
returns — including the version-column resolution, which SHALL be one batched fetch over the distinct
read sources of the returned rules rather than a per-rule lookup.

A rule's `updated_at` SHALL be stored at the precision it is reported with, so the value any rule read or
write returns is usable verbatim as the next `updated_since`. The watermark is inclusive (`>=`), so a
reported timestamp finer than the stored one would place the watermark past the rule and skip it — the
registry therefore mints `created_at`/`updated_at` truncated to microseconds, which is what the column holds.

#### Scenario: A reported updated_at is a usable watermark

- **WHEN** a rule is created or updated, and the `updated_at` from that response is passed straight back as
  `GET /v1/rules?updated_since=<updated_at>`
- **THEN** that rule is returned, and a later read of the rule reports the same `updated_at` value

#### Scenario: Sync returns enabled rules with inlined evaluators

- **WHEN** `GET /v1/rules` is called
- **THEN** each enabled rule is returned with its resolved evaluator version inlined, its read source and
  derived grain, and a `generation`

#### Scenario: Sync carries the read source of a rule that declares one

- **WHEN** `GET /v1/rules` is called and an enabled rule declares a `source` other than its target
  enrichment's `source_table`
- **THEN** that rule's `source` is the declared read source, and its `version_column` is that source's
  declared version column

#### Scenario: Sync carries the source's version column

- **WHEN** `GET /v1/rules` is called and an enabled rule's read source declares scan metadata
- **THEN** that rule carries `version_column` equal to that source's declared version column

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
util; (2) the rule's **read source** resolves — a declared `source` names an existing `source`-kind
table, and an omitted one defaults to the target enrichment's `source_table` — and satisfies the
grain-key-present and scan-metadata invariants (see "A rule's read source is declarable");
(3) `filter_sql` parses under the existing Calcite SQL front-end, is boolean over the read source's
declared columns, uses the bounded grammar (no joins/subqueries/CTEs), and is **source-scoped** (see
below), with referenced columns extracted; (4) the evaluator resolves; (5) `trigger_kind ∈ {on_ingest,
schedule, group}` and the trigger's declaration is internally consistent; (6) the evaluator-type
specific checks; (7) the group-trigger checks below.

**Source-scoping.** Every governed SQL fragment a rule carries — `filter_sql`, `ready_when.signal`, and
a `sql` evaluator's output expressions — is validated by embedding it in a statement over the rule's
**read source**, so the gate SHALL additionally confirm that what the front end accepted actually reads
that source: the translated statement's entity SHALL equal the rule's read source (compared
case-insensitively), and every referenced column SHALL be a declared column of that source (matched on
either its logical `name` or its `source_name`). Both are rejected with HTTP 422
`rule_validation_failed`. Making the read source declarable SHALL NOT weaken either check: a rule reads
exactly one table, and source-scoping is what confines it to the one the rule declares.

Neither check is redundant with the front end. A fragment that supplies its own `FROM` clause — an
expression ending in a SQL line comment, say — parses as a single valid `SELECT` over a *different*
table, and the sensitive-column entitlement check resolves referenced columns against the **rule's read
source's** catalog rows, where such columns are absent, so it would find nothing to gate. Separately, a
source's query model publishes its materialized enrichments' columns (qualified `table.column`), so a
fragment referencing one parses cleanly — but every rule-scoped read drops the enrichment joins, so the
rendered statement would reference a table absent from its own `FROM` clause and ClickHouse would reject
it at execution. Rejecting both at registration keeps the entitlement gate total and turns an execution
failure into the validation failure it always was.

The gate SHALL be **dispatched on the resolved evaluator's `type`**:

- **`llm`** — the resolved evaluator SHALL carry a non-blank `model`; all input/output JSONata
  expressions SHALL parse (static parse only — runtime evaluation is the runner's).
- **`sql`** — `model` SHALL NOT be required and JSONata SHALL NOT be parsed (there is none); every
  output variable's `sql` expression SHALL pass Class-1 expression governance (see
  [class1-enrichment](../class1-enrichment/spec.md)); the rule SHALL declare no input bindings, SHALL
  bind at least one output column, and SHALL target an enrichment keyed on the **read source's**
  identity column; and the `group` trigger SHALL be rejected, because a group-grain deterministic rule
  is an aggregation.

An accepted output binding's `column` SHALL be **stored normalized to the target enrichment's physical
column name** (its `source_name`). A binding may be supplied under either the column's logical `name` or
its `source_name`, but both write paths address the target physically — the Class-1 materialize statement
renders the column list into an `INSERT`, and the rule-scoped write-back reconciles caller rows against
the target's physical columns — so normalizing once at registration is what keeps a renamed column
writable instead of failing at execution. A rule read back through the API therefore reports the physical
column.

Output bindings SHALL be **one-to-one**: no two bindings may name the same target column, and no two may
name the same output variable. The column check runs after the normalization above, so naming one column
twice under its two spellings is caught too. Both write paths key their derived values by target column,
so a duplicate would silently drop a binding rather than fail — the Class-1 materialize statement would
emit a single `INSERT` column computed from whichever expression it resolved last.

The **group-trigger checks** SHALL be:

1. `trigger_kind = group` ⇒ `group_by` is present and **equal to the target enrichment's grain key**.
   A mismatch SHALL be rejected, because the enrichment is physically keyed on its grain and collapses
   by it, so a rule grouping by another column would write many groups onto one key. Since the grain key
   is also required to be a column of the rule's read source, a `group` rule always groups the rows it
   reads by a column those rows carry.
2. `group_by` and `ready_when` SHALL be rejected on a non-`group` trigger. This is the enforceable
   form of "`on_ingest` stays row-grain": a grain key carries no row-vs-group marker, so the gate
   constrains the *declaration* rather than inferring the grain's nature.
3. `ready_when.signal`, when present, SHALL parse under the same bounded Calcite grammar as
   `filter_sql`, SHALL be boolean over the **read source's** declared columns, and its referenced columns
   SHALL run the sensitive-column entitlement check.
4. `ready_when.idle` and `ready_when.max_staleness`, when present, SHALL parse as positive durations
   (both the short form `10m` and ISO-8601 `PT10M` are accepted); `ready_when.cost_ceiling`, when
   present, SHALL be a positive integer.
5. `trigger_kind = group` ⇒ `ready_when` SHALL declare at least one of `signal`, `idle`, or
   `max_staleness`. A readiness declaration with none would mark a group dirty forever and never
   ready, so the rule would silently do nothing.

Note (the model check): the gate does NOT verify that an `llm` evaluator's `model` names a
live DIAL deployment — ADAS has no deployment-listing client to reuse (only caller-key
introspection). Live deployment
verification is deferred; only evaluator resolution and a non-blank model are enforced here.

#### Scenario: Bad output binding column or type is rejected

- **WHEN** a rule declares an output binding to a column absent on the target enrichment or with a
  mismatched type
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: An output binding supplied under a column's logical name is stored physically

- **WHEN** a rule binds an output to a target column whose logical `name` differs from its `source_name`
- **THEN** the rule is accepted, the stored and returned binding names the column's `source_name`, and
  both write paths address the column ClickHouse actually carries

#### Scenario: Output bindings on the same column or the same variable are rejected

- **WHEN** a rule declares two output bindings naming the same target column (under either spelling), or
  two bindings naming the same output variable
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: Unparseable or unbounded filter is rejected

- **WHEN** `filter_sql` fails to parse, is not boolean, references columns undeclared on the rule's read
  source, or uses a join/subquery/CTE
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: Governed SQL reaching outside the rule's read source is rejected

- **WHEN** a rule's `filter_sql`, `ready_when.signal`, or `sql` output expression resolves against an
  entity other than the rule's read source, or references a column that is not that source's own —
  including a materialized enrichment's `table.column`
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`, rather than
  passing the gate with an unentitled column reference or failing later at execution

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
  `ready_when.signal` or `filter_sql` references a `sensitive` column of the rule's read source
- **THEN** the rule is accepted

#### Scenario: A group rule referencing a sensitive column is rejected for a non-entitled author

- **WHEN** a caller who may not see sensitive columns registers the same rule
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`
