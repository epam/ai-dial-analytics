## RENAMED Requirements

- FROM: `### Requirement: A materialize records the position it wrote through`
- TO: `### Requirement: A materialize records the position it wrote through, against its pipeline`

## MODIFIED Requirements

### Requirement: SQL-computed evaluators

The system SHALL support evaluators of `type: "sql"` — deterministic, model-free derivations computed
inside ClickHouse. For a `sql` evaluator the system SHALL require `model`, `preset`, `params`,
`request_template`, `input_vars`, and `response_schema` to be **absent**, and SHALL require every
entry of `output_vars` to carry a non-blank `sql` expression and a logical `type`. (`params` shape the
preset's request, so they are as preset-specific as the preset itself; accepting them on a `sql`
evaluator would store a declaration that nothing reads.) For an `llm`
evaluator `model` and `preset` SHALL remain required, unchanged from the original registry. A `sql` evaluator's
`output_vars` SHALL NOT carry `jsonata` (there is no model response to map).

Evaluator versioning is unchanged: a `sql` evaluator version is immutable, and editing an expression
mints a new `evaluator_version`, which is what makes re-running a backfill over history meaningful.

Expressions SHALL NOT be validated at evaluator registration, because an evaluator does not name a
source; they are validated at **pipeline** registration against the source derived from the pipeline's target
enrichment.

#### Scenario: A sql evaluator registers without model, preset, or template

- **WHEN** `POST /v1/evaluators` is called with `type: "sql"`, no `model`/`preset`/`request_template`/
  `response_schema`, and output variables each carrying a `sql` expression and a `type`
- **THEN** the evaluator version is created and HTTP 201 is returned

#### Scenario: A sql evaluator carrying LLM-only fields is rejected

- **WHEN** `POST /v1/evaluators` is called with `type: "sql"` together with any of `model`, `preset`,
  `params`, `request_template`, `input_vars`, or `response_schema`
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: A sql evaluator with an output variable lacking an expression is rejected

- **WHEN** `POST /v1/evaluators` is called with `type: "sql"` and an output variable whose `sql` is
  blank or absent
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: An llm evaluator still requires model and preset

- **WHEN** `POST /v1/evaluators` is called with `type: "llm"` and a missing `model` or `preset`
- **THEN** the request is rejected and the existing behaviour is unchanged


### Requirement: Class-1 expression governance

The system SHALL statically validate every `sql` output-variable expression at pipeline register and
enable, against the **entity** of the pipeline's read source — its declared `source` when it has one,
otherwise the target enrichment's `source_table` — where that entity comprises the read source's own
declared columns plus the columns of each of its `ACTIVE` enrichments under the qualified flat name
`<enrichment>.<column>` (see [enrichment-registry](../enrichment-registry/spec.md)). Each
failure SHALL be rejected with HTTP 422 `pipeline_validation_failed` carrying the failing detail.
Validation SHALL wrap the expression as `SELECT <expr> FROM <source>` through the **same** Calcite SQL
front end used for declaration filters, so the bounded grammar (no joins, subqueries, or CTEs), the closed
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
   `array<string>` SHALL be rejected with HTTP 422 `pipeline_validation_failed`, and the rejection SHALL name
   `array` as the token to write instead. Aliasing the parameterized form would advertise an element-type
   check that does not happen, and would read as having passed one when bound to a column of a different
   element type.
4. **Entitled.** The expression's referenced entity fields SHALL run the same sensitive-column
   entitlement check as a pipeline `filter`, whether the field is a read-source column or an enrichment
   column reached through the grain join: a `sensitive` field is admitted only for a caller entitled to
   see sensitive columns, and rejected with HTTP 403 `sensitive_column_not_entitled` otherwise. The
   check governs **who may reference** the field. The derived value SHALL NOT inherit its sensitivity
   and the bound target column's own flag SHALL NOT be changed, exactly as for a read-source column —
   see the "Runner identity and derived-column sensitivity posture" requirement in
   [enrichment-registry](../enrichment-registry/spec.md).
