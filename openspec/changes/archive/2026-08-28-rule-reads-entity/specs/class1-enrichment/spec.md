## MODIFIED Requirements

### Requirement: Class-1 expression governance

The system SHALL statically validate every `sql` output-variable expression at rule register and
enable, against the **entity** of the rule's read source — its declared `source` when it has one,
otherwise the target enrichment's `source_table` — where that entity comprises the read source's own
declared columns plus the columns of each of its `ACTIVE` enrichments under the qualified flat name
`<enrichment>.<column>` (see [enrichment-registry](../enrichment-registry/spec.md)). Each
failure SHALL be rejected with HTTP 422 `rule_validation_failed` carrying the failing detail.
Validation SHALL wrap the expression as `SELECT <expr> FROM <source>` through the **same** Calcite SQL
front end used for rule filters, so the bounded grammar (no joins, subqueries, or CTEs), the closed
function catalog, and unknown-column rejection apply identically. The materialize statement SHALL emit a
single-key `LEFT JOIN` on the shared grain key for each enrichment its own expressions reference, and
none for an enrichment nothing references. On top of that the gate SHALL enforce:

1. **Scalar, not aggregate.** An expression that resolves to an aggregate or window computation SHALL
   be rejected — an aggregating expression is an *aggregation*, not a Class-1 enrichment, and would
   silently break the target's `1:0..1` grain.
2. **A single projected value.** The expression SHALL yield exactly one column.
3. **Type-compatible.** The result type inferred by the front end SHALL be compatible with the output
   variable's declared `type` under the shared enrichment type mapping, and the declared type SHALL in
   turn be compatible with the bound target column's type (the existing output-binding check). An
   `object`-typed result SHALL be rejected: it is projection-only in the SQL subset and cannot be
   collapsed into a column bound to a grain. An `array`-typed result SHALL be **accepted** when the
   bound target column is itself array-typed, and rejected otherwise. The **element** type SHALL NOT
   be checked: `QueryFieldType.ARRAY` carries no element type, and a computed array has no declared
   `element_type` to compare against (the same reason `group_uniq_array`'s element kind follows its
   operand). The accepted consequence is that an array expression whose elements do not match the
   target column's element type is rejected by ClickHouse at insert time rather than at registration.
   One value per grain still holds: an array cell is a single value, not many rows. An output variable
   SHALL declare an array result as the bare token `array`; a **parameterized** spelling such as
   `array<string>` SHALL be rejected with HTTP 422 `rule_validation_failed`, and the rejection SHALL name
   `array` as the token to write instead. Aliasing the parameterized form would advertise an element-type
   check that does not happen, and would read as having passed one when bound to a column of a different
   element type.
4. **Entitled.** The expression's referenced entity fields SHALL run the same sensitive-column
   entitlement check as a rule `filter`, whether the field is a read-source column or an enrichment
   column reached through the grain join: a `sensitive` field is admitted only for a caller entitled to
   see sensitive columns, and rejected with HTTP 403 `sensitive_column_not_entitled` otherwise. The
   check governs **who may reference** the field. The derived value SHALL NOT inherit its sensitivity
   and the bound target column's own flag SHALL NOT be changed, exactly as for a read-source column —
   see the "Runner identity and derived-column sensitivity posture" requirement in
   [enrichment-registry](../enrichment-registry/spec.md).
5. **Row-grain trigger only.** A `type=sql` rule SHALL declare `on_ingest` or `schedule`; the `group`
   trigger SHALL be rejected, because a group-grain deterministic rule is an aggregation.
6. **No input bindings.** A `type=sql` rule SHALL declare no input bindings — there is no request to
   render.
7. **At least one output binding.** A `type=sql` rule SHALL bind at least one output column: with none
   there is nothing to materialize, and the rule would silently do no work.
