## MODIFIED Requirements

### Requirement: Sensitive-column entitlement

A pipeline SHALL reference only columns the caller is permitted to use, in its `filter`, `bound` input
bindings, `trigger.group_by`, `ready_when.signal`, `member_select` policy and `sql` output expressions,
and in the target's grain key. `trigger.group_by` and the grain key are named because they were
once the two references that joined the gate's reference set unexamined, and both are copied into the
target: the grain key is read by every derivation and stored as the target's key, so a sensitive one is
copied into the target on every row. A column flagged `sensitive` SHALL be usable in a pipeline only by a caller
entitled to see it — that is, one holding `TABLE_READ` and `TABLE_SENSITIVE` **on the table that owns
the column**, each globally or granted on that table — for an enrichment's column, on its source (see
`table-access-control`, "An enrichment's read-side access is its source's") — or any caller when `security.mode` is
`none` — mirroring the filter
applied on the query surface, which is likewise decided per owning table. A pipeline
referencing a `sensitive` column by a caller not so entitled SHALL be rejected with HTTP 403
`sensitive_column_not_entitled`.

**This is now a second, independent requirement on the author, and that is a change.** While register
and enable were full-admin-only, a legitimately authorized author always satisfied the entitlement check
as a side effect; register and enable now require `REGISTRY_ADMIN`, which confers no table privilege at
all. So an author mapped to `REGISTRY_ADMIN` alone is refused `403 sensitive_column_not_entitled` on a
sensitive-referencing declaration where the same authority previously always succeeded, and must also hold
`TABLE_READ` and `TABLE_SENSITIVE` on the read source — each globally or by a grant. That is the point of separating the
two: authoring what a pipeline computes and being entitled to the values it reads are different
authorities, and the second is now grantable per table rather than only as part of a bundle. The same rule SHALL be re-applied on the
runner's read path (see the enrichment scan): a scan whose projection includes a `sensitive` column
SHALL be rejected with the same status and code unless the scanning caller is entitled, so a
runner without that entitlement cannot read sensitive data even though the stored declaration
references it.

The author's entitlement is checked when the gate runs and is **not** re-checked afterwards: an enabled
pipeline keeps running as declared even if a column it reads is later marked `sensitive`, or its author
loses the entitlement. No approval is stored for a later read to be compared against. This is a deliberate
scope decision. An operator who marks a column `sensitive` and wants its derived copies stopped disables
the pipelines that read it; enabling one again runs the gate against whoever enables it.

#### Scenario: Sensitive reference by an entitled caller is accepted

- **WHEN** a caller holding `TABLE_READ` and `TABLE_SENSITIVE` on the read source (or any caller in
  `none` mode) registers a pipeline whose filter or bound input binding references a `sensitive`-flagged source
  column
- **THEN** the entitlement check passes

#### Scenario: Sensitive reference by a non-entitled caller is rejected

- **WHEN** a caller who may not see sensitive columns references a `sensitive`-flagged source column
  in a pipeline's filter or a bound input binding
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`

#### Scenario: Non-sensitive reference is accepted

- **WHEN** a pipeline's filter and bound bindings reference only non-`sensitive` source columns
- **THEN** the entitlement check passes

#### Scenario: Editing a transform re-runs the full gate

- **GIVEN** an enabled `sql` pipeline whose target carries no `sensitive` column
- **WHEN** a caller holding `REGISTRY_ADMIN` but not both `TABLE_READ` and `TABLE_SENSITIVE` on the
  read source
  patches its `transform` so an output expression reads a `sensitive` column of the read source
- **THEN** the patch is rejected `403 sensitive_column_not_entitled`, and the stored declaration is
  unchanged — the expression lives on the pipeline row, so there is no way to change what a stored
  pipeline reads that bypasses this gate

### Requirement: Sensitive-column entitlement crosses the grain join

The sensitive-column rule SHALL apply to an enrichment column exactly as it applies to a read-source
column. A `sensitive` column of an `ACTIVE` enrichment of the pipeline's read source SHALL be
referenceable only by a caller holding `TABLE_READ` and `TABLE_SENSITIVE` on that enrichment — which are the
privileges it holds on the enrichment's **source** (see `table-access-control`, "An enrichment's read-side access is
its source's"), each globally or granted there — and a reference by any other caller SHALL be rejected with HTTP 403
`sensitive_column_not_entitled`.

Every enrichment of the read source therefore shares the read source's entitlement: a caller entitled to the read
source's sensitive columns is entitled to those of each of its enrichments, and one that is not is entitled to none.
The rule is kept as its own requirement because an enrichment column's entitlement is still decided on the table the
enrichment hangs off, which, for an enrichment reached through the read source's grain join, is the read source.

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

- **WHEN** a caller holding `TABLE_READ` and `TABLE_SENSITIVE` on that enrichment's source (or any caller in
  `none` mode)
  registers a pipeline referencing a `sensitive` column of an `ACTIVE` enrichment of its read source
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

#### Scenario: An enrichment's sensitive column is referenceable with the source's entitlement

- **GIVEN** a read source with an `ACTIVE` enrichment carrying a `sensitive` column
- **WHEN** a caller holding `TABLE_READ` and `TABLE_SENSITIVE` on the read source registers a pipeline whose filter
  references `"<enrichment>.<column>"`
- **THEN** the entitlement check passes
- **AND WHEN** a caller holding `TABLE_READ` on the read source without the clearance registers the same pipeline
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`
