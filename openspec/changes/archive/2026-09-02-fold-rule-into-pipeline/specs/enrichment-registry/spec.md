## REMOVED Requirements

### Requirement: One rule per target enrichment

**Reason**: One writer per target is a registry invariant, not an enrichment one. Stating it here left the
same rule stated twice — once as `UNIQUE(target_enrichment)` and once as `UNIQUE(target)` on the aggregate
side — and made the two registries' inability to contend an argument about disjoint target shapes rather
than a constraint. After the fold it is one `UNIQUE (target)` holding across kinds.

**Migration**: [pipeline-registry](../pipeline-registry/spec.md) → "Pipeline registry object", which carries
the enrichment reason forward verbatim: enrichment tables are `ReplacingMergeTree` with full-row replace by
grain, so two pipelines writing the same grain would clobber each other's columns, and multi-label output is
one evaluator emitting several columns rather than a second pipeline. The status is unchanged at 409; the
code changes from `rule_validation_failed` to `pipeline_conflict`, because a conflict is not a validation
failure.

## RENAMED Requirements

- FROM: `### Requirement: Rule CRUD binding an evaluator to a target enrichment`
- TO: `### Requirement: ENRICH pipeline declaration binding an evaluator to a target enrichment`
- FROM: `### Requirement: A rule's read source is declarable`
- TO: `### Requirement: An ENRICH pipeline's read source is declarable`
- FROM: `### Requirement: Rule listing and runner sync feed with resolved evaluator and change detection`
- TO: `### Requirement: The enrich feed carries the resolved evaluator and a change token`
- FROM: `### Requirement: A rule reads its read source's entity, not only its read source table`
- TO: `### Requirement: An ENRICH pipeline reads its read source's entity, not only its read source table`
- FROM: `### Requirement: A rule's enrichment references must not form a dependency cycle`
- TO: `### Requirement: An ENRICH pipeline's enrichment references must not form a dependency cycle`
- FROM: `### Requirement: A rule's enrichment references are protected against drop and rename`
- TO: `### Requirement: An ENRICH pipeline's enrichment references are protected against drop and rename`

## MODIFIED Requirements

### Requirement: Register an evaluator with immutable versioning

The system SHALL register evaluators at `POST /v1/evaluators` from a body carrying
`{name, type, preset?, model?, params, request_template?, input_vars, response_schema?,
output_vars}`. Evaluator definitions SHALL be stored across two tables: `evaluator(name PK,
latest_version, …)` and `evaluator_version(name, version, …, PK(name,version))`. Registering a
`name` that does not yet exist SHALL create version `1`; registering an existing `name` SHALL
create a **new version** (`max(version)+1`) and never mutate an existing version row. `latest`
SHALL resolve to `max(version)`. There SHALL be no update or delete of an existing version; an
attempt SHALL be rejected with HTTP 409 `evaluator_immutable`.

`type` SHALL be one of `llm` (a model call) or `sql` (a deterministic derivation computed in the
database, see [class1-enrichment](../class1-enrichment/spec.md)). The registration shape SHALL be
dispatched on `type`:

- **`llm`** — `preset` and `model` SHALL be required and non-blank, and the only accepted `preset`
  SHALL be `chat_completion`. Output variables carry `jsonata`.
- **`sql`** — `preset`, `model`, `request_template`, `input_vars`, and `response_schema` SHALL be
  absent, and every entry of `output_vars` SHALL carry a non-blank `sql` expression together with a
  logical `type`.

A body violating its type's shape SHALL be rejected with HTTP 422 `pipeline_validation_failed`.

The code is `pipeline_validation_failed` rather than the `rule_validation_failed` it was, and the rename
reaches this requirement for a reason worth stating rather than leaving to be rediscovered: evaluator
validation has always raised the **same** exception the declaration gate does, so it has always reported
under the declaration's code. That coupling predates the fold and is not introduced by it; what the fold
changes is the code's spelling. Giving evaluators a code of their own would be new API surface and is not
part of this change.

#### Scenario: First registration creates version 1

- **WHEN** `POST /v1/evaluators` is called with a `name` that does not exist
- **THEN** the evaluator is created at version `1`, `latest_version=1`, and HTTP 201 is returned

#### Scenario: Re-registering a name creates a new immutable version

- **WHEN** `POST /v1/evaluators` is called with an existing `name` and edited fields
- **THEN** a new `evaluator_version` row is created at `max(version)+1`, prior versions are
  unchanged, and `latest_version` advances

#### Scenario: Mutating or deleting an existing version is rejected

- **WHEN** a request attempts to update or delete an already-stored evaluator version
- **THEN** the request is rejected with HTTP 409 and error code `evaluator_immutable`

#### Scenario: A non-`chat_completion` preset is rejected

- **WHEN** `POST /v1/evaluators` is called with a `preset` other than `chat_completion`
- **THEN** the request is rejected with a 4xx client error (the bounded `preset` enum rejects an
  unsupported value at request binding, HTTP 400)

#### Scenario: An llm evaluator without model or preset is rejected

- **WHEN** `POST /v1/evaluators` is called with `type: "llm"` and a blank or absent `model` or `preset`
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: A sql evaluator registers without LLM-only fields

- **WHEN** `POST /v1/evaluators` is called with `type: "sql"`, no `model`/`preset`/`request_template`/
  `response_schema`, and output variables each carrying `sql` and `type`
- **THEN** the evaluator version is created and HTTP 201 is returned


### Requirement: ENRICH pipeline declaration binding an evaluator to a target enrichment

The system SHALL manage pipelines via `POST /v1/pipelines`, `PATCH /v1/pipelines/{name}`, `DELETE
/v1/pipelines/{name}`, and `GET /v1/pipelines/{name}`. An ENRICH pipeline SHALL bind an
`evaluator_name` (+ optional `evaluator_version`, null meaning `latest`) to a `target`, plus an
optional single-entry `inputs`, `trigger.kind`, optional `trigger.cron`, `trigger.group_by`,
`trigger.ready_when`, `filter`, `input_bindings`, `output_bindings`, and execution knobs
(`sampling`, `cadence`, `batch_scan_limit`, `batch_chunk`, `rate_rpm`, `priority`, `enabled`). The
pipeline's **grain SHALL be derived** from the target enrichment's `grain` and SHALL NOT be
re-declared on the pipeline. The pipeline's **read source MAY be declared** as its `inputs` entry
and SHALL default to the target enrichment's `source_table` when omitted (see "A pipeline's read
source is declarable"). Each mutating pipeline operation SHALL bump a `generation` counter and
`updated_at`. An unknown pipeline name SHALL be rejected with HTTP 404 `unknown_pipeline`.

