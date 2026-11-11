## ADDED Requirements

### Requirement: Instance setting storage model

The system SHALL store instance settings in the PostgreSQL catalog, one document per setting name, with at
most one value per setting per ADAS instance. Each stored setting SHALL carry its `value` (the setting's
body), `updated_at` (the server time of the last successful write) and `updated_by` (the identity that made
it). The set of setting names SHALL be closed and defined by the service; the only setting name SHALL be
`sessions-source`. A setting that has never been written SHALL be **absent**, not stored with a default.

`updated_by` SHALL be the writing caller's email when its token carries one, otherwise its principal name.
Under `security.mode=none` it SHALL be `null`. Neither `updated_at` nor `updated_by` SHALL be accepted from
a request body.

#### Scenario: A never-written setting is absent

- **WHEN** no `PUT /v1/settings/sessions-source` has ever succeeded on the instance
- **THEN** `GET /v1/settings/sessions-source` responds `404` with error code `setting_not_found`

#### Scenario: A write stamps time and author

- **WHEN** a caller whose token carries the email `analyst@example.com` writes the setting
- **THEN** the response and every subsequent read carry `updated_by` `"analyst@example.com"` and an ISO-8601
  `updated_at` equal to the time of that write

#### Scenario: Unauthenticated mode stores no author

- **WHEN** the setting is written with `security.mode=none`
- **THEN** the stored setting carries `updated_by` `null`

### Requirement: Sessions-source setting body

The `sessions-source` setting SHALL name the catalog entity that holds session-level rows and bind its fields
to the session roles. Its body SHALL be:

```
{
  "base_table": string,
  "roles": {
    "session_id": string,
    "time": string,
    "project": string | null,
    "title": string | null,
    "cost": string | null,
    "turns": string | null
  },
  "updated_at": string,   // response only, ISO-8601
  "updated_by": string    // response only
}
```

`base_table` SHALL be the wire name of a queryable entity (see `query-schema-discovery`). Each role value
SHALL be a flat field name exactly as `GET /v1/queries/entities/schema/{base_table}` reports it: the bare
name for a column of the source, `<enrichment_table>.<name>` for an enrichment column. `session_id` and
`time` SHALL be required and non-null. `project`, `title`, `cost` and `turns` SHALL be optional; `null` and
an absent member SHALL both mean the role is not set, and a read SHALL return an unset role as `null`.

The role-to-type fit SHALL be:

| Role | Accepted flat field types |
|---|---|
| `session_id` | `string` |
| `time` | `timestamp` |
| `project` | `string` |
| `title` | `string` |
| `cost` | `decimal`, `integer`, `long` |
| `turns` | `integer`, `long` |

The same field MAY be bound to more than one role when its type fits each of them.

#### Scenario: Optional roles read back as null

- **WHEN** a setting is written with `roles` carrying only `session_id` and `time`
- **THEN** a read returns `project`, `title`, `cost` and `turns` each as `null`

#### Scenario: An enrichment field binds by its qualified name

- **WHEN** a setting binds `title` to `session_insights.title`, an enrichment column of `base_table` typed
  `string`
- **THEN** the write succeeds and a read returns `"title": "session_insights.title"`

### Requirement: Sessions-source validation

`PUT /v1/settings/sessions-source` SHALL validate the body against the catalog before storing it, and SHALL
reject it with `422` and error code `validation_error`, storing nothing and leaving any previously stored
value unchanged, when:

- `base_table` is missing or names no `ACTIVE` `source` table — an enrichment, a table that is not
  materialized, or a table that does not exist;
- `roles`, `roles.session_id` or `roles.time` is missing or `null`;
- a bound role names a field absent from `base_table`'s flat schema;
- a bound field's type is not accepted for its role;
- a bound field is `array`-typed, or is a projection-only (computed) field.

Validation SHALL use the entity's flat schema **as the writing caller may see it** at `TABLE_GET` — the bar
of `GET /v1/queries/entities/schema/{name}` — so a table the caller may not get, an enrichment it may not
get, and a sensitive column it holds no clearance for SHALL each be reported exactly as though it did not
exist. The message SHALL name the offending member (`base_table` or `roles.<role>`) and, for a type
mismatch, the field's type and the accepted types. The message SHALL NOT distinguish a hidden table or field
from an absent one.

A body carrying `updated_at`, `updated_by`, or any member not defined above (at the top level or inside
`roles`) SHALL be rejected with `400`, naming the member.

The system SHALL NOT check that `base_table` shares a session identifier with `dial_usage_log`.

#### Scenario: A valid setting is stored

- **WHEN** an administrator writes `base_table` `sessions_rollup` with `session_id` bound to a `string`
  column, `time` to a `timestamp` column, `cost` to a `decimal` column and `turns` to a `long` column
- **THEN** the response is `200` with the stored setting, and a following `GET` returns the same body

#### Scenario: An unknown base table is refused

