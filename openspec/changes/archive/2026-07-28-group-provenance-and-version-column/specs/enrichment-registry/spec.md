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

A returned rule SHALL additionally carry `version_column`: the **physical name of the source's version
column**, derived from the source's catalog scan metadata (`table_definition.version_column`) — the same
fact the scan path resolves, so the two cannot disagree. It SHALL NOT be declarable on the rule. A scan
page projects that column under its own name, and for a `group` rule its value is the group's activity
timestamp; surfacing the name is what lets a caller read it per source rather than holding one
configured name for every source it scans. When the source is unknown or declares no scan metadata,
`version_column` SHALL be **absent** and the read SHALL still succeed — the rule is valid, and an
unscannable source is rejected on the scan path (HTTP 422) rather than on a registry read.

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

#### Scenario: A returned rule carries the source's version column

- **WHEN** a rule is created, updated, or read and its source declares scan metadata
- **THEN** the response carries `version_column` equal to the source's declared version column

#### Scenario: A source without scan metadata omits version_column

- **WHEN** a rule is read and its source declares no version column (or the source is unknown)
- **THEN** `version_column` is absent from the response and the read succeeds

#### Scenario: Update bumps generation

- **WHEN** `PUT /v1/rules/{id}` changes a rule
- **THEN** the stored rule is updated, `generation` is incremented, and `updated_at` advances

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

### Requirement: Runner sync feed with resolved evaluator and change detection

The system SHALL expose `GET /v1/rules[?updated_since=…]` returning each **enabled** rule (the feed
is enabled-only; disabled rules are never returned) with its **resolved pinned evaluator inlined**
(the concrete version, resolving `latest` at sync time), the derived source/grain, the source's
`version_column` (absent when the source declares none), and a `generation` value the runner uses for
change detection (surfaced as an ETag). `updated_since` SHALL filter to rules changed at or after the
given timestamp. For a `group` rule the feed SHALL additionally inline `group_by` and `ready_when`, so a
caller can route the rule to its group path and evaluate readiness without a second round-trip.

The feed SHALL resolve every rule in a **constant number of queries** regardless of how many rules it
returns — including the version-column resolution, which SHALL be one batched fetch over the distinct
sources of the returned rules rather than a per-rule lookup.

#### Scenario: Sync returns enabled rules with inlined evaluators

- **WHEN** `GET /v1/rules` is called
- **THEN** each enabled rule is returned with its resolved evaluator version inlined, derived
  source/grain, and a `generation`

#### Scenario: Sync carries the source's version column

- **WHEN** `GET /v1/rules` is called and an enabled rule's source declares scan metadata
- **THEN** that rule carries `version_column` equal to the source's declared version column

#### Scenario: Incremental sync filters by updated_since

- **WHEN** `GET /v1/rules?updated_since=<t>` is called
- **THEN** only enabled rules with `updated_at >= t` are returned

#### Scenario: Sync inlines the group declaration

- **WHEN** `GET /v1/rules` is called and an enabled rule has `trigger_kind: "group"`
- **THEN** that rule's `group_by` and `ready_when` are inlined alongside its resolved evaluator
