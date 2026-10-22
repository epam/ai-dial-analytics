## 1. Entity collection

- [x] 1.1 Add `CompositeQueryEntities` in `com.epam.aidial.analytics.service.query.sql` with a static
  `Set<String> leafEntities(CompositeQuery query)` that recurses over `Relation`
  (`EntityRelation`/`JoinRelation`/`DerivedRelation`) and, for every `ScalarSubqueryExpr`/`SubqueryNode`
  found while walking the block's `select`, `filter`, and `having`, unions in
  `leafEntities(subquery.subquery())`. Use exhaustive `switch` over the sealed `Expr`/`FilterNode`
  hierarchies (mirroring `RelToStructuredQueryTranslator.collectFieldNames`/`collectFilterFieldNames`)
  so a future sealed subtype fails to compile rather than being silently skipped.
- [x] 1.2 Unit-test `CompositeQueryEntities` directly against hand-built `CompositeQuery` fixtures:
  one entity via a plain scan, one entity via a derived table wrapping the same entity twice, two
  entities via a join, two entities via an `IN` subquery, three entities via a join whose one operand
  is itself a derived table with a scalar subquery against a third entity.

## 2. Saved-query resolver

- [x] 2.1 Change `SavedQueryBodyResolver.resolveSource`/`resolveSql`/`resolveStructured` to return
  `List<String>` instead of `String`. `resolveStructured` returns a singleton list of the resolved
  entity.
- [x] 2.2 Change `resolveSql` to call `sqlQueryFrontEnd.plan(sql)` and switch on `SqlStatementPlan`:
  `SqlTranslation` resolves exactly as today but wraps the one entity in a singleton list;
  `SqlMetadataResult` keeps throwing the existing catalog-metadata `SqlTranslationException`;
  `SqlCompositeTranslation` calls `CompositeQueryEntities.leafEntities(composite.query())`, calls
  `requireModel` for every name in the set (`400 bad_request` for an unresolvable one, via the
  existing `QueryValidationException` mapping), and returns the resolved canonical names sorted
  alphabetically.
- [x] 2.3 Update `SavedQueryService` (create/replace) and any other caller of `resolver.resolveSource`
  for the new `List<String>` return type.

## 3. `source` becomes an array end to end

- [x] 3.1 Add `src/main/resources/db/migration/postgres/V37__saved_query_source_array.sql`:
  ```sql
  ALTER TABLE saved_query ALTER COLUMN source TYPE text[] USING ARRAY[source];
  ALTER TABLE saved_query ADD CONSTRAINT saved_query_source_not_empty CHECK (array_length(source, 1) > 0);
  ALTER TABLE saved_query_aud ALTER COLUMN source TYPE text[] USING
      CASE WHEN source IS NULL THEN NULL ELSE ARRAY[source] END;
  ```
  with the same explanatory-comment style as `V24__saved_query.sql` (why: a composite `sql`-kind body
  can read from more than one entity, and the audit mirror must widen in the same change).
- [x] 3.2 Change `SavedQueryEntity.source` from `String` to `List<String>`, annotated
  `@JdbcTypeCode(SqlTypes.ARRAY)` and `@Column(columnDefinition = "text[]", nullable = false)` —
  the exact pattern `TableDefinitionEntity.orderingKey` already uses under `@Audited`.
- [x] 3.3 Change `SavedQueryDto.source` from `String` to `List<String>`; update its `@Schema`
  description and example (`["dial_usage_log"]` / `["dial_usage_log", "other_table"]`).
- [x] 3.4 Update `SavedQueryMapper`/`SavedQueryService.apply` and any other direct read/write of
  `entity.getSource()`/`setSource(...)` for the new type — plain field copies, no logic change
  expected.

## 4. Tests

- [x] 4.1 `SavedQueryIntegrationTest`: add a case saving the motivating SQL shape — a derived table
  aggregating one entity with a window function (moving average / running total) over it — asserting
  `201` and `source` equal to `["<entity>"]`.
- [x] 4.2 `SavedQueryIntegrationTest`: add a case saving a `sql`-kind body that joins two queryable
  entities, asserting `201` and `source` equal to the two entities, sorted alphabetically.
- [x] 4.3 `SavedQueryIntegrationTest`: add a case saving a `sql`-kind body with an `IN` subquery against
  a second entity, asserting the same two-entity `source` outcome as the join case.
- [x] 4.4 `SavedQueryIntegrationTest`: add a case where a composite body joins a queryable entity with
  one the caller cannot resolve (or that does not exist), asserting `400 bad_request` and that nothing
  is persisted — reuse the existing "unresolvable field" assertion style (no existence disclosure).
- [x] 4.5 Update the existing `savesAndDerivesTheSourceOfSqlBody` test (and any other assertion of
  `jsonPath("$.source").value("<entity>")`) to expect a one-element array instead of a bare string.
- [x] 4.6 Confirm `POST /v1/queries/translate-sql` and `POST /v1/queries/translate` still reject the
  same joining SQL used in 4.2 with the unchanged `NO_DSL_EQUIVALENT` message (existing
  `SqlQueryExecutionIntegrationTest`/`SqlCompositeExecutionIntegrationTest` coverage — add a case only
  if none currently exercises the translate-sql path for a join).
- [x] 4.7 List/read round-trip: saving a joined `sql`-kind body, then listing and reading it, both
  return the same two-element `source` array (matches the existing "list is byte-for-byte the single
  read" test pattern).

## 5. Spec sync

- [x] 5.1 Confirm the delta at `specs/saved-queries/spec.md` matches shipped behavior exactly (the
  array `source` shape, the parse-and-plan validation description, the new scenarios) before archive.
