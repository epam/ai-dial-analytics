# Design

## D1. Metadata queries are executed, not translated

Everything ADAS does with SQL today is *translation*: parse → validate → `RelNode` →
`StructuredQuery` → ClickHouse. `INFORMATION_SCHEMA` cannot go down that path. Its rows live in
ADAS's own Postgres-backed catalog, not in ClickHouse, and there is no entity to translate against.

The rows are also tiny — a handful of entities times their columns — so the question is only *which*
in-process evaluator to use.

**Decision: Calcite's `Interpreter` over `ScannableTable`s.** The plan already exists (`planner.rel`
produces it for every statement); the interpreter executes that plan directly. `SELECT DISTINCT`,
`WHERE`, `ORDER BY`, `LIMIT`/`OFFSET` and aggregates all come for free, with Calcite's own semantics.

Rejected alternatives:

- **Hand-match the three known statements.** Brittle, and it stops being true the moment the connector
  version changes — which it already did between 3.12.0 and 3.14.0 (the `TABLE_CATALOG` filter was
  dropped and `IS_NULLABLE` was added).
- **Translate to `StructuredQuery`, then evaluate the DSL in memory.** Reuses the front end but
  requires a second implementation of filter/project/distinct/sort/limit semantics that must stay
  consistent with the ClickHouse one forever. A duplicate execution engine is a worse liability than a
  second execution *path*.

The seam is narrow on purpose: `SqlQueryFrontEnd.plan(sql)` returns a sealed
`SqlStatementPlan` — either the existing `SqlTranslation` or a new `SqlMetadataResult`. Because
`SqlMetadataResult` carries `(List<ResultColumn>, List<Map<String, Object>>)`, the shapes the rest of
the system already speaks, every consumer downstream is reused unchanged: `ArrowTypeMapper.schema`,
`ArrowBatchWriter.drain`, and `StructuredQueryResultDto`.

`translate(sql)` keeps its exact signature and meaning — a `SqlTranslation` or a failure — because
`FilterSqlValidator` and `ExprSqlValidator` call it with SQL they construct themselves and must never
receive a metadata result.

## D2. Which relations, and how faithful to Dremio

**`TABLES`, `COLUMNS`, `SCHEMATA`.** The connector queries only `TABLES` and `COLUMNS`; `SCHEMATA` is
included because an `INFORMATION_SCHEMA` that cannot answer "what schemas are there" is a trap for the
next client, and it costs one static table.

**`CATALOGS` and `VIEWS` are omitted.** ADAS has no catalog namespace — inventing a catalog name here
would contradict the empty `SQL_CATALOG_TERM` the Flight endpoint declares — and it has no views.
Serving an empty `VIEWS` would be defensible; serving a fabricated `CATALOGS` row would not, and the
pair is easier to reason about as "the two levels ADAS has, and nothing invented".

**Column sets follow Dremio exactly** — 4 columns for `TABLES`, 17 for `COLUMNS`, 5 for `SCHEMATA` —
rather than only the six the connector reads. A client that does `SELECT *` gets the shape it expects,
and the cost is a static mapping table. Where ADAS has no answer the value is null, which is what
`COLUMN_DEFAULT` and the interval columns are in Dremio too.

**`TABLE_CATALOG` is the empty string**, matching the empty `SQL_CATALOG_TERM` the endpoint already
declares. Dremio puts the literal `DREMIO` there. Connector 3.14.0 does not filter on it; **3.12.0
did** (`WHERE TABLE_CATALOG = 'DREMIO'`), so a downgrade would break — recorded here so the symptom is
recognisable rather than mysterious.

**`DATA_TYPE` uses Dremio's spelling**, because the connector hard-errors on anything outside its
12-name table:

| ADAS `QueryFieldType` | `DATA_TYPE` |
|---|---|
| `STRING`, `UUID`, `OBJECT` | `CHARACTER VARYING` |
| `INTEGER` | `INTEGER` |
| `LONG` | `BIGINT` |
| `DECIMAL` | `DECIMAL` |
| `BOOLEAN` | `BOOLEAN` |
| `DATE` | `DATE` |
| `TIMESTAMP` | `TIMESTAMP` |
| `ARRAY` | `ARRAY` |

`OBJECT` maps to `CHARACTER VARYING` for the same reason the Calcite row type does: an object travels
as JSON text, and `OBJECT` is not one of the 12 names.

**`IS_NULLABLE` is `YES` for every column**, which is not a shrug — it is what the Arrow mapping
already does (`CalciteQueryCatalog` marks every field nullable, and `GetXdbcTypeInfo` reports
`NULLABILITY_NULLABLE` for every family). Dremio hardcodes `YES` as well.

