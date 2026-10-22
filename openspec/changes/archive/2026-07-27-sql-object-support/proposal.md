## Why

`object`-typed columns (ClickHouse `JSON`, e.g. the seeded `dial_usage_log.jwt_claims` and
`request_tags`) are the last field type the governed SQL pipeline cannot see. They are excluded
from the Calcite schema, so every SQL statement naming one fails as "unknown column" on
`POST /v1/queries/execute-sql`, `translate-sql`, and Arrow Flight SQL — even though the
structured JSON path (`POST /v1/queries/execute`) has always projected them. The gap also leaks
into the DSL→SQL direction: rendering a bare (empty `select`) row query over
`dial_usage_log` fails with

> an empty 'select' projects the object-typed column 'jwt_claims', which has no SQL
> representation; list the desired columns explicitly

so the single most common query shape cannot be translated at all for the service's own primary
entity. This change finishes what `sql-array-support` (archived 2026-07-21) started for `array`
columns, applying the same projection-only treatment to `object`.

## What Changes

- `object`-typed fields become **SQL-visible** in the Calcite schema shared by `execute-sql`,
  Flight SQL and `translate-sql` — they can be projected (`SELECT jwt_claims FROM dial_usage_log`,
  including the bare `SELECT *`-equivalent projection).
- `object` fields are **projection-only**, exactly like `array`: use in `WHERE`, `GROUP BY`,
  `ORDER BY`, `SELECT DISTINCT`, or as a function/aggregate argument is rejected with a stable
  descriptive translation error naming the field, never a generic "unknown column".
- The DSL→SQL renderer **no longer rejects a bare `select`** over an entity with `object` source
  columns; the quoted error above disappears. `object` columns render as ordinary quoted
  identifiers, and the same projection-only rules are enforced on the render side so the
  render → translate round-trip still holds.
- **Arrow Flight SQL results encode `object` columns as `Utf8` carrying canonical JSON text**
  (Arrow has no schemaless-JSON type); the FlightInfo schema and the record batches agree.
- **REST SQL results** (`execute-sql`) return `object` values as native JSON objects — the same
  bytes the structured `/execute` path already produces, so the two surfaces stay identical.
- JSON path/extraction operators over `object` values (`jwt_claims.sub`, `JSON_VALUE`, key
  filtering) stay out of scope — as with arrays, operator semantics are a separate change.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `sql-query-translation`: `object` fields join the SQL-visible schema as projection-only
  columns (typed as character data in the Calcite row type); comparisons/grouping/ordering/
  aggregation over an `object` field are rejected with a descriptive error. No field type
  remains SQL-invisible.
- `query-translation-endpoint`: a bare/empty row-mode `select` over an entity with `object`
  source columns now renders instead of failing; the "no SQL representation" rejection is
  removed. Non-projection uses of an `object` field are rejected on the render side, mirroring
  the existing `array` rules.
- `rest-sql-endpoint`: `object` result values are returned as native JSON objects in the row
  objects, identical to `/v1/queries/execute`.
- `flight-sql-endpoint`: `object` result columns are encoded as Arrow `Utf8` containing the
  column's JSON serialization; both the FlightInfo schema and the batches carry `Utf8`.
- `structured-query`: the ClickHouse client's array wrapper is unwrapped at every depth, so arrays
  nested inside an `object` cell serialize as JSON arrays. This fixes a latent defect on
  `POST /v1/queries/execute` — a claims blob like `{"roles":["admin"]}` previously serialized as
  `{"roles":{"array":["admin"],"arrayOfObjects":["admin"]}}`; it had no test coverage because no
  test projected an object column with a nested array.

## Impact

- **Code**: `service/query/sql/CalciteQueryCatalog` (drop the `sqlVisible` exclusion, map
  `OBJECT` to a Calcite character type), `service/query/sql/RelToStructuredQueryTranslator` and
  `service/query/sql/RexToExprTranslator` (generalize the array projection-only guard to cover
  `object`; new `OBJECT` result kind), `service/query/sql/ResultColumnKind` +
  `ResultColumn` (new `OBJECT` kind), `service/query/sql/StructuredQueryToSqlRenderer` (remove
  the bare-select rejection, extend the projection-only guard), `service/query/arrow/
  ArrowTypeMapper` + `ArrowBatchWriter` (`OBJECT` → `Utf8`, JSON-text coercion of the cell).
- **APIs**: additive only — previously rejected SQL statements and translations now succeed; no
  request or response shape changes on calls that already worked. No breaking changes.
- **Dependencies**: none added.
- **Security**: no change to column governance — the Calcite schema is built from the same
  sensitive-filtered `QueryEntityModel` the `/execute` path uses, so a caller who may not see
  `jwt_claims` still does not get it in the SQL schema.
- **Specs**: delta specs for the five modified capabilities above. `query-schema-discovery`
  already publishes `object` as a field type and needs no change.
