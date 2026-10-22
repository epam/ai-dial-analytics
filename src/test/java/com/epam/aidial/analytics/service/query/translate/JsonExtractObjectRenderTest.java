package com.epam.aidial.analytics.service.query.translate;

import com.epam.aidial.analytics.configuration.QueryDslConfiguration;
import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.service.query.DialUsageLogTestModel;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.QueryFieldBinding;
import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.ComparisonOp;
import com.epam.aidial.analytics.service.query.model.Expr;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.FnExpr;
import com.epam.aidial.analytics.service.query.model.OutputColumn;
import com.epam.aidial.analytics.service.query.model.QueryMode;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.ValueType;
import org.jooq.DSLContext;
import org.jooq.conf.ParamType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Locks the two renderings of {@code json_extract_*}: the {@code JSONExtract*} text accessors over a
 * {@code string} column, and native JSON subcolumn access over an {@code object} column. The point of
 * these assertions is that the choice is driven purely by the argument field's declared type, that
 * the object form addresses the path through quoted identifier segments (never spliced text), and
 * that widening the overload left the text rendering byte-for-byte unchanged.
 *
 * <p>The <em>values</em> the two forms produce are pinned against a live ClickHouse by the parity
 * case in the structured-query execution integration test — SQL text alone cannot prove they agree.
 */
class JsonExtractObjectRenderTest {

    private static final String OBJECT_FIELD = "dial_usage_log_payload.jwt_claims";
    private static final String TEXT_FIELD = "dial_usage_log_payload.request_body";

    private final DSLContext ctx = new QueryDslConfiguration().clickhouseRenderContext();
    private final QueryEntityModel model = DialUsageLogTestModel.model();
    private final StructuredQueryBuilder builder = newBuilder();

    private static StructuredQueryBuilder newBuilder() {
        final ExprTranslator expr = new ExprTranslator(new ValueExprToObjectMapper());
        return new StructuredQueryBuilder(expr, new FilterTranslator(expr));
    }

    private String renderSelect(Expr expr) {
        final StructuredQuery query = new StructuredQuery(
                DialUsageLogTestModel.ENTITY, null, QueryMode.ROW, false,
                List.of(new OutputColumn(expr, "v")), null, null, null, null);
        return builder.build(ctx, model, query).getSQL(ParamType.INLINED);
    }

    private static FnExpr extract(String fn, String field, String... path) {
        final List<Expr> args = new java.util.ArrayList<>();
        args.add(new FieldExpr(field));
        for (final String key : path) {
            args.add(new ValueExpr(ValueType.STRING, key));
        }
        return new FnExpr(fn, false, args);
    }

    /**
     * The rendering with one wrong spelling next to it. {@code toString} over an array subcolumn yields
     * ClickHouse's array syntax — {@code ['a','b']} — which {@code JSONExtract} cannot parse, so the
     * obvious {@code JSONExtract(toString(<sub>), 'Array(String)')} returns the empty array for every
     * array carrying a quoted string, a null or a composite element, while still answering correctly for
     * arrays of bare integers, floats or booleans — the element kinds whose two syntaxes coincide.
     * Asserted as an absence as well as a presence, because the failure is silent and the two spellings
     * differ by one call.
     */
    @Test
    void arrayExtractOverObjectFieldSerializesTheSubcolumnRatherThanStringifyingIt() {
        assertThat(renderSelect(extract("json_extract_array", OBJECT_FIELD, "roles")))
                .contains("JSONExtract(assumeNotNull(ifNull(toJSONString("
                        + "\"dial_usage_log_payload\".\"jwt_claims\".\"roles\"), '')), 'Array(String)')")
                .doesNotContain("JSONExtract(toString(")
                .doesNotContain("toJSONString(\"dial_usage_log_payload\".\"jwt_claims\")");
    }

    /** Variadic, unlike the case-insensitive extractor: a deeper path addresses a deeper subcolumn. */
    @Test
    void arrayExtractOverObjectFieldAddressesAmultiSegmentPath() {
        assertThat(renderSelect(extract("json_extract_array", OBJECT_FIELD, "a", "b")))
                .contains("toJSONString(\"dial_usage_log_payload\".\"jwt_claims\".\"a\".\"b\")");
    }

    /** Over a text column nothing changed: the whole point is that the two forms still agree. */
    @Test
    void arrayExtractOverTextFieldIsUnchanged() {
        assertThat(renderSelect(extract("json_extract_array", TEXT_FIELD, "roles")))
                .contains("JSONExtract(")
                .contains("'roles'")
                .contains("'Array(String)'")
                .doesNotContain("ifNull(toJSONString(");
    }

