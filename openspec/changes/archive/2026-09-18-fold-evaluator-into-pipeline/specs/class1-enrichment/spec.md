## RENAMED Requirements

- FROM: `### Requirement: SQL-computed evaluators`
- TO: `### Requirement: SQL-computed transforms`

## MODIFIED Requirements

### Requirement: SQL-computed transforms

The system SHALL support an `enrich` pipeline whose `transform` declares `type: "sql"` — a deterministic,
model-free derivation computed inside ClickHouse. For a `sql` transform the system SHALL require
`model`, `preset`, `params` and `request_template` to be **absent**, and SHALL require each entry of
`outputs` to be a non-blank SQL expression string, keyed by the target column that receives it.
(`params` shape the preset's request, so they are as preset-specific as the preset itself; accepting
them on a `sql` transform would store a declaration that nothing reads.) For an `llm` transform `model`
SHALL remain required and `preset` SHALL default to `chat_completion`, unchanged in effect. A `sql`
transform's `outputs` SHALL NOT carry `jsonata` or `values` — there is no model response to map and no
schema to constrain — which follows from the value being a bare string rather than an object.

**A per-output logical `type` SHALL NOT be declared**, and the two type checks it sat between SHALL
collapse into one: the expression's **inferred** type is checked directly against the bound target
column's type, under the shared enrichment type mapping. The declared token was the redundant middle
hop of `inferred ~ declared` and `declared ~ column`, and the collapse SHALL be understood to be
**one-directional**: compatibility is equality, or both types numeric, or `string` → `enum`, and over
those three rules it is transitive — so every declaration the two-hop check accepts today the single
check still accepts. The collapse can only *loosen*: a declaration whose declared token disagreed
with its own expression was refused at the first hop and is now accepted, which is the point, since
the token carried no information the two ends did not already carry.

The inferred type SHALL come from the same SQL front end the expression is validated by — the
expression wrapped as a single-column `SELECT` over the read source, translated, and its result kind
mapped to a logical type. Because the transform now lives on the pipeline, a source is **always** in
hand wherever an expression is read, so the inferred type SHALL be available at every such point:
registration, patch, read and the materialize call alike. This removes the deferral the separate
evaluator forced, where an expression could be stored unvalidated because the object holding it named
no source.

An **`array`** result SHALL continue to pass on shape alone: an inferred `array` against a column
declared `array` is compatible by equality, and the column's `element_type` SHALL deliberately NOT be
compared, because the inferred type carries no element type. Dropping the declared token changes
neither half of that, and an array whose elements do not match the column's `element_type` is still
refused by ClickHouse at insert rather than at registration.

**Expressions SHALL live on the pipeline**, and the cost objection that previously kept them on a
separate evaluator SHALL be understood to have been mispriced. That objection held that editing an
expression would bump the pipeline's `generation`, which discards its materialized position and holds
every consumer of its target enrichment back behind the freshness clamp. Both halves are true; the
missing term is *for how long*. The runner holds the scan cursor, and a `generation` bump does not
touch it — so the next materialize resumes at the same cursor and re-records the position at the end of
the window it writes, one cadence tick later. The stall is therefore **one materialize cycle**, not a
replay from the start of the source. A replay from the start happens only when the **cursor** is lost,
which no declaration edit can cause. Against that bounded cost stands the deferral this removes: an
expression that names a column its source does not have is now refused where it is written.