`enabled` SHALL be **required** on a registration of this kind and SHALL carry no implicit default:
whether a pipeline starts live is an operator decision, and this kind is registered with the flag it
is given, so an omitted one would leave a pipeline that looks registered and never runs. An omitted
or null `enabled` SHALL be rejected with HTTP 422 `pipeline_validation_failed` naming the field.

The status is 422 rather than the 400 this answered while enrichment had a registry of its own, and
the reason is the merge: the requirement is now **per-kind** — an `aggregate` pipeline ignores the
flag entirely, because its dry-run preview is what the disabled state exists for — and a per-kind
requirement cannot be expressed by request-shape validation. See
[pipeline-registry](../pipeline-registry/spec.md), "Register and enable lifecycle".

A returned pipeline SHALL carry its resolved `inputs` entry — its read source, declared or derived —
and SHALL additionally carry `version_column`: the **physical name of the read source's version
column**, derived from that source's catalog scan metadata (`table_definition.version_column`) — the
same fact the scan path resolves, so the two cannot disagree. `version_column` SHALL NOT be
declarable on the pipeline. A scan page projects that column under its own name, and for a `group`
pipeline its value is the group's activity timestamp; surfacing the name is what lets a caller read
it per source rather than holding one configured name for every source it scans. When the read
source is unknown or declares no scan metadata, `version_column` SHALL be **absent** and the read
SHALL still succeed — the pipeline is valid, and an unscannable source is rejected on the scan path
(HTTP 422) rather than on a registry read.

Because `version_column` is derived rather than declared, it can change without any pipeline being
mutated: `PATCH /v1/tables/{source}/schema` MAY rename the column and repoint the stored name (see
[table-management](../table-management/spec.md)). A pipeline mutation is therefore **not** the only
event that bumps `generation`: when a source's `version_column` is repointed, every pipeline **whose
read source is that source** SHALL have its `generation` incremented and its `updated_at` advanced,
in the same transaction as the catalog repoint. The bump SHALL follow the read source, so a pipeline
that declares one is bumped by the table it actually scans and not by its target enrichment's
`source_table`. Both `generation` and `updated_at`, not just `generation` — the sync feed selects by
`updated_at`, so a bumped generation the feed does not return would be no signal at all. Without
this a runner detecting change by `generation` would keep the pre-rename name and read a key its
scan pages no longer carry.

`trigger.kind` SHALL be one of `on_ingest`, `schedule`, or `group`. A `group` pipeline fires once per
**group key** rather than once per row: it declares `trigger.group_by` (the group key) and `trigger.ready_when`
(`{signal?, idle?, max_staleness?, cost_ceiling?}`), the declaration of when a group is considered
complete enough to evaluate. A `group` pipeline SHALL NOT carry `trigger.cron` — its cadence is the
caller's readiness sweep, not a cron expression — which the existing cron-iff-`schedule` invariant
already enforces. A `group` pipeline writes exactly one enrichment row per group key through the existing
pipeline-scoped write-back, keyed by `trigger.group_by`, and the target enrichment's `ReplacingMergeTree` collapses
by that key.

#### Scenario: Create a pipeline derives its grain and defaults its source

- **WHEN** `POST /v1/pipelines` is called with a valid evaluator and `target` and no `inputs`
- **THEN** the pipeline is created with `grain` derived from the target enrichment and `inputs`
  defaulted to that enrichment's `source_table`, `enabled` per the request, `generation`
  initialized, and HTTP 201 returned

#### Scenario: Omitted enabled is rejected

- **WHEN** `POST /v1/pipelines` is called with `kind: "enrich"` and `enabled` absent or null
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the field, and
  no pipeline is created

#### Scenario: A returned pipeline carries the read source's version column

- **WHEN** a pipeline is created, updated, or read and its read source declares scan metadata
- **THEN** the response carries `version_column` equal to that source's declared version column

#### Scenario: A source without scan metadata omits version_column

- **WHEN** a pipeline is read and its read source declares no version column (or the source is
  unknown)
- **THEN** `version_column` is absent from the response and the read succeeds

#### Scenario: Update bumps generation

- **WHEN** `PATCH /v1/pipelines/{name}` changes a pipeline
- **THEN** the stored pipeline is updated, `generation` is incremented, and `updated_at` advances

#### Scenario: Repointing the read source's version column bumps generation

- **WHEN** `PATCH /v1/tables/{source}/schema` renames the column named by that source's
  `version_column`, and a pipeline's read source is that source
- **THEN** that pipeline's `generation` is incremented and its `updated_at` advances in the same
  transaction as the catalog repoint, and a subsequent `GET /v1/pipelines/{name}` carries the renamed
  `version_column`
- **AND** a pipeline whose read source is a different table is untouched, including a pipeline whose
  target enrichment hangs off the repointed source but which declares another read source

#### Scenario: A repointed version column reaches an incremental sync

- **WHEN** a runner has synced with `updated_since` and the source then repoints its
  `version_column`
- **THEN** the next sync at that watermark returns the affected pipelines, each carrying the new
  `version_column` and a higher `generation`

#### Scenario: Renaming a non-version column of the source bumps nothing

- **WHEN** `PATCH /v1/tables/{source}/schema` renames a source column that is neither the source's
  `version_column` nor its `identity_column`
- **THEN** no pipeline's `generation` or `updated_at` changes

#### Scenario: Delete removes the pipeline

- **WHEN** `DELETE /v1/pipelines/{name}` is called for an existing pipeline
- **THEN** the pipeline is removed and HTTP 204 returned

#### Scenario: An unknown pipeline is rejected

- **WHEN** any pipeline read/patch/delete targets a name that does not exist
- **THEN** the request is rejected with HTTP 404 and error code `unknown_pipeline`

#### Scenario: Create a group pipeline

- **WHEN** `POST /v1/pipelines` is called with `trigger.kind: "group"`, a `trigger.group_by` equal
  to the target enrichment's grain key, and a `trigger.ready_when` carrying at least one readiness
  condition
- **THEN** the pipeline is created and HTTP 201 returned


### Requirement: An ENRICH pipeline's read source is declarable

An ENRICH pipeline SHALL have a **read source**: the single table it reads to derive its values. It
MAY be declared as the single `inputs` entry on `POST /v1/pipelines` / `PATCH /v1/pipelines/{name}`,
and when omitted SHALL default to the target enrichment's `source_table`. Every declaration
registered before this capability therefore keeps its exact behaviour, and `inputs` stays an
additive, optional field.

