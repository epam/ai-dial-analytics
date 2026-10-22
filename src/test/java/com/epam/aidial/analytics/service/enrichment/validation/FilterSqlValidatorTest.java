package com.epam.aidial.analytics.service.enrichment.validation;

import com.epam.aidial.analytics.exception.PipelineValidationException;
import com.epam.aidial.analytics.service.query.CatalogQueryService;
import com.epam.aidial.analytics.service.query.DialUsageLogTestModel;
import com.epam.aidial.analytics.service.query.sql.CalciteQueryCatalog;
import com.epam.aidial.analytics.service.query.sql.SqlQueryFrontEnd;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The predicate half of the rule-validation gate, asserted for the one property the SQL surface's new
 * window support puts at risk: a pipeline predicate must be decidable from one source row, because it is
 * rendered into scan and materialize statements that read a bounded window.
 */
class FilterSqlValidatorTest {

    private static final String SOURCE = DialUsageLogTestModel.ENTITY;

    private final SourceScopeGuard scope = mock(SourceScopeGuard.class);
    private final FilterSqlValidator validator = new FilterSqlValidator(frontEnd(), scope);

    private static SqlQueryFrontEnd frontEnd() {
        final CatalogQueryService catalog = mock(CatalogQueryService.class);
        when(catalog.queryableEntityNames(any())).thenReturn(List.of(DialUsageLogTestModel.ENTITY));
        when(catalog.findModel(anyString(), any())).thenReturn(Optional.empty());
        when(catalog.findModel(eq(DialUsageLogTestModel.ENTITY), any())).thenReturn(Optional.of(DialUsageLogTestModel.model()));
        return new SqlQueryFrontEnd(new CalciteQueryCatalog(catalog));
    }

    @Test
    void scalarPredicateIsAcceptedAndItsColumnsCollected() {
        assertThat(validator.validateAndCollectColumns(SOURCE, "total_tokens > 0")).contains("total_tokens");
    }

    @Test
    void windowPredicateIsRejected() {
        // Calcite already refuses OVER in a WHERE, so what matters is that the rejection is a rule
        // validation failure (422) and not a leaked internal error.
        assertThatThrownBy(() -> validator.validateAndCollectColumns(
                        SOURCE, "row_number() over (order by request_time) = 1"))
                .isInstanceOf(PipelineValidationException.class);
    }

    /**
     * This one entry point is where every governed predicate in the service passes — a pipeline
     * {@code filter}, a group trigger's {@code ready_when.signal}, a {@code member_select.prefer_sql}, and
     * an aggregate declaration's {@code filter} and per-measure {@code where} — so a clock refused here is
     * refused on all of them, which is why the rule lives here and not at each caller.
     *
     * <p>The {@code filter} case is the one worth naming: a predicate moving with the clock would have an
     * aggregate refresh recompute an already-published historical bucket over a different row set, with no
     * error to point at afterwards.
     */
    @Test
    void predicateReadingTheClockIsRejected() {
        for (final String predicate : List.of(
                "request_time >= now() - INTERVAL 1 DAY",
                "request_time >= now()",
                "request_time >= date_sub('hour', 1, now())",
                "total_tokens > 0 AND request_time >= now()",
                "case when now() > request_time then 1 else 0 end = 1")) {
            assertThatThrownBy(() -> validator.validateAndCollectColumns(SOURCE, predicate))
                    .as("predicate '%s'", predicate)
                    .isInstanceOf(PipelineValidationException.class)
                    .hasMessageContaining("non-deterministic function 'now'")
                    .hasMessageContaining("must be reproducible");
        }
    }

    @Test
    void theRejectionNamesThePredicateItCameFrom() {
        // A rejected readiness signal must not report itself as a rejected filter — the `what` label is
        // what a caller uses to find which fragment to fix.
        assertThatThrownBy(() -> validator.analyze(SOURCE, "request_time >= now()", "ready_when.signal"))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("ready_when.signal");
    }

    /** It is the clock that is refused, not date arithmetic: the same functions over a column are fine. */
    @Test
    void dateArithmeticOverColumnIsStillAccepted() {
        assertThat(validator.validateAndCollectColumns(
                SOURCE, "date_trunc('day', request_time) = date_trunc('day', _ingested_at)"))
                .contains("request_time", "_ingested_at");
        assertThat(validator.validateAndCollectColumns(
                SOURCE, "date_sub('day', 1, request_time) < _ingested_at"))
                .contains("request_time", "_ingested_at");
        assertThat(validator.validateAndCollectColumns(
                SOURCE, "request_time + INTERVAL 1 DAY < _ingested_at"))
                .contains("request_time", "_ingested_at");
    }
}
