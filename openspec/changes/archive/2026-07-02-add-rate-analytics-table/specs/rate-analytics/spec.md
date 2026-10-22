## ADDED Requirements

### Requirement: rate_analytics table and exposed schema

The service SHALL provide a ClickHouse SOURCE table named `rate_analytics` that captures DIAL
feedback/rating events. The table SHALL be provisioned by a ClickHouse migration (not re-created by
ADAS) and registered in the query catalog as a SOURCE table in status ACTIVE, discoverable and
queryable via `/v1/queries` alongside `dial_usage_log`.

The query catalog SHALL expose exactly these `rate_analytics` columns: `response_id` (string),
`rate` (nullable integer), `comment` (nullable string), `deployment` (string), `user_hash` (string),
`chat_id` (string), `project_id` (string), and `request_time` (timestamp). Columns that exist
physically only to make storage correct (`trace_id`, `core_span_id`, `_ingested_at`) MUST NOT be
registered in the catalog, so they are neither projectable, filterable, sortable, nor groupable
through the query API.

#### Scenario: Table is discoverable as a queryable source

- **WHEN** a client lists entities via `GET /v1/queries`
- **THEN** `rate_analytics` appears as a queryable SOURCE entity

#### Scenario: Only the analytics columns are exposed

- **WHEN** a client discovers the `rate_analytics` schema or runs a row query with an empty projection
- **THEN** only `response_id`, `rate`, `comment`, `deployment`, `user_hash`, `chat_id`, `project_id`,
  and `request_time` are returned, and no physical structural column (`trace_id`, `core_span_id`,
  `_ingested_at`) appears

#### Scenario: A hidden physical column is not selectable

- **WHEN** a query references `trace_id`, `core_span_id`, or `_ingested_at` on `rate_analytics`
- **THEN** the query is rejected as an unknown field

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

#### Scenario: Numeric rate passes through

- **WHEN** a matched rate record's body has `"rate": -1`
- **THEN** the `rate_analytics` row's `rate` is `-1`

#### Scenario: Boolean rate is normalized

- **WHEN** a matched rate record's body has `"rate": true`
- **THEN** the `rate_analytics` row's `rate` is `1` (and `false` maps to `0`)

#### Scenario: Absent rate is null

- **WHEN** a matched rate record's body has no `rate` field
- **THEN** the `rate_analytics` row's `rate` is null

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
