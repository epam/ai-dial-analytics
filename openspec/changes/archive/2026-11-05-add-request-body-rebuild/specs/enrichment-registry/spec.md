## ADDED Requirements

### Requirement: A computed field may be bound as a transform input and nowhere else

A `transform.inputs` entry's `column` SHALL be allowed to name a computed field of the read source's entity —
`dial_usage_log_payload.request_body_full` or `dial_usage_log_payload.request_body_rebuild` (see the data-sync
capability, "A rebuilt request body is readable beside the stored one") — under the entry's existing resolution
and its existing sensitive-column entitlement check, which SHALL treat the field as `sensitive` when
`dial_usage_log_payload.request_body` or `dial_usage_log_payload.request_cache_control` is. A computed field is nullable, so the rule that an enrichment-sourced value is
readable only where absence is representable SHALL admit it.

Every other place a declaration names a column SHALL refuse a computed field with HTTP 422
`pipeline_validation_failed`, naming the field and stating that it can only be bound as a transform input: `filter`,
`trigger.ready_when.signal`, `trigger.member_select.prefer_sql`, `trigger.member_select.order_by`,
`trigger.group_by`, and a `sql` transform's output expressions. The refusal SHALL apply when a declaration is
created, patched, previewed or imported, a write that leaves the pipeline disabled included: it is a check by name,
like the trigger contradictions refused on every write, because no further authoring makes such a declaration valid
and the data plane reads a draft as stored. A name inside a fragment's string literal or comment is not a reference.
Being by name, it SHALL NOT depend on the caller's entitlement, and it SHALL precede the sensitive-column entitlement
check, which governs the one accepted place: a caller not entitled to the field is refused binding it in
`transform.inputs` exactly as binding `request_body` is.

The scan and group-assembly reads SHALL refuse, with the same 422, a stored declaration whose
`trigger.member_select.order_by` or `trigger.group_by` names a computed field (its fragments are refused by name when
they are parsed), so a declaration stored before this refusal existed is never ranked or grouped by the value a
statement reads in the field's place.

#### Scenario: Binding the rebuilt body is accepted

- **WHEN** an entitled author declares a `transform.inputs` entry `{"conversation": {"column":
  "dial_usage_log_payload.request_body_full"}}` on a pipeline reading `dial_usage_log`
- **THEN** the pipeline is accepted and the binding is stored under that qualified name

#### Scenario: A computed field in a filter is refused

- **WHEN** a declaration's `filter` reads `"dial_usage_log_payload.request_body_rebuild" = 'rebuilt'`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the field

#### Scenario: Ranking members by a computed field is refused

- **WHEN** a group declaration's `trigger.member_select.order_by` names `dial_usage_log_payload.request_body_full`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the field

#### Scenario: A draft cannot store a computed ranking

- **WHEN** a registration with `"enabled": false`, or a patch leaving the pipeline disabled, declares
  `trigger.member_select.order_by` naming `dial_usage_log_payload.request_body_full`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the field, and nothing is stored

#### Scenario: A field named in a literal is no reference

- **WHEN** a draft's `filter` reads `deployment = 'dial_usage_log_payload.request_body_full'`
- **THEN** the declaration is stored

#### Scenario: A stored computed ranking is not read

- **WHEN** a group pipeline stored with `trigger.member_select.order_by` naming a computed field is assembled
- **THEN** the read is refused with HTTP 422 `pipeline_validation_failed` naming the field, before any statement runs

#### Scenario: A sql transform cannot read a computed field

- **WHEN** a `type=sql` declaration's output expression reads `dial_usage_log_payload.request_body_full`
- **THEN** the request is rejected with HTTP 422 `pipeline_validation_failed` naming the field

#### Scenario: A non-entitled author cannot bind it

- **WHEN** an author without `TABLE_SENSITIVE` on `dial_usage_log_payload` binds `dial_usage_log_payload.request_body_full`
- **THEN** the request is refused exactly as binding `dial_usage_log_payload.request_body` is

#### Scenario: Sensitive markers make the rebuilt body sensitive

- **WHEN** `dial_usage_log_payload.request_body` is not `sensitive`, `dial_usage_log_payload.request_cache_control`
  is, and an author without `TABLE_SENSITIVE` on `dial_usage_log_payload` binds
  `dial_usage_log_payload.request_body_full`
- **THEN** the request is rejected with HTTP 403 `sensitive_column_not_entitled`
