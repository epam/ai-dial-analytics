## 1. Chart model

- [x] 1.1 Add the `SavedQueryChartLegend` record (`Boolean visible`, `String position`) next to
  `SavedQueryChart`. Its Javadoc names the four known `position` values; no `@Schema` (no swagger import under
  `service/`)
- [x] 1.2 Add a nullable `legend` component to `SavedQueryChart` and extend its Javadoc: `position` is stored
  as sent, like `type`; a chart stored without `legend` reads back without it. Pass `null` for `legend` at
  the one other constructor call, `SavedQueryBodyValidatorTest`

## 2. Tests

- [x] 2.1 `SavedQueryIntegrationTest`: a chart with `legend` `{visible: true, position: "below"}` round-trips
  on create, get, list entry and the revision snapshot of that save
- [x] 2.2 `SavedQueryIntegrationTest`: `{visible: false, position: "left"}` keeps `visible` `false`; an
  unknown `position` is returned as sent, with no `visible` key; a `null` or absent `legend` is omitted; `{}`
  and all-null members return `{}`
- [x] 2.3 `SavedQueryIntegrationTest`: save a chart with `legend`, remove the member in `saved_query` and
  `saved_query_aud` through JDBC (`chart - 'legend'`), then read it through get and the revision snapshot: no
  `legend` key
- [x] 2.4 `SavedQueryIntegrationTest`: each of these is rejected with `400 bad_request`: a `legend` of
  `"below"`, `true`, `5` or `[]`; a `visible` or `position` that is an object or an array; and a `legend`
  carrying an unknown member (`{"align": "start"}`), whose message names `align`. If any case is accepted,
  stop and correct the spec rather than the test
- [x] 2.5 `DashboardIntegrationTest`: an `inline_query` chart with `legend` round-trips on create and read;
  a widget stored without `legend` reads back without it
- [x] 2.6 `SavedQueryIntegrationTest`: a replace that leaves `legend` out returns and stores a chart without
  it, and the earlier revision snapshot keeps its `legend`

## 3. Docs and spec

- [x] 3.1 Regenerate `docs/openapi.json` with `./gradlew updateOpenApi` and check that the diff adds only the
  `SavedQueryChartLegend` schema, the `legend` `$ref` on `SavedQueryChart`, and the `legend.position` clause in
  the `chart` description of the saved-query and inline-query request DTOs
- [x] 3.2 Confirm the delta spec matches the shipped behaviour
- [x] 3.3 `docs/release-0.24-upgrade.md` §19: deploy ADAS before the client sends `legend`; on a rollback the
  older build refuses saves carrying it and drops a stored `legend` on the chart's next save

## 4. Verification

- [x] 4.1 `./gradlew clean build` passes
