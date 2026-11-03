## MODIFIED Requirements

### Requirement: The enrich feed carries the transform, a resolved schema and a change token

The system SHALL serve the enrich feed as `GET
/v1/pipelines?kind=enrich[&enabled=…][&updated_since=…]&view=compiled`, returning each
selected pipeline with its **authored `transform` block**, the **resolved `response_schema`** the model
is held to, the pipeline's read source and derived grain, the read source's `version_column` (absent
when that source declares none), and a `generation` value the runner uses for change detection. The feed
carries **no HTTP `ETag`/304** — `generation` is the change signal and comparison is the consumer's,
done client-side per pipeline. `updated_since` SHALL filter to pipelines changed at or after the
given timestamp. For a `group` pipeline the feed SHALL additionally inline `trigger.group_by` and
`trigger.ready_when`, so a caller can route the pipeline to its group path and evaluate readiness
without a second round-trip.

The feed SHALL carry **no `evaluator` object**: each fact about the transform appears on the entry under
exactly one spelling — the pipeline's `name` and `generation`, and the `transform`'s own `type`, `model`,
`preset`, `params` and `request_template`, with `response_schema` the derived top-level member below. A
consumer SHALL need no second lookup to recover any of them, and SHALL stamp provenance from `generation`.

Reshaping this entry is a **breaking** change to the compiled projection, so the runner contract
version SHALL be raised — see [pipeline-registry](../pipeline-registry/spec.md), "Every pipeline
response carries the runner contract version". That, and nothing added here, is what makes the ordered
deploy a checked fact: the version is echoed on every response under `/v1/pipelines`, the feed's own
response included, and a consumer verifies the echo **before** interpreting the body. The failure it
prevents is expensive and otherwise silent — a consumer that reads an entry carrying no
`transform.inputs` substitutes no placeholders, calls the model with literal `{{placeholder}}` text, is
billed for the call and stores the answer as a label. The feed SHALL NOT grow a request parameter or a
body member of its own for this; the header already covers it, on this read and on every other.

The feed SHALL be the **compiled** projection, requested as `view=compiled` — see
[pipeline-registry](../pipeline-registry/spec.md), "A pipeline read selects an authored or a compiled
projection". The runner SHALL name the projection explicitly rather than relying on a default: the
authored projection is the default for every pipeline read, and it carries none of the resolved
members the runner needs. `?kind=enrich` without `view=compiled` is therefore an operator's listing
and SHALL cost no resolution.

An `enrich` entry SHALL carry the **response schema the model is held to** and the **output mapping
the write is validated against**, whether either was stored or composed:

- The entry's top-level `response_schema` SHALL be the declaration's **stored** schema when its
  `transform` carries one, and otherwise the schema composed from the pipeline's target and its
  `outputs` (see "The response schema is composed from the target and the transform"). A stored schema
  SHALL keep winning over composition for as long as the declaration stores it — the composed form is
  not a faithful reproduction of one, so serving a composed schema for a declaration that stores one
  would change a live prompt. Clearing it is possible, and is the operator's explicit act through a
  declaration patch that omits it.
- The entry's `outputs` SHALL be the pipeline's derived output mapping, one ordered entry per mapped
  target column, each carrying the exposed `name`, the physical `column`, and the transform resolved
  through the declaration's stored `output_vars` or its `outputs` alike. Resolving both stored shapes
  behind one accessor is what keeps the split invisible to the consumer.
- The feed SHALL carry neither `input_vars` nor `output_vars` as a derived member. Both described what
  the entry's own `transform.inputs` and `outputs` now carry, and serving them alongside would restore
  the two-lists-joined-by-name shape the fold removes. The authored `transform.output_vars`, where a
  folded legacy declaration has one, is a declaration member and is served under both projections as
  the authored declaration it is.

The transform is the half of an output that cannot be recovered by guessing: a consumer that loses it
falls back to reading `response[name]`, which an identity mapping survives and a real one does not —
storing a sentinel string where the transform would have written NULL. Folding it onto the output is
what removes that failure mode rather than documenting it.

