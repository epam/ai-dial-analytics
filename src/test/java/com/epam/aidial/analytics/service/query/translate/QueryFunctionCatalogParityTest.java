package com.epam.aidial.analytics.service.query.translate;

import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.service.query.JooqTypes;
import com.epam.aidial.analytics.service.query.QueryFieldBinding;
import com.epam.aidial.analytics.service.query.QueryFunctionCatalog;
import com.epam.aidial.analytics.service.query.model.ArrayExpr;
import com.epam.aidial.analytics.service.query.model.Expr;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.FnExpr;
import com.epam.aidial.analytics.service.query.model.LambdaExpr;
import com.epam.aidial.analytics.service.query.model.LambdaParamExpr;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.ValueType;
import com.epam.aidial.analytics.service.query.model.WindowExpr;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import com.epam.aidial.analytics.web.dto.query.QueryFunctionArgDto;
import com.epam.aidial.analytics.web.dto.query.QueryFunctionDto;
import com.epam.aidial.analytics.web.dto.query.QueryFunctionGroup;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Binds the advertised function catalog ({@code GET /v1/queries/functions}) to what
 * {@link ExprTranslator} actually accepts: every descriptor, instantiated as a minimal valid
 * {@link FnExpr} per its declared arity and argument kinds, must translate; the advertised
 * {@code date_bin} units must equal the translator's accepted unit set.
 */
class QueryFunctionCatalogParityTest {

    /**
     * The two container columns the filler needs. A {@code map} and an {@code array} operand cannot be built
     * from a literal — the DSL has neither a map nor an array literal — so the map and array accessors are
     * exercised against declared columns instead of against no bindings at all. Every other descriptor still
     * translates without touching these, which is what keeps the generic filler doing the work.
     */
    private static final Map<String, QueryFieldBinding> BINDINGS = Map.of(
            "attrs", new QueryFieldBinding("attrs",
                    DSL.field(DSL.name("t", "attrs"), JooqTypes.of(QueryFieldType.MAP)), QueryFieldType.MAP),
            "roles", new QueryFieldBinding("roles",
                    DSL.field(DSL.name("t", "roles"), JooqTypes.of(QueryFieldType.ARRAY)),
                    QueryFieldType.ARRAY, QueryFieldType.STRING));

    private final ExprTranslator expr = new ExprTranslator(new ValueExprToObjectMapper());
    private final QueryFunctionCatalog catalog = new QueryFunctionCatalog();

