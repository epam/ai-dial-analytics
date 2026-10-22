## 1. Correct the catalog

- [x] 1.1 Add a Flyway migration setting `write_mode = 'UPSERT_BY_KEY'` on `dial_usage_log` and
      `rate_analytics`, matching the `ReplacingMergeTree(_ingested_at)` engine their ClickHouse
      migrations create
- [x] 1.2 Record in the migration why the two disagreed — `write_mode` arrived in V19 with an `APPEND`
      default, these tables' DDL is hand-written rather than rendered, so the facet was never an input to
      anything — and that this is metadata only, with no `ALTER` and no behavioural change

## 2. Verify nothing moved

- [x] 2.1 Confirm the existing deduplication coverage still passes unchanged: both seeded sources still
      return one row per ordering key, and the `count(*)` total agrees. The facet is not on the read path,
      so a failure here would mean something reads it that should not
- [x] 2.2 Confirm `GET /v1/tables/dial_usage_log` and `GET /v1/tables/rate_analytics` now report
      `write: "upsert_by_key"`, and that the facet is still rejected as immutable through
      `PUT /v1/tables/{name}` and `PATCH /v1/tables/{name}/schema`
- [x] 2.3 Confirm the aggregate pipeline's target rule is unaffected: it rejects an `append` target, and
      neither seeded source is a pipeline target

## 3. Close the gap that let it happen

- [x] 3.1 State in `specs/table-management/spec.md` that a seeded source's `write` facet SHALL agree with
      its physical engine, with the two tables' values named, so the next hand-written seeded table is
      reviewed against its DDL rather than against a default
- [x] 3.2 Tie the deduplication guarantee to the write discipline in `specs/structured-query/spec.md`, so
      the facet and the engine are one statement rather than two that nothing reconciles

## 4. Spec and close-out

- [x] 4.1 Confirm the shipped behaviour matches `specs/table-management/spec.md` and
      `specs/structured-query/spec.md` in this change — in particular that no deduplication behaviour
      changed, which is the whole claim
- [x] 4.2 Run `./gradlew clean build` (Docker required for integration tests) and confirm it is green