**`COLUMNS` lists every declared field, heavy ones included**, matching what
`GET /v1/queries/entities/schema/{name}` returns. That is a deliberate choice with a visible cost,
observed in the runtime check: a client builds its projection from this list, so Power BI's generated
preview names `request_body` and `response_body` explicitly and pulls both payload columns.

The alternative — mirroring the wildcard projection, as `CommandGetTables` with `include_schema` does
— would make previews cheap but make the heavy columns *undiscoverable*, even though they remain
selectable by name. Listing them is the behaviour that matches discovery elsewhere and keeps the
catalog honest about what exists; the two surfaces differ because they answer different questions
("what columns are there" versus "what does `SELECT *` return"). If heavy payloads turn out to make
previews unusable in practice, hiding them here is a one-line change — but it should be made on that
evidence, not pre-emptively.

## D3. `adas` as the schema name, with bare names still canonical

The name is a contract, not a label: the connector feeds `TABLE_SCHEMA` straight back as the qualifier
on every data query. `adas` is short, stable, and unambiguous. It is a constant rather than a
configuration knob — a deployment-varying namespace name would make every saved report
deployment-specific for no benefit.

The empty string was considered and rejected: it produces `FROM ""."dial_usage_log"`, the exact
empty-qualifier parse error that `flight-sql-catalog-metadata` was written to eliminate.

Entity tables are registered **twice** in the Calcite root — once bare, once under `adas` — pointing at
the same table objects. Bare names therefore keep resolving exactly as today, which is what keeps this
change backward compatible and what keeps the ODBC path (which generates unqualified names) untouched.

## D4. Why the Flight catalog RPCs still declare no schema namespace

This is the one deliberate inconsistency in the change, and it is a judgement call worth stating
plainly.

After this change ADAS *does* have a schema namespace, so the sentence "ADAS exposes no catalog and no
schema namespace" in the catalog-RPC requirement becomes false and is corrected. But the RPCs
themselves keep streaming zero `GetDbSchemas` rows and null `db_schema_name`, and `GetSqlInfo` keeps
declaring an empty `SQL_SCHEMA_TERM`.

Reasoning:

- **The Dremio connector reads none of it.** It discovers through catalog SQL. Changing the RPCs buys
  this change nothing.
- **Changing them has a real blast radius.** `SQL_SCHEMA_TERM` is precisely what makes ODBC clients
  qualify generated statements. Declaring a schema would make every one of them switch from
  `FROM "x"` to `FROM "adas"."x"` — a behaviour change to the integration that currently works end to
  end, in the same change that is trying to make a different one work.
- **Bare names remain the form ADAS steers clients toward.** The qualifier exists to serve clients that
  must qualify because they read a schema name out of catalog SQL. Declaring "no schema" is still the
  advice ADAS wants to give anyone who asks.

The requirement's narrative is updated to say this explicitly, so the asymmetry is specified rather
than accidental. Declaring the namespace over the RPCs as well is a reasonable follow-up once the
DirectQuery question is settled.

## D5. Trailing separator

Stripped in `SqlQueryFrontEnd` before screening, so every caller — REST, Flight, both translate
endpoints — behaves the same.

Exactly **one** trailing `;` is removed, and only when it is the last non-whitespace character. That
placement cannot fall inside a string literal: a statement ending in a literal ends with a quote, not a
semicolon. Two separators still fail, as does a separator between statements — the "exactly one
statement" screen is unchanged, because tolerating a terminator is not the same as accepting a batch.

## D6. Governance

Metadata queries do not touch ClickHouse, so they bypass the ClickHouse-shaped controls by
construction: no row cap, no concurrency gate, no default page limit. This is correct rather than a
loophole — the row count is bounded by the catalog itself (entities times columns), the caller already
sees the same data through `GET /v1/queries/entities`, and holding a metadata call behind the
ClickHouse concurrency gate would make discovery fail exactly when the system is busy, which is the
behaviour "Catalog RPCs stay available at saturation" already rejects for the RPC path.

Access control is inherited rather than reimplemented. Rows are built from the same
`CatalogQueryService.findModel` the SQL front end, structured execution and the catalog RPCs already
resolve through, and that method is the column-level chokepoint: a caller who may not see sensitive
columns gets a model without them, so those columns are absent from `INFORMATION_SCHEMA.COLUMNS` for
exactly the callers who cannot select them. The entity list is `queryableEntityNames()`, so `TABLES`
lists precisely what `GET /v1/queries/entities` lists — no more, and no separately-maintained view that
could drift from it.