    @Test
    void stringExtractOverObjectFieldRendersSubcolumnAccess() {
        assertThat(renderSelect(extract("json_extract_string", OBJECT_FIELD, "sub")))
                .contains("toString(\"dial_usage_log_payload\".\"jwt_claims\".\"sub\")")
                .doesNotContain("JSONExtractString")
                .doesNotContain("toJSONString");
    }

    /**
     * {@code JSONExtractInt} converts a number to an integer by truncating toward zero, so a bare
     * {@code toInt64OrZero} — which parses only wholly integral text — would read a member stored as
     * {@code 1.5} (or as the string {@code "1.5"}) as {@code 0} where the text form says {@code 1}.
     * Integral text is therefore read directly, keeping the exact digits of a value near the
     * {@code Int64} bounds, and everything else falls back through a truncated float read. The parity
     * integration test pins the values; this pins the shape.
     */
    @Test
    void longExtractOverObjectFieldReproducesTheTextFormsTruncation() {
        final String path = "toString(\"dial_usage_log_payload\".\"jwt_claims\".\"n\")";
        assertThat(renderSelect(extract("json_extract_long", OBJECT_FIELD, "n")))
                .contains("ifNull(toInt64OrNull(" + path + "), "
                        + "toInt64OrZero(toString(trunc(toFloat64OrZero(" + path + ")))))");
    }

    @Test
    void doubleExtractOverObjectFieldCoercesThroughText() {
        assertThat(renderSelect(extract("json_extract_double", OBJECT_FIELD, "f")))
                .contains("toFloat64OrZero(toString(\"dial_usage_log_payload\".\"jwt_claims\".\"f\"))");
    }

    /**
     * The value is reached by comparison, not by casting the path text: {@code toBool} over an absent
     * path's empty string throws {@code CANNOT_PARSE_BOOL}, where a comparison yields {@code false}
     * exactly as {@code JSONExtractBool} does. The outer {@code toBool} is still required — the
     * comparison renders as ClickHouse {@code UInt8}, which would serialize as 1/0 rather than as the
     * advertised boolean.
     *
     * <p>Which comparison depends on the stored member's type, because {@code JSONExtractBool} coerces
     * by type: a bare {@code = 'true'} would read a member stored as the number {@code 1} as false
     * (where the text form says true) and a member stored as the string {@code "true"} as true (where
     * the text form says false). The parity integration test pins the values; this pins the shape.
     */
    @Test
    void boolExtractOverObjectFieldReproducesTheTextFormsTypeCoercion() {
        final String sql = renderSelect(extract("json_extract_bool", OBJECT_FIELD, "b"));
        final String path = "\"dial_usage_log_payload\".\"jwt_claims\".\"b\"";
        assertThat(sql).contains("toBool(multiIf(");
        // A Bool member yields its own value...
        assertThat(sql).contains("dynamicType(" + path + ") = 'Bool'")
                .contains("toString(" + path + ") = 'true'");
        // ...an integer member yields value != 0, as JSONExtractBool does...
        assertThat(sql).contains("'Int64'").contains("'UInt64'")
                .contains("toString(" + path + ") != '0'");
        // ...and every other stored type (string, fractional number, absent path) is false.
        assertThat(sql).contains("false))");
        // toBool wraps the comparison, never the raw path text.
        assertThat(sql).doesNotContain("toBool(toString(");
    }

    @Test
    void nestedPathRendersEveryKeyAsItsOwnQuotedSegment() {
        assertThat(renderSelect(extract("json_extract_long", OBJECT_FIELD, "usage", "total_tokens")))
                .contains("\"dial_usage_log_payload\".\"jwt_claims\".\"usage\".\"total_tokens\"");
    }

    /**
     * A path key is an identifier segment, so jOOQ escapes it. Were it spliced as text, the quote and
     * the statement separator would terminate the identifier and open a second statement.
     */
    @Test
    void pathKeyWithSqlMetacharactersIsEscapedIntoOneIdentifier() {
        final String sql = renderSelect(extract("json_extract_string", OBJECT_FIELD, "ev\"il; DROP TABLE t; --"));
        // The embedded quote is doubled, so the key stays inside one identifier rather than closing it.
        assertThat(sql).contains("\"dial_usage_log_payload\".\"jwt_claims\".\"ev\"\"il; DROP TABLE t; --\"");
        // The key sits inside the projection and the statement continues normally, so nothing escaped
        // the identifier to become SQL of its own.
        assertThat(sql).containsSubsequence(
                "toString(", "\"ev\"\"il; DROP TABLE t; --\"", "from \"dial_usage_log\"");
    }

    /**
     * jOOQ doubles a double quote inside an identifier but leaves a backslash alone, and ClickHouse's
     * identifier lexer honours C-style escapes inside a double-quoted identifier: {@code "a\""} closes
     * the identifier just as {@code "a"""} does. A key carrying a backslash could therefore end the
     * subcolumn early and continue as SQL of its own, so it is refused rather than rendered.
     */
    @Test
    void pathKeyWithBackslashIsRejected() {
        final String breakout = "x\\\") FROM dial_usage_log UNION ALL SELECT 'PWN' AS v --";
        assertThatThrownBy(() -> renderSelect(extract("json_extract_string", OBJECT_FIELD, breakout)))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("must not contain a backslash");
    }

