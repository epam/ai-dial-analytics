## ADDED Requirements

### Requirement: Computed fields are projection-only and completed after the statement runs

A **computed field** — `dial_usage_log_payload.request_body_full` and `dial_usage_log_payload.request_body_rebuild`
(see the data-sync capability, "A rebuilt request body is readable beside the stored one") — SHALL be usable in a
structured query only as a **plain row-mode projection**: a `select` entry that is the bare field, with or without
`as`. Its value SHALL be completed by ADAS after the statement has run, so the statement itself never computes it.

Any other use SHALL be rejected before ClickHouse is contacted, with HTTP 400, `error` `bad_request`, and a message
naming the field and stating that it is projection-only and can only be selected. Rejected uses SHALL include: a
`filter` operand of any operator, `is_null` included; a `sort` key, directly or through the output name of a
`select` entry that reads one; a `group_by` key; a `having` operand; an argument of any function, aggregate, `case`
branch, lambda, window or `array_join`; any use in an `aggregate`-mode query; a `distinct` query; and a reference
from a scalar subquery or `in` subquery. A rejected query SHALL be rejected whatever else it projects.

A projected computed field SHALL appear in the response under its output name, in its `select` position, with the
type discovery reports for it (`string` for `request_body_full`, `enum` serialized as its string value for
`request_body_rebuild`), and the response SHALL carry no other column on its account. An output name beginning with
`__adas_` SHALL be rejected with HTTP 400 `bad_request` when the query projects a computed field.

Both computed fields SHALL count as heavy enrichment columns for late materialization (see "Projection-only heavy
enrichment columns are fetched for the returned page only"): a block projecting one is late-materialized under the
same conditions, and the rebuild runs for the returned page's rows only. The `include_total` count SHALL NOT compute
either field. A query that projects neither field SHALL render, execute and return exactly as it does without this
requirement.

The completed values are the frontend hop inspector's contract: a row read with `filter` on `trace_id`,
`core_span_id` and `request_time`, `limit 1`, and a `select` naming both fields SHALL return the whole request as
JSON text in `dial_usage_log_payload.request_body_full` and its status in
`dial_usage_log_payload.request_body_rebuild`; a client SHALL treat `partial` as "this is the stored delta, not the
whole request".

#### Scenario: A hop is read back whole

- **WHEN** `POST /v1/queries/execute` selects `trace_id`, `dial_usage_log_payload.request_body_full` and
  `dial_usage_log_payload.request_body_rebuild` from `dial_usage_log`, filtering `trace_id = "trace-0001"`,
  `core_span_id = "span-0003"` and a `request_time` range, with `limit 1`
- **THEN** the row carries the rebuilt request as a JSON string and `"rebuilt"`, and the response's columns are
  exactly the three selected

#### Scenario: An alias is a plain projection

- **WHEN** a query selects `{"field": "dial_usage_log_payload.request_body_full", "as": "body"}`
- **THEN** the row carries the completed value under `body`

#### Scenario: Filtering on a computed field is refused

- **WHEN** a query filters `dial_usage_log_payload.request_body_rebuild = "partial"`
- **THEN** the request is rejected with HTTP 400 `bad_request` naming the field and the projection-only restriction,
  and no ClickHouse query is issued

#### Scenario: An expression over a computed field is refused

- **WHEN** a query selects `json_extract_string(dial_usage_log_payload.request_body_full, "model")`
- **THEN** the request is rejected with HTTP 400 `bad_request` naming the field

#### Scenario: Sorting by a computed field's output name is refused

- **WHEN** a query selects `dial_usage_log_payload.request_body_full` as `body` and sorts by `body`
- **THEN** the request is rejected with HTTP 400 `bad_request`

#### Scenario: Aggregate mode is refused

- **WHEN** an `aggregate`-mode query groups by or measures a computed field
- **THEN** the request is rejected with HTTP 400 `bad_request`

#### Scenario: A late-materialized page rebuilds its own rows only

- **WHEN** a row-mode query selects `dial_usage_log_payload.request_body_full`, filters a `request_time` window
  holding many delta rows, and pages `limit 10`
- **THEN** the statement is late-materialized, and the rebuild statements look up chains for the ten returned rows
  only

#### Scenario: A query without the fields is unchanged

- **WHEN** a query selects `dial_usage_log_payload.request_body` and no computed field
- **THEN** its rendered SQL and its rows are those it returned before this requirement, and no rebuild statement is
  issued
