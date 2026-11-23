## MODIFIED Requirements

### Requirement: The declaration gate runs when a write arms the pipeline, not when it is stored
The system SHALL apply the kind's declaration gate at the moment a pipeline is **armed or executed**,
and SHALL NOT require a stored declaration to be complete or valid while the pipeline is disabled. A
write that leaves the pipeline `enabled: false` SHALL store what it was given.

This is the registry's single statement of validation timing; where another capability's requirement
says a rule is enforced "at registration", it SHALL be read as enforced at this gate.

**The gate has two halves, and only one of them is deferred.** The gate does not merely inspect a
declaration — it **derives** parts of it: an `aggregate` pipeline's `group_by` is expanded from the
target's `ordering_key` when omitted, and an `enrich` pipeline's `output_bindings` are derived from its
transform's outputs against the target's exposed columns. Neither member is authored, and the gate is
their only writer. An `aggregate` pipeline declared in **SQL mode** has a different derived member: its
input table is read from the statement's `FROM`, and its `group_by` is **not derived** at all, because
the statement's own `GROUP BY` is the declaration of its keys. Filling `group_by` from the target's
`ordering_key` in SQL mode would store a form-mode member beside `sql`, which the exclusivity rule
refuses from a caller and which the read would then serve as if the author had written it.

- **Derivation SHALL run on every declaration write**, whether or not the write arms the pipeline, and
  SHALL be best-effort: where a member cannot be derived — no transform declared yet, an output naming
  a column that does not exist — the previously stored value SHALL be left untouched. A declaration
  write SHALL NOT erase a derived member it could not re-derive. Were it to, the catalog's column
  drop/rename dependency guard, which resolves a pipeline's writes from its stored `output_bindings`,
  would stop seeing the pipeline: the column would become droppable, the ClickHouse `ALTER` would
  execute, and the catalog transaction that re-derives afterwards would roll back — leaving the column
  gone from one store and present in the other, with no operation that repairs the pair. In SQL mode
  the best-effort derivation SHALL read the input table only from a statement the SQL front end can
  plan into one `INSERT` block. A statement it cannot plan, for any reason (a parse error, an unknown
  table or column, a construct the front end refuses), SHALL be stored verbatim with the input table
  absent, and the derivation SHALL NOT throw. Whether the statement is admissible is the gate's question, not the draft write's.
- **Refusal SHALL run when the write arms the pipeline**, and on the dry-run preview of a pipeline
  that is **disabled** and therefore never gated. Arming asks, besides the kind's gate, the trigger's own
  completeness — a trigger kind, and a cron on a `schedule` trigger — and the preview of a disabled
  pipeline SHALL ask the same, so that a preview that passes means arming will pass. Whether the preview of an **enabled**
  pipeline re-gates it is the kind's to state, and the two kinds state it differently: an `enrich`
  preview of an enabled pipeline SHALL NOT re-gate it, because its declaration was gated when it was
  armed and re-gating would report it as unpreviewable for drift the operator did not cause and cannot
  see from there; an `aggregate` preview SHALL run its full gate in either state, because on that kind
  the drift is the answer an operator reading a live rollup needs (see
  [aggregate-pipeline](../aggregate-pipeline/spec.md), "Dry-run preview of an aggregate declaration").
  An `aggregate` declaration in SQL mode SHALL be held to its **full** SQL gate — the statement parsed,
  its eligibility for incremental refresh checked, and every governance rule of the form path applied
  to it — at exactly the moments the form gate runs: when a write arms it, on every preview, and on
  every refresh. A refresh SHALL NOT reuse a verdict from arming, because the input's schema and the
  referenced enrichments may both have moved since.
- **The pipeline-scoped data plane SHALL NOT run the whole gate.** It refuses a declaration whose
  required members are **absent**, and the materialize path additionally re-checks the two rules whose
  absence produces a silently wrong row rather than a loud failure — the result-type fit against the
  bound target column, and the row-grain rule against the read source's `identity_column`. Importing
  the rest would stop a running pipeline for drift the operator did not cause and cannot see from
  there. The two **writing** endpoints refuse a disabled pipeline outright, so what they execute has
  always passed the gate once, when it was armed. A refusal an endpoint already owns
  unconditionally stays unconditional in either state: turning a server fault into a refusal is not a
  new gate.

**Three classes of refusal SHALL remain unconditional**, on every write, armed or not, and they are
exceptions to the rule above rather than gaps in it:

- **An intra-declaration contradiction the storage layer refuses regardless** — a `member_select`
  declared against a non-`group` trigger, or a non-`schedule` trigger on an `aggregate` pipeline.
  These are refused by database constraints that hold for every row, so without a service-layer
  refusal the author would meet them as an integrity violation reported as a name collision. They are
  also not incompleteness: they are two stated members that cannot both be true, which no amount of
  further authoring resolves.
- **A malformed request body**, which is a binding failure and answered HTTP 400 naming the field, as
  it is today. Deferring governance does not make an unparseable body storable. A `trigger.cron` that
  does not parse is malformed in this sense and SHALL be refused on every write with HTTP 422; only its
  *absence* on a `schedule` trigger is deferred to arming. An `aggregate` `sql` that does not parse is
  **not** malformed in this sense: it is a string member that binds, and it SHALL be stored on a write
  that leaves the pipeline disabled, because a statement is authored over several saves and its first
  drafts rarely parse.
