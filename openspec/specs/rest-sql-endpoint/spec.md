# rest-sql-endpoint Specification

## Purpose

The HTTP/JSON transport for ad-hoc SQL: `POST /v1/queries/execute-sql` accepts a single read-only
SQL `SELECT` statement — the same SQL subset the [flight-sql-endpoint](../flight-sql-endpoint/spec.md)
accepts, per the [sql-query-translation](../sql-query-translation/spec.md) contract — translates it
to a `StructuredQuery`, executes it through the same pipeline as `POST /v1/queries/execute`
(all [structured-query](../structured-query/spec.md) governance unchanged), and returns the same
JSON response envelope. Always available (no feature toggle), independent of `flight.enabled`.

The capability also carries the validation-only counterpart of that endpoint:
`POST /v1/queries/validate-sql` runs every check `execute-sql` runs except the ClickHouse round trip
and reports whether the statement would be accepted, so a caller composing SQL can converge without
executing anything. It lives here rather than in a capability of its own because it is defined by
agreement with `execute-sql`: the two contracts have to move together, and keeping them in one file is
what makes a divergence visible.
## Requirements
### Requirement: REST SQL execution endpoint

The system SHALL execute an ad-hoc SQL `SELECT` statement submitted as a JSON request body at
`POST /v1/queries/execute-sql`. The request body SHALL be a JSON object with a single required
field `sql` carrying the SQL text; a missing, null, or blank `sql` SHALL be rejected with
HTTP 400. The accepted SQL surface SHALL be exactly the [sql-query-translation](../sql-query-translation/spec.md)
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

Every REST SQL query **against queryable entities** SHALL execute through the shared SQL front end and
the same execution path as `POST /v1/queries/execute`: a single-block statement by translating to a
`StructuredQuery`, a composite statement (one that joins, reads a derived table or CTE, carries a
subquery, or combines branches with a set operation — see the sql-query-translation contract) by executing the composite plan the front end produces,
whose leaves are single-entity blocks rendered by that same path. All structured-query governance SHALL
apply unchanged: entity and field validation against the discovered catalog schema in every block, the
closed function catalog, enrichment join rules per entity, sensitive-column masking, `final`
deduplication, the query budget, the default limit of 100, and the maximum limit of 1000 rows. The default
and maximum limit govern the **outermost** block only: a SQL `LIMIT` on the outer statement above the
maximum SHALL be rejected with HTTP 400 naming the requested limit and the maximum — never silently
clamped — and an outer statement with no `LIMIT` SHALL receive the default limit, while an inner block's
`LIMIT`/`OFFSET` is executed as written and an inner block without one is not defaulted. For a
**set-operation** statement the outermost block is the set operation's combined result, so the default and
the maximum bound the rows the branches produce together, while a branch's own `LIMIT` is an inner limit
and is executed as written. A `LIMIT` written after the last branch is a **branch** limit, not an outer
one, because that is where ClickHouse binds it (see the sql-query-translation set-operation requirement):
such a statement has stated no outer bound, so it receives the default limit rather than being governed by
the limit the caller wrote. Unsupported SQL
constructs SHALL be rejected before any ClickHouse contact, per the sql-query-translation contract.

A statement against `INFORMATION_SCHEMA` is the one exception, and it is scoped deliberately: it never
reaches ClickHouse, so none of the governance above has anything to govern. See "Catalog metadata
statements execute over REST" for what does apply. Every other statement — including one that only
looks like metadata, such as a `SELECT` over an entity named after a catalog relation — takes the path
above.

#### Scenario: Same rows as the DSL endpoint

- **WHEN** the same logical query is issued as SQL via `POST /v1/queries/execute-sql` and as a
  structured query via `POST /v1/queries/execute`
- **THEN** both return the same rows and values in the same JSON encoding

#### Scenario: Limit above the maximum rejected

- **WHEN** a client posts `{ "sql": "SELECT request_id FROM dial_usage_log LIMIT 50000" }`
- **THEN** the response is HTTP 400 with a message naming the requested limit and the 1000-row
  maximum, and no rows are returned

#### Scenario: Unsupported construct rejected before execution

- **WHEN** a client posts SQL containing a construct outside the supported subset (e.g. a correlated
  subquery or `WITH RECURSIVE`)
- **THEN** the response is HTTP 400 identifying the unsupported construct, and ClickHouse is
  not contacted

#### Scenario: Set-operation statement executes under the outer governance

- **WHEN** a client posts `{ "sql": "SELECT model FROM dial_usage_log UNION ALL SELECT model FROM rate_analytics" }`
- **THEN** the response is HTTP 200 with `{"rows": [...]}` bounded by the default limit of 100 counted
  over the combined result, and the statement ran under the same `final` and `query.budget.*` settings as
  a single-entity statement

