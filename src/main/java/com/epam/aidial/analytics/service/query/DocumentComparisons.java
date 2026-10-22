package com.epam.aidial.analytics.service.query;

import com.epam.aidial.analytics.service.query.model.ComparisonOp;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.util.Set;

/**
 * The comparison operators an {@code object} field admits, and the rule that {@code array} and
 * {@code map} fields admit none. The single expression of the <b>operator</b> carve-out in the
 * projection-only classification, in the same spirit as {@link ArrayValuedFunctions#acceptsArrayOperand}
 * is the single expression of the <b>positional</b> one: both surfaces consult this class rather than
 * holding a copy of the set, so a query legal on one cannot be refused on the other.
 *
 * <p>An {@code object} column is a ClickHouse native {@code JSON} document, and {@code toJSONString} over
 * one is a <b>canonical</b> serialization — keys ordered lexicographically at every level, insignificant
 * whitespace removed, numbers normalized, the string/number distinction preserved. That canonical text is
 * what makes a comparison expressible at all, and it is what the operators below are defined over:
 *
 * <ul>
 *   <li>{@code co}/{@code nc}/{@code ico}/{@code inc} match a substring of the canonical text, exactly as
 *       they do for a character field;
 *   <li>{@code eq}/{@code ne}/{@code in} compare <em>documents</em>: the right-hand literal is canonicalized
 *       by the same function before comparison, so the match is independent of the key order, whitespace and
 *       number format the caller writes. {@link #requiresJsonLiteral} marks these three.
 * </ul>
 *
 * <p><b>Two comparisons are deliberately absent, and neither is an oversight.</b> The ordering operators
 * ({@code lt}/{@code gt}/{@code le}/{@code ge}) would render — lexicographic order over canonical text is
 * valid ClickHouse and round-trips through the SQL subset — but lexicographic order over a serialization is
 * not an order over documents, so the query would return a confident, wrong-shaped answer rather than an
 * error. And {@code eq}/{@code ne} against a <em>null literal</em> is not a document comparison at all: the
 * DSL spells {@code IS NULL} that way, and a ClickHouse {@code JSON} column is never null, so it is a
 * constant false/true for every row. Both stay rejected on the same reasoning the codebase applies to a
 * computed array used as a group key — a plausible-looking result answering a question nobody asked is
 * worse than a rejection. The null case is filtered by operand shape at the call site, not here, because
 * this class classifies the operator and the field's type, and a null literal is neither.
 *
 * <p>{@code array} and {@code map} admit no operator. Calcite types an {@code array} column as a true
 * {@code ARRAY}, so {@code LIKE} does not even type-check on the SQL surface, and neither type has a
 * canonical text rendering the SQL subset could name — so no comparison over either would round-trip.
 * They are excluded by {@link #comparableType} answering only for {@code OBJECT} rather than by a case of
 * their own, which is the intended reading: a projection-only type added later is excluded here by
 * default, the safe direction for a carve-out.
 */
public final class DocumentComparisons {

    /**
     * The operators legal against an {@code object} field. The contains family reads the canonical text as
     * text; the equality family reads it as a document.
     */
    private static final Set<ComparisonOp> DOCUMENT_OPERATORS = Set.of(
            ComparisonOp.CO, ComparisonOp.NC, ComparisonOp.ICO, ComparisonOp.INC,
            ComparisonOp.EQ, ComparisonOp.NE, ComparisonOp.IN);

    /**
     * The subset of {@link #DOCUMENT_OPERATORS} comparing documents rather than text, and therefore
     * requiring a right-hand literal that is itself a well-formed JSON document.
     */
    private static final Set<ComparisonOp> JSON_LITERAL_OPERATORS =
            Set.of(ComparisonOp.EQ, ComparisonOp.NE, ComparisonOp.IN);

    /**
     * Parses a document comparison's right operand as a gate only. Shared and stateless: reading is
     * thread-safe, so one instance serves both surfaces.
     *
     * <p>{@link DeserializationFeature#FAIL_ON_TRAILING_TOKENS} is not decoration. Jackson's
     * {@code readTree} stops at the end of the <b>first</b> JSON value and ignores whatever follows,
     * so with the default configuration {@code {"a":1} garbage}, {@code {"a":1}{"b":2}} and
     * {@code {"a":1}]} all parse as objects — and the literal, malformed as a whole, would reach the
     * {@code CAST(... AS JSON)} this gate exists to keep it out of. Enabling the feature makes the gate
     * answer for the whole literal, which is what both delta specs require of it.
     */
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private DocumentComparisons() {}

