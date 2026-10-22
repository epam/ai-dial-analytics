## MODIFIED Requirements

### Requirement: Sensitive-column re-check on group assembly

The group assembly endpoint SHALL re-apply the sensitive-column rule against the **calling** identity,
exactly as the scan does, and SHALL bind the check to the columns the **declaration declares** — its bound
input bindings, its membership filter, and its `trigger.member_select` — rather than to the columns a given read
phase happens to project. If any of those is a `sensitive`-flagged column of the read source's
entity — a source column or an enrichment column reached through the grain join, the two governed
alike — and the caller may not see sensitive columns (not `FULL_ADMIN`, and `security.mode` is not
`none`), the request SHALL be rejected
with HTTP 403 `sensitive_column_not_entitled`, **before** either phase reads anything. Sensitive columns
SHALL NOT be silently stripped from the projection — a group input missing the very column the pipeline was
written to read would yield a quietly wrong label, so the misconfiguration fails loudly instead. The scan
and the group assembly SHALL share one implementation of this check so their behaviour cannot diverge.

Naming the owning table SHALL NOT be a way around the check: a declaration that reads
`"dial_usage_log_payload.request_body"` is refused for an unentitled caller on the same terms as one
reading a `sensitive` column of the source itself, and the refusal SHALL carry
`sensitive_column_not_entitled` rather than an unknown-field error, so the operator sees why the
declaration was refused rather than being told the column does not exist.

#### Scenario: An entitled caller receives body columns

- **WHEN** a `FULL_ADMIN` caller (or any caller in `none` mode) assembles a group for a pipeline whose
  projection includes `"dial_usage_log_payload.request_body"` or
  `"dial_usage_log_payload.response_body"`
- **THEN** those columns are present in the returned member rows, joined from the payload table by the
  grain key

#### Scenario: A non-entitled caller is rejected rather than served a stripped group

- **WHEN** a caller who may not see sensitive columns assembles the same group
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`, and no
  partially-projected group is returned

#### Scenario: Entitlement is enforced before the selection phase reads

- **WHEN** a caller who may not see sensitive columns assembles a group for a pipeline that binds a sensitive
  column, even though the selection phase would not project it
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`, and
  neither phase issues a read

#### Scenario: A member row whose payload has expired carries nulls

- **WHEN** an entitled caller assembles a group whose members include an event older than the payload
  table's retention
- **THEN** the member row is returned with its body columns null, and the assembly does not fail

## ADDED Requirements

### Requirement: A pipeline may read every payload column, and `object` joins the nullability exemption

All five payload columns SHALL remain readable by an `enrich`/Class-1 pipeline after the split, so an
expression MAY reference `"dial_usage_log_payload.request_body"` and its siblings through the grain
join exactly as it referenced the bare names before.

The general rule SHALL stand: a value-producing reference to a **non-nullable** enrichment column is
refused, because an unmatched grain join renders it as its type default rather than `NULL`, so a
derived value could not distinguish "no payload row for this event" from a real value. Because the
payload table's retention is shorter than its parent's, unmatched rows are an ordinary occurrence
rather than an edge case, and the distinction is load-bearing. The three body columns satisfy the rule
directly: they are declared `nullable`.

An `object` column SHALL be **exempt** from that refusal, joining `array` and `map`, and for the same
two reasons: ClickHouse has no `Nullable(JSON)`, so the rule would refuse the type permanently; and
the type default is the *empty document*, which every accessor the capability exposes reads exactly as
it reads a present document lacking the key — `toJSONString` yields `'{}'`, subcolumn access yields
`NULL`, `json_extract_array` yields `[]`, and `json_extract_string_ci` yields `''`. Absence and
emptiness are therefore indistinguishable by construction, so no derived value can be silently
corrupted by the default. This is what permits `jwt_claims` and `request_tags` to live on the
enrichment at all.

A consumer SHALL NOT rely on `IS NULL` over a claim or header column to detect a missing payload row:
the rendered value is `{}`. A body column, being `Nullable(String)`, does read `NULL` and remains the
way to make that distinction.

#### Scenario: A pipeline reads a body column through the grain join

- **WHEN** an entitled author registers a `type=sql` pipeline whose expression reads
  `"dial_usage_log_payload.request_body"`
- **THEN** the pipeline is accepted, and its materialize statement emits the grain join for the payload
  enrichment

#### Scenario: A pipeline reads the claim document through the grain join

- **WHEN** an entitled author registers a `type=sql` pipeline whose expression reads
  `json_extract_array("dial_usage_log_payload.jwt_claims", 'roles')`
- **THEN** the pipeline is accepted, because an `object` enrichment column is exempt from the
  non-nullable refusal

#### Scenario: An unmatched grain join yields the empty document, not null

- **WHEN** a pipeline reads `"dial_usage_log_payload.jwt_claims"` for an event whose payload row has
  expired
- **THEN** the expression sees the empty document `{}`, and every extraction over it returns what the
  same extraction returns over a present document that lacks the key

#### Scenario: A non-nullable enrichment column of a scalar type is still refused

- **WHEN** an entitled author registers a `type=sql` pipeline whose expression derives a value from a
  non-nullable `string` column of an enrichment
- **THEN** the pipeline is refused, naming the column and the nullability requirement