#### Scenario: A branch limit is not governed

- **WHEN** a client posts `{ "sql": "(SELECT event_id FROM dial_usage_log LIMIT 5000) UNION ALL (SELECT response_id FROM rate_analytics LIMIT 5000)" }`
- **THEN** the response is HTTP 200 — each branch `LIMIT 5000` is executed as written, and only the
  combined result is subject to the 1000-row maximum

#### Scenario: A limit after the last branch is a branch limit

- **WHEN** a client posts `{ "sql": "SELECT event_id FROM dial_usage_log UNION ALL SELECT response_id FROM rate_analytics LIMIT 5000" }`
- **THEN** the response is HTTP 200 and **not** the over-maximum rejection: the `LIMIT 5000` binds to the
  last branch, as it does in ClickHouse, so the statement states no outer bound and the combined result
  takes the default limit of 100

#### Scenario: Composite statement executes under the outer governance

- **WHEN** a client posts `{ "sql": "SELECT a.project_id, count(*) AS n FROM dial_usage_log a JOIN rate_analytics b ON a.response_id = b.response_id GROUP BY a.project_id" }`
- **THEN** the response is HTTP 200 with `{"rows": [...]}` bounded by the default limit of 100, and the
  statement ran under the same `final` and `query.budget.*` settings as a single-entity statement

#### Scenario: Inner limit is not governed

- **WHEN** a client posts `{ "sql": "SELECT count(*) FROM (SELECT event_id FROM dial_usage_log LIMIT 5000) t" }`
- **THEN** the response is HTTP 200 — the inner `LIMIT 5000` is executed as written and only the outer
  result is subject to the 1000-row maximum

### Requirement: Response envelope matches the DSL endpoint

The endpoint SHALL return the same response envelope as `POST /v1/queries/execute`: a JSON
object with a `rows` array and a nullable `totalCount`. Each row SHALL be a field-name → value
map keyed by the selected column names or aliases (enrichment columns by their qualified flat
name); a select expression the statement does not name SHALL be keyed by its derived name per the
`sql-query-translation` naming contract (a function call by its lowercase name, e.g. `count`) and
never by the planner's synthetic `EXPR$<n>`. Every projected column SHALL be present in every row
with JSON `null` for null cells.
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

#### Scenario: Unnamed expression is keyed by its derived name

- **WHEN** a client posts `{"sql": "SELECT count(*) FROM dial_usage_log"}` to `/v1/queries/execute-sql`
- **THEN** each row carries the key `count` and no `EXPR$0` key

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
`TABLE_READ` on every table the statement resolves for `execute-sql`, and `TABLE_GET` for
`validate-sql` — failures yield 401 for an absent credential, and an unresolvable relation rather than
`403` for a table below the bar, in the `ErrorView` shape; when
`security.mode=none`, the endpoint is open. No endpoint-specific rules SHALL be added.

#### Scenario: Unauthenticated caller rejected in oidc mode

- **WHEN** `security.mode=oidc` and a client posts to `/v1/queries/execute-sql` without
  credentials
- **THEN** the response is HTTP 401 `unauthorized`

#### Scenario: Open in none mode

- **WHEN** `security.mode=none`
- **THEN** the endpoint accepts requests without credentials

### Requirement: Array columns are selectable and returned as JSON arrays

`POST /v1/queries/execute-sql` SHALL accept SQL that projects `array`-typed catalog fields
(per the `sql-query-translation` contract: projection-only) and SHALL return each array cell
as a native JSON array of the field's element type inside the row object — never as an escaped
string — exactly the encoding `POST /v1/queries/execute` produces for the same projection. An
empty ClickHouse array SHALL be returned as an empty JSON array `[]`.

#### Scenario: Array column projected via SQL

- **WHEN** a client posts `{ "sql": "SELECT request_id, execution_path FROM dial_usage_log LIMIT 5" }`
- **THEN** the response is HTTP 200 and each row carries `execution_path` as a JSON array of strings (e.g. `["core","assistant"]`), matching the DSL endpoint's encoding for the same projection

#### Scenario: Empty array preserved

- **WHEN** a projected array cell is an empty ClickHouse array
- **THEN** the row carries the column key with a JSON `[]` value, not `null` and not a missing key

#### Scenario: Array misuse rejected per the translation contract

- **WHEN** a client posts `{ "sql": "SELECT request_id FROM dial_usage_log WHERE execution_path IS NULL" }`
- **THEN** the response is HTTP 400 `bad_request` with a message naming the array field and the projection-only restriction, and ClickHouse is not contacted

### Requirement: Object columns are selectable and returned as JSON objects