5. **Row-grain trigger only.** A `type=sql` pipeline SHALL declare `on_ingest` or `schedule`; the `group`
   trigger SHALL be rejected, because a group-grain deterministic pipeline is an aggregation.
6. **No input bindings.** A `type=sql` pipeline SHALL declare no input bindings — there is no request to
   render.
7. **At least one output binding.** A `type=sql` pipeline SHALL bind at least one output column: with none
   there is nothing to materialize, and the pipeline would silently do no work.
8. **Row-grain target.** The pipeline's target enrichment SHALL be keyed on the **read source's** declared
   `identity_column` (its scan metadata, see [enrichment-runtime](../enrichment-runtime/spec.md)). A
   Class-1 derivation produces one value per read-source row, so against a coarser grain one window would
   insert many rows carrying the same grain key and the target's `ReplacingMergeTree` would collapse them
   to an arbitrary survivor — a label that is neither wrong nor reproducible. A per-group deterministic
   value is an aggregation, which this capability does not provide. This also means a read source with no
   scan metadata is refused at registration rather than at the first materialize call. Resolving this
   against the read source is what keeps the check meaningful once a pipeline may read a table other than its
   target enrichment's `source_table`: an enrichment whose grain is coarser than the rows its pipeline reads
   (a conversation key over rate events, say) stays a `group` pipeline and is rejected here. The
   `identity_column` SHALL be a column of the read source itself: a Class-1 pipeline's grain is the row grain
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

- **WHEN** a `type=sql` pipeline declares an output-variable expression containing an aggregate (e.g.
  `sum(total_tokens)`)
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: An unknown column in an expression is rejected

- **WHEN** an expression references a column reachable on neither the pipeline's read source nor its
  `ACTIVE` enrichments
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: An expression reaching outside the pipeline's read source's entity is rejected

- **WHEN** an expression resolves against an entity other than the pipeline's read source (for instance by
  supplying its own `FROM` clause ahead of a SQL line comment), or references a column belonging neither
  to that source nor to one of its `ACTIVE` enrichments under the qualified `<enrichment>.<column>`
  spelling
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`, so the
  sensitive-column entitlement check — which resolves referenced fields against the pipeline's read source's
  entity — is never handed a reference it cannot see

#### Scenario: An expression over an enrichment column of the read source is accepted

- **WHEN** a `type=sql` pipeline's output expression reads a `nullable` column of an `ACTIVE` enrichment of
  its read source, whose materialized position is establishable and which closes no cycle
- **THEN** the pipeline is accepted, and the materialize statement emits the grain join for that enrichment

#### Scenario: A non-catalog function in an expression is rejected

- **WHEN** an expression calls a function outside the closed query function catalog (including any
  non-deterministic function such as `now()`)
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: A type-incompatible expression is rejected

- **WHEN** an expression's inferred result type is incompatible with the output variable's declared
  type, or the result is object-typed, or the result is array-typed and the bound target column is not
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: A sensitive reference by an entitled author is accepted

- **WHEN** a `FULL_ADMIN` caller (or any caller in `none` mode) registers a `type=sql` pipeline whose
  expression reads a `sensitive` read-source column such as `request_body`
- **THEN** the pipeline is accepted

#### Scenario: A sensitive reference by a non-entitled author is rejected

- **WHEN** a caller who may not see sensitive columns registers the same declaration
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`

#### Scenario: A group trigger or input bindings on a sql pipeline are rejected

- **WHEN** a `type=sql` pipeline declares `trigger_kind: "group"`, or declares any input binding
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: A sql pipeline with no output bindings is rejected

- **WHEN** a `type=sql` pipeline declares no output bindings
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: A sql pipeline targeting a coarser-than-row grain is rejected