    @Test
    void everyAdvertisedFunctionIsAcceptedByTheTranslator() {
        for (final QueryFunctionDto function : catalog.listFunctions()) {
            final List<Expr> args = minimalArgs(function);
            final FnExpr call = new FnExpr(function.name(), false, args);
            if (function.group() == QueryFunctionGroup.WINDOW) {
                // A window-only function is accepted exactly as the function of a window expression, and
                // refused as a plain call — both halves are the advertised contract.
                assertThatCode(() -> expr.toField(new WindowExpr(call, null, null, null), BINDINGS))
                        .as("window function '%s' under OVER", function.name())
                        .doesNotThrowAnyException();
                assertThatThrownBy(() -> expr.toField(call, BINDINGS))
                        .as("window function '%s' as a plain call", function.name())
                        .isInstanceOf(QueryValidationException.class);
                continue;
            }
            assertThatCode(() -> expr.toField(call, BINDINGS))
                    .as("catalog function '%s' with args per its descriptor", function.name())
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void optionalArgumentsMayBeOmitted() {
        for (final QueryFunctionDto function : catalog.listFunctions()) {
            final List<Expr> required = function.args().stream()
                    .filter(arg -> !Boolean.TRUE.equals(arg.optional()))
                    .map(arg -> minimalArg(function, arg))
                    .toList();
            if (required.size() == function.args().size()) {
                continue;
            }
            assertThatCode(() -> expr.toField(new FnExpr(function.name(), false, required), BINDINGS))
                    .as("catalog function '%s' without its optional arguments", function.name())
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void distinctIsAcceptedWhereAdvertised() {
        for (final QueryFunctionDto function : catalog.listFunctions()) {
            if (!function.distinctSupported()) {
                continue;
            }
            final List<Expr> args = minimalArgs(function);
            assertThatCode(() -> expr.toField(new FnExpr(function.name(), true, args), BINDINGS))
                    .as("catalog function '%s' with distinct", function.name())
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void advertisedDateBinUnitsEqualTranslatorUnits() {
        assertThat(new HashSet<>(QueryFunctionCatalog.DATE_BIN_ALLOWED_UNITS))
                .isEqualTo(ExprTranslator.DATE_BIN_UNITS.keySet());
    }

    @Test
    void advertisedDateTruncUnitsEqualTranslatorUnits() {
        assertThat(new HashSet<>(QueryFunctionCatalog.DATE_TRUNC_ALLOWED_UNITS))
                .isEqualTo(ExprTranslator.DATE_TRUNC_UNITS.keySet());
    }

    @Test
    void advertisedDateOffsetUnitsEqualTranslatorUnits() {
        assertThat(new HashSet<>(QueryFunctionCatalog.DATE_OFFSET_ALLOWED_UNITS))
                .isEqualTo(ExprTranslator.DATE_OFFSET_UNITS.keySet());
    }

    /**
     * The offset units are narrower than {@code date_trunc}'s, and the omission is exactly
     * {@code week}/{@code quarter}. Asserted rather than left to the descriptor because the asymmetry is a
     * decision, not an oversight: as an offset a week is exactly 7 days and a quarter exactly 3 months, so
     * admitting them would give one operation two spellings and break the DSL → SQL → DSL round trip
     * (Calcite lowers {@code INTERVAL 1 WEEK} back to days).
     */
    @Test
    void dateOffsetUnitsOmitTheTwoThatAreExactMultiplesOfAnother() {
        assertThat(ExprTranslator.DATE_OFFSET_UNITS.keySet())
                .containsExactlyInAnyOrder("second", "minute", "hour", "day", "month", "year")
                .doesNotContain("week", "quarter");
        assertThat(ExprTranslator.DATE_TRUNC_UNITS.keySet())
                .containsAll(ExprTranslator.DATE_OFFSET_UNITS.keySet());
    }

    /**
     * The determinism gate the enrichment and aggregate paths apply is driven by this property, so the
     * catalog is where the fact lives. Pinning the count at one keeps a future descriptor from quietly
     * declaring itself non-deterministic — which would newly refuse pipelines — without someone noticing.
     */
    @Test
    void theClockIsTheOnlyNonDeterministicFunction() {
        assertThat(catalog.listFunctions())
                .filteredOn(function -> !function.deterministic())
                .extracting(QueryFunctionDto::name)
                .containsExactly("now");
        assertThat(QueryFunctionCatalog.isNonDeterministic("now")).isTrue();
        assertThat(QueryFunctionCatalog.isNonDeterministic("NOW")).isTrue();
        assertThat(QueryFunctionCatalog.isNonDeterministic("date_sub")).isFalse();
        // A name that is not in the catalog at all answers false: it is refused as an unsupported function
        // long before determinism is anyone's question, and true would report the wrong reason.
        assertThat(QueryFunctionCatalog.isNonDeterministic("rand")).isFalse();
        assertThat(QueryFunctionCatalog.isNonDeterministic(null)).isFalse();
    }

    @Test
    void dateTruncCoversDateBinsUnitsAndTheCalendarOnesToo() {
        // The overlap is deliberate: a caller should not have to learn which units live behind which
        // function, so date_trunc('day', …) resolves rather than erroring. What separates them is the
        // amount multiplier, which only date_bin carries.
        assertThat(ExprTranslator.DATE_TRUNC_UNITS.keySet())
                .containsAll(ExprTranslator.DATE_BIN_UNITS.keySet())
                .contains("month", "quarter", "year");
    }

    @Test
    void catalogIsAlphabeticallyOrderedWithUniqueNames() {
        final List<QueryFunctionDto> functions = catalog.listFunctions();
        // Case-INSENSITIVELY sorted, which is the order a reader of the list expects: the
        // ClickHouse-named functions are camelCase, and an ASCII comparison would file every one of them
        // ahead of the whole lower-case catalog rather than beside its neighbours.
        assertThat(functions).isSortedAccordingTo(
                Comparator.comparing(QueryFunctionDto::name, String.CASE_INSENSITIVE_ORDER));
        assertThat(functions.stream().map(QueryFunctionDto::name).distinct()).hasSize(functions.size());
        assertThat(functions.stream().map(fn -> fn.name().toLowerCase(Locale.ROOT)).distinct())
                .as("two catalog names differing only in case would collide on every case-insensitive match")
                .hasSize(functions.size());
    }

    @Test
    void scalarFunctionsDoNotAdvertiseDistinct() {
        for (final QueryFunctionDto function : catalog.listFunctions()) {
            if (function.group() == QueryFunctionGroup.SCALAR) {
                assertThat(function.distinctSupported()).as("scalar '%s'", function.name()).isFalse();
            }
        }
    }

    @Test
    void nonCatalogFunctionIsStillRejected() {
        assertThatThrownBy(() -> expr.toField(new FnExpr("md5", false, List.of(intLiteral(1))), BINDINGS))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("unsupported function");
    }

    private List<Expr> minimalArgs(QueryFunctionDto function) {
        return function.args().stream().map(arg -> minimalArg(function, arg)).toList();
    }

    /**
     * Builds the smallest translator-valid expression for a descriptor argument.
     *
     * <p>An {@code expression} argument is an integer literal by default — the descriptor says only that
     * <em>some</em> expression belongs there, and a number satisfies every function that does not constrain
     * the operand's type. {@link #TYPED_EXPRESSION_ARGS} carries the exceptions: {@code if}'s condition is
     * declared as an expression but has to be a <b>boolean</b> one, so the generic filler would build
     * {@code if(7, 7, 7)} and this test would fail on a descriptor that is perfectly correct.
     *
     * <p>Named per (function, argument) rather than relaxed globally on purpose. The generic case is what
     * makes this test worth having: every future descriptor is exercised without anyone remembering to add
     * it here, and only an argument whose type the catalog cannot express needs an entry.
     */
    private Expr minimalArg(QueryFunctionDto function, QueryFunctionArgDto arg) {
        final Expr typed = TYPED_EXPRESSION_ARGS.get(function.name() + "." + arg.name());
        if (typed != null) {
            return typed;
        }
        return switch (arg.kind()) {
            case INTEGER_LITERAL -> intLiteral(arg.constraints() != null && arg.constraints().min() != null
                    ? arg.constraints().min().intValueExact()
                    : 1);
            case NUMERIC_LITERAL -> new ValueExpr(ValueType.DECIMAL, "0.5");
            case STRING_LITERAL -> new ValueExpr(ValueType.STRING,
                    arg.constraints() != null && arg.constraints().allowedValues() != null
                            ? arg.constraints().allowedValues().getFirst()
                            : "x");
            case EXPRESSION -> intLiteral(7);
            // A lambda's body has to satisfy the call that applies it: boolean for four of the five, and
            // anything for arrayMap. not_empty over the parameter is boolean and is valid in both.
            case LAMBDA -> new LambdaExpr(List.of("x"), new FnExpr(
                    "not_empty", false, List.of(new LambdaParamExpr("x"))));
        };
    }

    /**
     * The {@code expression} arguments whose operand type the descriptor cannot express, keyed
     * {@code <function>.<argument>}. Two kinds of entry live here, and both exist because
     * {@code QueryFunctionArgKind} has only "expression" to say — it can name neither a <em>boolean</em>
     * expression nor a <em>text</em> one:
     *
     * <ul>
     *   <li>{@code if}'s condition, which the translator requires to be boolean;</li>
     *   <li>the text operands of the string functions — {@code not_empty}, {@code starts_with},
     *       {@code contains}, {@code split_string}, {@code lower}, {@code upper}, {@code trim} and
     *       {@code length} — which the translator requires to be text so that the DSL surface agrees
     *       with the {@code CHARACTER} operand families {@code SqlQueryOperators} declares for the
     *       same functions;</li>
     *   <li>the {@code map} and {@code array} operands of the container accessors, which no literal can
     *       supply — they point at {@link #BINDINGS}' two declared columns;</li>
     *   <li>the value <em>lists</em> of {@code array_has_any}/{@code array_has_all}, which are the DSL's
     *       {@link ArrayExpr} shape rather than a plain expression.</li>
     * </ul>
     *
     * <p>{@code equals} needs no entry: it takes any type and only requires its two operands to unify, which
     * the generic integer filler satisfies on both sides.
     */
    private static final Map<String, Expr> TYPED_EXPRESSION_ARGS = Map.ofEntries(
            Map.entry("if.condition", new FnExpr("not_empty", false, List.of(text()))),
            Map.entry("not_empty.text", text()),
            Map.entry("starts_with.text", text()),
            Map.entry("starts_with.prefix", text()),
            Map.entry("contains.text", text()),
            Map.entry("contains.substring", text()),
            Map.entry("split_string.text", text()),
            Map.entry("lower.text", text()),
            Map.entry("upper.text", text()),
            Map.entry("trim.text", text()),
            Map.entry("length.text", text()),
            Map.entry("map_value.map", mapField()),
            Map.entry("map_contains.map", mapField()),
            Map.entry("map_keys.map", mapField()),
            Map.entry("array_has.array", arrayField()),
            Map.entry("array_has_any.array", arrayField()),
            Map.entry("array_has_all.array", arrayField()),
            Map.entry("array_length.array", arrayField()),
            Map.entry("array_join.array", arrayField()),
            Map.entry("array_to_string.array", arrayField()),
            Map.entry("array_slice.array", arrayField()),
            Map.entry("array_has_any.values", new ArrayExpr(List.of(text()))),
            Map.entry("array_has_all.values", new ArrayExpr(List.of(text()))),
            // The higher-order family and the two flattening aggregates read an array, which no literal
            // can supply; their lambda argument is built generically by its kind.
            Map.entry("arrayExists.array", arrayField()),
            Map.entry("arrayAll.array", arrayField()),
            Map.entry("arrayCount.array", arrayField()),
            Map.entry("arrayFilter.array", arrayField()),
            Map.entry("arrayMap.array", arrayField()),
            Map.entry("groupUniqArrayArray.array", arrayField()),
            Map.entry("groupArrayArray.array", arrayField()),
            Map.entry("match.haystack", text()),
            Map.entry("match.pattern", text()));

    private static Expr mapField() {
        return new FieldExpr("attrs");
    }

    private static Expr arrayField() {
        return new FieldExpr("roles");
    }

    private static Expr text() {
        return new ValueExpr(ValueType.STRING, "x");
    }

    private Expr intLiteral(int value) {
        return new ValueExpr(ValueType.INTEGER, Integer.toString(value));
    }
}
