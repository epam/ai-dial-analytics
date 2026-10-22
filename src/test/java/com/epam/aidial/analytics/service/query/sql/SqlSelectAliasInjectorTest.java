package com.epam.aidial.analytics.service.query.sql;

import com.epam.aidial.analytics.service.query.QueryFunctionCatalog;
import com.epam.aidial.analytics.service.query.model.FnExpr;
import com.epam.aidial.analytics.service.query.model.OutputColumnNaming;
import com.epam.aidial.analytics.web.dto.query.QueryFunctionArgDto;
import com.epam.aidial.analytics.web.dto.query.QueryFunctionDto;
import com.epam.aidial.analytics.web.dto.query.QueryFunctionGroup;
import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.parser.babel.SqlBabelParserImpl;
import org.apache.calcite.sql.validate.SqlConformanceEnum;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The names {@link SqlSelectAliasInjector} gives unnamed select items, and their parity with the DSL's
 * own derivation in {@link OutputColumnNaming}. Both implement the same PostgreSQL rule over different
 * trees (parse tree vs. {@code Expr}), so every function in the closed catalog is driven through both
 * and must come out identically named — otherwise the SQL and DSL surfaces would name the same query
 * differently.
 */
class SqlSelectAliasInjectorTest {

    /**
     * The catalog functions with no function spelling in the SQL subset: arithmetic is written with the
     * infix operators there, and {@code SqlQueryOperators} deliberately registers none of these names.
     *
     * <p>The exemption is safe because the invariant this test guards is about the <em>round-trip</em> — a
     * name the DSL→SQL renderer emits must parse back — and the renderer emits operators for all five, so
     * none of these names ever appears in a statement the SQL front end has to read. It is
     * <em>necessary</em> because {@code MINUS} is a reserved set operator in the Babel grammar (the
     * {@code EXCEPT} alias): {@code minus(a, b)} cannot parse as a function call however it is declared.
     * Shipping the four parseable ones would be a worse surface than one rule.
     */
    private static final Set<String> SQL_SPELLS_AS_AN_OPERATOR =
            Set.of("plus", "minus", "multiply", "div", "modulo");

    private static final SqlParser.Config PARSER_CONFIG = SqlParser.config()
            .withParserFactory(SqlBabelParserImpl.FACTORY)
            .withConformance(SqlConformanceEnum.BABEL)
            .withCaseSensitive(false)
            .withUnquotedCasing(Casing.UNCHANGED)
            .withQuotedCasing(Casing.UNCHANGED);

    private final QueryFunctionCatalog catalog = new QueryFunctionCatalog();

    @Test
    void everyCatalogFunctionIsNamedIdenticallyOnBothSurfaces() {
        for (final QueryFunctionDto function : catalog.listFunctions()) {
            if (SQL_SPELLS_AS_AN_OPERATOR.contains(function.name())) {
                continue;
            }
            final String dslName = OutputColumnNaming.baseName(new FnExpr(function.name(), false, List.of()));
            assertThat(injectedName(call(function)))
                    .as("catalog function '%s'", function.name())
                    .isEqualTo(dslName)
                    // Case-insensitively against the catalog spelling, not equal to it: a derived name is
                    // the PostgreSQL-shaped one and is always lowercase, so a camelCase catalog name (the
                    // ClickHouse-named functions) derives its own lowercase form. What must hold is that
                    // both surfaces derive the SAME name, which the assertion above is.
                    .isEqualToIgnoringCase(function.name());
        }
    }

    @Test
    void countStarIsNamedCount() {
        assertThat(injectedName("count(*)")).isEqualTo("count");
    }

    @Test
    void uppercaseCallIsNamedLowercase() {
        assertThat(injectedName("COUNT(*)")).isEqualTo("count");
    }

    @Test
    void withinGroupIsNamedAfterTheAggregate() {
        assertThat(injectedName("percentile_cont(0.5) WITHIN GROUP (ORDER BY total_price)"))
                .isEqualTo("percentile_cont");
    }

    @Test
    void sqlSynonymKeepsTheSpellingWritten() {
        assertThat(injectedName("char_length(deployment)")).isEqualTo("char_length");
    }

    @Test
    void literalIsNamedWithThePostgresPlaceholder() {
        assertThat(injectedName("1")).isEqualTo(OutputColumnNaming.UNNAMED);
    }

    @Test
    void operatorExpressionIsNamedWithThePostgresPlaceholder() {
        // PostgreSQL names an operator expression `?column?`, not after the operator. (Arithmetic is
        // rejected further down the translation; the name is what this class is responsible for.)
        assertThat(injectedName("total_tokens + 1")).isEqualTo(OutputColumnNaming.UNNAMED);
    }

