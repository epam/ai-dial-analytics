## Context

See `proposal.md` - Why. Two front-end entry points already exist on `SqlQueryFrontEnd`:

- `plan(sql)` — screens, parses, validates against the caller's catalog snapshot, and lowers to a
  `RelNode`, returning one of `SqlTranslation` (single block, single entity), `SqlCompositeTranslation`
  (a join, a derived table, a CTE, or a subquery — carries an internal `CompositeQuery` tree of
  `Relation`s: `EntityRelation`, `JoinRelation`, `DerivedRelation`), or `SqlMetadataResult`
  (`INFORMATION_SCHEMA`). `POST /v1/queries/execute-sql` uses this and executes whichever shape comes
  back.
- `translate(sql)` — calls `plan` and then insists on `SqlTranslation`, throwing
  `RelToStructuredQueryTranslator.NO_DSL_EQUIVALENT` for a `SqlCompositeTranslation` and a
  catalog-metadata message for `SqlMetadataResult`. `POST /v1/queries/translate-sql` and
  `SavedQueryBodyResolver.resolveSql` both call this today.

`SavedQueryBodyResolver.resolveSql` only needs two things from validation: proof the body is
executable, and the entity name(s) to store as `source`. It never needed a DSL document, so it never
needed `translate`'s stricter guarantee — it was simply the only existing entry point that also
returned an `entity`.

`CompositeQuery`/`Relation`/`ScalarSubqueryExpr`/`SubqueryNode` are internal-only (explicitly documented
as "never on the wire"), so nothing here changes what a `query`-kind (structured DSL) body can express.

Precedent for the array shape: `TableDefinitionEntity.orderingKey`/`tagOrder` already map a Postgres
`text[]` column to a JPA `List<String>` via `@JdbcTypeCode(SqlTypes.ARRAY)`, and that entity is
`@Audited` — the same pattern this design reuses, already proven safe under Envers.

## Goals / Non-Goals

**Goals**
- Let a `sql`-kind saved query be any statement `execute-sql` can run, with `source` always listing
  every entity it reads from.
- Keep `POST /v1/queries/translate-sql` and `POST /v1/queries/translate` byte-for-byte unchanged.

**Non-Goals**
- Extending the structured-query DSL (the `query`-kind body / `POST /v1/queries/execute`) to express
  joins or derived tables. That is a much larger change to a wire contract other clients (Flight SQL
  catalog, the query builder UI) depend on, and nothing in the motivating report needs it — the report
  is about the SQL editor's save action, not the query builder's.
- Changing how `execute-sql` itself plans or executes a composite statement.

## Decisions

**Route `resolveSql` through `plan`, not `translate`.** `SavedQueryBodyResolver.resolveSql` switches
to `sqlQueryFrontEnd.plan(sql)` and pattern-matches on the three `SqlStatementPlan` cases:
`SqlTranslation` behaves as before but wraps its one entity in a singleton list; `SqlMetadataResult`
keeps being rejected (an `INFORMATION_SCHEMA` statement has no entity and cannot be a saved query's
body any more than it can be a `query`-kind one); `SqlCompositeTranslation` is the new case.

Alternative considered: keep calling `translate` for the "does this run" check and separately call
`plan` to discover entities. Rejected — it plans the statement twice for no benefit; `plan` alone
already tells us which of the three shapes we have and, for `SqlTranslation`, carries the same
`StructuredQuery` `translate` would have unwrapped.

**A dedicated leaf-entity collector, not a reuse of `RelToStructuredQueryTranslator`'s field-name
walkers.** Add `CompositeQueryEntities.leafEntities(CompositeQuery): Set<String>` in
`com.epam.aidial.analytics.service.query.sql` (same package as the translator, so it can share
`Expr`/`FilterNode` pattern-matching idioms without a cross-package `public` surface). It recurses:
- over `Relation` — `EntityRelation` contributes its name; `JoinRelation` recurses into both sides;
  `DerivedRelation` recurses into its nested `CompositeQuery`;
- over the block's `select` expressions, `filter`, and `having` — any `ScalarSubqueryExpr` or
  `SubqueryNode` found contributes its own `leafEntities(subquery)`, unioned in.

Alternative considered: only look at the top-level `Relation` tree and ignore subqueries nested in
expressions. Rejected as a correctness gap in the feature itself — a body filtering one entity with an
`IN (SELECT … FROM other_entity)` genuinely reads two entities, and reporting a one-element `source`
for it would misdescribe what the saved query does.