8. **Row-grain target.** The rule's target enrichment SHALL be keyed on the **read source's** declared
   `identity_column` (its scan metadata, see [enrichment-runtime](../enrichment-runtime/spec.md)). A
   Class-1 derivation produces one value per read-source row, so against a coarser grain one window would
   insert many rows carrying the same grain key and the target's `ReplacingMergeTree` would collapse them
   to an arbitrary survivor — a label that is neither wrong nor reproducible. A per-group deterministic
   value is an aggregation, which this capability does not provide. This also means a read source with no
   scan metadata is refused at registration rather than at the first materialize call. Resolving this
   against the read source is what keeps the check meaningful once a rule may read a table other than its
   target enrichment's `source_table`: an enrichment whose grain is coarser than the rows its rule reads
   (a conversation key over rate events, say) stays a `group` rule and is rejected here. The
   `identity_column` SHALL be a column of the read source itself: a Class-1 rule's grain is the row grain
   of the table it pages, which no enrichment can supply.
9. **Enrichment reads are bounded and acyclic.** An expression reading an enrichment column SHALL
   additionally satisfy the entity-read requirements of
   [enrichment-registry](../enrichment-registry/spec.md): the enrichment column SHALL be declared
   `nullable`, the enrichment's materialized position SHALL be establishable, and the reference SHALL
   NOT close a dependency cycle.

Determinism is guaranteed by construction rather than by a denylist: non-deterministic functions such
as `now()`, `rand()`, and `generateUUIDv4()` are absent from the closed function catalog and are
therefore already rejected as unsupported functions. This matters because the exactly-once *effect*
guarantee below depends on re-running a window producing the same values.

#### Scenario: An aggregate expression is rejected

- **WHEN** a `type=sql` rule declares an output-variable expression containing an aggregate (e.g.
  `sum(total_tokens)`)
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: An unknown column in an expression is rejected

- **WHEN** an expression references a column reachable on neither the rule's read source nor its
  `ACTIVE` enrichments
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: An expression reaching outside the rule's read source's entity is rejected

- **WHEN** an expression resolves against an entity other than the rule's read source (for instance by
  supplying its own `FROM` clause ahead of a SQL line comment), or references a column belonging neither
  to that source nor to one of its `ACTIVE` enrichments under the qualified `<enrichment>.<column>`
  spelling
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`, so the
  sensitive-column entitlement check — which resolves referenced fields against the rule's read source's
  entity — is never handed a reference it cannot see

#### Scenario: An expression over an enrichment column of the read source is accepted

- **WHEN** a `type=sql` rule's output expression reads a `nullable` column of an `ACTIVE` enrichment of
  its read source, whose materialized position is establishable and which closes no cycle
- **THEN** the rule is accepted, and the materialize statement emits the grain join for that enrichment

#### Scenario: A non-catalog function in an expression is rejected

- **WHEN** an expression calls a function outside the closed query function catalog (including any
  non-deterministic function such as `now()`)
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: A type-incompatible expression is rejected

- **WHEN** an expression's inferred result type is incompatible with the output variable's declared
  type, or the result is object-typed, or the result is array-typed and the bound target column is not
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: A sensitive reference by an entitled author is accepted

- **WHEN** a `FULL_ADMIN` caller (or any caller in `none` mode) registers a `type=sql` rule whose
  expression reads a `sensitive` read-source column such as `request_body`
- **THEN** the rule is accepted

#### Scenario: A sensitive reference by a non-entitled author is rejected

- **WHEN** a caller who may not see sensitive columns registers the same rule
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`

#### Scenario: A group trigger or input bindings on a sql rule are rejected

- **WHEN** a `type=sql` rule declares `trigger_kind: "group"`, or declares any input binding
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: A sql rule with no output bindings is rejected

- **WHEN** a `type=sql` rule declares no output bindings
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: A sql rule targeting a coarser-than-row grain is rejected

- **WHEN** a `type=sql` rule names a target enrichment whose grain key is not the read source's
  `identity_column` (a conversation key, say, rather than the event identity)
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`, naming the
  identity column the target must be keyed on

#### Scenario: A sql rule keyed on an enrichment column is rejected

- **WHEN** a `type=sql` rule names a target enrichment whose grain key resolves to an enrichment column
  rather than to the read source's own `identity_column`
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed` — a Class-1
  rule's grain is the row grain of the table it pages

