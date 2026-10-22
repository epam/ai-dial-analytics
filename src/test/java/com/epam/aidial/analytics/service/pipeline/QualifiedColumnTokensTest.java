package com.epam.aidial.analytics.service.pipeline;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The token scans the dependency guards run over stored declarations, without the SQL front end. */
class QualifiedColumnTokensTest {

    @Test
    void qualifiedScanMissesAnAliasedQuotedNameWhichTheStatementScanFinds() {
        final String sql = "INSERT INTO t (k) SELECT e.\"Usage_Sentiment.sentiment\" FROM src e GROUP BY 1";

        assertThat(QualifiedColumnTokens.of(sql)).doesNotContain("usage_sentiment.sentiment");
        assertThat(QualifiedColumnTokens.ofStatement(sql)).contains("usage_sentiment.sentiment");
    }

    @Test
    void statementScanLowercasesQualifiedTokens() {
        assertThat(QualifiedColumnTokens.ofStatement("INSERT INTO t (k) SELECT Enr.Col FROM src"))
                .contains("enr.col")
                .doesNotContain("Enr.Col");
    }

    @Test
    void statementScanExcludesTheInsertHeadAfterLeadingComments() {
        final String sql = """
                -- a rollup
                /* by deployment */
                INSERT INTO "rollup" ("deployment", call_count, "x)y")
                SELECT deployment, count(*) FROM src GROUP BY 1""";

        assertThat(QualifiedColumnTokens.ofStatement(sql)).contains("deployment", "src").doesNotContain("call_count", "rollup");
    }

    @Test
    void statementScanFallsBackToTheWholeTextWhenTheHeadIsNotRecognised() {
        assertThat(QualifiedColumnTokens.ofStatement("INSERT INTO t SELECT a FROM src")).contains("t", "a", "src");
        assertThat(QualifiedColumnTokens.withoutInsertPrefix("SELECT 1")).isEqualTo("SELECT 1");
    }

    @Test
    void blankStatementHasNoTokens() {
        assertThat(QualifiedColumnTokens.ofStatement(" ")).isEmpty();
        assertThat(QualifiedColumnTokens.quoted(null)).isEmpty();
    }

    @Test
    void quotedScanSkipsQuotesInsideCommentsAndLiterals() {
        final String sql = "INSERT INTO t (k, n) SELECT /* 5\" screens */ e.\"enr.col\", 'a\"b', 'it''s \"x' -- 7\" tall\n"
                + "  , e.\"Other.Col\" FROM src e GROUP BY 1, 2";

        assertThat(QualifiedColumnTokens.quoted(sql)).containsExactly("enr.col", "Other.Col");
        assertThat(QualifiedColumnTokens.ofStatement(sql)).contains("enr.col", "other.col");
    }

    @Test
    void quotedScanReadsDoubledQuoteAsEscapeAndDropsUnterminated() {
        assertThat(QualifiedColumnTokens.quoted("SELECT \"a\"\"b\", \"open")).containsExactly("a\"b");
        assertThat(QualifiedColumnTokens.quoted("SELECT 'never closed \"x\"")).isEmpty();
    }
}