An `enrich` pipeline whose output mapping resolves to **no columns** SHALL NOT be served on the feed:
it SHALL be omitted from the response and the omission logged. This is a wire-contract guarantee, not
an implementation detail, because the failure is silent and destructive rather than an error. A
consumer handed an empty mapping still writes the grain key and the provenance columns and still
stamps the group as computed — which against a `ReplacingMergeTree` keyed on the grain is an upsert
of a row whose label columns are all NULL over an existing, already-paid-for label, never retried,
with the model call made and charged first. An empty mapping is a bug in the derivation, never a
configuration, so the feed SHALL refuse to hand it over rather than serving a pipeline that looks
healthy.

An `enrich` pipeline whose declaration declares `outputs` but whose **composed response schema cannot be
built** SHALL likewise not be served, and the omission logged. It is the same failure in a different
member: such a declaration stores no `response_schema` of its own, so composition is the only source, and
a read deliberately swallows a composition failure rather than failing a whole listing over one drifted
target. Served with a full output mapping and no schema, the entry looks healthy while
`response_format` is never sent, the model answers free-form, and every row is rejected at validation —
with the call made and charged first. A declaration that declares **no** `outputs` is a different case
and SHALL still be served: a declaration folded from a version stored before that shape and carrying no
schema called the model unconstrained before this change too, and refusing it now would retire a live
pipeline rather than protect it. This is also why the fold migration SHALL NOT convert `output_vars`
into `outputs`: converting exactly this declaration would give it an `outputs` array and no stored
schema, which is the input composition fires on, and the next feed read would send a `response_format`
where none was sent before.

#### Scenario: A pipeline whose outputs compose to no schema is omitted from the feed

- **WHEN** an enabled `enrich` pipeline declares an `llm` transform that declares `outputs`, stores
  no `response_schema`, and whose outputs do not compose against its target
- **THEN** the pipeline is omitted from `GET /v1/pipelines?kind=enrich&enabled=true&view=compiled` and
  the omission is logged naming the pipeline and its target

#### Scenario: A folded declaration with neither outputs nor a schema is still served

- **WHEN** an enabled `enrich` pipeline's transform was folded from an `llm` evaluator version carrying
  `output_vars` and no `response_schema`
- **THEN** the pipeline is served, the entry carries no `response_schema`, and the model is
  called unconstrained exactly as it was before the fold

The response envelope, the four filters and the listing order are the registry's, not this
capability's — see [pipeline-registry](../pipeline-registry/spec.md), "Pipeline listing carries the
registry's filters, envelope and ordering". What this capability adds is what an `enrich` entry
carries. One consequence is worth stating because it is a wire change for the runner: the feed is an
**object envelope** (`{"pipelines": [ … ]}`), where the enrichment registry served a bare array.

What an `enrich` entry carries SHALL NOT depend on **which** of those filters selected it. Resolution
costs a bounded number of queries for the whole page, so there is no reason to serve a narrower entry
under a narrower filter — and a caller adding `target` in order to read a single pipeline's resolved
transform is precisely the caller a downgraded entry would silently fail. Stated as a requirement
because the failure is invisible: the members would be **absent**, which reads as "this pipeline has
no transform" rather than as "this listing did not resolve one".

The `enabled` parameter SHALL narrow which pipelines are returned, and SHALL accept only the exact
values `true` (enabled only) or `false` (disabled only); every other value SHALL be rejected with
HTTP 400 `bad_request` rather than falling back to a default, since a silently-ignored filter would
report the wrong set as the whole registry. This SHALL hold against the framework's lenient boolean
conversion, which is otherwise the default behaviour: `1`/`yes`/`on` and their negatives, a
differently-cased `TRUE`, a repeated `enabled` parameter, and — the case a caller actually hits — an
**empty** value (`?enabled=`, what interpolating an unset variable sends) SHALL all be rejected
rather than accepted or mapped to the omitted-parameter default. An **omitted** `enabled` SHALL
return **all** registered pipelines regardless of their flag: a listing reports what is registered,
not only what is running, so a pipeline an operator switched off is visible rather than silently
absent. Every entry SHALL carry the same fully-resolved shape regardless of the filter — a disabled
pipeline is registered but not running, not a degraded record — and `enabled` SHALL compose with
`updated_since` rather than replace it.

The listing SHALL be returned in a **total** order — oldest `updated_at` first, `name` breaking ties,
as [pipeline-registry](../pipeline-registry/spec.md) specifies — so repeated calls agree on the
sequence. `updated_at` alone does not order the set: it is truncated
to microseconds (below), so pipelines written in one transaction can share a value and shuffle
between calls.

