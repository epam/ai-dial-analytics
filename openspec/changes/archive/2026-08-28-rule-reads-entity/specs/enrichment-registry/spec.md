## MODIFIED Requirements

### Requirement: A rule's read source is declarable

A rule SHALL have a **read source**: the single table it reads to derive its values. It MAY be
declared as `source` on `POST /v1/rules` / `PUT /v1/rules/{id}`, and when omitted SHALL default to the
target enrichment's `source_table`. Every rule registered before this capability therefore keeps its
exact behaviour, and `source` is an additive, optional field.

A declared `source` SHALL name an existing, `ACTIVE` table of kind `source`; an unknown name, a name of
kind `enrichment`, and a name whose definition is not `ACTIVE` SHALL each be rejected with HTTP 422
`rule_validation_failed`. Only an `ACTIVE` definition has a confirmed ClickHouse table behind it, and a
`FAILED` one keeps the columns its failed schema definition persisted — so without the status check it
could satisfy both invariants below and still be a table no scan can read. This mirrors the target
enrichment, which must also be `ACTIVE`.

The read source is what every governed read of the rule resolves: `filter_sql`, `ready_when.signal`,
`member_select`, `input_bindings` and a `sql` evaluator's output expressions are validated against its
**entity** — the read source plus its `ACTIVE` enrichments, as "A rule reads its read source's entity"
defines — and the incremental scan, the group-input assembly, and the Class-1 materialize page it. It is
**not** the same fact as the target enrichment's `source_table`, which remains the enrichment's immutable
read-side attachment — the table the enrichment auto-joins into on the shared grain key, whose schema
view flattens it, and which cannot be deleted while it has enrichments. Decoupling the two is what allows
an enrichment to hang off one table while being derived from another: `conversations_feedback` is an
enrichment of `conversations` keyed on `chat_id`, computed by a `group` rule reading `rate_analytics`,
which carries `chat_id` too.

The two cases are **not distinguishable in a rule read**: `source` reports the resolved read source,
declared or derived alike (see "Rule reads"). A caller that echoes a read response back into
`PUT /v1/rules/{id}` therefore *declares* what the rule previously derived, which is validated more
strictly by the invariants below — omitting `source` is how a caller keeps a rule following its target.

Because a declared read source is no longer guaranteed to be join-compatible with the target by
construction, the gate SHALL enforce two invariants in its place on a **declared** read source, on
register and on enable, each rejected with HTTP 422 `rule_validation_failed`:

1. **The grain key is reachable on the read source's entity, at a compatible type.** The target
   enrichment's grain key SHALL resolve to a field of the read source's entity — either a declared column
   of the read source itself (matched on either its logical `name` or its `source_name`), or the
   qualified flat name of a column of one of the read source's `ACTIVE` enrichments whose last segment is
   the grain key. Its declared type SHALL be compatible with the type the target enrichment is keyed at.
   The rule's derived rows are keyed by that column, and the target is read through a grain join on it, so
   a read source whose entity does not carry it could produce no addressable row, and one carrying a
   same-named column of another type would key the enrichment with values its grain join cannot match.
   A grain key reached **only through an enrichment** SHALL be admitted for a `group`-trigger rule alone,
   which names the qualified field as its `group_by` and has every read project it under the plain grain
   key. A row-grain rule declares no `group_by` and reads the grain key under its own bare name, which the
   read source does not carry — and many source rows share one enrichment value, so it would write many
   derived rows onto one target key for the target's `ReplacingMergeTree` to collapse arbitrarily. Where
   more than one `ACTIVE` enrichment of the read source declares the grain key, a `group_by` naming one of
   them SHALL settle which is meant; a declaration naming none SHALL be rejected as ambiguous.
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

#### Scenario: A declared read source that is not ACTIVE is rejected

- **WHEN** `POST /v1/rules` declares a `source` naming a `source`-kind table whose status is `pending`
  or `failed`
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`, because no
  materialized table stands behind it

#### Scenario: A read source whose entity does not reach the target's grain key is rejected

- **WHEN** a rule declares a `source` that carries no column matching the target enrichment's grain key,
  and none of that source's `ACTIVE` enrichments carries one either
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`, naming the
  grain key the read source's entity must reach

