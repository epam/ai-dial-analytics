package com.epam.aidial.analytics.service.query.saved;

import com.epam.aidial.analytics.configuration.JsonMapperConfiguration;
import com.epam.aidial.analytics.exception.SavedQueryValidationException;
import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.ComparisonOp;
import com.epam.aidial.analytics.service.query.model.Expr;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.query.model.FnExpr;
import com.epam.aidial.analytics.service.query.model.OutputColumn;
import com.epam.aidial.analytics.service.query.model.ParamExpr;
import com.epam.aidial.analytics.service.query.model.QueryMode;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.ValueType;
import com.epam.aidial.analytics.service.query.saved.model.SavedQueryChart;
import com.epam.aidial.analytics.service.query.saved.model.SavedQueryTime;
import com.epam.aidial.analytics.service.query.saved.model.SavedQueryTimeMode;
import com.epam.aidial.analytics.web.dto.query.saved.SavedQueryRequestDto;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Envelope validation for a saved query — every rule that is checkable without consulting the catalog,
 * and every one of which must answer {@code 422 validation_error}.
 */
class SavedQueryBodyValidatorTest {

    private static final int MAX_BODY_BYTES = 65536;

    private static final JsonMapper MAPPER = new JsonMapperConfiguration().jsonMapper();

    private final SavedQueryBodyValidator validator = newValidator(MAX_BODY_BYTES);

