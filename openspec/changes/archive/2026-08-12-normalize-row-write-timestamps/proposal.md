# Standardize the row-write timestamp format on ISO-8601

## Why

`POST /v1/tables/{name}/rows` rejects the timestamp format ADAS itself emits.
`"2026-08-11 10:00:00.000"` inserts; `"2026-08-11T10:00:00Z"` fails — and the read path returns exactly
the second form (`local_env/python-examples/tables/README.md`, "Read the row back"). A client that reads
a row, edits a field and writes it back is rejected by the service that produced the value.

Nothing in the repository chose that grammar. `TableDataService.write` validates only a row's **keys**
against the catalog and never inspects a value; `CatalogRowWriter.insert` serializes the caller's map
verbatim and hands the bytes to ClickHouse as `JSONEachRow`. No Java, no `application.yml` and no
migration sets ClickHouse's `date_time_input_format`, so the accepted grammar is whatever that
setting's default happens to accept — a ClickHouse implementation detail that became the public API
contract by omission. The space-separated form is not a format anyone designed; it is the
`JSONEachRow` parser showing through the API.

The cost is already visible inside this repository. `EnrichmentWriteBackIntegrationTest` declares the
enrichment's `enriched_at` column as **`type: "string"`** and writes `"2026-07-21T13:15:16.696466Z"`
into it. That is the defect's workaround in our own tests: to store a timestamp a caller emits in the
standard form, you mistype the column so the timestamp parser never runs — losing the column's type,
its ordering semantics and its TTL eligibility.

