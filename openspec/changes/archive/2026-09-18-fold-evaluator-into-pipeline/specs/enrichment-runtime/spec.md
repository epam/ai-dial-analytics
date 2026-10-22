## MODIFIED Requirements

### Requirement: Pipeline-scoped versioned write-back endpoint

The system SHALL expose `POST /v1/pipelines/{name}/rows` accepting a non-empty `rows` array, symmetric
with the scan endpoint. The system SHALL resolve the pipeline's `target` and validate that
each row's column-set is a subset of {the pipeline's declared output columns} ∪ {the grain key} ∪
{permitted provenance columns}; a row naming any other column SHALL be rejected with HTTP 422
`write_column_not_allowed`. The caller SHALL NOT name a table and SHALL NOT be able to write
outside its pipeline's declared columns.

For a pipeline whose `trigger.kind` is `group`, a row naming the pipeline's **`trigger.group_by`** SHALL be accepted as
naming the target's **plain grain key**, and the column SHALL be renamed to the grain key before validation
and before the write. The two spellings coincide unless the group key is reachable only through an
enrichment of the read source, where `trigger.group_by` carries the qualified flat name `<enrichment>.<column>`
that both pipeline-scoped reads project it under — the scan under *both* names by design. Since the reads hand
the caller two spellings of one key, which one it echoes back SHALL NOT decide whether the write is
accepted; the qualified name is no column of the target, so it is **renamed rather than admitted**. This
widens nothing: registration has already validated that `trigger.group_by` resolves to the grain, no column outside
the pipeline's declared set becomes writable, and every other column is still rejected as below. A row naming
**both** spellings SHALL be accepted only when their values are equal, and SHALL otherwise be rejected with
HTTP 422 `validation_error` — disagreeing values name two grains for one row, and keeping either silently
would write the label against the wrong key.

The **permitted provenance columns** SHALL be `pipeline_generation`, `evaluator_version`, `model`,
`enriched_at`, `group_version`, and `truncated`, supplied by the caller and written as ordinary user
columns — except `evaluator_version`, which stays in this set only so that naming it is answered by the
refusal below rather than by `write_column_not_allowed`, whose remedy is the opposite one. Membership
here settles which code a row gets, not whether it is accepted. `group_version` records the version of the input the row was computed from — for a `group`
pipeline, the maximum version-column value across the members the caller saw — and `truncated` records
whether that input had been trimmed, whether by the group-assembly caps or by the caller's own budget.
The two exist so a **truncated group's label is distinguishable from a complete group's**, and so a
label's input version is identifiable after the fact: without them a partial judgement reads as a full
one, and only the caller's private state knows which version of the group was judged.

**`pipeline_generation`** records the pipeline `generation` the row was derived under, and SHALL be the
provenance key for a declaration folded onto the pipeline. It is a **finer** partition than
`evaluator_version`, not a coarser one: `evaluator_version` moved only when the prompt or the
expressions changed and was blind to a `filter` or `member_select` change that altered which rows were
derived and from what, while `generation` moves for any of them. Resolving a value back to the
declaration it names is the pipeline snapshot at that revision (see
[audit-trail](../audit-trail/spec.md)), which carries the whole declaration and the identity that wrote
it — strictly more than an `evaluator_version` integer ever resolved to.

**A caller-supplied `evaluator_version` SHALL be rejected** with HTTP 422 `validation_error` naming the
column. Nothing writes that column after the fold, and the feed offers no member a caller could derive
it from — the `evaluator` object that carried a `version` is removed and provenance is stamped from the
pipeline's `generation` — so a row naming it is not an ambiguity to resolve but **evidence that the
caller was built against a pre-fold feed**. Admitting it would let a stale consumer write generation
numbers into a column holding evaluator versions, mixing two number spaces in one column forever.

