package com.epam.aidial.analytics.service.query.sql;

import com.epam.aidial.analytics.service.query.CatalogQueryService;
import com.epam.aidial.analytics.service.query.DialUsageLogTestModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link SqlIdentifierProbe} coverage. The probe's value is enumerating <em>every</em> unresolvable name
 * in one pass; its risk is naming one that is in fact legal, since it parses without validating and so has
 * no scope resolution. Most of what follows therefore tests the silences — the cases where it must find
 * nothing — because a false positive sends a caller to fix something that is not broken.
 */
class SqlIdentifierProbeTest {

    private final SqlIdentifierProbe probe = newProbe();

    private static SqlIdentifierProbe newProbe() {
        final CatalogQueryService catalog = mock(CatalogQueryService.class);
        when(catalog.queryableEntityNames(any())).thenReturn(List.of(DialUsageLogTestModel.ENTITY));
        when(catalog.findModel(anyString(), any())).thenReturn(Optional.empty());
        when(catalog.findModel(eq(DialUsageLogTestModel.ENTITY), any())).thenReturn(Optional.of(DialUsageLogTestModel.model()));
        return new SqlIdentifierProbe(catalog);
    }

    @Test
    void reportsEveryUnresolvableColumnInOnePass() {
        final List<SqlIdentifierProbe.Finding> issues = probe.probe(
                "SELECT nope_one, nope_two FROM dial_usage_log WHERE nope_three = 'x'");

        assertThat(issues).extracting(f -> f.issue().code()).containsOnly("unknown_column");
        assertThat(issues).extracting(f -> f.issue().message())
                .anyMatch(m -> m.contains("'nope_one'"))
                .anyMatch(m -> m.contains("'nope_two'"))
                .anyMatch(m -> m.contains("'nope_three'"));
    }

    @Test
    void offersTheClosestCatalogNameAsTheRemedy() {
        final List<SqlIdentifierProbe.Finding> issues = probe.probe("SELECT event_kindd FROM dial_usage_log");

        assertThat(issues).singleElement()
                .satisfies(f -> assertThat(f.suggestion()).isEqualTo("event_kind"));
    }

    @Test
    void carriesTheIdentifiersOwnPosition() {
        final List<SqlIdentifierProbe.Finding> issues = probe.probe("SELECT nope FROM dial_usage_log");

        assertThat(issues).singleElement().satisfies(f -> {
            assertThat(f.issue().line()).isEqualTo(1);
            assertThat(f.issue().column()).isEqualTo(8);
        });
    }

    @Test
    void reportsAnUnknownEntity() {
        final List<SqlIdentifierProbe.Finding> issues = probe.probe("SELECT x FROM no_such_entity");

        assertThat(issues).singleElement().satisfies(f -> {
            assertThat(f.issue().code()).isEqualTo("unknown_entity");
            assertThat(f.issue().message()).contains("'no_such_entity'");
            assertThat(f.identifier()).isEqualTo("no_such_entity");
        });
    }

    // ------------------------------------------------------ the silences

    @Test
    void findsNothingInResolvableStatement() {
        assertThat(probe.probe("SELECT event_kind, project_id FROM dial_usage_log WHERE total_tokens > 5"))
                .isEmpty();
    }

    /**
     * With no entity resolved there is no set of legal columns to test against, so every column would be
     * reported — including the ones that are fine. The unknown entity is the only honest finding.
     */
    @Test
    void doesNotReportColumnsWhenNoEntityResolves() {
        final List<SqlIdentifierProbe.Finding> issues = probe.probe("SELECT anything, at_all FROM no_such_entity");

        assertThat(issues).extracting(f -> f.issue().code()).containsExactly("unknown_entity");
    }

    @Test
    void doesNotReportTableAliasOrQualifiedColumn() {
        assertThat(probe.probe("SELECT d.event_kind FROM dial_usage_log d WHERE d.project_id = 'p'")).isEmpty();
    }

    @Test
    void doesNotReportSelectAlias() {
        assertThat(probe.probe(
                "SELECT count(*) AS hits FROM dial_usage_log GROUP BY event_kind ORDER BY hits DESC"))
                .isEmpty();
    }

    @Test
    void doesNotReportCteNameOrItsColumns() {
        assertThat(probe.probe("WITH recent AS (SELECT event_kind FROM dial_usage_log)"
                + " SELECT event_kind FROM recent")).isEmpty();
    }

    @Test
    void doesNotReportDerivedTableOutputColumns() {
        assertThat(probe.probe("SELECT kind FROM (SELECT event_kind AS kind FROM dial_usage_log) t")).isEmpty();
    }

    /** A statement that does not parse has no tree to walk, and the parse error is the only diagnosis. */
    @Test
    void findsNothingInStatementThatDoesNotParse() {
        assertThat(probe.probe("SELECT nope FROM dial_usage_log WHERE")).isEmpty();
        assertThat(probe.probe("this is not sql at all")).isEmpty();
    }

    /** An enrichment column is the one catalog name carrying a dot; quoted, it is a single part. */
    @Test
    void resolvesQuotedEnrichmentColumnWhole() {
        final CatalogQueryService catalog = mock(CatalogQueryService.class);
        when(catalog.queryableEntityNames(any())).thenReturn(List.of(DialUsageLogTestModel.ENTITY));
        when(catalog.findModel(anyString(), any())).thenReturn(Optional.empty());
        when(catalog.findModel(eq(DialUsageLogTestModel.ENTITY), any()))
                .thenReturn(Optional.of(DialUsageLogTestModel.modelWithEnrichment()));

        assertThat(new SqlIdentifierProbe(catalog).probe("SELECT event_id, \""
                + DialUsageLogTestModel.ENRICHMENT_COLUMN + "\" FROM " + DialUsageLogTestModel.ENTITY))
                .isEmpty();
    }
}
