## MODIFIED Requirements

### Requirement: Sensitive-column entitlement

The pipeline's `filter`, `bound` input bindings, `trigger.group_by`, `ready_when.signal`,
`member_select` policy and `sql` output expressions, and the target's grain key, SHALL reference only
columns the caller is permitted to use. `trigger.group_by` and the grain key are named because they were
once the two references that joined the gate's reference set unexamined, and both are copied into the
target: the grain key is read by every derivation and stored as the target's key, so a sensitive one is
copied into the target on every row. A column flagged `sensitive` SHALL be usable in a pipeline only by a caller
entitled to see it — that is, one holding `TABLE_READ` and `TABLE_SENSITIVE` **on the table that owns
the column**, each globally or granted on that table, or any caller when `security.mode` is `none` — mirroring the filter
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

### Requirement: Sensitive-column entitlement crosses the grain join

The sensitive-column rule SHALL apply to an enrichment column exactly as it applies to a read-source
column. A `sensitive` column of an `ACTIVE` enrichment of the pipeline's read source SHALL be
referenceable only by a caller holding `TABLE_READ` and `TABLE_SENSITIVE` **on that enrichment** —
each globally or granted on it — and a reference by any other caller SHALL be rejected with HTTP 403
`sensitive_column_not_entitled`.

Per owning table is what makes this a real rule rather than a restatement. Entitlement to the read
source's sensitive columns no longer implies entitlement to its enrichments': within one flattened entity
a caller may be entitled to the source's and not an enrichment's, or the reverse, and each column is
judged against the table it belongs to.

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

- **WHEN** a caller holding `TABLE_READ` and `TABLE_SENSITIVE` on that enrichment (or any caller in
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

### Requirement: Runner identity and derived-column sensitivity posture

The Enrichment Runner SHALL hold `TABLE_READ` and `TABLE_SENSITIVE` on a pipeline's read source for
any pipeline that references a `sensitive` column of it — each globally or **granted on that table**. The entitlement
check itself is unchanged: it is applied at pipeline registration and again at every read/derive egress,
against the **calling** identity, per owning table.

What changed is that a narrower grant now exists. The original least-privilege stance ("per-target grants
only, no global admin") was superseded for these pipelines only because `SensitiveColumnAccess` admitted
nothing but the full administrative role; with the privilege grantable per table, a runner operating a
sensitive-touching pipeline needs `REGISTRY_READ` for the sync feed plus three per-table grants —
`read` and `sensitive` on the read source (the clearance confers no read on its own), and `write` on
the target — and no global authority at all. The
least-privilege stance therefore stands for every pipeline again.

What bounds such a runner is unchanged, and no longer has to carry the argument alone: the surface stays
pipeline-scoped in both directions: a scan projects only declaration-referenced columns, a
group fetch returns only the pipeline's membership, and a write-back rejects any column outside the
pipeline's declared outputs — the entitlement changes *which columns a pipeline may reference*, not
*which pipelines the runner may operate*. The per-table `read` + `sensitive` grant pair **is** the narrower
sensitive grant this requirement named as a deferred follow-up; a per-pipeline one remains out of
scope.

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
