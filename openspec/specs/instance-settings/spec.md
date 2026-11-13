# instance-settings Specification

## Purpose

Store per-instance settings that an administrator authors once for the whole ADAS instance and every
analyst reads. The first is the sessions-source setting, which names the catalog source table holding
session-level rows and binds its flat fields (see [query-schema-discovery](../query-schema-discovery/spec.md))
to the session roles a sessions view needs. A setting is validated against the catalog when written, stored as
written, and authorized by the global `SETTINGS_READ` and `SETTINGS_WRITE` privileges (see
[authentication](../authentication/spec.md)); its changes are recorded in the audit trail (see
[audit-trail](../audit-trail/spec.md)).

## Requirements

### Requirement: Instance setting storage model

The system SHALL store instance settings in the PostgreSQL catalog, one document per setting name, with at
most one value per setting per ADAS instance. Each stored setting SHALL carry its `value` (the setting's
body), `updated_at` (the server time of the last successful write) and `updated_by` (the identity that made
it). The set of setting names SHALL be closed and defined by the service. The setting names SHALL be exactly
`sessions-source` and `app-routes`, and a path under `/v1/settings/` naming any other SHALL answer `404
not_found`. A setting that has never been written SHALL be **absent**, not stored with a default.

`updated_by` SHALL be the writing caller's principal name, as dashboards and saved queries record their
author; it SHALL NOT be the caller's email, which the audit feed shows only to `AUDIT_READ` holders, while
the setting is read by every `SETTINGS_READ` holder. Under `security.mode=none` it SHALL be `null`. Neither `updated_at` nor `updated_by` SHALL be accepted from
a request body.

#### Scenario: A never-written setting is absent

- **WHEN** no `PUT /v1/settings/sessions-source` has ever succeeded on the instance
- **THEN** `GET /v1/settings/sessions-source` responds `404` with error code `setting_not_found`

#### Scenario: A write stamps time and author

- **WHEN** a caller with principal `admin` whose token carries the email `admin@example.com` writes the setting
- **THEN** the response and every subsequent read carry `updated_by` `"admin"`, not the email, and an
  ISO-8601 `updated_at` equal to the time of that write

#### Scenario: Unauthenticated mode stores no author

- **WHEN** the setting is written with `security.mode=none`
- **THEN** the stored setting carries `updated_by` `null`

#### Scenario: The two settings are stored independently

- **WHEN** `app-routes` is written on an instance where `sessions-source` is stored
- **THEN** `GET /v1/settings/sessions-source` still returns its own body, `updated_at` and `updated_by`,
  unchanged

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
- a bound field is `array`-typed, or is a projection-only (computed) field;
- a bound field is sensitive, even when the writer holds `TABLE_SENSITIVE` and so sees it: the setting is
  read by every analyst, and a caller without that clearance has no such field to query.

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

#### Scenario: A projection-only field is refused

- **WHEN** `roles.title` names a projection-only (computed) field
- **THEN** the response is `422` with error code `validation_error` naming `roles.title` as projection-only

#### Scenario: A uuid field is not a session identifier

- **WHEN** `roles.session_id` names a `uuid` field
- **THEN** the response is `422` with error code `validation_error` naming `roles.session_id`, the field's
  type `uuid`, and the accepted type `string`

#### Scenario: A sensitive field is refused even for a cleared writer

- **WHEN** a writer holding `TABLE_SENSITIVE` on `base_table` binds `roles.title` to one of its sensitive
  columns
- **THEN** the response is `422` with error code `validation_error` naming `roles.title` as sensitive, and
  nothing is stored

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

`GET /v1/settings/{setting}` SHALL require `SETTINGS_READ` and `PUT /v1/settings/{setting}` SHALL require
`SETTINGS_WRITE`, for `sessions-source` and `app-routes` alike. Both are global-only privileges (see
`authentication`). A caller lacking the required privilege SHALL receive `403 forbidden`. A missing read
privilege on the setting is not a non-disclosure case, because the setting's existence is a fact of the
instance, not something the caller named.

A read SHALL NOT be filtered by the caller's table privileges: a `SETTINGS_READ` holder SHALL see the stored
`base_table` and role field names even when it may not list that table. This is a stated trade-off — the
setting is instance configuration authored by an administrator for every analyst — and reading the data the
setting points at still requires `TABLE_READ` on the query path. Likewise a `SETTINGS_READ` holder SHALL see
every allowed `(deployment, route)` pair, which names applications but carries no row data.

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

