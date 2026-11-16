## ADDED Requirements

### Requirement: Instance settings do not travel

A bundle SHALL NOT carry instance settings (see [instance-settings](../instance-settings/spec.md)): neither
the `app-routes` allowlist nor `sessions-source`. They configure the instance a bundle is imported into, not
a table or a pipeline. The import validation pass SHALL arm each imported pipeline against the target's own
settings, so a `dial_app_route` pipeline whose `(model, route)` pair the target's `app-routes` setting does
not list SHALL be reported `armable: false`, with an `arm_problems` entry naming `app-routes`. The import
SHALL still create that pipeline, disabled like every other. Allowing the pair on the target makes it
armable without a new import.

#### Scenario: An imported route pipeline whose pair is not allowed is not armable

- **GIVEN** a bundle holding a `dial_app_route` pipeline declaring `(insights-app, insights)`, and a target
  whose `app-routes` setting does not list that pair
- **WHEN** `/import/preview` runs the bundle
- **THEN** the pipeline entry reports `import_action` `CREATE`, `armable: false` and an `arm_problems` entry
  naming `app-routes`

#### Scenario: Allowing the pair on the target makes it armable

- **GIVEN** the same bundle, after `PUT /v1/settings/app-routes` lists `(insights-app, insights)` on the target
- **WHEN** `/import/preview` runs the bundle again
- **THEN** the pipeline entry reports `armable: true`
