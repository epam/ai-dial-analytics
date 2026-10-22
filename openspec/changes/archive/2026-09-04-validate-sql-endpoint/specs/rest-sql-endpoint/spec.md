## ADDED Requirements

### Requirement: SQL validation endpoint agrees with execution without executing

`POST /v1/queries/validate-sql` SHALL report whether `POST /v1/queries/execute-sql` would accept a
statement, without executing it and without contacting ClickHouse. The request body SHALL be the same as
`execute-sql`'s — a JSON object with a single required `sql` field — and the endpoint SHALL be available
unconditionally, independent of `flight.enabled`.

The endpoint SHALL apply every check `execute-sql` applies before its ClickHouse round trip, over the
whole accepted surface: the statement screen (a single read-only `SELECT`), parse, star expansion,
entity and field validation against the discovered catalog schema, the closed function catalog,
translation to a `StructuredQuery` for a single-block statement or to a composite plan for one that
joins, reads a derived table or CTE, or carries a subquery, and the row governance (default limit 100,
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
since a high-cardinality grouping builds one accumulator per group. A composite statement SHALL receive
only the limit warning: its blocks reference relation columns rather than a single entity's fields, so a
heavy or timestamp column cannot be attributed to a catalog binding with certainty.

#### Scenario: An unbounded heavy-column read is warned about

- **WHEN** a client posts `{ "sql": "SELECT event_id, assembled_response FROM dial_usage_log WHERE chat_id = '…' LIMIT 10" }`
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
execution, so a `sensitive` column absent from a non-`FULL_ADMIN` caller's model SHALL be reported as an
unresolvable column rather than confirmed to exist. This is the same answer `execute-sql` gives that
caller, and it is required: a validator that resolved names the caller cannot query would disclose the
column's existence.

#### Scenario: A sensitive column does not resolve for a read-only admin

- **WHEN** a `READ_ONLY_ADMIN` posts a statement naming a `sensitive` column to `/v1/queries/validate-sql`
- **THEN** the response is `200` with `valid` false and an unresolvable-column issue — not a `403`, and
  not a valid verdict

#### Scenario: Security inherits the /v1 policy

- **WHEN** `security.mode=oidc` and a client posts to `/v1/queries/validate-sql` without credentials
- **THEN** the response is `401`, and with `FULL_ADMIN` or `READ_ONLY_ADMIN` the request is served — no
  endpoint-specific rule applies
