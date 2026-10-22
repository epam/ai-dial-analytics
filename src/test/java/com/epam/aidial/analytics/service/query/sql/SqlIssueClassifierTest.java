package com.epam.aidial.analytics.service.query.sql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@link SqlIssueClassifier} to the messages the pipeline actually produces.
 *
 * <p>Every string below was captured from a real {@code SqlQueryFrontEnd.plan} or
 * {@code StructuredQueryBuilder.build} rejection rather than written from the source, because that is the
 * only way this test can do its job: the classifier reads messages it does not own — a third of them
 * Calcite's — so a Calcite upgrade or a reworded ADAS rejection must fail here rather than silently
 * degrade every affected code to the fallback.
 */
class SqlIssueClassifierTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        // ---- statement shape
        "only SELECT statements are supported; got INSERT | not_a_select",
        "exactly one SQL statement is required; got 2 | multiple_statements",
        "SQL statement must not be empty | empty_statement",

        // ---- catalog resolution
        "SQL validation error: Object 'no_such_entity' not found | unknown_entity",
        "SQL validation error: Column 'no_such_col' not found in any table | unknown_column",
        "SQL validation error: Column 'no_such_col' not found in table 'd' | unknown_column",
        "SQL validation error: Column 'event_kind' is ambiguous | ambiguous_column",
        "unknown field 'nope' | unknown_field",

        // ---- the function catalog
        "SQL validation error: Invalid number of arguments to function 'COUNT'. Was expecting 1 arguments"
                + " | function_arity",
        "SQL validation error: No match found for function signature no_such_fn(<CHARACTER>) | unknown_function",
        "unsupported function 'nope' in query expression | unsupported_function",

        // ---- governance
        "page limit 50000 exceeds the maximum of 1000 rows | limit_above_maximum",
        "page limit must not be negative | negative_limit",

        // ---- type restrictions
        "array field 'execution_path' can only be projected; IS [NOT] NULL on an array field is not supported"
                + " | projection_only_field",
        "CAST from BIGINT to VARCHAR is not supported by the query translator | unsupported_cast",
        "SQL validation error: Expression 'event_kind' is not being grouped | not_grouped",

        // ---- unsupported constructs
        "UNION requires ALL or DISTINCT: the database refuses a bare UNION, because its union_default_mode"
                + " setting has no default mode | unsupported_construct",
        "MINUS is not supported; use EXCEPT ALL or EXCEPT DISTINCT, which is what the database understands"
                + " | unsupported_construct",
        "an ORDER BY, LIMIT or OFFSET after a set operation belongs to its last branch and cannot follow a"
                + " parenthesis | unsupported_construct",
        "recursive common table expressions (WITH RECURSIVE) are not supported | unsupported_construct",
        "SQL construct 'IS NULL' cannot be expressed in the structured query DSL | unsupported_construct",
    })
    void classifiesTheRealMessage(String message, String expectedCode) {
        assertThat(SqlIssueClassifier.classify(message).code()).isEqualTo(expectedCode);
    }

    /**
     * Calcite rewrites an {@code IN} list longer than its sub-query threshold into a {@code VALUES}
     * relation, so the statement is refused for a construct the caller never wrote — the one message where
     * the classification carries information the message itself does not.
     */
    @Test
    void inListOverflowIsNotReportedAsAnUnsupportedConstruct() {
        final SqlIssue issue = SqlIssueClassifier.classify("SQL construct 'LogicalValues' is not supported");

        assertThat(issue.code()).isEqualTo("in_list_too_long");
        assertThat(issue.remedy()).contains("20");
    }

    /** An unquoted reserved-word alias: the hint's position is the offender, three columns past Calcite's. */
    @Test
    void reservedWordAliasIsAnchoredOnTheHintNotOnCalcitesPosition() {
        final SqlIssue issue = SqlIssueClassifier.classify(
                "SQL parse error: Incorrect syntax near the keyword 'AS' at line 1, column 29."
                        + " 'rows' at line 1, column 32 is a reserved word and cannot be an unquoted"
                        + " identifier — rename it, or quote it as \"rows\".");

        assertThat(issue.code()).isEqualTo("reserved_word_alias");
        assertThat(issue.line()).isEqualTo(1);
        assertThat(issue.column()).isEqualTo(32);
    }

    /**
     * The same hint fires on a truncated statement, where it names the very keyword the parser stopped on.
     * Both positions coincide there, so it is not the alias case and must classify as an ordinary parse
     * failure — otherwise the caller is advised to "rename or quote" their {@code WHERE}.
     */
    @Test
    void reservedWordAtStopPositionIsPlainParseError() {
        final SqlIssue issue = SqlIssueClassifier.classify(
                "SQL parse error: Incorrect syntax near the keyword 'WHERE' at line 1, column 39."
                        + " 'WHERE' at line 1, column 39 is a reserved word and cannot be an unquoted"
                        + " identifier — rename it, or quote it as \"WHERE\".");

        assertThat(issue.code()).isEqualTo("parse_error");
        assertThat(issue.remedy()).isNull();
    }

    /**
     * The nested-block failure: Calcite stops on the trailing clause, which is correct text, so the issue
     * is anchored on the block instead — the parenthesis the caller has to change.
     */
    @Test
    void nestedBlockFailureIsAnchoredOnTheBlockNotOnTheClause() {
        final SqlIssue issue = SqlIssueClassifier.classify(
                "SQL parse error: Incorrect syntax near the keyword 'LIMIT' at line 4, column 41."
                        + " The parenthesis at line 1, column 24 opens a block that starts with another '('"
                        + " and carries a trailing LIMIT, which this parser cannot read together —"
                        + " parenthesise the block's last query too, as in"
                        + " ((A) UNION ALL (B ORDER BY x LIMIT 2)), or leave its first one unparenthesised.");

        assertThat(issue.code()).isEqualTo("parse_error");
        assertThat(issue.line()).isEqualTo(1);
        assertThat(issue.column()).isEqualTo(24);
        assertThat(issue.remedy()).contains("parenthesise the block's last query too");
    }

    /**
     * A column the select list names and nothing resolves reads as a self-reference, because the unnamed-item
     * alias injector has already given the item that same name. Left unclassified this is the single most
     * misleading message on the surface: it describes ADAS's own injected alias, not the caller's mistake.
     */
    @Test
    void selfReferentialColumnDefinitionIsUnknownColumn() {
        final SqlIssue issue = SqlIssueClassifier.classify("SQL validation error: The definition of column"
                + " 'event_kindd' depends on itself through the following columns: 'event_kindd'");

        assertThat(issue.code()).isEqualTo("unknown_column");
        assertThat(issue.remedy()).contains("/v1/queries/entities/schema/");
    }

    /**
     * The three function-catalog remedies name the SQL catalog. The DSL catalog lists names this surface
     * refuses and signatures it cannot parse, so pointing there sends a SQL caller to the source of the mistake.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "SQL validation error: Invalid number of arguments to function 'COUNT'. Was expecting 1 arguments",
        "SQL validation error: No match found for function signature plus(<NUMERIC>, <NUMERIC>)",
        "unsupported function 'nope' in query expression",
    })
    void functionRemediesNameTheSqlCatalog(String message) {
        assertThat(SqlIssueClassifier.classify(message).remedy())
                .contains("GET /v1/queries/sql-functions")
                .doesNotContain("GET /v1/queries/functions");
    }

    @Test
    void parseFailureCarriesItsPosition() {
        final SqlIssue issue = SqlIssueClassifier.classify(
                "SQL parse error: Encountered \"+ <EOF>\" at line 1, column 64.");

        assertThat(issue.code()).isEqualTo("parse_error");
        assertThat(issue.line()).isEqualTo(1);
        assertThat(issue.column()).isEqualTo(64);
    }

    /** An unrecognised diagnosis degrades to the fallback with its text intact and no invented advice. */
    @Test
    void anUnrecognisedMessageKeepsItsTextAndOffersNoAdvice() {
        final SqlIssue issue = SqlIssueClassifier.classify("something Calcite has not said before");

        assertThat(issue.code()).isEqualTo(SqlIssueClassifier.FALLBACK_CODE);
        assertThat(issue.message()).isEqualTo("something Calcite has not said before");
        assertThat(issue.remedy()).isNull();
        assertThat(issue.line()).isNull();
    }

    @Test
    void nullMessageDoesNotThrow() {
        assertThat(SqlIssueClassifier.classify(null).code()).isEqualTo(SqlIssueClassifier.FALLBACK_CODE);
    }
}
