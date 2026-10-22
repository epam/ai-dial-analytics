package com.epam.aidial.analytics.service.query.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The PostgreSQL-shaped naming rules for a {@code select} list with optional aliases: base name per
 * expression kind, explicit aliases winning their exact name, and deterministic de-duplication.
 */
class OutputColumnNamingTest {

    private static final FnExpr COUNT = new FnExpr("count", false, List.of());

    @Test
    void fieldTakesItsOwnName() {
        assertThat(resolve(column(new FieldExpr("deployment"), null))).containsExactly("deployment");
    }

    @Test
    void enrichmentFieldKeepsItsQualifiedFlatName() {
        assertThat(resolve(column(new FieldExpr("deployment_ref.display_name"), null)))
                .containsExactly("deployment_ref.display_name");
    }

    @Test
    void functionTakesItsLowercaseName() {
        assertThat(resolve(
                column(COUNT, null),
                column(new FnExpr("DATE_BIN", false, List.of(new FieldExpr("request_time"))), null),
                column(new FnExpr("sum", true, List.of(new FieldExpr("total_price"))), null)))
                .containsExactly("count", "date_bin", "sum");
    }

    @Test
    void literalTakesPostgresPlaceholder() {
        assertThat(resolve(column(new ValueExpr(ValueType.INTEGER, "1"), null)))
                .containsExactly(OutputColumnNaming.UNNAMED);
    }

    @Test
    void arrayExpressionIsNamedArray() {
        assertThat(resolve(column(new ArrayExpr(List.of(new ValueExpr(ValueType.STRING, "a"))), null)))
                .containsExactly("array");
    }

    @Test
    void explicitAliasOverridesTheDefault() {
        assertThat(resolve(column(COUNT, "total"))).containsExactly("total");
    }

    @Test
    void blankAliasIsTreatedAsAbsent() {
        assertThat(resolve(column(COUNT, "   "), column(new FieldExpr("deployment"), "")))
                .containsExactly("count", "deployment");
    }

    @Test
    void collidingDerivedNamesAreSuffixedByPosition() {
        assertThat(resolve(
                column(COUNT, null),
                column(new FnExpr("count", true, List.of(new FieldExpr("deployment"))), null),
                column(new FnExpr("count", false, List.of(new FieldExpr("model"))), null)))
                .containsExactly("count", "count_1", "count_2");
    }

    @Test
    void explicitAliasKeepsItsNameAgainstCollidingDerivedName() {
        // The alias is reserved first even though it comes later in the list, so the caller always gets
        // exactly the name it asked for.
        assertThat(resolve(column(COUNT, null), column(new FieldExpr("deployment"), "count")))
                .containsExactly("count_1", "count");
    }

    @Test
    void repeatedExplicitAliasesAreDisambiguated() {
        assertThat(resolve(column(COUNT, "total"), column(new FieldExpr("total_price"), "total")))
                .containsExactly("total", "total_1");
    }

    @Test
    void derivedNameSkipsAnAlreadySuffixedName() {
        assertThat(resolve(column(COUNT, null), column(COUNT, "count_1"), column(COUNT, null)))
                .containsExactly("count", "count_1", "count_2");
    }

    @Test
    void minimalAliasesDropAnAliasTheDefaultAlreadyReproduces() {
        final List<OutputColumn> select = OutputColumnNaming.withMinimalAliases(
                List.of(COUNT, new FieldExpr("deployment")), List.of("count", "deployment"));
        assertThat(select).extracting(OutputColumn::as).containsExactly(null, null);
        assertThat(OutputColumnNaming.resolve(select)).containsExactly("count", "deployment");
    }

    @Test
    void minimalAliasesKeepAnAliasTheDefaultCannotReproduce() {
        final List<OutputColumn> select = OutputColumnNaming.withMinimalAliases(
                List.of(COUNT, new FieldExpr("deployment")), List.of("total", "deployment"));
        assertThat(select).extracting(OutputColumn::as).containsExactly("total", null);
    }

    @Test
    void minimalAliasesKeepAnAliasThatCollidesWithAnotherEntrysDefaultSuffix() {
        // `count_1` is exactly what entry 1 would derive on its own, so dropping the alias would make
        // entry 0 (freed of `foo`) take `count` and entry 1 keep `count_1` — a different name list.
        final List<Expr> exprs = List.of(COUNT, new FnExpr("count", false, List.of(new FieldExpr("deployment"))));
        final List<OutputColumn> select = OutputColumnNaming.withMinimalAliases(exprs, List.of("foo", "count_1"));
        assertThat(select).extracting(OutputColumn::as).containsExactly("foo", "count_1");
        assertThat(OutputColumnNaming.resolve(select)).containsExactly("foo", "count_1");
    }

    @Test
    void minimalAliasesAlwaysResolveBackToTheGivenNames() {
        final List<Expr> exprs = List.of(COUNT, COUNT, COUNT, new FieldExpr("deployment"));
        for (final List<String> names : List.of(
                List.of("count", "count_1", "count_2", "deployment"),
                List.of("count_2", "count", "count_1", "deployment"),
                List.of("a", "count", "count_1", "count_2"),
                List.of("count_1", "count_2", "count", "d"))) {
            final List<OutputColumn> select = OutputColumnNaming.withMinimalAliases(exprs, names);
            assertThat(OutputColumnNaming.resolve(select)).as("names %s", names).isEqualTo(names);
        }
    }

    @Test
    void minimalAliasesRejectMisalignedInput() {
        assertThatThrownBy(() -> OutputColumnNaming.withMinimalAliases(List.of(COUNT), List.of("a", "b")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyAndNullSelectResolveToNothing() {
        assertThat(OutputColumnNaming.resolve(null)).isEmpty();
        assertThat(OutputColumnNaming.resolve(List.of())).isEmpty();
        assertThat(OutputColumnNaming.withMinimalAliases(List.of(), List.of())).isEmpty();
    }

    @Test
    void assignDerivesEveryNameWhenNoExplicitNamesAreGiven() {
        assertThat(OutputColumnNaming.assign(null, List.of("count", "count", "sum")))
                .containsExactly("count", "count_1", "sum");
    }

    @Test
    void assignLeavesAnEntryWithNoBaseUnnamedAndReservesNothingForIt() {
        // A null base marks an entry that occupies no name (the SQL front end's star item), so the next
        // entry sharing that base keeps it unsuffixed.
        assertThat(OutputColumnNaming.assign(
                java.util.Arrays.asList(null, null), java.util.Arrays.asList(null, "count")))
                .containsExactly(null, "count");
    }

    private static List<String> resolve(OutputColumn... columns) {
        return OutputColumnNaming.resolve(List.of(columns));
    }

    private static OutputColumn column(Expr expr, String as) {
        return new OutputColumn(expr, as);
    }
}
