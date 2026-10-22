package com.epam.aidial.analytics.service.query.sql;

import com.epam.aidial.analytics.clickhouse.ClickHouseClientWrapper;
import com.epam.aidial.analytics.configuration.QueryBudgetProperties;
import com.epam.aidial.analytics.configuration.QueryDslConfiguration;
import com.epam.aidial.analytics.service.query.CatalogQueryService;
import com.epam.aidial.analytics.service.query.DialUsageLogTestModel;
import com.epam.aidial.analytics.service.query.StructuredQueryService;
import com.epam.aidial.analytics.service.query.repository.StructuredQueryExecutor;
import com.epam.aidial.analytics.service.query.translate.CompositeQueryBuilder;
import com.epam.aidial.analytics.service.query.translate.ExprTranslator;
import com.epam.aidial.analytics.service.query.translate.FilterTranslator;
import com.epam.aidial.analytics.service.query.translate.StructuredQueryBuilder;
import com.epam.aidial.analytics.service.query.translate.ValueExprToObjectMapper;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link SqlValidationService} coverage, wired with the <em>real</em> front end, executor and builders —
 * only the ClickHouse client is a mock, and the point of that mock is that every test below leaves it
 * untouched. Validating through the genuine pipeline is what makes these assertions mean anything: a test
 * against a stubbed governance pass would pass just as happily if the pass were never run, which is
 * precisely the defect this endpoint exists to correct on {@code /translate-sql}.
 */
class SqlValidationServiceTest {

    private final ClickHouseClientWrapper clickHouse = mock(ClickHouseClientWrapper.class);
    private final SqlValidationService service = newService(clickHouse);

    private static SqlValidationService newService(ClickHouseClientWrapper clickHouse) {
        final CatalogQueryService catalog = mock(CatalogQueryService.class);
        when(catalog.queryableEntityNames(any())).thenReturn(List.of(DialUsageLogTestModel.ENTITY));
        when(catalog.findModel(anyString(), any())).thenReturn(Optional.empty());
        when(catalog.findModel(eq(DialUsageLogTestModel.ENTITY), any())).thenReturn(Optional.of(DialUsageLogTestModel.model()));

        final ExprTranslator expr = new ExprTranslator(new ValueExprToObjectMapper());
        final StructuredQueryBuilder builder = new StructuredQueryBuilder(expr, new FilterTranslator(expr));
        final DSLContext renderContext = new QueryDslConfiguration().clickhouseRenderContext();
        final QueryBudgetProperties budget = new QueryBudgetProperties(
                Duration.ofSeconds(20), DataSize.ofGigabytes(2), DataSize.ofMegabytes(512));
        final StructuredQueryService queryService = new StructuredQueryService(
                catalog,
                new StructuredQueryExecutor(builder, renderContext, clickHouse, com.epam.aidial.analytics.service.security.RowPolicyFilterStubs.inert()),
                new CompositeQueryBuilder(builder),
                renderContext,
                budget);
        return new SqlValidationService(
                new SqlQueryFrontEnd(new CalciteQueryCatalog(catalog)),
                queryService,
                catalog,
                new SqlIdentifierProbe(catalog));
    }

    // ------------------------------------------------------ the verdict

    @Test
    void acceptsValidStatement() {
        final SqlValidation validation = service.validate(
                "SELECT event_kind FROM dial_usage_log LIMIT 10");

        assertThat(validation.valid()).isTrue();
        assertThat(validation.issues()).isEmpty();
        assertThat(validation.effectiveLimit()).isEqualTo(10);
    }

    @Test
    void acceptsCompositeStatement() {
        final SqlValidation validation = service.validate(
                "SELECT a.project_id, count(*) AS n FROM dial_usage_log a"
                        + " JOIN dial_usage_log b ON a.event_id = b.event_id"
                        + " GROUP BY a.project_id LIMIT 10");

        assertThat(validation.valid()).isTrue();
        assertThat(validation.issues()).isEmpty();
    }