- **WHEN** the body names a `base_table` that is not in the catalog
- **THEN** the response is `422` with error code `validation_error` naming `base_table`, and the stored
  value is unchanged

#### Scenario: An enrichment table is not a base table

- **WHEN** the body names an enrichment table as `base_table`
- **THEN** the response is `422` with error code `validation_error` naming `base_table`

#### Scenario: An unknown field is refused

- **WHEN** `roles.project` names a field that is not in the entity's flat schema
- **THEN** the response is `422` with error code `validation_error` naming `roles.project`

#### Scenario: A type mismatch is refused

- **WHEN** `roles.time` names a `string` field
- **THEN** the response is `422` with error code `validation_error` naming `roles.time`, the field's type
  `string`, and the accepted type `timestamp`

#### Scenario: An array field is refused

- **WHEN** `roles.project` names an `array` field whose element type is `string`
- **THEN** the response is `422` with error code `validation_error` naming `roles.project`

#### Scenario: A required role is missing

- **WHEN** the body omits `roles.session_id`, or sends it as `null`
- **THEN** the response is `422` with error code `validation_error` naming `roles.session_id`

#### Scenario: A field hidden from the writer is reported as unknown

- **WHEN** a writer holding no `TABLE_SENSITIVE` on `base_table` binds a role to one of its sensitive columns
- **THEN** the response is `422` with error code `validation_error` and the same message an absent field
  produces

#### Scenario: Response-only members are rejected in a request

- **WHEN** the body carries `updated_by`
- **THEN** the response is `400` naming `updated_by`, and nothing is stored

### Requirement: The stored setting is not revalidated on read

A read SHALL return the setting exactly as last written. A later catalog change — a dropped, renamed or
retyped column, a removed enrichment, a deleted or retired `base_table` — SHALL NOT alter, remove or
invalidate the stored setting, and SHALL NOT be refused because the setting refers to it. Only a subsequent
successful `PUT` replaces it.

#### Scenario: A dropped column leaves the setting as written

- **WHEN** a setting binds `title` to a column that is then dropped from `base_table`
- **THEN** `GET /v1/settings/sessions-source` still returns `"title"` bound to that name, and a `PUT` of
  the same body is refused `422 validation_error` naming `roles.title`

### Requirement: Sessions-source API

The system SHALL expose:

- `GET /v1/settings/sessions-source` — `200` with the stored setting, or `404 setting_not_found` when none
  is stored;
- `PUT /v1/settings/sessions-source` — create-or-replace the whole setting, `200` with the stored setting
  carrying the new `updated_at` and `updated_by`.

`PUT` SHALL replace the whole document; there SHALL be no partial update. A `PUT` whose body equals the
stored value SHALL still succeed and SHALL refresh `updated_at` and `updated_by`. Concurrent `PUT`s SHALL
resolve to last-writer-wins, with every read returning one complete written value, never a mix of two.
Every failure SHALL use the `ErrorView` contract.

#### Scenario: Replace overwrites the previous value

- **WHEN** a setting is stored and a `PUT` writes a body with a different `base_table`
- **THEN** a following `GET` returns only the new body, with no role carried over from the previous one

#### Scenario: Two concurrent writes leave one complete value

- **WHEN** two `PUT`s with different bodies run concurrently and both succeed
- **THEN** a following `GET` returns exactly one of the two bodies

### Requirement: Settings authorization

`GET /v1/settings/sessions-source` SHALL require `SETTINGS_READ`. `PUT /v1/settings/sessions-source` SHALL
require `SETTINGS_WRITE`. Both are global-only privileges (see `authentication`). A caller lacking the
required privilege SHALL receive `403 forbidden`; a missing read privilege on the setting is not a
non-disclosure case, because the setting's existence is a fact of the instance, not something the caller
named.

A read SHALL NOT be filtered by the caller's table privileges: a `SETTINGS_READ` holder SHALL see the stored
`base_table` and role field names even when it may not list that table. This is a stated trade-off — the
setting is instance configuration authored by an administrator for every analyst — and reading the data the
setting points at still requires `TABLE_READ` on the query path.

#### Scenario: An analyst bundle reads the setting

- **WHEN** a caller holding `READ_ONLY_ANALYTIC` reads `GET /v1/settings/sessions-source` while a setting is
  stored
- **THEN** the response is `200` with the stored setting

#### Scenario: An analyst bundle cannot write the setting

- **WHEN** a caller holding `READ_ONLY_ANALYTIC` sends `PUT /v1/settings/sessions-source`
- **THEN** the response is `403` with error code `forbidden`, no catalog validation result is disclosed,
  and nothing is stored

#### Scenario: A full administrator writes the setting

- **WHEN** a caller holding `FULL_ADMIN` sends a valid `PUT /v1/settings/sessions-source`
- **THEN** the response is `200` with the stored setting

#### Scenario: A caller without a settings privilege cannot read

- **WHEN** a caller whose roles map to `TABLE_READ` alone reads `GET /v1/settings/sessions-source`
- **THEN** the response is `403` with error code `forbidden`
