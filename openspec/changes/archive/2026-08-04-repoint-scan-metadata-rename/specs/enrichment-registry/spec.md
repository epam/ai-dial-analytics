## MODIFIED Requirements

### Requirement: Rule CRUD binding an evaluator to a target enrichment

The system SHALL manage rules via `POST /v1/rules`, `PUT /v1/rules/{id}`, `DELETE /v1/rules/{id}`,
and `GET /v1/rules/{id}`. A rule SHALL bind an `evaluator_name` (+ optional `evaluator_version`,
null meaning `latest`) to a `target_enrichment`, plus `trigger_kind`, optional `trigger_cron`,
`group_by`, `ready_when`, `filter_sql`, `input_bindings`, `output_bindings`, and execution knobs
(`sampling`, `cadence`, `batch_scan_limit`, `batch_chunk`, `rate_rpm`, `priority`, `enabled`). The
rule's **scan source and grain SHALL be derived** from the target enrichment's `parent`/`grain` and
SHALL NOT be re-declared on the rule. Each mutating rule operation SHALL bump a `generation` counter
and `updated_at`. An unknown rule id SHALL be rejected with HTTP 404 `unknown_rule`.

`enabled` SHALL be **required** on a create/update request and SHALL carry no implicit default: whether
a rule starts live is an operator decision, and an omitted or null `enabled` SHALL be rejected as a
validation failure (HTTP 400 `bad_request`) rather than silently defaulting to `false`.

A returned rule SHALL additionally carry `version_column`: the **physical name of the source's version
column**, derived from the source's catalog scan metadata (`table_definition.version_column`) — the same
fact the scan path resolves, so the two cannot disagree. It SHALL NOT be declarable on the rule. A scan
page projects that column under its own name, and for a `group` rule its value is the group's activity
timestamp; surfacing the name is what lets a caller read it per source rather than holding one
configured name for every source it scans. When the source is unknown or declares no scan metadata,
`version_column` SHALL be **absent** and the read SHALL still succeed — the rule is valid, and an
unscannable source is rejected on the scan path (HTTP 422) rather than on a registry read.

Because `version_column` is derived rather than declared, it can change without any rule being mutated:
`PATCH /v1/tables/{source}/schema` MAY rename the column and repoint the stored name (see
[table-management](../table-management/spec.md)). A rule mutation is therefore **not** the only event
that bumps `generation`: when a source's `version_column` is repointed, every rule whose target
enrichment reads that source SHALL have its `generation` incremented and its `updated_at` advanced, in
the same transaction as the catalog repoint. Both, not just `generation` — the sync feed selects by
`updated_at`, so a bumped generation the feed does not return would be no signal at all. Without this a
runner detecting change by `generation` would keep the pre-rename name and read a key its scan pages no
longer carry.

`trigger_kind` SHALL be one of `on_ingest`, `schedule`, or `group`. A `group` rule fires once per
**group key** rather than once per row: it declares `group_by` (the group key) and `ready_when`
(`{signal?, idle?, max_staleness?, cost_ceiling?}`), the declaration of when a group is considered
complete enough to evaluate. A `group` rule SHALL NOT carry `trigger_cron` — its cadence is the
caller's readiness sweep, not a cron expression — which the existing cron-iff-`schedule` invariant
already enforces. A `group` rule writes exactly one enrichment row per group key through the existing
rule-scoped write-back, keyed by `group_by`, and the target enrichment's `ReplacingMergeTree` collapses
by that key.

#### Scenario: Create a rule derives its source and grain

- **WHEN** `POST /v1/rules` is called with a valid evaluator and `target_enrichment`
- **THEN** the rule is created with `source`/`grain` derived from the target enrichment, `enabled`
  per the request, `generation` initialized, and HTTP 201 returned

#### Scenario: Omitted enabled is rejected

- **WHEN** `POST /v1/rules` or `PUT /v1/rules/{id}` is called with `enabled` absent or null
- **THEN** the request is rejected with HTTP 400 `bad_request` and no rule is created or updated

#### Scenario: A returned rule carries the source's version column

- **WHEN** a rule is created, updated, or read and its source declares scan metadata
- **THEN** the response carries `version_column` equal to the source's declared version column

#### Scenario: A source without scan metadata omits version_column

- **WHEN** a rule is read and its source declares no version column (or the source is unknown)
- **THEN** `version_column` is absent from the response and the read succeeds

#### Scenario: Update bumps generation

- **WHEN** `PUT /v1/rules/{id}` changes a rule
- **THEN** the stored rule is updated, `generation` is incremented, and `updated_at` advances

#### Scenario: Repointing the source's version column bumps generation

- **WHEN** `PATCH /v1/tables/{source}/schema` renames the column named by that source's
  `version_column`, and a rule's target enrichment reads that source
- **THEN** that rule's `generation` is incremented and its `updated_at` advances in the same
  transaction as the catalog repoint, and a subsequent `GET /v1/rules/{id}` carries the renamed
  `version_column`
- **AND** a rule whose target enrichment reads a different source is untouched

#### Scenario: A repointed version column reaches an incremental sync

- **WHEN** a runner has synced with `updated_since` and the source then repoints its `version_column`
- **THEN** the next sync at that watermark returns the affected rules, each carrying the new
  `version_column` and a higher `generation`

#### Scenario: Renaming a non-version column of the source bumps nothing

- **WHEN** `PATCH /v1/tables/{source}/schema` renames a source column that is neither the source's
  `version_column` nor its `identity_column`
- **THEN** no rule's `generation` or `updated_at` changes

#### Scenario: Delete removes the rule

- **WHEN** `DELETE /v1/rules/{id}` is called for an existing rule
- **THEN** the rule is removed and HTTP 204 returned

#### Scenario: Unknown rule is rejected

- **WHEN** any rule read/update/delete targets an id that does not exist
- **THEN** the request is rejected with HTTP 404 and error code `unknown_rule`

#### Scenario: Create a group rule

- **WHEN** `POST /v1/rules` is called with `trigger_kind: "group"`, a `group_by` equal to the target
  enrichment's grain key, and a `ready_when` carrying at least one readiness condition
- **THEN** the rule is created and HTTP 201 returned
