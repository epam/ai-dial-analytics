# rate-analytics Specification

## Purpose

Capture DIAL feedback/rating events into a dedicated ClickHouse `rate_analytics` SOURCE table so
ratings can be discovered and queried via the query API and joined to the rated completion's
`response_id` in `dial_usage_log`. Rows are emitted from the same blob-ingest pipeline that
populates `dial_usage_log`, only for `/rate` `POST` requests, and every distinct rate event is
preserved while re-ingest stays idempotent.

## Requirements

### Requirement: rate_analytics table and exposed schema

The service SHALL provide a ClickHouse SOURCE table named `rate_analytics` that captures DIAL
feedback/rating events. The table SHALL be provisioned by a ClickHouse migration (not re-created by
ADAS) and registered in the query catalog as a SOURCE table in status ACTIVE, discoverable and
queryable via `/v1/queries` alongside `dial_usage_log`.

The query catalog SHALL expose exactly these `rate_analytics` columns: `event_id` (uuid),
`response_id` (string), `rate` (nullable integer), `rate_raw` (nullable string), `comment` (nullable
string), `deployment` (string), `user_hash` (string), `chat_id` (string), `project_id` (string), and
`request_time` (timestamp). Columns that exist physically only to make storage correct (`trace_id`,
`core_span_id`, `_ingested_at`) MUST NOT be registered in the catalog, so they are neither
projectable, filterable, sortable, nor groupable through the query API. `event_id` is the exception
among the table's structural columns: it is the source's declared identity column (see
[enrichment-runtime](../enrichment-runtime/spec.md)), and an enrichment keyed on it can only be
built when its type is resolvable from the catalog, so it is exposed rather than hidden.

#### Scenario: Table is discoverable as a queryable source

- **WHEN** a client lists entities via `GET /v1/queries`
- **THEN** `rate_analytics` appears as a queryable SOURCE entity

#### Scenario: Only the analytics columns are exposed

- **WHEN** a client discovers the `rate_analytics` schema or runs a row query with an empty projection
- **THEN** only `event_id`, `response_id`, `rate`, `rate_raw`, `comment`, `deployment`, `user_hash`,
  `chat_id`, `project_id`, and `request_time` are returned, and no hidden physical column (`trace_id`,
  `core_span_id`, `_ingested_at`) appears

#### Scenario: A hidden physical column is not selectable

- **WHEN** a query references `trace_id`, `core_span_id`, or `_ingested_at` on `rate_analytics`
- **THEN** the query is rejected as an unknown field

### Requirement: rate_analytics retention expiry drops whole daily partitions

The `rate_analytics` table's `TTL` expiry SHALL drop whole parts rather than rewrite them
(`ttl_only_drop_parts = 1`), for the same reason and on the same terms as the `dial_usage_log` target it
is ingested alongside (see [data-sync](../data-sync/spec.md)): a row-level TTL rewrite needs free disk
equal to the part it rewrites, so on a full volume retention cannot run at all. The table is partitioned
by `toYYYYMMDD(request_time)` with its `TTL` expressed on `request_time`, so a part holds one day's rows
and expiry is quantized to that part — a row MAY outlive its `TTL` by up to one day, which is immaterial
against a one-year retention.

The setting SHALL be applied by the same versioned ClickHouse migration that applies it to the other
ingest target, so it is present whether Flyway applies the migrations or an operator applies them out of
band in version order.

#### Scenario: Provisioned table declares whole-part expiry

- **WHEN** `rate_analytics` is provisioned by applying the ClickHouse migrations in version order
- **THEN** its declaration carries `ttl_only_drop_parts = 1`, so a later `TTL` expiry drops expired
  daily parts instead of rewriting them

### Requirement: Per-row identity for the incremental scan

The `rate_analytics` table SHALL carry an `event_id` column that uniquely identifies one rate event,
so the table can be declared as a scannable enrichment source with the unique total order
`(_ingested_at, event_id)`.

`event_id` SHALL be derived by the database from the columns that make a row distinct — the table's
full ReplacingMergeTree sort key — rather than supplied by the ingest pipeline. Two consequences are
required. Rows already stored before the column existed SHALL read back the same value a fresh insert
of the same row would produce, so no row is left without a usable identity. Rows that collapse under
the engine's dedup (a re-ingested log line) SHALL share one `event_id`, so a re-ingest never changes
the identity an enrichment row is keyed on. Rows that do NOT collapse SHALL have distinct `event_id`
values, including rows whose `trace_id`/`core_span_id` are empty.