#### Scenario: A sql rule declaring a read source is validated against it

- **WHEN** a `type=sql` rule declares a `source` other than its target enrichment's `source_table`, and
  its expressions reference only that source's columns, and the target is keyed on that source's
  `identity_column`
- **THEN** the rule is accepted, and the materialize path derives its values from the declared read source

#### Scenario: An array expression bound to an array column is accepted

- **WHEN** a `type=sql` rule binds an output variable whose expression is
  `json_extract_array(jwt_claims, 'roles')` to a target column of type `array<string>`
- **THEN** the rule is accepted, and materialize writes the extracted array into that column

#### Scenario: An array expression bound to a scalar column is rejected

- **WHEN** the same expression is bound to a `string` target column
- **THEN** the request is rejected with HTTP 422 `rule_validation_failed` naming the array result and
  the scalar target

#### Scenario: A parameterized array type token is rejected with the token to use

- **WHEN** a `type=sql` rule's output variable declares its type as `array<string>`
- **THEN** the request is rejected with HTTP 422 `rule_validation_failed`, and the message names `array` as
  the token to declare instead rather than reporting the spelling as merely unrecognized

#### Scenario: An array element-type mismatch is not caught at registration

- **WHEN** a `type=sql` rule binds an array-producing expression to an array column whose element type
  differs
- **THEN** the rule is accepted — element types are not modelled — and the mismatch surfaces as a
  ClickHouse insert failure on the first materialize call

#### Scenario: An object expression is still rejected

- **WHEN** a `type=sql` rule binds an output variable whose expression yields an `object`
- **THEN** the request is rejected with HTTP 422 `rule_validation_failed`

#### Scenario: A conditional expression is accepted

- **WHEN** a `type=sql` rule binds an output variable whose expression is
  `if(not_empty(json_extract_string(jwt_claims, 'user_id')), 'jwt', 'api_key')`
- **THEN** the rule is accepted — the expression is scalar, single-valued, deterministic, and every
  function is in the closed catalog

### Requirement: Probe-then-write window semantics

A materialize call SHALL choose its window **before** it executes it, in two statements. First a cheap
two-column probe SHALL select the source's `(version, identity)` tuples matching the rule's filter,
the cursor keyset, the safety-lag, any `from`/`to` bound, and the enrichment freshness clamp below,
ordered by that tuple and limited to `limit`; the **last** returned tuple SHALL be the window end, and
`hasMore` SHALL be true exactly when the probe filled its limit. Second, a single `INSERT … SELECT` SHALL
write the derived values for source rows in `(cursorFrom, windowEnd]` only. Both statements SHALL read
with `final=1`, and each SHALL emit the grain join for the enrichments its **own** clauses reference and
no others — so a probe whose filter names no enrichment stays unjoined even when the write's expressions
join one.

**The enrichment freshness clamp.** When the rule's statements join one or more enrichments, the window
SHALL additionally be bounded by the position through which each of those enrichments has been
materialized, taking the lowest when several are joined (see
[enrichment-runtime](../enrichment-runtime/spec.md), which carries the same clamp on the scan window).
Class-1 is the strict case for this bound: the write derives one value per source row and records the
position it wrote through, so a row read while its enrichment cell is still absent is stored with a value
derived from that absence and is never revisited — a permanently wrong value rather than a missing one.
An enrichment that contributes no bound SHALL NOT hold the window back, and a rule joining no enrichment
SHALL be unclamped and its window unchanged.

Because the window is fixed before the write, a call that times out, fails, or is retried SHALL
re-execute an **identical** window. A single ClickHouse `INSERT` is atomic and the target enrichment
is a `ReplacingMergeTree` collapsing by grain, so re-running a window SHALL be a no-op in effect: the
`final=1` result is unchanged. This is the exactly-once *effect* guarantee at window granularity, the
counterpart of the row path's per-grain guarantee.