`POST /v1/queries/execute-sql` SHALL accept SQL that projects `object`-typed catalog fields
(per the `sql-query-translation` contract: projection-only, plus the document comparisons named
there) and SHALL return each object cell
as a native JSON value inside the row object — never as an escaped JSON string and never as a
Java-style `toString` rendering — exactly the encoding `POST /v1/queries/execute` produces for
the same projection. An empty object SHALL be returned as `{}`.

The endpoint SHALL additionally accept SQL that **filters** on an `object`-typed field with
`LIKE`, `ILIKE`, `NOT LIKE`, `NOT ILIKE`, `=`, `<>` or `IN` against character literals, executing it
with the canonical-JSON-document semantics the `structured-query` capability defines, and SHALL
return the matching rows. A comparison the contract still refuses — `IS NULL`/`IS NOT NULL`, an
ordering comparison, or `=`/`<>`/`IN` against a literal that is not a well-formed JSON document —
SHALL be rejected with HTTP 400 `bad_request` before ClickHouse is contacted.

#### Scenario: Object column projected via SQL

- **WHEN** a client posts `{ "sql": "SELECT request_id, jwt_claims FROM dial_usage_log LIMIT 5" }`
- **THEN** the response is HTTP 200 and each row carries `jwt_claims` as a native JSON object (e.g. `{"sub":"user-1","roles":["admin"]}`), matching the DSL endpoint's encoding for the same projection

#### Scenario: Object projection matches the DSL endpoint byte-for-byte

- **WHEN** a client runs a projection of the same columns through `POST /v1/queries/execute-sql` and the equivalent document through `POST /v1/queries/execute`
- **THEN** the two response bodies are identical

#### Scenario: Star projection over an entity with object columns succeeds

- **WHEN** a client posts `{ "sql": "SELECT * FROM dial_usage_log LIMIT 1" }` for an entity whose source columns include `object`-typed fields
- **THEN** the response is HTTP 200 and the object columns are present as native JSON values

#### Scenario: Object misuse rejected per the translation contract

- **WHEN** a client posts `{ "sql": "SELECT request_id FROM dial_usage_log WHERE jwt_claims IS NULL" }`
- **THEN** the response is HTTP 400 `bad_request` with a message naming the object field and the projection-only restriction, and ClickHouse is not contacted

#### Scenario: Object column filtered with ILIKE executes

- **WHEN** a client posts `{ "sql": "SELECT request_id FROM dial_usage_log WHERE request_tags ILIKE '%gpt-4%' LIMIT 5" }`
- **THEN** the response is HTTP 200 and carries exactly the rows whose `request_tags` document contains
  `gpt-4` case-insensitively in its canonical JSON text

#### Scenario: Object column filtered by document equality executes

- **WHEN** a client posts SQL comparing an `object` column with `=` against a well-formed JSON literal
  whose keys are written in an order different from the stored document's
- **THEN** the response is HTTP 200 and the matching rows are returned — document equality does not
  depend on key order

#### Scenario: Object column equality against a non-JSON literal rejected

- **WHEN** a client posts `{ "sql": "SELECT request_id FROM dial_usage_log WHERE jwt_claims = 'x'" }`
- **THEN** the response is HTTP 400 `bad_request` naming the object field and the JSON-literal
  requirement, and ClickHouse is not contacted

#### Scenario: Object column under an ordering comparison rejected

- **WHEN** a client posts SQL comparing an `object` column with `>` against a JSON literal
- **THEN** the response is HTTP 400 `bad_request` naming the object field and the projection-only
  restriction, and ClickHouse is not contacted

#### Scenario: SQL and DSL agree on a document filter

- **WHEN** a client runs an `ILIKE` filter on an `object` column through `POST /v1/queries/execute-sql`
  and the equivalent `ico` document through `POST /v1/queries/execute`
- **THEN** the two response bodies are identical

### Requirement: SQL execution inherits the query budget

The SQL endpoint SHALL execute its translated query under the same resource budget as the structured
query surface (see [structured-query](../structured-query/spec.md), "Query execution runs under a
resource budget"), and SHALL report a refusal with the same status and `error` code. The endpoint
translates SQL into the structured query and runs it through the same governed pipeline, so a ceiling
that bound only the DSL surface would leave the identical statement unbounded when written as SQL. The
same pipeline also applies the structured query's read shaping (late materialization of projection-only
heavy enrichment columns, and the enrichment-conjunct and scalar-call pushdowns), so a SQL statement and
its DSL equivalent cost the same.

#### Scenario: A SQL query exceeding a ceiling is refused

- **WHEN** a caller posts a `SELECT` whose execution exceeds the configured execution-time or memory
  ceiling
- **THEN** the response is HTTP 422 with `error` `query_budget_exceeded`, carrying the same message
  the DSL surface returns for that ceiling

#### Scenario: Selecting a heavy body column over an unbounded scan is refused

