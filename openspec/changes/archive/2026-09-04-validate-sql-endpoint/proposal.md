## Why

Assistants that compose SQL against ADAS — the `adas-mcp` toolset's callers — hand their users
statements that fail on first execution, and the reason is structural rather than a matter of prompting.

**They cannot test a statement.** `adas_execute_sql` registers only under `ADAS_CAPABILITIES=full`,
because it is the one tool that reaches ClickHouse. A read-only deployment, which is the right posture
for an assistant, therefore has no SQL tool at all: it is writing SQL blind and finding out whether it
parsed only when a human runs it.

**The one endpoint that looks like the answer is not.** `POST /v1/queries/translate-sql` never contacts
ClickHouse and would be the natural pre-flight check, but it is unfit for the job twice over. It
**rejects composite statements** — joins, CTEs, subqueries — that `execute-sql` accepts and executes
since `extend-sql-surface`, so every join would be reported as broken. And despite its own OpenAPI text
and this repo's spec claiming "the same governance … maximum limit 1000", it **runs no
`StructuredQueryBuilder` pass**: `QueryTranslationController.translateSql` calls
`SqlQueryFrontEnd.translate` and nothing else, while the row governance lives in
`StructuredQueryBuilder.validateLimit`. `SELECT … LIMIT 50000` is answered `200` by `translate-sql` and
`400` by `execute-sql`, and the emitted document is one `/v1/queries/execute` then rejects — which
contradicts that endpoint's contract that its output is executable as returned.

**A rejection carries one problem, as prose.** `ErrorView` has a single `message`, and validation is
first-failure-wins, so a statement with three misspelled columns costs three round trips. Several of the
messages also point away from the mistake: a column the select list names and nothing resolves is
reported as *"The definition of column 'x' depends on itself"* (it describes ADAS's own injected alias,
not the caller's error); an `IN` list past Calcite's sub-query threshold is reported as *"SQL construct
'LogicalValues' is not supported"*, naming a construct the caller never wrote; `count()` is an arity
error about `COUNT`; and an unquoted reserved-word alias is reported at the position of the preceding
token.

No new validation engine is needed to fix any of this. `SqlQueryFrontEnd.plan` already performs the
whole `execute-sql` surface check with no ClickHouse contact, and the only remaining stage is
`StructuredQueryBuilder.build`, which runs inside `StructuredQueryExecutor.execute` one statement before
the wire call. The work is to expose that boundary and report what it finds as a list.

## What Changes

- **New endpoint `POST /v1/queries/validate-sql`.** Takes the same body as `execute-sql`
  (`{"sql": "…"}`) and returns `{valid, effective_limit, issues[], warnings[]}`. It runs every check
  `execute-sql` runs except the ClickHouse round trip, over the whole accepted surface — single-block,
  composite and `INFORMATION_SCHEMA` alike — so a statement it calls valid is one `execute-sql` will not
  reject.
- **A well-formed request is always `200`**, whether the statement is valid or not. This is a deliberate
  departure from the service-wide rule that a caller error is an `ErrorView`: that rule exists so a
  failure is never mistaken for a result, and on this endpoint the failure *is* the result. A malformed
  request — missing, null or blank `sql`, unreadable JSON — stays `400 bad_request`.
- **Issues carry a stable `code`, the verbatim diagnosis, a position where one exists, and a `remedy`
  where the classification implies one.** An unrecognised diagnosis is reported as `invalid_sql` with its
  message intact and no advice, so a reworded upstream message degrades to what the caller would have
  read from `execute-sql` rather than to a wrong code.
- **Every unresolvable identifier is reported in one call.** After the pipeline rejects a statement, a
  parse-only probe enumerates the remaining unknown entities and columns and offers the closest catalog
  name. It runs on the failure path only, and reports a name solely when no entity in the statement could
  own it.
- **Warnings for the shapes that pass every check and are still refused at execution** — an unbounded
  heavy-column read, an unbounded high-cardinality `GROUP BY` — plus the default limit being applied.
  Warnings never affect `valid`; the query budget is enforced by ClickHouse as the statement runs, so no
  pre-flight check can be exact.
- **`translate-sql` gains the governance pass it already claims.** BREAKING in one direction: statements
  it used to answer `200` are now `400` (most visibly `LIMIT` above the maximum). It now matches its own
  shipped spec, and its output is executable as returned.

Unchanged: the accepted SQL grammar, `execute-sql`'s own behaviour, every existing `error` code, the
`/v1/**` authorization policy, and column-level sensitive masking — which the new endpoint inherits by
resolving through `CatalogQueryService`, so a caller who may not see a `sensitive` column is told the
column does not resolve, exactly as execution tells them.

## Capabilities

### New Capabilities

None. The validate endpoint is defined as "everything `execute-sql` does except the ClickHouse call", so
it belongs with that contract rather than in a near-duplicate capability of its own.

### Modified Capabilities

- `rest-sql-endpoint`: gains the validate endpoint — its agreement guarantee, response envelope, the
  request-versus-statement error split, and the advisory nature of warnings.
- `query-translation-endpoint`: the `translate-sql` requirement gains the row governance it already
  described, and the scenario that pins it.

## Impact

**Endpoints** (`/v1`):

| Method | Path | Change |
|---|---|---|
| POST | `/v1/queries/validate-sql` | **new** — `200` verdict; `400` only for a malformed request |
| POST | `/v1/queries/translate-sql` | now applies the row governance; over-maximum `LIMIT` becomes `400` |

- **Code**: `service/query/sql/` — new `SqlValidationService`, `SqlValidation`, `SqlIssue`,
  `SqlIssueClassifier`, `SqlIdentifierProbe`; `SqlQueryFrontEnd.PARSER_CONFIG` widened to
  package-visible so the probe parses with the same grammar. `service/query/StructuredQueryService` and
  `service/query/repository/StructuredQueryExecutor` gain `validate` siblings to their `execute`
  overloads. `web/controller/query/SqlValidationController` (new),
  `web/controller/query/QueryTranslationController` (governance pass),
  `web/dto/query/SqlValidationResponseDto` and `SqlIssueDto` (new).
- **Errors**: no new codes and no `DefaultExceptionHandler` change — the endpoint reports rather than
  throws, and its `400` is the existing bean-validation mapping.
- **Dependencies / config / migrations**: none. No new env var, no schema change, no new credential, no
  new ClickHouse or Postgres grant.
- **Docs**: `docs/sql-reference.md` (how to check a statement), `README.md` (the SQL surface list). No
  `docs/configuration.md` change — no config property is added or renamed.
- **Tests**: `SqlIssueClassifierTest` (the code table, pinned to captured real messages),
  `SqlIdentifierProbeTest` (chiefly the silences — the cases where the probe must find nothing),
  `SqlValidationServiceTest` (the real front end, executor and builders, with a ClickHouse client that
  every case leaves untouched), `integration/SqlValidationIntegrationTest` (for each statement, the
  verdict versus what `execute-sql` actually does, plus the envelope, the positionless-versus-positioned
  issue, and the invalid-statement-200 / malformed-request-400 split), and a governance scenario in `QueryTranslationControllerTest`.
- **Not affected**: the Flight SQL surface (no validation endpoint there — `web/flight/` is a gRPC
  listener, not a servlet path), saved queries, the DSL endpoint, the enrichment and pipeline paths.
- **Downstream**: `adas-mcp` adds an `adas_validate_sql` tool in its always-on read-only group and
  regenerates its OpenAPI snapshot. Without that the endpoint changes nothing for the assistant it exists
  to serve.