#### Scenario: A grain key reached through an enrichment of the read source is accepted

- **WHEN** a rule declares `source: "dial_usage_log"` for a target enrichment keyed on
  `client_session_id`, which `dial_usage_log` does not declare but its `ACTIVE` enrichment
  `usage_client_identity` does, and declares
  `group_by: "usage_client_identity.client_session_id"`
- **THEN** the rule is created with HTTP 201, and its derived rows are keyed by the plain grain key
  `client_session_id`

#### Scenario: A grain key named without its enrichment qualifier is rejected

- **WHEN** the same rule declares `group_by: "client_session_id"` while no column of that name is
  declared on `dial_usage_log` itself
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`, naming the
  qualified spelling `<enrichment>.<column>` to use instead — an enrichment column has no unqualified
  spelling in the entity namespace

#### Scenario: A row-grain rule may not key its rows on an enrichment column

- **WHEN** a rule for the same target declares `source: "dial_usage_log"` with `trigger_kind: "on_ingest"`,
  so its grain key is reachable only through an enrichment and it declares no `group_by`
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`, naming the group
  trigger as the only shape that may key derived rows on an enrichment column

#### Scenario: A grain key reached through an enrichment of another source is rejected

- **WHEN** a rule declares a `group_by` qualified with an enrichment that is not an `ACTIVE` enrichment
  of the rule's own read source
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: A read source whose grain-key column has an incompatible type is rejected

- **WHEN** a rule declares a `source` whose entity reaches the target enrichment's grain key as a field of
  a type incompatible with the type the target is keyed at, whether that field is a source column or an
  enrichment column
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

#### Scenario: Governed SQL is scoped to the declared read source's entity