The failure is also undiagnosable from the response. A rejected batch surfaces as
`ClickHouseQueryException` → generic `400 bad_request` ("The query could not be executed against
ClickHouse; check field/function/type compatibility"), naming neither the column, nor the value, nor
the expected form.

Flipping `date_time_input_format` to `best_effort` is not the fix: it would trade "rejects valid input"
for "silently misparses ambiguous input" (`01/02/2026` resolving by which `best_effort` variant is
active, and zone-less local-time strings taken at face value). Strictness is right; it is currently
aimed at the wrong grammar and enforced by the wrong component. ADAS is the only participant that can
enforce it well — it knows each column's declared type from the catalog, whereas by the time the JSON
reaches ClickHouse a timestamp is one string among strings.

The endpoint has no production consumers yet, so the format can be **standardized now** rather than
grown as a permissive superset that must then be supported forever.

## What Changes

- **ISO-8601 with an explicit zone becomes the one accepted timestamp format.** For a
  `timestamp`-typed column ADAS accepts exactly:
  - `2026-08-11T10:00:00Z` — UTC
  - `2026-08-11T13:00:00+03:00` — explicit offset, resolved to the instant it denotes

  The seconds field is optional (`2026-08-11T10:00Z`) and fractional seconds are optional at any
  precision up to nanoseconds, truncated toward the past to the column's stored millisecond
  precision. This is what
  `Instant.toString()`, `Date.toJSON()`, `datetime.isoformat()` on an aware datetime, and
  `time.RFC3339` all produce, and what the read path already returns — so the round-trip works by
  construction.

- **Every accepted value carries an explicit zone.** There is no assumed-zone case anywhere in the
  contract, so no input can be silently stored as the wrong instant.

- **BREAKING — the space-separated form is no longer accepted.** `"2026-08-11 10:00:00.000"` now
  returns `422`. It is a ClickHouse detail, not a format, and keeping it as an alias would make it
  permanent API surface. See the DIAL Admin coordination note below — this is the one change that
  requires the two repositories to land in the same release window.

- **BREAKING — a zone-less value is rejected, not assumed to be UTC.** `"2026-08-11T10:00:00"` returns
  `422`. Assuming UTC would store a Kyiv-local wall clock three hours off with no error — the exact
  `best_effort` failure mode this change rejects. A rejection naming the missing zone is a one-line
  fix for the caller; a silently wrong instant is not discoverable at all.

- **BREAKING — a date-only value and a numeric epoch are rejected.** `"2026-08-11"` and
  `1786514400000` both return `422`. ClickHouse's default parser accepts them today; neither is a
  timestamp a caller should be sending to a `DateTime64(3)` column, and a bare number's units are
  ambiguous between seconds and milliseconds.

- **A rejected timestamp becomes a `422 validation_error` that names the mistake specifically**, not
  just "invalid". The message names the row index, the column and the value, and then discriminates
  the five realistic errors — missing zone, space separator, date-only, non-string, and unparseable —
  each with the concrete correction. This carries most of the user-visible improvement: the caller
  fixes the request from the response alone.

- **Non-`timestamp` types are untouched.** `date` needs nothing (`YYYY-MM-DD` is both its ISO-8601 and
  its stored form, and ClickHouse `Date` is a zoneless day number). `decimal` is deliberately left
  alone — it travels as a string so the digits survive a JSON float, and coercing it would undo that.
  A `string`-typed column holding a timestamp-shaped value is not touched either, so the existing
  `enriched_at`-as-string test keeps passing unchanged.

- **Both row-write endpoints change together.** `POST /v1/rules/{id}/rows` reaches ClickHouse through
  the same writer: `RuleWriteService.write` ends in `TableDataService.write`, which is the only caller
  of `CatalogRowWriter.insert` in the codebase. Normalizing inside that method covers both surfaces
  with one implementation, so the two cannot disagree about timestamps.

- Documentation that teaches the old restriction is corrected:
  `local_env/python-examples/tables/README.md` ("Timestamps use the space-separated format, not
  ISO-8601", and the accepted/rejected example pair) and the `ROW` literal and its comment in
  `write_custom_table.py`.

No new configuration, no migration, no ClickHouse setting change, no dependency change.

### Cross-repo coordination (DIAL Admin) — required, not advisory

DIAL Admin's *Add rows* template and its forthcoming *Connect* panel emit the space-separated form,
and the panel copy teaches "timestamps are space-separated" as a rejection to avoid. Because that form
now returns `422`, **the two repositories must land in the same release window**: DIAL Admin switches
its emitted literal to `…T…Z` and drops the panel guidance. The `422` message names the space
separator explicitly, so if they do drift the failure is self-explanatory rather than mysterious —
but it is still a failure, and this is the trade accepted in exchange for a single canonical format.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `table-management`: the "Write rows into a materialized table" requirement gains the accepted
  ISO-8601 timestamp format, the requirement that every value carry an explicit zone, and the
  `422 validation_error` rejection naming the row, column, value and specific mistake.
- `enrichment-runtime`: the "Rule-scoped versioned write-back endpoint" requirement states explicitly
  that `POST /v1/rules/{id}/rows` accepts the same single timestamp format and the same `422`, because
  it delegates to the materialized-table write path.

## Impact

**Code**

- `service/table/TableDataService` — the existing per-row key-validation loop also normalizes
  `timestamp`-typed values, **after** the key check so an unknown-column rejection keeps its current
  reason. The timestamp column set is resolved **once per request**, not per row:
  `TABLES_MAX_WRITE_BATCH_ROWS` defaults to 10 000, so the loop can run 10 000 × column-count times.
  A table with no `timestamp` column does no extra work.
- `service/table/` — a new small normalizer component owning the accepted format and the discriminated
  rejection messages.
- No change to `clickhouse/CatalogRowWriter` (it keeps serializing whatever it is handed), to
  `RuleWriteService`/`RuleDataController`, or to `web/handler/DefaultExceptionHandler`:
  `TableValidationException` already renders as `422 validation_error`.

**APIs**

- `POST /v1/tables/{name}/rows` and `POST /v1/rules/{id}/rows` — one canonical timestamp format
  replaces an accidental one. Previously-accepted space-separated, zone-less, date-only and numeric
  values now return `422 validation_error` instead of `200`; previously-rejected ISO-8601 values now
  succeed.

**Docs**

- `local_env/python-examples/tables/README.md`, `local_env/python-examples/tables/write_custom_table.py`.

**Not affected**

- The query/read path (DSL timestamp literals stay epoch-milliseconds per the existing project
  convention), Flight SQL, `data-sync` ingestion (`UsageLogWriter`, a different writer), Class-1
  materialize (a server-side `INSERT … SELECT` that never carries a caller payload), ClickHouse
  settings, and configuration.