    /** The one field type that carries a canonical text rendering both surfaces can name. */
    private static boolean comparableType(QueryFieldType type) {
        return type == QueryFieldType.OBJECT;
    }

    /**
     * Whether a field of {@code type} may be an operand of {@code op}. False for every projection-only type
     * but {@code object}, and for the operators {@code object} does not admit. A null type or operator is
     * not a match rather than an NPE: callers reach this before their own null handling in some paths.
     */
    public static boolean accepts(QueryFieldType type, ComparisonOp op) {
        return type != null && op != null && comparableType(type) && DOCUMENT_OPERATORS.contains(op);
    }

    /**
     * Whether {@code op} compares documents, so its right-hand literal must parse as a JSON document.
     * Only meaningful for an operand this class {@link #accepts}; the contains family returns false,
     * its literals being ordinary substring patterns that carry no well-formedness requirement.
     */
    public static boolean requiresJsonLiteral(ComparisonOp op) {
        return op != null && JSON_LITERAL_OPERATORS.contains(op);
    }

    /**
     * Whether {@code literal} is a well-formed JSON <b>document</b> — the right operand a document
     * comparison requires. Parsing is a <b>gate</b> only: the tree is discarded, and ClickHouse, not
     * Jackson, decides the literal's canonical form (see {@code FilterTranslator}'s rendering). A
     * non-object JSON value (an array, a bare scalar) is not a document and answers false, matching the
     * ClickHouse {@code JSON} type it would be cast to, and so does an object followed by trailing
     * text — the literal has to be well-formed <em>as a whole</em>, not merely to start with a
     * document.
     *
     * <p>Asked on <b>both</b> surfaces: the DSL surface reaches it through {@code FilterTranslator}, and
     * the SQL front end through {@code RexToExprTranslator}, because {@code POST /v1/queries/translate-sql}
     * translates without a builder pass — so a literal rejected on execution has to be rejected there too,
     * or the endpoint would emit a document the execute endpoint refuses.
     */
    public static boolean isJsonDocument(String literal) {
        if (literal == null) {
            return false;
        }
        try {
            return JSON.readTree(literal).isObject();
        } catch (JacksonException notJson) {
            return false;
        }
    }

    /** The shared wording for a document comparison whose right operand is not a JSON document. */
    public static String jsonLiteralMessage(String field, ComparisonOp op, String literal) {
        return "comparison '" + op.code() + "' on object field '" + field + "' requires a JSON document"
                + " as its right operand; " + (literal == null ? "a null literal" : "'" + literal + "'")
                + " is not one";
    }

    /**
     * The shared wording for a document comparison whose right operand is not a literal at all — a field
     * or a call, which has no text to canonicalize. Held here beside {@link #jsonLiteralMessage} for the
     * same reason: a caller must not be able to tell which surface refused the query from the sentence.
     */
    public static String nonLiteralOperandMessage(String field, ComparisonOp op) {
        return "comparison '" + op.code() + "' on object field '" + field + "' requires a string literal"
                + " holding a JSON document as its right operand";
    }

    /**
     * The shared rejection wording for a projection-only field used where it may not be:
     * {@code <type> field '<field>' can only be projected; <use> a[n] <type> field <tail>}.
     *
     * <p>Held here, and delegated to by {@code SqlProjectionOnlyTypes.message}, because there are now
     * <b>three</b> enforcement points across two packages — the DSL surface's {@code FilterTranslator},
     * the DSL→SQL renderer and the SQL→DSL translator — and a caller must not be able to tell which one
     * refused a query by the shape of the sentence. Each supplies its own {@code use} and {@code tail}
     * ("is not supported" on the DSL surface, "cannot be expressed in SQL" from the renderer); the stem
     * that clients and tests match on is identical.
     */
    public static String rejectionMessage(String field, QueryFieldType type, String use, String tail) {
        final String typeName = type.code();
        return typeName + " field '" + field + "' can only be projected; "
                + use + " " + article(typeName) + " " + typeName + " field " + tail;
    }

    /**
     * The indefinite article for a type name — "an" before a vowel, "a" otherwise. Two of the
     * projection-only type names ({@code array}, {@code object}) take "an" and {@code map} takes "a", so
     * the article is derived rather than fixed.
     */
    private static String article(String typeName) {
        return "aeiou".indexOf(typeName.charAt(0)) >= 0 ? "an" : "a";
    }
}
