# Tasks

## 1. Advertise the admissible `GetSqlInfo` values

- [x] 1.1 In `AdasFlightSqlProducer.getStreamSqlInfo`, extend the `SqlInfoBuilder` chain with the
      **identifier and search metadata**: `withSqlExtraNameCharacters("")`,
      `withSqlQuotedIdentifierCase(SQL_CASE_SENSITIVITY_CASE_INSENSITIVE)`,
      `withSqlSearchStringEscape("\\")`, `withSqlKeywords(new String[0])`.
      Keep the existing `withSqlIdentifierCase`/`withSqlIdentifierQuoteChar` calls adjacent so the
      identifier group reads as one block.
- [x] 1.2 Add the **absences** — empty enum bitmasks: `withSqlSupportedUnions()`,
      `withSqlSubQueriesSupported()`, `withSqlOuterJoinSupportLevel()`,
      `withSqlSupportedPositionedCommands()`, `withSqlSupportedTransactionsIsolationLevels()`;
      `withSqlSupportsConvert(Map.of())`; and `false` for
      `withSqlStoredProceduresSupported`, `withSqlStoredFunctionsUsingCallSyntaxSupported`,
      `withSqlCorrelatedSubqueriesSupported`, `withSqlSelectForUpdateSupported`,
      `withSqlBatchUpdatesSupported`, `withSqlSavepointsSupported`,
      `withSqlNamedParametersSupported`, `withSqlSupportsLikeEscapeClause`,
      `withSqlSupportsIntegrityEnhancementFacility`, `withSqlSupportsNonNullableColumns`,
      `withSqlDataDefinitionCausesTransactionCommit`, `withSqlDataDefinitionsInTransactionsIgnored`,
      `withSqlLocatorsUpdateCopy`, `withSqlCatalogAtStart`, `withSqlMaxRowSizeIncludesBlobs`.
      Plus `withSqlProcedureTerm("")` and `withSqlDefaultTransactionIsolation(0)`.
- [x] 1.3 Add the **limits**: `0` for `withSqlMaxCatalogNameLength`, `withSqlDbSchemaNameLength`,
      `withSqlMaxTableNameLength`, `withSqlMaxColumnNameLength`, `withSqlMaxCursorNameLength`,
      `withSqlMaxProcedureNameLength`, `withSqlMaxUsernameLength`, `withSqlMaxBinaryLiteralLength`,
      `withSqlMaxCharLiteralLength`, `withSqlMaxColumnsInGroupBy`, `withSqlMaxColumnsInIndex`,
      `withSqlMaxColumnsInOrderBy`, `withSqlMaxColumnsInSelect`, `withSqlMaxColumnsInTable`,
      `withSqlMaxConnections`, `withSqlMaxIndexLength`, `withSqlMaxRowSize`,
      `withSqlMaxStatementLength`, `withSqlMaxStatements` — and
      **`withSqlMaxTablesInSelect(1)`**, the one non-zero limit (single-entity `FROM`).
      Comment the `0` convention once for the group: it is "no limit imposed by ADAS", not a claim
      about ClickHouse.
- [x] 1.4 Add the **transport facts**:
      `withSqlSupportedResultSetTypes(SQL_RESULT_SET_TYPE_FORWARD_ONLY)`,
      `withFlightSqlServerCancel(false)`, `withFlightSqlServerBulkIngestion(false)`,
      `withFlightSqlServerBulkIngestionTransaction(false)`,
      `withFlightSqlServerStatementTimeout(0)`, `withFlightSqlServerTransactionTimeout(0)`.
- [x] 1.5 Add the **positive claims and the two deliberate under-claims**:
      `withSqlSupportsColumnAliasing(true)`, `withSqlSupportsTableCorrelationNames(true)`,
      `withSqlSupportsDifferentTableCorrelationNames(false)`,
      `withSqlAllTablesAreSelectable(true)`, `withSqlNullPlusNullIsNull(true)`,
      `withSqlSupportsExpressionsInOrderBy(false)`, `withSqlSupportsOrderByUnrelated(false)`.
      The last two are narrower than reality on purpose — a comment must say so and why
      (both are mixed: `ORDER BY count(*)` resolves, `ORDER BY price * 2` is rejected as arithmetic;
      the conservative answer steers a client to a form that always works).
- [x] 1.6 Determine `SQL_NULL_ORDERING` **by measurement, not derivation** (task 2.4 supplies the
      measurement): add `withSqlNullOrdering(<measured constant>)` matching the placement ClickHouse
      actually produces for a sort with no explicit `NULLS` clause. If the measurement contradicts
      the expected constant, the declaration follows the measurement.
