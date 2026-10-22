# class1-enrichment Specification

## Purpose

Class-1 enrichment: **deterministic, model-free** derivation of an enrichment column, computed
set-based inside ClickHouse from a governed scalar expression over the source's declared columns. It is
the sibling of the Class-2 (LLM) path in [enrichment-registry](../enrichment-registry/spec.md) /
[enrichment-runtime](../enrichment-runtime/spec.md), sharing their registry, `1:0..1` grain model,
authorization, cursor, and provenance — Class-1 changes only *where the value is computed*. Its
canonical workload is structural extraction from stored request/response bodies, which is why keeping
the computation in the database matters twice over: it is set-based rather than a row loop (the
performance argument) and no row payload crosses the wire in either direction, so `sensitive` bodies
never leave ClickHouse (the privacy argument).

An `enrich` pipeline's `transform` declares `type=sql` with one scalar expression per output variable,
and binds those outputs to target-enrichment columns exactly as an LLM transform does. The runner supplies only
cadence, window, and cursor bookkeeping through `POST /v1/pipelines/{name}/materialize`, which **probes a
window before it writes it** so a retry re-executes an identical range — the exactly-once *effect*
guarantee at window granularity. Class-1 is row-grain and non-aggregating by construction: aggregate
expressions and the `group` trigger are rejected, because a group-grain deterministic derivation is an
aggregation, which this capability deliberately does not provide.

Because ADAS is itself the writer here, this is the only path that can record **how far an enrichment has
been materialized** — which is what every dependent rollup's freshness clamp reads.
## Requirements

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
mapped to a logical type. Because the transform lives on the pipeline, a source is **always** in
hand wherever an expression is read, so the inferred type SHALL be available at every such point:
registration, patch, read and the materialize call alike. An expression on a disabled draft is stored
unvalidated and checked when the pipeline is armed or previewed (see below).

An **`array`** result SHALL continue to pass on shape alone: an inferred `array` against a column
declared `array` is compatible by equality, and the column's `element_type` SHALL deliberately NOT be
compared, because the inferred type carries no element type. Dropping the declared token changes
neither half of that, and an array whose elements do not match the column's `element_type` is still
refused by ClickHouse at insert rather than at registration.

**Expressions SHALL live on the pipeline**, so an expression that names a column its source does not
have is refused by the write that arms the pipeline (or by a preview of a draft), not deferred to the
materialize call. Editing an expression bumps the pipeline's `generation`, which
discards its materialized position and holds every consumer of its target enrichment back behind the
freshness clamp — for **one materialize cycle**. The runner holds the scan cursor, and a `generation`
bump does not touch it, so the next materialize resumes at the same cursor and re-records the position
at the end of the window it writes, one cadence tick later. A replay from the start of the source
happens only when the **cursor** is lost, which no declaration edit can cause.