#### Scenario: Rows stored before the identity column existed are identified

- **WHEN** the identity column is introduced onto a table that already holds rate events
- **THEN** every pre-existing row reads back an `event_id`, distinct per distinct row

#### Scenario: A re-ingested rate event keeps its identity

- **WHEN** the same rate log line is ingested again after the identity column exists
- **THEN** the re-ingested row carries the same `event_id` as the row it collapses with

#### Scenario: Rows without trace identifiers are still distinct

- **WHEN** two distinct rate events are stored with empty `trace_id` and `core_span_id`
- **THEN** they receive different `event_id` values

### Requirement: Rate events are captured from the ingest pipeline

The service SHALL emit a `rate_analytics` row during the same blob-ingest pipeline that populates
`dial_usage_log`, but ONLY for a record whose `request.uri` ends with `/rate` **and** whose
`request.method` equals `POST`. A record that does not match this condition MUST NOT produce a
`rate_analytics` row. Emitting a `rate_analytics` row MUST NOT alter the record's `dial_usage_log`
row: a matching record produces both rows.

#### Scenario: A rate request produces a rate_analytics row

- **WHEN** an ingested record has `request.uri` ending in `/rate` and `request.method` = `POST`
- **THEN** the service writes a `rate_analytics` row in addition to the record's `dial_usage_log` row

#### Scenario: A non-rate request produces no rate_analytics row

- **WHEN** an ingested record's `request.uri` does not end in `/rate` or its method is not `POST`
- **THEN** the service writes no `rate_analytics` row for that record, and its `dial_usage_log` row is
  unaffected

### Requirement: response_id is sourced from the request body

For a `rate_analytics` row the service SHALL populate `response_id` from the client-supplied
`responseId` field inside the record's `request.body` JSON — NOT from the response — so a rating can
be joined to the rated completion's `response_id` in `dial_usage_log`. When `request.body` carries no
`responseId`, `response_id` SHALL fall back to the empty string (the row is still persisted).

#### Scenario: response_id comes from the rate body

- **WHEN** a matched rate record's `request.body` contains `{"responseId": "resp_123", ...}`
- **THEN** the `rate_analytics` row's `response_id` is `resp_123`

#### Scenario: Missing responseId yields an empty response_id

- **WHEN** a matched rate record's `request.body` has no `responseId`
- **THEN** the `rate_analytics` row is still written with an empty `response_id`

### Requirement: rate value is normalized

The service SHALL populate the `rate` column from the `rate` field of the record's `request.body`,
normalized to a signed integer: a numeric value SHALL pass through as its integer value; a boolean
`true` SHALL map to `1` and `false` SHALL map to `0`; an absent or blank value SHALL produce a null.

This normalization is **lossy by design and retained for compatibility**: boolean `false` and numeric
`0` both store `0`, so `rate` alone cannot distinguish a thumbs-down from a cleared rating. The
`rate_raw` column (see *raw rate value is preserved*) carries the unnormalized form for exactly that
purpose. The `rate` column's type and mapping SHALL NOT change, so every existing consumer of `rate`
continues to observe the values it observes today.

#### Scenario: Numeric rate passes through

- **WHEN** a matched rate record's body has `"rate": -1`
- **THEN** the `rate_analytics` row's `rate` is `-1`

#### Scenario: Boolean rate is normalized

- **WHEN** a matched rate record's body has `"rate": true`
- **THEN** the `rate_analytics` row's `rate` is `1` (and `false` maps to `0`)

#### Scenario: Absent rate is null

- **WHEN** a matched rate record's body has no `rate` field
- **THEN** the `rate_analytics` row's `rate` is null

#### Scenario: Normalized rate stays ambiguous on its own

- **WHEN** two matched rate records have bodies `"rate": false` and `"rate": 0` respectively
- **THEN** both rows' `rate` is `0`, and only `rate_raw` distinguishes them
### Requirement: comment is captured as encrypted free text

The service SHALL populate the nullable `comment` column from the `comment` field of the record's
`request.body`. Because a comment is user-authored free text (potential PII), the column SHALL be
stored with ClickHouse's AES encryption codec, mirroring the `dial_usage_log` body columns. When no
comment is present the column SHALL be null.

#### Scenario: Comment is captured

