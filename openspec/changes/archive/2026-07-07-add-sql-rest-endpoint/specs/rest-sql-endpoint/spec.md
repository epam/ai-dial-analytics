# rest-sql-endpoint Delta Specification

## ADDED Requirements

### Requirement: REST SQL execution endpoint

The system SHALL execute an ad-hoc SQL `SELECT` statement submitted as a JSON request body at
`POST /v1/queries/execute-sql`. The request body SHALL be a JSON object with a single required
field `sql` carrying the SQL text; a missing, null, or blank `sql` SHALL be rejected with
HTTP 400. The accepted SQL surface SHALL be exactly the [sql-query-translation](../../../../specs/sql-query-translation/spec.md)
contract — the same SQL the Flight SQL endpoint accepts: a single read-only `SELECT` statement,
validated against the query-schema catalog and translated to a `StructuredQuery`. The endpoint
SHALL be available unconditionally (no feature toggle), independent of `flight.enabled`.

#### Scenario: Valid SQL executes

- **WHEN** a client posts `{ "sql": "SELECT request_id FROM dial_usage_log LIMIT 10" }` to
  `/v1/queries/execute-sql`
- **THEN** the SQL is translated to a `StructuredQuery` and executed, and the response is
  HTTP 200 with the projected rows

#### Scenario: Blank sql rejected

- **WHEN** a client posts `{ "sql": "  " }` (or omits the `sql` field)
- **THEN** the request is rejected with HTTP 400 and an `ErrorView` body, and no translation or
  execution occurs

#### Scenario: Works with Flight disabled

- **WHEN** the service runs with `flight.enabled=false` (the default) and a client posts a
  valid SQL query to `/v1/queries/execute-sql`
- **THEN** the query executes successfully — the SQL translator does not require the Flight
  gRPC listener

### Requirement: Execution reuses the structured-query pipeline and governance

Every REST SQL query SHALL execute by translating the SQL to a `StructuredQuery` via the shared
SQL front end and invoking the same execution path as `POST /v1/queries/execute`. All
structured-query governance SHALL apply unchanged: entity and field validation against the
discovered catalog schema, the closed function catalog, enrichment join rules, the default
limit of 100, and the maximum limit of 1000 rows. A SQL `LIMIT` above the maximum SHALL be
rejected with HTTP 400 naming the requested limit and the maximum — never silently clamped. A
SQL query with no `LIMIT` SHALL receive the default limit. Unsupported SQL constructs SHALL be
rejected before any ClickHouse contact, per the sql-query-translation contract.

#### Scenario: Same rows as the DSL endpoint

- **WHEN** the same logical query is issued as SQL via `POST /v1/queries/execute-sql` and as a
  structured query via `POST /v1/queries/execute`
- **THEN** both return the same rows and values in the same JSON encoding

#### Scenario: Limit above the maximum rejected

- **WHEN** a client posts `{ "sql": "SELECT request_id FROM dial_usage_log LIMIT 50000" }`
- **THEN** the response is HTTP 400 with a message naming the requested limit and the 1000-row
  maximum, and no rows are returned

#### Scenario: Unsupported construct rejected before execution

- **WHEN** a client posts SQL containing a construct outside the supported subset (e.g. an
  explicit `JOIN` or `SELECT a + b`)
- **THEN** the response is HTTP 400 identifying the unsupported construct, and ClickHouse is
  not contacted

### Requirement: Response envelope matches the DSL endpoint

The endpoint SHALL return the same response envelope as `POST /v1/queries/execute`: a JSON
object with a `rows` array and a nullable `totalCount`. Each row SHALL be a field-name → value
map keyed by the selected column names or aliases (enrichment columns by their qualified flat
name); every projected column SHALL be present in every row with JSON `null` for null cells.
`totalCount` SHALL never be populated on this path — SQL provides no total-count opt-in — and,
per the service's shared response serialization (null bean properties are omitted), the
`totalCount` key SHALL be absent from the JSON body, exactly as the DSL endpoint serializes a
null `totalCount`.

#### Scenario: Envelope shape

- **WHEN** a valid SQL query returns two rows of two columns
- **THEN** the response body is `{ "rows": [ { <col>: <value>, <col>: <value> }, ... ] }` with
  both column keys present in every row and no `totalCount` key

#### Scenario: Null cells preserved

- **WHEN** a selected enrichment column has no matching enrichment row for some result row
- **THEN** that row still contains the column's key with a JSON `null` value

### Requirement: HTTP error mapping

Failures SHALL map to the stable `ErrorView` contract without leaking raw backend internals:
SQL parse errors, unsupported SQL constructs, and unknown tables/columns (translation
failures) → HTTP 400 `bad_request` with a message identifying the problem; structured-query
validation failures → HTTP 400 `bad_request`; ClickHouse rejection of the rendered query →
HTTP 400 `bad_request`; ClickHouse connectivity failure → HTTP 503 `clickhouse_unavailable`;
malformed JSON body → HTTP 400 `bad_request`.

#### Scenario: Translation failure maps to 400

- **WHEN** a client posts `{ "sql": "SELECT no_such_column FROM dial_usage_log" }`
- **THEN** the response is HTTP 400 with `error` = `bad_request` and a message naming the
  unknown column, in the `ErrorView` shape

#### Scenario: ClickHouse down maps to 503

- **WHEN** ClickHouse is unreachable while executing a valid SQL query
- **THEN** the response is HTTP 503 with `error` = `clickhouse_unavailable` and no raw driver
  message in the body

### Requirement: Security inherits the /v1 policy

The endpoint SHALL be protected exactly as other `/v1/**` read endpoints: when
`security.mode=oidc`, the caller must authenticate (Bearer token or `Api-Key`) and carry
`FULL_ADMIN` or `READ_ONLY_ADMIN` — failures yield 401/403 in the `ErrorView` shape; when
`security.mode=none`, the endpoint is open. No endpoint-specific rules SHALL be added.

#### Scenario: Unauthenticated caller rejected in oidc mode

- **WHEN** `security.mode=oidc` and a client posts to `/v1/queries/execute-sql` without
  credentials
- **THEN** the response is HTTP 401 `unauthorized`

#### Scenario: Open in none mode

- **WHEN** `security.mode=none`
- **THEN** the endpoint accepts requests without credentials
