## MODIFIED Requirements

### Requirement: SQL translates to the structured query DSL without execution

`POST /v1/queries/translate-sql` SHALL accept the same request body as
`POST /v1/queries/execute-sql` (`{"sql": "<one read-only SELECT>"}`) and SHALL translate the
statement to the structured query DSL using exactly the mapping contract of the
`sql-query-translation` capability — same supported subset, same catalog validation, same
rejections. The endpoint SHALL NOT execute the query and SHALL NOT contact ClickHouse. On
success it SHALL return `200` with `{"query": <DSL document>}`, where the DSL document is
accepted verbatim by `POST /v1/queries/execute` and executes with semantics equivalent to the
submitted SQL. A select entry SHALL omit `as` only where omitting it leaves the whole select list
resolving to the same output names, so an unnamed SQL expression translates to an alias-free select
entry that still executes to the same column name, while an alias the DSL's naming would not
reproduce in place is always carried. Translation failures SHALL
return the same `ErrorView` errors as `execute-sql` (400 `bad_request` naming the offending
construct or unknown identifier).

Validation SHALL include the **row governance** the execute path applies, not only the catalog and
mapping checks the translation itself performs: the endpoint SHALL run the same
`StructuredQueryBuilder` pass `POST /v1/queries/execute` runs — field allowlist, function catalog, mode
coherence, and the default limit of 100 with the maximum of 1000 — and discard its result, so a document
this endpoint returns is one `POST /v1/queries/execute` accepts. A statement whose `LIMIT` exceeds the
maximum SHALL therefore be rejected with 400 `bad_request` naming the requested limit and the maximum,
rather than answered with a document that execution then rejects. (Previously the endpoint ran only the
SQL front end, so the governance it already described was not in fact applied and an over-maximum `LIMIT`
was answered `200`.) A caller needing the accept/reject verdict for the whole `execute-sql` surface —
composite statements included — SHALL use `POST /v1/queries/validate-sql` (see
[rest-sql-endpoint](../rest-sql-endpoint/spec.md)); this endpoint's product is the DSL document, and it
rejects whatever has no document.

A **composite** statement — one that joins, reads a derived table or CTE, or carries a subquery — is
accepted and executed by `execute-sql` but has no `StructuredQuery` equivalent; this endpoint SHALL reject
it with 400 `bad_request` and the message `statements with joins, derived tables or subqueries execute but
have no structured-query equivalent`, without contacting ClickHouse. A single-block statement using `CASE`,
arithmetic or a window expression is not composite and SHALL translate.

#### Scenario: Aggregate SQL returns its DSL document

- **WHEN** a client posts `{"sql": "SELECT model, count(*) AS n FROM dial_usage_log GROUP BY model"}` to `/v1/queries/translate-sql`
- **THEN** the response is `200` with a `query` object having `entity="dial_usage_log"`, `mode="aggregate"`, `group_by=["model"]`, and a `count` fn select entry aliased `n` — and no query was executed

#### Scenario: Unsupported SQL rejected without execution

- **WHEN** a client posts `{"sql": "SELECT event_id FROM dial_usage_log UNION ALL SELECT response_id FROM rate_analytics"}`
- **THEN** the response is a 400 `ErrorView` with code `bad_request` naming the unsupported construct, and ClickHouse is not contacted

#### Scenario: Composite statement has no DSL document

- **WHEN** a client posts `{"sql": "SELECT a.x FROM t1 a JOIN t2 b ON a.id = b.id"}` over two queryable entities
- **THEN** the response is a 400 `ErrorView` with code `bad_request` and the composite-statement message, and ClickHouse is not contacted

#### Scenario: Translated document is executable

- **WHEN** the `query` object returned by `/v1/queries/translate-sql` is posted unchanged to `/v1/queries/execute`
- **THEN** it deserializes and validates successfully and runs with the same semantics as posting the original SQL to `/v1/queries/execute-sql`

#### Scenario: Unnamed SQL expression translates without an alias

- **WHEN** a client posts `{"sql": "SELECT model, count(*) FROM dial_usage_log GROUP BY model"}` to `/v1/queries/translate-sql`
- **THEN** the `query` object's `count` select entry carries no `as` key, and posting the document to
  `/v1/queries/execute` returns rows keyed `model` and `count`

#### Scenario: CASE statement translates to a case expression

- **WHEN** a client posts `{"sql": "SELECT CASE WHEN success THEN 1 ELSE 0 END AS ok FROM dial_usage_log"}` to `/v1/queries/translate-sql`
- **THEN** the response is `200` and the select entry is a `case` expression aliased `ok`

#### Scenario: Limit above the maximum rejected rather than translated

- **WHEN** a client posts `{"sql": "SELECT event_id FROM dial_usage_log LIMIT 50000"}` to `/v1/queries/translate-sql`
- **THEN** the response is a 400 `ErrorView` with code `bad_request` naming the requested limit and the
  1000-row maximum, and no `query` document is returned — the same answer `/v1/queries/execute-sql` gives
