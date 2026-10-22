package com.epam.aidial.analytics.service.query.translate;

import com.epam.aidial.analytics.configuration.QueryDslConfiguration;
import com.epam.aidial.analytics.service.query.DialUsageLogTestModel;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.FnExpr;
import com.epam.aidial.analytics.service.query.model.OutputColumn;
import com.epam.aidial.analytics.service.query.model.QueryMode;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import org.jooq.DSLContext;
import org.jooq.conf.ParamType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ClickHouse SQL {@code div} and {@code modulo} render to. Both guard a zero or NULL divisor so that no row, not
 * even one the result's NULL mask hides, divides by zero: a hidden {@code inf}/{@code nan} fails the conversion an
 * {@code INSERT … SELECT} into a {@code decimal} or integer column performs.
 */
class GuardedDivisionRenderTest {

    private static final FieldExpr TOTAL_TOKENS = new FieldExpr("total_tokens");
    private static final FieldExpr PROMPT_TOKENS = new FieldExpr("prompt_tokens");

    private final DSLContext ctx = new QueryDslConfiguration().clickhouseRenderContext();
    private final QueryEntityModel model = DialUsageLogTestModel.model();
    private final StructuredQueryBuilder builder = newBuilder();

    private static StructuredQueryBuilder newBuilder() {
        final ExprTranslator expr = new ExprTranslator(new ValueExprToObjectMapper());
        return new StructuredQueryBuilder(expr, new FilterTranslator(expr));
    }

    private String render(String fn) {
        final FnExpr call = new FnExpr(fn, false, List.of(TOTAL_TOKENS, PROMPT_TOKENS));
        return builder.build(ctx, model, new StructuredQuery(DialUsageLogTestModel.ENTITY, null, QueryMode.ROW, false,
                List.of(new OutputColumn(call, "r")), null, null, null, null)).getSQL(ParamType.INLINED);
    }

    @Test
    void divNeverDividesByZero() {
        assertThat(render("div")).contains("if(coalesce(\"dial_usage_log\".\"prompt_tokens\", 0) = 0, NULL, "
                + "divide(\"dial_usage_log\".\"total_tokens\", coalesce(nullIf(\"dial_usage_log\".\"prompt_tokens\", 0), 1)))");
    }

    @Test
    void moduloNeverDividesByZero() {
        assertThat(render("modulo")).contains("if(coalesce(\"dial_usage_log\".\"prompt_tokens\", 0) = 0, NULL, "
                + "modulo(\"dial_usage_log\".\"total_tokens\", coalesce(nullIf(\"dial_usage_log\".\"prompt_tokens\", 0), 1)))");
    }
}