- **WHEN** a `type=sql` pipeline names a target enrichment whose grain key is not the read source's
  `identity_column` (a conversation key, say, rather than the event identity)
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`, naming the
  identity column the target must be keyed on

#### Scenario: A sql pipeline keyed on an enrichment column is rejected

- **WHEN** a `type=sql` pipeline names a target enrichment whose grain key resolves to an enrichment column
  rather than to the read source's own `identity_column`
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed` — a Class-1
  pipeline's grain is the row grain of the table it pages

#### Scenario: A sql pipeline declaring a read source is validated against it

- **WHEN** a `type=sql` pipeline declares a `source` other than its target enrichment's `source_table`, and
  its expressions reference only that source's columns, and the target is keyed on that source's
  `identity_column`
- **THEN** the pipeline is accepted, and the materialize path derives its values from the declared read source

#### Scenario: An array expression bound to an array column is accepted

- **WHEN** a `type=sql` pipeline binds an output variable whose expression is
  `json_extract_array(jwt_claims, 'roles')` to a target column of type `array<string>`
- **THEN** the pipeline is accepted, and materialize writes the extracted array into that column

#### Scenario: An array expression bound to a scalar column is rejected

- **WHEN** the same expression is bound to a `string` target column
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the array result and
  the scalar target

#### Scenario: A parameterized array type token is rejected with the token to use

- **WHEN** a `type=sql` pipeline's output variable declares its type as `array<string>`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed`, and the message names `array` as
  the token to declare instead rather than reporting the spelling as merely unrecognized

#### Scenario: An array element-type mismatch is not caught at registration

- **WHEN** a `type=sql` pipeline binds an array-producing expression to an array column whose element type
  differs
- **THEN** the pipeline is accepted — element types are not modelled — and the mismatch surfaces as a
  ClickHouse insert failure on the first materialize call

#### Scenario: An object expression is still rejected

- **WHEN** a `type=sql` pipeline binds an output variable whose expression yields an `object`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed`

#### Scenario: A conditional expression is accepted

- **WHEN** a `type=sql` pipeline binds an output variable whose expression is
  `if(not_empty(json_extract_string(jwt_claims, 'user_id')), 'jwt', 'api_key')`
- **THEN** the pipeline is accepted — the expression is scalar, single-valued, deterministic, and every
  function is in the closed catalog


### Requirement: Set-based materialize endpoint

The system SHALL expose `POST /v1/pipelines/{name}/materialize` accepting a body
`{cursor?, limit?, from?, to?}` and returning `{rowsWritten, nextCursor, hasMore, lagSeconds}`. The
`cursor` is the caller-held opaque token from a prior window, with the same encoding, verification, and
statelessness as the scan's: ADAS signs and decodes it but holds no per-caller position, so the caller
persists it and advances **only on a successful response**. It SHALL resolve the pipeline's source,
filter, grain, expressions, and target enrichment from the stored pipeline; the caller SHALL NOT supply
SQL, a table name, a filter, or a column list, and **no row payloads SHALL cross the wire in either
direction** — the source data, including any sensitive body column, never leaves ClickHouse.

The endpoint SHALL be authorized by the pipeline's **target-enrichment access, write flavour** — the same
grant as `POST /v1/pipelines/{name}/rows`, because it writes. A caller lacking it SHALL be rejected with
HTTP 403 `pipeline_access_denied`. Independently of that grant, the sensitive-column rule SHALL be
re-applied against the **calling** identity over the columns the pipeline's expressions and filter
reference, and a non-entitled caller SHALL be rejected with HTTP 403
`sensitive_column_not_entitled` — so a non-`FULL_ADMIN` runner cannot cause sensitive data to be read
and derived even though no body is returned to it.

Because a `sensitive` column is *hidden* from a non-entitled caller's schema view, such a caller cannot
parse an expression that reads one: validation would fail first with an unknown-column error. The system
SHALL NOT surface that as a 422 "invalid expression"; when a governed-expression validation fails for a
caller who may not read the source's sensitive columns, it SHALL be reported as HTTP 403
`sensitive_column_not_entitled` instead. An entitled caller's validation failure SHALL be reported
accurately, so a genuinely broken declaration (e.g. a source column dropped since registration) still reaches
the caller who can act on it.

`from` and `to` SHALL carry the same shape and `(from, to]` semantics as the scan's bounded window
(see [enrichment-runtime](../enrichment-runtime/spec.md)); `limit` SHALL bound the window's row count,
with a configured default when omitted and a configured maximum above which the request is rejected.
An unknown pipeline SHALL return HTTP 404 `unknown_pipeline`. Calling `materialize` on a `type=llm` pipeline, or
calling the row scan on a `type=sql` pipeline, SHALL be rejected with HTTP 422
`pipeline_validation_failed` — the two execution paths are not interchangeable.

#### Scenario: Materialize writes the window and reports the count

- **WHEN** `POST /v1/pipelines/{name}/materialize` is called for an enabled or paused `type=sql` pipeline with
  matching source rows below the safety-lag horizon
- **THEN** the derived values are written to the target enrichment and the response reports
  `rowsWritten`, an advanced `nextCursor`, `hasMore`, and `lagSeconds`

#### Scenario: Materialize returns no rows to the caller

- **WHEN** any materialize call succeeds
- **THEN** the response carries counts and cursor bookkeeping only, and never source or derived row
  contents

#### Scenario: Materialize on an LLM pipeline is rejected

- **WHEN** `POST /v1/pipelines/{name}/materialize` is called for a pipeline whose evaluator is `type=llm`
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: Scan on a SQL pipeline is rejected

- **WHEN** `POST /v1/pipelines/{name}/scan` is called for a pipeline whose evaluator is `type=sql`
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: A non-entitled caller cannot materialize a sensitive-reading pipeline

- **WHEN** a caller holding the target enrichment's grant but not entitled to see sensitive columns calls
  `materialize` for a pipeline whose expression reads a `sensitive` source column
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`, not with
  a 422 about an invalid expression

