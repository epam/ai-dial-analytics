# Tasks — standardize the row-write timestamp format on ISO-8601

## 1. Timestamp validator

- [x] 1.1 Add `service/table/WriteTimestampNormalizer` (Spring `@Component`) owning the accepted format
      from the `table-management` delta spec: one static `DateTimeFormatter` built with
      `DateTimeFormatterBuilder` — ISO local date, literal `T`, hour and minute, optional seconds,
      optional fractional seconds at any precision, then a **required** offset via `appendOffsetId()`
      (covering `Z` and `±HH:MM`). The offset must be required by the grammar, not checked afterwards,
      so a zone-less value cannot silently resolve.
- [x] 1.1a Pin `ResolverStyle.STRICT` and `IsoChronology.INSTANCE` on the assembled formatter (and on the
      diagnosis-only zone-less one). `DateTimeFormatterBuilder.append(DateTimeFormatter)` copies only the
      appended formatter's printer/parser, so `ISO_LOCAL_DATE`'s strict resolver is lost and the builder
      default `SMART` clamps an out-of-range day — `2026-02-29T10:00:00Z` in a non-leap year would store
      `2026-02-28 10:00:00.000` with a `200`, the exact silent-wrong-instant failure this change removes.
- [x] 1.2 Resolve a parsed value to an `Instant` from its offset, and truncate toward the past to
      millisecond precision (the column's `DateTime64(3)`).
- [x] 1.3 Render the validated instant back as `yyyy-MM-dd HH:mm:ss.SSS` at UTC — the store's form — so
      the API contract does not depend on ClickHouse's `date_time_input_format`.
- [x] 1.4 Throw `TableValidationException` (already mapped to HTTP 422 `validation_error`) on any value
      not of the accepted form, with a message naming the 1-based row index, the column and the
      length-capped value, and discriminating six cases with their corrections: **missing zone**
      (zone-less value), **wrong separator** (space instead of `T`), **date-only**, **a day that does not
      exist on the calendar** (named as such by re-parsing the date with a clamping resolver, since it is
      the one rejected input that otherwise looks well-formed), **non-string** (including a numeric epoch,
      whose units are ambiguous), and a generic **unparseable** fallback carrying an example. Add no new
      exception type and no new handler.
- [x] 1.5 Leave a `null` value untouched (a nullable `timestamp` column stores null).

## 2. Wire it into the shared write path

- [x] 2.1 In `service/table/TableDataService`, extend the single
      `columns.findByTableNameOrderByOrdinal` pass that builds the physical-column set so it also yields
      the set of `TIMESTAMP`-typed column names — one catalog read per request, not per row.
- [x] 2.2 For an `ENRICHMENT` target, resolve the hidden grain key's declared type from the **source**
      table's column mapping (same column name on both, per `EnrichmentGrainEntity`) and include the
      grain key in the timestamp set when it is `TIMESTAMP`. One extra repository call, enrichment
      targets only, once per request.
- [x] 2.3 Run timestamp validation **after** the existing column-set check, so a row naming an unknown or
      `_`-prefixed system column still fails for that reason (per design D5 — this keeps
      `TableWriteFacetIntegrationTest`'s `_updated_at` case rejecting for the right reason).
- [x] 2.4 In the existing per-row loop, validate only values whose key is in the timestamp set;
      short-circuit the whole step when that set is empty.
- [x] 2.5 Never mutate the caller's list or maps: copy a row map only when one of its values actually
      changes (mirroring `RuleWriteService.dropAbsentProvenance`), and pass the resulting list to
      `CatalogRowWriter.insert`.
- [x] 2.6 Confirm `POST /v1/rules/{id}/rows` inherits the behavior with no second implementation —
      `RuleWriteService.write` already ends in `TableDataService.write`; make no change to
      `RuleDataController`, `RuleWriteService`, or `CatalogRowWriter`.
- [x] 2.7 Update the class Javadoc on `TableDataService` (and `CatalogRowWriter` where it describes what
      it receives) to state that timestamp values are validated and rendered to the store form before
      insert.

## 3. Tests

- [x] 3.1 Unit-test the accepted format: `Z`, explicit `±HH:MM` offset (asserting conversion to the same
      instant in UTC), omitted seconds (`…T10:00Z`), and fractional seconds at 0/3/6/9 digits including
      sub-millisecond truncation toward the past.
- [x] 3.2 Unit-test each discriminated rejection, asserting the message identifies the specific defect:
      zone-less `2026-08-11T10:00:00`, space-separated `2026-08-11 10:00:00.000`, date-only
      `2026-08-11`, the number `1786514400000`, a boolean, an empty string, and free text.
- [x] 3.2a Unit- and integration-test the calendar-date rule: `2026-02-29` (non-leap), `2026-02-30`,
      `2026-04-31` and `2100-02-29` are rejected with a message naming the date and nothing is inserted,
      while `2024-02-29` — a day that does exist — is accepted and stores that instant.
- [x] 3.3 Unit-test pass-through: `null` for a nullable timestamp column, and that `date`, `decimal`
      (string spelling preserved), `string` (a timestamp-shaped value stored verbatim), `uuid`, `array`
      and `object` values are untouched.
- [x] 3.4 Unit-test that the rejection message names the correct 1-based row index for a bad value in a
      multi-row batch.
- [x] 3.5 Integration test (`@SpringBootTest` + `@AutoConfigureMockMvc` under `.../integration/`,
      extending the existing ClickHouse/Postgres base, each test owning its data) for
      `POST /v1/tables/{name}/rows`: a row written with an ISO-8601 `Z` timestamp is accepted and reads
      back as the same instant, and the value the read path returns is writable back verbatim (the
      round-trip the change exists to fix).
- [x] 3.6 Integration test: an explicit-offset value stores the same instant as its `Z` equivalent.
- [x] 3.7 Integration test: the space-separated form now returns HTTP 422 with
      `error: "validation_error"` and a message naming the separator; a batch containing one invalid
      timestamp inserts **no** rows.
- [x] 3.8 Integration test for `POST /v1/rules/{id}/rows`: an ISO-8601 value on a `timestamp` column is
      accepted and a space-separated one returns the same 422 `validation_error` — proving the two write
      surfaces agree.
- [x] 3.9 Audit existing tests that write rows through either endpoint and update any that send a
      now-rejected timestamp for a `timestamp`-typed column. `EnrichmentWriteBackIntegrationTest`
      declares `enriched_at` as `string` and needs no change; `TableWriteFacetIntegrationTest`'s
      `_updated_at` case must keep failing on the unknown column (task 2.3). Verify rather than assume.

## 4. Documentation

- [x] 4.1 `local_env/python-examples/tables/README.md` — replace the "Timestamps use the space-separated
      format, not ISO-8601" item under "Three things that bite when writing rows" with the ISO-8601
      requirement, and invert the accepted/rejected example pair beneath it. Keep the item count and
      heading coherent with what remains.
- [x] 4.2 `local_env/python-examples/tables/write_custom_table.py` — change the `ROW` literal's
      `recorded_at` to `"2026-08-11T10:00:00Z"` and correct the comment above `ROW` that explains the
      format; leave the `score` note as is.
- [x] 4.3 No `docs/configuration.md` change — this change adds no configuration property. Confirm
      explicitly rather than silently skipping.

## 5. Verification

- [x] 5.1 Re-read both delta specs against the shipped behavior and correct either side if they
      disagree.
- [x] 5.2 `openspec validate normalize-row-write-timestamps --strict` passes.
- [x] 5.3 `./gradlew clean build` passes (compile + checkstyle + tests).
