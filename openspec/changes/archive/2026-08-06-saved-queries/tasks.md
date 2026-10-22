## 1. Storage

- [x] 1.1 Add `V24__saved_query.sql` (authored as `V23`, renumbered when the default branch claimed that
  slot mid-review) under `src/main/resources/db/migration/postgres/`: the
  `saved_query` table (`id` TEXT PK, `name`, `description`, `tag`, `scope`, `owner_id`, `owner_email`,
  `source`, `query` JSONB, `sql` TEXT, `time_intent` JSONB, `result_view`, `chart` JSONB,
  `generation` BIGINT, `created_at`/`updated_at` TIMESTAMPTZ), with `CHECK` constraints for the
  `scope` and `result_view` enum-like values and for exactly-one-of `query`/`sql`, and indexes on
  `(owner_id, scope)` and on `scope`. Include the header comment explaining the exactly-one-of check
  and the deliberate absence of a foreign key on `source`, in the style of `V20__aggregate_pipeline.sql`,
  plus the authorship-pair comment and a `COMMENT ON COLUMN` stating that nothing may authorize on
  `owner_email` (D6a) — it sits next to an identity it resembles, which is what makes that mistake easy.
- [x] 1.2 Add `service/query/saved/SavedQueryEntity` — the JPA entity, `@JdbcTypeCode(SqlTypes.JSON)`
  on the JSONB columns as `PipelineEntity`/`RuleEntity` do, `@Enumerated(EnumType.STRING)` for
  `scope` and `result_view`. The JSON columns are held as `String`, not as mapped types: Hibernate's
  format mapper is Jackson 2 and cannot read the DSL's Jackson 3 `FilterNode` wiring back (D1a). Add
  `service/query/saved/SavedQueryJson` to convert them with the application's own `JsonMapper`.
- [x] 1.3 Add `service/query/saved/SavedQueryScope` and `SavedQueryResultView` enums.
- [x] 1.4 Add `service/query/saved/SavedQueryRepository` — a Spring Data JPA repository whose finders
  carry the **visibility predicate** (`id` + (`scope = COMMON` OR `owner_id = :author`), and the
  null-author fail-open variant) so an invisible row and a missing row share one code path.

## 2. Model and DTOs

- [x] 2.1 Add `service/query/saved/model/SavedQueryTime` — the validated `null` | relative | absolute
  union (D13) — and `SavedQueryChart`. These are the JSONB payload types.
- [x] 2.2 Add `web/dto/query/saved/SavedQueryRequestDto` — the caller-supplied fields only. Include
  the server-assigned names (`id`, `owner_id`, `source`, `generation`, `created_at`, `updated_at`) as
  explicitly rejected rather than absent, so a client sending them gets `422` instead of silence.
- [x] 2.3 Add `web/dto/query/saved/SavedQueryDto` (the one read shape, used by both the list and the
  single read — D11 was reversed, so there is no summary DTO) and `SavedQueryListDto`
  (`{saved_queries: [...]}`), both documented with `@Schema`.
- [x] 2.4 Add `web/mapper/query/SavedQueryMapper` — the read direction only. Hand-written rather than
  MapStruct, following `PipelineMapper`'s precedent of writing by hand what is not field copying: the
  entity's JSON payloads are text, so mapping them is a parse.

## 3. Validation

- [x] 3.1 Add `exception/SavedQueryNotFoundException`, `exception/SavedQueryValidationException`, and
  `exception/SensitiveLiteralNotAllowedException`.
- [x] 3.2 Add `@ExceptionHandler`s to `web/handler/DefaultExceptionHandler`: `404 not_found`,
  `422 validation_error`, and `422 sensitive_literal_not_allowed` respectively. Confirm
  `AccessDeniedException` already maps to `403 forbidden` and reuse it for the `common` gate.
- [x] 3.3 Add `service/query/saved/SavedQueryBodyValidator`: exactly-one-of `query`/`sql`; blank-name
  rejection; server-assigned-field rejection (including a supplied `params`, which is not an accepted
  field); rejection of any `ParamExpr` in a `query` body with this capability's own message (D10);
  `time` union validation (D13); the size cap (D15).
- [x] 3.4 Add `service/query/saved/SavedQueryBodyResolver`, implementing the one "must translate" rule
  (D8) in the order *entity resolution → sensitive-literal guard → translation*:
  for a `query`-kind body, resolve `CatalogQueryService.findModel(query.entity())`, run the guard, then
  validate via `QueryTranslationService.toSql(query)` (discarding the SQL) and derive `source` from
  `query.entity()`; for a `sql`-kind body, translate via `SqlQueryFrontEnd.translate(sql)`, run the
  guard over `.query()`, and derive `source` from the translated entity. Translation failures pass
  through unchanged as `400 bad_request` at every scope — the same error the query endpoints give for
  the same body — so nothing has to sniff exception messages to separate an unresolvable column from
  any other failure.
- [x] 3.5 Add `service/query/saved/SensitiveLiteralGuard`: walk `filter` and `having` of the
  `StructuredQuery` (the request's, or the SQL translation's); for each `ComparisonNode`, partition
  arguments into column references (`FieldExpr`, or `FnExpr` transitively containing one) and literals
  (`ValueExpr`, or `ArrayExpr` of them); if a node holds at least one of each and any referenced field
  is `sensitive` in the caller's `QueryEntityModel.baseSchema()`, throw
  `SensitiveLiteralNotAllowedException` naming the column. Never inspect SQL text.