#### Scenario: An entitled caller may materialize the same pipeline

- **WHEN** a `FULL_ADMIN` caller (or any caller in `none` mode) calls the same `materialize`
- **THEN** the derivation runs normally

#### Scenario: A bounded window materializes only its range

- **WHEN** `materialize` is called with `from` and `to`
- **THEN** only source rows whose version is inside `(from, to]` contribute to the write


### Requirement: Probe-then-write window semantics

A materialize call SHALL choose its window **before** it executes it, in two statements. First a cheap
two-column probe SHALL select the source's `(version, identity)` tuples matching the pipeline's filter,
the cursor keyset, the safety-lag, any `from`/`to` bound, and the enrichment freshness clamp below,
ordered by that tuple and limited to `limit`; the **last** returned tuple SHALL be the window end, and
`hasMore` SHALL be true exactly when the probe filled its limit. Second, a single `INSERT … SELECT` SHALL
write the derived values for source rows in `(cursorFrom, windowEnd]` only. Both statements SHALL read
with `final=1`, and each SHALL emit the grain join for the enrichments its **own** clauses reference and
no others — so a probe whose filter names no enrichment stays unjoined even when the write's expressions
join one.

**The enrichment freshness clamp.** When the pipeline's statements join one or more enrichments, the window
SHALL additionally be bounded by the position through which each of those enrichments has been
materialized, taking the lowest when several are joined (see
[enrichment-runtime](../enrichment-runtime/spec.md), which carries the same clamp on the scan window).
Class-1 is the strict case for this bound: the write derives one value per source row and records the
position it wrote through, so a row read while its enrichment cell is still absent is stored with a value
derived from that absence and is never revisited — a permanently wrong value rather than a missing one.
An enrichment that contributes no bound SHALL NOT hold the window back, and a pipeline joining no enrichment
SHALL be unclamped and its window unchanged.

Because the window is fixed before the write, a call that times out, fails, or is retried SHALL
re-execute an **identical** window. A single ClickHouse `INSERT` is atomic and the target enrichment
is a `ReplacingMergeTree` collapsing by grain, so re-running a window SHALL be a no-op in effect: the
`final=1` result is unchanged. This is the exactly-once *effect* guarantee at window granularity, the
counterpart of the row path's per-grain guarantee.

