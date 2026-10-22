package com.epam.aidial.analytics.service.query.translate;

import com.epam.aidial.analytics.configuration.QueryDslConfiguration;
import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.service.query.JooqTypes;
import com.epam.aidial.analytics.service.query.QueryFieldBinding;
import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.ComparisonOp;
import com.epam.aidial.analytics.service.query.model.Expr;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.FnExpr;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.ValueType;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DSL-surface coverage for {@code enum} fields. The DSL has no enum literal — a caller names an enum
 * value with the string it is — so the load-bearing rule is that an {@code enum} unifies with a
 * {@code string}. Without it the expression-position functions that require one type across two
 * operands ({@code equals}, and {@code if}'s branches) could not take an enum at all, which is the
 * failure most of this class exists to catch. The filter grammar's {@code eq} takes a different path
 * with no unification and was never at risk; the contains family is the exception, and has its own
 * cases below.
 */
class EnumFieldTranslationTest {

    private final ExprTranslator expr = new ExprTranslator(new ValueExprToObjectMapper());
    private final DSLContext ctx = new QueryDslConfiguration().clickhouseRenderContext();

    private final Map<String, QueryFieldBinding> bindings = Map.of(
            "status", binding("status", QueryFieldType.ENUM),
            "name", binding("name", QueryFieldType.STRING),
            "total", binding("total", QueryFieldType.LONG));

    private static QueryFieldBinding binding(String name, QueryFieldType type) {
        return new QueryFieldBinding(name, DSL.field(DSL.name("t", name), JooqTypes.of(type)), type);
    }

    private String render(Expr node) {
        return ctx.renderInlined(expr.toField(node, bindings));
    }

    private static Expr fn(String name, Expr... args) {
        return new FnExpr(name, false, List.of(args));
    }

    private static Expr text(String value) {
        return new ValueExpr(ValueType.STRING, value);
    }

    @Test
    void enumFieldComparesAgainstStringLiteral() {
        assertThat(render(fn("equals", new FieldExpr("status"), text("running"))))
                .contains("running");
    }

    /** Order-independent: the enum may be either operand. */
    @Test
    void stringLiteralComparesAgainstAnEnumField() {
        assertThat(render(fn("equals", text("running"), new FieldExpr("status"))))
                .contains("running");
    }

    @Test
    void enumFieldUnifiesWithStringField() {
        assertThat(render(fn("equals", new FieldExpr("status"), new FieldExpr("name"))))
                .isNotBlank();
    }

    /**
     * ClickHouse defines comparison over an enum but not the string functions, so admitting an enum to
     * the text family would emit a statement the server rejects. Refusing it here turns that into a
     * knowable input error instead of a failed query.
     */
    @Test
    void stringFunctionsRejectAnEnumOperand() {
        assertThatThrownBy(() -> render(fn("starts_with", new FieldExpr("status"), text("run"))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("requires a text")
                .hasMessageContaining("enum");
    }

    /**
     * The single-operand half of the family — {@code lower}, {@code upper}, {@code trim},
     * {@code length} — takes the same check. ClickHouse answers {@code lower(<Enum8 column>)} with
     * "Illegal type ... of argument", so without it the enum rule would hold for the two-operand
     * predicates and quietly leak through the four functions a caller reaches for first.
     */
    @Test
    void singleOperandStringFunctionsRejectAnEnumOperand() {
        for (final String fn : List.of("lower", "upper", "trim", "length")) {
            assertThatThrownBy(() -> render(fn(fn, new FieldExpr("status"))))
                    .as("function '%s' over an enum field", fn)
                    .isInstanceOf(QueryValidationException.class)
                    .hasMessageContaining("requires a text")
                    .hasMessageContaining("enum");
        }
    }

    /** The guard narrows nothing for the type those four functions are for. */
    @Test
    void singleOperandStringFunctionsStillAcceptTextFields() {
        for (final String fn : List.of("lower", "upper", "trim", "length")) {
            assertThat(render(fn(fn, new FieldExpr("name")))).containsIgnoringCase("name");
        }
    }

    /**
     * The filter grammar's {@code co}/{@code nc}/{@code ico}/{@code inc} are the same {@code LIKE} the
     * {@code contains} function expresses, over the same operand, so they take the same text check. Without
     * it the rejection would hold for one spelling of the operation while the other quietly sent
     * {@code "t"."status" LIKE '%run%'} to a server that has no such overload for an enum.
     */
    @Test
    void containsFamilyRejectsAnEnumField() {
        final FilterTranslator filters = new FilterTranslator(expr);
        for (final ComparisonOp op : List.of(
                ComparisonOp.CO, ComparisonOp.NC, ComparisonOp.ICO, ComparisonOp.INC)) {
            assertThatThrownBy(() -> filters.toCondition(
                            new ComparisonNode(op, List.of(new FieldExpr("status"), text("run"))), bindings))
                    .isInstanceOf(QueryValidationException.class)
                    .hasMessageContaining("requires a text")
                    .hasMessageContaining("enum");
        }
    }

    /** The guard narrows nothing for the type the operators are for. */
    @Test
    void containsFamilyStillAcceptsTextFields() {
        final FilterTranslator filters = new FilterTranslator(expr);
        assertThat(ctx.renderInlined(filters.toCondition(
                        new ComparisonNode(ComparisonOp.CO, List.of(new FieldExpr("name"), text("run"))),
                        bindings)))
                .containsIgnoringCase("like");
    }

    @Test
    void enumFieldAgainstNumericOperandIsRejected() {
        assertThatThrownBy(() -> render(fn("equals", new FieldExpr("status"),
                new ValueExpr(ValueType.INTEGER, "1"))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("must be of the same type");
    }

    @Test
    void enumFieldAgainstNumericFieldIsRejected() {
        assertThatThrownBy(() -> render(fn("equals", new FieldExpr("status"), new FieldExpr("total"))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("must be of the same type");
    }

    /**
     * Pins the property the SQL/Arrow layers rely on: an enum is an ordinary scalar, not one of the
     * projection-only families. It holds today because {@code isScalar} is written as an exclusion list;
     * this test is what would fail if that were ever inverted into an allow-list.
     */
    @Test
    void enumIsScalarType() {
        assertThat(ExprFieldType.isScalar(QueryFieldType.ENUM)).isTrue();
        assertThat(ExprFieldType.isNumeric(QueryFieldType.ENUM)).isFalse();
    }
}
