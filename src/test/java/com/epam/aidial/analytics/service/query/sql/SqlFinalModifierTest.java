package com.epam.aidial.analytics.service.query.sql;

import com.epam.aidial.analytics.exception.SqlTranslationException;
import org.apache.calcite.sql.parser.SqlParser;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlFinalModifierTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "SELECT x FROM t FINAL|SELECT x FROM t /*+ final */",
        "SELECT x FROM t final WHERE x = 1|SELECT x FROM t /*+ final */ WHERE x = 1",
        "SELECT x FROM t AS a FINAL|SELECT x FROM t /*+ final */ AS a",
        "SELECT x FROM t a FINAL|SELECT x FROM t /*+ final */ a",
        "SELECT x FROM \"t\" FINAL|SELECT x FROM \"t\" /*+ final */",
        "SELECT x FROM s.t FINAL|SELECT x FROM s.t /*+ final */",
        "SELECT x FROM s.\"t\" AS \"a\" FINAL|SELECT x FROM s.\"t\" /*+ final */ AS \"a\"",
        "SELECT 1 FROM a FINAL JOIN b ON a.k = b.k|SELECT 1 FROM a /*+ final */ JOIN b ON a.k = b.k",
        "SELECT 1 FROM a JOIN b r FINAL ON a.k = r.k|SELECT 1 FROM a JOIN b /*+ final */ r ON a.k = r.k",
        "SELECT 1 FROM a, b FINAL|SELECT 1 FROM a, b /*+ final */",
        // A comma after a join condition continues the FROM list.
        "SELECT 1 FROM a JOIN b ON a.k = b.k, c FINAL|SELECT 1 FROM a JOIN b ON a.k = b.k, c /*+ final */",
        "SELECT 1 FROM a JOIN b USING (k), c FINAL|SELECT 1 FROM a JOIN b USING (k), c /*+ final */",
        "SELECT 1 FROM a JOIN b USING (k, j), c AS x FINAL WHERE 1 = 1|SELECT 1 FROM a JOIN b USING (k, j), c /*+ final */ AS x WHERE 1 = 1",
        "SELECT m FROM t FINAL UNION ALL SELECT m FROM t|SELECT m FROM t /*+ final */ UNION ALL SELECT m FROM t",
        "WITH c AS (SELECT x FROM t FINAL) SELECT count(*) FROM c|WITH c AS (SELECT x FROM t /*+ final */) SELECT count(*) FROM c",
        "SELECT * FROM (SELECT x FROM t FINAL) d|SELECT * FROM (SELECT x FROM t /*+ final */) d",
        "SELECT x FROM t WHERE x IN (SELECT y FROM u FINAL)|SELECT x FROM t WHERE x IN (SELECT y FROM u /*+ final */)",
    })
    void rewritesModifierIntoHint(String sql, String expected) {
        final String rewritten = SqlFinalModifier.rewrite(sql);
        assertThat(rewritten).isEqualTo(expected);
        assertThatCode(() -> SqlParser.create(rewritten, SqlQueryFrontEnd.PARSER_CONFIG).parseQuery())
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT 'FINAL' AS \"final\" FROM t -- FINAL",
        "SELECT x FROM t /* FINAL */",
        "SELECT e'it''s FINAL' FROM t",
        "SELECT x AS final_x FROM t",
        "SELECT x FROM t WHERE x = 1 FINAL",
        "SELECT a, b FINAL FROM t",
        "SELECT x FROM t ORDER BY x FINAL",
        "SELECT x FROM t ORDER BY x, y FINAL",
        "SELECT 1 FROM a JOIN b ON a.k = b.k WHERE x IN (1, 2) GROUP BY x, y FINAL",
        "SELECT x FROM t AS \"FINAL\"",
        // ClickHouse refuses FINAL between a reference and its alias; left for the parser to refuse too.
        "SELECT x FROM t FINAL AS a",
        "SELECT x FROM t FINAL a",
        "SELECT x FROM t FINAL \"a\"",
    })
    void leavesOtherUsesAlone(String sql) {
        assertThat(SqlFinalModifier.rewrite(sql)).isEqualTo(sql);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT * FROM (SELECT x FROM t) FINAL",
        "SELECT * FROM (SELECT x FROM t) AS d FINAL",
        "SELECT * FROM a JOIN (SELECT k FROM b) d FINAL ON a.k = d.k",
    })
    void refusesFinalOnDerivedTable(String sql) {
        assertThatThrownBy(() -> SqlFinalModifier.rewrite(sql))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessage(SqlFinalModifier.NOT_AN_ENTITY);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "WITH c AS (SELECT x FROM t) SELECT * FROM c FINAL",
        "SELECT * FROM INFORMATION_SCHEMA.\"TABLES\" FINAL",
    })
    void refusesFinalOnCteNameOrCatalogTable(String sql) throws Exception {
        final var parsed = SqlParser.create(SqlFinalModifier.rewrite(sql), SqlQueryFrontEnd.PARSER_CONFIG).parseQuery();
        assertThatThrownBy(() -> SqlFinalReferences.check(parsed))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessage(SqlFinalModifier.NOT_AN_ENTITY);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT x FROM t FINAL",
        "WITH c AS (SELECT x FROM t FINAL) SELECT * FROM c",
    })
    void acceptsFinalOnEntityReference(String sql) throws Exception {
        final var parsed = SqlParser.create(SqlFinalModifier.rewrite(sql), SqlQueryFrontEnd.PARSER_CONFIG).parseQuery();
        assertThatCode(() -> SqlFinalReferences.check(parsed)).doesNotThrowAnyException();
    }
}