- **WHEN** a rule declares `source: "rate_analytics"` and a `filter_sql` referencing a column of
  `conversations` that neither `rate_analytics` nor any of its `ACTIVE` enrichments carries
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed` — entity scoping
  follows the read source, not the target enrichment's `source_table`

### Requirement: Static governance gate on register and enable

The system SHALL statically validate a rule on register and on enable; each failure SHALL be
rejected with HTTP 422 `rule_validation_failed` (except entitlement and immutability, which use
their own codes) with detail identifying the failing check. The gate SHALL enforce: (1) the
`target_enrichment` exists, is `ACTIVE`, and is kind=enrichment, and every output-binding column
exists on it with a type matching the output-var type via the shared JSON→ClickHouse type-mapping
util; (2) the rule's **read source** resolves — a declared `source` names an existing `source`-kind
table, and an omitted one defaults to the target enrichment's `source_table` — and satisfies the
grain-key-reachable and scan-metadata invariants (see "A rule's read source is declarable");
(3) `filter_sql` parses under the existing Calcite SQL front-end, is boolean over the read source's
**entity fields**, uses the bounded grammar (no joins/subqueries/CTEs), and is **entity-scoped** (see
below), with referenced columns extracted; (4) the evaluator resolves; (5) `trigger_kind ∈ {on_ingest,
schedule, group}` and the trigger's declaration is internally consistent; (6) the evaluator-type
specific checks; (7) the group-trigger checks below.

**Entity-scoping.** Every governed SQL fragment a rule carries — `filter_sql`, `ready_when.signal`,
`member_select.prefer_sql`, and a `sql` evaluator's output expressions — is validated by embedding it in
a statement over the rule's **read source**, so the gate SHALL additionally confirm that what the front
end accepted actually reads that source's entity: the translated statement's entity SHALL equal the
rule's read source (compared case-insensitively), and every referenced column SHALL be either a declared
column of that source (matched on either its logical `name` or its `source_name`) or the **qualified**
flat name `<enrichment>.<column>` of a column of one of that source's `ACTIVE` enrichments. Both are
rejected with HTTP 422 `rule_validation_failed`. Making the read source declarable SHALL NOT weaken
either check: a rule reads exactly one source table, and entity-scoping is what confines it to that
source and the enrichments reached through its own grain joins.

Neither check is redundant with the front end. A fragment that supplies its own `FROM` clause — an
expression ending in a SQL line comment, say — parses as a single valid `SELECT` over a *different*
table, and the sensitive-column entitlement check resolves referenced columns against the **rule's read
source's** entity, where such columns are absent, so it would find nothing to gate. Separately, the
query model publishes an enrichment's columns only under their qualified flat name, so a fragment naming
an enrichment column of some *other* source, or naming an enrichment column under a bare unqualified
spelling, parses cleanly against no field the rule's own statement joins — and the rendered statement
would reference a table absent from its own `FROM` clause, which ClickHouse rejects at execution.
Rejecting both at registration keeps the entitlement gate total and turns an execution failure into the
validation failure it always was.

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

A **bound input binding's** `column` SHALL name a field of the read source's entity, under the same
resolution as a governed fragment's column reference: a source column under either spelling, or an
enrichment column under its qualified flat name. A binding naming no field of the entity SHALL be
rejected with HTTP 422 `rule_validation_failed`.

The **group-trigger checks** SHALL be:

1. `trigger_kind = group` ⇒ `group_by` is present and **resolves to the target enrichment's grain key**:
   either equal to it, or the qualified flat name of an enrichment column whose last segment is it. Any
   other value SHALL be rejected, because the enrichment is physically keyed on its grain and collapses
   by it, so a rule grouping by another column would write many groups onto one key. Since the grain key
   is also required to be reachable on the rule's read source's entity, a `group` rule always groups the
   rows it reads by a value those rows carry.
2. `group_by` and `ready_when` SHALL be rejected on a non-`group` trigger. This is the enforceable
   form of "`on_ingest` stays row-grain": a grain key carries no row-vs-group marker, so the gate
   constrains the *declaration* rather than inferring the grain's nature.
3. `ready_when.signal`, when present, SHALL parse under the same bounded Calcite grammar as
   `filter_sql`, SHALL be boolean over the **read source's entity fields**, and its referenced columns
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

- **WHEN** `filter_sql` fails to parse, is not boolean, references columns reachable on neither the rule's
  read source nor its `ACTIVE` enrichments, or uses a join/subquery/CTE
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: Governed SQL reaching outside the rule's read source's entity is rejected

- **WHEN** a rule's `filter_sql`, `ready_when.signal`, `member_select.prefer_sql`, or `sql` output
  expression resolves against an entity other than the rule's read source, or references a column that
  is neither that source's own nor the qualified `<enrichment>.<column>` of one of that source's `ACTIVE`
  enrichments
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`, rather than
  passing the gate with an unentitled column reference or failing later at execution

#### Scenario: A filter over an enrichment column of the read source is accepted

- **WHEN** a rule declares `source: "dial_usage_log"` and a `filter_sql` of
  `length("usage_client_identity.client_session_id") > 0`, where `usage_client_identity` is an `ACTIVE`
  enrichment of `dial_usage_log`
- **THEN** the rule is accepted, and the scan and group-assembly statements emit the grain join for that
  enrichment

#### Scenario: A bound input binding over an enrichment column is accepted

- **WHEN** a rule binds an evaluator input variable to `"usage_client_identity.client_type"`
- **THEN** the rule is accepted, and the binding is stored under that qualified name

#### Scenario: A bound input binding naming no entity field is rejected

- **WHEN** a rule binds an evaluator input variable to a column reachable on neither the read source nor
  its `ACTIVE` enrichments
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

#### Scenario: A group_by not resolving to the target grain is rejected

- **WHEN** a `group` rule declares a `group_by` that neither equals the target enrichment's grain key nor
  is the qualified flat name of an enrichment column whose last segment is that grain key
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: group_by or ready_when on a non-group trigger is rejected

- **WHEN** an `on_ingest` or `schedule` rule declares `group_by` or `ready_when`
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: An unparseable or non-boolean readiness signal is rejected

- **WHEN** a `group` rule's `ready_when.signal` fails to parse, is not boolean, references columns
  reachable on neither the read source nor its `ACTIVE` enrichments, or uses a join/subquery/CTE
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