This one endpoint is therefore both the operator's listing of the registry and the runner's sync feed,
and the runner reads it **unfiltered**: `GET /v1/pipelines?kind=enrich&view=compiled`. A pipeline's
`enabled` flag is its single run control (see [pipeline-registry](../pipeline-registry/spec.md), "A
pipeline has exactly one run control"), and a consumer that follows it has to tell a **stopped**
pipeline, whose cursor and queued work it keeps, from a **deleted** one, whose queued work it discards.
Under `enabled=true` both are simply absent; the unfiltered listing keeps the stopped one, marked.

A consumer that **executes** pipelines SHALL execute only entries carrying `enabled: true`, and SHALL
treat an entry carrying `enabled: false` as stopped — scheduling nothing for it and writing nothing for
it — since the unfiltered listing carries disabled pipelines, including ones that were never armed and
so never passed the declaration gate. The write paths' refusal of a disabled pipeline (see
[enrichment-runtime](../enrichment-runtime/spec.md), "A disabled pipeline's write paths refuse") stays
the backstop for a consumer that does not. A pipeline **absent** from the unfiltered listing is either
deleted or not servable — an incomplete declaration, or one the omission rules above withhold — and a
consumer SHALL NOT run it.

The `inputs` entry the feed reports SHALL be the table the runner is to scan — the pipeline's
declared read source when it has one, otherwise the target enrichment's `source_table` — so a runner
needs no second lookup and cannot scan the wrong table.

The feed SHALL resolve every pipeline in a **constant number of queries** regardless of how many
pipelines it returns — including the version-column resolution, which SHALL be one batched fetch
over the distinct read sources of the returned pipelines rather than a per-pipeline lookup.

A pipeline's `updated_at` SHALL be stored at the precision it is reported with, so the value any
pipeline read or write returns is usable verbatim as the next `updated_since`. The watermark is
inclusive (`>=`), so a reported timestamp finer than the stored one would place the watermark past
the pipeline and skip it — the registry therefore mints `created_at`/`updated_at` truncated to
microseconds, which is what the column holds.

#### Scenario: A reported updated_at is a usable watermark

- **WHEN** a pipeline is created or updated, and the `updated_at` from that response is passed
  straight back as `GET /v1/pipelines?kind=enrich&updated_since=<updated_at>`
- **THEN** that pipeline is returned, and a later read of the pipeline reports the same `updated_at`
  value

#### Scenario: The feed carries no evaluator object

- **WHEN** `GET /v1/pipelines?kind=enrich&enabled=true&view=compiled` is called
- **THEN** no entry carries an `evaluator` object, and each carries its authored `transform` and a resolved
  top-level `response_schema`

#### Scenario: A stale consumer refuses the shape rather than half-understanding it

- **WHEN** a consumer built against the previous contract version polls the feed
- **THEN** the response carries an `Adas-Contract-Version` the consumer does not recognise, and the
  consumer classifies the feed incompatible and evaluates nothing rather than rendering unsubstituted
  placeholders — while its data-plane calls are separately refused HTTP 409 by the header requirement

#### Scenario: The unfiltered listing returns every registered pipeline

- **WHEN** `GET /v1/pipelines?kind=enrich&view=compiled` is called
- **THEN** every registered pipeline is returned regardless of its `enabled` flag, each with its
  `transform`, its read source and derived grain, its `enabled` value, and a `generation`

#### Scenario: The runner's unfiltered feed tells a stopped pipeline from a deleted one

- **GIVEN** two armed `enrich` pipelines, one then disabled and the other deleted
- **WHEN** `GET /v1/pipelines?kind=enrich&view=compiled` is called
- **THEN** the disabled pipeline is returned with `enabled: false` and its full resolved entry, and the
  deleted one is absent

#### Scenario: The listing narrows to enabled pipelines

- **WHEN** `GET /v1/pipelines?kind=enrich&enabled=true` is called
- **THEN** only pipelines with `enabled: true` are returned — the set a runner runs

#### Scenario: The listing narrows to disabled pipelines

- **WHEN** `GET /v1/pipelines?kind=enrich&enabled=false` is called
- **THEN** only pipelines with `enabled: false` are returned

