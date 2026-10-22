## ADDED Requirements

### Requirement: No evaluator surface remains

The system SHALL expose no route under `/v1/evaluators` and SHALL store no evaluator definition: an
enrichment's definition exists only as its pipeline's `transform`, versioned by the pipeline's `generation`
and attributed by the pipeline's audit trail. A request to any `/v1/evaluators` path SHALL be answered
exactly as a request to any other unmapped path is — for `GET`, HTTP 404 `not_found` in the `ErrorView`
shape — and the error codes `unknown_evaluator` and `evaluator_immutable` SHALL NOT be produced by any
route.

The `evaluator` and `evaluator_version` tables SHALL NOT exist in the control-plane store, and neither
SHALL the `pipeline.evaluator_name` / `pipeline.evaluator_version` pointer columns or their `pipeline_aud`
mirrors. The definitions those tables held are not carried anywhere else: a pre-fold prompt, and what an
already-stamped `evaluator_version` provenance value referred to, SHALL be understood to be unanswerable by
the service. The `evaluator_version` provenance column on an existing enrichment target is a separate
matter and is unaffected (see [enrichment-runtime](../enrichment-runtime/spec.md), "Pipeline-scoped
versioned write-back endpoint").

A pipeline request naming `evaluator_name` or `evaluator_version` SHALL keep being refused at the binding,
as specified in "ENRICH pipeline declaration binding a transform to a target enrichment".

#### Scenario: The evaluator listing is an unmapped path

- **WHEN** `GET /v1/evaluators` is called by a caller holding `REGISTRY_READ`
- **THEN** the response is HTTP 404 with error code `not_found`

#### Scenario: A version path is answered as an unmapped path

- **WHEN** `GET`, `PUT` or `DELETE` is sent to `/v1/evaluators/{name}/versions/{version}`
- **THEN** each is answered as the same method on an unmapped path is answered, the `GET` with HTTP 404
  `not_found`, and none answers `evaluator_immutable` or `unknown_evaluator`

#### Scenario: No evaluator table or pointer column remains

- **WHEN** the control-plane migrations have run
- **THEN** neither an `evaluator` nor an `evaluator_version` table exists, and neither `pipeline` nor
  `pipeline_aud` carries an `evaluator_name` or `evaluator_version` column

## MODIFIED Requirements

### Requirement: A string output binds to an enum target column

An output whose value is a `string` SHALL be bindable to a target column of type `enum`: an
enum column holds strings, and ClickHouse validates the written value against the declared domain at
insert time. Without this the governance gate's exact-type match would reject the binding and make
an enum column unwritable by any enrichment.

Where a declaration carries its outputs as `transform.outputs` (the current shape) an output carries
**no declared logical type at all**, so there is no spelling of `enum` for the gate to refuse: for an
`llm` output the type comes from the target column itself, and for a `sql` output it is the type the
SQL front end infers for the expression, checked directly against the column. The refusal below
therefore governs a **folded old-shape** `transform.output_vars` declaration only, where a
per-variable `type` still exists.

`enum` SHALL NOT be accepted as a stored output variable's declared logical type. The pipeline declares
the value it produces; the column declares the domain that value must fall into. Accepting `enum` as
a variable type would advertise a domain check the gate does not perform — the same reasoning that
already refuses a parameterized `array<...>` spelling. The declaration SHALL be refused with a
message saying to declare the output as `string` and let the target column carry the domain.

Binding SHALL remain exact in the other direction: a value whose type is neither `string` nor
numeric SHALL still be rejected against an `enum` column.

#### Scenario: A string output binds to an enum column

- **WHEN** a declaration carries an output bound to a target column of type `enum`, whose
  value is a `string`
- **THEN** the pipeline is accepted, and a materialization writing a value from the column's
  declared domain succeeds

#### Scenario: A value outside the domain fails at write time, not registration

- **WHEN** a pipeline bound to an `enum` column produces a value outside that column's declared
  domain
- **THEN** the pipeline remains valid at registration and the failure surfaces when the
  materialization writes, through the existing materialization failure contract

#### Scenario: An enum domain composed from the column makes the failure unrepresentable for an llm output

- **WHEN** an `llm` declaration's output binds to a target column declaring `enum_values`, and the
  composed response schema carries that column's domain
- **THEN** the model is constrained to the column's own domain under strict schema enforcement, so a
  value the column cannot store is refused at the model boundary rather than at insert

#### Scenario: A folded output variable declared enum is refused

- **WHEN** a pipeline's `transform.output_vars`, folded from an old-shape evaluator version, declares
  the logical type `enum`
- **THEN** the declaration is rejected with a message directing the author to declare `string` and
  let the target column declare the domain

#### Scenario: A non-string output against an enum column is still rejected

- **WHEN** a pipeline's output value is a `timestamp` and it binds to a target column of type `enum`
- **THEN** the binding is rejected by the governance gate

### Requirement: A transform's outputs are one fact in two stored shapes

A folded transform SHALL carry its outputs in whichever of the two shapes its source evaluator version
stored, and the fold SHALL convert **neither** into the other. Two shapes of the same fact therefore
continue to coexist indefinitely, now inside `pipeline.spec`: a transform folded from a version
registered before the `outputs` shape carries `output_vars` (and, for `llm`, a stored
`response_schema`), and one folded from a later version — or authored after this change — carries
`outputs`. Every consumer SHALL resolve them through **one accessor** that returns `outputs` when
present **and non-empty** and falls back to `output_vars` otherwise — the emptiness test is part of the
rule, not an implementation detail, because an `outputs` array that is present and empty is a row the
accessor must still read as old-shape.

**Response-schema composition is the one deliberate exception, and SHALL be stated as one.** Composition
reads `transform.outputs` alone and SHALL NOT go through the accessor: a declaration carrying
`output_vars` and no stored `response_schema` calls the model unconstrained today and SHALL keep doing
so, and routing composition through the accessor would compose a schema for exactly that declaration —
sending a `response_format` where none was sent before, which is the silent live-prompt change this
requirement's copy-not-convert rule exists to prevent. Every other consumer — the gate, the cycle scan,
the drop and rename guards, the derived output mapping, expression analysis — uses the accessor.

Carrying both verbatim rather than converting is what makes the fold migration a pure column copy, and
the reason is sharper than economy. A `sql` row converts mechanically and losslessly. An `llm` row does
not, and the blocking case is the row carrying `output_vars` and **no** `response_schema`: such a
declaration calls the model unconstrained today and is served that way deliberately, but converting it
would hand it an `outputs` array with no stored schema — exactly the input composition fires on — so
the next feed read would send a `response_format` where none was ever sent. That is a live prompt
change made by a migration on an environment nobody inspected. A copying migration is correct for every
environment by construction; a converting one has to be lucky.

A single accessor is required rather than recommended, because the consumers are not all validation
paths: one of them feeds the catalog's drop-and-rename dependency guard, where a missed resolution
does not fail a request but silently makes an enrichment column a Class-1 pipeline reads droppable
(see "An ENRICH pipeline's enrichment references are protected against drop and rename"). Another is
the declaration gate itself, which requires at least one output: reading `outputs` alone there would
refuse every pipeline the fold produced.

Because the transform is now authored rather than stored immutably, both legacy members are part of
the **authored surface** — served under `view=source`, accepted on `PATCH`, refused on `POST` in the
case of `output_vars` — and a declaration carrying both shapes is refused with HTTP 422. See "ENRICH
pipeline declaration binding a transform to a target enrichment" for the three rules that bound this.

A stored `response_schema` SHALL keep winning over composition **for as long as the declaration stores
it**. The composed form is not a faithful reproduction of a stored one — stored enum order, stored type
spelling and the stored property order can each differ from what composition produces — and under
strict schema enforcement property order is generation order, so serving a composed schema for a
declaration that stores one would silently change a live prompt. What changes with the fold is only
that "for as long as" is now finite: a declaration patch that omits `response_schema` clears it, which
is how a legacy declaration is migrated onto `outputs` at all, and is an act rather than a drift.

The **pipeline** feed is the surface where the two stored shapes are reconciled, and there neither
list is served at all: the feed carries neither `input_vars` nor `output_vars`, and the entry's
own `outputs` carries the resolved mapping with each transform folded in, resolved from whichever
shape the declaration stores. A consumer of the feed therefore never learns which shape it was, which
is the property that lets the two coexist indefinitely.

#### Scenario: Both shapes resolve for expression analysis

- **WHEN** a Class-1 pipeline carries a folded new-shape `sql` transform and a materialize is requested
- **THEN** the expressions are read from `outputs` and analyzed exactly as they would be from
  `output_vars`, and the materialize proceeds

#### Scenario: The fold converts neither shape

- **WHEN** the fold migration folds an old-shape evaluator version into a pipeline's `spec`
- **THEN** `output_vars` and `response_schema` are carried across byte for byte, no `outputs` array is
  synthesised, and the pipeline resolves its outputs through the same accessor

#### Scenario: An old-shape declaration reads back as stored on the pipeline

- **WHEN** `GET /v1/pipelines/{name}?view=source` is called for a pipeline whose transform was folded from
  a version registered before the `outputs` shape
- **THEN** the response carries its stored `transform.output_vars`, and its stored
  `transform.response_schema` where it has one, unchanged

### Requirement: Registry reads and writes are authorized by registry privileges

The **pipeline registry** SHALL be authorized by privilege rather than by application role.
Reading it, the runner's pipeline sync feed included, SHALL require `REGISTRY_READ`. An enrichment's
definition is authored on its pipeline as `transform`, so authoring it is `REGISTRY_ADMIN` on the
pipeline surface. The pipeline endpoints the sync feed and the ENRICH declaration share with the
aggregate kind are specified once, in [aggregate-pipeline](../aggregate-pipeline/spec.md).

`REGISTRY_READ` SHALL NOT confer any table privilege: an identity holding it may enumerate pipelines
without being able to read the tables they reference. Conversely a table privilege SHALL
NOT confer `REGISTRY_READ`.

Registry payloads name columns — a pipeline's `filter_sql`, `group_by`, `member_select` and
`ready_when`, and the `transform`'s request template — and the service SHALL NOT redact them.
`REGISTRY_READ` therefore discloses column names, consistent with column names not being secret;
column **values** remain governed by the table privileges.

#### Scenario: Registry read does not confer table read

- **WHEN** a caller holding `REGISTRY_READ` and no table privilege lists pipelines and then queries the
  source one of those pipelines reads
- **THEN** the pipeline listing succeeds and the query reports an unknown entity

#### Scenario: Authoring requires REGISTRY_ADMIN

- **WHEN** a caller holding `REGISTRY_READ` but not `REGISTRY_ADMIN` registers a pipeline
- **THEN** the response is `403 forbidden`

#### Scenario: The sync feed needs only REGISTRY_READ

- **WHEN** an Enrichment Runner holding `REGISTRY_READ` and per-table grants requests the pipeline sync
  feed
- **THEN** the feed is returned, and the runner needs no bundle conferring global table read

## REMOVED Requirements

### Requirement: Read evaluators

**Reason**: The archive was retained after the fold only so a pre-fold prompt and an already-stamped
`evaluator_version` stayed readable. No pipeline path consults it, nothing writes it, and its tables are
dropped with this change (see "No evaluator surface remains").

**Migration**: An enrichment's definition is read from its pipeline — `GET /v1/pipelines/{name}` (the
`transform` block) — and its history from the pipeline's audit trail. An operator who wants the pre-fold
definitions dumps the `evaluator` and `evaluator_version` tables before upgrading.
