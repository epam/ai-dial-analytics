## ADDED Requirements

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

## MODIFIED Requirements

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
