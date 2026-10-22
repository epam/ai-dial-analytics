## Why

`UNION`, `INTERSECT` and `EXCEPT` are the last relational constructs the SQL surface refuses outright:
`extend-sql-surface` lifted joins, CTEs, derived tables, subqueries, `CASE` and windows but deliberately
left set operations as a follow-up, rejected at two sites with "set operations (UNION, INTERSECT, EXCEPT)
are not supported". Stitching two entities' rows together is the most ordinary analytical shape there is
— "requests from the usage log plus the rate table", "models present yesterday but not today" — and today
the only answer is "run the branches as separate statements and merge them yourself", which no BI tool
does. ClickHouse supports every form natively, so the gap is entirely in ADAS's front end.

The change is governed by one rule: **a user who knows ClickHouse writes a ClickHouse query, and ADAS
executes it identically.** For set operations that rule is not a preference between two tidy conventions —
ClickHouse and standard SQL disagree about a bare `INTERSECT`, about a bare `UNION`, and about what a
trailing `ORDER BY` even applies to, and in each case the difference is *silent*: the same text returns
different rows. So the semantics ADAS implements are ClickHouse's, measured against the server rather
than derived from the standard, and where ADAS cannot reproduce ClickHouse's reading it refuses the
statement instead of executing the other one. PostgreSQL compatibility is not a goal of this surface.

## What Changes

- **Set operations execute on the SQL surface.** `POST /v1/queries/execute-sql`, Flight SQL and
  `POST /v1/queries/validate-sql` accept `UNION ALL`, `UNION DISTINCT`, `INTERSECT`, `INTERSECT ALL`,
  `INTERSECT DISTINCT`, `EXCEPT`, `EXCEPT ALL` and `EXCEPT DISTINCT`, at the top level, inside a CTE body,
  inside a derived table and inside a subquery alike, with any number of branches and any accepted block
  shape per branch (aggregation, `DISTINCT`, joins, its own `ORDER BY`/`LIMIT`, a nested set operation).
- **The unqualified operators take ClickHouse's default modes, not the standard's.** A bare `INTERSECT`
  means `INTERSECT ALL` and a bare `EXCEPT` means `EXCEPT ALL` — measured: `intersect_default_mode` and
  `except_default_mode` both default to `ALL`, where the SQL standard and Calcite read both as `DISTINCT`.
  A bare `UNION` is **rejected** naming the required keyword, because `union_default_mode` defaults to
  empty and ClickHouse refuses that statement itself. The executed statement always carries the keyword
  explicitly, so a reconfigured server cannot change the result of a statement ADAS accepted.
- **`MINUS` is rejected**, naming `EXCEPT`. ClickHouse has no such keyword — verified, it answers a syntax
  error — so accepting Oracle's synonym would succeed where the user's own query fails.
- **A trailing `ORDER BY`/`LIMIT`/`OFFSET` binds to the last branch**, as ClickHouse binds it, not to the
  combined result as standard SQL does. Measured: `SELECT 5 UNION ALL SELECT <3 rows> ORDER BY x LIMIT 2`
  returns `5, 1, 2` on ClickHouse — the first branch untouched, the last one sorted and truncated — where
  standard SQL returns `1, 2`. ADAS reproduces that by re-associating the clause into the last branch
  before validation. A caller wanting the combined result ordered writes
  `SELECT * FROM (<set operation>) ORDER BY …`, which means the same thing on both.
- **Branch compatibility is the validator's**: equal column counts, positional matching, output names from
  the first branch, each result column typed as the unified branch type. Where the validator cannot unify
  two branch types the statement is a 400 naming the mismatch — narrower than ClickHouse, which searches
  harder for a supertype. This is the one place the surface is deliberately stricter than the database:
  refusing a statement ClickHouse would have run keeps the rule intact, executing it under a different
  unification would break it.
- **Governance is unchanged and covers the combined result.** The combined result keeps the default limit
  of 100 and the 1000-row maximum over REST and the `flight.max-rows` clamp-and-probe over Flight; branch
  limits — including one written after the last branch — execute as written. Every branch entity is
  resolved through the catalog, keeps its own enrichment joins and wildcard rules, and sensitive columns
  stay invisible in every branch. `final = 1`, `join_use_nulls = 1` and the `query.budget.*` ceilings cover
  the whole statement.
- **Flight SQL advertises unions.** `SQL_SUPPORTED_UNIONS` stops reporting the empty bitmask and declares
  `SQL_UNION` and `SQL_UNION_ALL`. Three neighbouring declarations are **deliberately left as they are**
  and recorded in the spec as inherited under-claims: `SQL_SUPPORTED_SUBQUERIES` and
  `SQL_OUTER_JOIN_SUPPORT_LEVEL` stay empty and `SQL_MAX_TABLES_IN_SELECT` stays `1`, all three already
  wrong after `extend-sql-surface` shipped joins and subqueries. Correcting them is one derivation across
  all three, none of them blocks a statement from executing, and doing it here would widen this change
  past its subject. Naming them in the spec is what keeps them from being inherited silently again.
