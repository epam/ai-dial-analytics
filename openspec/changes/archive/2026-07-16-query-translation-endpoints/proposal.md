# Query Translation Endpoints

## Why

ADAS already translates SQL to the structured query DSL internally (Calcite front end behind the
Flight SQL and REST SQL endpoints), and clients increasingly work in both representations: SQL is
the lingua franca for humans and BI-adjacent tooling, while the JSON DSL is what programmatic
consumers build and what the schema-discovery surface documents. There is no way today to convert
between the two without executing a query. Exposing both translation directions lets clients
migrate ad-hoc SQL to governed DSL programmatically, lets DSL consumers render a readable SQL
representation of a stored query, and makes the translator's fail-closed subset directly
inspectable (translate first, execute later — or never).

## What Changes

- New endpoint `POST /v1/queries/translate-sql` — translate a single read-only SQL SELECT into
  the structured query DSL JSON **without executing it**. Same SQL subset, catalog validation,
  and error behavior as `POST /v1/queries/execute-sql`; the response carries the DSL query
  document that `POST /v1/queries/execute` would accept.
- New endpoint `POST /v1/queries/translate` — translate a structured query DSL document into a
  SQL SELECT statement in the **external SQL dialect** (the exact subset `execute-sql` accepts),
  without executing it. The DSL document is validated against the catalog with the same rules as
  `POST /v1/queries/execute` before rendering.
- The two directions are round-trip coherent: SQL produced by `translate` is accepted by
  `translate-sql`/`execute-sql`, and DSL produced by `translate-sql` is accepted by
  `translate`/`execute`.
- DSL model gains canonical JSON **serialization** (the wire shape previously only had custom
  deserializers) so a `StructuredQuery` can be returned as the same JSON `execute` accepts.
- **BREAKING** — the DSL contains family is redefined for case-sensitivity support: `co`/`nc`
  become **case-sensitive** contains / not-contains (previously case-insensitive), and new
  operators `ico`/`inc` provide the **case-insensitive** behavior. Existing DSL clients using
  `co`/`nc` for case-insensitive matching switch to `ico`/`inc`.
- **BREAKING** — the SQL dialect's contains matching becomes standard-SQL exact:
  `LIKE '%text%'`/`NOT LIKE` → `co`/`nc` (case-sensitive, standard SQL semantics — previously
  it silently matched case-insensitively, a documented deviation), and `ILIKE`/`NOT ILIKE` →
  `ico`/`inc` (case-insensitive). Affects existing `execute-sql`/Flight SQL callers relying on
  the old deviation (both surfaces experimental). DSL→SQL rendering is likewise exact
  (`co` → `LIKE`, `ico` → `ILIKE`).
- No new configuration; endpoints inherit the existing `/v1/**` security rules (in `oidc` mode:
  `FULL_ADMIN` or `READ_ONLY_ADMIN`).

## Capabilities

### New Capabilities

- `query-translation-endpoint`: the REST translation surface — both endpoints' request/response
  contracts, validation-without-execution semantics, the DSL→SQL rendering contract (external
  dialect, identifier quoting, literal encoding, operator/function mapping, constructs that
  cannot be rendered), and the round-trip guarantee.

### Modified Capabilities

- `structured-query`: the filter sub-grammar's contains family becomes four operators —
  `co`/`nc` redefined as case-sensitive, new `ico`/`inc` case-insensitive — and the ClickHouse
  translation contract maps them to case-sensitive / case-insensitive `LIKE` respectively.
- `sql-query-translation`: the LIKE requirement changes — `LIKE`/`NOT LIKE` translate to
  `co`/`nc` (case-sensitive, standard semantics) and `ILIKE`/`NOT ILIKE` to `ico`/`inc`,
  replacing today's case-insensitivity deviation with exact 1:1 semantics.

`rest-sql-endpoint` execution behavior is otherwise unchanged (its user-facing description of
the LIKE rule is updated to match).

## Impact

- **Code**: new `QueryTranslationController` (`web/controller/query`), new request/response DTOs
  (`web/dto/query`), new DSL→SQL renderer in `service/query/sql` (or `translate`), JSON
  serializers for the DSL model types (`service/query/model`). Reuses `SqlQueryFrontEnd`
  (SQL→DSL), `CatalogQueryService`/`StructuredQueryBuilder` (DSL validation).
- **APIs**: two new POST endpoints under `/v1/queries`; OpenAPI/Swagger updated via annotations.
- **Errors**: existing `ErrorView` mappings reused — `SqlTranslationException` and
  `QueryValidationException` → 400 `bad_request` (including an unknown entity, matching the
  execute endpoint's rejection).
- **Docs/specs**: new capability spec; `docs/configuration.md` untouched (no new config).
- **Dependencies**: none added (Calcite and jOOQ are already in place).