    @Test
    void acceptsMinimalStructuredSavedQuery() {
        assertThatCode(() -> validator.validate(request("Top chats", simpleQuery(), null)))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsMinimalSqlSavedQuery() {
        assertThatCode(() -> validator.validate(request("Raw", null, "SELECT 1")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsNullRequest() {
        assertThatThrownBy(() -> validator.validate(null))
                .isInstanceOf(SavedQueryValidationException.class);
    }

    @Test
    void rejectsMissingName() {
        assertThatThrownBy(() -> validator.validate(request(null, simpleQuery(), null)))
                .isInstanceOf(SavedQueryValidationException.class)
                .hasMessageContaining("name");
    }

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> validator.validate(request("   ", simpleQuery(), null)))
                .isInstanceOf(SavedQueryValidationException.class)
                .hasMessageContaining("name");
    }

    @Test
    void rejectsBothBodies() {
        assertThatThrownBy(() -> validator.validate(request("Both", simpleQuery(), "SELECT 1")))
                .isInstanceOf(SavedQueryValidationException.class)
                .hasMessageContaining("both");
    }

    @Test
    void rejectsNeitherBody() {
        assertThatThrownBy(() -> validator.validate(request("Neither", null, null)))
                .isInstanceOf(SavedQueryValidationException.class)
                .hasMessageContaining("neither");
    }

    @Test
    void treatsBlankSqlAsNoBody() {
        assertThatThrownBy(() -> validator.validate(request("Blank sql", null, "   ")))
                .isInstanceOf(SavedQueryValidationException.class)
                .hasMessageContaining("neither");
    }

    @Test
    void acceptsStructuredBodyBesideBlankSql() {
        // Blank means absent, so this is one body — not "both".
        assertThatCode(() -> validator.validate(request("Blank sql beside a query", simpleQuery(), "")))
                .doesNotThrowAnyException();
    }

    @Test
    void normalizesBlankSqlToAbsentForPersistence() {
        // The stored value must agree with the rule above: writing "" next to a structured body would
        // violate the table's exactly-one-body CHECK, turning a validation concern into a 500.
        assertThat(SavedQueryBodyValidator.sqlBody(request("Blank sql", simpleQuery(), ""))).isNull();
        assertThat(SavedQueryBodyValidator.sqlBody(request("Blank sql", simpleQuery(), "  "))).isNull();
        assertThat(SavedQueryBodyValidator.sqlBody(request("Sql", null, "SELECT 1"))).isEqualTo("SELECT 1");
    }

    @Test
    void rejectsEachServerAssignedField() {
        assertThatThrownBy(() -> validator.validate(withServerField("id", "sq_1")))
                .isInstanceOf(SavedQueryValidationException.class).hasMessageContaining("id");
        assertThatThrownBy(() -> validator.validate(withServerField("owner_id", "someone")))
                .isInstanceOf(SavedQueryValidationException.class).hasMessageContaining("owner_id");
        // Taken from the caller's own token, never the body — otherwise a caller could author a saved
        // query that displays someone else's name as "Saved by".
        assertThatThrownBy(() -> validator.validate(withServerField("owner_email", "someone@example.com")))
                .isInstanceOf(SavedQueryValidationException.class).hasMessageContaining("owner_email");
        assertThatThrownBy(() -> validator.validate(withServerField("source", "dial_usage_log")))
                .isInstanceOf(SavedQueryValidationException.class).hasMessageContaining("source");
        assertThatThrownBy(() -> validator.validate(withServerField("generation", 7L)))
                .isInstanceOf(SavedQueryValidationException.class).hasMessageContaining("generation");
        assertThatThrownBy(() -> validator.validate(withServerField("created_at", Instant.EPOCH)))
                .isInstanceOf(SavedQueryValidationException.class).hasMessageContaining("created_at");
        assertThatThrownBy(() -> validator.validate(withServerField("updated_at", Instant.EPOCH)))
                .isInstanceOf(SavedQueryValidationException.class).hasMessageContaining("updated_at");
    }

    @Test
    void rejectsServerAssignedFieldWhateverShapeItsValueHas() {
        // The rule is about presence, not value. Held as typed Long/Instant/List<String> these would fail
        // in the binder and answer 400 before the validator ran — refused, but under the wrong code.
        // The array shape below is exactly what a client naturally echoes back on a read-then-write round
        // trip, since a saved query is always read back with `source` as an array.
        assertThatThrownBy(() -> validator.validate(withServerField("source", List.of("dial_usage_log"))))
                .isInstanceOf(SavedQueryValidationException.class).hasMessageContaining("source");
        assertThatThrownBy(() -> validator.validate(withServerField("generation", "soon")))
                .isInstanceOf(SavedQueryValidationException.class).hasMessageContaining("generation");
        assertThatThrownBy(() -> validator.validate(withServerField("created_at", "not-a-timestamp")))
                .isInstanceOf(SavedQueryValidationException.class).hasMessageContaining("created_at");
        assertThatThrownBy(() -> validator.validate(withServerField("updated_at", List.of(1, 2))))
                .isInstanceOf(SavedQueryValidationException.class).hasMessageContaining("updated_at");
    }

    @Test
    void rejectsSuppliedParamsField() {
        assertThatThrownBy(() -> validator.validate(withServerField("params", List.of("user_email"))))
                .isInstanceOf(SavedQueryValidationException.class)
                .hasMessageContaining("params");
    }

    @Test
    void rejectsParameterExpressionInFilter() {
        final FilterNode filter = new ComparisonNode(
                ComparisonOp.EQ, List.of(new FieldExpr("user_email"), new ParamExpr("who")));
        assertThatThrownBy(() -> validator.validate(request("Templated", queryFiltering(filter), null)))
                .isInstanceOf(SavedQueryValidationException.class)
                .hasMessageContaining("parameter");
    }

    @Test
    void rejectsParameterExpressionNestedInFunction() {
        final Expr nested = new FnExpr("lower", false, List.of(new ParamExpr("who")));
        final FilterNode filter =
                new ComparisonNode(ComparisonOp.EQ, List.of(new FieldExpr("user_email"), nested));
        assertThatThrownBy(() -> validator.validate(request("Templated", queryFiltering(filter), null)))
                .isInstanceOf(SavedQueryValidationException.class)
                .hasMessageContaining("parameter");
    }

    @Test
    void rejectsParameterExpressionInSelect() {
        final StructuredQuery query = new StructuredQuery(
                "dial_usage_log", null, QueryMode.ROW, false,
                List.of(new OutputColumn(new ParamExpr("who"), "who")), null, null, null, null);
        assertThatThrownBy(() -> validator.validate(request("Templated", query, null)))
                .isInstanceOf(SavedQueryValidationException.class)
                .hasMessageContaining("parameter");
    }

    @Test
    void rejectsParameterExpressionInHaving() {
        final StructuredQuery query = new StructuredQuery(
                "dial_usage_log", null, QueryMode.AGGREGATE, false, null, null,
                new ComparisonNode(ComparisonOp.EQ, List.of(new FieldExpr("total"), new ParamExpr("n"))),
                null, null);
        assertThatThrownBy(() -> validator.validate(request("Templated", query, null)))
                .isInstanceOf(SavedQueryValidationException.class)
                .hasMessageContaining("parameter");
    }

    @Test
    void acceptsNullTimeIntent() {
        assertThatCode(() -> validator.validate(withTime(null))).doesNotThrowAnyException();
    }

    @Test
    void acceptsWellFormedRelativeIntent() {
        assertThatCode(() -> validator.validate(withTime(relative("last_2d")))).doesNotThrowAnyException();
    }

    @Test
    void acceptsWellFormedAbsoluteIntent() {
        final SavedQueryTime time = new SavedQueryTime(
                SavedQueryTimeMode.ABSOLUTE, null, Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-08-06T00:00:00Z"));
        assertThatCode(() -> validator.validate(withTime(time))).doesNotThrowAnyException();
    }

    @Test
    void rejectsTimeIntentWithNoMode() {
        final SavedQueryTime time = new SavedQueryTime(null, "last_2d", null, null);
        assertThatThrownBy(() -> validator.validate(withTime(time)))
                .isInstanceOf(SavedQueryValidationException.class)
                .hasMessageContaining("time.mode");
    }

    @Test
    void rejectsRelativeIntentWithMalformedPeriod() {
        assertThatThrownBy(() -> validator.validate(withTime(relative("Last 2 Days!"))))
                .isInstanceOf(SavedQueryValidationException.class)
                .hasMessageContaining("time.period");
    }

    @Test
    void rejectsRelativeIntentWithNoPeriod() {
        assertThatThrownBy(() -> validator.validate(withTime(relative(null))))
                .isInstanceOf(SavedQueryValidationException.class)
                .hasMessageContaining("time.period");
    }

    @Test
    void rejectsRelativeIntentCarryingResolvedInstants() {
        // Resolving a relative period at save time would freeze the query to its authoring day.
        final SavedQueryTime time = new SavedQueryTime(
                SavedQueryTimeMode.RELATIVE, "last_2d", Instant.EPOCH, Instant.EPOCH.plusSeconds(60));
        assertThatThrownBy(() -> validator.validate(withTime(time)))
                .isInstanceOf(SavedQueryValidationException.class)
                .hasMessageContaining("relative");
    }

    @Test
    void rejectsAbsoluteIntentWithInvertedRange() {
        final SavedQueryTime time = new SavedQueryTime(
                SavedQueryTimeMode.ABSOLUTE, null, Instant.parse("2026-08-06T00:00:00Z"),
                Instant.parse("2026-08-01T00:00:00Z"));
        assertThatThrownBy(() -> validator.validate(withTime(time)))
                .isInstanceOf(SavedQueryValidationException.class)
                .hasMessageContaining("must not be after");
    }

    @Test
    void rejectsAbsoluteIntentMissingBound() {
        final SavedQueryTime time =
                new SavedQueryTime(SavedQueryTimeMode.ABSOLUTE, null, Instant.EPOCH, null);
        assertThatThrownBy(() -> validator.validate(withTime(time)))
                .isInstanceOf(SavedQueryValidationException.class)
                .hasMessageContaining("required");
    }

    @Test
    void rejectsAbsoluteIntentCarryingPeriod() {
        final SavedQueryTime time = new SavedQueryTime(
                SavedQueryTimeMode.ABSOLUTE, "last_2d", Instant.EPOCH, Instant.EPOCH.plusSeconds(60));
        assertThatThrownBy(() -> validator.validate(withTime(time)))
                .isInstanceOf(SavedQueryValidationException.class)
                .hasMessageContaining("period");
    }

    @Test
    void rejectsUnknownTimeModeToken() {
        // The shape the request node can carry but the typed union cannot: this must be the capability's
        // 422, not the binder's 400, so the mode is parsed here rather than by Jackson.
        assertThatThrownBy(() -> validator.validate(withTimeJson("{\"mode\":\"weekly\"}")))
                .isInstanceOf(SavedQueryValidationException.class)
                .hasMessageContaining("time.mode")
                .hasMessageContaining("weekly");
    }

    @Test
    void rejectsNonObjectTime() {
        assertThatThrownBy(() -> validator.validate(withTimeJson("\"yesterday\"")))
                .isInstanceOf(SavedQueryValidationException.class)
                .hasMessageContaining("'time' must be null");
        assertThatThrownBy(() -> validator.validate(withTimeJson("[]")))
                .isInstanceOf(SavedQueryValidationException.class)
                .hasMessageContaining("'time' must be null");
    }

    @Test
    void rejectsTimeWithUnparseableInstant() {
        final String badFrom = "{\"mode\":\"absolute\",\"from\":\"the day before\",\"to\":\"2026-08-06T00:00:00Z\"}";
        assertThatThrownBy(() -> validator.validate(withTimeJson(badFrom)))
                .isInstanceOf(SavedQueryValidationException.class)
                .hasMessageContaining("'time' must be null");
    }

    @Test
    void acceptsJsonNullTimeAsAbsent() {
        assertThatCode(() -> validator.validate(withTimeJson("null"))).doesNotThrowAnyException();
        assertThat(validator.timeIntent(withTimeJson("null"))).isNull();
    }

    @Test
    void bindsTheStoredTimeIntentFromTheRequestNode() {
        // Persistence writes what validation accepted, so an unrecognised key cannot ride along into the
        // jsonb column.
        final SavedQueryRequestDto request =
                withTimeJson("{\"mode\":\"relative\",\"period\":\"last_2d\",\"smuggled\":\"<script>\"}");
        assertThat(validator.timeIntent(request))
                .isEqualTo(new SavedQueryTime(SavedQueryTimeMode.RELATIVE, "last_2d", null, null));
    }

    @Test
    void rejectsBodyOverTheSizeCap() {
        final SavedQueryBodyValidator tiny = newValidator(32);
        assertThatThrownBy(() -> tiny.validate(request("Big", null, "SELECT " + "x".repeat(200))))
                .isInstanceOf(SavedQueryValidationException.class)
                .hasMessageContaining("maximum");
    }

    @Test
    void countsEveryBodyPartTowardsTheSizeCap() {
        // The chart alone is under the cap; it is the sum that must be measured.
        final SavedQueryBodyValidator tiny = newValidator(40);
        final SavedQueryRequestDto request = new SavedQueryRequestDto(
                "Sum", null, null, null, null, "SELECT 1", MAPPER.valueToTree(relative("last_2d")), null,
                new SavedQueryChart("bar", "a-fairly-long-x-axis-field-name", "y"),
                null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> tiny.validate(request))
                .isInstanceOf(SavedQueryValidationException.class)
                .hasMessageContaining("maximum");
    }

    private static SavedQueryBodyValidator newValidator(int maxBodyBytes) {
        final SavedQueryBodyValidator validator = new SavedQueryBodyValidator(MAPPER);
        ReflectionTestUtils.setField(validator, "maxBodyBytes", maxBodyBytes);
        return validator;
    }

    private static SavedQueryTime relative(String period) {
        return new SavedQueryTime(SavedQueryTimeMode.RELATIVE, period, null, null);
    }

    private static StructuredQuery simpleQuery() {
        return new StructuredQuery(
                "dial_usage_log", null, QueryMode.ROW, false, null, null, null, null, null);
    }

    private static StructuredQuery queryFiltering(FilterNode filter) {
        return new StructuredQuery(
                "dial_usage_log", filter, QueryMode.ROW, false, null, null, null, null, null);
    }

    private static SavedQueryRequestDto request(String name, StructuredQuery query, String sql) {
        return new SavedQueryRequestDto(
                name, null, null, null, query, sql, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    /** A valid request whose {@code time} is the typed intent, as the request node would carry it. */
    private static SavedQueryRequestDto withTime(SavedQueryTime time) {
        return withTimeNode(time == null ? null : MAPPER.valueToTree(time));
    }

    /** A valid request whose {@code time} is the given JSON verbatim — including shapes that will not bind. */
    private static SavedQueryRequestDto withTimeJson(String json) {
        return withTimeNode(MAPPER.readTree(json));
    }

    private static SavedQueryRequestDto withTimeNode(JsonNode time) {
        return new SavedQueryRequestDto(
                "Named", null, null, null, simpleQuery(), null, time, null, null,
                null, null, null, null, null, null, null, null);
    }

    /**
     * A valid request with exactly one server-assigned field populated.
     *
     * <p>The node-typed fields take any JSON shape on purpose: their <em>presence</em> is what the rule
     * rejects, so a well-formed value and a nonsense one must both reach the validator rather than one of
     * them failing in the binder and answering {@code 400}.
     */
    private static SavedQueryRequestDto withServerField(String field, Object value) {
        final JsonNode node = MAPPER.valueToTree(value);
        return new SavedQueryRequestDto(
                "Named", null, null, null, simpleQuery(), null, null, null, null,
                "id".equals(field) ? (String) value : null,
                "owner_id".equals(field) ? (String) value : null,
                "owner_email".equals(field) ? (String) value : null,
                "source".equals(field) ? node : null,
                "generation".equals(field) ? node : null,
                "created_at".equals(field) ? node : null,
                "updated_at".equals(field) ? node : null,
                "params".equals(field) ? node : null);
    }

    @Test
    void acceptsOrdinaryLiteralFilter() {
        // The envelope validator does not inspect literal values at all — an ordinary predicate must
        // pass here untouched.
        final Expr literal = new ValueExpr(ValueType.STRING, "some-project");
        final FilterNode filter =
                new ComparisonNode(ComparisonOp.EQ, List.of(new FieldExpr("project_id"), literal));
        assertThatCode(() -> validator.validate(request("Filtered", queryFiltering(filter), null)))
                .doesNotThrowAnyException();
    }
}