    @Test
    void namedItemsAreLeftUntouched() {
        final SqlSelect select = parse("SELECT deployment, count(*) AS n, * FROM dial_usage_log");
        SqlSelectAliasInjector.injectAliases(select);
        assertThat(select.getSelectList().get(0)).isInstanceOf(SqlIdentifier.class);
        assertThat(select.getSelectList().get(1).getKind()).isEqualTo(SqlKind.AS);
        assertThat(aliasOf(select.getSelectList().get(1))).isEqualTo("n");
        assertThat(select.getSelectList().get(2)).isInstanceOf(SqlIdentifier.class);
    }

    @Test
    void collidingNamesAreSuffixedAndAliasesAreReservedFirst() {
        final SqlSelect select = parse(
                "SELECT count(*), count(deployment), sum(total_tokens) AS \"count\" FROM dial_usage_log");
        SqlSelectAliasInjector.injectAliases(select);
        assertThat(aliasOf(select.getSelectList().get(0))).isEqualTo("count_1");
        assertThat(aliasOf(select.getSelectList().get(1))).isEqualTo("count_2");
    }

    @Test
    void derivedNameYieldsToAnIdenticallyNamedColumnReference() {
        // A projected column named `count` occupies that name, so the aggregate is suffixed.
        final SqlSelect select = parse("SELECT count, count(*) FROM dial_usage_log");
        SqlSelectAliasInjector.injectAliases(select);
        assertThat(aliasOf(select.getSelectList().get(1))).isEqualTo("count_1");
    }

    @Test
    void starReservesNoNameForItsUnnamedSiblings() {
        // The star is named by the columns it expands to, so it must not consume `?column?` and push the
        // literal to `?column?_1` — no output would carry the reserved name.
        final SqlSelect select = parse("SELECT *, 1 FROM dial_usage_log");
        SqlSelectAliasInjector.injectAliases(select);
        assertThat(aliasOf(select.getSelectList().get(1))).isEqualTo(OutputColumnNaming.UNNAMED);
    }

    @Test
    void orderByParseShapeIsRewrittenToo() {
        final SqlNode parsed = parseNode("SELECT count(*) FROM dial_usage_log ORDER BY count(*)");
        SqlSelectAliasInjector.injectAliases(parsed);
        final SqlSelect select = (SqlSelect) ((org.apache.calcite.sql.SqlOrderBy) parsed).query;
        assertThat(aliasOf(select.getSelectList().getFirst())).isEqualTo("count");
    }

    /** The smallest parseable SQL spelling of a catalog descriptor (types are irrelevant to parsing). */
    private static String call(QueryFunctionDto function) {
        final List<String> args = new ArrayList<>();
        for (final QueryFunctionArgDto arg : function.args()) {
            args.add(switch (arg.kind()) {
                case STRING_LITERAL -> "'x'";
                case NUMERIC_LITERAL -> "0.5";
                case INTEGER_LITERAL -> "1";
                case EXPRESSION -> "total_tokens";
                // A lambda's body only has to parse here; what it means is the translator's business.
                case LAMBDA -> "x -> x";
            });
        }
        if (function.group() == QueryFunctionGroup.ORDERED_SET_AGGREGATE) {
            // An ordered-set aggregate is spelled f(fraction) WITHIN GROUP (ORDER BY column) in SQL.
            return function.name() + "(" + args.getFirst() + ") WITHIN GROUP (ORDER BY "
                    + String.join(", ", args.subList(1, args.size())) + ")";
        }
        return function.name() + "(" + String.join(", ", args) + ")";
    }

    /** The alias the injector gives {@code item} when it is the sole, unnamed select item. */
    private static String injectedName(String item) {
        final SqlSelect select = parse("SELECT " + item + " FROM dial_usage_log");
        SqlSelectAliasInjector.injectAliases(select);
        return aliasOf(select.getSelectList().getFirst());
    }

    private static String aliasOf(SqlNode item) {
        assertThat(item.getKind()).isEqualTo(SqlKind.AS);
        return ((SqlIdentifier) ((SqlCall) item).getOperandList().get(1)).getSimple();
    }

    private static SqlSelect parse(String sql) {
        return (SqlSelect) parseNode(sql);
    }

    private static SqlNode parseNode(String sql) {
        try {
            return SqlParser.create(sql, PARSER_CONFIG).parseQuery();
        } catch (SqlParseException e) {
            throw new IllegalStateException("test SQL does not parse: " + sql, e);
        }
    }
}
