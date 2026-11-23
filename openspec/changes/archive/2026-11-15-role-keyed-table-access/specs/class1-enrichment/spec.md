## MODIFIED Requirements

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
identity over the columns the pipeline's expressions and filter reference, the target's grain key, and the read
source's `version_column` and `identity_column`, whose values the `nextCursor` carries readably, per owning table, and a
non-entitled caller SHALL be rejected with HTTP 403 `sensitive_column_not_entitled` — so a runner
without `TABLE_SENSITIVE` on the table that owns the column cannot cause sensitive data to be read
and derived even though no body is returned to it. The scan already judges those two cursor columns the same way.

Because a `sensitive` column is *hidden* from a non-entitled caller's schema view, such a caller cannot
parse an expression that reads one: validation would fail first with an unknown-column error. The system
SHALL NOT surface that as a 422 "invalid expression"; when a governed-expression validation fails for a
caller who may not read the source's sensitive columns, it SHALL be reported as HTTP 403
`sensitive_column_not_entitled` instead.

A column a column policy hides from the calling identity (see
[table-access-control](../table-access-control/spec.md), "Column visibility by caller role" and "Pipeline and export
paths refuse a hidden column") is hidden from its schema view the same way. A reference to one SHALL be refused with
HTTP 403 `column_not_entitled`, checked before the clearance, and a governed-expression validation failure SHALL be
re-attributed to `column_not_entitled` when the read source or one of its active enrichments has a column hidden from
the caller by a column policy, otherwise to `sensitive_column_not_entitled` as above. Only a caller from whom no column
of those tables is hidden — by the clearance or by a column policy — SHALL have its validation failure reported
accurately, so a genuinely broken declaration (e.g. a source column dropped since registration) still reaches the
caller who can act on it.

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

#### Scenario: A sensitive version column needs the clearance to materialize

- **GIVEN** a read source whose `version_column` is `sensitive`
- **WHEN** a caller holding `TABLE_READ` on the read source without the clearance, and `TABLE_WRITE` on the target,
  calls `materialize` for a pipeline whose expressions read no sensitive column
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`

#### Scenario: A caller from which a column policy hides a column the expressions read cannot materialize

- **WHEN** a caller holding `TABLE_READ` and the clearance on the read source and `TABLE_WRITE` on the target, from
  which a column policy hides a source column the pipeline's expression reads, calls `materialize`
- **THEN** the request is rejected with HTTP 403 and error code `column_not_entitled`, not with a 422 about an invalid
  expression

#### Scenario: A bounded window materializes only its range

- **WHEN** `materialize` is called with `from` and `to`
- **THEN** only source rows whose version is inside `(from, to]` contribute to the write

#### Scenario: Materialize of a disabled pipeline is refused

- **WHEN** `POST /v1/pipelines/{name}/materialize` is called for a disabled `sql` pipeline
- **THEN** the request is rejected with HTTP 409 `pipeline_conflict`, nothing is written, and no
  position or drained horizon is recorded