An empty probe SHALL write nothing, return `rowsWritten: 0`, `hasMore: false`, and echo the input
cursor unchanged, so a caller that advances only on success never skips a window. A window emptied by the
freshness clamp SHALL be reported this way rather than as a failure.

An empty probe ordinarily also records the pipeline's **drained horizon** — "nothing matching below this
instant minus the safety lag" — which is what releases a filtered pipeline's dependents from waiting on rows
nothing will ever enrich. That horizon SHALL NOT be recorded when the clamp is what emptied the window.
Under a clamp the claim can be false: rows above the bound match and were withheld, and recording the
horizon would let anything clamping on *this* enrichment advance past rows it never enriched — the same
unbounded read the clamp denied, one level down and equally unrecoverable.

Whether the clamp withheld anything SHALL be **established, not inferred** — by re-probing the same window
with the clamp lifted, one row being enough to answer it. Inferring it from where the bound sits relative to
the safety-lag horizon is wrong in the common direction: a source whose newest row is hours old puts every
bound below that instant, so a clamped pipeline would never record a drained horizon again even when genuinely
caught up, silently withdrawing the mechanism that releases a *filtered* pipeline's dependents from waiting on
rows nothing will ever enrich. A clamped pipeline that really has nothing left below its bound SHALL record the
horizon exactly as an unclamped one does.

The write SHALL stamp the provenance columns the target physically carries — `evaluator_version` with
the pipeline's resolved pinned version and `enriched_at` with the write time — and SHALL skip any provenance
column the target lacks, matching the write-back path's best-effort lineage. It SHALL also skip every
provenance column a Class-1 pipeline cannot describe, each for a structural reason rather than a missing
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

- **WHEN** a `type=sql` pipeline whose expressions read an enrichment column is materialized while source
  rows exist above that enrichment's recorded materialize position
- **THEN** those rows are outside the window, no value is written for them, the returned cursor does not
  advance past that position, and a later call writes them once the enrichment has reached them

#### Scenario: A window emptied by the clamp echoes the cursor

- **WHEN** the referenced enrichment has been materialized no further than the pipeline's current cursor
- **THEN** the response is `rowsWritten: 0`, `hasMore: false`, and `nextCursor` equal to the input
  cursor, rather than a failure

#### Scenario: A window emptied by the clamp does not record the pipeline as drained

- **WHEN** the same call returns its empty window
- **THEN** the pipeline's drained horizon is left unmoved, so a reader clamping on this pipeline's target
  enrichment is not released past the source rows the clamp withheld

#### Scenario: A clamped pipeline that is genuinely caught up still records the horizon

- **WHEN** a clamped pipeline's window is empty and no row matches above its cursor even with the clamp lifted
- **THEN** the drained horizon is recorded, exactly as for an unclamped pipeline, because the emptiness is the
  absence of matching rows rather than the clamp

#### Scenario: A pipeline joining no enrichment is unclamped

- **WHEN** a `type=sql` pipeline whose expressions read only its read source's own columns is materialized
- **THEN** the window is bounded only by the cursor, the safety-lag, and any requested bounds, exactly
  as before

#### Scenario: A probe naming no enrichment stays unjoined

- **WHEN** a `type=sql` pipeline's `filter` references no enrichment column while its output expressions
  reference one
- **THEN** the probe statement emits no join and the `INSERT … SELECT` emits the grain join for that
  enrichment

#### Scenario: Provenance absent from the target is skipped, not failed

- **WHEN** the target enrichment does not physically carry `enriched_at` or `evaluator_version`
- **THEN** those columns are omitted from the write and the write still succeeds

#### Scenario: Provenance a sql pipeline cannot describe is skipped

- **WHEN** the target enrichment physically carries `model`, `group_version`, or `truncated`
- **THEN** those columns are omitted from the materialize write and the write still succeeds


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