Editing an expression SHALL NOT re-derive already-written rows. Old rows keep the values the previous
expression produced; re-deriving history remains an explicit bounded materialize (see "A materialize
records the position it wrote through, against its pipeline").

Per-row provenance SHALL be preserved by `pipeline_generation`, the permitted provenance column
carrying the `generation` under which a row was derived (see
[enrichment-runtime](../enrichment-runtime/spec.md), "Pipeline-scoped versioned write-back endpoint").
`generation` moves when either the expression or the `filter` changes, so the stamp also separates
rows derived under different filters.

The **materialize path SHALL stamp it itself**, inside its `INSERT … SELECT`. It SHALL NOT stamp
`evaluator_version`: that name stays a reserved provenance column, and values already in it remain as
frozen data. The stamp SHALL be dropped silently when the target does not physically carry the column,
exactly as the three provenance columns a Class-1 target can never populate are, so a target created
before the column existed keeps materializing until an operator adds it (see
[table-management](../table-management/spec.md), "Provenance columns are injected into an enrichment's
schema definition").

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

#### Scenario: A stored sql transform keeps its expressions in output_vars

- **WHEN** a Class-1 pipeline's stored transform carries its expressions in `transform.output_vars`
  rather than `transform.outputs`
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

The system SHALL statically validate every `sql` output-variable expression **wherever the declaration
gate runs** — when a write leaves the pipeline armed (a registration carrying `enabled: true` included,
since that write arms it), and on `POST /v1/pipelines/{name}/preview` of a disabled pipeline — against
the **entity** of the pipeline's read
source — its declared `source` when it has one,
otherwise the target enrichment's `source_table` — where that entity comprises the read source's own
declared columns plus the columns of each of its `ACTIVE` enrichments under the qualified flat name
`<enrichment>.<column>` (see [enrichment-registry](../enrichment-registry/spec.md)). Each
failure SHALL be rejected with HTTP 422 `pipeline_validation_failed` carrying the failing detail.
Validation SHALL wrap the expression as `SELECT <expr> FROM <source>` through the **same** Calcite SQL
front end used for declaration filters, so the bounded grammar (no joins, subqueries, or CTEs), the closed
function catalog, and unknown-column rejection apply identically.

**A declaration write that leaves the pipeline disabled SHALL store the expression without refusing it**
(see [pipeline-registry](../pipeline-registry/spec.md)), so an author may save a half-written
derivation and come back to it. Three consequences are part of this requirement rather than exceptions
to it. The **authored** projection of such a pipeline SHALL remain readable — a read never refuses a
stored declaration for being unfinished, or the author could not read back what they were writing —
while the **compiled** projection, which is what the materialize path and the Enrichment Runner
consume, SHALL refuse it as [pipeline-registry](../pipeline-registry/spec.md) states ("An undeclared
declaration member is absent from the authored projection"). A declaration carrying no output expression at all is **incomplete** rather
than wrong, and SHALL be refused with the same HTTP 422 `pipeline_validation_failed` a
declared-but-invalid expression produces, naming the member that is absent rather than a defect; the
gate reports one problem per refusal, exactly as every other refusal on this surface does. And every refusal below
SHALL be re-derived by each consumer of the stored declaration rather than inherited from the moment of
storage: nothing may assume the expression was checked on the way in, because it was not.

**`POST /v1/pipelines/{name}/materialize` SHALL refuse a disabled pipeline, and SHALL re-check two of
these rules for itself over an enabled one.** A **complete but invalid** declaration is now storable
while disabled, and the absence refusal does not fire on it, because it is complete. What keeps it
from being executed is that the write paths refuse a disabled pipeline with HTTP 409 (see
[enrichment-runtime](../enrichment-runtime/spec.md), "A disabled pipeline's write paths refuse"), so a
declaration reaches `/materialize` only after arming ran this gate over it. The two re-checks are kept
beside that as the guard for a stored declaration that **drifts** after it was armed. The materialize path already
re-derives the expression parse, the closed catalog, determinism, source scoping and the caller's
entitlement, but it re-derives neither rule 3's result-type fit against the bound column, nor rule 8's
row-grain rule against the read source's `identity_column`, nor rule 9's enrichment-read rules — so a
declaration whose target is keyed more coarsely than its read source would materialize, and the target's
`ReplacingMergeTree` would collapse the window to an arbitrary survivor, which is the precise outcome
rule 8 exists to prevent. Rules 3 and 8 SHALL therefore be re-checked at `/materialize`, being the two
whose absence produces a silently wrong row rather than a loud failure; **nothing else of this gate
SHALL be imported onto the data plane**, because running the rest would stop a running pipeline for
drift the operator did not cause and cannot see from there. A stored
derived member that no longer re-derives from the declaration as it now stands SHALL count as absent
for `/materialize` — see [enrichment-runtime](../enrichment-runtime/spec.md).

The materialize statement SHALL emit a
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
   One value per grain still holds: an array cell is a single value, not many rows. Where a stored
   declaration's `transform.output_vars` still declares a per-output logical type, an array
   result SHALL be declared as the bare token `array`; a **parameterized** spelling such as
   `array<string>` SHALL be rejected with HTTP 422 `pipeline_validation_failed`, and the rejection SHALL name
   `array` as the token to write instead. Aliasing the parameterized form would advertise an element-type
   check that does not happen, and would read as having passed one when bound to a column of a different
   element type.
4. **Entitled.** The expression's referenced entity fields SHALL run the same sensitive-column
   entitlement check as a pipeline `filter`, whether the field is a read-source column or an enrichment
   column reached through the grain join: a `sensitive` field is admitted only for a caller entitled to
   see sensitive columns, and rejected with HTTP 403 `sensitive_column_not_entitled` otherwise. The
   check governs **who may reference** the field and runs wherever the gate runs; on the authoring
   surfaces, which are `FULL_ADMIN`-only, it is defence in depth, and it is load-bearing on the runner
   data plane, which re-runs it per read and per derive (see
   [enrichment-registry](../enrichment-registry/spec.md), "Static governance gate on arming and
   execution"). The derived value SHALL NOT inherit its sensitivity
   and the bound target column's own flag SHALL NOT be changed, exactly as for a read-source column —
   see the "Runner identity and derived-column sensitivity posture" requirement in
   [enrichment-registry](../enrichment-registry/spec.md).
5. **Row-grain trigger only.** A `sql` pipeline SHALL declare `schedule`; the `group`
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
   alone here would refuse a stored `output_vars` declaration.
8. **Row-grain target.** The pipeline's target enrichment SHALL be keyed on the **read source's** declared
   `identity_column` (its scan metadata, see [enrichment-runtime](../enrichment-runtime/spec.md)). A
   Class-1 derivation produces one value per read-source row, so against a coarser grain one window would
   insert many rows carrying the same grain key and the target's `ReplacingMergeTree` would collapse them
   to an arbitrary survivor — a label that is neither wrong nor reproducible. A per-group deterministic
   value is an aggregation, which this capability does not provide. This also means a read source with no
   scan metadata is refused **at the declaration gate** — when the pipeline is armed, or previewed
   while disabled — rather than only at the first materialize call. The
   retiming does not weaken the guarantee the original wording was making, which was never about
   storage: what matters is that the refusal arrives **before any row is written**. That guarantee rests
   on two things together: `POST …/materialize` refuses a disabled pipeline, so nothing is written from a
   declaration that was never armed and therefore never gated, and this rule is one of the two
   `/materialize` re-checks over an enabled one, as above. What the retiming does change is that the
   diagnosis is now available on demand, from preview, without arming anything.
   Resolving this
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

Throughout the scenarios below, **"the request is rejected" SHALL name the request that runs the gate**
— the write that arms the pipeline or a preview while it is disabled — and not a write that merely
stores the declaration, nor a compiled read, which checks completeness only. Each of the ten rules is
checked in full by each of those, so which
one the author reaches first changes when they are told, never what they are told. A materialize is
**not** one of those moments and SHALL NOT be described as one: it re-derives what it has always
re-derived and re-checks rules 3 and 8 on top, which is where a scenario names it explicitly.

#### Scenario: A draft expression is stored and refused when the pipeline is armed

- **GIVEN** a disabled `enrich` pipeline whose `transform` declares an output expression naming a column
  its read source does not carry
- **WHEN** the declaration is stored by `POST /v1/pipelines` or a declaration `PATCH`
- **THEN** it is accepted and stored unvalidated, because the write arms nothing
- **AND WHEN** the pipeline is armed, or previewed while disabled
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed` naming the
  unknown column

#### Scenario: A declaration with no transform yet is refused for the member it lacks

- **GIVEN** an `enrich` pipeline registered with `name`, `kind`, `target` and a complete `trigger`, but
  no `transform`
- **WHEN** it is armed, previewed while disabled, or read with `view=compiled`
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`, whose
  message names the absent `transform` rather than asserting a defect in one
- **AND** a read of its **authored** projection still serves the declaration as far as it has been
  written, so the author can read back what they stored

#### Scenario: A draft is refused at materialize for its state, not its content

- **GIVEN** the same draft, still disabled
- **WHEN** `POST /v1/pipelines/{name}/materialize` is called on it
- **THEN** the request is rejected with HTTP 409 `pipeline_conflict` before any transform check, and
  nothing is written

#### Scenario: A read source with no scan metadata is refused before any row is written

- **GIVEN** a `sql` pipeline whose read source declares no `identity_column`
- **WHEN** the pipeline is armed, or previewed while disabled
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed` naming the
  missing scan metadata, so the refusal still arrives ahead of the first materialize rather than from it
- **AND WHEN** `POST /v1/pipelines/{name}/materialize` is called on it while it is disabled, without it
  ever having been armed
- **THEN** the request is rejected with HTTP 409 `pipeline_conflict` and nothing is written, because a
  disabled pipeline writes to its target by no route

#### Scenario: A complete but invalid declaration is not executed

- **GIVEN** a `sql` pipeline whose declaration is complete — so the completeness refusal does not fire —
  but whose target enrichment is keyed more coarsely than its read source's `identity_column`
- **WHEN** `POST /v1/pipelines/{name}/materialize` is called while it is disabled
- **THEN** the request is rejected with HTTP 409 `pipeline_conflict`, and arming it is refused by the gate
- **AND WHEN** the same declaration is found enabled — reached by drift or by a write that bypassed the API
- **THEN** that endpoint's row-grain re-check fires and the request is rejected with HTTP 422
  `pipeline_validation_failed` naming the grain, and no `INSERT … SELECT` is executed against the target

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

- **WHEN** a caller holding `TABLE_READ` and `TABLE_SENSITIVE` on the read source — each globally or
  granted on that table — or any caller in `none` mode, registers a `sql` pipeline whose expression reads a
  `sensitive` read-source column such as `request_body`
- **THEN** the pipeline is accepted

#### Scenario: A caller who may not see sensitive columns cannot author the declaration at all

- **WHEN** such a caller registers, patches or previews any `sql` declaration, whether or not it
  references a `sensitive` column
- **THEN** the request is refused with HTTP 403 `forbidden` by method security before the declaration
  gate runs, because the authoring surfaces require the same role the entitlement is — so the in-gate
  `sensitive_column_not_entitled` is unreachable from them and nothing about its timing is observable

#### Scenario: A runner identity that may not see sensitive columns is refused at materialize

- **GIVEN** a `sql` pipeline whose expression reads a `sensitive` read-source column
- **WHEN** an authenticated identity holding the target enrichment's WRITE grant, but not `FULL_ADMIN`,
  calls `POST /v1/pipelines/{name}/materialize`
- **THEN** the request is rejected with HTTP 403 `sensitive_column_not_entitled`, no row is written, and
  no value of the column is returned — this is the surface on which the entitlement check decides
  anything

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

- **WHEN** a `sql` pipeline's stored `transform.output_vars`
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

### Requirement: Set-based materialize endpoint

The system SHALL expose `POST /v1/pipelines/{name}/materialize` accepting a body
`{cursor?, limit?, from?, to?}` and returning `{rowsWritten, nextCursor, hasMore, lagSeconds}`. The
`cursor` is the caller-held opaque token from a prior window, with the same encoding, verification, and
statelessness as the scan's: ADAS signs and decodes it but holds no per-caller position, so the caller
persists it and advances **only on a successful response**. It SHALL resolve the pipeline's source,
filter, grain, expressions, and target enrichment from the stored pipeline; the caller SHALL NOT supply
SQL, a table name, a filter, or a column list, and **no row payloads SHALL cross the wire in either
direction** — the source data, including any sensitive body column, never leaves ClickHouse.

The endpoint SHALL be authorized by **both** directions it exercises: `TABLE_READ` on the pipeline's
read source and `TABLE_WRITE` on its target enrichment, each held globally or granted on that table (see
the `enrichment-runtime` capability). A caller lacking either SHALL be rejected with HTTP 403
`pipeline_access_denied`. Authorizing it by the target's write grant alone — as this required — was a
confused deputy: a materialize reads the source, so a write grant on a small derived enrichment bought a
read of the table it derives from.

Independently of those grants, the sensitive-column rule SHALL be re-applied against the **calling**
identity over the columns the pipeline's expressions and filter reference, per owning table, and a
non-entitled caller SHALL be rejected with HTTP 403 `sensitive_column_not_entitled` — so a runner
without `TABLE_SENSITIVE` on the table that owns the column cannot cause sensitive data to be read
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
An unknown pipeline SHALL return HTTP 404 `unknown_pipeline`. A **disabled** pipeline SHALL be refused
with HTTP 409 `pipeline_conflict` before anything is probed or written: a disabled pipeline writes to
its target by no route (see [enrichment-runtime](../enrichment-runtime/spec.md), "A disabled
pipeline's write paths refuse"). Calling `materialize` on a `llm` pipeline, or
calling the row scan on a `sql` pipeline, SHALL be rejected with HTTP 422
`pipeline_validation_failed` — the two execution paths are not interchangeable.

#### Scenario: Materialize writes the window and reports the count

- **WHEN** `POST /v1/pipelines/{name}/materialize` is called for an enabled `sql` pipeline with
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

- **WHEN** a caller holding `TABLE_READ` and `TABLE_SENSITIVE` on the read source and `TABLE_WRITE`
  on the target — each globally or by grant — or any caller in `none` mode, calls the same `materialize`
- **THEN** the derivation runs normally

#### Scenario: A bounded window materializes only its range

- **WHEN** `materialize` is called with `from` and `to`
- **THEN** only source rows whose version is inside `(from, to]` contribute to the write

#### Scenario: Materialize of a disabled pipeline is refused

- **WHEN** `POST /v1/pipelines/{name}/materialize` is called for a disabled `sql` pipeline
- **THEN** the request is rejected with HTTP 409 `pipeline_conflict`, nothing is written, and no
  position or drained horizon is recorded

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

**`evaluator_version` SHALL NOT be stamped here**; values already in that column stay unchanged. On a
target that does not carry `pipeline_generation` the stamp is skipped by the best-effort rule above until
an operator adds the column — the materialize still succeeds, and records no
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
  as it was

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

- **WHEN** a `sql` transform declares an output expression calling `map_value` on a `map` source column
  with a literal key, bound to a `string`-typed output variable
- **THEN** the pipeline registers, the expression passing the scalar, single-value and type-compatibility
  gates

#### Scenario: Array membership derives a boolean enrichment column

- **WHEN** a `sql` transform declares an `array_has` call over an `array` source column bound to a
  `boolean`-typed output variable
- **THEN** the pipeline registers

#### Scenario: Map key presence derives a boolean enrichment column

- **WHEN** a `sql` transform declares a `map_contains` call on a `map` source column with a literal key,
  bound to a `boolean`-typed output variable
- **THEN** the pipeline registers

#### Scenario: Map keys bind only to an array column

- **WHEN** a `sql` transform declares a `map_keys` call bound to an `array`-typed output variable, and a
  second declares it bound to a `string`-typed one
- **THEN** the first registers and the second is rejected with HTTP 422 `pipeline_validation_failed`

#### Scenario: Split result binds only to an array column

- **WHEN** a `sql` transform declares a `split_string` call bound to an `array`-typed output variable,
  and a second declares the same expression bound to a `string`-typed one
- **THEN** the first registers and the second is rejected with HTTP 422 `pipeline_validation_failed`

#### Scenario: A bare map column is still not a derivable result

- **WHEN** a `sql` transform declares an output expression that is a bare `map` column reference
- **THEN** the pipeline is rejected with HTTP 422 `pipeline_validation_failed` — the accessors read a map, they
  do not make one bindable

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
the request separates it from a live tick — and it is the default shape of a backfill, and the way a
transform-expression change is re-applied to history. What separates the two is the effect rather than the
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

### Requirement: The Class-1 gate refuses a window function explicitly

The "Scalar, not aggregate" rule of the Class-1 expression governance gate SHALL be enforced for window
functions by an explicit check rather than as a side effect of the query mode. A window keeps its block in row
mode, so the mode assertion that rejects an aggregate does not reject it — and the SQL front end now
translates `OVER` into a DSL `window` expression (see
[sql-query-translation](../sql-query-translation/spec.md), "Window functions translate to the DSL window
expression"), so the gate has to refuse it itself.

The gate SHALL therefore reject, with HTTP 422 `pipeline_validation_failed`, a `sql` transform's output-variable
expression that contains a `window` expression anywhere in its tree — including one nested inside a `CASE`
branch — and the rejection message SHALL name the window as the reason. A pipeline predicate (`filter`, a
group pipeline's `ready_when.signal`) SHALL likewise carry no window expression; the front end already refuses
`OVER` in a `WHERE`, and the gate SHALL surface any such predicate as the same 422 rather than as an
internal error.

The reason is the exactly-once *effect* guarantee, not grain: a Class-1 expression is rendered into
`INSERT INTO <target> … SELECT … FROM <source> WHERE <window predicates>`, so a window function would be
computed over whichever source rows the materialization window happened to cover. `row_number() OVER ()`
would write a value that depends on the window's boundaries, and re-materializing an overlapping window —
which the probe-then-write contract explicitly permits after a timeout or a retry — would write a
different value for the same source row. The position rules the query surfaces apply to a window
(`select`-only, never in a filter) do not run on the enrichment path, which renders the analysed
expression directly.

#### Scenario: A window expression in an output-variable expression is rejected

- **WHEN** a `sql` transform declares an output-variable expression containing a window function (e.g.
  `row_number() over ()` or `sum(total_tokens) over (order by request_time)`)
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`, and the message
  names the window function as the reason

#### Scenario: A window expression nested inside a conditional is rejected

- **WHEN** a `sql` transform declares an output-variable expression whose `CASE` branch contains a window
  function
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: A window expression in a pipeline predicate is rejected

- **WHEN** a pipeline's `filter` contains a window function
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

### Requirement: Materialize requires the same runner contract version as the rest of the data plane

`POST /v1/pipelines/{name}/materialize` SHALL carry the `Adas-Contract-Version` header and SHALL be
refused on a missing or mismatched value exactly as the scan, group-assembly and write-back endpoints are
(HTTP 409 `runner_contract_required` / `runner_contract_mismatch`, decided before any pipeline lookup,
authorization or query — see the `enrichment-runtime` capability for the normative rule).

The Class-1 lane SHALL NOT be exempted on the grounds that it carries no row payload. It is the lane whose
refusal is *most* consequential to the rest of the service, because the materialized position it advances
is what every dependent rollup's freshness clamp reads: a skewed caller that reached this endpoint would
either advance that position over a window it did not derive, or — being refused for the wrong reason,
later — leave the clamp frozen without anything naming the cause. A refusal at admission makes the
position simply stop moving, which is recoverable within one cadence of the matching runner landing, and
pairs with the runner-contact metric that names the pipeline holding the clamp.

#### Scenario: A materialize without the contract header advances no position

- **WHEN** `POST /v1/pipelines/{name}/materialize` is called with no `Adas-Contract-Version` header
- **THEN** the response is HTTP 409 `runner_contract_required`, no window is probed or written, and the
  pipeline's materialized position is unchanged

#### Scenario: A mismatched caller cannot advance the materialized position

- **WHEN** the endpoint is called with a contract version that differs from the version served
- **THEN** the response is HTTP 409 `runner_contract_mismatch` and the materialized position is unchanged,
  so a dependent rollup's clamp holds at the last position actually derived

### Requirement: A materialize call reports its outcome in one log line

Each materialize call that reaches its window probe SHALL log one INFO outcome line. The line names the
pipeline and its generation, and gives an outcome of `written`, `drained`, `withheld` or `clamped_empty`. It
also gives the rows written, whether the write narrowed, `has_more`, `lag_s`, and the durations of the first
probe (`probe_ms`), all re-probes together (`reprobe_ms`), all write attempts together (`write_ms`) and the
whole call (`total_ms`). When the freshness clamp bounded the window, the line SHALL name the enrichment
that bound it and the bound tuple: the version half as the epoch-millisecond position, and the identity half
as a short digest rather than its value, because the identity is a source column value the service cannot
assume is free of user data. It SHALL give the ClickHouse `query_id` of the last write statement, or of the
last probe when nothing was written.

- `written`: rows were written.
- `drained`: the window was empty and the drained horizon was recorded. A call carrying `from`/`to` that
  finds its window empty also reports `drained`, although such a call records no horizon.
- `clamped_empty`: the clamp emptied the window, so no horizon was recorded.
- `withheld`: a joined enrichment has no usable position, so no statement ran. This line reports a zero
  `total_ms`.

The line SHALL carry no row values, no filter literals and no SQL.

`drained`, `withheld` and `clamped_empty` SHALL be logged only when a pipeline's outcome differs from its
previous outcome on the same replica. A caught-up or blocked pipeline is called on every runner cadence,
and the line exists to show changes, not to repeat a steady state. `written` SHALL be logged on every call.
A call that fails SHALL NOT log an outcome line. Its failure is reported by the error handler.

A stall caused by a missing upstream position is otherwise invisible in the logs. Before this requirement,
none of the empty paths logged anything, and the only durations were trace spans, which exist only while
trace export is on.

#### Scenario: A written window is logged with its stage durations

- **WHEN** a materialize call writes rows after narrowing once
- **THEN** one INFO line reports `outcome=written`, the rows, `narrowed=true`, `has_more=true`, and
  `probe_ms`, `reprobe_ms`, `write_ms` and `total_ms`, with the write's `query_id`

#### Scenario: A withheld pipeline is logged when it becomes withheld

- **WHEN** a pipeline whose joined enrichment has no usable position is materialized on several calls in a
  row
- **THEN** only the first of those calls logs `outcome=withheld`, naming the enrichment, and the next call
  with a different outcome logs that outcome

#### Scenario: A clamped empty window is distinguished from a drained one

- **WHEN** the clamp empties the window while rows above the bound match
- **THEN** the outcome line reports `clamped_empty` with the bounding enrichment and tuple, not `drained`
- **AND** the tuple's identity half appears only as a digest, never as the source value

### Requirement: A materialize call nearing its budget is warned about

A materialize call whose `total_ms` passes half of the configured `pipeline.budget.timeout` SHALL log one
WARN, whether it succeeds or not. The WARN names the pipeline, `probe_ms`, `reprobe_ms`, `write_ms`,
`total_ms` and the configured timeout. The threshold is derived from the budget rather than configured
separately. Under the single per-call deadline, the budget is the call's upper bound, and half of it is the
point at which one more slow statement would reach the refusal.

#### Scenario: A slow successful call warns with its stage split

- **WHEN** a materialize call succeeds after more than half of `pipeline.budget.timeout`
- **THEN** one WARN names the pipeline, the three stage durations, the total and the configured timeout

#### Scenario: A fast call does not warn

- **WHEN** a materialize call completes in less than half of `pipeline.budget.timeout`
- **THEN** no soft-deadline WARN is logged

### Requirement: A discarded materialized position is logged

The service SHALL log one INFO line when recording a materialize discards a materialized position or
drained horizon because the mark was stored under a different generation. The line names the pipeline, the
stored generation and the current generation. The line SHALL be logged only when a stored generation existed. A
pipeline's first mark ever claims a generation where none was stored, and that is not a discard. The stored
generation SHALL be read by the same statement that discards the mark, with no separate read that a
concurrent writer could interleave with.

This line answers "why did the rollup freeze": a discarded position holds every dependent rollup's
freshness clamp until the pipeline materializes again under its new generation.

#### Scenario: A mark under an older generation is discarded and logged

- **WHEN** a materialize records its position while the stored mark carries an older generation
- **THEN** one INFO line reports the pipeline and `stored_gen -> gen`

#### Scenario: The first mark ever is not logged as a discard

- **WHEN** a pipeline records its first materialized position, with no generation stored
- **THEN** no discard line is logged

### Requirement: Materialize writes the alignment column of an aligned target

A Class-1 materialize into an aligned enrichment SHALL write the alignment column from the source row's
partition column (`source.request_time` for `dial_usage_log`) in the same `INSERT … SELECT`, with no change
to the request the runner sends. A materialize into a non-aligned target SHALL be unchanged.

A pipeline output bound to the alignment column SHALL remain valid, at registration, patch, import and
arming, and SHALL be **superseded**: the statement SHALL write the column once, from the source row, and
SHALL NOT render the bound expression. Preview SHALL show the value that is written for a bound alignment
column, and SHALL NOT list a hidden alignment column the declaration does not bind, which is not one of its
outputs. A binding whose
expression is the source partition column itself (`request_time: request_time`, as `usage-request-baggage-live`
declares) writes exactly what it wrote before, so such a pipeline's rows, filter, cursor and cadence are
unchanged by the alignment. Every other output binding SHALL be rendered as before.

The alignment SHALL be judged against the target's own source definition, never against the pipeline's read
source. A materialize of a pipeline whose read source is not the target's own source, into an aligned target, SHALL
be refused with HTTP 422 `pipeline_validation_failed` before any statement runs, and SHALL write nothing: that
table's rows hold no value of the target's source rows, so a write would stamp the column's default or another
table's same-named column and leave every row unmatched. Registration refuses the same declaration (enrichment-runtime,
"Only a row-trigger pipeline of the target's own source can write an aligned enrichment"); this guards one that drifted.

The `INSERT … SELECT` SHALL NOT be limited by ClickHouse's `max_partitions_per_insert_block`: a window spanning more
than 100 day partitions of an aligned target is written, not refused on every tick.

#### Scenario: Materialized rows carry the source's partition value

- **GIVEN** a Class-1 pipeline targeting an aligned enrichment of `dial_usage_log`
- **WHEN** a window is materialized
- **THEN** every written row's `request_time` equals its source row's `request_time`

#### Scenario: A pipeline binding the alignment column keeps working unchanged

- **GIVEN** `usage-request-baggage-live`, with outputs `baggage` and `request_time: request_time`, targeting
  `usage_request_baggage` after it is aligned
- **WHEN** a window is materialized
- **THEN** the `INSERT` names `request_time` once, the call succeeds, and the written rows — `baggage`,
  `request_time` and which events get a row — are identical to those the same window wrote before the
  alignment

#### Scenario: A window spanning many days is written

- **GIVEN** a Class-1 pipeline targeting an aligned enrichment of `dial_usage_log`
- **WHEN** a materialized window spans more than 100 days of source rows
- **THEN** the `INSERT` succeeds

#### Scenario: A binding to the alignment column with another expression is superseded

- **GIVEN** a Class-1 pipeline binding the alignment column to an expression other than the source column
- **WHEN** it is registered, previewed and materialized
- **THEN** registration succeeds, and both the preview and the written rows carry the source row's
  `request_time`

### Requirement: Materialize refuses during the target's relayout hold

`POST /v1/pipelines/{name}/materialize` for a pipeline whose target is held by a relayout SHALL be refused
with HTTP 503 `table_relayout_in_progress` before the probe, and SHALL record no materialized position. The hold
SHALL be checked again immediately before each `INSERT … SELECT`, so a hold taken while the probe runs also refuses
the call with the same 503 before anything is written. When that second check finds the hold released but the
target aligned while the call resolved it unaligned — a relayout aligned it meanwhile — the call SHALL be refused
with the same 503 before anything is written, since its statement would carry no alignment value. The next
materialize after the hold is released SHALL resume from the runner's committed cursor.

#### Scenario: A held materialize writes nothing and records no position

- **WHEN** a materialize targets an enrichment whose relayout is holding writers
- **THEN** the response is HTTP 503 `table_relayout_in_progress`, no row is written and the pipeline's
  materialized position is unchanged

#### Scenario: A materialize that resolved its target before the relayout aligned it writes nothing

- **GIVEN** a materialize that resolved its target unaligned
- **WHEN** a relayout aligns the target and releases its hold before the call reaches its `INSERT … SELECT`
- **THEN** the response is HTTP 503 `table_relayout_in_progress`, no row is written, and the next materialize
  writes the rows with their source rows' partition value
