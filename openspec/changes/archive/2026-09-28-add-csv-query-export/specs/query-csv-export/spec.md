## Purpose

The CSV response form of query execution: `POST /v1/queries/execute-csv` and
`POST /v1/queries/execute-sql-csv` return the result `/v1/queries/execute` and
`/v1/queries/execute-sql` would return, as a downloadable RFC 4180 CSV file instead of a JSON
envelope, with the same governance and error contract.

## ADDED Requirements

### Requirement: CSV execution endpoints mirror the JSON execution endpoints

The system SHALL expose `POST /v1/queries/execute-csv`, accepting exactly the request body of
`POST /v1/queries/execute` (a structured-query DSL document), and `POST /v1/queries/execute-sql-csv`,
accepting exactly the request body of `POST /v1/queries/execute-sql` (`{"sql": "<statement>"}`,
including statements answered from `INFORMATION_SCHEMA`). Each SHALL execute the request through the
same pipeline as its JSON counterpart, so that everything the counterpart's specs
([structured-query](../structured-query/spec.md), [rest-sql-endpoint](../rest-sql-endpoint/spec.md))
say about the result holds here: the same accepted surface and rejections, the same validation and
function catalog, the same default limit (100) and maximum limit (1000) — a requested limit above the
maximum is rejected, never clamped — the same `INFORMATION_SCHEMA` exemption from those limits, the
same query budget, and the same rows, columns, column names, column order and row order. The CSV
endpoints SHALL NOT be a way to obtain more rows, other rows or other columns than the JSON endpoint
returns for the same request body.

On success the response SHALL be HTTP 200 with `Content-Type: text/csv; charset=UTF-8` and
`Content-Disposition: attachment; filename="result.csv"`, whatever the request's `Accept` header.

#### Scenario: DSL query returns its result as CSV

- **WHEN** a client posts a valid row-mode DSL query projecting two columns to `/v1/queries/execute-csv`
- **THEN** the response is HTTP 200 with content type `text/csv; charset=UTF-8`, an attachment
  disposition named `result.csv`, and a body of one header row followed by one record per result
  row, in the order `/v1/queries/execute` returns them

#### Scenario: SQL statement returns its result as CSV

- **WHEN** a client posts `{"sql": "SELECT request_id FROM dial_usage_log LIMIT 10"}` to
  `/v1/queries/execute-sql-csv`
- **THEN** the response is HTTP 200 CSV whose header is `request_id` and whose records are the rows
  `/v1/queries/execute-sql` returns for the same body

#### Scenario: Catalog statement returns CSV without the page limit

- **WHEN** a client posts an unbounded `SELECT` against `INFORMATION_SCHEMA.COLUMNS` whose result
  exceeds the default page limit to `/v1/queries/execute-sql-csv`
- **THEN** every catalog row is returned as a CSV record, exactly as `/v1/queries/execute-sql` would
  return them

#### Scenario: Limit above the maximum is rejected, not exported

- **WHEN** a client posts a query whose limit exceeds the maximum limit to either CSV endpoint
- **THEN** the response is the same HTTP 400 `bad_request` `ErrorView` the JSON counterpart returns,
  and no CSV is produced

#### Scenario: Accept header does not change the success response

- **WHEN** a client posts a valid query to a CSV endpoint with `Accept: application/json`
- **THEN** the response is still HTTP 200 `text/csv`

### Requirement: CSV dialect and header row

The body SHALL be CSV per RFC 4180, encoded as UTF-8 without a byte-order mark: fields separated by
`,`, every record (the header included) terminated by `CRLF`. A field SHALL be enclosed in double
quotes when it contains `,`, `"`, `CR` or `LF`, or when it is the empty string, and a `"` inside a
quoted field SHALL be doubled; otherwise it SHALL be written unquoted. The first record SHALL always
be a header naming the result's columns in result order, using the same names the JSON counterpart
uses as row keys — present **even when the result has no rows**, in which case the body is exactly
the header record.

#### Scenario: Empty result yields a header-only file

- **WHEN** a valid query projecting `request_id` and `model` matches no rows
- **THEN** the body is exactly `request_id,model` followed by `CRLF`

#### Scenario: Special characters are quoted

- **WHEN** a string cell holds `say "hi", then` followed by a line break and `bye`
- **THEN** the field is written as `"say ""hi"", then` + line break + `bye"`, and the record count
  of the parsed file equals the result's row count plus one

#### Scenario: Unnamed SQL expression keeps its derived header name

- **WHEN** a client posts `{"sql": "SELECT count(*) FROM dial_usage_log"}` to `/v1/queries/execute-sql-csv`
- **THEN** the header record is `count`, never `EXPR$0`

### Requirement: CSV cells render the JSON value unchanged