### Requirement: A materialize records the position it wrote through, against its pipeline

A materialize that writes a window SHALL record, against the pipeline it ran for, the position it wrote
through — the same `(version, identity)` tuple it returns as the caller's next cursor. The record SHALL be
monotonic: a call SHALL NOT lower it.

ADAS is the writer on this path and the write is synchronous, so this is a fact ADAS observes rather than
one it is told. When the `INSERT … SELECT` returns, the derived rows are in ClickHouse and the probed
window's end is the position through which the enrichment is complete. It is recorded because a consumer
that aggregates the enrichment alongside its source needs to know how far it may safely read, and the
caller's own cursor cannot answer that: the caller persists a position it was handed, and for the row path
the equivalent position marks work that has been queued rather than written.

A materialize that finds no rows SHALL record instead that the pipeline was **drained**, as of the moment it
looked. That is a distinct fact from the position: a pipeline declaring a filter advances its position only to
its last matching source row, so without the drained signal a consumer could not tell "there is more to
come" from "there is nothing left that this pipeline will ever cover".

The position SHALL be recorded only where a window was actually probed and written. An empty window echoes
the **caller's** cursor by contract, and recording that would persist what the caller asserted rather than
what ADAS observed.

A materialize carrying a `from`/`to` bound SHALL record neither the position nor the drained signal, and
SHALL instead record that the pipeline's history was re-derived, as of that moment. Such a call rewrites values
for source rows that consumers may already have read, which is a different fact from progress and would
otherwise drag the recorded position backwards.

A materialize that writes a window ending **at or below the position the pipeline is already complete through**
SHALL also record that the pipeline's history was re-derived, whether or not it carried a bound. The bound is
not a sufficient discriminator on its own: a **full replay** carries a cursor and no window, so no field of
the request separates it from a live tick — and it is the default shape of a backfill, and the way an
evaluator-expression bump is re-applied to history. What separates the two is the effect rather than the
request. A live tick writes rows the pipeline has never been complete through; a replay rewrites rows it has,
and "complete through" is exactly the position a dependent consumer was allowed to read past — the later of
what was written and where the pipeline was last found drained.

This SHALL be evaluated against the position as it stood **before** the call's own record was applied, and
a pipeline whose mark is absent (never written, or recorded under a superseded generation) SHALL NOT be treated
as re-deriving: nothing was declared complete, so nothing can have been read past. One false positive is
accepted rather than designed away — a live tick whose response was lost re-executes an identical window on
the next fire, which is indistinguishable from a replay of one page and is reported as a re-derivation. A
rare spurious rebuild instruction is the safe direction against a systematic miss on the default backfill
shape.

The recorded **position and drained horizon** SHALL be qualified by the pipeline's `generation` at the time they
were written, and SHALL be treated as absent once that generation has moved. A pipeline mutation can change what
a recorded position means, and one case makes it meaningless rather than merely stale: repointing the read
source's `version_column` changes the axis the tuple is expressed on. That repoint already bumps the
generation of every pipeline reading the source, so the qualification is exact rather than approximate.

The recorded **re-derivation** SHALL NOT be qualified that way, and SHALL survive a pipeline mutation. It is a
fact about values that were already published to consumers, not about a position on an axis: editing the
declaration edit does not make a rollup that already aggregated the re-derived values correct again, so discarding the
record on a mutation would clear a warning that is still owed.

#### Scenario: A written window records its end position

- **WHEN** a materialize call writes a window
- **THEN** the position it wrote through is recorded against the pipeline, and a later call that writes a
  further window advances it

#### Scenario: An empty window records drained, not a position

- **WHEN** a materialize call's probe finds no rows above the cursor and below the safety-lag horizon
- **THEN** the pipeline is recorded as drained as of that moment, and the echoed cursor is not recorded as a
  position

#### Scenario: A backfill records a re-derivation instead of progress

- **WHEN** a materialize call carries a `from`/`to` bound
- **THEN** the pipeline's history is recorded as re-derived, and neither the position nor the drained signal
  is recorded