## ADDED Requirements

### Requirement: A rule reads its read source's entity, not only its read source table

Every column reference a rule declares SHALL resolve against the **entity** of its read source: the
read source's own declared columns under their exposed names, plus the columns of each of its `ACTIVE`
enrichments under the qualified flat name `<enrichment>.<column>`. This is the same flat namespace
`GET /v1/queries/entities/{name}` publishes and the aggregate pipeline surface resolves, so a rule, a
rollup and a query over the same entity SHALL name a column identically.

Membership SHALL be decided once, from the catalog's `ACTIVE` enrichments of the read source, and SHALL
be the same set the compiled statement joins — a field admitted by validation that the statement would
not join is a failure at execution rather than at declaration, and SHALL NOT be reachable.

The surfaces this covers SHALL be: the target enrichment's grain key and `group_by`; `filter_sql`;
`ready_when.signal`; `member_select.prefer_sql` and `member_select.order_by`; `input_bindings[].column`;
and a `sql` evaluator's output expressions.

An enrichment column SHALL be addressable **only** under its qualified flat name. A bare column name
SHALL always resolve to the read source, so an enrichment SHALL NOT be able to shadow a source column,
and a rule declaring only bare source column names SHALL behave and render exactly as before this
capability. `source` SHALL continue to name a source table: naming an enrichment as `source` stays
rejected with HTTP 422 `rule_validation_failed`, because an enrichment is reached through its source's
grain join rather than read directly. This capability SHALL NOT introduce a second input, general joins,
or a rule reading an enrichment at a different grain from its read source.

No new rule field SHALL be introduced and no opt-in SHALL be required: the resolution is a widening, and
an enrichment column's qualified spelling was previously rejected outright.

#### Scenario: A rule naming no enrichment column is unchanged

- **WHEN** a rule registered before this capability, referencing only its read source's own columns, is
  read, enabled, scanned, assembled or materialized
- **THEN** its validation outcome and the SQL its reads render are unchanged, and no enrichment is
  joined

#### Scenario: A referenced enrichment is joined; an untouched one is not

- **WHEN** a rule references a column of one `ACTIVE` enrichment of its read source, and its read source
  has other `ACTIVE` enrichments it does not reference
- **THEN** every statement whose own clauses reference that enrichment emits a LEFT JOIN for it on the
  shared grain key, and no join is emitted for the enrichments nothing references

#### Scenario: A bare enrichment column name resolves to the source, not the enrichment

- **WHEN** a rule references a bare column name that an `ACTIVE` enrichment of its read source declares
  and the read source does not
- **THEN** the reference is rejected with HTTP 422 and error code `rule_validation_failed`, naming the
  qualified spelling — the bare namespace belongs to the read source alone

#### Scenario: Naming an enrichment as the read source is still rejected

- **WHEN** `POST /v1/rules` declares `source` naming a table of kind `enrichment`
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: The rule path and the query path agree on a column's value

- **WHEN** the same enrichment column is read through a rule's scan and through
  `POST /v1/queries/execute` over the same entity for the same source row
- **THEN** both return the same value, because both resolve the same flat field through the same grain
  join

### Requirement: An enrichment-sourced value is readable only where absence is representable

A rule's grain join is **outer**, so an enrichment column SHALL be treated as possibly absent whatever
the enrichment declared. Where such a column produces a **value** — a `group_by` key, or a `sql`
evaluator's output expression — the enrichment column SHALL be declared `nullable`, and a rule reading
a non-`nullable` one in such a position SHALL be rejected with HTTP 422 `rule_validation_failed`.

The reason is a ClickHouse default rather than a policy: `join_use_nulls` is 0, so an unmatched outer
join renders a non-nullable column as its **type default** — `''`, `0`, `false`, the epoch — which is
indistinguishable from a derived value. `array` and `map` columns SHALL be exempt: ClickHouse has no
`Nullable(Array)`, and their default is *empty*, which contributes nothing.