## 4. Service and authorization

- [x] 4.1 Add `service/query/saved/SavedQueryAccess`: resolve the caller via
  `SecurityClaimsExtractor.getAuthor()`; expose `canWrite(scope)` (`FULL_ADMIN` for `COMMON`, any
  application role for `PERSONAL`) and the `none`-mode fail-open path (`null` author, no owner filter,
  every write allowed — D7). Mirror `SensitiveColumnAccess`'s `security.mode` handling.
- [x] 4.2 Add `service/query/saved/SavedQueryService`: list / create / read / replace / delete.
  Create assigns `id` as `"sq_" + UUID.randomUUID()` with dashes stripped (D2), `owner_id`,
  `generation = 1`, and the timestamps. Replace preserves `id`/`owner_id`/`created_at`, bumps
  `generation`, refreshes `updated_at`, and enforces the write rule against **both** the stored and
  requested scope (D5). All reads/writes go through the visibility predicate so a non-visible row is
  `404`.
- [x] 4.3 Bind `saved-queries.max-body-bytes` (default `65536`) with `@Value` on
  `SavedQueryBodyValidator`, matching how `SensitiveColumnAccess` reads `security.mode` — a single
  scalar does not warrant a properties class.

## 5. Web layer

- [x] 5.1 Add `web/controller/query/SavedQueryController` with the five endpoints, `@Schema`/`@Operation`
  documentation matching the neighbouring query controllers, `@Valid` request bodies, `204` on delete,
  and a `scope` request parameter defaulting to `personal` whose unrecognised values yield
  `422 validation_error`.
- [x] 5.2 Verify the new paths are covered by the existing `/v1/**` security matchers in
  `OidcSecurityConfiguration` (authenticated + application role for reads) and that no
  `@FullAdminOnly` is placed on the mutating methods — the `common` gate is body-dependent and lives
  in the service (D5).

## 6. Configuration and docs

- [x] 6.1 Add the bare `saved-queries.max-body-bytes: ${SAVED_QUERIES_MAX_BODY_BYTES:65536}` binding
  to `application.yml` with **no** explanatory comment.
- [x] 6.2 Add a `Saved queries` table to `docs/configuration.md` describing that property — the only
  place the description lives. Do not add an env-var table to `README.md`.

## 7. Tests

- [x] 7.1 Unit-test `SensitiveLiteralGuard`: a literal bound to a sensitive column is rejected; a
  sensitive-column-to-column comparison is allowed; a literal bound to a non-sensitive column is
  allowed; a nested `FnExpr(sensitive) = literal` is rejected; an `in` predicate with an `ArrayExpr` of
  literals is rejected; a sensitive literal in `having` is rejected as well as in `filter`.
- [x] 7.2 Unit-test `SavedQueryBodyValidator`: exactly-one-of both/neither; blank name; each
  server-assigned field rejected; a supplied `params` field rejected; a `ParamExpr` in a `query` body
  rejected with this capability's message rather than the translator's; each `time` shape
  accepted/rejected including the inverted absolute range; the size cap.
- [x] 7.3 Integration-test the API in `none` mode: full CRUD round trip at both scopes, `owner_id`
  null, no owner filter, list defaults to `personal`, delete then read is `404`, and `generation` bumps
  on replace. For the list (D11 reversed): each entry carries the full body, a list entry equals the
  single read of the same saved query, nulls stay omitted rather than emitted, a `sql`-kind entry
  carries `sql` and no `query`, and ordering is by most recently updated.
- [x] 7.4 Integration-test the API in `oidc` mode: the owner filter on `personal` list; another
  caller's `personal` id yields `404` and never `403` on read, replace, and delete; a `READ_ONLY_ADMIN`
  reading `common`; the `FULL_ADMIN` gate on `common` create/replace/delete; a non-admin's attempt to
  promote its own row to `common` yielding `403` with the stored scope unchanged.
- [x] 7.5 Integration-test the sensitive-literal path with a `FULL_ADMIN` caller (`422
  sensitive_literal_not_allowed`, column named, nothing persisted) for both a `query` body and a `sql`
  body, and the non-admin counterpart yielding `400 bad_request` with no mention of the column.
- [x] 7.6 Integration-test `source` derivation and the "must translate" rule (D8): `source` matches the
  body's entity; a `sql` body's `source` comes from the translation; untranslatable SQL is
  `400 bad_request` at both scopes; a structured body the query layer rejects is `400`; a stored body
  read back and posted to `POST /v1/queries/translate` is accepted unchanged (the executable-as-stored
  guarantee, asserted against the query surface rather than through ClickHouse); and there is no
  `POST /v1/saved-queries/{id}/execute` (`404`).

## 8. Verify

- [x] 8.1 `./gradlew clean build` passes (compile + checkstyle + tests).
- [x] 8.2 `openspec validate saved-queries` passes and the delta specs match what shipped — including
  the `authentication` delta for the body-dependent `common` gate.