A declared `inputs` entry SHALL name an existing, `ACTIVE` table of kind `source`; an unknown name,
a name of kind `enrichment`, and a name whose definition is not `ACTIVE` SHALL each be rejected with
HTTP 422 `pipeline_validation_failed`. Only an `ACTIVE` definition has a confirmed ClickHouse table
behind it, and a `FAILED` one keeps the columns its failed schema definition persisted — so without
the status check it could satisfy both invariants below and still be a table no scan can read. This
mirrors the target enrichment, which must also be `ACTIVE`.

The read source is what every governed read of the pipeline resolves: `filter`, `trigger.ready_when.signal`,
`trigger.member_select`, `input_bindings` and a `sql` evaluator's output expressions are validated against its
**entity** — the read source plus its `ACTIVE` enrichments, as "A pipeline reads its read source's entity"
defines — and the incremental scan, the group-input assembly, and the Class-1 materialize page it. It is
**not** the same fact as the target enrichment's `source_table`, which remains the enrichment's immutable
read-side attachment — the table the enrichment auto-joins into on the shared grain key, whose schema
view flattens it, and which cannot be deleted while it has enrichments. Decoupling the two is what allows
an enrichment to hang off one table while being derived from another: `conversations_feedback` is an
enrichment of `conversations` keyed on `chat_id`, computed by a `group` pipeline reading `rate_analytics`,
which carries `chat_id` too.

The two cases are **not distinguishable in a pipeline read**: `inputs` reports the resolved read
source, declared or derived alike (see the declaration requirement above). A caller that echoes a
read response back into `PATCH /v1/pipelines/{name}` therefore *declares* what the pipeline
previously derived, which is validated more strictly by the invariants below — omitting `inputs` is
how a caller keeps a pipeline following its target.

Because a declared read source is no longer guaranteed to be join-compatible with the target by
construction, the gate SHALL enforce two invariants in its place on a **declared** read source, on
register and on enable, each rejected with HTTP 422 `pipeline_validation_failed`:

1. **The grain key is reachable on the read source's entity, at a compatible type.** The target
enrichment's grain key SHALL resolve to a field of the read source's entity — either a declared
column of the read source itself (matched on either its logical `name` or its `source_name`), or the
qualified flat name of a column of one of the read source's `ACTIVE` enrichments whose last segment
is the grain key. Its declared type SHALL be compatible with the type the target enrichment is keyed
at. The pipeline's derived rows are keyed by that column, and the target is read through a grain
join on it, so a read source whose entity does not carry it could produce no addressable row, and
one carrying a same-named column of another type would key the enrichment with values its grain join
cannot match. A grain key reached **only through an enrichment** SHALL be admitted for a
`group`-trigger pipeline alone, which names the qualified field as its `trigger.group_by` and has
every read project it under the plain grain key. A row-grain pipeline declares no `trigger.group_by`
and reads the grain key under its own bare name, which the read source does not carry — and many
source rows share one enrichment value, so it would write many derived rows onto one target key for
the target's `ReplacingMergeTree` to collapse arbitrarily. Where more than one `ACTIVE` enrichment
of the read source declares the grain key, a `trigger.group_by` naming one of them SHALL settle
which is meant; a declaration naming none SHALL be rejected as ambiguous. 2. **The read source is
scannable.** The read source SHALL declare scan metadata — both `version_column` and
`identity_column` (see [enrichment-runtime](../enrichment-runtime/spec.md)). Every pipeline-scoped
read already requires the pair; checking it at registration reports the misdeclaration to the author
rather than at the runner's first scan.

Neither invariant SHALL be applied to a **derived** read source. The first holds there by
construction — an enrichment's grain key is validated to be a column of its `source_table` when its
schema is written — so applying it would only re-check a catalog fact. The second is a genuinely new
requirement that a derived source need not meet: declaring scan metadata is optional, a pipeline
over an enrichment of an unscannable source registers today and is refused on the scan path instead
(HTTP 422), and that pipeline SHALL remain registrable. Declaring a read source must not invalidate
pipelines that are valid without one.

#### Scenario: A pipeline omitting source derives it from the target enrichment

- **WHEN** `POST /v1/pipelines` is called without `inputs`
- **THEN** the pipeline's read source is the target enrichment's `source_table`, and the created
  pipeline reports that table as its `inputs` entry

#### Scenario: A pipeline declares a read source other than the target's source table

- **WHEN** `POST /v1/pipelines` is called for a target enrichment of `conversations` keyed on
  `chat_id`, with `inputs: ["rate_analytics"]`, a `filter` over `rate_analytics` columns, and
  `trigger.group_by: "chat_id"`
- **THEN** the pipeline is created with HTTP 201, its `inputs` entry is `rate_analytics`, and the
  target enrichment's own `source_table` is unchanged

#### Scenario: A declared read source that is not a source table is rejected

- **WHEN** `POST /v1/pipelines` declares an `inputs` entry that does not exist, or that names an
  enrichment
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: A declared read source that is not ACTIVE is rejected

- **WHEN** `POST /v1/pipelines` declares an `inputs` entry naming a `source`-kind table whose status
  is `pending` or `failed`
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`,
  because no materialized table stands behind it

#### Scenario: A read source whose entity does not reach the target's grain key is rejected

- **WHEN** a pipeline declares an `inputs` entry that carries no column matching the target
  enrichment's grain key, and none of that source's `ACTIVE` enrichments carries one either
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`, naming
  the grain key the read source's entity must reach

#### Scenario: A grain key reached through an enrichment of the read source is accepted

- **WHEN** a pipeline declares `inputs: ["dial_usage_log"]` for a target enrichment keyed on
  `client_session_id`, which `dial_usage_log` does not declare but its `ACTIVE` enrichment
  `usage_client_identity` does, and declares
  `trigger.group_by: "usage_client_identity.client_session_id"`
- **THEN** the pipeline is created with HTTP 201, and its derived rows are keyed by the plain grain
  key `client_session_id`

#### Scenario: A grain key named without its enrichment qualifier is rejected

- **WHEN** the same pipeline declares `trigger.group_by: "client_session_id"` while no column of
  that name is declared on `dial_usage_log` itself
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`, naming
  the qualified spelling `<enrichment>.<column>` to use instead — an enrichment column has no
  unqualified spelling in the entity namespace

#### Scenario: A row-grain pipeline may not key its rows on an enrichment column

- **WHEN** a pipeline for the same target declares `inputs: ["dial_usage_log"]` with `trigger.kind:
  "on_ingest"`, so its grain key is reachable only through an enrichment and it declares no
  `trigger.group_by`
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`, naming
  the group trigger as the only shape that may key derived rows on an enrichment column

#### Scenario: A grain key reached through an enrichment of another source is rejected

- **WHEN** a pipeline declares a `trigger.group_by` qualified with an enrichment that is not an
  `ACTIVE` enrichment of the pipeline's own read source
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: A read source whose grain-key column has an incompatible type is rejected

