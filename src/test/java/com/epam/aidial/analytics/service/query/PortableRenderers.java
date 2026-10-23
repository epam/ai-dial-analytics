package com.epam.aidial.analytics.service.query;

import com.epam.aidial.analytics.configuration.QueryDslConfiguration;
import com.epam.aidial.analytics.service.query.sql.CalciteQueryCatalog;
import com.epam.aidial.analytics.service.query.sql.SqlQueryFrontEnd;
import com.epam.aidial.analytics.service.query.sql.StructuredQueryToSqlRenderer;
import com.epam.aidial.analytics.service.query.translate.ExprTranslator;
import com.epam.aidial.analytics.service.query.translate.FilterTranslator;
import com.epam.aidial.analytics.service.query.translate.StructuredQueryBuilder;
import com.epam.aidial.analytics.service.query.translate.ValueExprToObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A {@link PortableQueryRenderer} for service unit tests: the real builder pass, renderer and SQL front end over one
 * fixed model, which every entity resolves to.
 */
public final class PortableRenderers {

    private PortableRenderers() {
    }

    public static PortableQueryRenderer over(QueryEntityModel model) {
        final CatalogQueryService catalog = mock(CatalogQueryService.class);
        when(catalog.findModel(any(), any())).thenReturn(Optional.of(model));
        when(catalog.queryableEntityNames(any())).thenReturn(List.of(DialUsageLogTestModel.ENTITY));
        final ExprTranslator expr = new ExprTranslator(new ValueExprToObjectMapper());
        return new PortableQueryRenderer(catalog, new StructuredQueryBuilder(expr, new FilterTranslator(expr)),
                new QueryDslConfiguration().clickhouseRenderContext(),
                new StructuredQueryToSqlRenderer(new ValueExprToObjectMapper()),
                new SqlQueryFrontEnd(new CalciteQueryCatalog(catalog)));
    }
}