- **WHEN** a `FULL_ADMIN` caller posts
  `{ "sql": "SELECT count(*) FROM dial_usage_log WHERE \"dial_usage_log_payload.assembled_response\" ILIKE '%error%'" }`,
  reading a `heavy` column that no returned page bounds, with no predicate that prunes the partitions of
  either the source or the payload table it joins
- **THEN** the caller receives `query_budget_exceeded` naming the ceiling, rather than the connection
  failing with no response

#### Scenario: A projection-only body over an unbounded scan is fetched for the page

- **WHEN** a `FULL_ADMIN` caller posts
  `{ "sql": "SELECT event_id, \"dial_usage_log_payload.assembled_response\" FROM dial_usage_log WHERE chat_id = '…'" }`,
  which names the heavy column only in its projection and runs under the default page `LIMIT`
- **THEN** the body is read for the returned page's rows only, so the heavy column's cost does not grow with
  the unpruned scan; the scan of `dial_usage_log` that chooses the page still runs under the budget and is
  refused with `query_budget_exceeded` if it exceeds a ceiling

#### Scenario: The same query inside a bound succeeds

- **WHEN** the refused statement adds a `request_time` range that prunes to a small set of partitions
- **THEN** the query completes inside the budget and returns its count

#### Scenario: A self-join bounded on one side completes inside the budget

- **WHEN** a caller posts
  `SELECT b."usage_client_identity.user_email" AS parent_user, count(*) AS c FROM dial_usage_log a JOIN dial_usage_log b ON a.trace_id = b.trace_id AND a.core_parent_span_id = b.core_span_id WHERE a.request_time >= TIMESTAMP '2026-10-13 00:00:00' AND a.request_time < TIMESTAMP '2026-10-14 00:00:00' GROUP BY b."usage_client_identity.user_email" ORDER BY c DESC LIMIT 20`,
  where only the child side `a` is time-bounded