- **WHEN** a pipeline declares an `inputs` entry whose entity reaches the target enrichment's grain
  key as a field of a type incompatible with the type the target is keyed at, whether that field is
  a source column or an enrichment column
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: A declared read source without scan metadata is rejected at registration

- **WHEN** a pipeline declares an `inputs` entry whose `table_definition` declares no
  `version_column` or no `identity_column`
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed` at
  registration, rather than being accepted and failing at the first scan

#### Scenario: A pipeline deriving an unscannable read source still registers

- **WHEN** a pipeline declares no `inputs` and its target enrichment's `source_table` declares no
  scan metadata
- **THEN** the pipeline is created, unchanged from the behaviour before the read source was
  declarable, and the scan path is what refuses it

#### Scenario: Governed SQL is scoped to the declared read source's entity

- **WHEN** a pipeline declares `inputs: ["rate_analytics"]` and a `filter` referencing a column of
  `conversations` that neither `rate_analytics` nor any of its `ACTIVE` enrichments carries
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed` —
  entity scoping follows the read source, not the target enrichment's `source_table`


### Requirement: The enrich feed carries the resolved evaluator and a change token

The system SHALL serve the enrich feed as `GET
/v1/pipelines?kind=enrich[&enabled=…][&updated_since=…]`, returning each selected pipeline with its
**resolved pinned evaluator inlined** (the concrete version, resolving `latest` at sync time), the
pipeline's read source and derived grain, the read source's `version_column` (absent when that
source declares none), and a `generation` value the runner uses for change detection. The feed
carries **no HTTP `ETag`/304** — `generation` is the change signal and comparison is the consumer's,
done client-side per pipeline. `updated_since` SHALL filter to pipelines changed at or after the
given timestamp. For a `group` pipeline the feed SHALL additionally inline `trigger.group_by` and
`trigger.ready_when`, so a caller can route the pipeline to its group path and evaluate readiness
without a second round-trip.

The response envelope, the four filters and the listing order are the registry's, not this
capability's — see [pipeline-registry](../pipeline-registry/spec.md), "Pipeline listing carries the
registry's filters, envelope and ordering". What this capability adds is what an `enrich` entry
carries. One consequence is worth stating because it is a wire change for the runner: the feed is an
**object envelope** (`{"pipelines": [ … ]}`), where the enrichment registry served a bare array.

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

The listing SHALL be returned in a **total** order — oldest `updated_at` first, `id` breaking ties —
so repeated calls agree on the sequence. `updated_at` alone does not order the set: it is truncated
to microseconds (below), so pipelines written in one transaction can share a value and shuffle
between calls.

This one endpoint is therefore both the operator's listing of the registry (no filter) and the
runner's sync feed (`enabled=true`). A consumer that **executes** pipelines SHALL pass
`enabled=true` explicitly rather than relying on the default, since the unfiltered listing carries
disabled pipelines.

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

#### Scenario: The unfiltered listing returns every registered pipeline

- **WHEN** `GET /v1/pipelines?kind=enrich` is called
- **THEN** every registered pipeline is returned regardless of its `enabled` flag, each with its
  resolved evaluator version inlined, its read source and derived grain, its `enabled` value, and a
  `generation`

#### Scenario: The listing narrows to enabled pipelines

- **WHEN** `GET /v1/pipelines?kind=enrich&enabled=true` is called
- **THEN** only pipelines with `enabled: true` are returned — the set a runner executes

#### Scenario: The listing narrows to disabled pipelines

- **WHEN** `GET /v1/pipelines?kind=enrich&enabled=false` is called
- **THEN** only pipelines with `enabled: false` are returned

#### Scenario: A disabled pipeline is fully resolved

- **WHEN** a pipeline registered with `enabled: false` is returned by any of these listings
- **THEN** it carries the same resolved evaluator, read source, derived grain and `version_column`
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
- **THEN** both calls return the pipelines in the same order, ties broken by `id`

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
  resolved evaluator


### Requirement: Static governance gate on register and enable

The system SHALL statically validate a pipeline on register and on enable; each failure SHALL be
rejected with HTTP 422 `pipeline_validation_failed` (except entitlement and immutability, which use
their own codes) with detail identifying the failing check. The gate SHALL enforce: (1) the
`target` exists, is `ACTIVE`, and is kind=enrichment, and every output-binding column
exists on it with a type matching the output-var type via the shared JSON→ClickHouse type-mapping
util; (2) the pipeline's **read source** resolves — a declared `inputs` entry names an existing `source`-kind
table, and an omitted one defaults to the target enrichment's `source_table` — and satisfies the
grain-key-reachable and scan-metadata invariants (see "A pipeline's read source is declarable");
(3) `filter` parses under the existing Calcite SQL front-end, is boolean over the read source's
**entity fields**, uses the bounded grammar (no joins/subqueries/CTEs), and is **entity-scoped** (see
below), with referenced columns extracted; (4) the evaluator resolves; (5) `trigger.kind ∈ {on_ingest,
schedule, group}` and the trigger's declaration is internally consistent; (6) the evaluator-type
specific checks; (7) the group-trigger checks below.

**Entity-scoping.** Every governed SQL fragment a pipeline carries — `filter`, `trigger.ready_when.signal`,
`trigger.member_select.prefer_sql`, and a `sql` evaluator's output expressions — is validated by embedding it in
a statement over the pipeline's **read source**, so the gate SHALL additionally confirm that what the front
end accepted actually reads that source's entity: the translated statement's entity SHALL equal the
pipeline's read source (compared case-insensitively), and every referenced column SHALL be either a declared
column of that source (matched on either its logical `name` or its `source_name`) or the **qualified**
flat name `<enrichment>.<column>` of a column of one of that source's `ACTIVE` enrichments. Both are
rejected with HTTP 422 `pipeline_validation_failed`. Making the read source declarable SHALL NOT weaken
either check: a pipeline reads exactly one source table, and entity-scoping is what confines it to that
source and the enrichments reached through its own grain joins.

Neither check is redundant with the front end. A fragment that supplies its own `FROM` clause — an
expression ending in a SQL line comment, say — parses as a single valid `SELECT` over a *different*
table, and the sensitive-column entitlement check resolves referenced columns against the
**pipeline's read source's** entity, where such columns are absent, so it would find nothing to
gate. Separately, the query model publishes an enrichment's columns only under their qualified flat
name, so a fragment naming an enrichment column of some *other* source, or naming an enrichment
column under a bare unqualified spelling, parses cleanly against no field the pipeline's own
statement joins — and the rendered statement would reference a table absent from its own `FROM`
clause, which ClickHouse rejects at execution. Rejecting both at registration keeps the entitlement
gate total and turns an execution failure into the validation failure it always was.