A `filter_sql`, `ready_when.signal` or `member_select.prefer_sql` MAY still read a non-`nullable`
enrichment column: there it **selects rows** rather than producing a value, and
`"<enrichment>.<column>" != ''` is how such a predicate excludes the rows the enrichment has not
reached.

#### Scenario: A non-nullable enrichment column is refused as a group key

- **WHEN** a `group` rule declares a `group_by` naming an enrichment column declared `nullable: false`
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: A non-nullable enrichment column is refused in a Class-1 output expression

- **WHEN** a `type=sql` rule's output expression reads an enrichment column declared `nullable: false`
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: A non-nullable enrichment column may still be read by a predicate

- **WHEN** the same column is referenced only by the rule's `filter_sql`, `ready_when.signal` or
  `member_select.prefer_sql`
- **THEN** the rule is accepted

#### Scenario: An array enrichment column is exempt

- **WHEN** a rule reads an `array`-typed enrichment column declared `nullable: false` in a
  value-producing position
- **THEN** the rule is accepted, because an unmatched join renders it empty rather than as a value

### Requirement: An enrichment read requires an establishable materialized position

A rule reading an enrichment column SHALL be admitted only when the position that enrichment has been
materialized through can be established, because that position is what bounds the rule's read window
(see [enrichment-runtime](../enrichment-runtime/spec.md) and
[class1-enrichment](../class1-enrichment/spec.md)). Registration and enable SHALL classify each
referenced enrichment and SHALL reject, with HTTP 422 `rule_validation_failed` naming the enrichment and
the reason, one whose position cannot be established:

- an enrichment written by a **`group`-trigger** rule — a group's members span arbitrary source
  positions, so the only sound position is the one below which every group has settled, which one
  long-lived group pins indefinitely, and the value changes on every recompute by design;
- an enrichment written by a rule **ADAS does not execute itself** — the write-through position is held
  by the runner and never reported.

An enrichment that **no rule targets** SHALL be admitted and SHALL impose no bound: a rule-less
enrichment will never gain another row, so its absent values are final rather than late and there is
nothing to wait for.

This is stricter than the aggregate pipeline surface, which admits an unbounded read and reports it.
The asymmetry is deliberate: a rollup recomputes every dirty key in full on its next refresh, so an
unbounded read costs it a number that self-corrects, while a rule's read source cursor advances once —
neither a group left unmarked nor a Class-1 value written from an unlanded input comes back.

#### Scenario: An enrichment written by a Class-1 rule is admitted

- **WHEN** a rule references a column of an enrichment written by a `type=sql` rule scanning the same
  read source
- **THEN** the rule is accepted, and its read window is bounded by that enrichment's recorded position

#### Scenario: An enrichment written by a group rule is refused

