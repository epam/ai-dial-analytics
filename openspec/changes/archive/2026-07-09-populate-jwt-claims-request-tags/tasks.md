## 1. Add the object extraction kind

- [x] 1.1 Add `OBJECT("object")` to `ColumnKind`.
- [x] 1.2 In `MappingEngine.coerce`, add the `OBJECT` case returning the source `JsonNode` unchanged.
- [x] 1.3 In `MappingEngine.zero`, return the empty JSON object for `OBJECT` (serializes to `{}`).
- [x] 1.4 Confirm `isBlank`/`applyFallback` treat a resolved object node as present and an absent
  source as `{}` for the non-nullable columns; adjust only if a populated object is wrongly dropped.

## 2. Teach the schema validator about JSON

- [x] 2.1 Add `TypeFamily.JSON`; map the `JSON` type spelling in `classify` and return it from
  `expectedFamily(OBJECT)`.

## 3. Wire up the two columns in the default mapping

- [x] 3.1 In `src/main/resources/mapping/dial-usage-log.yaml`, add `jwt_claims` (source `claims`,
  kind `object`) and `request_tags` (source `headers`, kind `object`), both non-nullable, in the
  column order matching the DDL.
- [x] 3.2 Update the mapping file header comment: `jwt_claims`/`request_tags` are now populated; only
  `operation_duration_ms` remains blocked.

## 4. Tests

- [x] 4.1 `MappingEngineTest`: a record with `claims`/`headers` maps to the object columns verbatim;
  an absent `claims`/`headers` yields `{}`.
- [x] 4.2 `SchemaValidatorTest`: an `object`-kind column reconciles with a `JSON` table column, and a
  mismatch still fails.
- [x] 4.3 A `UsageLogWriter` (or serialization) test asserting a `JsonNode` object value serializes as
  a nested JSON object on the `JSONEachRow` line.

## 5. Spec and build

- [x] 5.1 Confirm the `data-sync` delta in this change reflects shipped behavior (blocked set reduced
  to `operation_duration_ms`; object kind; captured claims/headers).
- [x] 5.2 Run `./gradlew clean build` and confirm compile + checkstyle + tests pass. (Compile, checkstyle,
  and all unit tests pass; the Testcontainers-backed integration tests fail in this sandbox because
  Docker is unavailable — confirmed pre-existing/unrelated by reproducing the same failures on a stash
  of this change's diff.)
