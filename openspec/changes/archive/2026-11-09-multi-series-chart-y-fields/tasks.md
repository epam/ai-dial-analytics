## 1. Chart model

- [x] 1.1 Change `SavedQueryChart.fieldY` (`y_field`) to `List<String>` with `ACCEPT_SINGLE_VALUE_AS_ARRAY`, and update its Javadoc
- [x] 1.2 Reword the "stored verbatim" OpenAPI `@Schema` descriptions of `chart` on `SavedQueryRequestDto` and `InlineQueryRequestDto`: stored as sent apart from the `y_field` normalization (a string becomes a one-element array)
- [x] 1.3 Fix every compile site that constructs a `SavedQueryChart` (tests included)

## 2. Tests

- [x] 2.1 Saved-query integration test: multi-series round-trip keeps order; legacy string request reads as a one-element array; `[]` is returned as `[]`; scalar and `null` elements are kept as their string form and `null`; `{}`, `[{}]` and `[[]]` are `400 bad_request` (a non-empty nested array such as `[["a"]]` is not tested; see design)
- [x] 2.2 Saved-query integration test: write a string `y_field` straight into both `saved_query.chart` and the matching `saved_query_aud.chart` row (hand-editing `saved_query` writes no audit row), then check that both the saved-query read and the revision endpoint return it as a one-element array
- [x] 2.3 Dashboard integration test: an inline-query chart with a `y_field` array round-trips; an inline chart with `y_field` `[{}]` is `400 bad_request`; and a `dashboard.widgets` row whose inline chart has a string `y_field` (written straight to the table) reads back as a one-element array

## 3. Docs

- [x] 3.1 Add to `docs/release-0.24.0-upgrade.md` both a section and a row in its "Change | Effect | Action" summary table: `chart.y_field` is now an array (breaking response shape), a string is still accepted, and a rollback first needs the stored arrays rewritten to their first element in `saved_query.chart`, `dashboard.widgets` and `saved_query_aud.chart`

## 4. Verification

- [x] 4.1 `./gradlew clean build`