#### Scenario: An unwindowed replay is recognised by what it wrote

- **GIVEN** a pipeline already complete through some position
- **WHEN** a materialize call carrying a cursor and no `from`/`to` bound writes a window ending at or below
  that position
- **THEN** the pipeline's history is recorded as re-derived, and the recorded position is unchanged

#### Scenario: A recorded position does not go backwards

- **WHEN** a materialize call is replayed at an earlier cursor and writes an already-written window
- **THEN** the recorded position is unchanged rather than lowered, and the re-derivation is recorded
  alongside it

#### Scenario: A pipeline mutation invalidates the record

- **GIVEN** a pipeline with a recorded position
- **WHEN** the pipeline is mutated, or its read source's `version_column` is repointed
- **THEN** the record is treated as absent until the next materialize establishes it again


### Requirement: Class-1 derivations may read map and array source columns

The map and array accessors SHALL be available to a `sql` output-variable expression on the same terms
they are available to a query: `map_value`, `map_contains` and `map_keys` over a `map` source column,
`array_has`/`array_has_any`/`array_has_all`/`array_length` over an `array` source column, and
`split_string` over a text column. This follows from validation running through the same Calcite front
end and the same closed function catalog as the read path.

This is what makes a `map` or `array` source column derivable at all. Previously both types were
projection-only in every position, so no Class-1 expression could read one, and a dimension carried
inside a map could only be projected — never lifted into a scalar, grain-bound column. Lifting a map
key into a typed enrichment column is now expressible, which is the supported route to giving such a
key its own `sensitive` flag, its own type, and its own place in a `trigger.group_by`: whole-column sensitivity
on the map cannot distinguish one key from another.

The governance pipelines SHALL apply unchanged. `map_value` returns text, `map_contains` boolean, and
`array_has*`/`array_length` scalars, so all six satisfy the scalar-not-aggregate and
single-projected-value pipelines and bind to a scalar-typed output variable. `map_keys` and `split_string`
return arrays, so each SHALL be accepted only when bound to an array-typed target column, under the
existing array-result declaration, and their element type SHALL remain unchecked for the same reason every
computed array's is.

A `map`- or `array`-typed **result** SHALL remain rejected: the accessors exist to read those columns,
not to write them. An expression that projects a bare `map` column SHALL continue to fail validation,
as an `object`-typed result does.

#### Scenario: Map key lifted into a scalar enrichment column

- **WHEN** a `sql` evaluator declares an output expression calling `map_value` on a `map` source column
  with a literal key, bound to a `string`-typed output variable
- **THEN** the pipeline registers, the expression passing the scalar, single-value and type-compatibility
  gates

#### Scenario: Array membership derives a boolean enrichment column

- **WHEN** a `sql` evaluator declares an `array_has` call over an `array` source column bound to a
  `boolean`-typed output variable
- **THEN** the pipeline registers

#### Scenario: Map key presence derives a boolean enrichment column

- **WHEN** a `sql` evaluator declares a `map_contains` call on a `map` source column with a literal key,
  bound to a `boolean`-typed output variable
- **THEN** the pipeline registers

#### Scenario: Map keys bind only to an array column

- **WHEN** a `sql` evaluator declares a `map_keys` call bound to an `array`-typed output variable, and a
  second declares it bound to a `string`-typed one
- **THEN** the first registers and the second is rejected with HTTP 422 `pipeline_validation_failed`

#### Scenario: Split result binds only to an array column

- **WHEN** a `sql` evaluator declares a `split_string` call bound to an `array`-typed output variable,
  and a second declares the same expression bound to a `string`-typed one
- **THEN** the first registers and the second is rejected with HTTP 422 `pipeline_validation_failed`

#### Scenario: A bare map column is still not a derivable result

- **WHEN** a `sql` evaluator declares an output expression that is a bare `map` column reference
- **THEN** the pipeline is rejected with HTTP 422 `pipeline_validation_failed` — the accessors read a map, they
  do not make one bindable

