package com.epam.aidial.analytics.service.query.sql;

import com.epam.aidial.analytics.exception.SqlTranslationException;
import com.epam.aidial.analytics.service.query.CatalogQueryService;
import com.epam.aidial.analytics.service.query.DialUsageLogTestModel;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Coverage for {@code specs/sql-query-translation/spec.md}'s catalog-metadata, schema-qualified-name
 * and trailing-separator requirements.
 *
 * <p>The three statements under "the connector's statements" are quoted **verbatim** from Power BI's
 * Dremio connector 3.14.0 (`DremioAdbcDataSource.pqm`), captured in
 * {@code local_env/powerbi/dremio-connector-findings.md} — including the indentation, the comment
 * marker, and the trailing semicolon on the column query. They are the reason this surface exists, so
 * a paraphrase would not be evidence that it works.
 */
class InformationSchemaSqlTest {

    /** The twelve names a Dremio-compatible client will accept; anything else aborts its navigation. */
    private static final Set<String> ACCEPTED_TYPE_NAMES = Set.of(
            "CHARACTER VARYING", "DOUBLE", "INTEGER", "BIGINT", "DATE", "TIMESTAMP",
            "TIME", "ARRAY", "BOOLEAN", "DECIMAL", "FLOAT", "BINARY VARYING");

    private final SqlQueryFrontEnd frontEnd = newFrontEnd();

    private static SqlQueryFrontEnd newFrontEnd() {
        final CatalogQueryService catalog = mock(CatalogQueryService.class);
        when(catalog.queryableEntityNames(any())).thenReturn(List.of(DialUsageLogTestModel.ENTITY));
        when(catalog.findModel(eq(DialUsageLogTestModel.ENTITY), any())).thenReturn(Optional.of(DialUsageLogTestModel.model()));
        return new SqlQueryFrontEnd(new CalciteQueryCatalog(catalog));
    }

    private SqlMetadataResult metadata(String sql) {
        final SqlStatementPlan plan = frontEnd.plan(sql);
        assertThat(plan).isInstanceOf(SqlMetadataResult.class);
        return (SqlMetadataResult) plan;
    }

    // ---- The connector's statements, verbatim ----------------------------------------------

    @Test
    void answersTheConnectorSchemaQuery() {
        final SqlMetadataResult result = metadata("""
                /* PBI ADBC metadata: GetSchemas */
                                SELECT DISTINCT TABLE_SCHEMA
                                FROM INFORMATION_SCHEMA."TABLES"
                                WHERE TABLE_TYPE <> 'SYSTEM_TABLE'
                                ORDER BY TABLE_SCHEMA""");

        assertThat(result.columns()).singleElement()
                .satisfies(column -> assertThat(column.name()).isEqualTo("TABLE_SCHEMA"));
        assertThat(result.rows()).containsExactly(Map.of("TABLE_SCHEMA", InformationSchema.DATA_SCHEMA));
    }

    @Test
    void answersTheConnectorTableQuery() {
        final SqlMetadataResult result = metadata("""
                /* PBI ADBC metadata: GetTables */
                                SELECT TABLE_NAME, TABLE_TYPE
                                FROM INFORMATION_SCHEMA."TABLES"
                                WHERE TABLE_SCHEMA = 'adas'
                                AND TABLE_TYPE <> 'SYSTEM_TABLE'""");

        assertThat(result.rows())
                .containsExactly(Map.of("TABLE_NAME", DialUsageLogTestModel.ENTITY, "TABLE_TYPE", "TABLE"));
    }

    @Test
    void answersTheConnectorColumnQueryIncludingItsTrailingSeparator() {
        final SqlMetadataResult result = metadata("""
                /* PBI ADBC metadata: GetTableType */
                                SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE
                                FROM INFORMATION_SCHEMA.COLUMNS
                                WHERE TABLE_SCHEMA = 'adas'
                                AND TABLE_NAME = 'dial_usage_log';""");

        assertThat(result.columns()).extracting(ResultColumn::name)
                .containsExactly("COLUMN_NAME", "DATA_TYPE", "IS_NULLABLE");
        assertThat(result.rows()).hasSize(DialUsageLogTestModel.model().bindings().size());
        // Every declared field is described, every type name is one the connector accepts, and
        // IS_NULLABLE is the string the connector compares against rather than a boolean.
        assertThat(result.rows()).allSatisfy(row -> {
            assertThat(row.get("DATA_TYPE")).isIn(ACCEPTED_TYPE_NAMES);
            assertThat(row.get("IS_NULLABLE")).isEqualTo("YES");
        });
        assertThat(result.rows()).extracting(row -> row.get("COLUMN_NAME"))
                .contains("event_kind", "total_tokens", "request_time");
    }