    /**
     * The same refusal over a {@code string} column, where the key would render as an escaped literal
     * and nothing is at risk: the overload's contract is that the two forms are interchangeable, so a
     * key either works over both storages or over neither.
     */
    @Test
    void pathKeyWithBackslashIsRejectedOverTheTextColumnToo() {
        assertThatThrownBy(() -> renderSelect(extract("json_extract_string", TEXT_FIELD, "a\\b")))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("must not contain a backslash");
    }

    /**
     * jOOQ drops an empty identifier segment instead of rendering it, so the key would address the
     * enclosing path rather than a member: {@code json_extract_string(dial_usage_log_payload.jwt_claims, '')} would render as
     * {@code toString("dial_usage_log_payload.jwt_claims")}, the whole serialized document, where the text form yields the
     * empty string. A segment that disappears cannot address anything, so it is refused.
     */
    @Test
    void emptyPathKeyIsRejected() {
        assertThatThrownBy(() -> renderSelect(extract("json_extract_string", OBJECT_FIELD, "")))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("must not be empty");
    }

    /**
     * The same, mid-path: an empty segment among other keys would silently shift the address —
     * {@code json_extract_long(dial_usage_log_payload.jwt_claims, '', 'usage')} would read {@code "dial_usage_log_payload.jwt_claims"."usage"}.
     */
    @Test
    void emptyPathKeyAmongOtherKeysIsRejected() {
        assertThatThrownBy(() -> renderSelect(extract("json_extract_long", OBJECT_FIELD, "", "usage")))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("must not be empty");
    }

    /**
     * And over a {@code string} column, where the empty literal renders correctly and the text
     * accessor answers with its type default: the two forms are interchangeable only if a key either
     * works over both storages or over neither.
     */
    @Test
    void emptyPathKeyIsRejectedOverTheTextColumnToo() {
        assertThatThrownBy(() -> renderSelect(extract("json_extract_string", TEXT_FIELD, "")))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("must not be empty");
    }

    @Test
    void textColumnRenderingIsUnchanged() {
        final String sql = renderSelect(extract("json_extract_string", TEXT_FIELD, "model"));
        assertThat(sql).contains("JSONExtractString(\"dial_usage_log_payload\".\"request_body\", 'model')");
        assertThat(sql).doesNotContain("toString(");
    }

    @Test
    void textColumnBoolRenderingKeepsItsToBoolWrap() {
        assertThat(renderSelect(extract("json_extract_bool", TEXT_FIELD, "stream")))
                .contains("toBool(JSONExtractBool(\"dial_usage_log_payload\".\"request_body\", 'stream'))");
    }

    /**
     * Access control is inherited, not re-implemented. A {@code sensitive} object column never enters
     * the bindings for a caller who may not see it, so extracting from it fails as an ordinary unknown
     * field — the same way projecting it does. Extraction grants no reach the schema did not already.
     */
    @Test
    void extractingFromAnUnresolvableObjectFieldFailsAsUnknownField() {
        final Map<String, QueryFieldBinding> withoutClaims = new LinkedHashMap<>(model.bindings());
        withoutClaims.remove(OBJECT_FIELD);
        final QueryEntityModel filtered = new QueryEntityModel(
                DialUsageLogTestModel.ENTITY, model.table(), withoutClaims, model.enrichments(), model.baseSchema());
        final StructuredQuery query = new StructuredQuery(
                DialUsageLogTestModel.ENTITY, null, QueryMode.ROW, false,
                List.of(new OutputColumn(extract("json_extract_string", OBJECT_FIELD, "sub"), "v")),
                null, null, null, null);

        assertThatThrownBy(() -> builder.build(ctx, filtered, query))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("unknown field '" + OBJECT_FIELD + "'");
    }

    @Test
    void extractedObjectPathIsUsableAsFilterOperand() {
        final ComparisonNode filter = new ComparisonNode(
                ComparisonOp.EQ,
                List.of(extract("json_extract_string", OBJECT_FIELD, "sub"), new ValueExpr(ValueType.STRING, "user-1")));
        final StructuredQuery query = new StructuredQuery(
                DialUsageLogTestModel.ENTITY, filter, QueryMode.ROW, false,
                List.of(new OutputColumn(new FieldExpr("event_id"), null)), null, null, null, null);
        assertThat(builder.build(ctx, model, query).getSQL(ParamType.INLINED))
                .contains("where")
                .contains("toString(\"dial_usage_log_payload\".\"jwt_claims\".\"sub\")");
    }
}
