## ADDED Requirements

### Requirement: Computed fields are projection-only in SQL

The computed fields `"dial_usage_log_payload.request_body_full"` and `"dial_usage_log_payload.request_body_rebuild"`
(see the structured-query capability, "Computed fields are projection-only and completed after the statement runs")
SHALL be resolvable SQL columns of `dial_usage_log` for a caller who may see them, typed as character data, and
SHALL be usable only as a plain select item, optionally aliased, of a **single-entity** statement. Any other use SHALL
be rejected at translation time — before any ClickHouse contact — with HTTP 400 `bad_request` and a message naming
the field and stating that it can only be selected; the error SHALL NOT be an "unknown column" error. Rejected uses
SHALL include `WHERE`, `ORDER BY` (by the column, its alias or its ordinal position), `GROUP BY`, `HAVING`, `SELECT
DISTINCT`, any function or operator argument, `CASE`, a window, `ARRAY_JOIN`, a subquery, and **every composite
statement** — a join, a derived table, a common table expression, a set operation or a statement carrying a
subquery — wherever in it the field appears, because a composite block's projection may be consumed by an
enclosing block that the completion step never sees. A `SELECT *` SHALL NOT expand to either field, since it
excludes enrichment columns.

A single-entity statement projecting one SHALL translate to the structured query carrying it as an ordinary field
projection, and SHALL execute with the completed value, exactly as the DSL does.

#### Scenario: Selecting the rebuilt body over SQL

- **WHEN** `POST /v1/queries/execute-sql` runs `SELECT trace_id, "dial_usage_log_payload.request_body_full" AS body
  FROM dial_usage_log WHERE trace_id = 'trace-0001' LIMIT 1`
- **THEN** the row carries the completed value under `body`

#### Scenario: A computed field in WHERE is refused

- **WHEN** a statement filters `WHERE "dial_usage_log_payload.request_body_rebuild" = 'partial'`
- **THEN** it is rejected with HTTP 400 `bad_request` naming the field and the projection-only restriction

#### Scenario: A computed field in a join is refused

- **WHEN** a statement joins `dial_usage_log` to another entity and selects `"dial_usage_log_payload.request_body_full"`
- **THEN** it is rejected with HTTP 400 `bad_request`, naming the field and stating that it cannot be read in a
  composite statement

#### Scenario: A computed field in a derived table is refused

- **WHEN** a statement selects `"dial_usage_log_payload.request_body_full"` inside a derived table in `FROM`
- **THEN** it is rejected with HTTP 400 `bad_request`