    // ---- Type vocabulary --------------------------------------------------------------------

    @Test
    void reportsDremioTypeNamesNotCalciteOnes() {
        final Map<String, Object> byColumn = typeByColumn();

        assertThat(byColumn.get("event_kind")).isEqualTo("CHARACTER VARYING");
        assertThat(byColumn.get("event_id")).isEqualTo("CHARACTER VARYING");        // uuid
        assertThat(byColumn.get("dial_usage_log_payload.jwt_claims")).isEqualTo("CHARACTER VARYING");      // object travels as JSON text
        assertThat(byColumn.get("total_tokens")).isEqualTo("BIGINT");
        assertThat(byColumn.get("response_status")).isEqualTo("INTEGER");
        assertThat(byColumn.get("total_price")).isEqualTo("DECIMAL");
        assertThat(byColumn.get("success")).isEqualTo("BOOLEAN");
        assertThat(byColumn.get("request_time")).isEqualTo("TIMESTAMP");
        assertThat(byColumn.get("execution_path")).isEqualTo("ARRAY");
        assertThat(byColumn.values()).doesNotContain("VARCHAR", "INT");
    }

    private Map<String, Object> typeByColumn() {
        return metadata("SELECT COLUMN_NAME, DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_NAME = 'dial_usage_log'")
                .rows().stream()
                .collect(java.util.stream.Collectors.toMap(
                        row -> (String) row.get("COLUMN_NAME"), row -> row.get("DATA_TYPE")));
    }

    // ---- Relation shapes --------------------------------------------------------------------

    @Test
    void tablesCarriesDremiosFourColumnsInOrder() {
        assertThat(metadata("SELECT * FROM INFORMATION_SCHEMA.\"TABLES\"").columns())
                .extracting(ResultColumn::name)
                .containsExactly("TABLE_CATALOG", "TABLE_SCHEMA", "TABLE_NAME", "TABLE_TYPE");
    }

    @Test
    void columnsCarriesDremiosSeventeenColumnsInOrder() {
        assertThat(metadata("SELECT * FROM INFORMATION_SCHEMA.COLUMNS").columns())
                .extracting(ResultColumn::name)
                .containsExactly(
                        "TABLE_CATALOG", "TABLE_SCHEMA", "TABLE_NAME", "COLUMN_NAME", "ORDINAL_POSITION",
                        "COLUMN_DEFAULT", "IS_NULLABLE", "DATA_TYPE", "COLUMN_SIZE",
                        "CHARACTER_MAXIMUM_LENGTH", "CHARACTER_OCTET_LENGTH", "NUMERIC_PRECISION",
                        "NUMERIC_PRECISION_RADIX", "NUMERIC_SCALE", "DATETIME_PRECISION",
                        "INTERVAL_TYPE", "INTERVAL_PRECISION");
    }

    @Test
    void ordinalPositionIsOneBasedAndCatalogIsEmpty() {
        final Map<String, Object> first = metadata(
                "SELECT ORDINAL_POSITION, TABLE_CATALOG FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_NAME = 'dial_usage_log' ORDER BY ORDINAL_POSITION").rows().getFirst();

        assertThat(first.get("ORDINAL_POSITION")).isEqualTo(1);
        assertThat(first.get("TABLE_CATALOG")).isEqualTo("");
    }

    @Test
    void schemataListsBothSchemas() {
        final SqlMetadataResult result = metadata("SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA");

        assertThat(result.rows()).extracting(row -> row.get("SCHEMA_NAME"))
                .containsExactlyInAnyOrder(InformationSchema.DATA_SCHEMA, "INFORMATION_SCHEMA");
    }

    @Test
    void catalogRelationsAreListedAsSystemTables() {
        final SqlMetadataResult result = metadata(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.\"TABLES\" WHERE TABLE_TYPE = 'SYSTEM_TABLE'");

        assertThat(result.rows()).extracting(row -> row.get("TABLE_NAME"))
                .containsExactlyInAnyOrder("TABLES", "COLUMNS", "SCHEMATA");
    }

