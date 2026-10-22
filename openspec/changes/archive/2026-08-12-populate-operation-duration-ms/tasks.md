## 1. Map the column

- [x] 1.1 In `src/main/resources/mapping/dial-usage-log.yaml`, add a mapped entry for
  `operation_duration_ms` (`source: operation_duration_ms`, `kind: long`, no `nullable`, no `default`),
  positioned between `deployment_price` and `response_status` so the file keeps `V1`'s column order.
- [x] 1.2 Rewrite the header comment's blocked-column sentence: `_ingested_at` is now the only unmapped
  table column, and the new entry needs the one thing a reader cannot get from the DDL — that the source is
  the producer's own measurement (not recomputed here), that the producer clamps it at zero, and that a
  pre-`8b1634e2` core omits the field so the non-nullable fallback stores `0`.

## 2. Specs

- [x] 2.1 Add the `data-sync` delta under `specs/`: an ADDED behavioral requirement that the reported
  duration is persisted (with the reported-zero and absent-field scenarios), and MODIFIED versions of
  `Target table provisioning and schema` and `Fail-fast startup validation against the table schema` that
  drop the blocked-column promise. The main `openspec/specs/data-sync/spec.md` is left untouched — the
  delta is the pending statement until `/opsx:archive` merges it.
- [x] 2.2 Confirm `openspec validate populate-operation-duration-ms --strict` passes.

## 3. Tests

- [x] 3.1 `MappingFileLoaderTest.loadsShippedDefaultMapping` — bump the expected column count to 37 and
  update the trailing comment.
- [x] 3.2 `MappingEngineTest` — add `"operation_duration_ms":1234` to the `SAMPLE` fixture and assert the
  row carries `1234L`; add `operation_duration_ms` to `writesOnlyDeclaredColumnsInOrder` in its DDL
  position.
- [x] 3.3 `MappingEngineTest` — assert the absent case (`{}` record) stores `0L` alongside the other
  non-nullable zero-fallback columns, and cover a reported `0`. The three cases live in one test
  (`carriesTheProducersReportedOperationDuration`) rather than three: for a non-nullable column the
  reported-`0` and absent cases store the same value, so separate tests would assert the same thing while
  reading as if they discriminated. The shared test states the conflation explicitly.
- [x] 3.4 `SchemaValidatorTest` — drop the "blocked, intentionally unmapped" comment from
  `liveSchema()`, and re-point `unmappedBlockedColumnsArePermitted` at `_ingested_at` (renamed
  `unmappedTableDefaultColumnIsPermitted`); the class javadoc's blocked-column phrase follows.

## 4. Docs

- [x] 4.1 `docs/configuration.md` — in the default-mapping bullet, move `operation_duration_ms` into the
  direct columns and drop the blocked-column clause. While there, fix the same sentence's stale claim that
  `jwt_claims`/`request_tags` are unmapped (they have been mapped since
  `2026-07-09-populate-jwt-claims-request-tags`), and narrow the startup-validation bullet's
  permissibly-unmapped list to `_ingested_at`.
- [x] 4.2 Confirm `docs/column-tags.md` needs no change (the `performance` tag already covers the column)
  and that `README.md` makes no blocked-column claim. Both confirmed: no edit needed.

## 5. Verify

- [x] 5.1 Run `./gradlew clean build` and confirm compile,
  checkstyle, and tests pass. Green.
- [x] 5.2 Verify the mapping against the producer's actual JSON shape. The field is emitted top-level and
  unquoted (`append(entry, ",\"operation_duration_ms\":", false)` then the bare digits; the core's own test
  asserts `isNumber()` and `asLong() == 1234`), which `kind: long` reads directly — and `node.asLong` would
  coerce a quoted numeric too, as `response_status` already relies on. The three stored outcomes (reported
  N, reported `0`, field absent) are pinned by `carriesTheProducersReportedOperationDuration` over the
  shipped mapping.
- [x] 5.3 Confirm the mapping reconciles with the real column rather than only with the hand-maintained
  `liveSchema()` copy. It does: the integration contexts boot the full app with Flyway enabled and
  `sync.validate-schema` at its `true` default, and the run logged
  `Data-sync mapping reconciled with table 'dial_usage_log': 37 column(s) validated` — so the new entry was
  checked against the live `UInt32` column, and an incompatible kind or nullability would have failed those
  contexts at startup.
- [x] 5.4 Search the repo for remaining "blocked column" references and confirm none outside the main spec
  still claims `operation_duration_ms` is unpopulated. The four remaining hits are all in
  `openspec/specs/data-sync/spec.md`, which the delta rewrites at archive time.
- [ ] 5.5 Ingest into a running deployment and read the column back. Deferred: it writes rows to a shared
  ClickHouse and needs a DIAL Core carrying `8b1634e2` to produce a non-zero value. Until then an
  existing `conversations_rollup` keeps reporting `duration_ms` as `0` for
  conversations built from already-ingested rows.
