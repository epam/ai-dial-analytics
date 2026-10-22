package com.epam.aidial.analytics.service.query.sql;

import com.epam.aidial.analytics.exception.SqlTranslationException;
import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlDataTypeSpec;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.util.SqlShuttle;

import java.util.Locale;

/**
 * Rewrites the two <b>text spellings the grammar binds elsewhere</b> onto the DSL's own text functions,
 * <strong>before</strong> the statement is validated: the {@code ||} operator becomes
 * {@code concat(a, b)}, and {@code CAST(<expr> AS <character type>)} becomes a
 * {@link SqlQueryOperators#TO_STRING_CAST} call — the marker that says "the caller wrote a cast to text
 * here", which {@code RexToExprTranslator} then reads as {@code to_string(<expr>)} or, when the operand is
 * already text, as the operand itself.
 *
 * <p><b>Why a rewrite and not an operator-table entry.</b> Neither spelling is resolved through the
 * operator table: both are bound by the parser itself, so chaining a DSL-signature operator ahead of the
 * standard table cannot reach them — the same reason {@link SqlDateTruncRebinder} exists. For {@code ||}
 * what makes the swap necessary rather than merely tidy is that operator's <em>type checker</em>: it is
 * string-only, so {@code 'depth ' || 4} fails validation with a message about the operator's supported
 * forms, before any translator sees the statement. The DSL {@code concat} renders each operand as text
 * itself and therefore accepts the pair — refusing the mixed spelling would be this service narrowing its
 * own function through a spelling it happens to reject.
 *
 * <p><b>Why the cast rewrite has to happen here and not on the {@code Rex} tree.</b> This was implemented
 * once in {@code RexToExprTranslator}, where casts are already classified, and it was wrong: Calcite's
 * implicit type coercion <em>injects</em> a cast to a character type whenever a text-family operand is given
 * a non-text argument, so lowering every {@code Rex} cast-to-character onto {@code to_string} silently
 * widened every text function to accept anything — {@code not_empty(total_tokens)} became
 * {@code notEmpty(toString(total_tokens))} and was accepted, contradicting the shipped rule that a non-text
 * operand is a 400 naming its type. A pre-validation tree contains only what the caller wrote, so rewriting
 * here draws exactly the line that matters: a cast the <em>caller</em> spelled is a rendering request, and a
 * cast the <em>validator</em> invents stays subject to the cast-tolerance rules that reject it.
 *
 * <p><b>Why the rewrite emits a marker rather than {@code to_string} itself.</b> The line above is the only
 * one this tree can draw — what it cannot decide is whether the rendering is <em>needed</em>, because no
 * operand has a type here yet. Emitting {@code to_string} directly therefore wrapped the identity case too,
 * and {@code CAST(<text column> AS VARCHAR)} — the spelling a BI client puts in a label, a {@code WHERE} and
 * a {@code GROUP BY} alike — stopped translating to the bare column it had always been:
 * {@code toString(project_id) = 'p1'} instead of {@code project_id = 'p1'}, and a group key on {@code String}
 * instead of on the {@code LowCardinality(String)} column. {@link SqlQueryOperators#TO_STRING_CAST} carries
 * the request to where the operand's type is known and the identity case can be left alone, while a
 * caller-written {@code TO_STRING(<text column>)} keeps rendering {@code toString} — the DSL→SQL renderer
 * emits that spelling, so unwrapping it would break the round trip.
 *
 * <p><b>Why the marker is refused when a caller spells it.</b> The marker is registered in
 * {@link SqlQueryOperators#TABLE} because the validator resolves the swapped call by name like any other
 * operator — which also makes {@code TO_STRING_CAST(x)} resolvable in a statement a client wrote, and that
 * name is in neither the DSL function catalog nor {@code GET /v1/queries/sql-functions}. The subset's rule is
 * that a function outside the catalog is rejected, so this shuttle rejects the spelling: the check sits at
 * the top of the walk, before {@link #asToStringCast} introduces any marker of its own and before the
 * shuttle descends, so a marker call seen there can only be the caller's. Callers spell the cast
 * ({@code CAST(x AS VARCHAR)}) or the catalog function ({@code TO_STRING(x)}); the marker stays internal.
 *
 * <p><b>Why it runs after {@link SqlSelectAliasInjector}.</b> Both passes read the parsed tree, but the
 * injector reads it to <em>name</em> unnamed select items, and PostgreSQL names neither a cast nor a
 * {@code ||} expression after an operator — both are {@code ?column?}. Running this swap first left the
 * injector looking at a {@code TO_STRING_CAST}/{@code CONCAT} function call and naming the item after the
 * operator introduced here, so an unaliased {@code CAST(project_id AS VARCHAR)} — accepted and named
 * {@code ?column?} long before this class existed — started reporting a result column called
 * {@code to_string_cast}, leaking an internal marker's name into result schemas and DSL documents. The
 * order is therefore load-bearing, not incidental; this shuttle walks straight through the {@code AS} calls
 * the injector leaves behind.
 *
 * <p>A declared length ({@code CAST(x AS VARCHAR(50))}) is dropped rather than applied: the store has no
 * bounded string type, so there is nothing to truncate to, and refusing the spelling would reject what BI
 * clients actually emit for a label.
 *
 * <p>The {@code ||} swap is <b>unconditional</b> for that operator, unlike the date-trunc one's literal
 * guard. There is nothing else it could mean here: standard SQL defines it over character data, ClickHouse
 * over strings, and the array/multiset concatenations Calcite defines elsewhere are different operators. An
 * operand the DSL cannot render — an {@code array} column, a {@code map} — still fails closed, on the
 * per-argument projection-only rule every function's operands go through.
 *
 * <p>A chain is left-associative, so {@code a || b || c} parses as two nested calls and becomes two nested
 * {@code concat} calls. That is the same expression rather than a flattened three-operand one, and it is
 * deliberately not flattened: {@code concat} is variadic and associative, so nesting costs nothing, and
 * flattening would be this class inventing a normal form the round-trip does not need.
 *
 * <p><b>A separate class from {@link SqlDateTruncRebinder}</b> even though both are pre-validation operator
 * swaps over the same tree. That class's javadoc is entirely the argument-order story of one BigQuery-shaped
 * builtin; folding an unrelated rule into it would leave neither readable. The cost is one extra walk over a
 * parsed statement, which is not measurable beside validation.
 */
