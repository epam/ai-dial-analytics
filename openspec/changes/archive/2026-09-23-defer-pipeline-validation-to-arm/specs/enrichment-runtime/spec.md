## MODIFIED Requirements

### Requirement: Declarable group member selection

A `group` pipeline SHALL be able to declare **which** of a group's members bear on its label, through an
optional `trigger.member_select` object carrying an optional `prefer_sql`, an optional `order_by`, and a `limit`.
Without it the endpoint's existing policy applies unchanged: the newest members by the source's total
order, bounded by the configured caps.

The three fields SHALL compose as one selection policy, evaluated over the group's members after the
membership `filter` and the safety lag:

- `prefer_sql` — a boolean predicate over the read source's **entity fields**, validated by the same
  closed grammar and field resolution as `filter`. Members satisfying it are the **candidate set**.
  When **no** member satisfies it, the candidate set SHALL fall back to **all** members of the group, so a
  declaration declaring a preference still produces a label for a group that cannot satisfy it.
- `order_by` — an ordered list of `{column, direction}` over the read source's **entity fields**, applied
  to the candidate set. It SHALL be followed by the source's total order descending as a deterministic
  tiebreak, so selection is stable across re-assembly of an unchanged group.
- `limit` — the number of candidates retained, at least 1 and at most the configured group-fetch maximum
  row count.

`trigger.member_select` SHALL be valid only for a pipeline whose `trigger.kind` is `group`; declaring it on any other
trigger kind SHALL be rejected with HTTP 422 `pipeline_validation_failed`. **This one SHALL be refused
on every write, armed or not**, unlike the checks below: a `member_select` beside a `schedule` trigger
is a contradiction inside the declaration the author just sent, legible with nothing else resolved and
meaning nothing at any later stage, and the storage guard that refuses the pair is kept unconditional —
so a service-layer refusal is what turns it into a `pipeline_validation_failed` naming the field rather
than a database-integrity failure reported as something else. A `member_select` on a declaration that
does **not name a trigger kind yet** is not that contradiction and SHALL be storable.