    // ---- Resolution and rejection ------------------------------------------------------------

    @Test
    void resolvesCaseInsensitivelyQuotedOrNot() {
        // Only the relation and schema names are matched case-insensitively; the result column keeps
        // the case the client wrote it in, as every other ADAS select item does. The connector writes
        // and reads uppercase throughout, so the two never disagree in practice.
        final List<Object> expected = tableNames("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.\"TABLES\"", "TABLE_NAME");

        assertThat(tableNames("select table_name from information_schema.tables", "table_name"))
                .isEqualTo(expected);
        assertThat(tableNames("SELECT TABLE_NAME FROM Information_Schema.\"tables\"", "TABLE_NAME"))
                .isEqualTo(expected);
        assertThat(tableNames("SELECT TABLE_NAME FROM information_schema.COLUMNS", "TABLE_NAME"))
                .isNotEmpty();
    }

    private List<Object> tableNames(String sql, String column) {
        return metadata(sql).rows().stream().map(row -> row.get(column)).toList();
    }

    @Test
    void supportsTheStatementShapesTheSurfaceAdvertises() {
        assertThat(metadata("SELECT DISTINCT TABLE_TYPE FROM INFORMATION_SCHEMA.\"TABLES\" "
                + "ORDER BY TABLE_TYPE").rows())
                .extracting(row -> row.get("TABLE_TYPE"))
                .containsExactly("SYSTEM_TABLE", "TABLE");
        assertThat(metadata("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.\"TABLES\" "
                + "ORDER BY TABLE_NAME LIMIT 2 OFFSET 1").rows()).hasSize(2);
    }

    @Test
    void evaluatesAggregatesWithStandardSemantics() {
        assertThat(metadata("SELECT TABLE_SCHEMA, COUNT(*) AS n FROM INFORMATION_SCHEMA.COLUMNS "
                + "GROUP BY TABLE_SCHEMA ORDER BY TABLE_SCHEMA").rows())
                .extracting(row -> row.get("TABLE_SCHEMA"))
                .containsExactly(InformationSchema.SCHEMA, InformationSchema.DATA_SCHEMA);
        // The three catalog relations describe themselves: 4 + 17 + 5 columns. COUNT is BIGINT, so the
        // value arrives as a Long — the same widening a client reads off the Arrow schema.
        assertThat(metadata("SELECT COUNT(*) AS n FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_SCHEMA = 'INFORMATION_SCHEMA'").rows())
                .containsExactly(Map.of("n", 26L));
        assertThat(metadata("SELECT TABLE_NAME, COUNT(*) AS n FROM INFORMATION_SCHEMA.COLUMNS "
                + "GROUP BY TABLE_NAME HAVING COUNT(*) = 17").rows())
                .extracting(row -> row.get("TABLE_NAME"))
                .containsExactly("COLUMNS");
        // COLUMN_DEFAULT is null in every row, so counting it must not count rows.
        assertThat(metadata("SELECT COUNT(COLUMN_DEFAULT) AS n FROM INFORMATION_SCHEMA.COLUMNS").rows())
                .containsExactly(Map.of("n", 0L));
        assertThat(metadata("SELECT MIN(ORDINAL_POSITION) AS lo, MAX(ORDINAL_POSITION) AS hi "
                + "FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'SCHEMATA'").rows())
                .containsExactly(Map.of("lo", 1, "hi", 5));
    }