    /**
     * The reason this endpoint exists rather than {@code /translate-sql} being exposed: the row governance
     * lives in the builder, which the translate path never runs, so this statement is one the translate
     * endpoint reported clean and {@code execute-sql} then rejected.
     */
    @Test
    void rejectsLimitAboveTheMaximum() {
        final SqlValidation validation = service.validate(
                "SELECT event_kind FROM dial_usage_log LIMIT 50000");

        assertThat(validation.valid()).isFalse();
        assertThat(validation.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo("limit_above_maximum");
            assertThat(issue.message()).contains("50000").contains("1000");
        });
        assertThat(validation.effectiveLimit()).isNull();
    }

    @Test
    void rejectsAnUnknownColumnWithItsClassification() {
        final SqlValidation validation = service.validate("SELECT event_kindd FROM dial_usage_log");

        assertThat(validation.valid()).isFalse();
        assertThat(validation.issues().getFirst().code()).isEqualTo("unknown_column");
    }

    /**
     * The commonest case of all — one misspelled column — and the one where the suggestion matters most.
     * The pipeline names the identifier itself, so the probe's finding is not reported separately; its
     * suggestion must still reach the caller rather than being dropped with the duplicate.
     */
    @Test
    void mergesTheProbesSuggestionOntoTheOneIssue() {
        final SqlValidation validation = service.validate("SELECT event_kindd FROM dial_usage_log");

        assertThat(validation.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo("unknown_column");
            assertThat(issue.remedy()).isEqualTo("did you mean 'event_kind'?");
        });
    }

    /** The pipeline names the first offender; the probe supplies the rest, once each. */
    @Test
    void reportsEveryUnknownColumnInOneCall() {
        final SqlValidation validation = service.validate(
                "SELECT nope_one, nope_two FROM dial_usage_log WHERE nope_three = 'x'");

        assertThat(validation.valid()).isFalse();
        assertThat(validation.issues()).extracting(SqlIssue::code).containsOnly("unknown_column");
        assertThat(validation.issues()).hasSize(3);
        assertThat(validation.issues()).extracting(SqlIssue::message)
                .anyMatch(m -> m.contains("nope_one"))
                .anyMatch(m -> m.contains("nope_two"))
                .anyMatch(m -> m.contains("nope_three"));
    }

    @Test
    void reportsTheReservedWordBehindAnAliasFailure() {
        final SqlValidation validation = service.validate(
                "SELECT event_kind, count(*) AS rows FROM dial_usage_log GROUP BY event_kind");

        assertThat(validation.issues().getFirst().code()).isEqualTo("reserved_word_alias");
    }

    /**
     * End to end over the real message, so the classifier's pattern cannot drift from the sentence
     * {@code SqlNestedTrailingClause} actually produces: the issue must point at the block on line 1, not
     * at the clause Calcite stopped on.
     */
    @Test
    void anchorsTheNestedBlockFailureOnTheBlock() {
        final SqlValidation validation = service.validate("""
                SELECT event_kind FROM (
                  (SELECT event_kind FROM dial_usage_log LIMIT 5)
                  UNION ALL
                  SELECT event_kind FROM dial_usage_log LIMIT 2
                )""");

        assertThat(validation.valid()).isFalse();
        final SqlIssue issue = validation.issues().getFirst();
        assertThat(issue.code()).isEqualTo("parse_error");
        assertThat(issue.line()).isEqualTo(1);
        assertThat(issue.column()).isEqualTo(24);
        assertThat(issue.remedy()).contains("parenthesise the block's last query too");
    }

    @Test
    void reportsAnOverlongInListAsWhatItIs() {
        final String literals = String.join(",", Collections.nCopies(21, "'x'"));
        final SqlValidation validation = service.validate(
                "SELECT event_kind FROM dial_usage_log WHERE project_id IN (" + literals + ")");

        assertThat(validation.issues().getFirst().code()).isEqualTo("in_list_too_long");
    }

    /** A catalog statement is answered from ADAS's own catalog, so nothing is left that could reject it. */
    @Test
    void acceptsCatalogMetadataStatement() {
        final SqlValidation validation = service.validate(
                "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'dial_usage_log'");

        assertThat(validation.valid()).isTrue();
        assertThat(validation.effectiveLimit()).isNull();
        assertThat(validation.warnings()).isEmpty();
    }

    // ------------------------------------------------------ warnings

    @Test
    void warnsThatTheDefaultLimitWillBeApplied() {
        final SqlValidation validation = service.validate("SELECT event_kind FROM dial_usage_log");

        assertThat(validation.valid()).isTrue();
        assertThat(validation.effectiveLimit()).isEqualTo(100);
        assertThat(validation.warnings()).extracting(SqlIssue::code).contains("default_limit_applied");
    }

    @Test
    void doesNotWarnAboutTheLimitWhenTheStatementSetsOne() {
        assertThat(service.validate("SELECT event_kind FROM dial_usage_log LIMIT 10").warnings())
                .extracting(SqlIssue::code)
                .doesNotContain("default_limit_applied");
    }

    @Test
    void warnsAboutHeavyColumnWithNoTimeBound() {
        final SqlValidation validation = service.validate(
                "SELECT event_id, \"dial_usage_log_payload.assembled_response\" FROM dial_usage_log WHERE chat_id = 'c' LIMIT 10");

        assertThat(validation.valid()).isTrue();
        assertThat(validation.warnings()).extracting(SqlIssue::code).contains("heavy_column_unbounded");
        assertThat(validation.warnings()).extracting(SqlIssue::message)
                .anyMatch(m -> m.contains("dial_usage_log_payload.assembled_response"));
    }

    /** A timestamp predicate prunes the read, which is the whole point of the warning — so it goes away. */
    @Test
    void doesNotWarnAboutHeavyColumnInsideTimeBound() {
        final SqlValidation validation = service.validate(
                "SELECT event_id, \"dial_usage_log_payload.assembled_response\" FROM dial_usage_log"
                        + " WHERE request_time >= TIMESTAMP '2026-08-01 00:00:00' LIMIT 10");

        assertThat(validation.warnings()).extracting(SqlIssue::code).doesNotContain("heavy_column_unbounded");
    }

    @Test
    void warnsAboutAnUnboundedGroupBy() {
        final SqlValidation validation = service.validate(
                "SELECT project_id, count(*) AS n FROM dial_usage_log GROUP BY project_id LIMIT 10");

        assertThat(validation.warnings()).extracting(SqlIssue::code).contains("unbounded_group_by");
    }

    @Test
    void doesNotWarnAboutGroupByInsideTimeBound() {
        final SqlValidation validation = service.validate(
                "SELECT project_id, count(*) AS n FROM dial_usage_log"
                        + " WHERE request_time >= TIMESTAMP '2026-08-01 00:00:00'"
                        + " GROUP BY project_id LIMIT 10");

        assertThat(validation.warnings()).extracting(SqlIssue::code).doesNotContain("unbounded_group_by");
    }

    /**
     * The endpoint's standing promise. Held here by the executor's own client being a strict mock across
     * every case above — valid, invalid, composite and metadata alike.
     */
    @Test
    void neverContactsClickHouse() {
        service.validate("SELECT event_kind FROM dial_usage_log");
        service.validate("SELECT event_kindd FROM dial_usage_log");
        service.validate("SELECT event_kind FROM dial_usage_log LIMIT 50000");
        service.validate("SELECT a.event_id FROM dial_usage_log a JOIN dial_usage_log b ON a.event_id = b.event_id");
        service.validate("SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS");

        verifyNoInteractions(clickHouse);
    }
}
