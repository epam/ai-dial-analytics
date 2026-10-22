## Why

A SQL query that runs fine through `POST /v1/queries/execute-sql` — one with a derived table,
window functions, a subquery, or a join — currently cannot be saved. `SavedQueryBodyResolver`
validates a `sql`-kind body by calling `SqlQueryFrontEnd.translate(sql)`, the same strict,
single-block-over-one-entity path `POST /v1/queries/translate-sql` uses to produce a structured-query
DSL document. Anything the SQL front end classifies as composite (`SqlCompositeTranslation`) is
rejected with `400 bad_request` ("statements with joins, derived tables or subqueries execute but
have no structured-query equivalent") even though it executes successfully and even though saving a
`sql`-kind body never needed a DSL document in the first place — it only needs the raw SQL text plus
a derived `source` for display.

A concrete example that hits this today: a derived table aggregating `dial_usage_log` by day, with a
7-day moving average and a running total computed via window functions over that derived table. It
executes; it cannot be saved.

## What Changes

- `sql`-kind saved query bodies are validated by the same **parse + plan** front end
  `POST /v1/queries/execute-sql` uses (`SqlQueryFrontEnd.plan`), not the DSL-only `translate` path.
  A composite SQL statement (join, derived table, CTE, or subquery) is now storable as long as it is
  otherwise executable.
- `source` becomes an **array** of every entity the body reads from, instead of a single string.
  It is derived by collecting every leaf entity the plan reads from, including entities reached only
  through a derived table, a scalar subquery, or an `IN`/`EXISTS` subquery, sorted alphabetically for
  a deterministic response:
  - A single-entity body (every `query`-kind body, and a `sql`-kind body like the motivating example
    above) yields a one-element array — the same entity `source` names today, just wrapped.
  - A `sql`-kind body reading from more than one entity (a join, or a subquery against a different
    entity) yields every one of them, one array entry each.
  - Every referenced entity is still validated against the caller's own view of the catalog exactly
    as today's single-entity check is — an entity the caller cannot resolve fails the save the same
    way an unresolvable field does today (`400 bad_request`, no existence disclosure).
  - **BREAKING**: `source` changes type from a string to a non-empty array of strings for **every**
    saved query, not only new composite ones. A caller reading `source` as a bare string must change
    to read the array. Existing stored rows are migrated in place (each existing string value becomes
    its one-element array) — no data is lost and no read produces a different set of entities than it
    did before, only a different JSON shape.
- `query`-kind (structured DSL) bodies keep their behavior unchanged **other than this `source` shape
  change**: the structured-query DSL itself has no wire representation for a join or a derived table
  (`CompositeQuery`/`JoinRelation`/`DerivedRelation` are internal-only), so a `query`-kind body stays
  single-entity and its `source` array always has exactly one element.
- `POST /v1/queries/translate-sql` and `POST /v1/queries/translate` are **unchanged** and continue to
  reject a composite statement with the existing `NO_DSL_EQUIVALENT` error — their job is producing a
  structured-query DSL document, which a composite statement genuinely has none of. Only the
  saved-queries write path stops routing through that translation-only front end for its validation.

## Capabilities

### Modified Capabilities
- `saved-queries`: a `sql`-kind body may now be a composite SQL statement (join, derived table, CTE,
  or subquery); `source` changes from a string to a non-empty array of every entity the body reads
  from; every entity a composite body reads from is validated against the caller's catalog view.

## Impact

- `com.epam.aidial.analytics.service.query.saved.SavedQueryBodyResolver` — `resolveSql`/`resolveSource`
  switch from `SqlQueryFrontEnd.translate` to `SqlQueryFrontEnd.plan` and from returning `String` to
  returning `List<String>`.
- A new leaf-entity collector over `CompositeQuery`/`Relation` (and the subqueries nested in a
  block's filter/having/select expressions), in `com.epam.aidial.analytics.service.query.sql`.
- `SavedQueryEntity.source` changes from `String`/`TEXT` to `List<String>`/`TEXT[]` (new Postgres
  migration converting existing rows in place, plus its `saved_query_aud` audit mirror per this
  project's auditing rule for widening an audited column); `SavedQueryDto.source` changes from
  `String` to `List<String>` in the API response.
- `docs/configuration.md` — no config keys change; no entry needed.
- Tests: `SavedQueryIntegrationTest` gains coverage for a single-entity composite save (the derived
  table + window function example, `source` a one-element array), a multi-entity join save (`source`
  a two-element array), and a query referencing an entity the caller cannot resolve.
