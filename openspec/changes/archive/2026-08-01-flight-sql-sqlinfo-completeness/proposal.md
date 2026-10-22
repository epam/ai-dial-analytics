## Why

`AdasFlightSqlProducer.getStreamSqlInfo` advertises 17 of the ~60 `CommandGetSqlInfo` values the
Flight SQL protocol defines. A value ADAS never supplies is not a default for an ODBC client — it is
an **error**. Measured against the Dremio Arrow Flight SQL ODBC driver 0.9.7 with `pyodbc.getinfo`:

```
SQL_SPECIAL_CHARACTERS     <Error>
SQL_TIMEDATE_FUNCTIONS     <Error>
```

Every one of these is a question about ADAS whose answer is already fixed by shipped behaviour —
"which non-alphanumeric characters may appear in an unquoted identifier?" is `none`; "how long may a
table name be?" is "ADAS imposes no limit"; "are outer joins supported?" is `no`, because
`sql-query-translation` rejects every JOIN. Leaving them unanswered turns a settled fact into a
client-side failure, and a BI tool that hits one during capability discovery reports it as a
connection-level error rather than degrading.

The namespace half of this problem is **already fixed**: `SQL_CATALOG_TERM`, `SQL_SCHEMA_TERM`, and
both `*_SUPPORTED_ACTIONS` bitmasks were shipped with `flight-sql-catalog-metadata`
(`AdasFlightSqlProducer.java:314-319`, spec requirement "Capability advertisement declares no catalog
and no schema namespace", test `FlightSqlIntegrationTest.sqlInfoDeclaresNoCatalogAndNoSchemaNamespace`).
The `from ""."rate_analytics"` parse error that motivated it no longer reproduces. This change finishes the surrounding job: the remaining values that are plain facts.

## What Changes

- `getStreamSqlInfo` advertises the remaining `CommandGetSqlInfo` values that are **determined by
  already-specified ADAS behaviour and cannot steer a client into generating SQL that ADAS rejects**:
  - identifier metadata — `SQL_EXTRA_NAME_CHARACTERS` (empty: ODBC's `SQL_SPECIAL_CHARACTERS`, the
    measured error), `SQL_QUOTED_IDENTIFIER_CASE`, `SQL_SEARCH_STRING_ESCAPE`, `SQL_KEYWORDS`
    (empty — ADAS adds no keyword to standard SQL);
  - the unsupported-construct set as **absences** — no unions, no subqueries, no outer joins, no
    `CONVERT`, no positioned commands, no transaction isolation levels, no stored procedures, no
    savepoints, no batch updates, no named parameters, no `SELECT FOR UPDATE`, no `LIKE ESCAPE`
    clause, no correlated subqueries, no integrity-enhancement facility;
  - limits — every `SQL_MAX_*` name-length and size value as `0` ("no limit imposed"), except
    `SQL_MAX_TABLES_IN_SELECT`, which is `1` because the translator accepts a single-entity `FROM`
    only;
  - transport facts — forward-only result sets, no query cancellation, no bulk ingestion, no
    statement or transaction timeout;
  - the positive claims ADAS genuinely makes — column aliasing, table correlation names, all listed
    tables selectable — each pinned by a test in this change rather than asserted.
- No behavioural change to the SQL front end, to any other RPC, or to REST. `getStreamSqlInfo` still
  answers only the codes the client asked for, so the wire cost is unchanged for existing clients.

**Explicitly not changed**: the function-support sets (`SQL_STRING_FUNCTIONS`,
`SQL_NUMERIC_FUNCTIONS`, `SQL_SYSTEM_FUNCTIONS`, `SQL_DATETIME_FUNCTIONS` — ODBC
`SQL_TIMEDATE_FUNCTIONS`), the SQL-grammar level (`SQL_SUPPORTED_GRAMMAR`,
`SQL_ANSI92_SUPPORTED_LEVEL`), and `SQL_SUPPORTED_GROUP_BY`. Those are claims about what ADAS can
*execute*: over-claiming makes Power Query fold an expression into SQL the translator then rejects,
turning a working local computation into a hard error. `SQL_TIMEDATE_FUNCTIONS` therefore keeps
erroring after this change — deliberately. See design.md's follow-up note.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `flight-sql-endpoint`: the existing requirement "Capability advertisement declares no catalog and
  no schema namespace" is widened into a requirement covering the whole `CommandGetSqlInfo`
  response — what ADAS SHALL advertise, and the rule that governs which values are admissible
  (a value is advertised only when its answer follows from specified behaviour and cannot cause a
  client to generate rejected SQL). The catalog/schema declaration is retained verbatim as part of it.

## Impact

- `src/main/java/com/epam/aidial/analytics/web/flight/AdasFlightSqlProducer.java` —
  `getStreamSqlInfo` only.
- `src/test/java/com/epam/aidial/analytics/integration/FlightSqlIntegrationTest.java` — extends the
  existing `GetSqlInfo` coverage and adds statement-level tests pinning the positive claims.
- No configuration, no migration, no dependency change, no `docs/configuration.md` change.
- Clients: an ODBC/JDBC driver over Flight SQL answers instead of erroring for the newly advertised
  codes. A client that already worked sees no behavioural difference — every added value either
  states an absence or matches what the driver was already assuming.