final class SqlTextRebinder {

    /** Calcite's name for the {@code ||} operator, which is the operator's spelling itself. */
    private static final String CONCAT_OPERATOR = "||";

    private SqlTextRebinder() {}

    /**
     * Rewrites every {@code ||} call and every cast-to-character in {@code statement}, returning the
     * rewritten tree.
     *
     * <p>Walks the whole tree rather than the select list alone, for the reason the date-trunc re-binder
     * does: both spellings are legal anywhere an expression is, and a {@code GROUP BY} occurrence must be
     * rewritten identically to its {@code SELECT} twin or the two stop matching and the group key fails to
     * resolve.
     *
     * <p>Returns the root because a cast rewrite <em>replaces</em> a node rather than mutating it in place
     * the way an operator swap does. A root that is itself a cast is not a statement, so in practice the
     * same object comes back — but the caller uses the return value rather than relying on that.
     */
    static SqlNode rebind(SqlNode statement) {
        if (statement == null) {
            return null;
        }
        return statement.accept(new SqlShuttle() {
            @Override
            public SqlNode visit(SqlCall call) {
                rejectSpelledMarker(call);
                if (call instanceof SqlBasicCall basic
                        && CONCAT_OPERATOR.equals(basic.getOperator().getName())) {
                    basic.setOperator(SqlQueryOperators.CONCAT);
                }
                return asToStringCast(super.visit(call));
            }
        });
    }

    /**
     * Refuses a call the caller wrote against the internal {@link SqlQueryOperators#TO_STRING_CAST} marker,
     * which the operator table would otherwise resolve by name like any catalog function.
     *
     * <p>Case-insensitively, because the front end parses with unchanged casing and validates
     * case-insensitively, so {@code to_string_cast(x)} resolves exactly as the upper-case spelling does.
     * The message is the one an out-of-catalog function already answers with, so the error carries the same
     * {@code unsupported_function} code and the same "use GET /v1/queries/sql-functions" hint — which is what
     * the name is: a function this service does not offer.
     */
    private static void rejectSpelledMarker(SqlCall call) {
        if (SqlQueryOperators.TO_STRING_CAST.getName().equalsIgnoreCase(call.getOperator().getName())) {
            throw new SqlTranslationException("unsupported function '" + call.getOperator().getName()
                    + "' in query expression");
        }
    }

    /**
     * {@code CAST(<expr> AS <character type>)} as a {@link SqlQueryOperators#TO_STRING_CAST} call, or
     * {@code node} unchanged.
     */
    private static SqlNode asToStringCast(SqlNode node) {
        // getOperandList(), not operand(i): that accessor's return type is inferred from its use, and an
        // `instanceof` gives it nothing to infer from, so javac had it checkcast to SqlNodeList and every
        // cast in a statement raised a ClassCastException before the type test ever ran.
        if (node instanceof SqlCall call
                && call.getKind() == SqlKind.CAST
                && call.operandCount() == 2
                && call.getOperandList().get(1) instanceof SqlDataTypeSpec spec
                && isCharacter(spec)) {
            return SqlQueryOperators.TO_STRING_CAST.createCall(SqlParserPos.ZERO, call.getOperandList().getFirst());
        }
        return node;
    }

    /**
     * Whether a written type spec names a character type. Read off the spelling the caller used rather than
     * a derived type, because nothing is validated yet: an unknown or non-basic type name simply is not a
     * match, which leaves the cast exactly as parsed for validation to judge.
     */
    private static boolean isCharacter(SqlDataTypeSpec spec) {
        if (!(spec.getTypeNameSpec().getTypeName() instanceof SqlIdentifier identifier)
                || !identifier.isSimple()) {
            return false;
        }
        final SqlTypeName named = SqlTypeName.get(identifier.getSimple().toUpperCase(Locale.ROOT));
        return named != null && SqlTypeName.CHAR_TYPES.contains(named);
    }
}
