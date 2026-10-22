## MODIFIED Requirements

### Requirement: Rule-scoped scan and write authorization

The scan, the group assembly, and the write endpoints SHALL be authorized by the rule's
**target-enrichment access** (reusing the role-based `access` model), not by a global read/write
authority. A caller lacking the required access on the rule's target enrichment SHALL be rejected with
HTTP 403 `rule_access_denied`. `FULL_ADMIN` SHALL satisfy the requirement. The group assembly endpoint
SHALL be authorized identically to the scan — it is a rule-scoped read of the same source, so a caller
that may scan a rule may assemble its groups.

This holds unchanged when the rule declares a **read source** other than its target enrichment's
`source_table` (see [enrichment-registry](../enrichment-registry/spec.md)): the authorizing table stays
the target enrichment, and the read source is not separately authorized. That is not a widening — the
`access` model delegates only `write` and `modify` and has no read grant to bypass, so what governs
exposure of the read source's rows is the sensitive-column re-check below, which SHALL be applied
against the **rule's read source**. A rule reading a table other than its target's is therefore
constrained exactly as one reading its target's is: by who may operate the target enrichment, and by
which of the read source's columns the calling identity may see.

#### Scenario: Least-privilege scan is authorized by target access only

- **WHEN** a non-admin caller holding the target enrichment's access calls `POST /v1/rules/{id}/scan`
- **THEN** the scan is authorized without requiring any global read authority

#### Scenario: Missing target access is rejected

- **WHEN** a caller without the required target-enrichment access calls the scan, group assembly, or
  write endpoint
- **THEN** the request is rejected with HTTP 403 and error code `rule_access_denied`

#### Scenario: A declared read source is authorized by the target enrichment

- **WHEN** a caller holding the target enrichment's access scans a rule whose declared read source is a
  different table
- **THEN** the scan is authorized by the target-enrichment access alone, and the sensitive-column
  re-check is applied against the columns of the declared read source

### Requirement: Sensitive-column re-check on scan

Independently of the target-enrichment access grant, the scan SHALL re-apply the sensitive-column
rule (see [enrichment-registry](../enrichment-registry/spec.md)) against the **scanning caller's**
identity: if the scan's projection includes a `sensitive`-flagged column of the rule's **read source**
and the caller may not see sensitive columns (not `FULL_ADMIN`, and `security.mode` is not `none`), the
scan SHALL be rejected with HTTP 403 `sensitive_column_not_entitled`. This holds even though the stored
rule was registered by a `FULL_ADMIN` author, so a non-`FULL_ADMIN` runner cannot read sensitive data,
and it holds whether the read source is the target enrichment's `source_table` or a declared one.

The refusal SHALL carry that code **however the sensitive column is reached** — projected directly via a
bound column, or referenced by the rule's `filter_sql` or `ready_when.signal`. A non-entitled caller
cannot even parse the stored predicate, because the SQL front end validates against a schema from which
sensitive columns are hidden for that caller; such a parse failure SHALL be re-attributed to
`sensitive_column_not_entitled` rather than reported as a malformed rule (422), which would both mislead
and misstate whose problem it is. An entitled caller's parse failure SHALL be reported unchanged, so a
genuinely broken rule still reports accurately to the caller who can fix it.

#### Scenario: Non-entitled runner scanning a sensitive-referencing rule is rejected

- **WHEN** a caller who may not see sensitive columns calls `POST /v1/rules/{id}/scan` for a rule
  whose projection includes a `sensitive`-flagged column of the rule's read source
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`

#### Scenario: The refusal holds for a sensitive column referenced only by the rule's filter

- **WHEN** the same caller scans a rule whose `filter_sql` (or `ready_when.signal`) references a
  `sensitive` column of the read source that no binding projects
- **THEN** the request is rejected with HTTP 403 and error code `sensitive_column_not_entitled`, not with
  a validation error

#### Scenario: FULL_ADMIN runner scanning a sensitive-referencing rule is allowed

- **WHEN** a `FULL_ADMIN` caller (or any caller in `none` mode) calls the same scan
- **THEN** the sensitive column is projected and the scan proceeds normally