#### Scenario: A disabled pipeline is fully resolved

- **WHEN** a pipeline registered with `enabled: false` is returned by any of these listings
- **THEN** it carries the same authored transform, read source, derived grain and `version_column`
  an enabled pipeline carries — the filter decides membership, never the shape of an entry

#### Scenario: The enabled filter composes with the watermark

- **WHEN** `GET /v1/pipelines?kind=enrich&enabled=true&updated_since=<t>` is called
- **THEN** only enabled pipelines with `updated_at >= t` are returned — the filter narrows the
  watermarked set rather than replacing the watermark

#### Scenario: A non-boolean enabled value is rejected

- **WHEN** `GET /v1/pipelines?kind=enrich&enabled=<value that is not exactly true or false>` is
  called — including `1`, `yes`, `on`, `TRUE`, or the parameter repeated with two values
- **THEN** the request is rejected with HTTP 400 `bad_request` rather than served under a default
  filter

#### Scenario: An empty enabled value is rejected rather than read as omitted

- **WHEN** `GET /v1/pipelines?kind=enrich&enabled=` is called, the parameter present but valueless
- **THEN** the request is rejected with HTTP 400 `bad_request` — a present-but-empty filter is a
  caller error, and answering it with the whole registry is the silent mis-answer the filter exists
  to prevent

#### Scenario: The listing order is total

- **WHEN** `GET /v1/pipelines?kind=enrich` is called twice over an unchanged registry containing
  pipelines that share an `updated_at`
- **THEN** both calls return the pipelines in the same order, ties broken by `name`

#### Scenario: A target-filtered enrich listing is resolved like the unfiltered feed

- **WHEN** `GET /v1/pipelines?kind=enrich&target=<enrichment>` is called
- **THEN** the returned pipeline carries its authored transform, derived grain key, read source and
  `version_column` exactly as `GET /v1/pipelines?kind=enrich` would return them

#### Scenario: Sync carries the read source of a pipeline that declares one

- **WHEN** `GET /v1/pipelines?kind=enrich` is called and a returned pipeline declares an `inputs`
  entry other than its target enrichment's `source_table`
- **THEN** that pipeline's `inputs` entry is the declared read source, and its `version_column` is
  that source's declared version column

#### Scenario: Sync carries the source's version column

- **WHEN** `GET /v1/pipelines?kind=enrich` is called and a returned pipeline's read source declares
  scan metadata
- **THEN** that pipeline carries `version_column` equal to that source's declared version column

#### Scenario: Incremental sync filters by updated_since

- **WHEN** `GET /v1/pipelines?kind=enrich&updated_since=<t>` is called
- **THEN** only pipelines with `updated_at >= t` are returned

#### Scenario: Sync inlines the group declaration

- **WHEN** `GET /v1/pipelines?kind=enrich` is called and a returned pipeline has `trigger.kind:
  "group"`
- **THEN** that pipeline's `trigger.group_by` and `trigger.ready_when` are inlined alongside its
  authored transform

#### Scenario: A stored response schema is served verbatim

- **WHEN** `GET /v1/pipelines?kind=enrich` is called and a returned pipeline's `transform` carries a
  stored `response_schema`
- **THEN** that stored schema is served unchanged, property order included, and no composed schema
  replaces it

#### Scenario: A new-shape declaration's schema is composed

- **WHEN** `GET /v1/pipelines?kind=enrich` is called and a returned pipeline's `transform` declares
  `outputs` and carries no stored `response_schema`
- **THEN** the entry carries the schema composed from the pipeline's target and those `outputs`, and
  its `outputs` mapping carries each output's transform

#### Scenario: The feed carries the derived output mapping

- **WHEN** `GET /v1/pipelines?kind=enrich` is called for a pipeline whose declaration declares fifteen
  outputs, all resolving to target columns
- **THEN** the entry carries fifteen output bindings, each naming a target column's physical spelling

#### Scenario: A pipeline with no resolvable output mapping is omitted from the feed

- **WHEN** `GET /v1/pipelines?kind=enrich&enabled=true` is called and one enabled pipeline's output
  mapping resolves to no columns
- **THEN** that pipeline is absent from the response and the omission is logged, rather than served
  with an empty mapping a consumer would act on by overwriting existing labels with NULLs
