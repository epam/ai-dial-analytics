## ADDED Requirements

### Requirement: Rule-scoped group-input assembly endpoint

The system SHALL expose `POST /v1/rules/{id}/groups:fetch` accepting a body `{groupKey, limit?}` and
returning `{rows[], truncated}`. It SHALL resolve the rule's source, membership `filter`, projection,
and ordering from the stored rule; the caller SHALL supply **only** the group key, and SHALL NOT name a
table, a filter, a column list, or an ordering. The assembled query SHALL select the source rows whose
`group_by` column equals `groupKey` and which satisfy the rule's `filter`, ordered by the source's
total order, read with `final=1`, and bounded by the caps below. The rule SHALL be a `group` rule; a
request for a rule with any other trigger kind SHALL be rejected with HTTP 422
`rule_validation_failed`. An unknown rule SHALL return HTTP 404 `unknown_rule`.

The endpoint SHALL apply the **same safety-lag** as the scan. Assembling from rows the scan has not yet
surfaced would produce a group whose contents disagree with the group version the caller
dirty-marked, so consistency between the two reads takes precedence over assembling the newest
possible group.

A group key that matches no rows — because it has aged out of the source's retention, because the
membership filter excluded every member, or because it never existed — SHALL return HTTP 200 with an
empty `rows` array and `truncated: false`. It SHALL NOT be an error: groups are discovered from a row
scan and expire with the source, so an empty group is a normal race rather than a caller mistake.

#### Scenario: A group's members are assembled, filtered and ordered

- **WHEN** `POST /v1/rules/{id}/groups:fetch` is called with a `groupKey` for a `group` rule
- **THEN** the response contains the source rows sharing that group key which satisfy the rule's
  membership filter, in the source's total order, projected to the rule's referenced columns

#### Scenario: The caller supplies only the group key

- **WHEN** any `groups:fetch` request is made
- **THEN** the source table, membership filter, projection, and ordering are resolved from the stored
  rule, and no caller-supplied table, filter, or column list is accepted

#### Scenario: A non-group rule is rejected

- **WHEN** `POST /v1/rules/{id}/groups:fetch` is called for a rule whose `trigger_kind` is not `group`
- **THEN** the request is rejected with HTTP 422 and error code `rule_validation_failed`

#### Scenario: An unknown or empty group returns an empty page

- **WHEN** `groups:fetch` is called with a `groupKey` matching no rows
- **THEN** the response is HTTP 200 with `rows: []` and `truncated: false`

### Requirement: Group-input payload caps

The group assembly response SHALL be bounded by **both** a row count and a response byte size, each
with a configured default and maximum. A group whose members carry `sensitive` body columns can be
megabytes, so a row cap alone would leave the real failure mode unbounded. When either cap trims the
group, the response SHALL set `truncated: true`, so a caller can record that its input was partial and
a downstream consumer can identify a partial result. When neither cap trims the group,
`truncated` SHALL be `false`.

#### Scenario: A row cap trims the group

- **WHEN** a group has more members than the effective row limit
- **THEN** the response returns at most that many rows and `truncated: true`

#### Scenario: A byte cap trims the group

- **WHEN** a group's members are individually small enough in number but exceed the response byte cap
- **THEN** the response is trimmed to fit the cap and `truncated: true`

#### Scenario: A group within both caps is not marked truncated

- **WHEN** a group fits within both the row and the byte cap
- **THEN** every member is returned and `truncated: false`

### Requirement: Sensitive-column re-check on group assembly

The group assembly endpoint SHALL re-apply the sensitive-column rule against the **calling** identity,
exactly as the scan does: if the assembled projection includes a `sensitive`-flagged source column and
the caller may not see sensitive columns (not `FULL_ADMIN`, and `security.mode` is not `none`), the
request SHALL be rejected with HTTP 403 `sensitive_column_not_entitled`. Sensitive columns SHALL NOT be
silently stripped from the projection — a group input missing the very column the rule was written to
read would yield a quietly wrong label, so the misconfiguration fails loudly instead. The scan and the
group assembly SHALL share one implementation of this check so their behaviour cannot diverge.

#### Scenario: An entitled caller receives body columns

- **WHEN** a `FULL_ADMIN` caller (or any caller in `none` mode) assembles a group for a rule whose
  projection includes `request_body` or `response_body`
- **THEN** those columns are present in the returned member rows

#### Scenario: A non-entitled caller is rejected rather than served a stripped group

- **WHEN** a caller who may not see sensitive columns assembles the same group
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`, and no
  partially-projected group is returned

## MODIFIED Requirements

### Requirement: Scan projection and version isolation

The scan query SHALL read the **base source only, with no enrichment LEFT JOINs**, and SHALL push
projection down to `{columns referenced by the rule's filter and input bindings} ∪ {grain id} ∪
{cursor columns}`. The scan SHALL read with `final=1` so only the latest version per source
identity is returned, independent of the projected columns.

For a rule whose `trigger_kind` is `group`, the projection SHALL additionally include the rule's
`group_by` column and every column referenced by `ready_when.signal`, so one scan page tells the caller
which groups changed and whether any scanned row signalled completion. The activity timestamp needed to
evaluate an idle window requires no addition: the scan already projects the source's version and
identity columns under their own names. Every other aspect of the scan — the keyset predicate, the
opaque cursor, the safety-lag, `final=1`, ordering, and the sensitive-column re-check — SHALL be
unchanged for a group rule; the dirty-mark read is the same endpoint with a richer projection, not a
second endpoint. Its cost is therefore proportional to **new rows**, not to the number of groups.

#### Scenario: Scan omits enrichment-join columns

- **WHEN** the scanned source has one or more enrichments
- **THEN** the returned rows contain only source/projected columns — no auto-joined enrichment columns

#### Scenario: Projection is limited to referenced columns

- **WHEN** a rule references only a subset of the source's columns
- **THEN** the scan projects only those columns plus the grain and cursor columns

#### Scenario: A group rule's scan projects the group key and signal columns

- **WHEN** `POST /v1/rules/{id}/scan` is called for a rule whose `trigger_kind` is `group`
- **THEN** each returned row additionally carries the rule's `group_by` value, the columns referenced
  by `ready_when.signal`, and the source's version column as an activity timestamp

#### Scenario: A group rule's scan keeps row-scan semantics

- **WHEN** a group rule is scanned across pages
- **THEN** paging, cursor advance, safety-lag, `final=1`, and the sensitive-column re-check behave
  exactly as for a row-grain rule

### Requirement: Rule-scoped scan and write authorization

The scan, the group assembly, and the write endpoints SHALL be authorized by the rule's
**target-enrichment access** (reusing the role-based `access` model), not by a global read/write
authority. A caller lacking the required access on the rule's target enrichment SHALL be rejected with
HTTP 403 `rule_access_denied`. `FULL_ADMIN` SHALL satisfy the requirement. The group assembly endpoint
SHALL be authorized identically to the scan — it is a rule-scoped read of the same source, so a caller
that may scan a rule may assemble its groups.

#### Scenario: Least-privilege scan is authorized by target access only

- **WHEN** a non-admin caller holding the target enrichment's access calls `POST /v1/rules/{id}/scan`
- **THEN** the scan is authorized without requiring any global read authority

#### Scenario: Missing target access is rejected

- **WHEN** a caller without the required target-enrichment access calls the scan, group assembly, or
  write endpoint
- **THEN** the request is rejected with HTTP 403 and error code `rule_access_denied`