**`source` is always a sorted array, never a heuristic "primary table".** `SavedQueryBodyResolver`
returns `List<String>` (the caller-resolved, canonical form of each name in the collected set, sorted
alphabetically) rather than picking a "first" or "primary" entity. Sorting rather than preserving
discovery order is deliberate: discovery order is an implementation accident of how Calcite orders
join operands and where a subquery sits in the expression tree, not a fact about the query worth
exposing, and a stable order lets two saved queries over the same entities compare equal on `source`
and keeps test assertions simple. Every entity in the set is validated for resolvability (below), not
just a chosen "main" one.

**Every leaf entity is validated the same way, whether one or many.** For each name in the collected
set, `SavedQueryBodyResolver` calls the existing `requireModel` (which already backs the single-entity
path) — unchanged behavior, just applied per entity instead of once. This is belt-and-suspenders:
`plan` already refused an unresolvable table while lowering to `RelNode` (`scanCtx` throws for a name
absent from the caller's own catalog snapshot), so `requireModel` cannot actually fail here on a plan
that already succeeded. It stays for the same reason the single-entity path already had it: keeping
one authority (`CatalogQueryService`) for "is this queryable" rather than trusting a second, adjacent
catalog view to agree forever.

**`source` becomes an array at every layer it touches, for every saved query — not only new composite
ones.** `saved_query.source` changes from `TEXT NOT NULL` to `TEXT[] NOT NULL` (with a `CHECK
(array_length(source, 1) > 0)`, mirroring this table's existing `CHECK (length(trim(name)) > 0)`
style); `saved_query_aud.source` (the Envers mirror) widens the same way in the same migration, per
this project's rule that widening an audited column requires widening its mirror in the same change;
`SavedQueryEntity.source` becomes `List<String>` via `@JdbcTypeCode(SqlTypes.ARRAY)` +
`@Column(columnDefinition = "text[]")`, following `TableDefinitionEntity.orderingKey`'s exact pattern;
`SavedQueryDto.source` becomes `List<String>`. The field is **not** renamed — it is the same fact
("what does this body query"), now expressed completely instead of collapsed to one name or to
`null` when there is more than one.

Alternative considered: keep `source` a nullable string and add a second `sources: string[]` field.
Rejected — carrying two fields for one fact invites them to drift (which one does a new reader use?),
and the user-facing ask was specifically to make `source` itself the array.

## Risks / Trade-offs

- **[Risk]** Every existing consumer of `source` (not just ones handling joins) breaks: the type
  changes from `string` to `string[]` for every saved query, including ones that predate this change.
  → Called out as **BREAKING** in the proposal; mitigated by migrating existing rows in place (each
  existing string becomes its one-element array) so no consumer sees a missing or reordered fact,
  only a different JSON shape to read.
- **[Risk]** The subquery-walking collector must stay exhaustive as `Expr`/`FilterNode` grow new
  sealed subtypes, or a future expression kind silently under-counts entities. → Both are `sealed`
  interfaces; an exhaustive `switch` over either fails to compile when a new subtype is added with no
  case, which is exactly the safety net `RelToStructuredQueryTranslator`'s existing walkers
  (`collectFieldNames`, `containsWindow`) already rely on for the same reason.

## Migration Plan

One Flyway migration (`V37__saved_query_source_array.sql`) converts both the live and audit tables:

```sql
ALTER TABLE saved_query ALTER COLUMN source TYPE text[] USING ARRAY[source];
ALTER TABLE saved_query ADD CONSTRAINT saved_query_source_not_empty CHECK (array_length(source, 1) > 0);
ALTER TABLE saved_query_aud ALTER COLUMN source TYPE text[] USING
    CASE WHEN source IS NULL THEN NULL ELSE ARRAY[source] END;
```

Every existing row's single string becomes a one-element array (`USING ARRAY[source]`), so the
migration is a lossless, in-place type conversion with no application-level backfill step. The audit
mirror keeps allowing `NULL` (an Envers revision row that predates the `source` column existing), so
its `USING` clause is null-safe where the live table's is not (the live table's `NOT NULL` already
guarantees every row has a value to wrap). Rollback is not provided, matching the project's other
non-reversible Flyway migrations — reverting would need to collapse a genuinely multi-entity `source`
back into one string, which is lossy and has no correct answer.