The gate SHALL be **dispatched on the resolved evaluator's `type`**:

- **`llm`** — the resolved evaluator SHALL carry a non-blank `model`; all input/output JSONata
  expressions SHALL parse (static parse only — runtime evaluation is the runner's).
- **`sql`** — `model` SHALL NOT be required and JSONata SHALL NOT be parsed (there is none); every
  output variable's `sql` expression SHALL pass Class-1 expression governance (see
  [class1-enrichment](../class1-enrichment/spec.md)); the pipeline SHALL declare no input bindings, SHALL
  bind at least one output column, and SHALL target an enrichment keyed on the **read source's**
  identity column; and the `group` trigger SHALL be rejected, because a group-grain deterministic pipeline
  is an aggregation.

An accepted output binding's `column` SHALL be **stored normalized to the target enrichment's
physical column name** (its `source_name`). A binding may be supplied under either the column's
logical `name` or its `source_name`, but both write paths address the target physically — the
Class-1 materialize statement renders the column list into an `INSERT`, and the pipeline-scoped
write-back reconciles caller rows against the target's physical columns — so normalizing once at
registration is what keeps a renamed column writable instead of failing at execution. A pipeline
read back through the API therefore reports the physical column.

Output bindings SHALL be **one-to-one**: no two bindings may name the same target column, and no two
may name the same output variable. The column check runs after the normalization above, so naming
one column twice under its two spellings is caught too. Both write paths key their derived values by
target column, so a duplicate would silently drop a binding rather than fail — the Class-1
materialize statement would emit a single `INSERT` column computed from whichever expression it
resolved last.

A **bound input binding's** `column` SHALL name a field of the read source's entity, under the same
resolution as a governed fragment's column reference: a source column under either spelling, or an
enrichment column under its qualified flat name. A binding naming no field of the entity SHALL be
rejected with HTTP 422 `pipeline_validation_failed`.

The **group-trigger checks** SHALL be:

1. `trigger_kind = group` ⇒ `trigger.group_by` is present and **resolves to the target enrichment's
grain key**: either equal to it, or the qualified flat name of an enrichment column whose last
segment is it. Any other value SHALL be rejected, because the enrichment is physically keyed on its
grain and collapses by it, so a pipeline grouping by another column would write many groups onto one
key. Since the grain key is also required to be reachable on the pipeline's read source's entity, a
`group` pipeline always groups the rows it reads by a value those rows carry. 2. `trigger.group_by`
and `trigger.ready_when` SHALL be rejected on a non-`group` trigger. This is the enforceable form of
"`on_ingest` stays row-grain": a grain key carries no row-vs-group marker, so the gate constrains
the *declaration* rather than inferring the grain's nature. 3. `trigger.ready_when.signal`, when
present, SHALL parse under the same bounded Calcite grammar as `filter`, SHALL be boolean over the
**read source's entity fields**, and its referenced columns SHALL run the sensitive-column
entitlement check. 4. `trigger.ready_when.idle` and `trigger.ready_when.max_staleness`, when
present, SHALL parse as positive durations (both the short form `10m` and ISO-8601 `PT10M` are
accepted); `trigger.ready_when.cost_ceiling`, when present, SHALL be a positive integer. 5.
`trigger_kind = group` ⇒ `trigger.ready_when` SHALL declare at least one of `signal`, `idle`, or
`max_staleness`. A readiness declaration with none would mark a group dirty forever and never ready,
so the pipeline would silently do nothing.

Note (the model check): the gate does NOT verify that an `llm` evaluator's `model` names a
live DIAL deployment — ADAS has no deployment-listing client to reuse (only caller-key
introspection). Live deployment verification is deferred; only evaluator resolution and a non-blank
model are enforced here.

#### Scenario: Bad output binding column or type is rejected

- **WHEN** a pipeline declares an output binding to a column absent on the target enrichment or with
  a mismatched type
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: An output binding supplied under a column's logical name is stored physically

- **WHEN** a pipeline binds an output to a target column whose logical `name` differs from its
  `source_name`
- **THEN** the pipeline is accepted, the stored and returned binding names the column's
  `source_name`, and both write paths address the column ClickHouse actually carries

#### Scenario: Output bindings on the same column or the same variable are rejected

- **WHEN** a pipeline declares two output bindings naming the same target column (under either
  spelling), or two bindings naming the same output variable
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: Unparseable or unbounded filter is rejected

- **WHEN** `filter` fails to parse, is not boolean, references columns reachable on neither the
  pipeline's read source nor its `ACTIVE` enrichments, or uses a join/subquery/CTE
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: Governed SQL reaching outside the pipeline's read source's entity is rejected

- **WHEN** a pipeline's `filter`, `trigger.ready_when.signal`, `trigger.member_select.prefer_sql`,
  or `sql` output expression resolves against an entity other than the pipeline's read source, or
  references a column that is neither that source's own nor the qualified `<enrichment>.<column>` of
  one of that source's `ACTIVE` enrichments
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`, rather
  than passing the gate with an unentitled column reference or failing later at execution

#### Scenario: A filter over an enrichment column of the read source is accepted

- **WHEN** a pipeline declares `inputs: ["dial_usage_log"]` and a `filter` of
  `length("usage_client_identity.client_session_id") > 0`, where `usage_client_identity` is an `ACTIVE`
  enrichment of `dial_usage_log`
- **THEN** the pipeline is accepted, and the scan and group-assembly statements emit the grain join
  for that enrichment

#### Scenario: A bound input binding over an enrichment column is accepted

- **WHEN** a pipeline binds an evaluator input variable to `"usage_client_identity.client_type"`
- **THEN** the pipeline is accepted, and the binding is stored under that qualified name

#### Scenario: A bound input binding naming no entity field is rejected

- **WHEN** a pipeline binds an evaluator input variable to a column reachable on neither the read
  source nor its `ACTIVE` enrichments
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: Unresolvable evaluator or missing model on an llm pipeline is rejected

- **WHEN** the pipeline's evaluator (name/version) does not resolve, or the resolved `llm` evaluator
  has a blank `model`
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: Disallowed trigger kind is rejected

- **WHEN** a pipeline declares a `trigger.kind` outside `{on_ingest, schedule, group}` (e.g.
  `push`), or declares `group` for a `type=sql` evaluator
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: Unparseable JSONata expression is rejected

- **WHEN** any input or output JSONata expression of an `llm` pipeline fails to parse
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: A sql pipeline is validated without a model or JSONata

- **WHEN** a pipeline binds a `type=sql` evaluator that carries no `model` and no JSONata
- **THEN** the model and JSONata checks are skipped, the `sql` expressions are validated instead,
  and a well-formed declaration is accepted

#### Scenario: A group key not resolving to the target grain is rejected

- **WHEN** a `group` pipeline declares a `trigger.group_by` that neither equals the target
  enrichment's grain key nor is the qualified flat name of an enrichment column whose last segment
  is that grain key
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: A group key or readiness declaration on a non-group trigger is rejected

- **WHEN** an `on_ingest` or `schedule` pipeline declares `trigger.group_by` or `trigger.ready_when`
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: An unparseable or non-boolean readiness signal is rejected

- **WHEN** a `group` pipeline's `trigger.ready_when.signal` fails to parse, is not boolean,
  references columns reachable on neither the read source nor its `ACTIVE` enrichments, or uses a
  join/subquery/CTE
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: An unparseable readiness duration or non-positive ceiling is rejected

- **WHEN** a `group` pipeline's `trigger.ready_when.idle`/`max_staleness` is not a parseable
  duration, or its `cost_ceiling` is zero or negative
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: A readiness declaration with no condition is rejected

- **WHEN** a `group` pipeline's `trigger.ready_when` declares none of `signal`, `idle`, or
  `max_staleness`
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: A group pipeline referencing a sensitive column is accepted for an entitled author

- **WHEN** a `FULL_ADMIN` caller (or any caller in `none` mode) registers a `group` pipeline whose
  `trigger.ready_when.signal` or `filter` references a `sensitive` column of the pipeline's read source
- **THEN** the pipeline is accepted

#### Scenario: A group pipeline referencing a sensitive column is rejected for a non-entitled author

- **WHEN** a caller who may not see sensitive columns registers the same declaration
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`