Editing an expression SHALL NOT re-derive already-written rows, exactly as minting a new evaluator
version did not. Old rows keep the values the previous expression produced; re-deriving history remains
an explicit bounded materialize (see "A materialize records the position it wrote through, against its
pipeline").

Per-row provenance SHALL be preserved by `pipeline_generation`, the permitted provenance column
carrying the `generation` under which a row was derived (see
[enrichment-runtime](../enrichment-runtime/spec.md), "Pipeline-scoped versioned write-back endpoint").
It supersedes `evaluator_version` for rows written after the fold, and SHALL be understood as a
**finer** partition rather than a coarser one: `evaluator_version` moved only when the expression
changed and was blind to a `filter` change that altered which rows were derived at all, while
`generation` moves for either.

The **materialize path SHALL stamp it itself**, inside its `INSERT … SELECT`, where it stamps
`evaluator_version` today — and SHALL stop stamping `evaluator_version`, whose only remaining value
would be the pipeline's generation under an old column name. The stamp SHALL be dropped silently when
the target does not physically carry the column, exactly as the three provenance columns a Class-1
target can never populate are, so a target created before the column existed keeps materializing
until an operator adds it (see [table-management](../table-management/spec.md), "Provenance columns
are injected into an enrichment's schema definition").

#### Scenario: A sql transform is declared on the pipeline

- **WHEN** `POST /v1/pipelines` is called with `kind: "enrich"` and a `transform` carrying
  `type: "sql"`, no `model`/`preset`/`request_template`/`params`, and `outputs` mapping each target
  column name to a non-blank SQL expression
- **THEN** the pipeline is created and HTTP 201 is returned

#### Scenario: A sql transform carrying LLM-only fields is rejected

- **WHEN** `POST /v1/pipelines` is called with `transform.type: "sql"` together with any of `model`,
  `preset`, `params` or `request_template` inside the block
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: A sql transform with a blank output expression is rejected

- **WHEN** `POST /v1/pipelines` is called with `transform.type: "sql"` and an `outputs` value that is
  blank
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: An llm transform still requires a model

- **WHEN** `POST /v1/pipelines` is called with `transform.type: "llm"` and a missing `model`
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: A folded sql transform keeps its expressions in output_vars

- **WHEN** a Class-1 pipeline's transform was folded from an evaluator version registered before the
  `outputs` shape, so its expressions live in `transform.output_vars`
- **THEN** the expressions are read through the single accessor, validated and materialized exactly as
  `transform.outputs` expressions are, and the pipeline is not refused for declaring no `outputs`

#### Scenario: An expression naming an unknown source column is rejected where it is written

- **WHEN** `POST /v1/pipelines` or `PATCH /v1/pipelines/{name}` declares a `sql` transform whose
  expression references a column the pipeline's read source does not have
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the expression and
  the column, at declaration time rather than at the next materialize

#### Scenario: An expression's inferred type is checked against the target column

- **WHEN** a Class-1 pipeline declares an `outputs` expression the front end infers as `string`, bound
  to a target column declared `enum`
- **THEN** the pipeline is accepted, by the single check `inferred ~ column`, with no declared token
  in between

#### Scenario: An array expression passes on shape alone

- **WHEN** an `outputs` expression the front end infers as `array` binds to a target column declared
  `array` with `element_type: string`
- **THEN** the pipeline is accepted, the element type is not compared, and a value whose elements do
  not match is refused by ClickHouse at insert

#### Scenario: An object-typed expression is still rejected

- **WHEN** an `outputs` expression resolves to an `object` result
- **THEN** the request is rejected with HTTP 422, because such a result is projection-only in the SQL
  subset and cannot be collapsed into a column bound to a grain

#### Scenario: A type-incompatible expression is rejected

- **WHEN** an `outputs` expression the front end infers as `timestamp` binds to a target column
  declared `enum`
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: Editing an expression stalls consumers for one cycle, not a replay

- **WHEN** `PATCH /v1/pipelines/{name}` changes a Class-1 transform's expression, bumping `generation`
- **THEN** the pipeline's materialized position is discarded, every consumer of its target enrichment
  is clamped, and the next materialize — resuming at the runner-held cursor, which the bump did not
  touch — re-records a position and releases the clamp
- **AND** no already-written row is re-derived

#### Scenario: A row records the generation that derived it

- **WHEN** a Class-1 materialize writes rows and the target enrichment carries `pipeline_generation`
- **THEN** each written row carries the pipeline's `generation` at the time of the write, and
  `evaluator_version` is not stamped

#### Scenario: A target without the column still materializes

- **WHEN** a Class-1 materialize writes rows to a target enrichment created before
  `pipeline_generation` was injected
- **THEN** the stamp is skipped, the derived columns are written, and the materialize succeeds

### Requirement: Class-1 expression governance

The system SHALL statically validate every `sql` output-variable expression at pipeline **register,
declaration patch, read and enable** alike — the same set "SQL-computed transforms" names, now that the
read source is in hand wherever the declaration is — against the **entity** of the pipeline's read
source — its declared `source` when it has one,
otherwise the target enrichment's `source_table` — where that entity comprises the read source's own
declared columns plus the columns of each of its `ACTIVE` enrichments under the qualified flat name
`<enrichment>.<column>` (see [enrichment-registry](../enrichment-registry/spec.md)). Each
failure SHALL be rejected with HTTP 422 `pipeline_validation_failed` carrying the failing detail.
Validation SHALL wrap the expression as `SELECT <expr> FROM <source>` through the **same** Calcite SQL
front end used for declaration filters, so the bounded grammar (no joins, subqueries, or CTEs), the closed
function catalog, and unknown-column rejection apply identically. The materialize statement SHALL emit a
single-key `LEFT JOIN` on the shared grain key for each enrichment its own expressions reference, and
none for an enrichment nothing references; each such join SHALL be keyed on the window's source-only
predicates (the cursor predicates always, the filter when it names no enrichment column), so the
enrichment side is restricted to the grain keys of the window rather than hashed whole. On top of that
the gate SHALL enforce:

1. **Scalar, not aggregate.** An expression that resolves to an aggregate or window computation SHALL
   be rejected — an aggregating expression is an *aggregation*, not a Class-1 enrichment, and would
   silently break the target's `1:0..1` grain.
2. **A single projected value.** The expression SHALL yield exactly one column.
3. **Type-compatible.** The result type inferred by the front end SHALL be compatible with the bound
   target column's type under the shared enrichment type mapping, in **one** check — see
   "SQL-computed transforms" for why the declared per-output token that sat between them is gone and
   why removing it can only loosen the gate. An
   `object`-typed result SHALL be rejected: it is projection-only in the SQL subset and cannot be
   collapsed into a column bound to a grain. An `array`-typed result SHALL be **accepted** when the
   bound target column is itself array-typed, and rejected otherwise. The **element** type SHALL NOT
   be checked: the inferred array type carries no element type, and a computed array has no declared
   `element_type` to compare against (the same reason `group_uniq_array`'s element kind follows its
   operand). The accepted consequence is that an array expression whose elements do not match the
   target column's element type is rejected by ClickHouse at insert time rather than at registration.
   One value per grain still holds: an array cell is a single value, not many rows. Where a **folded
   old-shape** declaration's `transform.output_vars` still declares a per-output logical type, an array
   result SHALL be declared as the bare token `array`; a **parameterized** spelling such as
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
5. **Row-grain trigger only.** A `sql` pipeline SHALL declare `on_ingest` or `schedule`; the `group`
   trigger SHALL be rejected, because a group-grain deterministic pipeline is an aggregation.
6. **No transform inputs.** A `sql` pipeline SHALL declare no `transform.inputs` — there is no request
   to render. This is the rule that makes `inputs` a member of the type-dispatched block rather than a
   top-level one: it is meaningful for exactly one value of `transform.type`.
7. **At least one resolvable output.** A `sql` pipeline's declaration SHALL carry at least
   one output — resolved through the single accessor, so `transform.outputs` where it has them and
   `transform.output_vars` otherwise — that resolves to a column on the target: with none
   there is nothing to materialize, and the pipeline would silently do no work. The refusal SHALL say
   that the declaration must carry an output resolving to a target column, which is the actionable
   form of the check now that the mapping is derived rather than declared. Reading `transform.outputs`
   alone here would refuse every declaration the evaluator fold produced from an old-shape version.
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

10. **Deterministic.** An expression calling a function the query function catalog declares
    **non-deterministic** SHALL be rejected — the pipeline-wide rule in
    [enrichment-registry](../enrichment-registry/spec.md) ("Every governed SQL fragment is
    deterministic"), applied here to a Class-1 output expression and reported as the same HTTP 422
    `pipeline_validation_failed` as every other failure of this gate.

    Determinism was previously guaranteed by *construction*: no non-deterministic function existed in
    the closed catalog, so nothing had to reject one. That floor is gone now that the query surfaces
    need a clock (`now()`), so the guarantee is enforced explicitly — but still derived from the
    catalog's own declared property rather than from a list of names kept beside it. Functions the
    engine has and this catalog does not — `rand()`, `generateUUIDv4()` — remain rejected as
    unsupported names, as before.

    It matters most here of anywhere: the exactly-once *effect* guarantee below depends on re-running
    a window producing the same values, so an expression reading the clock would write a different
    label for the same row on a re-materialize, and the two would be indistinguishable afterwards.

#### Scenario: An aggregate expression is rejected

- **WHEN** a `sql` pipeline declares an output-variable expression containing an aggregate (e.g.
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

- **WHEN** a `sql` pipeline's output expression reads a `nullable` column of an `ACTIVE` enrichment of
  its read source, whose materialized position is establishable and which closes no cycle
- **THEN** the pipeline is accepted, and the materialize statement emits the grain join for that enrichment

#### Scenario: A non-catalog function in an expression is rejected

- **WHEN** an expression calls a function outside the closed query function catalog (for instance
  `rand()` or `generateUUIDv4()`)
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: A non-deterministic catalog function in an expression is rejected

- **WHEN** a `sql` pipeline declares an output-variable expression calling `now()`, directly or
  nested inside another call such as `date_sub('hour', 1, now())`
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`, naming
  the function and stating that a Class-1 derivation must be reproducible

#### Scenario: A type-incompatible expression is rejected

- **WHEN** an expression's inferred result type is incompatible with the bound target column's type,
  or the result is object-typed, or the result is array-typed and the bound target column is not
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: A sensitive reference by an entitled author is accepted

- **WHEN** a `FULL_ADMIN` caller (or any caller in `none` mode) registers a `sql` pipeline whose
  expression reads a `sensitive` read-source column such as `request_body`
- **THEN** the pipeline is accepted

#### Scenario: A sensitive reference by a non-entitled author is rejected

- **WHEN** a caller who may not see sensitive columns registers the same declaration
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`

#### Scenario: A group trigger or transform inputs on a sql pipeline are rejected

- **WHEN** a `sql` pipeline declares `trigger_kind: "group"`, or declares any `transform.inputs` entry
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: A sql pipeline whose outputs resolve to no target column is rejected

- **WHEN** a `sql` pipeline's declaration carries no output naming a column of the
  target enrichment
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`, saying
  that the declaration must carry an output resolving to a column on the target

#### Scenario: A sql pipeline targeting a coarser-than-row grain is rejected

- **WHEN** a `sql` pipeline names a target enrichment whose grain key is not the read source's
  `identity_column` (a conversation key, say, rather than the event identity)
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`, naming the
  identity column the target must be keyed on

#### Scenario: A sql pipeline keyed on an enrichment column is rejected

- **WHEN** a `sql` pipeline names a target enrichment whose grain key resolves to an enrichment column
  rather than to the read source's own `identity_column`
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed` — a Class-1
  pipeline's grain is the row grain of the table it pages

#### Scenario: A sql pipeline declaring a read source is validated against it

- **WHEN** a `sql` pipeline declares a `source` other than its target enrichment's `source_table`, and
  its expressions reference only that source's columns, and the target is keyed on that source's
  `identity_column`
- **THEN** the pipeline is accepted, and the materialize path derives its values from the declared read source

#### Scenario: An array expression bound to an array column is accepted

- **WHEN** a `sql` pipeline declares the output expression
  `json_extract_array(jwt_claims, 'roles')` against a target column of type `array<string>`
- **THEN** the pipeline is accepted, and materialize writes the extracted array into that column

#### Scenario: An array expression bound to a scalar column is rejected

- **WHEN** the same expression is bound to a `string` target column
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the array result and
  the scalar target

#### Scenario: A parameterized array type token on a stored declaration is rejected with the token to use

- **WHEN** a `sql` pipeline's `transform.output_vars`, folded from an old-shape evaluator version,
  declares an output variable's type as `array<string>`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed`, and the message names `array` as
  the token to declare instead rather than reporting the spelling as merely unrecognized

#### Scenario: An array element-type mismatch is not caught at registration

- **WHEN** a `sql` pipeline binds an array-producing expression to an array column whose element type
  differs
- **THEN** the pipeline is accepted — element types are not modelled — and the mismatch surfaces as a
  ClickHouse insert failure on the first materialize call

#### Scenario: An object expression is still rejected

- **WHEN** a `sql` pipeline binds an output variable whose expression yields an `object`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed`

#### Scenario: A conditional expression is accepted

- **WHEN** a `sql` pipeline binds an output variable whose expression is
  `if(not_empty(json_extract_string(jwt_claims, 'user_id')), 'jwt', 'api_key')`
- **THEN** the pipeline is accepted — the expression is scalar, single-valued, deterministic, and every
  function is in the closed catalog

### Requirement: Probe-then-write window semantics

A materialize call SHALL choose its window **before** it executes it, in two statements. First a
two-column probe SHALL select the source's `(version, identity)` tuples matching the pipeline's filter,
the cursor keyset, the safety-lag, any `from`/`to` bound, and the enrichment freshness clamp below,
ordered by that tuple and limited to `limit`; the **last** returned tuple SHALL be the window end, and
`hasMore` SHALL be true exactly when the probe filled its limit. Second, a single `INSERT … SELECT` SHALL
write the derived values for source rows in `(cursorFrom, windowEnd]` only. Both statements SHALL read
with `final=1`, and each SHALL emit the grain join for the enrichments its **own** clauses reference and
no others — so a probe whose filter names no enrichment stays unjoined even when the write's expressions
join one.

**A probe is two-column in what it returns, not in what it reads, and SHALL NOT be assumed cheap.** A
probe whose filter names an enrichment column emits that grain join like any other statement, and the
join's derived table projects whatever the filter addresses — for an `object` column, the addressed
literal paths where every reference names one, and otherwise the whole column. Its cost is therefore the
cost of that projection across the parts the restriction admits, which can exceed the write's: the write
projects the same columns over the same rows, and the probe returns two columns only after reading them.
Both statements SHALL run under the same resource ceiling, for the reason
[enrichment-runtime](../enrichment-runtime/spec.md) gives: a bounded probe gating an unbounded write
bounds the wrong statement.

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

A write narrowed by the resource ceiling SHALL preserve that guarantee unchanged. The narrowed window is a
**prefix** of the fixed one, the returned cursor names its end, and the rows above it are left for the next
call rather than skipped — so the sequence of windows still partitions the source in cursor order, and each
is still re-executable identically. `hasMore` SHALL be true whenever the write narrowed, whatever the probe
reported, because rows below the probed window end remain unwritten.

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

The write SHALL stamp the provenance columns the target physically carries — `pipeline_generation`
with the pipeline's `generation` at the time of the write and `enriched_at` with the write time — and
SHALL skip any provenance column the target lacks, matching the write-back path's best-effort lineage. It SHALL also skip every
provenance column a Class-1 pipeline cannot describe, each for a structural reason rather than a missing
value: `model` (a `sql` transform makes no model call), `group_version` and `truncated` (governance
forbids a `sql` transform the `group` trigger, and a set-based window is never trimmed). The version
column `_updated_at` SHALL be stamped by ClickHouse, never by ADAS.

**`evaluator_version` SHALL NO LONGER be stamped here.** Its only remaining candidate value would be the
pipeline's `generation` under a column name that means an evaluator version, which is the two-number-spaces
corruption `pipeline_generation` exists to prevent. Rows stamped before the fold keep their integers. No
target created before this change carries `pipeline_generation`, so on those the stamp is skipped by the
best-effort rule above until an operator adds the column — the materialize still succeeds, and records no
generation in the meantime.

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

- **WHEN** a `sql` pipeline whose expressions read an enrichment column is materialized while source
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

- **WHEN** a `sql` pipeline whose expressions read only its read source's own columns is materialized
- **THEN** the window is bounded only by the cursor, the safety-lag, and any requested bounds, exactly
  as before

#### Scenario: A probe naming no enrichment stays unjoined

- **WHEN** a `sql` pipeline's `filter` references no enrichment column while its output expressions
  reference one
- **THEN** the probe statement emits no join and the `INSERT … SELECT` emits the grain join for that
  enrichment

#### Scenario: A narrowed write leaves the skipped rows above the cursor

- **WHEN** a write over the probed window is refused by the resource ceiling and succeeds over half of it
- **THEN** the returned cursor names the narrowed window's end, `hasMore` is true, and a subsequent call
  writes the remaining rows

#### Scenario: A probe whose filter names an enrichment is bounded like any other read

- **WHEN** a `sql` pipeline's `filter` references an enrichment column and the probe's projection
  exceeds the memory ceiling
- **THEN** the probe is refused with HTTP 422 `pipeline_read_budget_exceeded` and the cursor does not
  advance

#### Scenario: Provenance absent from the target is skipped, not failed

- **WHEN** the target enrichment does not physically carry `enriched_at` or `pipeline_generation`
- **THEN** those columns are omitted from the write and the write still succeeds

#### Scenario: The materialize stamps the generation, not an evaluator version

- **WHEN** a Class-1 materialize writes to a target carrying both `evaluator_version` and
  `pipeline_generation`
- **THEN** `pipeline_generation` carries the pipeline's `generation` and `evaluator_version` is left
  as it was, so rows written either side of the fold stay distinguishable

#### Scenario: Provenance a sql pipeline cannot describe is skipped

- **WHEN** the target enrichment physically carries `model`, `group_version`, or `truncated`
- **THEN** those columns are omitted from the materialize write and the write still succeeds

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
An unknown pipeline SHALL return HTTP 404 `unknown_pipeline`. Calling `materialize` on a `llm` pipeline, or
calling the row scan on a `sql` pipeline, SHALL be rejected with HTTP 422
`pipeline_validation_failed` — the two execution paths are not interchangeable.

#### Scenario: Materialize writes the window and reports the count

- **WHEN** `POST /v1/pipelines/{name}/materialize` is called for an enabled or paused `sql` pipeline with
  matching source rows below the safety-lag horizon
- **THEN** the derived values are written to the target enrichment and the response reports
  `rowsWritten`, an advanced `nextCursor`, `hasMore`, and `lagSeconds`

#### Scenario: Materialize returns no rows to the caller

- **WHEN** any materialize call succeeds
- **THEN** the response carries counts and cursor bookkeeping only, and never source or derived row
  contents

#### Scenario: Materialize on an LLM pipeline is rejected

- **WHEN** `POST /v1/pipelines/{name}/materialize` is called for a pipeline whose `transform.type` is `llm`
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: Scan on a SQL pipeline is rejected

- **WHEN** `POST /v1/pipelines/{name}/scan` is called for a pipeline whose `transform.type` is `sql`
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