- **THEN** `a`'s window restricts `a`'s operand, `b` is reduced to the trace ids `a` can produce, and `b`'s
  enrichment join is keyed by that reduction (see structured-query, "A composite join's operand-only conjuncts
  restrict that operand" and "An equi-join operand is reduced to the keys its restricted partner can produce")
- **AND** the statement completes inside the budget with the rows the unreduced statement returns

### Requirement: Star projections omit heavy and enrichment columns

`POST /v1/queries/execute-sql` SHALL inherit the star expansion of the shared SQL front end (see the
`sql-query-translation` capability's "Star projection expands to the non-heavy source columns"): a
`SELECT *` response SHALL carry a row key per **non-heavy source** column and none for a `heavy` column
or an enrichment column (**BREAKING** — a star previously returned enrichment columns too), while a
statement that names a heavy or enrichment column SHALL return it unchanged. A star whose expansion
would be empty SHALL be rejected with HTTP 400 `bad_request` in the `ErrorView` shape, before any
ClickHouse contact.

The equivalence with the DSL endpoint SHALL hold for every entity, enrichments attached or not:
`SELECT *` and a row-mode document with no `select` SHALL return the same columns.

The two exclusions are independent and either alone suffices. On `dial_usage_log` the seeded heavy
columns are now enrichment columns as well, so a star omits them under either rule; the entity's
`object`-typed claim and header columns are source columns and are still expanded.

#### Scenario: Star response omits the heavy column

- **WHEN** a client posts `{ "sql": "SELECT * FROM <entity> LIMIT 1" }` as a caller entitled to see a
  **source** column of that entity marked `heavy`
- **THEN** the response is HTTP 200 and no row carries that column's key, while every non-heavy
  source column key is present

#### Scenario: Star over dial_usage_log omits every body column

- **WHEN** a `FULL_ADMIN` client posts `{ "sql": "SELECT * FROM dial_usage_log LIMIT 1" }`
- **THEN** the response is HTTP 200 and no row carries a `request_body`, `response_body`,
  `assembled_response`, `jwt_claims` or `request_tags` key under any spelling, while
  `request_body_bytes` and `response_body_bytes` are both present

#### Scenario: Named heavy column comes back

- **WHEN** the same caller posts
  `{ "sql": "SELECT event_id, \"dial_usage_log_payload.response_body\" FROM dial_usage_log LIMIT 1" }`
- **THEN** the response carries both keys with their values

#### Scenario: Star and empty DSL select agree

- **WHEN** a client posts `SELECT * FROM <source>` for a source with an attached enrichment, and the
  equivalent row-mode document with no `select` to `POST /v1/queries/execute`
- **THEN** both responses carry the same column keys — the non-heavy source columns, with no
  enrichment column in either

#### Scenario: Enrichment column returned when named

- **WHEN** a client posts `{ "sql": "SELECT event_id, \"deployment_ref.display_name\" FROM dial_usage_log LIMIT 1" }`
- **THEN** the response carries the enrichment column under its qualified flat name, with a JSON `null`
  where no enrichment row matches

### Requirement: Catalog metadata statements execute over REST

`POST /v1/queries/execute-sql` SHALL accept a statement against `INFORMATION_SCHEMA` and return its
rows in the standard response envelope, so the SQL surface is the same wherever ADAS accepts SQL and a
client is not forced onto Flight to read the catalog.

The response SHALL use the same shape as a data query — a `rows` array of column-name → value objects,
with `totalCount` omitted. Values SHALL be JSON strings for the character columns and JSON numbers for
`ORDINAL_POSITION` and the other integer columns, with `null` where the catalog has no value.

A metadata statement SHALL NOT be subject to the default page limit or the maximum-limit ceiling,
which govern ClickHouse execution; its result size is bounded by the catalog.

#### Scenario: Column listing over REST

- **WHEN** a client posts `SELECT COLUMN_NAME, DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'dial_usage_log'`
- **THEN** the response is `200` with one `rows` entry per field of that entity and no `totalCount` key

#### Scenario: Metadata statement returns more rows than the default limit

- **WHEN** a client posts an unbounded `SELECT` against `INFORMATION_SCHEMA.COLUMNS` whose result
  exceeds the default page limit
- **THEN** every row is returned rather than the result being limited or the request rejected

### Requirement: SQL validation endpoint agrees with execution without executing

`POST /v1/queries/validate-sql` SHALL report whether `POST /v1/queries/execute-sql` would accept a
statement, without executing it and without contacting ClickHouse. The request body SHALL be the same as
`execute-sql`'s — a JSON object with a single required `sql` field — and the endpoint SHALL be available
unconditionally, independent of `flight.enabled`.

The endpoint SHALL apply every check `execute-sql` applies before its ClickHouse round trip, over the
whole accepted surface: the statement screen (a single read-only `SELECT`), parse, star expansion,
entity and field validation against the discovered catalog schema, the closed function catalog,
translation to a `StructuredQuery` for a single-block statement or to a composite plan for one that
joins, reads a derived table or CTE, carries a subquery, or combines branches with a set operation, and
the row governance (default limit 100,
maximum limit 1000 on the outermost block). A statement reported valid SHALL therefore be one
`execute-sql` does not reject, and a statement reported invalid SHALL be one `execute-sql` rejects.
A statement against `INFORMATION_SCHEMA` SHALL be reported valid, since it is answered from ADAS's own
catalog and no governance applies to it.

Validity SHALL NOT be read as a promise that execution succeeds. The query budget
(`query.budget.*`) is enforced by ClickHouse as the statement runs and a breach is observable only then,
so a valid statement MAY still be refused at execution with `query_budget_exceeded`.

#### Scenario: A statement execute-sql accepts is reported valid

- **WHEN** a client posts `{ "sql": "SELECT event_kind FROM dial_usage_log LIMIT 10" }` to
  `/v1/queries/validate-sql`
- **THEN** the response is `200` with `valid` true and an empty `issues` array, and posting the same
  statement to `/v1/queries/execute-sql` returns `200`

#### Scenario: A composite statement is reported valid

- **WHEN** a client posts a statement joining two queryable entities with an equality in `ON`
- **THEN** the response is `200` with `valid` true — the composite surface `execute-sql` executes is
  validated, not only the DSL-expressible subset

#### Scenario: A set-operation statement is reported valid

- **WHEN** a client posts `{ "sql": "SELECT model FROM dial_usage_log UNION ALL SELECT model FROM rate_analytics" }`
- **THEN** the response is `200` with `valid` true and an empty `issues` array — superseding the
  verdict that reported it invalid with an unsupported-construct issue advising the branches be run as
  separate statements

#### Scenario: A set operation with mismatched branches is reported invalid

- **WHEN** a client posts `{ "sql": "SELECT model FROM dial_usage_log UNION ALL SELECT model, rate FROM rate_analytics" }`
- **THEN** the response is `200` with `valid` false and an issue naming the branch column-count
  mismatch, and posting the same statement to `/v1/queries/execute-sql` returns `400`

#### Scenario: A bare UNION is reported invalid

- **WHEN** a client posts `{ "sql": "SELECT model FROM dial_usage_log UNION SELECT model FROM rate_analytics" }`
- **THEN** the response is `200` with `valid` false and an issue stating that `UNION` requires `ALL` or
  `DISTINCT`, matching the rejection `/v1/queries/execute-sql` gives it

#### Scenario: An over-maximum limit is reported invalid

- **WHEN** a client posts `{ "sql": "SELECT event_kind FROM dial_usage_log LIMIT 50000" }`
- **THEN** the response is `200` with `valid` false and an issue naming the requested limit and the
  1000-row maximum, and posting the same statement to `/v1/queries/execute-sql` returns `400`

#### Scenario: A catalog metadata statement is reported valid

- **WHEN** a client posts `{ "sql": "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS" }`
- **THEN** the response is `200` with `valid` true and no `effective_limit`

#### Scenario: ClickHouse is never contacted

- **WHEN** a client posts any statement — valid, invalid, composite or metadata — to
  `/v1/queries/validate-sql`
- **THEN** no query is issued to ClickHouse, and the endpoint answers even while ClickHouse is
  unreachable

### Requirement: Validation reports a verdict, and a malformed request is the only error

`POST /v1/queries/validate-sql` SHALL answer `200` for every well-formed request, whether the statement
is valid or not, with a JSON object carrying `valid` (boolean), `effective_limit`, `issues` and
`warnings`. An invalid statement SHALL NOT be reported as an `ErrorView`: the verdict is the endpoint's
result, and the response carries a list of problems that the single-`message` `ErrorView` contract cannot
express.

A malformed **request** SHALL still follow the service-wide contract: a missing, null or blank `sql`, or
an unreadable JSON body, SHALL be rejected with `400` `bad_request` in the `ErrorView` shape, and no
validation SHALL occur. A catalog read failure SHALL surface as `503` `postgres_unavailable`, as
elsewhere on the query surface. The endpoint SHALL NOT return `422`, since it never executes.

`effective_limit` SHALL carry the outermost row ceiling execution would apply — the statement's own
`LIMIT`, or the default where it carries none — and SHALL be absent for an invalid statement and for a
catalog-metadata statement.

Each entry of `issues` and `warnings` SHALL carry a stable machine-readable `code`, the underlying
diagnosis verbatim as `message`, 1-based `line` and `column` where the diagnosis carries a position, and
a `remedy` where the classification implies a concrete fix. A diagnosis the endpoint does not recognise
SHALL be reported with the code `invalid_sql`, its message intact and no `remedy` — never with a
guessed classification. `issues[0]` SHALL be the rejection `execute-sql` itself would return.

#### Scenario: An invalid statement is HTTP 200

- **WHEN** a client posts `{ "sql": "SELECT no_such_column FROM dial_usage_log" }`
- **THEN** the response is `200` with `valid` false and at least one issue, not a `400` `ErrorView`

#### Scenario: Blank sql is a bad request

- **WHEN** a client posts `{ "sql": "  " }` (or omits the `sql` field)
- **THEN** the response is `400` `bad_request` in the `ErrorView` shape, with no `valid` key, and no
  validation occurs

#### Scenario: A positionless diagnosis omits its position

- **WHEN** the reported issue is a governance rejection rather than a token-level one
- **THEN** the issue carries its `code` and `message` and the `line` and `column` keys are absent

#### Scenario: The effective limit reports the applied default

- **WHEN** a client posts a valid statement carrying no `LIMIT`
- **THEN** `effective_limit` is 100, and a statement carrying `LIMIT 25` reports 25

### Requirement: Validation reports every unresolvable identifier it can establish

`POST /v1/queries/validate-sql` SHALL, for a statement rejected for an unresolvable name, report the
remaining unresolvable entity and column names in the same response, so a statement misspelling several
names need not be corrected one request at a time. Where a catalog name is close to the submitted one,
the issue's `remedy` SHALL name it.

Reporting SHALL be conservative: a name SHALL be reported only when no entity the statement reads could
own it, and a name the statement itself declares — a table alias, a CTE name, a select alias, a derived
table's output column — SHALL NOT be reported. Where no entity of the statement resolves, only the
entity issues SHALL be reported and no column issue, since no set of legal columns exists to test
against. A statement that does not parse SHALL report its parse failure alone.

#### Scenario: Several unknown columns are reported together

- **WHEN** a client posts `{ "sql": "SELECT nope_one, nope_two FROM dial_usage_log WHERE nope_three = 'x'" }`
- **THEN** the response is `200` with `valid` false and an issue for each of the three names

#### Scenario: The closest catalog name is offered

- **WHEN** a client posts a statement naming `event_kindd` on an entity exposing `event_kind`
- **THEN** the issue's `remedy` names `event_kind`

#### Scenario: A statement-declared name is not reported

- **WHEN** a client posts a statement using a table alias, a select alias reused in `ORDER BY`, a CTE, or
  a derived table's output column
- **THEN** none of those names is reported as unresolvable

#### Scenario: An unknown entity suppresses column reporting

- **WHEN** a client posts `{ "sql": "SELECT anything FROM no_such_entity" }`
- **THEN** the only issue is the unknown entity — the columns are not reported, because nothing resolves
  that could own them

### Requirement: Validation warns about the shapes the query budget refuses

`POST /v1/queries/validate-sql` SHALL report advisory `warnings` for a statement that passes every check
and is nonetheless likely to be refused at execution, and for the default limit being applied. A warning
SHALL NOT affect `valid`. Warnings are heuristics on the translated query, not verdicts: the query budget
is enforced as the statement runs, so a warned statement MAY execute successfully and an unwarned one MAY
still breach the budget.

The endpoint SHALL warn when the statement carries no outer `LIMIT`, naming the default that will be
applied; when it references a `heavy` column with no filter on a `timestamp` field of the entity, so the
read is not pruned to a few partitions; and when it aggregates with a `GROUP BY` and no such filter,
since a high-cardinality grouping builds one accumulator per group. The heavy-column warning SHALL fire
for a heavy enrichment column named by its qualified flat name as it does for a heavy source column: the
flag is carried into the query model by the column, not by which table owns it. A composite statement
SHALL receive only the limit warning: its blocks reference relation columns rather than a single entity's
fields, so a heavy or timestamp column cannot be attributed to a catalog binding with certainty.

#### Scenario: An unbounded heavy-column read is warned about

- **WHEN** a client posts
  `{ "sql": "SELECT event_id, \"dial_usage_log_payload.assembled_response\" FROM dial_usage_log WHERE chat_id = '…' LIMIT 10" }`
- **THEN** the response is `200` with `valid` true and a warning naming the heavy column and the missing
  time bound

#### Scenario: A time bound removes the warning

- **WHEN** the same statement adds a range filter on `request_time`
- **THEN** the response carries no heavy-column warning

#### Scenario: The default limit is reported as a warning

- **WHEN** a client posts a valid statement with no `LIMIT`
- **THEN** the response carries a warning naming the default limit, and a statement with an explicit
  `LIMIT` carries none

#### Scenario: A composite statement receives only the limit warning

- **WHEN** a client posts a valid join carrying no outer `LIMIT`
- **THEN** the only warning is the default-limit one

### Requirement: Validation resolves against the caller's own column visibility

`POST /v1/queries/validate-sql` SHALL resolve entities and fields through the same catalog path as
execution, so a `sensitive` column absent from the model of a caller without `TABLE_SENSITIVE` on
its owning table SHALL be reported as an
unresolvable column rather than confirmed to exist. This is the same answer `execute-sql` gives that
caller, and it is required: a validator that resolved names the caller cannot query would disclose the
column's existence.

#### Scenario: A sensitive column does not resolve for a read-only admin

- **WHEN** a caller holding `TABLE_GET` but not `TABLE_SENSITIVE` posts a statement naming a
  `sensitive` column to `/v1/queries/validate-sql`
- **THEN** the response is `200` with `valid` false and an unresolvable-column issue — not a `403`, and
  not a valid verdict

#### Scenario: Security inherits the /v1 policy

- **WHEN** `security.mode=oidc` and a client posts to `/v1/queries/validate-sql` without credentials
- **THEN** the response is `401`, and with `TABLE_GET` on every resolved table the request is served —
  no
  endpoint-specific rule applies

### Requirement: Ad-hoc SQL requires TABLE_READ on every resolved table

`POST /v1/queries/execute-sql` SHALL require `TABLE_READ` — held globally or granted on the table —
for **every** table the statement resolves, including each enrichment the flattened entity reads.
A caller lacking it on any resolved table SHALL be refused as though that table did not exist: the
statement SHALL fail as referring to an unknown relation or column with `400 bad_request`, never
`403`.

No separate privilege SHALL gate ad-hoc SQL as distinct from the structured DSL: both resolve through
the same governed catalog model and the same chokepoint, so a privilege that admitted one and refused
the other would describe no difference in what is disclosed.

#### Scenario: A statement over a readable table executes

- **GIVEN** a source table whose `read` list contains the caller's provider role
- **WHEN** the caller posts a statement selecting from that entity
- **THEN** the statement resolves and executes normally

#### Scenario: A statement over an unreadable table is unknown

- **WHEN** the caller posts a statement selecting from a table it holds no read privilege on
- **THEN** the response is `400 bad_request` reporting an unknown relation, and does not reveal that
  the table exists

#### Scenario: A join across a readable and an unreadable table is refused

- **WHEN** the caller posts a statement joining a readable entity to one it may not read
- **THEN** the response is `400 bad_request` reporting the unreadable side as unknown

### Requirement: SQL validation requires TABLE_GET on every resolved table

`POST /v1/queries/validate-sql` SHALL require `TABLE_GET` — not `TABLE_READ` — on every table the
statement resolves, matching the translation endpoints rather than `execute-sql`: it resolves the
statement against the catalog model and never contacts ClickHouse, so it discloses schema rather than
data.

A table the caller lacks `TABLE_GET` on SHALL be absent from the model it validates against, so the
statement is reported as naming an unresolvable relation through this endpoint's ordinary `200` diagnostic
shape rather than as an authorization failure. Sensitive columns SHALL remain governed by
`TABLE_SENSITIVE` on the owning table, and a statement referencing one SHALL be reported as naming
an unresolvable column.

#### Scenario: Validation needs only schema visibility

- **GIVEN** a table whose `get` list contains the caller's provider role and whose `read` list does not
- **WHEN** the caller validates a statement selecting from that entity
- **THEN** the statement is reported as valid
- **AND WHEN** the caller posts the same statement to `/v1/queries/execute-sql`
- **THEN** the response reports an unknown relation

#### Scenario: A table below the bar is unresolvable, not forbidden

- **WHEN** the caller validates a statement naming a table it holds no read-side privilege on
- **THEN** the response is `200` reporting the relation as unresolvable, and does not reveal that the
  table exists

### Requirement: Function validation remedies name the SQL function catalog

A `POST /v1/queries/validate-sql` issue classified as one of the function-catalog codes SHALL carry a
`remedy` naming `GET /v1/queries/sql-functions`, the catalog of spellings this surface accepts, and
SHALL NOT name `GET /v1/queries/functions`. The codes are:

- `unknown_function`: no function of that signature exists;
- `unsupported_function`: the function validated but has no lowering;
- `function_arity`: the argument count is wrong.

The DSL catalog lists names this surface refuses (`plus`, `minus`, `multiply`, `div`, `modulo`) and
signatures it cannot parse (`percentile_cont(fraction, column)`). So pointing a SQL caller at it
would send them to the source of the mistake.

The codes themselves and the rest of each issue are unchanged.

#### Scenario: An unknown function points at the SQL catalog

- **WHEN** a client validates `SELECT plus(total_tokens, 1) AS n FROM dial_usage_log`
- **THEN** the verdict is invalid with an issue of code `unknown_function` whose `remedy` names
  `GET /v1/queries/sql-functions`

#### Scenario: A wrong arity points at the SQL catalog

- **WHEN** a client validates `SELECT count() AS n FROM dial_usage_log`
- **THEN** the issue's code is `function_arity` and its `remedy` names `GET /v1/queries/sql-functions`
  and `count(*)`

## Implementation notes

- Validation endpoint: `web.controller.query.SqlValidationController`
  (`POST /v1/queries/validate-sql`), sharing `SqlQueryRequestDto`; response DTOs
  `web.dto.query.SqlValidationResponseDto` and `SqlIssueDto`. It reuses the execute path rather than
  restating its rules: `service.query.sql.SqlQueryFrontEnd.plan(...)` then
  `service.query.StructuredQueryService.validate(...)`, the latter being the executor's own
  `StructuredQueryBuilder` pass with the built statement discarded — so the only stage it omits is the
  ClickHouse call. `service.query.sql.SqlIssueClassifier` maps a rejection message to a stable `code`
  and remedy (unrecognised messages fall back to `invalid_sql` with the message intact), and
  `SqlIdentifierProbe` enumerates the remaining unresolvable identifiers on the failure path only.
- Endpoint: `com.epam.aidial.analytics.web.controller.query.SqlQueryController`
  (`POST /v1/queries/execute-sql`); request DTO `web.dto.query.SqlQueryRequestDto`
  (`{ "sql": "..." }`, `@NotBlank` — an object, not a bare string, so future fields such as a
  total-count opt-in stay non-breaking).
- Execution composes the existing components unchanged: `service.query.sql.SqlQueryFrontEnd`
  (Calcite screen → parse → validate → translate; shared with Flight) then
  `service.query.StructuredQueryService.execute(...)`; the response reuses
  `web.dto.query.StructuredQueryResultDto`.
- Error mapping: `SqlTranslationException` → 400 `bad_request` via
  `web/handler/DefaultExceptionHandler` (echoes the translation message — it names only the
  offending construct or catalog entities the caller can already discover). All other failure
  modes reuse existing handlers.
- A null `totalCount` is omitted from the JSON by the shared mapper's NON_NULL value inclusion
  (`configuration.JsonMapperConfiguration`) — same wire behavior as the DSL endpoint; row-map
  null cells are kept by content inclusion ALWAYS.
- No configuration: the endpoint is unconditional; `flight.*` governs only the gRPC listener.
- Coverage: `web/controller/query/SqlQueryControllerTest` (`@WebMvcTest` slice) and
  `integration/SqlQueryExecutionIntegrationTest` (Testcontainers ClickHouse + Postgres,
  `flight.enabled=false`; REST-SQL vs DSL equivalence, limit governance, fail-closed
  rejection, enrichment null-cell preservation).
