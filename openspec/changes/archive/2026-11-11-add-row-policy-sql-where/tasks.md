## 1. Translation

- [x] 1.1 Translate a condition text to a filter tree through the SQL front end, as the `WHERE` of a statement over the table, under the system identity
- [x] 1.2 Rewrite `caller.user_hash` and `caller.project` outside quotes to per-request marker literals and map them back to `param` nodes after translation

## 2. API

- [x] 2.1 Accept `where` on `PUT /v1/tables/{name}/row-policies/{role}`; refuse a body with both `where` and `filter`, or neither, with 422
- [x] 2.2 Refuse `where` on a table that is not a queryable entity, and map every translation failure to `422 validation_error`

## 3. Tests

- [x] 3.1 Integration: a policy written as `where` is stored as the same tree as its `filter` form and confines reads; caller values work; a literal spelling a caller value stays a literal
- [x] 3.2 Integration: refusals for both members given, a subquery, an unknown column, a caller value in a function, and an enrichment table

## 4. Specs and docs

- [x] 4.1 Add the SQL form to `docs/row-level-security.md`
- [x] 4.2 Confirm the delta spec matches the shipped behaviour and run `openspec validate add-row-policy-sql-where --strict`

## 5. Verification

- [x] 5.1 `./gradlew clean build` passes