- **WHEN** a matched rate record's body has `"comment": "Ui bug"`
- **THEN** the `rate_analytics` row's `comment` is `Ui bug` (stored encrypted at rest)

#### Scenario: Absent comment is null

- **WHEN** a matched rate record's body has no `comment` field
- **THEN** the `rate_analytics` row's `comment` is null

### Requirement: Keep-all-ratings with idempotent re-ingest

The `rate_analytics` table SHALL preserve every distinct rate event as its own row: repeated ratings
of the same `response_id` (e.g. a thumbs-up later changed to thumbs-down, or an added comment) MUST
NOT collapse into one another. Re-ingesting the same source log line MUST remain idempotent — it MUST
NOT create a duplicate row. This SHALL be achieved with a `ReplacingMergeTree` engine whose sort key
includes the record's span identity (`trace_id`, `core_span_id`), so distinct rate events differ in
the sort key while a re-ingested identical line collapses on merge.

#### Scenario: Distinct ratings of one response are all kept

- **WHEN** the same `response_id` is rated by two separate `/rate` requests (distinct span ids)
- **THEN** `rate_analytics` retains a row for each rate event

#### Scenario: Re-ingesting the same line does not duplicate

- **WHEN** the same rate log line is ingested more than once
- **THEN** `rate_analytics` collapses the duplicates and retains a single row for that event
### Requirement: raw rate value is preserved

The service SHALL populate a `rate_raw` column with the **verbatim scalar text** of the `rate` field of
the record's `request.body`, alongside the normalized `rate`, so that a rating submitted as boolean
`false` remains distinguishable from one submitted as numeric `0`. A boolean value SHALL be stored as
`true` or `false`; a numeric value SHALL be stored as its literal text (`0`, `1`, `-1`); a JSON string
value SHALL be stored as its unquoted text content; an absent, blank, or non-scalar (object or array)
value SHALL produce a null.

`rate_raw` SHALL be nullable, SHALL NOT be treated as sensitive (it carries a rating scalar, never
user-authored text), and SHALL NOT be heavy. Rows ingested before this column existed SHALL read null
rather than a substituted value: the raw form was never captured for them and MUST NOT be inferred
from the normalized `rate`, because that inference is exactly the ambiguity this column exists to
remove.

Because `true` and `false` are not numeric text, a consumer SHALL be able to count unambiguous
thumbs-down ratings exactly, as those rows whose `rate_raw` is `false` together with those whose
`rate_raw` is present and whose normalized `rate` is negative. The negative half of the count SHALL be
read from `rate`, not from `rate_raw`: `rate_raw` is text and therefore has no ordering, so matching it
against a fixed literal such as `-1` would silently miss any other negative value the producer sends.

#### Scenario: Boolean false is preserved as boolean

- **WHEN** a matched rate record's body has `"rate": false`
- **THEN** the `rate_analytics` row's `rate` is `0` and its `rate_raw` is `false`

#### Scenario: Numeric zero is preserved as numeric

- **WHEN** a matched rate record's body has `"rate": 0`
- **THEN** the `rate_analytics` row's `rate` is `0` and its `rate_raw` is `0`, so the row is
  distinguishable from a boolean `false` submission that stores the same `rate`

#### Scenario: Boolean true is preserved as boolean

- **WHEN** a matched rate record's body has `"rate": true`
- **THEN** the `rate_analytics` row's `rate` is `1` and its `rate_raw` is `true`

#### Scenario: Negative numeric rate is preserved

- **WHEN** a matched rate record's body has `"rate": -1`
- **THEN** the `rate_analytics` row's `rate` is `-1` and its `rate_raw` is `-1`

#### Scenario: Absent rate yields a null raw value

- **WHEN** a matched rate record's body has no `rate` field
- **THEN** the `rate_analytics` row's `rate` is null and its `rate_raw` is null

#### Scenario: Non-scalar rate yields a null raw value

- **WHEN** a matched rate record's body has a `rate` field whose value is an object or an array
- **THEN** the `rate_analytics` row's `rate_raw` is null and the ingest does not fail

#### Scenario: Exact thumbs-down count is queryable

- **WHEN** a client queries `rate_analytics` counting rows where `rate_raw` is `false`, or where
  `rate_raw` is not null and `rate` is negative
- **THEN** the result counts only ratings that are unambiguously negative, excluding rows whose `rate`
  is `0` because the rating was cleared, and excluding pre-column rows whose `rate_raw` is null
