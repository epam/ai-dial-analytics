package com.epam.aidial.analytics.service.query;

import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.exception.SqlTranslationException;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.OutputColumn;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.sql.SqlQueryFrontEnd;
import com.epam.aidial.analytics.service.query.sql.StructuredQueryToSqlRenderer;
import com.epam.aidial.analytics.service.query.translate.StructuredQueryBuilder;
import com.epam.aidial.analytics.web.security.Privilege;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Renders a {@link StructuredQuery} as the external SQL dialect after validating it exactly as the execute
 * path would, so what comes out is a statement {@code /v1/queries/execute-sql} accepts and a document
 * {@code /v1/queries/execute} accepts. ClickHouse is never contacted.
 *
 * <p>Two callers. The translation endpoint wants a refusal as an error ({@link #render}); a pipeline preview
 * wants it as a reason to report beside a sample that is still worth returning ({@link #tryRender}). The
 * second also reads the rendered SQL back through the SQL front end: the renderer refuses only what it cannot
 * print, while a shape such as a computed key aliased by an entity field's name is valid DSL that the front
 * end refuses as ambiguous — and the promise is "the query path runs it", not "the renderer printed it". The
 * document it returns is that reading, not the one it rendered from: the two are equivalent, and returning the
 * reading makes {@code /v1/queries/translate-sql} of the statement answer exactly the returned document, with the
 * defaults the front end spells out ({@code final}, the null ordering) spelled out the same way.
 */
@Component
@RequiredArgsConstructor
public class PortableQueryRenderer {

    private final CatalogQueryService catalog;
    private final StructuredQueryBuilder queryBuilder;
    private final DSLContext renderContext;
    private final StructuredQueryToSqlRenderer renderer;
    private final SqlQueryFrontEnd frontEnd;

    /**
     * A portable rendering, or why there is none.
     *
     * @param sql     the external-dialect statement; null when refused
     * @param query   the document the SQL front end reads {@code sql} as; null when refused
     * @param refusal the query path's own message; null when rendered
     * @param subject the output column the refusal is most likely about, where one can be named; else null
     */
    public record Portable(String sql, StructuredQuery query, String refusal, String subject) {

        public boolean rendered() {
            return sql != null;
        }
    }

    /** Validates {@code query} against {@code model} and renders it; a query the path refuses throws as it would there. */
    public String render(StructuredQuery query, QueryEntityModel model) {
        // Validation pass only — the jOOQ model (internal ClickHouse SQL) is deliberately discarded.
        queryBuilder.build(renderContext, model, query);
        return renderer.render(query, model);
    }

    /**
     * Renders {@code query} against the caller's own {@link Privilege#TABLE_GET} view of its entity — the bar the
     * translation endpoint uses, so a column the caller cannot see is never printed — and reads the result back.
     * Never throws for a query the dialect cannot carry: the refusal is returned instead.
     */
    public Portable tryRender(StructuredQuery query) {
        final QueryEntityModel model = catalog.findModel(query.entity(), Privilege.TABLE_GET).orElse(null);
        if (model == null) {
            return new Portable(null, null, "entity '" + query.entity() + "' is not queryable by the caller", null);
        }
        try {
            final String sql = render(query, model);
            return new Portable(sql, frontEnd.translate(sql, Privilege.TABLE_GET).query(), null, null);
        } catch (QueryValidationException | SqlTranslationException ex) {
            return new Portable(null, null, ex.getMessage(), shadowingOutput(query, model.bindings()));
        }
    }

    /**
     * The first output named after an entity field it does not itself project — the shape the query path reads as
     * that field rather than as the output. Only an attribution for a refusal the path already made, never the test
     * of whether a query is portable.
     */
    private static String shadowingOutput(StructuredQuery query, Map<String, QueryFieldBinding> bindings) {
        if (query.select() == null) {
            return null;
        }
        for (final OutputColumn column : query.select()) {
            final String name = column.as();
            if (name != null && bindings.containsKey(name) && !(column.expr() instanceof FieldExpr(String field) && field.equals(name))) {
                return name;
            }
        }
        return null;
    }
}