#### Scenario: An analyst bundle cannot write the route allowlist

- **WHEN** a caller holding `READ_ONLY_ANALYTIC` sends `PUT /v1/settings/app-routes`
- **THEN** the response is `403` with error code `forbidden`, and nothing is stored

### Requirement: App-routes setting body

The `app-routes` setting SHALL list the DIAL application routes an enrichment pipeline may call (see
[enrichment-registry](../enrichment-registry/spec.md), "An app-route pipeline is armed only for an allowed
route"). Its body SHALL be:

```
{
  "routes": [
    { "deployment": string, "route": string }
  ],
  "updated_at": string,   // response only, ISO-8601
  "updated_by": string | null   // response only
}
```

`routes` SHALL be served in the order written. An empty `routes` list SHALL be a valid, stored value
meaning "no route is allowed", the same effect as an absent setting. The setting is a list of permissions,
so its own state SHALL be the only thing that decides what is allowed.

#### Scenario: A stored allowlist reads back as written

- **WHEN** `PUT /v1/settings/app-routes` writes `{"routes":[{"deployment":"insights-app","route":"insights"},
  {"deployment":"triage-app","route":"v1/classify"}]}`
- **THEN** `GET /v1/settings/app-routes` returns both pairs in that order, with `updated_at` and `updated_by`

### Requirement: App-routes validation

`PUT /v1/settings/app-routes` SHALL reject the body with `422` and error code `validation_error`, storing
nothing and leaving any previously stored value unchanged, when:

- `routes` is missing or `null`;
- it lists more than 100 pairs;
- an entry's `deployment` is missing or blank, or longer than 256 characters;
- an entry's `deployment` carries whitespace, a control or format character, `\`, `?`, `#` or `%`. No DIAL
  deployment id carries one, so a pair naming such an id could never match a declaration's call, and a format
  character would make two entries look identical. A `/` SHALL be accepted, because DIAL application ids may
  contain one;
- an entry's `deployment` starts or ends with `/`, or has an empty, `.` or `..` segment between them. A
  declaration's `model` has no grammar beyond equalling a listed pair, so this is the only guard on the
  deployment half of `/v1/deployments/{model}/route/{route}`, which a normalizing client or proxy would
  otherwise resolve out of `/v1/deployments/`;
- an entry's `route` is missing, or violates the route grammar of "A transform may call a DIAL application
  route" (see [enrichment-registry](../enrichment-registry/spec.md)). One grammar serves both writes, so a
  pair the setting accepts is a pair a declaration can name;
- two entries are the same pair, compared exactly and case-sensitively.

The message SHALL name the offending entry by its index. ADAS SHALL NOT check a pair against DIAL Core: it
cannot see DIAL's deployments or an application's route configuration. Listing a route that does not exist
allows a call that DIAL then answers with its own error.

#### Scenario: A traversing route is refused in the setting too

- **WHEN** `PUT /v1/settings/app-routes` lists `{"deployment":"insights-app","route":"../admin"}`
- **THEN** the response is `422` with error code `validation_error` naming `routes[0].route`, and nothing is
  stored

#### Scenario: A deployment carrying a URL delimiter or whitespace is refused

- **WHEN** `PUT /v1/settings/app-routes` lists the deployment `"insights app"` or `"insights-app?x=1"`
- **THEN** the response is `422` with error code `validation_error` naming `routes[0].deployment`, and
  nothing is stored

#### Scenario: A deployment that could leave the deployments path is refused

- **WHEN** `PUT /v1/settings/app-routes` lists the deployment `"a/../../x"`, `"team//insights-app"`,
  `"/insights-app"` or `"team\\insights-app"`
- **THEN** the response is `422` with error code `validation_error` naming `routes[0].deployment`, and
  nothing is stored

#### Scenario: A deployment id may carry a slash

- **WHEN** `PUT /v1/settings/app-routes` lists `{"deployment":"team/insights-app","route":"insights"}`
- **THEN** the setting is stored with that deployment as written

#### Scenario: A duplicate pair is refused

- **WHEN** the body lists `(insights-app, insights)` twice
- **THEN** the response is `422` with error code `validation_error` naming `routes[1]`

#### Scenario: Pairs differing only by case are distinct

- **WHEN** the body lists `(insights-app, insights)` and `(insights-app, Insights)`
- **THEN** the setting is stored with both pairs

### Requirement: App-routes API

The system SHALL expose:

- `GET /v1/settings/app-routes`, which answers `200` with the stored setting, or `404 setting_not_found`
  when none is stored;
- `PUT /v1/settings/app-routes`, which creates or replaces the whole setting and answers `200` with the
  stored setting carrying the new `updated_at` and `updated_by`.

`PUT` SHALL replace the whole document; there SHALL be no partial update. A `PUT` whose body equals the
stored value SHALL still succeed and SHALL refresh `updated_at` and `updated_by`. Concurrent `PUT`s SHALL
resolve to last-writer-wins, as for `sessions-source`, with every read returning one complete written value,
never a mix of two. Every failure SHALL use the `ErrorView` contract. A successful write SHALL be recorded as a `Setting` activity whose resource id is
`app-routes` (see [audit-trail](../audit-trail/spec.md)).

#### Scenario: Replace overwrites the previous allowlist

- **WHEN** a setting listing `(insights-app, insights)` is replaced by one listing `(triage-app, classify)`
  only, and no enabled pipeline uses the first pair
- **THEN** a following `GET` returns only `(triage-app, classify)`

#### Scenario: An unknown setting name is not found

- **WHEN** a caller sends `GET /v1/settings/model-allowlist`
- **THEN** the response is `404` with error code `not_found`

### Requirement: An allowed route in use by an enabled pipeline cannot be removed

The system SHALL refuse with `409` and error code `app_route_in_use`, storing nothing, a
`PUT /v1/settings/app-routes` whose new body omits a pair that an **enabled** `dial_app_route` pipeline
declares. The message SHALL
name every such pipeline with its pair. A disabled pipeline SHALL NOT block the write. Its pair is checked
again when it is armed, so dropping the pair leaves it as a draft that cannot be enabled, rather than as a
running pipeline calling a route nobody allowed.

An enabled pipeline whose stored declaration this build cannot read SHALL be skipped by the check and logged,
not fail the write. The check reads every enabled enrich pipeline, so one drifted row would otherwise block
every allowlist edit. The runner's sync feed cannot serve a declaration ADAS cannot read, so a skipped
pipeline calls no route.

This guard, with the arm gate, is what keeps "every enabled route pipeline's pair is allowed" true at all
times. It is why neither the data plane nor the runner re-checks the setting. The setting write and an arming pipeline write SHALL be serialized against each
other, so the two checks cannot each pass against the other's old state. One transaction SHALL NOT enable a pipeline on a pair while
another drops that pair.

#### Scenario: Dropping a pair an enabled pipeline uses is refused

- **GIVEN** an enabled pipeline `insights-enrich` declaring `(insights-app, insights)`
- **WHEN** `PUT /v1/settings/app-routes` writes `{"routes":[]}`
- **THEN** the response is `409` with error code `app_route_in_use` naming `insights-enrich`, and the stored
  setting is unchanged

#### Scenario: An unreadable declaration does not block the write

- **GIVEN** an enabled enrich pipeline whose stored declaration this build cannot read
- **WHEN** `PUT /v1/settings/app-routes` is sent
- **THEN** the write is judged on the remaining pipelines, and the skipped one is logged

#### Scenario: Disabling the pipeline first lets the pair be dropped

- **GIVEN** that pipeline is disabled
- **WHEN** the same `PUT` is sent
- **THEN** the response is `200`, and enabling the pipeline afterwards is refused with `422`
  `pipeline_validation_failed`

#### Scenario: A concurrent arm and removal cannot both succeed

- **WHEN** a `PATCH` enabling a pipeline on `(insights-app, insights)` and a `PUT` dropping that pair run
  concurrently
- **THEN** exactly one of them answers `200`, and the other is refused by its own check: the `PUT` with `409`
  `app_route_in_use` when the arm won, the `PATCH` with `422` `pipeline_validation_failed` when the removal
  won. Either the pipeline is enabled and the setting still lists the pair, or the setting no longer lists it
  and the pipeline is disabled