    /**
     * SQL requires a grouping-less aggregate to produce exactly one row whatever the input, and Calcite's
     * interpreter produces none when the input is empty. The gap is reachable through the most ordinary
     * catalog probe there is — "does this object exist" — where an empty result set instead of {@code 0}
     * reads to a JDBC-shaped client as "no data" rather than "none".
     */
    @Test
    void globalAggregateOverAnEmptyInputStillReturnsItsRow() {
        assertThat(metadata("SELECT COUNT(*) AS n FROM INFORMATION_SCHEMA.\"TABLES\" "
                + "WHERE TABLE_NAME = 'no_such_table'").rows())
                .containsExactly(Map.of("n", 0L));
        // COUNT answers 0; every other aggregate answers null. Both in one row.
        assertThat(metadata("SELECT COUNT(*) AS n, MAX(ORDINAL_POSITION) AS m, MIN(TABLE_NAME) AS lo "
                + "FROM INFORMATION_SCHEMA.COLUMNS WHERE 1 = 0").rows())
                .singleElement()
                .satisfies(row -> {
                    assertThat(row).containsEntry("n", 0L);
                    assertThat(row.get("m")).isNull();
                    assertThat(row.get("lo")).isNull();
                });
        // An ORDER BY over the single row keeps it; a populated input is unaffected.
        assertThat(metadata("SELECT COUNT(*) AS n FROM INFORMATION_SCHEMA.\"TABLES\" WHERE 1 = 0 ORDER BY 1").rows())
                .containsExactly(Map.of("n", 0L));
        assertThat(metadata("SELECT COUNT(*) AS n FROM INFORMATION_SCHEMA.\"TABLES\"").rows())
                .containsExactly(Map.of("n", 4L));

        // Two shapes where returning nothing is the correct answer and the row must NOT be invented: an
        // OFFSET steps past it, and a grouped aggregate over an empty input has no groups to report.
        assertThat(metadata("SELECT COUNT(*) AS n FROM INFORMATION_SCHEMA.\"TABLES\" "
                + "WHERE 1 = 0 OFFSET 1 ROWS").rows()).isEmpty();
        assertThat(metadata("SELECT TABLE_SCHEMA, COUNT(*) AS n FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE 1 = 0 GROUP BY TABLE_SCHEMA").rows()).isEmpty();
    }

    /**
     * The two global-aggregate shapes whose empty-input row this surface cannot produce — it would have to
     * evaluate a predicate or an expression over the synthesized row. They are refused on the plan's shape,
     * so the refusal does not depend on whether the catalog happened to match: a statement that answered
     * correctly against a populated catalog and wrongly against an empty filter match would be a bug that
     * only appears on someone else's deployment.
     */
    @Test
    void refusesGlobalAggregateShapesWhoseEmptyRowItCannotProduce() {
        for (final String sql : List.of(
                "SELECT COUNT(*) + 1 AS n FROM INFORMATION_SCHEMA.\"TABLES\" WHERE 1 = 0",
                "SELECT COUNT(*) + 1 AS n FROM INFORMATION_SCHEMA.\"TABLES\"",
                "SELECT COUNT(*) AS n FROM INFORMATION_SCHEMA.\"TABLES\" WHERE 1 = 0 HAVING COUNT(*) = 0",
                "SELECT COUNT(*) AS n FROM INFORMATION_SCHEMA.\"TABLES\" HAVING COUNT(*) > 0")) {
            assertThatThrownBy(() -> frontEnd.plan(sql))
                    .as(sql)
                    .isInstanceOf(SqlTranslationException.class)
                    .hasMessageContaining("global aggregate cannot be evaluated");
        }
        // Grouped HAVING is unaffected — it has groups, so it never owes a row for an empty input.
        assertThat(metadata("SELECT TABLE_NAME, COUNT(*) AS n FROM INFORMATION_SCHEMA.COLUMNS "
                + "GROUP BY TABLE_NAME HAVING COUNT(*) = 17").rows()).hasSize(1);
    }

    /**
     * Aggregating a <em>grouping-less</em> aggregate is refused, and the reason is the empty-input rule
     * rather than taste. Such an inner aggregate owes one row even over an empty input, so the outer
     * aggregate would have to be computed over that row — while the interpreter, having never produced the
     * inner row, reports nothing at all. Accepted, {@code COUNT(n)} over an empty inner {@code COUNT(*)}
     * answers 0 where 1 is correct, and the same statement answers correctly whenever the catalog does
     * match: a wrong number that depends on the deployment, which is what this screen exists to rule out.
     */
    @Test
    void refusesAggregatingOverGroupinglessAggregate() {
        for (final String sql : List.of(
                "SELECT COUNT(n) AS c FROM (SELECT COUNT(*) AS n FROM INFORMATION_SCHEMA.\"TABLES\" "
                        + "WHERE 1 = 0) x",
                "SELECT SUM(n) AS s FROM (SELECT COUNT(*) AS n FROM INFORMATION_SCHEMA.\"TABLES\" "
                        + "WHERE 1 = 0) x",
                // Refused on shape, so the populated form goes the same way as the empty one.
                "SELECT SUM(n) AS s FROM (SELECT COUNT(*) AS n FROM INFORMATION_SCHEMA.\"TABLES\") x")) {
            assertThatThrownBy(() -> frontEnd.plan(sql))
                    .as(sql)
                    .isInstanceOf(SqlTranslationException.class)
                    .hasMessageContaining("aggregate over a grouping-less aggregate");
        }
        // A derived table without nested aggregation still works, aggregated or not.
        assertThat(metadata("SELECT n FROM (SELECT COUNT(*) AS n FROM INFORMATION_SCHEMA.\"TABLES\") x").rows())
                .containsExactly(Map.of("n", 4L));
        assertThat(metadata("SELECT COUNT(*) AS n FROM (SELECT TABLE_NAME FROM INFORMATION_SCHEMA.\"TABLES\" "
                + "WHERE TABLE_TYPE = 'SYSTEM_TABLE') x").rows())
                .containsExactly(Map.of("n", 3L));
    }