The remaining checks are part of the declaration gate and SHALL run **where the gate runs** — on a
write that arms the pipeline and on `POST /v1/pipelines/{name}/preview` of a disabled one — rather
than at registration, and SHALL NOT be re-asked at the group-assembly endpoint, which runs no part of
this gate (see "The governed data plane refuses an absent declaration, and materialize re-checks two
rules"): a `prefer_sql`
that fails the grammar, either field naming a field reachable on neither the read source nor its
`ACTIVE` enrichments, or a `limit` outside its range SHALL each be rejected with HTTP 422 `pipeline_validation_failed`. An
`order_by` direction SHALL be `asc` or `desc`; any other value is rejected as a malformed request body
(HTTP 400) **on every write including a draft save**, as every other enum-valued field of the API is —
that refusal is request binding, not the gate, and deferring it would mean storing bytes no member of
the model can hold. A `trigger.member_select` referencing a `sensitive`
column — of the read source or of one of its `ACTIVE` enrichments — SHALL be treated exactly as
`filter` is: group assembly by a caller who may not see sensitive columns SHALL be rejected with HTTP
403 `sensitive_column_not_entitled`, and the check runs against the calling identity on every call.
On the authoring surfaces, which are `FULL_ADMIN`-only, the in-gate half of that check is defence in
depth (see [enrichment-registry](../enrichment-registry/spec.md), "Static governance gate on arming and
execution").

Selection is a statement about **which members qualify** and SHALL NOT change the order members are
returned in: the response ordering remains the source's total order, ascending.

#### Scenario: A preference narrows the candidate set

- **WHEN** a group pipeline declaring `trigger.member_select.prefer_sql` assembles a group in which some members
  satisfy that predicate
- **THEN** only members satisfying it are candidates, and the members that do not are excluded from the
  response

#### Scenario: A preference no member satisfies falls back to the whole group

- **WHEN** the same pipeline assembles a group in which **no** member satisfies `prefer_sql`
- **THEN** all members of the group become candidates and a label is still produced, rather than the group
  returning empty

#### Scenario: Candidates are ordered by the declared ordering

- **WHEN** a group pipeline declares `trigger.member_select.order_by` over a source column and its candidate set is
  larger than `limit`
- **THEN** the retained members are those ranking first by that ordering, with the source's total order
  descending breaking ties, and re-assembling the same unchanged group retains the same members

#### Scenario: A selection policy over an enrichment column is accepted

- **WHEN** a group pipeline declares a `trigger.member_select.prefer_sql` or `order_by` naming a column of an
  `ACTIVE` enrichment of its read source
- **THEN** the pipeline is accepted, and the selection phase emits the grain join for that enrichment so the
  predicate and the ordering resolve

#### Scenario: A ranked selection is still returned in the source's total order

- **WHEN** a pipeline's `order_by` ranks an older member above a newer one and both are retained
- **THEN** the response returns them ascending in the source's total order, not in the order the ranking put
  them in

#### Scenario: A limit smaller than the group retains exactly that many members

- **WHEN** a group has more candidates than `trigger.member_select.limit`
- **THEN** exactly `limit` members are returned

#### Scenario: Member selection is rejected on a non-group pipeline

- **WHEN** a pipeline whose `trigger.kind` is not `group` is created or updated with `trigger.member_select`,
  whether or not the write leaves it enabled
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`, and
  nothing is stored

#### Scenario: A member selection on a declaration with no trigger kind yet is stored

- **WHEN** a disabled pipeline is patched with a `trigger.member_select` and no `trigger.kind`
- **THEN** the write is accepted, because the contradiction above is not present and the author has
  not yet said what the trigger is

#### Scenario: An invalid member selection is rejected when the pipeline arms

- **WHEN** a write arms a group pipeline, or a disabled one is previewed, whose `trigger.member_select` has a
  `prefer_sql` that fails the closed grammar, a
  `prefer_sql` or `order_by` naming a field reachable on neither the read source nor its `ACTIVE`
  enrichments, or a `limit` below 1 or above the configured maximum row count
- **THEN** the request is rejected with HTTP 422 and error code `pipeline_validation_failed`

#### Scenario: The same invalid selection is storable while the pipeline stays disabled

- **WHEN** that declaration is saved on a pipeline that stays disabled
- **THEN** the write is accepted, no schedule or sync feed picks the pipeline up, and the refusal
  arrives the moment the author arms or previews it rather than at the save

#### Scenario: A malformed order_by direction is rejected on any write

- **WHEN** a `trigger.member_select.order_by` entry declares a `direction` outside `{asc, desc}`, on a
  draft save as much as on an arming write
- **THEN** the request is rejected with HTTP 400 as a malformed request body, because that is request
  binding rather than the declaration gate

#### Scenario: A non-entitled caller cannot use a selection over a sensitive column

- **WHEN** a caller who may not see sensitive columns, holding the target enrichment's WRITE grant,
  assembles a group for a pipeline whose `trigger.member_select` references a `sensitive` column of the
  read source or of one of its `ACTIVE` enrichments
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`

#### Scenario: A pipeline declaring no member selection is unchanged

- **WHEN** a group pipeline carrying no `trigger.member_select` assembles a group
- **THEN** the members retained are the most recent ones by the source's total order, bounded by the
  configured caps, exactly as before

### Requirement: Pipeline-scoped incremental scan endpoint

The system SHALL expose `POST /v1/pipelines/{name}/scan` accepting a body `{cursor?, limit?, from?, to?}`
and returning `{rows[], nextCursor, hasMore, lagSeconds}`. The system SHALL resolve the pipeline's
stored, already-validated source, filter, referenced columns, and grain from the `pipeline` record and
the target enrichment's catalog row; the caller SHALL NOT supply a table name, filter, or column list.
The scan SHALL require the pipeline to **exist** and its declaration to be **complete** for its kind,
but NOT to be `enabled` (it writes nothing, and a disabled pipeline's dry-run preview samples through
it). The two conditions are separate
and the tolerance is bounded to the first: "already-validated" above is what the endpoint has always
assumed of the stored declaration, and once a declaration can be stored before it passes the gate,
"not enabled" stops implying "was validated once". So a **disabled and complete** pipeline still
scans — that is what a disabled pipeline's preview reads through — while a pipeline whose
declaration is incomplete SHALL be refused with HTTP 422 `pipeline_validation_failed` naming what
is missing, because there is no source, filter or grain to resolve and the alternative is a query
compiled from absent members. A pipeline whose `transform.type` is `sql` SHALL be refused with HTTP 422
`pipeline_validation_failed` pointing at `POST /v1/pipelines/{name}/materialize` **before** the
completeness check, in either state, since finishing the declaration would not make it scannable. The refusal is **absence only**: a complete declaration SHALL NOT be
re-judged here, whatever its state, because drift the operator did not cause must not stop a
running pipeline's scan (see "The governed data plane refuses an
absent declaration, and materialize re-checks two rules", which owns that rule for all four
data-plane endpoints). An unknown pipeline SHALL return HTTP 404 `unknown_pipeline`. When `limit` is omitted the
system SHALL apply a configured default; an over-limit request SHALL be clamped or rejected
consistent with the existing query-limit policy.

`from` and `to` are optional ISO-8601 instants bounding the source's version column as the half-open
range `(from, to]`. They are an additional predicate only: they compose with `cursor`, the keyset
seek, and the safety-lag rather than replacing any of them, and they do not change ordering,
projection, `final=1`, or cursor encoding. A request where `from` is greater than or equal to `to`
SHALL be rejected with HTTP 400, so an inverted window is never mistaken for an exhausted one.

#### Scenario: Scan resolves the pipeline and returns a bounded page

- **WHEN** `POST /v1/pipelines/{name}/scan` is called for an existing pipeline with a valid or omitted `cursor`
- **THEN** the system runs the pipeline's governed scan and returns up to `limit` rows plus
  `nextCursor`, `hasMore`, and `lagSeconds`

#### Scenario: Scan of a disabled but complete pipeline succeeds

- **WHEN** `POST /v1/pipelines/{name}/scan` is called for a pipeline that exists, is `enabled=false`,
  and whose declaration is complete for its kind
- **THEN** the scan proceeds normally (a read path; it writes nothing)

#### Scenario: Scan of an incomplete declaration is refused

- **WHEN** `POST /v1/pipelines/{name}/scan` is called for a pipeline that exists but whose declaration
  is not yet complete — no transform, no `trigger.kind`, or a `group` trigger with no
  `trigger.group_by`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` — by the shared
  no-transform refusal when the declaration carries no `transform`, and otherwise naming every absent
  member in one message — and no query is compiled and no cursor is issued

#### Scenario: Scan of a sql-transform pipeline points at materialize before completeness

- **WHEN** `POST /v1/pipelines/{name}/scan` is called for a pipeline, disabled or not, whose
  `transform.type` is `sql`, whether or not the rest of its declaration is complete
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` telling the caller to use
  `POST /v1/pipelines/{name}/materialize`, and that refusal is reported ahead of any completeness
  problem, because no completion of the declaration would make the row scan the right endpoint

#### Scenario: Scan of an unknown pipeline is rejected

- **WHEN** `POST /v1/pipelines/{name}/scan` is called with a name that does not resolve to a pipeline
- **THEN** the request is rejected with HTTP 404 and error code `unknown_pipeline`

#### Scenario: A bounded window returns only rows inside it

- **WHEN** `POST /v1/pipelines/{name}/scan` is called with `from` and `to`
- **THEN** only rows whose version column is greater than `from` and less than or equal to `to` are
  returned, and rows on either side of the window are excluded regardless of the cursor position

#### Scenario: A window composes with the cursor across pages

- **WHEN** a caller pages through a window by echoing each `nextCursor` while re-supplying the same
  `from` and `to`
- **THEN** each row inside the window is returned exactly once and paging stops with `hasMore=false`
  at the window's end rather than continuing to the source head

#### Scenario: An inverted window is rejected

- **WHEN** `POST /v1/pipelines/{name}/scan` is called with `from` greater than or equal to `to`
- **THEN** the request is rejected with HTTP 400 rather than returning an empty page


## ADDED Requirements

### Requirement: The governed data plane refuses an absent declaration, and materialize re-checks two rules

The system SHALL refuse, at each of the four pipeline-scoped data-plane endpoints —
`POST /v1/pipelines/{name}/scan`, `POST /v1/pipelines/{name}/group-rows`,
`POST /v1/pipelines/{name}/materialize` and `POST /v1/pipelines/{name}/rows` — a stored declaration
missing a member its kind requires, with HTTP 422 `pipeline_validation_failed` naming what is absent,
**before** any ClickHouse statement is compiled, read or written. On the two read endpoints this holds
in either pipeline state; the two writing endpoints refuse a disabled pipeline first, with HTTP 409 (see
"A disabled pipeline's write paths refuse"), so there it guards an enabled one. A
declaration is incomplete here when a member the kind requires is absent: `transform`,
`transform.type`, `transform.outputs`, `trigger.kind`, `trigger.cron` on a `schedule` trigger,
`trigger.group_by` on a `group` trigger, or the derived output mapping. The four endpoints serve
`enrich` pipelines only, so no aggregate member is consulted here. A read source that does not
resolve is **not** a completeness member: `/scan` and `/materialize` refuse it as they always have,
with HTTP 400 `bad_request` from the governed query layer.

`POST /v1/pipelines/{name}/materialize` SHALL additionally re-check exactly **two** rules of the
kind's gate: the **result-type fit** of each derived value against the target column it is bound to,
and the **row-grain rule** against the read source's `identity_column`. **Nothing else of the gate
SHALL be imported onto these lanes.**

These are the entry points that **compile or execute** the stored declaration, they never went
through a caller who could be told to fix a body, and they have always been written against the
assumption — true until the gate was deferred — that a stored declaration had already passed it. The
absence refusal is what restores the part of that assumption they cannot do without: a query compiled
from members that are not there is not a query.

**Why the two re-checks, and only those two.** Completeness and validity are different questions: a
declaration naming every member its kind requires passes the absence refusal while still failing the
gate. Such a declaration is storable for the first time, though only while disabled — and a disabled
pipeline is not executed by the writing endpoints — so what the re-checks guard is a declaration that
was gated when it was armed and has **drifted** since. The execution entry points re-derive only
part of what the gate asks — `POST …/materialize` re-checks the expression parse, the closed function
catalog, determinism, source scoping and the caller's sensitive entitlement, but **not** the result
type's fit against the bound column, **not** the row-grain rule against the read source's
`identity_column`, and **not** the enrichment-read acyclicity rules. Those first two are the ones
whose absence produces a **silently wrong row** rather than a loud failure: a Class-1 declaration
whose target is keyed more coarsely than its read source would materialize, and the target's
`ReplacingMergeTree` would collapse many rows onto one arbitrary survivor — a label that is neither
wrong nor reproducible, which is precisely what the row-grain rule exists to prevent. They are cheap,
and they are added. The rest is not.

**The whole gate SHALL NOT be run here.** A running pipeline's declaration can drift under it — a
column renamed on the read source, an enrichment that moved — and a full gate would stop it mid-run for
drift the operator did not cause and cannot see from there, which is the same regression the preview
rule declines to accept on an armed pipeline. So a **complete** declaration SHALL be run as it is found,
subject only to the two re-checks above.

**A stored derivation that no longer re-derives SHALL count as absent at `/materialize`.** A
declaration write keeps a derived `output_bindings` it could not re-derive rather than erasing it, so
the stored mapping can outlive the transform it came from. Where the stored bindings do not re-derive
from the current transform, `/materialize` SHALL refuse — the alternative is writing a newly authored
expression into a column bound by the previous one, which is a write the stored declaration does not
describe.

**A refusal an endpoint already owns unconditionally SHALL stay unconditional**, and is not part of
what is timed above: a stored declaration carrying no `transform` is refused on all four — on the two
writing endpoints only once the pipeline is enabled, since a disabled one is refused there first with
HTTP 409 — and logged at ERROR when the pipeline is enabled (see
[enrichment-registry](../enrichment-registry/spec.md), "A stored ENRICH declaration carrying no
transform is refused rather than run empty"), and a null `trigger.group_by` is refused rather than
dereferenced in either state. Turning a server fault into a refusal is not a new gate.

Two of the four do not merely mis-report an incomplete declaration today; they fail on it:

- **`/group-rows` is a 500, not a refusal.** The group-assembly path compares the stored
  `trigger.group_by` without a presence check, so a `group` pipeline whose group key has not been set
  is a null dereference reported as a server fault, inviting a retry that cannot succeed. It SHALL be
  the 422 above, in either state — a null group key is a crash on the enabled path too.
- **`/rows` mis-diagnoses it, under either of two codes.** The write allow-list is **not** empty when
  no `output_bindings` have been derived: it is seeded with the provenance columns and the pipeline's
  grain key, and only the declared output columns are missing from it. So a row carrying the label
  column the operator did declare fails the subset check and is refused `write_column_not_allowed`
  naming that column, while a row of grain and provenance alone **passes** the subset check and is
  refused `write_output_column_required` for naming none of the — empty — declared outputs. Nothing
  unsafe happens, since every row meets one refusal or the other and the "empty bindings write NULL
  labels" hazard does not resurface; what is wrong is that neither code names the declaration, so the
  operator is told about their row. The absence refusal SHALL be raised first, and
  both `write_column_not_allowed` and `write_output_column_required` SHALL remain exactly what they
  are today on a **complete** declaration.

One code SHALL serve all four — `pipeline_validation_failed`, which is what every other declaration
refusal on this surface already carries — so a runner switches on the code rather than on which
endpoint it happened to call, and 422 is the status on every surface: the declaration is not valid and
the remedy is to finish it, which is what 422 means here — 409 stays reserved for a request whose body
may be perfectly good and whose timing is wrong. A declaration carrying no `transform` is refused by
the shared no-transform refusal above, which the data plane asks first; otherwise, unlike the
declaration gate, which reports one problem per refusal, the completeness refusal names **every**
absent member in one message — the check is a single presence sweep over the declaration, so there is
no ordering to impose and nothing gained by withholding the rest.

Both refusals SHALL be ordered **after** the exact `Adas-Contract-Version` check and after
`unknown_pipeline`: a caller at the wrong contract version is answered HTTP 409 before any pipeline is
loaded, and a name that resolves to nothing is HTTP 404, so neither is re-reported as a declaration
problem.

The completeness refusal itself is not an enablement check: it refuses a declaration that cannot be
compiled, whatever the pipeline's state. Whether a disabled pipeline may be written through is decided
separately, by "A disabled pipeline's write paths refuse".

#### Scenario: A group pipeline with no group key is refused rather than crashing

- **WHEN** `POST /v1/pipelines/{name}/group-rows` is called for a `group` pipeline whose
  `trigger.group_by` has not been declared
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming
  `trigger.group_by`, rather than answering HTTP 500 from a null dereference

#### Scenario: A write-back against an incomplete declaration names the declaration, not the column

- **WHEN** `POST /v1/pipelines/{name}/rows` is called for an enabled pipeline whose declaration is
  incomplete,
  so no output mapping has been derived
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming what is
  missing, no row is written, and the message reports neither `write_column_not_allowed` against the
  caller's own column nor `write_output_column_required` against a row of grain and provenance

#### Scenario: An out-of-set column on a complete declaration keeps its own refusal

- **WHEN** `POST /v1/pipelines/{name}/rows` is called for a pipeline whose declaration is complete,
  with a row naming a column outside its declared outputs, grain and provenance set
- **THEN** the request is rejected with HTTP 422 `write_column_not_allowed` exactly as before, and a
  row naming none of the declared outputs keeps `write_output_column_required`

#### Scenario: Materialize of an incomplete declaration is refused before any statement runs

- **WHEN** `POST /v1/pipelines/{name}/materialize` is called for an enabled `sql` pipeline whose
  declaration carries no transform or no resolvable output
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed`, no
  `INSERT … SELECT` is issued and no cursor or watermark advances

#### Scenario: Materialize of a complete declaration keyed too coarsely is refused by the row-grain re-check

- **WHEN** `POST /v1/pipelines/{name}/materialize` is called for a Class-1 pipeline whose
  declaration names every member its kind requires but whose target is keyed more coarsely than its
  read source's `identity_column`, and which is enabled — a state reached by drift, since arming it is
  refused by the gate
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the row-grain
  rule, and no `INSERT … SELECT` is issued — rather than materializing rows the target's
  `ReplacingMergeTree` would collapse onto an arbitrary survivor

#### Scenario: A stale derivation is treated as absent at materialize

- **WHEN** `POST /v1/pipelines/{name}/materialize` is called for an enabled pipeline whose stored
  `output_bindings` were derived from a transform the declaration has since replaced, and no longer
  re-derive from it
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed`, rather than
  writing a newly authored expression into a column bound by the previous one

#### Scenario: A drifted but complete declaration is not re-gated

- **WHEN** any of the four endpoints is called for an enabled pipeline whose stored declaration is
  complete but no longer passes its kind's gate — a read-source column renamed since it was written,
  say — or either read endpoint is called for such a pipeline while it is disabled
- **THEN** the call proceeds exactly as it does today, because refusing it would stop a running
  pipeline for drift the operator did not cause — subject, at `/materialize`, to its two re-checks
  (result-type fit and row grain), which still refuse a drift that would write a silently wrong row

#### Scenario: A wrong contract version is still answered before the completeness check

- **WHEN** a caller at a mismatched `Adas-Contract-Version` calls any of the four endpoints for a
  pipeline whose declaration is incomplete
- **THEN** the response is HTTP 409 for the contract mismatch, decided before the pipeline is loaded,
  and the declaration problem is not reported instead

#### Scenario: A complete and valid declaration is unaffected on every one of the four endpoints

- **WHEN** any of the four endpoints is called for an enabled pipeline whose declaration is complete
  and passes its kind's gate, or either read endpoint is called for such a pipeline while it is disabled
- **THEN** the endpoint behaves exactly as it does today

### Requirement: A disabled pipeline's write paths refuse

`POST /v1/pipelines/{name}/rows` and `POST /v1/pipelines/{name}/materialize` SHALL refuse a pipeline
that is disabled with HTTP 409 `pipeline_conflict`, before reading or writing anything and whatever its
declaration holds, after the `Adas-Contract-Version` check, `unknown_pipeline` and the caller's
per-target authorization.

A disabled pipeline writes to its target by no route (see
[pipeline-registry](../pipeline-registry/spec.md), "Register and enable lifecycle"), and these two were
the routes that did not enforce it. Their tolerance existed so a paused pipeline could still be
backfilled, which no consumer relied on: the Enrichment Runner reads only the `enabled=true` feed, drops
a disabled pipeline from its cache, and stalls its own backfill of one until it is re-enabled. Once a
declaration may be stored before it passes the gate, the tolerance would also have written a target
from a declaration that was never armed and so never validated.

409 rather than 422, because the call may be perfectly good and only the pipeline's state is wrong — the
answer a disabled `aggregate` pipeline's on-demand refresh already gives. The two read endpoints,
`…/scan` and `…/group-rows`, SHALL stay open to a disabled pipeline: they write nothing, and the dry-run
preview of a disabled pipeline samples through them.

The cost is accepted and stated: work the runner already has in flight when an operator disables a
pipeline, before its next sync, has its write refused, and those labels go to its dead-letter handling.

#### Scenario: A write-back to a disabled pipeline is refused

- **WHEN** `POST /v1/pipelines/{name}/rows` is called for a disabled pipeline
- **THEN** the request is rejected with HTTP 409 `pipeline_conflict` and no row is written

#### Scenario: A materialize of a disabled pipeline is refused

- **WHEN** `POST /v1/pipelines/{name}/materialize` is called for a disabled pipeline, whether its
  declaration is complete or a draft
- **THEN** the request is rejected with HTTP 409 `pipeline_conflict`, no statement is executed, and no
  position or drained horizon is recorded

#### Scenario: The contract and existence checks still come first

- **WHEN** either endpoint is called for a disabled pipeline with a wrong `Adas-Contract-Version`, or
  for a name that resolves to no pipeline
- **THEN** the response is the contract 409 or the 404 `unknown_pipeline` respectively, not the
  disabled-pipeline refusal
