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
- [x] 2.3 `SavedQueryIntegrationTest`: following `chartStoredWithStringSeriesIsReturnedAsOneSeries`, write a
  chart without `legend` straight to `saved_query` and `saved_query_aud` through JDBC, then read it through
  get and the revision snapshot: no `legend` key
- [x] 2.4 `SavedQueryIntegrationTest`: each of these is rejected with `400 bad_request`: a `legend` of
  `"below"`, `true`, `5` or `[]`; a `visible` or `position` that is an object or an array; and a `legend`
  carrying an unknown member (`{"align": "start"}`), whose message names `align`. If any case is accepted,
  stop and correct the spec rather than the test
- [x] 2.5 `DashboardIntegrationTest`: an `inline_query` chart with `legend` round-trips on create and read;
  a widget stored without `legend` reads back without it

## 3. Docs and spec

- [x] 3.1 Regenerate `docs/openapi.json` with `./gradlew updateOpenApi` and check that the diff adds only the
  `SavedQueryChartLegend` schema and the `legend` `$ref` on `SavedQueryChart`
- [x] 3.2 Confirm the delta spec matches the shipped behaviour. No `docs/*.md` change: the member is additive
  and needs no operator action, so the release upgrade notes gain no entry

## 4. Verification

- [ ] 4.1 `./gradlew clean build` passes