    /**
     * The catalog path's expression surface is wider than the data path's, and deliberately so: nothing
     * here has to be expressed in the DSL, so arithmetic, {@code CASE} and {@code CAST} — all rejected
     * against an entity because the DSL has no equivalent — simply evaluate. The endpoint's own OpenAPI
     * description and `docs/configuration.md` now say this, so it is pinned rather than assumed.
     */
    @Test
    void expressionsTheDataPathRejectsEvaluateOverTheCatalog() {
        assertThat(metadata("SELECT ORDINAL_POSITION + 1 AS p FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_NAME = 'SCHEMATA' ORDER BY p").rows())
                .extracting(row -> row.get("p"))
                .containsExactly(2, 3, 4, 5, 6);
        assertThat(metadata("SELECT CAST(ORDINAL_POSITION AS BIGINT) AS p FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_NAME = 'SCHEMATA' ORDER BY p LIMIT 1").rows())
                .containsExactly(Map.of("p", 1L));
        assertThat(metadata("SELECT CASE WHEN TABLE_TYPE = 'TABLE' THEN 'entity' ELSE 'catalog' END AS kind "
                + "FROM INFORMATION_SCHEMA.\"TABLES\" WHERE TABLE_NAME = 'dial_usage_log'").rows())
                .singleElement()
                .satisfies(row -> assertThat(row.get("kind")).asString().startsWith("entity"));
        assertThat(metadata("SELECT COALESCE(COLUMN_DEFAULT, 'none') AS d FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_NAME = 'SCHEMATA' LIMIT 1").rows())
                .containsExactly(Map.of("d", "none"));
        // Arithmetic, CASE and COALESCE are expressible against an entity too now (arithmetic lowers to the
        // catalog's plus, the other two to the case expression), so the divide between the two paths has
        // closed for these; a general CAST still marks it — the data path tolerates only the identity,
        // widening and literal-to-timestamp forms.
        assertThatCode(() -> frontEnd.translate("SELECT prompt_tokens + 1 AS p FROM dial_usage_log"))
                .doesNotThrowAnyException();
        assertThatCode(() -> frontEnd.translate(
                "SELECT CASE WHEN success THEN 'y' ELSE 'n' END AS k FROM dial_usage_log"))
                .doesNotThrowAnyException();
        assertThatCode(() -> frontEnd.translate(
                "SELECT COALESCE(deployment, 'none') AS d FROM dial_usage_log"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT CAST(deployment AS BIGINT) AS d FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class);
    }

    /**
     * The screen above must test for a grouping-less inner aggregate, not for any aggregate. {@code SELECT
     * DISTINCT} and a bare {@code GROUP BY} both lower to an aggregate too, and neither owes a row for an
     * empty input — so rejecting every nested aggregate would take out {@code COUNT(*)} over a
     * {@code DISTINCT} subquery. That is the only way this surface can count distinct values, and the form
     * the DISTINCT-aggregate rejection recommends: refusing it would leave that advice pointing nowhere.
     */
    @Test
    void countingOverDistinctOrGroupedSubqueryIsAllowed() {
        assertThat(metadata("SELECT COUNT(*) AS n FROM "
                + "(SELECT DISTINCT TABLE_SCHEMA FROM INFORMATION_SCHEMA.COLUMNS) x").rows())
                .containsExactly(Map.of("n", 2L));
        assertThat(metadata("SELECT COUNT(*) AS n FROM "
                + "(SELECT TABLE_SCHEMA FROM INFORMATION_SCHEMA.COLUMNS GROUP BY TABLE_SCHEMA) x").rows())
                .containsExactly(Map.of("n", 2L));
        // The empty-input row is still owed by the outer aggregate, and the grouped inner owes none.
        assertThat(metadata("SELECT COUNT(*) AS n FROM (SELECT DISTINCT TABLE_SCHEMA "
                + "FROM INFORMATION_SCHEMA.COLUMNS WHERE 1 = 0) x").rows())
                .containsExactly(Map.of("n", 0L));
        // An aggregate over a grouped aggregate: the inner owes nothing, so this is answerable. The count
        // is the data schema's column total, so it tracks the fixture (and the catalog the fixture mirrors)
        // rather than being a constant of this test.
        assertThat(metadata("SELECT MAX(n) AS mx FROM (SELECT TABLE_SCHEMA, COUNT(*) AS n "
                + "FROM INFORMATION_SCHEMA.COLUMNS GROUP BY TABLE_SCHEMA) x").rows())
                .containsExactly(Map.of("mx", (long) DialUsageLogTestModel.model().bindings().size()));
        assertThat(metadata("SELECT TABLE_SCHEMA, COUNT(*) AS n FROM (SELECT DISTINCT TABLE_SCHEMA, TABLE_NAME "
                + "FROM INFORMATION_SCHEMA.COLUMNS) x GROUP BY TABLE_SCHEMA ORDER BY TABLE_SCHEMA").rows())
                .containsExactly(
                        Map.of("TABLE_SCHEMA", InformationSchema.SCHEMA, "n", 3L),
                        Map.of("TABLE_SCHEMA", InformationSchema.DATA_SCHEMA, "n", 1L));
    }

    /**
     * Calcite carries a {@code DATE} as an epoch-day {@code Integer} and a {@code TIMESTAMP} as epoch
     * millis. Those must be converted where the rows are made, not left to the encoders: the Arrow writer
     * has no coercion from {@code Integer} to a date column, so an unconverted value escapes the
     * evaluator's containment and fails <em>mid-stream</em> as gRPC {@code INTERNAL} — and over REST both
     * would serialize as bare integers where every other ADAS date is an ISO string.
     */
    @Test
    void temporalValuesCarryTheSameJavaTypesAsQueryResults() {
        assertThat(metadata("SELECT DATE '2020-01-01' AS d FROM INFORMATION_SCHEMA.\"TABLES\" LIMIT 1").rows())
                .containsExactly(Map.of("d", LocalDate.of(2020, 1, 1)));
        assertThat(metadata("SELECT TIMESTAMP '2020-01-01 10:00:00' AS t "
                + "FROM INFORMATION_SCHEMA.\"TABLES\" LIMIT 1").rows())
                .containsExactly(Map.of("t", LocalDateTime.of(2020, 1, 1, 10, 0)));
    }

    /**
     * The in-process evaluator drops a {@code DISTINCT} on an aggregate call — Calcite's interpreter
     * ignores {@code AggregateCall.isDistinct()} and this front end runs no planner rule that would
     * rewrite it — so {@code COUNT(DISTINCT TABLE_SCHEMA)} would answer with the total row count. A
     * plausible wrong number is the one outcome this surface must not produce, so the statement is
     * refused and the message names the construct that does work.
     */
    @Test
    void refusesDistinctAggregatesRatherThanAnsweringThemWrongly() {
        for (final String sql : List.of(
                "SELECT COUNT(DISTINCT TABLE_SCHEMA) AS n FROM INFORMATION_SCHEMA.COLUMNS",
                "SELECT SUM(DISTINCT ORDINAL_POSITION) AS s FROM INFORMATION_SCHEMA.COLUMNS",
                "SELECT TABLE_NAME, COUNT(DISTINCT DATA_TYPE) AS n FROM INFORMATION_SCHEMA.COLUMNS "
                        + "GROUP BY TABLE_NAME")) {
            assertThatThrownBy(() -> frontEnd.plan(sql))
                    .as(sql)
                    .isInstanceOf(SqlTranslationException.class)
                    .hasMessageContaining("DISTINCT aggregate cannot be evaluated")
                    .hasMessageContaining("use SELECT DISTINCT");
        }
        // The construct the message recommends answers the same question, correctly.
        assertThat(metadata("SELECT DISTINCT TABLE_SCHEMA FROM INFORMATION_SCHEMA.COLUMNS").rows()).hasSize(2);
    }

    /**
     * Every shape the interpreter cannot evaluate SHALL reach the client as a client error. Left
     * unscreened these escape as {@code UnsupportedOperationException} / {@code ClassCastException} /
     * {@code NullPointerException}, which the handlers render as {@code 500 internal_error} and gRPC
     * {@code INTERNAL} — telling a client ADAS broke when its statement was simply outside the surface.
     */
    @Test
    void reportsUnevaluableConstructsAsClientErrorsNotInternalOnes() {
        final Map<String, String> unsupported = new java.util.LinkedHashMap<>();
        unsupported.put("SELECT t.TABLE_NAME FROM INFORMATION_SCHEMA.\"TABLES\" t "
                + "JOIN INFORMATION_SCHEMA.COLUMNS c ON t.TABLE_NAME = c.TABLE_NAME", "LogicalJoin");
        unsupported.put("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.\"TABLES\" WHERE TABLE_NAME IN "
                + "(SELECT TABLE_NAME FROM INFORMATION_SCHEMA.COLUMNS)", "subquery");
        unsupported.put("SELECT TABLE_NAME, ROW_NUMBER() OVER (ORDER BY TABLE_NAME) AS rn "
                + "FROM INFORMATION_SCHEMA.\"TABLES\"", "window function");
        unsupported.put("SELECT TABLE_SCHEMA, COUNT(*) AS n FROM INFORMATION_SCHEMA.COLUMNS "
                + "GROUP BY ROLLUP(TABLE_SCHEMA)", "GROUPING SETS");
        // Not screened by shape — MIN over a character column parses and validates, then throws inside
        // the interpreter. The catch-all keeps even that a 400.
        unsupported.put("SELECT MIN(TABLE_NAME) AS lo FROM INFORMATION_SCHEMA.\"TABLES\"",
                "cannot be evaluated over INFORMATION_SCHEMA");

        unsupported.forEach((sql, expected) -> assertThatThrownBy(() -> frontEnd.plan(sql))
                .as(sql)
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining(expected));
    }

    @Test
    void rejectsMixingCatalogRelationsWithAnEntity() {
        // Both mixing shapes must name which halves collided, rather than reporting a join that "cannot
        // be expressed" (the data path's message) or an unknown entity named TABLES.
        for (final String sql : List.of(
                "SELECT t.TABLE_NAME FROM INFORMATION_SCHEMA.\"TABLES\" t, dial_usage_log d",
                // The entity here hides inside a RexSubQuery, which plan-input walks do not reach.
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.\"TABLES\" WHERE TABLE_NAME IN "
                        + "(SELECT event_kind FROM dial_usage_log)",
                "SELECT event_kind FROM dial_usage_log WHERE event_kind IN "
                        + "(SELECT TABLE_NAME FROM INFORMATION_SCHEMA.\"TABLES\")")) {
            assertThatThrownBy(() -> frontEnd.plan(sql))
                    .as(sql)
                    .isInstanceOf(SqlTranslationException.class)
                    .hasMessageContaining("cannot combine INFORMATION_SCHEMA with a queryable entity");
        }
    }

    @Test
    void translateRejectsCatalogStatement() {
        assertThatThrownBy(() -> frontEnd.translate("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.\"TABLES\""))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("no structured-query equivalent");
    }

    @Test
    void columnHiddenFromCallerIsAbsentFromColumns() {
        // The rows come from the caller's resolved model, so the sensitive-column chokepoint in
        // CatalogQueryService.buildModel hides the column here without this surface knowing about it.
        final Map<String, com.epam.aidial.analytics.service.query.QueryFieldBinding> bindings =
                new java.util.LinkedHashMap<>(DialUsageLogTestModel.model().bindings());
        bindings.remove("dial_usage_log_payload.jwt_claims");
        final com.epam.aidial.analytics.service.query.QueryEntityModel filtered =
                new com.epam.aidial.analytics.service.query.QueryEntityModel(
                        DialUsageLogTestModel.ENTITY,
                        org.jooq.impl.DSL.table(org.jooq.impl.DSL.name(DialUsageLogTestModel.ENTITY)),
                        bindings,
                        List.of(),
                        DialUsageLogTestModel.model().baseSchema().stream()
                                .filter(field -> !"dial_usage_log_payload.jwt_claims".equals(field.name()))
                                .toList());
        final CatalogQueryService catalog = mock(CatalogQueryService.class);
        when(catalog.queryableEntityNames(any())).thenReturn(List.of(DialUsageLogTestModel.ENTITY));
        when(catalog.findModel(eq(DialUsageLogTestModel.ENTITY), any())).thenReturn(Optional.of(filtered));
        final SqlQueryFrontEnd restricted = new SqlQueryFrontEnd(new CalciteQueryCatalog(catalog));

        final SqlMetadataResult result = (SqlMetadataResult) restricted.plan(
                "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'dial_usage_log'");

        assertThat(result.rows()).extracting(row -> row.get("COLUMN_NAME"))
                .doesNotContain("dial_usage_log_payload.jwt_claims")
                .contains("dial_usage_log_payload.request_tags");
    }

    // ---- Schema-qualified entity names --------------------------------------------------------

    @Test
    void qualifiedAndBareEntityNamesTranslateIdentically() {
        final SqlTranslation bare = frontEnd.translate("SELECT event_kind FROM dial_usage_log LIMIT 5");

        assertThat(frontEnd.translate("SELECT event_kind FROM adas.dial_usage_log LIMIT 5"))
                .isEqualTo(bare);
        assertThat(frontEnd.translate("SELECT event_kind FROM \"adas\".\"dial_usage_log\" LIMIT 5"))
                .isEqualTo(bare);
        assertThat(frontEnd.translate("SELECT event_kind FROM \"ADAS\".\"DIAL_USAGE_LOG\" LIMIT 5"))
                .isEqualTo(bare);
    }

    /**
     * A star carrying the schema qualifier must expand exactly as the bare star does. Left to Calcite's
     * validator it expands against the table's full row type instead, which puts the heavy columns the
     * wildcard rule exists to exclude — and the dotted enrichment columns whose per-enrichment
     * {@code LEFT JOIN} the rule removed — into a statement that differs from {@code SELECT *} only by a
     * qualifier this MR actively steers clients into using.
     */
    @Test
    void qualifiedStarExpandsLikeTheBareStar() {
        final List<String> bare = columnNames("SELECT * FROM dial_usage_log");

        assertThat(bare).doesNotContain("dial_usage_log_payload.request_body", "dial_usage_log_payload.response_body").isNotEmpty();
        for (final String sql : List.of(
                "SELECT * FROM adas.dial_usage_log",
                "SELECT dial_usage_log.* FROM adas.dial_usage_log",
                "SELECT adas.dial_usage_log.* FROM adas.dial_usage_log",
                "SELECT \"ADAS\".\"DIAL_USAGE_LOG\".* FROM \"ADAS\".\"DIAL_USAGE_LOG\"",
                "SELECT d.* FROM adas.dial_usage_log d")) {
            assertThat(columnNames(sql)).as(sql).isEqualTo(bare);
        }
    }

    private List<String> columnNames(String sql) {
        return frontEnd.translate(sql).columns().stream().map(ResultColumn::name).toList();
    }

    @Test
    void unknownQualifierStillFailsAndNamesItself() {
        assertThatThrownBy(() -> frontEnd.translate("SELECT event_kind FROM warehouse.dial_usage_log LIMIT 5"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("warehouse");
    }

    // ---- Trailing separator --------------------------------------------------------------------

    @Test
    void trailingSeparatorIsIgnored() {
        final SqlTranslation plain = frontEnd.translate("SELECT event_kind FROM dial_usage_log LIMIT 5");

        assertThat(frontEnd.translate("SELECT event_kind FROM dial_usage_log LIMIT 5;")).isEqualTo(plain);
        assertThat(frontEnd.translate("SELECT event_kind FROM dial_usage_log LIMIT 5;  \n")).isEqualTo(plain);
    }

    @Test
    void batchIsStillRejected() {
        assertThatThrownBy(() -> frontEnd.translate(
                "SELECT event_kind FROM dial_usage_log; SELECT deployment FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class);
        assertThatThrownBy(() -> frontEnd.translate("SELECT event_kind FROM dial_usage_log LIMIT 5;;"))
                .isInstanceOf(SqlTranslationException.class);
    }

    @Test
    void semicolonInsideLiteralSurvives() {
        final SqlTranslation translation = frontEnd.translate(
                "SELECT event_kind FROM dial_usage_log WHERE event_kind = 'a;b' LIMIT 5");

        assertThat(translation.query().filter()).isNotNull();
        assertThat(translation).isEqualTo(frontEnd.translate(
                "SELECT event_kind FROM dial_usage_log WHERE event_kind = 'a;b' LIMIT 5;"));
    }
}
