## 1. Tests first (red)

- [x] 1.1 In `EnrichmentJoinRenderTest`, add a partition-aligned model with two enrichments, and render tests for:
  a mixed `and` (source `amount`/`user_id` conjuncts plus an enrichment conjunct) that emits both `user_id IN` and
  `day IN` carrying only the source conjuncts; the enrichment conjunct appearing only in the outer `WHERE`;
  `buildCount` keyed the same way; a filter on enrichment A that keys enrichment B's join; nested `and`
  flattening; a mixed `or` left unkeyed; an enrichment-only filter left unkeyed
- [x] 1.2 Run the new render tests and confirm they fail against the all-or-nothing rule

## 2. Implementation

- [x] 2.1 Add `StructuredQueryBuilder.sourceOnlyConjuncts(QueryEntityModel, FilterNode)`, which flattens
  top-level `and`s recursively and keeps the conjuncts whose referenced fields are all source fields
- [x] 2.2 Rewrite `keyPredicates` to keep the whole filter when it is source-only and otherwise translate each
  source-only conjunct with the same `filterTranslator` and bindings; `build` and `buildCount` both use it
- [x] 2.3 Update the Javadoc on `keyPredicates` and the `EnrichmentJoins` class note where they describe
  the whole-filter rule

## 3. Integration

- [x] 3.1 Add a ClickHouse integration test (`StructuredQueryExecutionIntegrationTest`) for a mixed filter
  `request_time >= T AND payload.request_body IS NULL`, with in-window rows that have a body, in-window rows
  with no payload row, and an out-of-window row. Assert that the rows and `totalCount` returned through
  `/v1/queries/execute` equal a hand-written unkeyed ClickHouse statement's result
- [x] 3.2 Add a positive mixed-filter case (`request_time >= T AND request_body = 'v'`, selecting the body),
  also compared against the unkeyed statement

## 4. Specs and verification

- [x] 4.1 Confirm the `structured-query` delta matches the shipped behavior; `rest-sql-endpoint` needs no
  delta, because it does not restate the rule
- [x] 4.2 `openspec validate key-enrichment-joins-by-source-conjuncts`
- [x] 4.3 `./gradlew clean build` passes
