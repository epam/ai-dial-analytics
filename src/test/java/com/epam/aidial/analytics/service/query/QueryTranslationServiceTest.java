package com.epam.aidial.analytics.service.query;

import com.epam.aidial.analytics.configuration.QueryDslConfiguration;
import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.ComparisonOp;
import com.epam.aidial.analytics.service.query.model.CursorPage;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.OffsetPage;
import com.epam.aidial.analytics.service.query.model.OutputColumn;
import com.epam.aidial.analytics.service.query.model.QueryMode;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.ValueType;
import com.epam.aidial.analytics.service.query.sql.StructuredQueryToSqlRenderer;
import com.epam.aidial.analytics.service.query.translate.ExprTranslator;
import com.epam.aidial.analytics.service.query.translate.FilterTranslator;
import com.epam.aidial.analytics.service.query.translate.StructuredQueryBuilder;
import com.epam.aidial.analytics.service.query.translate.ValueExprToObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The translate path validates exactly like the execute path — same entity resolution message, and
 * the full builder pass — before rendering; execution-side rejections (unknown fields, cursor paging,
 * limit governance) therefore surface identically even though the renderer itself never checks them.
 */
class QueryTranslationServiceTest {

    private final QueryTranslationService service = newService();

    private static QueryTranslationService newService() {
        final CatalogQueryService catalog = mock(CatalogQueryService.class);
        when(catalog.queryableEntityNames(any())).thenReturn(List.of(DialUsageLogTestModel.ENTITY));
        when(catalog.findModel(eq(DialUsageLogTestModel.ENTITY), any())).thenReturn(Optional.of(DialUsageLogTestModel.model()));
        final ExprTranslator expr = new ExprTranslator(new ValueExprToObjectMapper());
        return new QueryTranslationService(catalog, new PortableQueryRenderer(
                catalog,
                new StructuredQueryBuilder(expr, new FilterTranslator(expr)),
                new QueryDslConfiguration().clickhouseRenderContext(),
                new StructuredQueryToSqlRenderer(new ValueExprToObjectMapper()),
                null));
    }

    @Test
    void translatesValidatedQueryToExternalSql() {
        final StructuredQuery query = new StructuredQuery(
                DialUsageLogTestModel.ENTITY,
                new ComparisonNode(ComparisonOp.CO, List.of(
                        new FieldExpr("deployment"), new ValueExpr(ValueType.STRING, "gpt"))),
                QueryMode.ROW,
                false,
                List.of(new OutputColumn(new FieldExpr("project_id"), null)),
                null, null, null,
                new OffsetPage(0L, 10, false));

        assertThat(service.toSql(query)).isEqualTo(
                "SELECT \"project_id\" FROM \"dial_usage_log\" FINAL WHERE \"deployment\" LIKE '%gpt%' LIMIT 10");
    }

    @Test
    void rejectsUnknownEntityWithTheExecuteMessage() {
        final StructuredQuery query = new StructuredQuery(
                "nope", null, QueryMode.ROW, false, null, null, null, null, null);
        assertThatThrownBy(() -> service.toSql(query))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("entity 'nope' is not queryable");
    }

    @Test
    void rejectsUnknownFieldBeforeRendering() {
        final StructuredQuery query = new StructuredQuery(
                DialUsageLogTestModel.ENTITY, null, QueryMode.ROW, false,
                List.of(new OutputColumn(new FieldExpr("no_such_field"), null)), null, null, null, null);
        assertThatThrownBy(() -> service.toSql(query))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("no_such_field");
    }

    @Test
    void rejectsCursorPagingLikeTheExecutePath() {
        final StructuredQuery query = new StructuredQuery(
                DialUsageLogTestModel.ENTITY, null, QueryMode.ROW, false,
                List.of(new OutputColumn(new FieldExpr("project_id"), null)), null, null, null,
                new CursorPage(null, 10));
        assertThatThrownBy(() -> service.toSql(query)).isInstanceOf(QueryValidationException.class);
    }

    @Test
    void rejectsLimitAboveMaximumLikeTheExecutePath() {
        final StructuredQuery query = new StructuredQuery(
                DialUsageLogTestModel.ENTITY, null, QueryMode.ROW, false,
                List.of(new OutputColumn(new FieldExpr("project_id"), null)), null, null, null,
                new OffsetPage(0L, 50_000, false));
        assertThatThrownBy(() -> service.toSql(query))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("1000");
    }

    @Test
    void translatesEmptySelectOnEntityWithObjectSourceColumns() {
        // dial_usage_log's bare projection includes OBJECT columns (jwt_claims, request_tags) and an
        // ARRAY column. Every declared type is SQL-visible, so the document that execution accepts
        // now renders too — and '*' is the equivalent projection, since a star and a bare select share
        // one wildcard rule (the source's non-heavy columns).
        final StructuredQuery query = new StructuredQuery(
                DialUsageLogTestModel.ENTITY, null, QueryMode.ROW, false, null, null, null, null, null);
        assertThat(service.toSql(query)).isEqualTo("SELECT * FROM \"dial_usage_log\" FINAL");
    }

    @Test
    void rejectsNullQuery() {
        assertThatThrownBy(() -> service.toSql(null))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("must not be null");
    }
}