- [x] 1.7 Do **not** add `withSqlNumericFunctions`, `withSqlStringFunctions`,
      `withSqlSystemFunctions`, `withSqlDatetimeFunctions`, `withSqlSupportedGrammar`,
      `withSqlAnsi92SupportedLevel`, or `withSqlSupportedGroupBy`. Extend the method's Javadoc with
      the admissibility rule (determined + non-steering) and the reason the function sets are held
      back, so the next person adding a value does not have to re-derive it.
- [x] 1.8 Keep the method readable: group the chain by concern (server identity / namespace /
      identifiers / absences / limits / transport / capabilities) with one comment per group, and
      preserve the existing namespace block and its Javadoc verbatim — it documents a shipped fix.
      Confirm the 180-char line limit and Google Java Style pass (`./gradlew checkstyleMain`).

## 2. Tests

- [x] 2.1 In `FlightSqlIntegrationTest`, extend the `GetSqlInfo` coverage with a test asserting the
      identifier group: `SQL_EXTRA_NAME_CHARACTERS` is present and `""`, `SQL_KEYWORDS` is present
      and empty, `SQL_SEARCH_STRING_ESCAPE` is `"\"`, `SQL_QUOTED_IDENTIFIER_CASE` is the
      case-insensitive constant. Assert **presence** explicitly (`containsKey`) — an absent key is
      the defect this change fixes, so a test that only checks values would pass on a regression.
- [x] 2.2 Add a test asserting the absences and the limits: the rejected-construct bitmasks are
      present and zero, `SQL_MAX_TABLES_IN_SELECT` is `1`, and the identifier-length maxima are
      present and `0`.
- [x] 2.3 Add a statement test pinning the table-correlation-name claim:
      `SELECT t.deployment FROM dial_usage_log t LIMIT 1` reaches `getFlightInfo` and streams. Add
      the quoted-identifier case claim the same way — a statement naming a column in a
      case-differing double-quoted form resolves.
- [x] 2.4 Add the `SQL_NULL_ORDERING` measurement test: sort a nullable column with no explicit
      `NULLS` clause over the Testcontainers ClickHouse, observe where nulls land, and assert the
      advertised constant matches. This test is what makes the value honest and what fails if
      ClickHouse's default changes.
- [x] 2.5 Add a test pinning the **cost** of the function-set exclusion: requesting
      `SQL_DATETIME_FUNCTIONS` fails the call. `SqlInfoBuilder.send` does `providers.get(code).accept(...)`
      with no null check, so an unadvertised code NPEs and `listener.error` aborts the whole response —
      the exclusion is not a silently-omitted row. The test documents the deliberate decision and its
      real consequence, and fails if someone adds a function set without doing the mapping work.
- [x] 2.6 Leave `sqlInfoDeclaresNoCatalogAndNoSchemaNamespace` untouched — it covers the shipped
      namespace declaration this change must not regress.
- [x] 2.7 Add the test backing `SQL_ALL_TABLES_ARE_SELECTABLE=true` in
      `FlightMetadataAuthIntegrationTest`: read the entity names `GetTables` streams to the read-only
      (non-admin) caller and issue `SELECT * FROM <name> LIMIT 1` with the same credentials against
      each. The catalog is *column*-filtered, not table-filtered, so the claim rests on ADAS gating no
      entity's SELECT — a fact only an executed statement can pin, and one a per-table read grant
      (`rework-uac-role-based-access`) would falsify.

## 3. Specs and verification

- [x] 3.1 Keep `openspec/changes/flight-sql-sqlinfo-completeness/specs/flight-sql-endpoint/spec.md`
      in sync with whatever the implementation actually declares — in particular, if the
      `SQL_NULL_ORDERING` measurement or any positive claim changes during implementation, the
      MODIFIED requirement and its scenarios change with it.
- [x] 3.2 If the "Implementation notes" section of `openspec/specs/flight-sql-endpoint/spec.md`
      needs a pointer to the admissibility rule, add it at sync time (archive step), not before.
- [x] 3.3 `./gradlew clean build` passes (compile + checkstyle + the full test suite, including the
      Testcontainers Flight integration tests).
- [x] 3.4 Runtime check with `FLIGHT_ENABLED=true ./gradlew bootRun` (never an IntelliJ run
      configuration — `build.gradle` attaches the required Arrow JVM flags only to `test` and
      `bootRun`, and without them every `DoGet` fails with a bare `internal error`): confirm the
      newly advertised codes stream values. Note that `GetSqlInfo` is reachable over Flight only —
      there is no REST surface for it, so the HTTP smoke checks cover regression, not this change.
