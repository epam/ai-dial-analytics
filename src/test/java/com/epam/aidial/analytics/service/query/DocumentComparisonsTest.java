package com.epam.aidial.analytics.service.query;

import com.epam.aidial.analytics.service.query.model.ComparisonOp;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The classifier both surfaces consult. Tested directly, per operator × type, because the whole point of
 * the class is that the DSL surface and the SQL front end cannot answer differently — a drift between
 * them would otherwise only show up as a round-trip failure in a much larger test.
 */
class DocumentComparisonsTest {

    /** The seven operators an object field admits; the two families the class documents. */
    private static final Set<ComparisonOp> ACCEPTED = EnumSet.of(
            ComparisonOp.CO, ComparisonOp.NC, ComparisonOp.ICO, ComparisonOp.INC,
            ComparisonOp.EQ, ComparisonOp.NE, ComparisonOp.IN);

    @ParameterizedTest
    @EnumSource(ComparisonOp.class)
    void objectAdmitsExactlyTheDocumentOperators(ComparisonOp op) {
        assertThat(DocumentComparisons.accepts(QueryFieldType.OBJECT, op))
                .as("object field under '%s'", op.code())
                .isEqualTo(ACCEPTED.contains(op));
    }

    @ParameterizedTest
    @EnumSource(ComparisonOp.class)
    void arrayAdmitsNoOperator(ComparisonOp op) {
        assertThat(DocumentComparisons.accepts(QueryFieldType.ARRAY, op)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(ComparisonOp.class)
    void mapAdmitsNoOperator(ComparisonOp op) {
        assertThat(DocumentComparisons.accepts(QueryFieldType.MAP, op)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = QueryFieldType.class, names = {"OBJECT", "ARRAY", "MAP"}, mode = EnumSource.Mode.EXCLUDE)
    void scalarTypesAreNotDocumentOperands(QueryFieldType type) {
        // A scalar is comparable, but never *as a document* — it takes the ordinary rendering path, so
        // this classifier must not claim it. Answering true here would wrap a string column in
        // toJSONString and silently change what its filters mean.
        assertThat(DocumentComparisons.accepts(type, ComparisonOp.EQ)).isFalse();
        assertThat(DocumentComparisons.accepts(type, ComparisonOp.CO)).isFalse();
    }

    @Test
    void orderingComparisonsAreRejectedOnEveryType() {
        for (final ComparisonOp op : EnumSet.of(
                ComparisonOp.LT, ComparisonOp.GT, ComparisonOp.LE, ComparisonOp.GE)) {
            assertThat(DocumentComparisons.accepts(QueryFieldType.OBJECT, op))
                    .as("ordering '%s' over a document", op.code())
                    .isFalse();
        }
    }

    @Test
    void onlyTheEqualityFamilyRequiresJsonLiterals() {
        for (final ComparisonOp op : ComparisonOp.values()) {
            assertThat(DocumentComparisons.requiresJsonLiteral(op))
                    .as("'%s' requires a JSON literal", op.code())
                    .isEqualTo(EnumSet.of(ComparisonOp.EQ, ComparisonOp.NE, ComparisonOp.IN).contains(op));
        }
    }

    @Test
    void nullTypeAndNullOperatorAreNotMatches() {
        assertThat(DocumentComparisons.accepts(null, ComparisonOp.EQ)).isFalse();
        assertThat(DocumentComparisons.accepts(QueryFieldType.OBJECT, null)).isFalse();
        assertThat(DocumentComparisons.requiresJsonLiteral(null)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"a\":1}", "{ \"a\" : [1,2] }", "{\"n\":{\"z\":1}}"})
    void jsonObjectsAreDocuments(String literal) {
        assertThat(DocumentComparisons.isJsonDocument(literal)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"x", "", "[1,2]", "\"just-a-string\"", "42", "true", "null", "{\"a\":", "{'a':1}"})
    void nonDocumentsAreRejected(String literal) {
        // A JSON array or bare scalar is well-formed JSON but not a *document*, and the ClickHouse JSON
        // type it would be cast to holds an object — so both fail here rather than at execution.
        assertThat(DocumentComparisons.isJsonDocument(literal)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"a\":1} garbage", "{\"a\":1}{\"b\":2}", "{\"a\":1}]", "{} 0", "{} \"tail\""})
    void trailingContentAfterTheDocumentIsRejected(String literal) {
        // Jackson's readTree stops at the end of the *first* JSON value and, by default, ignores whatever
        // follows — so each of these would answer "is a document" and be handed to CAST(... AS JSON). The
        // gate has to judge the whole literal, or a malformed one reaches ClickHouse on both surfaces.
        assertThat(DocumentComparisons.isJsonDocument(literal)).isFalse();
    }

    @Test
    void nullLiteralIsRejectedAsDocument() {
        assertThat(DocumentComparisons.isJsonDocument(null)).isFalse();
    }

    @Test
    void rejectionWordingNamesFieldTypeAndUse() {
        assertThat(DocumentComparisons.rejectionMessage(
                "request_tags", QueryFieldType.OBJECT, "comparison 'gt' over", "is not supported"))
                .isEqualTo("object field 'request_tags' can only be projected;"
                        + " comparison 'gt' over an object field is not supported");
    }

    @Test
    void rejectionWordingDerivesTheArticleFromTheTypeName() {
        // "an array"/"an object" but "a map" — the article is derived, so a type added later reads right.
        assertThat(DocumentComparisons.rejectionMessage("m", QueryFieldType.MAP, "use", "tail"))
                .contains("use a map field tail");
        assertThat(DocumentComparisons.rejectionMessage("a", QueryFieldType.ARRAY, "use", "tail"))
                .contains("use an array field tail");
    }

    @Test
    void jsonLiteralWordingNamesFieldOperatorAndLiteral() {
        assertThat(DocumentComparisons.jsonLiteralMessage("jwt_claims", ComparisonOp.EQ, "x"))
                .isEqualTo("comparison 'eq' on object field 'jwt_claims' requires a JSON document"
                        + " as its right operand; 'x' is not one");
        assertThat(DocumentComparisons.jsonLiteralMessage("jwt_claims", ComparisonOp.EQ, null))
                .contains("a null literal is not one");
    }
}