An empty probe SHALL write nothing, return `rowsWritten: 0`, `hasMore: false`, and echo the input
cursor unchanged, so a caller that advances only on success never skips a window. A window emptied by the
freshness clamp SHALL be reported this way rather than as a failure.

An empty probe ordinarily also records the rule's **drained horizon** — "nothing matching below this
instant minus the safety lag" — which is what releases a filtered rule's dependents from waiting on rows
nothing will ever enrich. That horizon SHALL NOT be recorded when the clamp is what emptied the window.
Under a clamp the claim can be false: rows above the bound match and were withheld, and recording the
horizon would let anything clamping on *this* enrichment advance past rows it never enriched — the same
unbounded read the clamp denied, one level down and equally unrecoverable.

Whether the clamp withheld anything SHALL be **established, not inferred** — by re-probing the same window
with the clamp lifted, one row being enough to answer it. Inferring it from where the bound sits relative to
the safety-lag horizon is wrong in the common direction: a source whose newest row is hours old puts every
bound below that instant, so a clamped rule would never record a drained horizon again even when genuinely
caught up, silently withdrawing the mechanism that releases a *filtered* rule's dependents from waiting on
rows nothing will ever enrich. A clamped rule that really has nothing left below its bound SHALL record the
horizon exactly as an unclamped one does.

The write SHALL stamp the provenance columns the target physically carries — `evaluator_version` with
the rule's resolved pinned version and `enriched_at` with the write time — and SHALL skip any provenance
column the target lacks, matching the write-back path's best-effort lineage. It SHALL also skip every
provenance column a Class-1 rule cannot describe, each for a structural reason rather than a missing
value: `model` (a `sql` evaluator makes no model call), `group_version` and `truncated` (governance
forbids a `sql` evaluator the `group` trigger, and a set-based window is never trimmed). The version
column `_updated_at` SHALL be stamped by ClickHouse, never by ADAS.

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

#### Scenario: The window is clamped to a referenced enrichment's position

- **WHEN** a `type=sql` rule whose expressions read an enrichment column is materialized while source
  rows exist above that enrichment's recorded materialize position
- **THEN** those rows are outside the window, no value is written for them, the returned cursor does not
  advance past that position, and a later call writes them once the enrichment has reached them

#### Scenario: A window emptied by the clamp echoes the cursor

- **WHEN** the referenced enrichment has been materialized no further than the rule's current cursor
- **THEN** the response is `rowsWritten: 0`, `hasMore: false`, and `nextCursor` equal to the input
  cursor, rather than a failure

#### Scenario: A window emptied by the clamp does not record the rule as drained

- **WHEN** the same call returns its empty window
- **THEN** the rule's drained horizon is left unmoved, so a reader clamping on this rule's target
  enrichment is not released past the source rows the clamp withheld

#### Scenario: A clamped rule that is genuinely caught up still records the horizon

- **WHEN** a clamped rule's window is empty and no row matches above its cursor even with the clamp lifted
- **THEN** the drained horizon is recorded, exactly as for an unclamped rule, because the emptiness is the
  absence of matching rows rather than the clamp

#### Scenario: A rule joining no enrichment is unclamped

- **WHEN** a `type=sql` rule whose expressions read only its read source's own columns is materialized
- **THEN** the window is bounded only by the cursor, the safety-lag, and any requested bounds, exactly
  as before

#### Scenario: A probe naming no enrichment stays unjoined

- **WHEN** a `type=sql` rule's `filter_sql` references no enrichment column while its output expressions
  reference one
- **THEN** the probe statement emits no join and the `INSERT … SELECT` emits the grain join for that
  enrichment

#### Scenario: Provenance absent from the target is skipped, not failed

- **WHEN** the target enrichment does not physically carry `enriched_at` or `evaluator_version`
- **THEN** those columns are omitted from the write and the write still succeeds

#### Scenario: Provenance a sql rule cannot describe is skipped

- **WHEN** the target enrichment physically carries `model`, `group_version`, or `truncated`
- **THEN** those columns are omitted from the materialize write and the write still succeeds