### Requirement: Sensitive-column entitlement

The pipeline's `filter` and `bound` input bindings SHALL reference only columns the caller is
permitted to use. A column flagged `sensitive` SHALL be usable in a pipeline only by a caller
entitled to see sensitive columns — that is, a `FULL_ADMIN` caller, or any caller when
`security.mode` is `none` — mirroring the pipeline applied on the query surface. A pipeline
referencing a `sensitive` column by a caller not so entitled SHALL be rejected with HTTP 403
`sensitive_column_not_entitled`.

Because pipeline register and enable are `FULL_ADMIN`-only, a legitimately authorized author always
satisfies this check; it fails closed for any other caller. The same rule SHALL be re-applied on the
runner's read path (see the enrichment scan): a scan whose projection includes a `sensitive` column
SHALL be rejected with the same status and code unless the scanning caller is entitled, so a
non-`FULL_ADMIN` runner cannot read sensitive data even though the stored declaration references it.

#### Scenario: Sensitive reference by an entitled caller is accepted

- **WHEN** a `FULL_ADMIN` caller (or any caller in `none` mode) registers a pipeline whose filter or
  bound input binding references a `sensitive`-flagged source column
- **THEN** the entitlement check passes

#### Scenario: Sensitive reference by a non-entitled caller is rejected

- **WHEN** a caller who may not see sensitive columns references a `sensitive`-flagged source column
  in a pipeline's filter or a bound input binding
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`

#### Scenario: Non-sensitive reference is accepted

- **WHEN** a pipeline's filter and bound bindings reference only non-`sensitive` source columns
- **THEN** the entitlement check passes


### Requirement: Sensitive-column entitlement crosses the grain join

The sensitive-column rule SHALL apply to an enrichment column exactly as it applies to a read-source
column. A `sensitive` column of an `ACTIVE` enrichment of the pipeline's read source SHALL be
referenceable only by a caller entitled to see sensitive columns, and a reference by any other
caller SHALL be rejected with HTTP 403 `sensitive_column_not_entitled`.

Because such a column is **hidden** from a non-entitled caller's schema view rather than forbidden
to it, a governed fragment naming one fails to parse for that caller. That failure SHALL be
re-attributed to HTTP 403 `sensitive_column_not_entitled` rather than reported as HTTP 422
`pipeline_validation_failed`: the pipeline is not malformed, the caller is not entitled, and the
contract promises the 403.

The pipeline governs **who may reference** the column, and nothing more. A derived enrichment column
SHALL NOT inherit sensitivity from an enrichment column any more than it does from a source column —
the existing "Runner identity and derived-column sensitivity posture" requirement applies unchanged,
and this capability SHALL NOT introduce an exception to it for a value that happens to arrive
through a grain join. Controlling a derived column's exposure remains the enrichment author's
decision, taken by declaring that target column `sensitive`.

#### Scenario: A sensitive enrichment column is accepted for an entitled author

- **WHEN** a `FULL_ADMIN` caller (or any caller in `none` mode) registers a pipeline referencing a
  `sensitive` column of an `ACTIVE` enrichment of its read source
- **THEN** the pipeline is accepted

#### Scenario: A sensitive enrichment column is refused for a non-entitled author

- **WHEN** a caller who may not see sensitive columns registers the same declaration
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`, not
  with HTTP 422 reporting an unknown column

#### Scenario: A derived column does not inherit an enrichment column's sensitivity

- **WHEN** a `type=sql` pipeline's output expression reads a `sensitive` enrichment column and binds
  the result to a target column that is not itself `sensitive`
- **THEN** the pipeline is accepted and the target column's `sensitive` flag is left as the table
  author declared it, exactly as for a `sensitive` column of the read source — an enrichment column
  reached through the grain join is governed no differently from one the source declares


### Requirement: Runner identity and derived-column sensitivity posture

