## Why

A sessions view of an analytics client needs to know which catalog table holds session-level data
and which of its fields play the session roles (id, time, project, title, cost, turns). Today the client
hard-codes that answer in a mock, which cannot follow an operator's catalog. The choice is one value per
ADAS instance, authored by an administrator and read by every analyst, so it belongs in the service,
validated against the catalog it refers to.

## What Changes

- New endpoint pair under a new `/v1/settings` namespace:
  - `GET /v1/settings/sessions-source` returns the stored setting, or `404 setting_not_found` when none
    has been stored yet.
  - `PUT /v1/settings/sessions-source` replaces the whole setting (create-or-replace, idempotent) and
    returns it with the server-stamped `updated_at` and `updated_by`.
- The setting body is `{base_table, roles: {session_id, time, project, title, cost, turns}}`. Each role
  value is a flat field name exactly as `GET /v1/queries/entities/schema/{base_table}` reports it (bare
  for a source column, `<enrichment_table>.<name>` for an enrichment column), or `null` when the role is
  unset. `session_id` and `time` are required.
- `PUT` validates against the catalog and answers `422 validation_error` when `base_table` is not an
  `ACTIVE` `source`, when a bound field is not in that entity's schema as the writing caller sees it, when
  a field's type does not fit its role (`session_id`/`project`/`title`: `string`; `time`: `timestamp`;
  `cost`: `decimal`, `integer` or `long`; `turns`: `integer` or `long`), or when a bound field is an
  `array` or a projection-only field. Response-only members (`updated_at`, `updated_by`) and unknown members
  in a request body are rejected.
- Two new global-only privileges, `SETTINGS_READ` and `SETTINGS_WRITE`, with
  `SETTINGS_WRITE → SETTINGS_READ` admitted as an implication. `READ_ONLY_ADMIN`, `READ_ONLY_ANALYTIC` and
  `READ_ONLY_SENSITIVE_ANALYTIC` gain `SETTINGS_READ`; `FULL_ADMIN` holds both, being every privilege. The
  closed privilege set grows from sixteen to eighteen.
- The stored setting is a row in a new PostgreSQL table (`instance_setting`, keyed by setting name), audited
  with Envers like every other catalog document; its changes appear in the activity feed under a new
  resource type `Setting`.
- The stored value is not re-validated on read: a later catalog change (a dropped column, a deleted
  table) leaves it stored as written until an administrator replaces it.

## Capabilities

### New Capabilities

- `instance-settings`: per-instance settings stored by ADAS, starting with the sessions-source setting —
  its storage model, `GET`/`PUT` API, catalog validation, authorization and error contract.

### Modified Capabilities

- `authentication`: the closed privilege set gains `SETTINGS_READ` and `SETTINGS_WRITE`, three bundles gain
  `SETTINGS_READ`, and the implication set gains `SETTINGS_WRITE → SETTINGS_READ`.
- `audit-trail`: the instance setting becomes an audited resource, with a new `Setting` resource type keyed
  by the setting name.

## Impact

- **API**: new `GET`/`PUT /v1/settings/sessions-source`; new `ErrorView` code `setting_not_found` (404);
  existing `validation_error` (422) and `forbidden` (403) reused.
- **Catalog schema**: new Flyway migration under `db/migration/postgres` creating `instance_setting` and its
  `instance_setting_aud` mirror.
- **Security**: `Privilege`, `RoleBundle`, `PrivilegeImplications` and `EndpointAuthorizationCoverageTest`
  expectations; deployments whose `roles-mapping` names raw privileges (rather than a bundle) must add
  `SETTINGS_READ` for their analysts to read the setting.
- **Audit**: `ActivityResourceType`, `AuditResourceMapper`.
- **Config**: no new property, so nothing is added to `docs/configuration.md`. The privilege count in the
  `openspec/config.yaml` conventions is updated.
- **Out of scope**: the setting is not part of catalog export/import; ADAS does not check that the base table
  shares its session id with `dial_usage_log`; table deletion and schema changes do not consult the setting.