- **`validate-sql` reclassifies.** The `unsupported_construct` rule that matched the set-operation refusal
  (remedy: "run the branches as separate statements") is removed with the refusal it described; a set
  operation validates as `valid: true`, and a bare `UNION` or an incompatible branch list as `valid: false`
  with the matching issue.
- **Set operations have no DSL document.** `POST /v1/queries/execute` is unchanged — the structured query
  DSL stays single-entity, exactly as it is for joins — so `POST /v1/queries/translate-sql` answers 400
  for a set-operation statement with the existing "cannot be expressed in the structured query DSL"
  wording. `POST /v1/queries/translate` is unchanged.
- **Still rejected**, each with an error naming the construct: a set operation over `INFORMATION_SCHEMA`
  relations (the in-process catalog evaluator has no set-operation support) and one mixing a catalog
  relation with a data entity; `WITH RECURSIVE`, whose body is a `UNION ALL` over a self-reference; branch
  lists whose column counts or types do not unify; ClickHouse's `SELECT * EXCEPT (col)` column-exclusion
  syntax, which is not the set operator and does not parse here.

**What the no-divergence rule does *not* claim.** Three surface properties still make ADAS's answer differ
from a raw `clickhouse-client` session, and all three predate this change and apply to every statement,
not to set operations: the row cap (an unbounded query returns 100 rows, not all of them), `final = 1`
(reads deduplicate by default), and `join_use_nulls = 1` on composite statements (an unmatched outer-join
column reads NULL rather than its type default). A set operation inherits all three unchanged. Rule-level
fidelity is about what the *operators mean*, not about removing the governance the surface exists to apply.

No **BREAKING** changes: every statement accepted today is accepted with the same result, and the DSL wire
contract does not move. Only previously rejected statements change outcome, plus the two declarations that
described the rejection (`SQL_SUPPORTED_UNIONS`, the `validate-sql` classifier rule).

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `sql-query-translation`: a new requirement governs set operations — accepted spellings, ClickHouse's
  default modes for the bare forms, the `MINUS` and bare-`UNION` rejections, branch compatibility and
  output typing, the last-branch scope of a trailing `ORDER BY`/`LIMIT`, nesting, and the
  `INFORMATION_SCHEMA` boundary; the fail-closed rejection list drops set operations and its scenario
  flips to branch incompatibility.
- `rest-sql-endpoint`: execution governance stated for a set-operation statement — the combined result is
  what the row cap bounds, and a limit after the last branch is a branch limit rather than an outer one —
  and `validate-sql` reports a set operation valid, a bare `UNION` or an incompatible one invalid.
- `flight-sql-endpoint`: `SQL_SUPPORTED_UNIONS` declares `SQL_UNION`/`SQL_UNION_ALL` and the three
  neighbouring under-claims are recorded; the row-cap clamp and truncation probe apply to the combined
  result.
- `query-translation-endpoint`: `translate-sql` rejects a set-operation statement as inexpressible in the
  DSL, alongside joins and subqueries.

`structured-query` and `query-schema-discovery` are deliberately **not** modified: the DSL grammar, the
function catalog and the three discovery endpoints are untouched.

## Impact

- **Code**: `service/query/sql/` (a new pre-parse set-operation qualifier pass and a pre-validation
  `ORDER BY` re-association pass, `SqlQueryFrontEnd` screen, `SqlStarExpander`, `SqlSelectAliasInjector`,
  `RelToStructuredQueryTranslator` fold, `SqlIssueClassifier`, `CompositeQueryEntities`, `ResultColumns`),
  `service/query/model/` (a new `SetOpRelation` member of the sealed `Relation` hierarchy plus its kind
  enum), `service/query/translate/` (`CompositeQueryBuilder` renders the set operation and the synthetic
  model over it), `web/controller/query/SqlQueryController` (OpenAPI description),
  `web/flight/AdasFlightSqlProducer` (`SqlInfo`).
- **APIs**: additive on the SQL surfaces; no request or response shape changes. Error codes unchanged
  (`bad_request`, `query_budget_exceeded`, `clickhouse_unavailable`; Flight `INVALID_ARGUMENT`). One
  `validate-sql` classifier rule is retired with the message it matched.
- **Dependencies / config / migrations**: none. No new env var, no catalog or ClickHouse schema change,
  no new credential — so `docs/configuration.md` gains nothing.
- **Docs**: `docs/sql-reference.md` (a set-operations section, including the two places the reading is
  ClickHouse's rather than the standard's; the "Not supported" bullet), `README.md` (the
  rejected-construct sentence), the `SqlQueryController` OpenAPI text.
- **Tests**: the six rejection tests across unit, integration and Flight flip to acceptance; new unit
  tests for the qualifier pass, the `ORDER BY` re-association, the fold and the rendered SQL; new
  integration tests executing every form against real ClickHouse over REST and Flight, including the two
  divergence cases asserted against the row sets ClickHouse itself returns.