The Enrichment Runner SHALL operate as `FULL_ADMIN` for any pipeline that references a `sensitive`
source column. This follows from the access model: `SensitiveColumnAccess` admits `FULL_ADMIN` (or
any caller when `security.mode` is `none`), with no narrower grant available, and the pipeline is
applied both at pipeline registration and again at every read/derive egress against the **calling**
identity. This **supersedes the original least-privilege stance** ("per-target grants only, no global
admin") for those pipelines; pipelines touching no sensitive column are unaffected and continue to
work with a per-target grant alone. What still bounds a `FULL_ADMIN` runner is that the surface
stays pipeline-scoped in both directions: a scan projects only declaration-referenced columns, a
group fetch returns only the pipeline's membership, and a write-back rejects any column outside the
pipeline's declared outputs — the wider role changes *which columns a pipeline may reference*, not
*which pipelines the runner may operate*. A narrower per-pipeline or service-identity sensitive
grant is a named deferred follow-up.

Derived enrichment columns SHALL NOT inherit sensitivity. A pipeline whose filter, input bindings, or
`sql` expressions reference a `sensitive` source column and whose output bindings target a
**non-`sensitive`** enrichment column SHALL be accepted as registered: the system SHALL NOT mark the
target column `sensitive` as a side effect, and SHALL NOT reject the pipeline. Pipeline registration never
mutates catalog metadata, because doing so would silently change which callers can read an existing
column. The consequence is explicit and accepted: a value derived from sensitive data can be readable
by callers who may not read its source. Controlling that is the enrichment author's decision, taken by
declaring the target column `sensitive` when defining the enrichment's schema
(`POST /v1/tables/{name}/schema`) or by patching it later (`PATCH /v1/tables/{name}/schema`).

#### Scenario: A pipeline deriving a non-sensitive column from a sensitive source is accepted unchanged

- **WHEN** a `FULL_ADMIN` caller registers a pipeline whose expression or filter reads a `sensitive`
  source column and whose output binding targets a non-`sensitive` enrichment column
- **THEN** the pipeline is accepted, and the target column's `sensitive` flag is left exactly as the
  table author declared it

#### Scenario: A non-entitled runner cannot operate a sensitive-referencing pipeline

- **WHEN** a caller who may not see sensitive columns invokes any pipeline-scoped read or derive
  path for a pipeline referencing a `sensitive` source column
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`


### Requirement: An ENRICH pipeline reads its read source's entity, not only its read source table

Every column reference a pipeline declares SHALL resolve against the **entity** of its read source:
the read source's own declared columns under their exposed names, plus the columns of each of its
`ACTIVE` enrichments under the qualified flat name `<enrichment>.<column>`. This is the same flat
namespace `GET /v1/queries/entities/{name}` publishes and the aggregate pipeline surface resolves,
so a pipeline, a rollup and a query over the same entity SHALL name a column identically.

Membership SHALL be decided once, from the catalog's `ACTIVE` enrichments of the read source, and
SHALL be the same set the compiled statement joins — a field admitted by validation that the
statement would not join is a failure at execution rather than at declaration, and SHALL NOT be
reachable.

The surfaces this covers SHALL be: the target enrichment's grain key and `trigger.group_by`;
`filter`; `trigger.ready_when.signal`; `trigger.member_select.prefer_sql` and
`trigger.member_select.order_by`; `input_bindings[].column`; and a `sql` evaluator's output
expressions.

An enrichment column SHALL be addressable **only** under its qualified flat name. A bare column name
SHALL always resolve to the read source, so an enrichment SHALL NOT be able to shadow a source
column, and a pipeline declaring only bare source column names SHALL behave and render exactly as
before this capability. an `inputs` entry SHALL continue to name a source table: naming an
enrichment there stays rejected with HTTP 422 `pipeline_validation_failed`, because an enrichment is
reached through its source's grain join rather than read directly. This capability SHALL NOT
introduce a second input, general joins, or a pipeline reading an enrichment at a different grain
from its read source.

No new declaration field SHALL be introduced and no opt-in SHALL be required: the resolution is a
widening, and an enrichment column's qualified spelling was previously rejected outright.

#### Scenario: A pipeline naming no enrichment column is unchanged

- **WHEN** a pipeline registered before this capability, referencing only its read source's own
  columns, is read, enabled, scanned, assembled or materialized
- **THEN** its validation outcome and the SQL its reads render are unchanged, and no enrichment is
  joined

#### Scenario: A referenced enrichment is joined; an untouched one is not

- **WHEN** a pipeline references a column of one `ACTIVE` enrichment of its read source, and its
  read source has other `ACTIVE` enrichments it does not reference
- **THEN** every statement whose own clauses reference that enrichment emits a LEFT JOIN for it on
  the shared grain key, and no join is emitted for the enrichments nothing references

#### Scenario: A bare enrichment column name resolves to the source, not the enrichment

- **WHEN** a pipeline references a bare column name that an `ACTIVE` enrichment of its read source
  declares and the read source does not
- **THEN** the reference is rejected with HTTP 422 and error code `pipeline_validation_failed`,
  naming the qualified spelling — the bare namespace belongs to the read source alone

#### Scenario: Naming an enrichment as the read source is still rejected

- **WHEN** `POST /v1/pipelines` declares an `inputs` entry naming a table of kind `enrichment`
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: The pipeline path and the query path agree on a column's value

- **WHEN** the same enrichment column is read through a pipeline's scan and through
  `POST /v1/queries/execute` over the same entity for the same source row
- **THEN** both return the same value, because both resolve the same flat field through the same
  grain join


### Requirement: An enrichment-sourced value is readable only where absence is representable

A pipeline's grain join is **outer**, so an enrichment column SHALL be treated as possibly absent
whatever the enrichment declared. Where such a column produces a **value** — a `trigger.group_by`
key, or a `sql` evaluator's output expression — the enrichment column SHALL be declared `nullable`,
and a pipeline reading a non-`nullable` one in such a position SHALL be rejected with HTTP 422
`pipeline_validation_failed`.

The reason is a ClickHouse default rather than a policy: `join_use_nulls` is 0, so an unmatched
outer join renders a non-nullable column as its **type default** — `''`, `0`, `false`, the epoch —
which is indistinguishable from a derived value. `array` and `map` columns SHALL be exempt:
ClickHouse has no `Nullable(Array)`, and their default is *empty*, which contributes nothing.

A `filter`, `trigger.ready_when.signal` or `trigger.member_select.prefer_sql` MAY still read a
non-`nullable` enrichment column: there it **selects rows** rather than producing a value, and
`"<enrichment>.<column>" != ''` is how such a predicate excludes the rows the enrichment has not
reached.

#### Scenario: A non-nullable enrichment column is refused as a group key

- **WHEN** a `group` pipeline declares a `trigger.group_by` naming an enrichment column declared
  `nullable: false`
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: A non-nullable enrichment column is refused in a Class-1 output expression

- **WHEN** a `type=sql` pipeline's output expression reads an enrichment column declared `nullable:
  false`
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: A non-nullable enrichment column may still be read by a predicate

- **WHEN** the same column is referenced only by the pipeline's `filter`,
  `trigger.ready_when.signal` or `trigger.member_select.prefer_sql`
- **THEN** the pipeline is accepted

#### Scenario: An array enrichment column is exempt

- **WHEN** a pipeline reads an `array`-typed enrichment column declared `nullable: false` in a
  value-producing position
- **THEN** the pipeline is accepted, because an unmatched join renders it empty rather than as a
  value


### Requirement: An enrichment read requires an establishable materialized position

A pipeline reading an enrichment column SHALL be admitted only when the position that enrichment has
been materialized through can be established, because that position is what bounds the pipeline's
read window (see [enrichment-runtime](../enrichment-runtime/spec.md) and
[class1-enrichment](../class1-enrichment/spec.md)). Registration and enable SHALL classify each
referenced enrichment and SHALL reject, with HTTP 422 `pipeline_validation_failed` naming the
enrichment and the reason, one whose position cannot be established:

- an enrichment written by a **`group`-trigger** pipeline — a group's members span arbitrary source
  positions, so the only sound position is the one below which every group has settled, which one
  long-lived group pins indefinitely, and the value changes on every recompute by design;
- an enrichment written by a pipeline **ADAS does not execute itself** — the write-through position
  is held by the runner and never reported.

An enrichment that **no pipeline targets** SHALL be admitted and SHALL impose no bound: an unwritten
enrichment will never gain another row, so its absent values are final rather than late and there is
nothing to wait for.

This is stricter than the aggregate pipeline surface, which admits an unbounded read and reports it.
The asymmetry is deliberate: a rollup recomputes every dirty key in full on its next refresh, so an
unbounded read costs it a number that self-corrects, while a pipeline's read source cursor advances
once — neither a group left unmarked nor a Class-1 value written from an unlanded input comes back.

#### Scenario: An enrichment written by a Class-1 pipeline is admitted

- **WHEN** a pipeline references a column of an enrichment written by a `type=sql` pipeline scanning
  the same read source
- **THEN** the pipeline is accepted, and its read window is bounded by that enrichment's recorded
  position

#### Scenario: An enrichment written by a group pipeline is refused

- **WHEN** a pipeline references a column of an enrichment whose own pipeline declares
  `trigger.kind: "group"`
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`, naming
  the enrichment and that no position can be established for a group-written enrichment

#### Scenario: An enrichment written by a runner-executed pipeline is refused

- **WHEN** a pipeline references a column of an enrichment written by a pipeline ADAS does not
  execute itself
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: An enrichment no pipeline targets is admitted unbounded

- **WHEN** a pipeline references a column of an `ACTIVE` enrichment that no pipeline targets
- **THEN** the pipeline is accepted, the enrichment contributes no bound, and the pipeline's state
  reports the unbounded read


### Requirement: An ENRICH pipeline's enrichment references must not form a dependency cycle

Registration and enable SHALL reject, with HTTP 422 `pipeline_validation_failed`, a pipeline whose
enrichment references would close a cycle in the dependency graph whose edges run from a pipeline's
`target` to each enrichment that pipeline reads. A `type=sql` pipeline's reads live in its **pinned
evaluator's output expressions** rather than in the pipeline row, and SHALL count as edges exactly
as the pipeline's own fragments do — otherwise the pipeline deriving from a sibling enrichment,
which is the shape this capability exists to allow, would contribute no edge at all. Two pipelines
each reading the other's target would bound each pipeline's window on the other's position, freezing
both permanently with no error raised and no operator action that releases them. The refusal SHALL
name the enrichments forming the cycle.

#### Scenario: A pipeline closing a cycle is rejected

- **WHEN** a pipeline targeting enrichment `A` references a column of enrichment `B`, and `B`'s own
  pipeline references a column of `A`
- **THEN** the later request is rejected with HTTP 422 and error code `pipeline_validation_failed`,
  naming the enrichments forming the cycle

#### Scenario: A pipeline reading its own target is rejected

- **WHEN** a pipeline references a column of the enrichment it targets
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: An acyclic chain is accepted

- **WHEN** a pipeline targeting `C` references `B`, and `B`'s pipeline references `A`, and nothing
  references `C`
- **THEN** both pipelines are accepted


### Requirement: An ENRICH pipeline's enrichment references are protected against drop and rename

An enrichment a pipeline reads SHALL be a dependency of that pipeline, protected exactly as the
pipeline's declared read source is. What a pipeline reads SHALL be resolved from its whole
declaration, including a `type=sql` pipeline's pinned evaluator output expressions — the only place
such a pipeline's column references appear. Dropping such an enrichment, or dropping or renaming a
column of it that a rule references, SHALL be refused with HTTP 409 `conflict` naming the pipelines
that read it, rather than leaving those pipelines to fail on their next scan or materialize.

#### Scenario: Dropping an enrichment a pipeline reads is refused

- **WHEN** `DELETE /v1/tables/{name}` names an enrichment whose columns a registered pipeline
  references
- **THEN** the request is refused with HTTP 409 and error code `conflict`, naming the pipelines that
  read it

#### Scenario: Dropping an enrichment read only by a Class-1 output expression is refused

- **WHEN** `DELETE /v1/tables/{name}` names an enrichment referenced only by a `type=sql` pipeline's
  evaluator output expression, with no reference in the pipeline row itself
- **THEN** the request is refused with HTTP 409 and error code `conflict`, naming that pipeline

#### Scenario: Dropping a referenced column of an enrichment is refused

- **WHEN** a schema patch would drop or rename a column of an enrichment that a registered pipeline
  references
- **THEN** the request is refused with HTTP 409 and error code `conflict`

#### Scenario: An unreferenced column of a read enrichment may still change

- **WHEN** a schema patch drops a column of an enrichment that no pipeline references, while other
  columns of that enrichment are referenced
- **THEN** the patch is accepted


### Requirement: A string output binds to an enum target column

A pipeline output variable declared `string` SHALL be bindable to a target column of type `enum`: an
enum column holds strings, and ClickHouse validates the written value against the declared domain at
insert time. Without this the governance gate's exact-type match would reject the binding and make
an enum column unwritable by any enrichment.

`enum` SHALL NOT be accepted as an output variable's declared logical type. The pipeline declares
the value it produces; the column declares the domain that value must fall into. Accepting `enum` as
a variable type would advertise a domain check the gate does not perform — the same reasoning that
already refuses a parameterized `array<...>` spelling. The declaration SHALL be refused with a
message saying to declare the output as `string` and let the target column carry the domain.

Binding SHALL remain exact in the other direction: an output declared with any non-`string`,
non-numeric type SHALL still be rejected against an `enum` column.

#### Scenario: A string output binds to an enum column

- **WHEN** a pipeline declares a `string` output variable bound to a target column of type `enum`
- **THEN** the pipeline is accepted, and a materialization writing a value from the column's
  declared domain succeeds

#### Scenario: A value outside the domain fails at write time, not registration

- **WHEN** a pipeline bound to an `enum` column produces a value outside that column's declared
  domain
- **THEN** the pipeline remains valid at registration and the failure surfaces when the
  materialization writes, through the existing materialization failure contract

#### Scenario: Declaring an output variable as enum is refused

- **WHEN** a pipeline declares an output variable whose logical type is `enum`
- **THEN** the declaration is rejected with a message directing the author to declare `string` and
  let the target column declare the domain

#### Scenario: A non-string output against an enum column is still rejected

- **WHEN** a pipeline declares a `timestamp` output variable bound to a target column of type `enum`
- **THEN** the binding is rejected by the governance gate

