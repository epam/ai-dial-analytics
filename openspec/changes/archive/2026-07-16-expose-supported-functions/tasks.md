## 1. Function catalog descriptor

- [x] 1.1 Create `service.query.QueryFunctionCatalog` with an immutable, alphabetically ordered list
  of descriptors for all 14 supported functions (name, group, signature, returns, distinct flag,
  description, args with kinds/optional/constraints), exposing the `date_bin` allowed-units set as
  the shared constant
- [x] 1.2 Point `ExprTranslator.DATE_BIN_UNITS` validation at the catalog's unit set (keyword map
  keys asserted equal) and add a cross-referencing comment on `toFunction` naming the catalog

## 2. Discovery endpoint

- [x] 2.1 Add DTOs `web.dto.query.QueryFunctionDto`, `QueryFunctionArgDto`,
  `QueryFunctionArgConstraintsDto` (snake_case wire names, NON_NULL serialization for optional
  members)
- [x] 2.2 Add `GET /v1/queries/functions` to `QuerySchemaController` returning the catalog as
  `List<QueryFunctionDto>` with OpenAPI annotations matching the existing endpoints

## 3. Tests

- [x] 3.1 Controller/serialization test: response lists all 14 functions alphabetically; `date_bin`
  constraints (`min` 1, allowed units), `count` optional arg + distinct, omitted-not-null optional
  members
- [x] 3.2 Parity test: every catalog descriptor, instantiated as a minimal valid `FnExpr` per its
  declared arity/kinds, is accepted by `ExprTranslator.toField`; advertised `date_bin` units equal
  `DATE_BIN_UNITS` keys; non-catalog name still rejected

## 4. Spec & docs

- [x] 4.1 Keep the delta spec (`specs/query-schema-discovery/spec.md`) in sync with shipped behavior
  and update its Implementation notes section content for the new endpoint/DTOs
- [x] 4.2 Verify no config/docs changes needed (no new env vars); run `./gradlew clean build` green