- **A named table that does not exist.** `target` and `inputs` each carry a foreign key to the table
  catalog, which the database enforces on every row, so a declaration naming a table that does not exist
  SHALL be refused with HTTP 422 naming it rather than reaching storage and being reported as a name
  collision. Whether the table is the right kind of table, `ACTIVE` and scannable stays the gate's
  question. The refusal covers the tables a caller **names** in `target` and `inputs`; a table an SQL
  statement's `FROM` names is read by the best-effort derivation, so one that does not exist leaves
  the derived input table absent on a disabled write rather than refusing it, and arming then refuses
  it as incomplete.

A storage-layer refusal that is reached anyway SHALL be reported by the constraint it broke: a
collision on the pipeline's name or target as HTTP 409, and a CHECK or foreign-key violation as HTTP 422
`pipeline_validation_failed` naming the rule — never as "name or target already in use".

A derived member SHALL be stored as an **empty collection rather than null** when it has no entries, so
that a partial declaration has one representation in storage and in memory.

**The deferral is safe because a disabled pipeline runs nothing.** ADAS's scheduler selects on
`(enabled, next_run_at)`; the enrich feed omits a pipeline whose declaration is incomplete whatever its
flag, and the Enrichment Runner executes only feed entries carrying `enabled: true`, so no incomplete
declaration is reached by either. What the gate protects is the moment the pipeline begins to
produce rows, and that moment has its own enforcement, unchanged.

**The gate's content SHALL NOT change.** Every check applied at registration today SHALL be applied at
arming, over the stored declaration, with the same status and the same error code.

#### Scenario: An incomplete declaration is stored while disabled

- **WHEN** `POST /v1/pipelines` registers a pipeline with `name`, `kind` and `target` and no other
  declaration member
- **THEN** the response is 201, the pipeline is stored `enabled: false`, and no kind gate has run

#### Scenario: A complete but invalid declaration is stored when the write does not arm

- **WHEN** `POST /v1/pipelines` registers an `aggregate` pipeline with a complete declaration that
  violates a governance rule of its kind
- **THEN** the response is 201 and the declaration is stored, because an `aggregate` registration lands
  disabled and the write therefore arms nothing; the refusal is raised by `POST …/preview` or by the
  patch that arms it

#### Scenario: Arming runs the gate the registration did not

- **WHEN** `PATCH /v1/pipelines/{name}` sets `enabled: true` on a pipeline whose stored declaration
  does not satisfy its kind's gate
- **THEN** the request is rejected, the pipeline stays disabled, and the stored declaration is unchanged

#### Scenario: A declaration write never erases a derived member

- **GIVEN** an `enrich` pipeline whose stored `output_bindings` were derived by an earlier gated write
- **WHEN** a declaration patch is applied while the pipeline is disabled and the new declaration's
  outputs cannot be re-derived
- **THEN** the previously stored `output_bindings` are retained rather than replaced with an empty
  mapping, and the catalog's drop/rename guard continues to see the pipeline as a writer of those
  columns

#### Scenario: A derivable member is re-derived on an ungated write

- **WHEN** a declaration patch of a disabled `aggregate` pipeline omits `group_by` and the target's
  `ordering_key` columns are all plain input columns of the same name
- **THEN** the group keys are derived and stored, exactly as they would be on a gated write

#### Scenario: A disabled pipeline with an incomplete declaration produces no rows

- **WHEN** a pipeline's stored declaration is incomplete and its trigger's moment arrives
- **THEN** ADAS's scheduler does not select it, the enrich feed does not carry it under any
  `enabled` filter, and nothing is written to its target

#### Scenario: Preview of an enabled pipeline re-gates according to its kind

- **WHEN** `POST /v1/pipelines/{name}/preview` is called for an enabled pipeline whose stored
  declaration no longer passes its kind's gate
- **THEN** an `aggregate` pipeline is refused with the status and code arming would give, while an
  `enrich` pipeline runs only the checks its enabled preview runs, not the gate, and is sampled

#### Scenario: An unparseable SQL draft is stored without an input table

- **WHEN** `POST /v1/pipelines` or a `PATCH /v1/pipelines/{name}` that leaves the pipeline disabled carries
  an `aggregate` `sql` that does not parse
- **THEN** the write succeeds, `sql` is stored verbatim and read back unchanged, the pipeline's input table
  is absent, and no parse error is raised

#### Scenario: An SQL draft derives its input table from the statement

- **WHEN** a write that leaves an `aggregate` pipeline disabled carries an `sql` whose `FROM` names an
  existing table and carries no `inputs`
- **THEN** the stored input table is the table the statement reads

#### Scenario: An SQL-mode draft does not derive group keys

- **WHEN** a write that leaves an `aggregate` pipeline disabled carries `sql` and no `group_by`, and the
  target's `ordering_key` columns are all plain input columns of the same name
- **THEN** no `group_by` is stored, and the authored projection serves `sql` without `group_by`

#### Scenario: Arming an SQL declaration runs the full SQL gate

- **WHEN** `PATCH /v1/pipelines/{name}` sets `enabled: true` on an `aggregate` pipeline whose stored `sql`
  parses but is not eligible for incremental refresh — for instance it carries a `HAVING` clause
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the clause, the
  pipeline stays disabled, and the stored `sql` is unchanged

#### Scenario: An armed SQL declaration is re-gated on every refresh

- **GIVEN** an enabled `aggregate` pipeline in SQL mode whose statement passed the gate when it was armed
- **WHEN** the input or an enrichment the statement references changes so that the statement no longer
  passes the gate, and the next refresh runs
- **THEN** the refresh runs the full SQL gate again, fails with the refusal arming would now give, and
  writes nothing to the target
