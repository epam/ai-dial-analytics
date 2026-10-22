# Tasks — Query Translation Endpoints

## 1. DSL model serialization

- [x] 1.1 Verify `StructuredQuery` (and its `FilterNode`/`Expr`/`OutputColumn`/`SortItem`/`PageSpec`
      trees) serializes to the exact wire shape `POST /v1/queries/execute` deserializes; add
      explicit serializers only where the default shape diverges (design D3).
- [x] 1.2 Add a serialization round-trip unit test: DSL JSON → `StructuredQuery` → JSON →
      `StructuredQuery`, asserting equality across representative queries (row + aggregate,
      nested filters, all expr types, sort/page).

## 2. Contains operator redefinition — co/nc case-sensitive, new ico/inc (design D6, BREAKING)

- [x] 2.1 Add `ICO("ico")`/`INC("inc")` to `ComparisonOp`; accept them everywhere `co`/`nc` are
      accepted (filter/having validation, string-operand rules); extend the
      canonical-serialization round-trip coverage.
- [x] 2.2 Remap `FilterTranslator`: `CO`/`NC` → case-sensitive `like`/`notLike` (**redefinition**),
      `ICO`/`INC` → `likeIgnoreCase`/`notLikeIgnoreCase`, all with the same escaped pattern;
      update/extend execution-path tests for both sensitivities.
- [x] 2.3 Add `SqlLibraryOperators.ILIKE`/`NOT_ILIKE` to `SqlQueryOperators.TABLE` so `ILIKE`
      parses and validates.
- [x] 2.4 Change `RexToExprTranslator.likeFilter` to branch on the operator's case-sensitivity:
      `LIKE`/`NOT LIKE` → `co`/`nc`, `ILIKE`/`NOT ILIKE` → `ico`/`inc`; keep the `'%text%'`
      pattern and no-ESCAPE rules for both.
- [x] 2.5 Update `SqlQueryFrontEndTest` LIKE/ILIKE scenarios, the `execute-sql` OpenAPI
      description in `SqlQueryController` (drop the case-insensitivity deviation wording;
      document `LIKE` = case-sensitive → `co`, `ILIKE` = case-insensitive → `ico`), and the
      `OPERATORS` list of the bundled query demo page, since removed (add `ico`/`inc`).

## 3. DSL → SQL renderer

- [x] 3.1 Implement `StructuredQueryToSqlRenderer` in `service/query/sql` rendering the external
      dialect per design D5 (quoting, literals from execution-parsed values, case-insensitive
      function names, operators, clauses, bare-select source-column projection (`SELECT *` only
      when equivalent) / DISTINCT, IS NULL rewriting, `co`/`nc` → LIKE and `ico`/`inc` → ILIKE
      with metacharacter rejection, no-page → no LIMIT).
- [x] 3.2 Implement `QueryTranslationService`: entity resolution via `CatalogQueryService`,
      validation by discarding `StructuredQueryBuilder.build(…)` output, then rendering (D4).
- [x] 3.3 Unit-test the renderer: every operator, every function, literal encodings (string
      escaping, timestamp epoch-millis → UTC literal, date, boolean, uuid), enrichment dotted
      identifiers, inexpressible-construct rejections (`co` with `%`/`_`, null outside eq/ne).

## 4. Endpoints

- [x] 4.1 Add response DTOs `SqlTranslationResponseDto {query}` and
      `StructuredQueryTranslationResponseDto {sql}` in `web/dto/query` with `@Schema` docs.
- [x] 4.2 Add `QueryTranslationController` with `POST /v1/queries/translate-sql` (reuses
      `SqlQueryRequestDto` + `SqlQueryFrontEnd`, returns `translation.query()`) and
      `POST /v1/queries/translate` (body `StructuredQuery`, returns rendered SQL), with OpenAPI
      annotations documenting the ILIKE-based contains rule (design D1/D2/D6).

## 5. Round-trip and web-layer tests

- [x] 5.1 Round-trip tests through the real front end: for representative DSL queries,
      `SqlQueryFrontEnd.translate(render(query))` yields a semantically equivalent
      `StructuredQuery`; for representative SQL, rendering the translated query re-translates to
      an equal query (reuse the offline catalog fixtures from `SqlQueryFrontEndTest`).
- [x] 5.2 Controller tests: 200 envelopes (`{"query":…}`, `{"sql":…}`), 400 `ErrorView` for
      unsupported SQL, inexpressible/invalid DSL, and unknown entity on `translate`,
      and that no ClickHouse interaction occurs on the translation paths.

## 6. Specs and docs

- [x] 6.1 Confirm all delta specs (`specs/query-translation-endpoint/spec.md`,
      `specs/sql-query-translation/spec.md`, `specs/structured-query/spec.md`) match shipped
      behavior; adjust if implementation surfaced deviations.
- [x] 6.2 Update `openspec/project.md` (querying surface paragraph) to mention the translation
      endpoints; no `docs/configuration.md` change (no new config).
- [x] 6.3 `./gradlew clean build` passes (compile + checkstyle + tests).
