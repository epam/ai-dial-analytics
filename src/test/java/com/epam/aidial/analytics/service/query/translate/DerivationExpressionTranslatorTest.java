package com.epam.aidial.analytics.service.query.translate;

import com.epam.aidial.analytics.configuration.QueryDslConfiguration;
import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.service.query.ArrayValuedFunctions;
import com.epam.aidial.analytics.service.query.DialUsageLogTestModel;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.QueryFieldBinding;
import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.ComparisonOp;
import com.epam.aidial.analytics.service.query.model.Expr;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.FnExpr;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.ValueType;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DSL-surface coverage for the derivation expression functions — {@code if} and its predicate producers,
 * {@code json_extract_string_ci}, {@code json_extract_array} — over the {@link DialUsageLogTestModel}
 * bindings, which carry both {@code string} and {@code object} columns.
 *
 * <p>Rendering is asserted, not just acceptance, because two of these functions have a second rendering for
 * an {@code object} argument and the wrong one <em>succeeds</em> against ClickHouse while returning nothing
 * (see {@code ExprTranslator#documentSource}). A test that only checked "no exception" would pass on the
 * broken form.
 */
class DerivationExpressionTranslatorTest {

    private final ExprTranslator expr = new ExprTranslator(new ValueExprToObjectMapper());
    private final FilterTranslator filter = new FilterTranslator(expr);
    private final DSLContext ctx = new QueryDslConfiguration().clickhouseRenderContext();
    private final QueryEntityModel model = DialUsageLogTestModel.model();
    private final Map<String, QueryFieldBinding> bindings = model.bindings();

    private String render(Expr node) {
        return ctx.renderInlined(expr.toField(node, bindings));
    }

    private static Expr text(String value) {
        return new ValueExpr(ValueType.STRING, value);
    }

    private static Expr fn(String name, Expr... args) {
        return new FnExpr(name, false, List.of(args));
    }

    /** A boolean-valued condition, so a test about branches is not also a test about the condition. */
    private static Expr cond() {
        return fn("not_empty", new FieldExpr("chat_id"));
    }

    // --- not_empty ------------------------------------------------------------------------------------

    @Test
    void notEmptyIsFalseForNullAndForEmpty() {
        // The null case is the whole reason this function exists rather than length(x) > 0: ClickHouse's
        // notEmpty(NULL) is NULL, and a NULL condition would fall to if's else branch by accident rather
        // than by contract. The ifNull wrap is what makes if(not_empty(a), a, b) a first-non-absent merge.
        assertThat(render(fn("not_empty", new FieldExpr("chat_id"))))
                .contains("notEmpty(ifNull(")
                .contains("''");
    }

    @Test
    void notEmptyTakesExactlyOneArgument() {
        assertThatThrownBy(() -> render(fn("not_empty", new FieldExpr("chat_id"), text("x"))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("exactly one argument");
    }

    // --- starts_with / contains / equals ---------------------------------------------------------------

    @Test
    void predicateProducersAreNullSafe() {
        // startsWith(NULL, 'x'), position(NULL, 'x') > 0 and NULL = 'x' all evaluate to NULL in ClickHouse.
        // The spec says these are false — never null — so each rendering wraps the test.
        assertThat(render(fn("starts_with", new FieldExpr("deployment"), text("gpt"))))
                .contains("ifNull(startsWith(");
        assertThat(render(fn("contains", new FieldExpr("deployment"), text("gpt"))))
                .contains("ifNull(position(")
                .contains("> 0");
        assertThat(render(fn("equals", new FieldExpr("deployment"), text("gpt"))))
                .contains("ifNull(")
                .contains("= 'gpt'");
    }

    @Test
    void predicateProducersCompareCaseSensitively() {
        // No _ci variants: lower() is already in the catalog and a caller wanting a case-insensitive test
        // wraps both operands in it, which is what the rendering must let them do.
        assertThat(render(fn("starts_with", fn("lower", new FieldExpr("deployment")), text("gpt"))))
                .contains("lower(")
                .contains("startsWith(");
    }

    @Test
    void predicateProducersTakeExactlyTwoArguments() {
        for (final String name : List.of("starts_with", "contains", "equals")) {
            assertThatThrownBy(() -> render(fn(name, new FieldExpr("deployment"))))
                    .isInstanceOf(QueryValidationException.class)
                    .hasMessageContaining("exactly two arguments");
        }
    }

    /**
     * The operand-type checks exist so the DSL surface and the SQL surface answer the same query the same
     * way. {@code SqlQueryOperators} declares {@code not_empty}/{@code starts_with}/{@code contains} with a
     * {@code CHARACTER} operand family, so all of these are translation-time 400s there; without the check
     * here they would translate, render, and fail inside ClickHouse instead.
     */
    @Test
    void textPredicatesRejectNonTextOperand() {
        assertThatThrownBy(() -> render(fn("not_empty", new FieldExpr("total_tokens"))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("text operand")
                .hasMessageContaining("long");
        assertThatThrownBy(() -> render(fn("contains", new FieldExpr("dial_usage_log_payload.jwt_claims"), text("x"))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("text")
                .hasMessageContaining("object");
        assertThatThrownBy(() -> render(fn("starts_with", new FieldExpr("deployment"),
                new ValueExpr(ValueType.INTEGER, "1"))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("text prefix");
    }

    @Test
    void textPredicatesAcceptUuidOperand() {
        // uuid renders as a ClickHouse string and types as CHARACTER in the Calcite schema, so refusing it
        // here would be the DSL surface being stricter than the SQL one — the same drift, mirrored.
        assertThat(render(fn("starts_with", new FieldExpr("event_id"), text("41"))))
                .contains("startsWith(");
    }

    @Test
    void equalsRequiresComparableOperands() {
        // Not a text family — equals takes any type — but the two operands still have to unify, because
        // ClickHouse raises on a pair it has no supertype for rather than returning false.
        assertThatThrownBy(() -> render(fn("equals", new FieldExpr("deployment"),
                new ValueExpr(ValueType.INTEGER, "1"))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("operands must be of the same type");
        assertThat(render(fn("equals", new FieldExpr("total_tokens"),
                new ValueExpr(ValueType.INTEGER, "1"))))
                .as("integer against a long widens, as it does everywhere else")
                .contains("= 1");
    }

    @Test
    void equalsRejectsNullOperandRatherThanAlwaysAnsweringFalse() {
        // x = NULL is null for every row, which the ifNull wrap then reports as false for every row — a
        // silent always-false, and not what the name suggests: filter's eq against null means IS NULL.
        assertThatThrownBy(() -> render(fn("equals", new FieldExpr("chat_id"),
                new ValueExpr(ValueType.NULL, null))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("null literal")
                .hasMessageContaining("filter");
    }

    // --- if -------------------------------------------------------------------------------------------

    @Test
    void conditionalRendersBothBranches() {
        assertThat(render(fn("if",
                fn("not_empty", new FieldExpr("chat_id")),
                new FieldExpr("chat_id"),
                text("none"))))
                .contains("if(")
                .contains("notEmpty(")
                .contains("'none'");
    }

    @Test
    void conditionalsNest() {
        // A multi-branch classification is nested ifs — the DSL has no CASE and this is how a caller writes
        // one, so the nesting has to survive translation rather than being flattened or refused.
        assertThat(render(fn("if",
                fn("not_empty", new FieldExpr("chat_id")),
                text("chat"),
                fn("if", fn("starts_with", new FieldExpr("deployment"), text("gpt")), text("gpt"), text("other")))))
                .contains("'chat'")
                .contains("'gpt'")
                .contains("'other'");
    }

    @Test
    void conditionalRejectsNonBooleanCondition() {
        assertThatThrownBy(() -> render(fn("if", new FieldExpr("total_tokens"), text("a"), text("b"))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("boolean condition")
                .hasMessageContaining("not_empty");
    }

    @Test
    void conditionalRejectsComparisonShapedCondition() {
        // length(x) > 0 has no expression-position form at all — there is no comparison node in Expr — so
        // the closest a caller can get is length(x) as the condition, which is what this rejects. The
        // message names not_empty because that is the fix.
        assertThatThrownBy(() -> render(fn("if",
                fn("length", new FieldExpr("chat_id")), text("a"), text("b"))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("not_empty");
    }

    @Test
    void conditionalRejectsMismatchedBranchTypes() {
        assertThatThrownBy(() -> render(fn("if",
                fn("not_empty", new FieldExpr("chat_id")),
                text("text"),
                new ValueExpr(ValueType.INTEGER, "1"))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("same type");
    }

    @Test
    void conditionalWidensNumericBranches() {
        assertThatCode(() -> render(fn("if",
                fn("not_empty", new FieldExpr("chat_id")),
                new ValueExpr(ValueType.INTEGER, "1"),
                new ValueExpr(ValueType.DECIMAL, "1.5"))))
                .doesNotThrowAnyException();
    }

    /**
     * The branch rule reads the <b>DSL</b> type, not the translated field's Java type. These four cases are
     * exactly where the two disagree, and all four were wrong while the rule read jOOQ's: {@code JooqTypes}
     * maps {@code long} and {@code timestamp} onto the same {@code BIGINT}, maps {@code object},
     * {@code array} and {@code map} onto the same {@code OTHER}, and renders a timestamp literal through
     * {@code fromUnixTimestamp64Milli}, which carries neither. See {@link ExprFieldType}.
     */
    @Test
    void conditionalDoesNotUnifyTimestampWithLong() {
        // Both are BIGINT to jOOQ. ClickHouse has no supertype for DateTime64 and Int64, so accepting this
        // turns an authoring mistake into a server error where the contract promises a 400.
        assertThatThrownBy(() -> render(fn("if", cond(),
                new FieldExpr("request_time"), new FieldExpr("total_tokens"))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("same type")
                .hasMessageContaining("timestamp")
                .hasMessageContaining("long");
    }

    @Test
    void conditionalUnifiesTimestampColumnWithTimestampLiteral() {
        // The mirror image: legitimate, and refused while the rule compared a BIGINT column against the
        // Object-typed field a timestamp literal renders as.
        assertThat(render(fn("if", cond(),
                new FieldExpr("request_time"), new ValueExpr(ValueType.TIMESTAMP, "1767225600000"))))
                .contains("if(")
                .contains("fromUnixTimestamp64Milli");
    }

    @Test
    void conditionalRejectsNonScalarBranches() {
        // object/array/map are all OTHER to jOOQ, so every pairing of them looked like a match. A conditional
        // over them has nothing to return, and letting one through would produce a non-scalar result that
        // ArrayValuedFunctions — which recognizes array results by function name — cannot see.
        for (final String column : List.of("dial_usage_log_payload.jwt_claims", "execution_path")) {
            assertThatThrownBy(() -> render(fn("if", cond(), new FieldExpr(column), new FieldExpr(column))))
                    .as("if over %s", column)
                    .isInstanceOf(QueryValidationException.class)
                    .hasMessageContaining("can only be projected");
        }
        assertThatThrownBy(() -> render(fn("length",
                fn("if", cond(), new FieldExpr("dial_usage_log_payload.jwt_claims"), new FieldExpr("dial_usage_log_payload.request_tags")))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("can only be projected");
    }

    @Test
    void conditionalRejectsNullBranchAndSaysWhereNullTestsBelong() {
        assertThatThrownBy(() -> render(fn("if", cond(),
                new FieldExpr("chat_id"), new ValueExpr(ValueType.NULL, null))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("null literal")
                .hasMessageContaining("filter");
    }

    @Test
    void conditionalTypesNestedConditionalFromItsBranches() {
        // ExprFieldType resolves a nested if through its `then` branch, so the outer call sees `string` here
        // rather than an untyped operand it would have to give up on.
        assertThat(render(fn("if", cond(),
                fn("if", cond(), text("a"), text("b")),
                new FieldExpr("chat_id"))))
                .contains("if(");
        assertThatThrownBy(() -> render(fn("if", cond(),
                fn("if", cond(), text("a"), text("b")),
                new FieldExpr("total_tokens"))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("same type");
    }

    @Test
    void conditionalTakesExactlyThreeArguments() {
        assertThatThrownBy(() -> render(fn("if",
                fn("not_empty", new FieldExpr("chat_id")), text("a"))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("exactly three arguments");
    }

    // --- json_extract_string_ci -----------------------------------------------------------------------

    @Test
    void caseInsensitiveExtractLowersTheKeyAtTranslationTime() {
        // The search key is a literal, so it is folded once here rather than once per row; only the KEY is
        // folded, never the document, which would corrupt every value it touched.
        final String sql = render(fn("json_extract_string_ci", new FieldExpr("dial_usage_log_payload.request_body"), text("X-Session-Id")));

        assertThat(sql)
                .contains("arrayFirst(")
                .contains("arrayMap(")
                .contains("JSONExtractKeysAndValues(")
                .contains("'x-session-id'")
                .doesNotContain("'X-Session-Id'");
    }

    @Test
    void caseInsensitiveExtractReadsAnObjectColumnThroughItsSerializedText() {
        // The regression this exists for: JSONExtractKeysAndValues over a native JSON column returns an
        // EMPTY key set and raises nothing (measured on ClickHouse 26.3), so passing the column straight
        // through would make this function return '' for every row of dial_usage_log_payload.request_tags — the column it was
        // built for — with no error anywhere. toJSONString is what makes the object form work at all.
        final String sql = render(fn("json_extract_string_ci", new FieldExpr("dial_usage_log_payload.request_tags"), text("X-Session-Id")));

        // Wrapped non-null too: the document arrives across the payload LEFT JOIN, and JSONExtractKeysAndValues
        // refuses a Nullable operand (PayloadJsonTextExtractionIntegrationTest).
        assertThat(sql)
                .contains("JSONExtractKeysAndValues(assumeNotNull(ifNull(toJSONString(")
                .contains("\"dial_usage_log_payload\".\"request_tags\"");
    }

    @Test
    void caseInsensitiveExtractRejectsMultiSegmentPath() {
        assertThatThrownBy(() -> render(fn("json_extract_string_ci",
                new FieldExpr("dial_usage_log_payload.request_body"), text("a"), text("b"))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("single top-level key");
    }

    // --- json_extract_array ---------------------------------------------------------------------------

    @Test
    void arrayExtractRendersTypedExtraction() {
        assertThat(render(fn(ArrayValuedFunctions.JSON_EXTRACT_ARRAY, new FieldExpr("dial_usage_log_payload.request_body"), text("roles"))))
                .contains("JSONExtract(")
                .contains("'roles'")
                .contains("'Array(String)'");
    }

    @Test
    void arrayExtractReadsAnObjectColumnThroughTheAddressedSubcolumn() {
        // The serialization is over the subcolumn, not the column: one read stream rather than one per
        // JSON path per part. Handing the column itself to JSONExtract fails loudly
        // (ILLEGAL_TYPE_OF_ARGUMENT), which is why some serialization is needed at all.
        assertThat(render(fn(ArrayValuedFunctions.JSON_EXTRACT_ARRAY, new FieldExpr("dial_usage_log_payload.jwt_claims"), text("roles"))))
                .contains("JSONExtract(assumeNotNull(ifNull(toJSONString("
                        + "\"dial_usage_log_payload\".\"jwt_claims\".\"roles\"), '')), 'Array(String)')")
                .doesNotContain("toJSONString(\"dial_usage_log_payload\".\"jwt_claims\")");
    }

    @Test
    void arrayExtractResultCannotBeNestedInAnotherFunction() {
        // Same projection-only rule group_uniq_array carries, and now stated once for both: the DSL has no
        // array operand, so an array result is expressible only as a projection item.
        assertThatThrownBy(() -> render(fn("length", fn(ArrayValuedFunctions.JSON_EXTRACT_ARRAY, new FieldExpr("dial_usage_log_payload.jwt_claims"), text("roles")))))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining(ArrayValuedFunctions.JSON_EXTRACT_ARRAY)
                .hasMessageContaining("can only be projected");
    }

    @Test
    void arrayExtractResultCannotBeComparisonOperand() {
        assertThatThrownBy(() -> filter.toCondition(new ComparisonNode(ComparisonOp.EQ, List.of(
                        fn(ArrayValuedFunctions.JSON_EXTRACT_ARRAY, new FieldExpr("dial_usage_log_payload.jwt_claims"), text("roles")),
                        text("admin"))), bindings))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("can only be projected");
    }

}