- **WHEN** a rule references a column of an enrichment whose own rule declares `trigger_kind: "group"`
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`, naming the
  enrichment and that no position can be established for a group-written enrichment

#### Scenario: An enrichment written by a runner-executed rule is refused

- **WHEN** a rule references a column of an enrichment written by a rule ADAS does not execute itself
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: An enrichment no rule targets is admitted unbounded

- **WHEN** a rule references a column of an `ACTIVE` enrichment that no rule targets
- **THEN** the rule is accepted, the enrichment contributes no bound, and the rule's state reports the
  unbounded read

### Requirement: A rule's enrichment references must not form a dependency cycle

Registration and enable SHALL reject, with HTTP 422 `rule_validation_failed`, a rule whose enrichment
references would close a cycle in the dependency graph whose edges run from a rule's
`target_enrichment` to each enrichment that rule reads. A `type=sql` rule's reads live in its **pinned
evaluator's output expressions** rather than in the rule row, and SHALL count as edges exactly as the
rule's own fragments do — otherwise the rule deriving from a sibling enrichment, which is the shape this
capability exists to allow, would contribute no edge at all. Two rules each reading the other's target would
bound each rule's window on the other's position, freezing both permanently with no error raised and no
operator action that releases them. The refusal SHALL name the enrichments forming the cycle.

#### Scenario: A rule closing a cycle is rejected

- **WHEN** a rule targeting enrichment `A` references a column of enrichment `B`, and `B`'s own rule
  references a column of `A`
- **THEN** the later request is rejected with HTTP 422 and error code `rule_validation_failed`, naming
  the enrichments forming the cycle

#### Scenario: A rule reading its own target is rejected

- **WHEN** a rule references a column of the enrichment it targets
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: An acyclic chain is accepted

- **WHEN** a rule targeting `C` references `B`, and `B`'s rule references `A`, and nothing references `C`
- **THEN** both rules are accepted

### Requirement: Sensitive-column entitlement crosses the grain join

The sensitive-column rule SHALL apply to an enrichment column exactly as it applies to a read-source
column. A `sensitive` column of an `ACTIVE` enrichment of the rule's read source SHALL be referenceable
only by a caller entitled to see sensitive columns, and a reference by any other caller SHALL be
rejected with HTTP 403 `sensitive_column_not_entitled`.

Because such a column is **hidden** from a non-entitled caller's schema view rather than forbidden to
it, a governed fragment naming one fails to parse for that caller. That failure SHALL be re-attributed
to HTTP 403 `sensitive_column_not_entitled` rather than reported as HTTP 422
`rule_validation_failed`: the rule is not malformed, the caller is not entitled, and the contract
promises the 403.

The rule governs **who may reference** the column, and nothing more. A derived enrichment column SHALL
NOT inherit sensitivity from an enrichment column any more than it does from a source column — the
existing "Runner identity and derived-column sensitivity posture" requirement applies unchanged, and
this capability SHALL NOT introduce an exception to it for a value that happens to arrive through a
grain join. Controlling a derived column's exposure remains the enrichment author's decision, taken by
declaring that target column `sensitive`.

#### Scenario: A sensitive enrichment column is accepted for an entitled author

- **WHEN** a `FULL_ADMIN` caller (or any caller in `none` mode) registers a rule referencing a
  `sensitive` column of an `ACTIVE` enrichment of its read source
- **THEN** the rule is accepted

#### Scenario: A sensitive enrichment column is refused for a non-entitled author

- **WHEN** a caller who may not see sensitive columns registers the same rule
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`, not
  with HTTP 422 reporting an unknown column

#### Scenario: A derived column does not inherit an enrichment column's sensitivity

- **WHEN** a `type=sql` rule's output expression reads a `sensitive` enrichment column and binds the
  result to a target column that is not itself `sensitive`
- **THEN** the rule is accepted and the target column's `sensitive` flag is left as the table author
  declared it, exactly as for a `sensitive` column of the read source — an enrichment column reached
  through the grain join is governed no differently from one the source declares

### Requirement: A rule's enrichment references are protected against drop and rename

An enrichment a rule reads SHALL be a dependency of that rule, protected exactly as the rule's declared
read source is. What a rule reads SHALL be resolved from its whole declaration, including a `type=sql`
rule's pinned evaluator output expressions — the only place such a rule's column references appear. Dropping such an enrichment, or dropping or renaming a column of it that a rule
references, SHALL be refused with HTTP 409 `conflict` naming the rules that read it, rather than
leaving those rules to fail on their next scan or materialize.

#### Scenario: Dropping an enrichment a rule reads is refused

- **WHEN** `DELETE /v1/tables/{name}` names an enrichment whose columns a registered rule references
- **THEN** the request is refused with HTTP 409 and error code `conflict`, naming the rules that
  read it

#### Scenario: Dropping an enrichment read only by a Class-1 output expression is refused

- **WHEN** `DELETE /v1/tables/{name}` names an enrichment referenced only by a `type=sql` rule's
  evaluator output expression, with no reference in the rule row itself
- **THEN** the request is refused with HTTP 409 and error code `conflict`, naming that rule

#### Scenario: Dropping a referenced column of an enrichment is refused

- **WHEN** a schema patch would drop or rename a column of an enrichment that a registered rule
  references
- **THEN** the request is refused with HTTP 409 and error code `conflict`

#### Scenario: An unreferenced column of a read enrichment may still change

- **WHEN** a schema patch drops a column of an enrichment that no rule references, while other columns
  of that enrichment are referenced
- **THEN** the patch is accepted
