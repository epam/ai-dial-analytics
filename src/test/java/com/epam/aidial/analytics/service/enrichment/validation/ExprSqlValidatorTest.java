package com.epam.aidial.analytics.service.enrichment.validation;

import com.epam.aidial.analytics.exception.PipelineValidationException;
import com.epam.aidial.analytics.service.enrichment.support.EnrichmentTypeMapping;
import com.epam.aidial.analytics.service.query.CatalogQueryService;
import com.epam.aidial.analytics.service.query.DialUsageLogTestModel;
import com.epam.aidial.analytics.service.query.sql.CalciteQueryCatalog;
import com.epam.aidial.analytics.service.query.sql.SqlQueryFrontEnd;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

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
 * Class-1 expression governance for the result types a derivation can now produce: an {@code array} is
 * accepted and carries its logical type out to the output-binding check, an {@code object} is still
 * refused, and a conditional passes the scalar/deterministic gate like any other expression.
 *
 * <p>The scope guard is stubbed to a no-op: what it enforces (a fragment reading only its rule's source)
 * is {@link SourceScopeGuard}'s own subject and is covered where that lives. What matters here is the
 * result-type mapping, which is the part this change moved.
 */
class ExprSqlValidatorTest {

    private static final String SOURCE = DialUsageLogTestModel.ENTITY;

    private final SourceScopeGuard scope = mock(SourceScopeGuard.class);
    private final ExprSqlValidator validator = new ExprSqlValidator(frontEnd(), scope);
    private final EnrichmentTypeMapping types = new EnrichmentTypeMapping();

    private static SqlQueryFrontEnd frontEnd() {
        final CatalogQueryService catalog = mock(CatalogQueryService.class);
        when(catalog.queryableEntityNames(any())).thenReturn(List.of(DialUsageLogTestModel.ENTITY));
        when(catalog.findModel(anyString(), any())).thenReturn(Optional.empty());
        when(catalog.findModel(eq(DialUsageLogTestModel.ENTITY), any())).thenReturn(Optional.of(DialUsageLogTestModel.model()));
        return new SqlQueryFrontEnd(new CalciteQueryCatalog(catalog));
    }

    private QueryFieldType typeOf(String exprSql) {
        return validator.analyze(SOURCE, exprSql, "output variable 'v'").type();
    }

    @Test
    void anArrayExpressionResolvesToTheArrayType() {
        // Previously rejected outright. An array cell is one value per grain, not many rows, so the grain
        // argument that refuses an aggregate does not apply to it.
        assertThat(typeOf("json_extract_array(\"dial_usage_log_payload.jwt_claims\", 'roles')")).isEqualTo(QueryFieldType.ARRAY);
    }

    @Test
    void anArrayExpressionIsCompatibleWithAnArrayColumnOnly() {
        // The binding check is plain type equality outside the numeric set, so nothing about arrays needed
        // special-casing — this asserts that, because the alternative reading is that it was forgotten.
        final QueryFieldType array = typeOf("json_extract_array(\"dial_usage_log_payload.jwt_claims\", 'roles')");
        assertThat(types.isCompatible(array, QueryFieldType.ARRAY)).isTrue();
        assertThat(types.isCompatible(array, QueryFieldType.STRING)).isFalse();
        assertThat(types.isCompatible(array, QueryFieldType.LONG)).isFalse();
    }

    @Test
    void anObjectExpressionIsStillRejected() {
        // For a reason an array does not share: an object is projection-only in the SQL subset and cannot
        // be collapsed into a column at all.
        assertThatThrownBy(() -> typeOf("\"dial_usage_log_payload.jwt_claims\""))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("yields an object");
    }

    @Test
    void conditionalExpressionIsAccepted() {
        assertThat(typeOf("if(not_empty(json_extract_string(\"dial_usage_log_payload.jwt_claims\", 'user_id')), 'jwt', 'api_key')"))
                .isEqualTo(QueryFieldType.STRING);
    }

    @Test
    void caseInsensitiveExtractionIsAcceptedAsText() {
        assertThat(typeOf("json_extract_string_ci(\"dial_usage_log_payload.request_tags\", 'x-session-id')"))
                .isEqualTo(QueryFieldType.STRING);
    }

    @Test
    void anAggregateExpressionIsStillRejected() {
        assertThatThrownBy(() -> typeOf("sum(total_tokens)"))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("aggregates");
    }

    @Test
    void windowExpressionIsRejected() {
        // A window stays in ROW mode, so the aggregate check does not catch it. It must be caught anyway:
        // MaterializeService renders the analysed expression into an INSERT … SELECT over one bounded
        // window, so the written value would depend on which source rows that window covered.
        assertThatThrownBy(() -> typeOf("row_number() over ()"))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("window function");
        assertThatThrownBy(() -> typeOf("sum(total_tokens) over (order by request_time)"))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("window function");
        Mockito.verifyNoInteractions(scope);
    }

    @Test
    void windowNestedInsideConditionalIsRejected() {
        // The guard is a tree walk, not a top-node test: a window buried in a CASE branch reaches the
        // materialize renderer just the same.
        assertThatThrownBy(() -> typeOf("case when total_tokens > 0 then row_number() over () else 0 end"))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("window function");
    }

    @Test
    void anUncataloguedFunctionIsStillRejected() {
        // rand(), not now(): the clock is IN the catalog now, so it is refused by the determinism gate
        // below rather than as an unknown name, and using it here would stop testing this rule.
        assertThatThrownBy(() -> typeOf("rand()"))
                .isInstanceOf(PipelineValidationException.class);
        assertThatThrownBy(() -> typeOf("generateUUIDv4()"))
                .isInstanceOf(PipelineValidationException.class);
        Mockito.verifyNoInteractions(scope);
    }

    /**
     * The clock is a catalog function the query surfaces accept freely, so a Class-1 expression has to
     * refuse it on its own terms. Determinism used to be guaranteed by construction — no non-deterministic
     * function existed to name — and the exactly-once <em>effect</em> guarantee rests on it: an expression
     * reading the clock would write a different label for the same row on a re-materialize, and afterwards
     * the two would be indistinguishable.
     */
    @Test
    void nonDeterministicCatalogFunctionIsRejected() {
        assertThatThrownBy(() -> typeOf("now()"))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("non-deterministic function 'now'")
                .hasMessageContaining("must be reproducible");
        Mockito.verifyNoInteractions(scope);
    }

    @Test
    void nonDeterministicFunctionNestedInsideAnotherCallIsRejected() {
        // A tree walk, not a top-node test: date_sub('hour', 1, now()) is no more reproducible than now().
        assertThatThrownBy(() -> typeOf("date_sub('hour', 1, now())"))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("non-deterministic function 'now'");
        assertThatThrownBy(() -> typeOf("case when total_tokens > 0 then now() else request_time end"))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("non-deterministic function 'now'");
        assertThatThrownBy(() -> typeOf("to_string(date_trunc('day', now()))"))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("non-deterministic function 'now'");
    }

    @Test
    void nonDeterministicFunctionHiddenInsideLambdaBodyIsRejected() {
        // A lambda body is an ordinary expression, so it hides a call exactly as date_sub's argument does
        // — and the walk has to enter it, or a stored Class-1 declaration reading the clock is accepted
        // and re-materializes to a different value with nothing left behind to point at.
        assertThatThrownBy(() -> typeOf("arrayExists(x -> equals(x, to_string(now())), execution_path)"))
                .isInstanceOf(PipelineValidationException.class)
                .hasMessageContaining("non-deterministic function 'now'")
                .hasMessageContaining("must be reproducible");
    }

    /** The lambda itself is no obstacle: a body over the parameter and a column is accepted. */
    @Test
    void deterministicLambdaBodyIsAccepted() {
        assertThat(typeOf("arrayExists(x -> starts_with(x, deployment), execution_path)"))
                .isEqualTo(QueryFieldType.BOOLEAN);
    }

    /** It is the clock that is refused, not date arithmetic: the same functions over a column are fine. */
    @Test
    void dateArithmeticOverColumnIsStillAccepted() {
        assertThat(typeOf("date_trunc('day', request_time)")).isEqualTo(QueryFieldType.TIMESTAMP);
        assertThat(typeOf("date_sub('day', 1, request_time)")).isEqualTo(QueryFieldType.TIMESTAMP);
        assertThat(typeOf("date_add('hour', 2, request_time)")).isEqualTo(QueryFieldType.TIMESTAMP);
        assertThat(typeOf("request_time - INTERVAL 1 DAY")).isEqualTo(QueryFieldType.TIMESTAMP);
    }

    @Test
    void parameterizedArrayTypeTokenIsRefusedWithActionableMessage() {
        // Aliasing array<string> to ARRAY would advertise an element-type check that does not happen, and
        // would read as passing when bound to an array<long> column. Say what to write instead.
        assertThatThrownBy(() -> types.resolve("array<string>"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'array'");
        assertThat(types.resolve("array")).isEqualTo(QueryFieldType.ARRAY);
    }
}
