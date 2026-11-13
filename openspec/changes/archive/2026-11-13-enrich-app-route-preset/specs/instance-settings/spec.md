## MODIFIED Requirements

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

## ADDED Requirements

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
- an entry's `deployment` carries whitespace, a control character, `?`, `#` or `%`. No DIAL deployment id
  carries one, so a pair naming such an id could never match a declaration's call. A `/` SHALL be
  accepted, because DIAL application ids may contain one;
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

This guard, with the arm gate, is what keeps "every enabled route pipeline's pair is allowed" true at all
times. It is why neither the data plane nor the runner re-checks the setting. The setting write and an arming pipeline write SHALL be serialized against each
other, so the two checks cannot each pass against the other's old state. One transaction SHALL NOT enable a pipeline on a pair while
another drops that pair.

#### Scenario: Dropping a pair an enabled pipeline uses is refused

- **GIVEN** an enabled pipeline `insights-enrich` declaring `(insights-app, insights)`
- **WHEN** `PUT /v1/settings/app-routes` writes `{"routes":[]}`
- **THEN** the response is `409` with error code `app_route_in_use` naming `insights-enrich`, and the stored
  setting is unchanged

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