Each cell SHALL carry the value the JSON counterpart's row carries for that column, rendered as text
and never otherwise rewritten:

- a JSON `null` SHALL be an empty, unquoted field; an empty string SHALL be the quoted field `""`,
  so the two stay distinguishable;
- a string SHALL be its characters as-is;
- a number or boolean SHALL be its JSON literal text (`42`, `3.5`, `true`);
- an `array`, `object` or `map` cell SHALL be its compact JSON text exactly as the JSON body would
  serialize it (e.g. `["a",null]`, `{"http.method":"GET"}`), then quoted per the dialect rules.

The system SHALL NOT alter a value to defend spreadsheet applications — a string beginning with `=`,
`+`, `-` or `@` SHALL be written as-is.

#### Scenario: Null and empty string are distinguishable

- **WHEN** one row has a null enrichment column and another has the empty string in a string column
- **THEN** the null renders as an empty unquoted field and the empty string renders as `""`

#### Scenario: Structured cells render as compact JSON

- **WHEN** a query projects an `array` cell `["a", null]` and a `map` cell `{"http.method": "GET"}`
- **THEN** the fields are `"[""a"",null]"` and `"{""http.method"":""GET""}"`

#### Scenario: Formula-like strings are not escaped

- **WHEN** a string cell holds `=SUM(A1:A2)`
- **THEN** the field is written as `=SUM(A1:A2)`

### Requirement: Total count travels in a response header

When the JSON counterpart would populate `totalCount` (a DSL row-mode query whose offset page sets
`include_total`), `POST /v1/queries/execute-csv` SHALL return that value in an `X-Total-Count`
response header and SHALL NOT add it to the body. When `totalCount` would be null — always on
`execute-sql-csv` — the header SHALL be absent.

#### Scenario: include_total yields X-Total-Count

- **WHEN** a row-mode query with an offset page setting `include_total: true` is posted to
  `/v1/queries/execute-csv`
- **THEN** the response carries `X-Total-Count` equal to the count of all rows matching the filter,
  and the body holds only the header and the page's rows

#### Scenario: No total, no header

- **WHEN** a query that does not request the total is posted to either CSV endpoint
- **THEN** the response has no `X-Total-Count` header

### Requirement: CSV endpoint errors keep the ErrorView contract

A failure on either CSV endpoint SHALL return the same HTTP status and `ErrorView` JSON body
(`Content-Type: application/json`) as the JSON counterpart returns for the same request — 400
`bad_request` for malformed bodies and validation or translation failures, 422
`query_budget_exceeded` for an exhausted query budget, 503 `clickhouse_unavailable` for an
unreachable ClickHouse, and 401 `unauthorized` / 403 `forbidden` from the security layer — and SHALL
do so **even when the request's `Accept` header admits only `text/csv`**. A failure SHALL never
produce a partial CSV body or an empty response in place of the `ErrorView`.

#### Scenario: Validation failure under Accept text/csv

- **WHEN** a client posts a DSL query naming an unknown field to `/v1/queries/execute-csv` with
  `Accept: text/csv`
- **THEN** the response is HTTP 400 with content type `application/json` and an `ErrorView` body
  whose `error` is `bad_request`

#### Scenario: SQL translation failure

- **WHEN** a client posts `{"sql": "SELECT no_such_column FROM dial_usage_log"}` to
  `/v1/queries/execute-sql-csv`
- **THEN** the response is HTTP 400 `bad_request` in the `ErrorView` shape, naming the unknown column

### Requirement: CSV endpoints inherit query access control

The CSV endpoints SHALL be authorized exactly as the JSON execution endpoints: in `oidc` mode the
caller SHALL hold `FULL_ADMIN` or `READ_ONLY_ADMIN`, and sensitive columns SHALL be invisible to a
caller that is not `FULL_ADMIN` — absent from a wildcard projection's header and records, and
rejected as an unknown field when referenced. No endpoint-specific access rule SHALL be added.

#### Scenario: Unauthenticated caller rejected in oidc mode

- **WHEN** `security.mode=oidc` and a client posts to `/v1/queries/execute-csv` without credentials
- **THEN** the response is HTTP 401 `unauthorized` in the `ErrorView` shape

#### Scenario: Sensitive column is an unknown field for a read-only caller

- **WHEN** a `READ_ONLY_ADMIN` caller posts a query referencing a sensitive column to either CSV
  endpoint
- **THEN** the response is HTTP 400 `bad_request` in the `ErrorView` shape, exactly as the JSON
  counterpart rejects it, and no CSV is produced

#### Scenario: A read-only caller executes to CSV

- **WHEN** a `READ_ONLY_ADMIN` caller posts a valid query over non-sensitive columns to either CSV
  endpoint
- **THEN** the response is HTTP 200 CSV
