## 1. Transform shape: preset and route

- [x] 1.1 Add `DIAL_APP_ROUTE` to `TransformPreset` and check that it serializes as `dial_app_route`.
- [x] 1.2 Add `route` to:
  - `EnrichSpec.Transform`;
  - the create and patch transform requests in `PipelineRequests`;
  - the transform view in `PipelineDto`;
  - `PipelineMapper`, which must keep it absent when undeclared under both projections.
- [x] 1.3 Add a single route-grammar class (`AppRoutePath`): 1–256 characters; `[A-Za-z0-9._~-]+`
  segments separated by single `/`; no `.`/`..`, `?`, `#` or `%`. Unit-test every scenario of "A transform
  may call a DIAL application route".
- [x] 1.4 In `EnrichSpecValidator`'s shape checks, which run when the pipeline is armed, require `route` and its
  grammar under `dial_app_route`, and refuse it under `chat_completion`. Add `route` to the llm-only
  members that a `sql` transform refuses. Each case is a 422 `pipeline_validation_failed` naming
  `transform.route`.
- [x] 1.5 Add a per-output `source` to the authored output (`OutputSpecBody`, `TransformOutput`) and to the
  resolved compiled `outputs` entry (`PipelineOutputDto`). The gate refuses it under `chat_completion`, refuses a
  route output's `jsonata`, and refuses a `source` that does not parse or is the identity expression. Each case is
  a 422 naming the output.
- [x] 1.5a Compose a route transform's schema from column nullability: a nullable column's output is not
  required and accepts `null`.
- [x] 1.6 Mapper and validator tests:
  - an app-route declaration round-trips;
  - a missing route, or a route on chat, is refused;
  - `preset: "embeddings"` is refused with 400 `bad_request`;
  - a `chat_completion` read carries no `route` member;
  - an output `source` round-trips and reaches the compiled `outputs`, is refused on chat, and is refused when
    unparseable or the identity;
  - a route output's `jsonata` is refused;
  - the route schema leaves a nullable column's output optional.

## 2. Raw-JSON placeholders

- [x] 2.1 Extend `TemplatePlaceholderRule` to accept an optional `:json` suffix. Validate the name before
  it at both grains, count `{{members:json}}` as satisfying the members rule, and refuse any other suffix
  with 422 naming the placeholder.
- [x] 2.2 Tests cover the three scenarios of "A template placeholder may render its value as raw JSON",
  plus a row-grain `{{v:json}}` that binds nothing.
- [x] 2.3 Refuse every `{{...}}` token outside the placeholder grammar (`{{a:b:json}}`, `{{members :json}}`,
  `{{a-b}}`), which the runner would fail as unbound; whitespace around a well-formed token stays accepted.

## 3. `app-routes` instance setting

- [x] 3.0 Add a Flyway migration widening the `instance_setting.name` CHECK constraint to admit `app-routes`.
- [x] 3.1 Add `APP_ROUTES("app-routes")` to `InstanceSettingName`, plus a setting record
  (`AppRoutesSetting`) and its DTO. `routes` is an ordered list of `{deployment, route}`.
- [x] 3.2 Add `AppRoutesValidator`, which returns 422 `validation_error` naming the entry by index. It
  checks that `routes` is present and holds at most 100 entries, that each `deployment` is non-blank and at
  most 256 characters with no whitespace, control character, `?`, `#` or `%` (a `/` is allowed), the route grammar (it reuses `AppRoutePath`), and that no pair is an exact duplicate.
- [x] 3.3 Add `AppRoutesSettingService` with get and replace, an audited `Setting` activity, and the
  in-use guard: 409 `app_route_in_use` naming each enabled `dial_app_route` pipeline whose pair the new body
  drops.
- [x] 3.4 Add `GET`/`PUT /v1/settings/app-routes` to `SettingsController`, under `SETTINGS_READ` and
  `SETTINGS_WRITE`.
- [x] 3.5 Add an `AppRouteInUseException` and a 409 `app_route_in_use` handler in
  `DefaultExceptionHandler`.
- [x] 3.6 Integration tests:
  - absent → 404 `setting_not_found`;
  - round-trip order;
  - each validation refusal;
  - the 409 guard, and dropping a pair after the pipeline is disabled;
  - 403 for an analyst on `PUT`;
  - `sessions-source` unaffected by an `app-routes` write.

## 4. Arm gate and serialization

- [x] 4.1 Make the arm gate in `EnrichSpecValidator` refuse a `dial_app_route` transform whose exact,
  case-sensitive `(model, route)` pair is not in the stored `app-routes` setting, absent meaning none. The
  422 message names the deployment, the route and `app-routes`. The gate runs only at gated requests: an
  armed write, and the preview of a disabled pipeline.
- [x] 4.2 Take a transaction-scoped advisory lock keyed on `app-routes`:
  - always in the setting `PUT`;
  - in a pipeline write only when the result is an armed `dial_app_route` pipeline.

  Both checks run inside the lock.
- [x] 4.3 Integration tests:
  - arm refused when the pair is not listed, and when no setting exists;
  - a disabled route pipeline is stored, then armed once the pair is allowed;
  - a case mismatch is refused;
  - a concurrent arm and removal never both succeed: a separate transaction holds the lock until both
    requests wait on it, then exactly one answers 200 and the other 409 or 422.

## 5. Feed and contract

- [x] 5.1 Raise `RunnerContract.VERSION_VALUE` to `5` and update the golden list in
  `RunnerContractShapeTest` for `transform.route`.
- [x] 5.2 Feed test: an enabled app-route pipeline's compiled entry carries `preset`, `model` and `route` inside
  `transform` and `source` on its `outputs`, and the response carries `Adas-Contract-Version: 5`.
- [x] 5.3 Catalog export/import test: `transform.route` travels in the source projection, and an imported
  route pipeline lands disabled.
- [x] 5.4 Catalog import preview: an imported route pipeline whose pair the target does not allow reports
  `armable: false` naming `app-routes`, and `armable: true` once the pair is allowed.

## 6. Docs and specs

- [x] 6.1 Add an "Application routes" section to `docs/release-0.24-upgrade.md` covering:
  - the contract bump, so ADAS and the Enrichment Runner deploy together;
  - the `app-routes` setting, absent means no route allowed;
  - the disable → patch → enable sequence for moving a pipeline off a chat shim.
- [x] 6.1a Add to `docs/catalog-promotion.md` §"What does not travel" that the `app-routes` and
  `sessions-source` settings stay behind, and that each route pair is allowed on the target before arming.
- [x] 6.2 Confirm that no configuration property is added, so nothing changes in `docs/configuration.md`.
- [x] 6.3 Check every scenario above against a test, and confirm the merged
  `enrichment-registry`, `instance-settings`, `enrichment-runtime` and `catalog-export-import` specs match the shipped behavior.
- [x] 6.4 Run the public-repo hygiene sweep (`.claude/skills/pr-ready/hygiene-sweep.pl`) over the change.

## 7. Verify

- [x] 7.1 `./gradlew clean build` passes.