**This refusal is not the deploy detector, and SHALL NOT be specified as one.** This endpoint already
requires an exact-matched `Adas-Contract-Version` (see "The governed data plane requires an exact runner
contract version"), decided before any pipeline lookup, so a runner built against a pre-fold contract is
refused **HTTP 409** here and never reaches this check — a test asserting 422 for that caller would
observe 409. What this refusal governs is the caller the header gate does not reach the same way: a
hand-written request, an out-of-date `adas-mcp`, any holder of a `WRITE` grant that declares the current
contract and still names the retired column. It is the repository's ordinary rule that a retired
spelling is refused rather than absorbed, applied to a provenance column name.

An earlier draft **renamed** `evaluator_version` onto `pipeline_generation` instead, on the grounds that
the value sent *is* the generation and the rename is therefore exact. That was correct while the fold
deliberately left the consumer unmodified and ADAS had to absorb the old spelling silently. With the
consumer updated in the same window the tolerance has no client left to serve, and the same fact is
worth more as a signal than as a repair: a silent rename would let a misordered deploy write rows that
look correct while the consumer is executing a declaration shape it does not understand.

**`evaluator_version` SHALL remain a permitted name and its column SHALL NOT be removed**, so that a
target carrying already-stamped rows keeps a column those values are valid in and no ClickHouse schema
change is forced by the fold. What changes is that nothing writes it again: pre-fold rows keep their
integers, post-fold rows carry `pipeline_generation`, and the boundary between the two is legible
precisely because the two spaces never mix.

**Provenance is best-effort, so this stamp can silently write nothing** — and by design it almost never
does. Creation-time injection reaches new tables only, so no enrichment materialized before this change
carries `pipeline_generation` of its own accord. The system therefore **reconciles the column onto every
existing enrich target at startup**, idempotently and with no operator action (see
[table-management](../table-management/spec.md), "The provenance stamp column is reconciled onto
existing enrich targets at startup"); that reconciliation, not a hand-written schema patch, is what
turns the stamp on for a target that predates the change. A target that still does not physically carry
the column — the reconciler is disabled, or its last attempt failed and is retried on the next
startup — drops the value exactly as it drops any provenance column it lacks, and its label columns are
written regardless.

Every permitted provenance name SHALL be **un-prefixed**: the `_` prefix is reserved for system columns,
which are not caller-settable at all, and a user-declared enrichment column may not begin with `_`, so a
provenance column lives in the user namespace and is declared on the target like any other column.

Provenance is informational lineage and is therefore **optional on the target**: a permitted provenance column
that the target enrichment does not physically carry SHALL be silently dropped from the write
(best-effort lineage) rather than failing it, so an enrichment created without provenance columns
still enriches successfully. This tolerance applies only to the permitted provenance names; a
non-provenance column outside the pipeline's declared set is still rejected as above, and the target's
physical-column check still applies to every remaining column. The version column `_updated_at`
SHALL be stamped by ClickHouse and SHALL NOT be accepted as caller input. After validation the
write SHALL delegate to the existing materialized-table write path. An unknown pipeline SHALL return
HTTP 404 `unknown_pipeline`.

Because the write delegates to the materialized-table write path, a value for a `timestamp`-typed
column on this endpoint SHALL be accepted in exactly the **same single format** as on
`POST /v1/tables/{name}/rows` — ISO-8601 with an explicit zone, either `Z` or an explicit offset — and a
value not of that form SHALL be rejected the same way, with HTTP 422 and error code `validation_error`
naming the row, the column, the value and the specific defect (see the `table-management` capability for
the normative format and rejection detail). The two row-write surfaces SHALL NOT diverge on which
timestamp values they accept. A provenance column such as `enriched_at`, when declared on the target as
a `timestamp`, is therefore written in ISO-8601 form; when declared as a `string` its value is stored
verbatim and is not inspected.

#### Scenario: A well-formed write is accepted and collapses by grain

- **WHEN** `POST /v1/pipelines/{name}/rows` is called with rows whose columns are all within the pipeline's outputs plus grain and provenance
- **THEN** the rows are written to the target enrichment and a subsequent `final=1` read returns the latest version per grain key

#### Scenario: A row records the pipeline generation that derived it

- **WHEN** `POST /v1/pipelines/{name}/rows` is called with rows carrying `pipeline_generation` and the
  target enrichment declares that column
- **THEN** the value is written as an ordinary column, and a query over the enrichment can partition
  rows by the declaration that produced them

#### Scenario: An already-stamped evaluator_version column keeps working

- **WHEN** a target enrichment carries `evaluator_version` with rows stamped before the fold, and a
  write after the fold carries `pipeline_generation` but no `evaluator_version`
- **THEN** the write succeeds, the pre-fold rows keep their values, and no schema change to the target
  is required

#### Scenario: A caller-supplied evaluator_version is refused as a stale-consumer signal

- **WHEN** a caller built against a pre-fold feed stamps `evaluator_version`, whether or not the target
  carries the column
- **THEN** the request is rejected with HTTP 422 `validation_error` naming the column, no row of the
  batch is written, and pre-fold rows already stamped in that column are unchanged

#### Scenario: A row naming both provenance spellings is rejected whatever the values

- **WHEN** a row carries `evaluator_version` and `pipeline_generation`, with equal or with different
  values
- **THEN** the request is rejected with HTTP 422 `validation_error`, because the refusal is of the
  spelling rather than of a disagreement between two claims

#### Scenario: A target without pipeline_generation drops the stamp rather than failing

- **WHEN** a write carrying `pipeline_generation` and no `evaluator_version` targets an enrichment
  created before this change that carries `evaluator_version` but not `pipeline_generation`, because
  the startup reconciler is disabled or has not yet completed against it
- **THEN** that stamp is dropped, the label columns are written successfully, and the target records no
  generation until a startup reconciles the column onto it

#### Scenario: An ISO-8601 timestamp is accepted on the pipeline-scoped write

- **WHEN** `POST /v1/pipelines/{name}/rows` is called with a row whose `timestamp`-typed column (for example the
  provenance column `enriched_at`) carries an ISO-8601 value such as `2026-08-11T10:00:00Z`
- **THEN** the write is accepted and stores the instant it denotes, identically to the same value written
  through `POST /v1/tables/{name}/rows`

#### Scenario: A timestamp not in the accepted format is rejected the same way on both write surfaces

- **WHEN** `POST /v1/pipelines/{name}/rows` is called with a row whose `timestamp`-typed column carries a value
  that is not ISO-8601 with an explicit zone — for example the space-separated form or a zone-less value
- **THEN** the request is rejected with HTTP 422 and error code `validation_error` naming the row, the
  column, the value and the specific defect, and no row of the batch is written

#### Scenario: A provenance column absent from the target is skipped, not rejected

- **WHEN** `POST /v1/pipelines/{name}/rows` is called with rows carrying a permitted provenance column (e.g. `enriched_at`) that the target enrichment does not physically have
- **THEN** that provenance column is silently dropped and the remaining columns are written successfully (the write is not failed and no dead-letter is produced)

#### Scenario: A group pipeline records its input version and truncation

- **WHEN** a `group` pipeline writes its one row per group key with `group_version` and `truncated`, and the target enrichment carries both columns
- **THEN** both are written as ordinary columns and a query over the enrichment can tell a label computed from a trimmed group from one computed from a complete group

#### Scenario: Group provenance absent from the target is skipped

- **WHEN** the same write targets an enrichment that carries neither `group_version` nor `truncated`
- **THEN** both are silently dropped and the label is written successfully

#### Scenario: A group pipeline's write may name the key by its declared `trigger.group_by`

- **GIVEN** a group pipeline whose `trigger.group_by` is the qualified flat name of an enrichment column (for example
  `usage_client_identity.client_session_id`) and whose target enrichment's grain key is `client_session_id`
- **WHEN** `POST /v1/pipelines/{name}/rows` is called with a row naming the key under that qualified spelling
- **THEN** the write is accepted and the row is keyed by `client_session_id`, indistinguishable from the
  same row written under the plain grain key

#### Scenario: A row naming the group key under both spellings with different values is rejected

- **WHEN** a row of such a pipeline names both `<enrichment>.<column>` and the plain grain key with different
  values
- **THEN** the request is rejected with HTTP 422 and error code `validation_error`, and no row is written

#### Scenario: A write naming an undeclared column is rejected

- **WHEN** a row references a column that is neither a declared output of the pipeline, the grain key, nor a permitted provenance column
- **THEN** the request is rejected with HTTP 422 and error code `write_column_not_allowed`

#### Scenario: Caller-supplied `_updated_at` is rejected

- **WHEN** a write row attempts to set the system version column `_updated_at`
- **THEN** the request is rejected (system column is not caller-settable) and ClickHouse stamps the version on insert

### Requirement: An ENRICH pipeline's read window is clamped to its referenced enrichments' positions

Every pipeline-scoped read that advances a cursor SHALL bound its window to the position through which each
enrichment its statements join has been materialized, taking the **lowest** such position when several
enrichments are joined. This applies to the incremental scan; the Class-1 materialize window carries the
same clamp (see [class1-enrichment](../class1-enrichment/spec.md)).

The clamp exists because an enrichment row lands **after** the source row it derives from, while a
pipeline's window advances on the source's cursor. Without it a row would be read while its enrichment cell
is still absent, and because the source row does not move again, the consequence is permanent: a group
pipeline would attribute the row to the wrong group or to none and never revisit it, a `trigger.ready_when.signal`
computed inside the scan query would return a verdict derived from an absent value, and a
bound `transform.inputs` value read at assembly would be missing from the prompt. Clamping the window removes
every one of those at once, because a row that does not enter the window contributes to none of them.

Clamping the scan window SHALL be sufficient for a group pipeline's members: the assembly endpoint selects
the **whole** group by key rather than only newly seen members, so a group that is marked is assembled
from every member it has. The unrecoverable case is a source row that is never marked at all, which is
what the clamp prevents.

The clamp's set and the **join** set are therefore not the same set, and each SHALL be derived for what it
governs. A statement SHALL join only the enrichments its own clauses name, so nothing pays a full hash
read of a table it never selects from. The clamp SHALL additionally cover the columns a group pipeline's
`transform.inputs` bind, which the scan page does not project but the assembly reads for the very rows
that page marks: a
bound value read while its enrichment cell is absent is a wrong prompt value, so the enrichment behind it
holds the scan window back even though no clause of the scan names it.

An enrichment that contributes no bound — one no pipeline targets — SHALL NOT hold the window back. A
`trigger.ready_when.idle` window SHALL be measured after the clamp, so a group's idle timer starts from the
activity the scan actually reported; the clamp therefore delays labelling by at most the lag of the
enrichments a pipeline reads.

A pipeline joining no enrichment SHALL be unclamped and its window unchanged.

#### Scenario: A window is held back to an enrichment's materialized position

- **WHEN** a pipeline referencing an enrichment column is scanned while source rows exist above that
  enrichment's recorded materialize position
- **THEN** those rows are excluded from the page, the returned cursor does not advance past that
  position, and a later scan returns them once the enrichment has reached them

#### Scenario: The lowest position bounds a multi-enrichment read

- **WHEN** a pipeline references columns of two enrichments materialized to different positions
- **THEN** the window is bounded by the lower of the two

#### Scenario: An enrichment with no pipeline imposes no bound

- **WHEN** a pipeline references a column of an `ACTIVE` enrichment that no pipeline targets
- **THEN** the window is not held back, because that enrichment will never gain another row

#### Scenario: A pipeline joining no enrichment is unclamped

- **WHEN** a pipeline referencing only its read source's own columns is scanned
- **THEN** the window is bounded only by the cursor, the safety-lag, and any requested bounds, exactly
  as before

#### Scenario: A clamped scan reports its lag rather than failing

- **WHEN** a pipeline's referenced enrichment has not been materialized at all, so no source row is inside
  the window
- **THEN** the scan returns an empty page and echoes the input cursor, rather than failing
