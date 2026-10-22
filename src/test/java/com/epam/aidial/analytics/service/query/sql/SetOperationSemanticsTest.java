package com.epam.aidial.analytics.service.query.sql;

import com.epam.aidial.analytics.exception.SqlTranslationException;
import com.epam.aidial.analytics.service.query.CatalogQueryService;
import com.epam.aidial.analytics.service.query.DialUsageLogTestModel;
import com.epam.aidial.analytics.service.query.JooqTypes;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.QueryFieldBinding;
import com.epam.aidial.analytics.service.query.model.CompositeQuery;
import com.epam.aidial.analytics.service.query.model.OffsetPage;
import com.epam.aidial.analytics.service.query.model.SetOpKind;
import com.epam.aidial.analytics.service.query.model.SetOpRelation;
import com.epam.aidial.analytics.service.query.model.SubqueryNode;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import com.epam.aidial.analytics.web.dto.query.QuerySchemaFieldDto;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The ClickHouse readings of a set operation, recovered from the statement text before validation.
 *
 * <p>Every assertion here is a case where Calcite's parse alone would give the <em>standard SQL</em>
 * answer and the database gives another one. Measured against ClickHouse 26.3 while this was designed:
 * {@code union_default_mode} is empty (a bare {@code UNION} is an error), {@code intersect_default_mode}
 * and {@code except_default_mode} are both {@code ALL} (where the standard reads {@code DISTINCT}), a
 * trailing {@code ORDER BY}/{@code LIMIT} binds to the chain's final SELECT, it is a syntax error when a
 * parenthesis closes before it, and {@code MINUS} is not a keyword at all.
 */
class SetOperationSemanticsTest {

    private final SqlQueryFrontEnd frontEnd = newFrontEnd();

    private SqlCompositeTranslation composite(String sql) {
        return (SqlCompositeTranslation) frontEnd.plan(sql);
    }

    private SetOpRelation setOp(String sql) {
        final CompositeQuery root = composite(sql).query();
        assertThat(root.source()).isInstanceOf(SetOpRelation.class);
        return (SetOpRelation) root.source();
    }

    // ---------------------------------------------------------------- qualifiers

    @Test
    void unionAllKeepsItsQualifier() {
        final SetOpRelation setOp = setOp("SELECT event_kind FROM dial_usage_log"
                + " UNION ALL SELECT event_kind FROM dial_usage_log");
        assertThat(setOp.kind()).isEqualTo(SetOpKind.UNION);
        assertThat(setOp.all()).isTrue();
        assertThat(setOp.qualifier()).isEqualTo("ALL");
    }

    @Test
    void unionDistinctKeepsItsQualifier() {
        final SetOpRelation setOp = setOp("SELECT event_kind FROM dial_usage_log"
                + " UNION DISTINCT SELECT event_kind FROM dial_usage_log");
        assertThat(setOp.kind()).isEqualTo(SetOpKind.UNION);
        assertThat(setOp.all()).isFalse();
        assertThat(setOp.qualifier()).isEqualTo("DISTINCT");
    }

    @Test
    void bareIntersectMeansAllNotDistinct() {
        // The whole point of reading the source text: Calcite parsed this as INTERSECT DISTINCT.
        final SetOpRelation setOp = setOp("SELECT event_kind FROM dial_usage_log"
                + " INTERSECT SELECT event_kind FROM dial_usage_log");
        assertThat(setOp.kind()).isEqualTo(SetOpKind.INTERSECT);
        assertThat(setOp.all()).isTrue();
    }

    @Test
    void bareExceptMeansAllNotDistinct() {
        final SetOpRelation setOp = setOp("SELECT event_kind FROM dial_usage_log"
                + " EXCEPT SELECT event_kind FROM dial_usage_log");
        assertThat(setOp.kind()).isEqualTo(SetOpKind.EXCEPT);
        assertThat(setOp.all()).isTrue();
    }

    @Test
    void explicitDistinctIsHonouredOnIntersectAndExcept() {
        // The keyword the caller wrote always wins over the default the bare spelling would have taken.
        assertThat(setOp("SELECT event_kind FROM dial_usage_log"
                + " INTERSECT DISTINCT SELECT event_kind FROM dial_usage_log").all()).isFalse();
        assertThat(setOp("SELECT event_kind FROM dial_usage_log"
                + " EXCEPT DISTINCT SELECT event_kind FROM dial_usage_log").all()).isFalse();
        assertThat(setOp("SELECT event_kind FROM dial_usage_log"
                + " INTERSECT ALL SELECT event_kind FROM dial_usage_log").all()).isTrue();
        assertThat(setOp("SELECT event_kind FROM dial_usage_log"
                + " EXCEPT ALL SELECT event_kind FROM dial_usage_log").all()).isTrue();
    }

    @Test
    void bareUnionIsRejected() {
        assertThatThrownBy(() -> frontEnd.plan("SELECT event_kind FROM dial_usage_log"
                + " UNION SELECT event_kind FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("UNION requires ALL or DISTINCT")
                .hasMessageContaining("union_default_mode");
    }

    @Test
    void minusIsRejectedNamingExcept() {
        assertThatThrownBy(() -> frontEnd.plan("SELECT event_kind FROM dial_usage_log"
                + " MINUS SELECT event_kind FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("MINUS is not supported")
                .hasMessageContaining("EXCEPT ALL");
    }

    @Test
    void starExceptIsRejectedBeforeExecution() {
        // ClickHouse's column-exclusion syntax is not this operator. It parses here and fails in
        // validation against the star, so the refusal does not name the construct — which is what the
        // spec and the docs say, and this test is what keeps that statement honest.
        assertThatThrownBy(() -> frontEnd.plan("SELECT * EXCEPT (event_kind) FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("Unknown identifier '*'");
    }

    @Test
    void commentBetweenTheOperandsDoesNotChangeTheQualifier() {
        // A comment mentioning ALL must not be read as the keyword, and one after the keyword must not
        // hide it.
        assertThatThrownBy(() -> frontEnd.plan("SELECT event_kind FROM dial_usage_log UNION /* ALL */"
                + " SELECT event_kind FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("UNION requires ALL or DISTINCT");
        assertThat(setOp("SELECT event_kind FROM dial_usage_log UNION ALL /* really */"
                + " SELECT event_kind FROM dial_usage_log").all()).isTrue();
        assertThat(setOp("SELECT event_kind FROM dial_usage_log UNION -- comment\n"
                + " ALL SELECT event_kind FROM dial_usage_log").all()).isTrue();
    }

    @Test
    void theOperatorIsReadAcrossNewlinesAndParenthesizedOperands() {
        assertThat(setOp("""
                (SELECT event_kind FROM dial_usage_log)
                  UNION
                  ALL
                  (SELECT event_kind FROM dial_usage_log)""").all()).isTrue();
        assertThatThrownBy(() -> frontEnd.plan("""
                (SELECT event_kind FROM dial_usage_log)
                  UNION
                  (SELECT event_kind FROM dial_usage_log)"""))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("UNION requires ALL or DISTINCT");
    }

    @Test
    void quotedIdentifierNamedUnionIsNotAnOperator() {
        // "union" as a column alias must not be read as an operator by the text pass.
        final SqlTranslation single = frontEnd.translate(
                "SELECT event_kind AS \"union\" FROM dial_usage_log");
        assertThat(single.columns().getFirst().name()).isEqualTo("union");
    }

    @Test
    void chainedOperatorsResolveIndependently() {
        // Each operator's qualifier is read from its own region: the INTERSECT binds tighter, so the plan
        // is UNION ALL over (a, INTERSECT ALL over (b, c)) — and the bare INTERSECT is still ALL.
        final SetOpRelation union = setOp("SELECT event_kind FROM dial_usage_log"
                + " UNION ALL SELECT event_kind FROM dial_usage_log"
                + " INTERSECT SELECT event_kind FROM dial_usage_log");
        assertThat(union.kind()).isEqualTo(SetOpKind.UNION);
        assertThat(union.all()).isTrue();
        assertThat(union.branches()).hasSize(2);
        final CompositeQuery nested = union.branches().get(1);
        assertThat(nested.source()).isInstanceOf(SetOpRelation.class);
        final SetOpRelation intersect = (SetOpRelation) nested.source();
        assertThat(intersect.kind()).isEqualTo(SetOpKind.INTERSECT);
        assertThat(intersect.all()).isTrue();
    }

    // ------------------------------------------------- trailing ORDER BY / LIMIT

    @Test
    void trailingOrderByAndLimitBindToTheLastBranch() {
        // ClickHouse's reading, not the standard's: the clause belongs to the final SELECT. Measured —
        // `SELECT 5 UNION ALL <3 rows> ORDER BY x LIMIT 2` returns 5,1,2 there.
        final SetOpRelation setOp = setOp("SELECT event_kind FROM dial_usage_log"
                + " UNION ALL SELECT event_kind FROM dial_usage_log ORDER BY event_kind LIMIT 7");
        final CompositeQuery first = setOp.branches().getFirst();
        final CompositeQuery last = setOp.branches().get(1);
        assertThat(first.block().sort()).isNull();
        assertThat(first.block().page()).isNull();
        assertThat(last.block().sort()).hasSize(1);
        assertThat(last.block().page()).isEqualTo(new OffsetPage(0, 7, false));
    }

    @Test
    void trailingOrderByWithNoRowBoundStillSortsTheLastBranch() {
        // The clause with no LIMIT/OFFSET beside it, which is the case Calcite's own converter would throw
        // away as an unobservable sub-query sort (removeSortInSubQuery, disabled in SqlQueryFrontEnd for
        // exactly this reason): re-associating the clause makes the last branch a set-operation input, so
        // the default would have dropped the collation and returned the combined result unordered — with
        // the surface's default row limit then choosing which rows, not just their order. Measured on
        // ClickHouse 26.3.24: `SELECT 5 AS x UNION ALL SELECT arrayJoin([3,1,2]) AS x ORDER BY x` returns
        // 5, 1, 2, 3 — the last branch sorted, the first untouched.
        final SetOpRelation setOp = setOp("SELECT event_kind FROM dial_usage_log"
                + " UNION ALL SELECT event_kind FROM dial_usage_log ORDER BY event_kind");
        assertThat(setOp.branches().getFirst().block().sort()).isNull();
        assertThat(setOp.branches().getLast().block().sort()).hasSize(1);
        assertThat(setOp.branches().getLast().block().page()).isNull();
    }

    @Test
    void branchKeepsItsOwnParenthesizedOrderByWithNoRowBound() {
        // The same drop from the other direction: a branch the caller parenthesized around an ORDER BY of
        // its own, with no LIMIT to keep the collation alive. ClickHouse executes it and sorts that branch.
        final SetOpRelation setOp = setOp("(SELECT event_kind FROM dial_usage_log ORDER BY event_kind)"
                + " UNION ALL SELECT event_kind FROM dial_usage_log");
        assertThat(setOp.branches().getFirst().block().sort()).hasSize(1);
        assertThat(setOp.branches().getLast().block().sort()).isNull();
    }

    @Test
    void trailingClauseBindsToTheFinalSelectOfMixedPrecedenceChain() {
        // Measured on ClickHouse 26.3.24: `SELECT 9 AS x UNION ALL SELECT arrayJoin([2,3]) AS x INTERSECT
        // ALL SELECT arrayJoin([1,2,3]) AS x ORDER BY x LIMIT 1` returns the single row 9 — the clause
        // bound to C alone, C being the chain's final SELECT. Calcite's last *operand* is the INTERSECT
        // node, so taking it would have put the clause above B INTERSECT ALL C and returned 9 and 2.
        // Both operators that INTERSECT binds tighter than, so both chains nest to the right.
        for (final Map.Entry<String, SetOpKind> outer : Map.of(
                "UNION ALL", SetOpKind.UNION, "EXCEPT ALL", SetOpKind.EXCEPT).entrySet()) {
            final SetOpRelation union = setOp("SELECT event_kind FROM dial_usage_log"
                    + " " + outer.getKey() + " SELECT event_kind FROM dial_usage_log"
                    + " INTERSECT ALL SELECT event_kind FROM dial_usage_log"
                    + " ORDER BY event_kind LIMIT 4");
            assertThat(union.kind()).isEqualTo(outer.getValue());
            // Nothing above the final SELECT is ordered or truncated: not the first branch, and not the
            // nested operation the clause would have landed on.
            assertThat(union.branches().getFirst().block().sort()).isNull();
            assertThat(union.branches().getFirst().block().page()).isNull();
            final CompositeQuery nestedBlock = union.branches().get(1);
            assertThat(nestedBlock.block().sort()).isNull();
            assertThat(nestedBlock.block().page()).isNull();
            final SetOpRelation nested = (SetOpRelation) nestedBlock.source();
            assertThat(nested.branches().getFirst().block().page()).isNull();
            final CompositeQuery finalSelect = nested.branches().get(1);
            assertThat(finalSelect.block().sort()).hasSize(1);
            assertThat(finalSelect.block().page()).isEqualTo(new OffsetPage(0, 4, false));
        }
    }

    @Test
    void trailingClauseSurvivesWithClause() {
        // Calcite parses `WITH … <set operation> ORDER BY x` as SqlOrderBy(SqlWith(<set operation>)), so
        // the clause trails the WITH rather than the operation — and ClickHouse still reads it as the
        // final SELECT's: `WITH c AS (SELECT arrayJoin([1,2,3]) AS x) SELECT 9 AS x UNION ALL SELECT x
        // FROM c ORDER BY x LIMIT 1` returns 9 and 1 on 26.3.24, not the single row the combined reading
        // would give.
        final SqlCompositeTranslation plan = composite("WITH c AS (SELECT event_kind FROM dial_usage_log)"
                + " SELECT event_kind FROM c UNION ALL SELECT event_kind FROM c"
                + " ORDER BY event_kind LIMIT 3");
        assertThat(plan.query().block().sort()).isNull();
        assertThat(plan.explicitLimit()).isFalse();
        final SetOpRelation setOp = (SetOpRelation) plan.query().source();
        assertThat(setOp.branches().getFirst().block().sort()).isNull();
        assertThat(setOp.branches().getFirst().block().page()).isNull();
        assertThat(setOp.branches().get(1).block().sort()).hasSize(1);
        assertThat(setOp.branches().get(1).block().page()).isEqualTo(new OffsetPage(0, 3, false));
    }

    @Test
    void parenthesizedBranchBeforeTheLastOneKeepsTheClauseOnTheLastBranch() {
        // The parenthesis belongs to a branch that is not the last one, so the clause still follows a bare
        // final SELECT and ClickHouse executes the statement: `(SELECT 1 AS x) UNION ALL SELECT
        // arrayJoin([2,3]) AS x ORDER BY x LIMIT 1` returns 1 and 2 on 26.3.24. Refusing it would refuse a
        // shape BI tools and hand-written ClickHouse both produce.
        final SetOpRelation setOp = setOp("(SELECT event_kind FROM dial_usage_log ORDER BY event_kind"
                + " LIMIT 2) UNION ALL SELECT event_kind FROM dial_usage_log ORDER BY event_kind LIMIT 3");
        assertThat(setOp.branches().getFirst().block().page()).isEqualTo(new OffsetPage(0, 2, false));
        assertThat(setOp.branches().get(1).block().page()).isEqualTo(new OffsetPage(0, 3, false));
        // And with a parenthesized middle branch, which ClickHouse also executes (measured: rows 1 and 2
        // for `SELECT 1 AS x UNION ALL (SELECT 2 AS x) INTERSECT ALL SELECT arrayJoin([2,3]) AS x ORDER BY
        // x LIMIT 1`).
        final SetOpRelation chain = setOp("SELECT event_kind FROM dial_usage_log"
                + " UNION ALL (SELECT event_kind FROM dial_usage_log)"
                + " INTERSECT ALL SELECT event_kind FROM dial_usage_log ORDER BY event_kind LIMIT 3");
        final SetOpRelation nested = (SetOpRelation) chain.branches().get(1).source();
        assertThat(nested.branches().get(1).block().page()).isEqualTo(new OffsetPage(0, 3, false));
    }

    @Test
    void nestedBlockWhoseFirstBranchIsParenthesizedCannotCarryTrailingClause() {
        // The one qualification on the test above, and the only shape where a parenthesized non-last
        // branch is not accepted: inside a parenthesized block, the block cannot both open with a `(` and
        // carry a trailing clause. Calcite's grammar picks between two readings of the text after a `(` on
        // lookahead, and only the reading that cannot start at a `(` accepts a trailing clause — so the
        // statement dies in the generated parser, before any of the rules above run. Not a set-operation
        // rule and not ADAS's reading: ClickHouse executes all three of these.
        final String derivedTable = "SELECT event_kind FROM ((SELECT event_kind FROM dial_usage_log"
                + " ORDER BY event_kind LIMIT 10) UNION ALL SELECT event_kind FROM dial_usage_log"
                + " ORDER BY event_kind LIMIT 10) ORDER BY event_kind";
        final String cteBody = "WITH c AS ((SELECT event_kind FROM dial_usage_log LIMIT 5) UNION ALL"
                + " SELECT event_kind FROM dial_usage_log LIMIT 2) SELECT event_kind FROM c";
        for (final String sql : List.of(derivedTable, cteBody)) {
            assertThatThrownBy(() -> frontEnd.plan(sql))
                    .as("%s", sql)
                    .isInstanceOf(SqlTranslationException.class)
                    // The parse failure is reported against the clause, which is the one correct part of
                    // the text; the sentence has to name the block instead and give the spelling that works.
                    .hasMessageContaining("SQL parse error")
                    .hasMessageContaining("opens a block that starts with another '('")
                    .hasMessageContaining("parenthesise the block's last query too");
        }
    }

    @Test
    void subqueryOperandTakesTheShapeTheOtherPositionsRefuse() {
        // The gap reaches only the two positions whose parentheses the grammar reads as "expression, join
        // or ordered query" — a FROM item and a CTE body. An IN/EXISTS/scalar operand is parsed by the
        // comma-list production instead, which does accept a trailing clause, so the same text works there
        // and the ClickHouse reading is applied to it as usual: the LIMIT bounds the last branch.
        final SqlCompositeTranslation plan = composite("SELECT event_id FROM dial_usage_log WHERE event_kind"
                + " IN ((SELECT event_kind FROM dial_usage_log) UNION ALL SELECT event_kind"
                + " FROM dial_usage_log LIMIT 2)");
        final SubqueryNode predicate = (SubqueryNode) plan.query().block().filter();
        assertThat(predicate.kind()).isEqualTo(SubqueryNode.SubqueryKind.IN);
        assertThat(predicate.subquery().source()).isInstanceOf(SetOpRelation.class);
        final SetOpRelation setOp = (SetOpRelation) predicate.subquery().source();
        assertThat(setOp.branches().getFirst().block().page()).isNull();
        assertThat(setOp.branches().get(1).block().page()).isEqualTo(new OffsetPage(0, 2, false));
    }

    @Test
    void parenthesizingEveryBranchIsTheSpellingThatWorksNested() {
        // The remedy the message names, and it means the same thing: the clause is inside the last branch
        // either way, which is where re-association would have put it.
        final SqlCompositeTranslation plan = composite("SELECT count(*) AS n FROM ((SELECT event_kind"
                + " FROM dial_usage_log) UNION ALL (SELECT event_kind FROM dial_usage_log LIMIT 2))");
        assertThat(plan.query().source()).isInstanceOf(SetOpRelation.class);
        final SetOpRelation setOp = (SetOpRelation) plan.query().source();
        assertThat(setOp.branches().getFirst().block().page()).isNull();
        assertThat(setOp.branches().get(1).block().page()).isEqualTo(new OffsetPage(0, 2, false));

        // Leaving the first branch bare is the other spelling, and the clause re-associates as usual.
        final SetOpRelation bare = (SetOpRelation) composite("SELECT count(*) AS n FROM (SELECT event_kind"
                + " FROM dial_usage_log UNION ALL SELECT event_kind FROM dial_usage_log LIMIT 2)")
                .query().source();
        assertThat(bare.branches().get(1).block().page()).isEqualTo(new OffsetPage(0, 2, false));
    }

    @Test
    void trailingClauseAfterParenthesizedChainIsRejected() {
        // ClickHouse answers a syntax error whenever a parenthesis closes before the clause, whatever it
        // encloses: the whole mixed-precedence chain, or the nested operation that ends it.
        assertThatThrownBy(() -> frontEnd.plan("(SELECT event_kind FROM dial_usage_log"
                + " UNION ALL SELECT event_kind FROM dial_usage_log"
                + " INTERSECT ALL SELECT event_kind FROM dial_usage_log) ORDER BY event_kind LIMIT 3"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("belongs to its last branch");
        assertThatThrownBy(() -> frontEnd.plan("SELECT event_kind FROM dial_usage_log UNION ALL"
                + " (SELECT event_kind FROM dial_usage_log"
                + " INTERSECT ALL SELECT event_kind FROM dial_usage_log) ORDER BY event_kind"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("belongs to its last branch");
    }

    @Test
    void trailingLimitIsNotTheStatementsOwnBound() {
        // Because the limit went into a branch, the statement states no outer bound — so the surface's
        // default applies to the combined result, exactly as for a statement with no LIMIT at all. A
        // trailing LIMIT above the maximum is therefore not the over-maximum rejection either.
        final SqlCompositeTranslation plan = composite("SELECT event_kind FROM dial_usage_log"
                + " UNION ALL SELECT event_kind FROM dial_usage_log LIMIT 5000");
        assertThat(plan.explicitLimit()).isFalse();
    }

    @Test
    void trailingClauseAfterParenthesizedBranchIsRejected() {
        // ClickHouse answers a syntax error here, because the clause is part of an unparenthesized final
        // SELECT. Accepting it would invent a meaning for text that has none.
        assertThatThrownBy(() -> frontEnd.plan("SELECT event_kind FROM dial_usage_log"
                + " UNION ALL (SELECT event_kind FROM dial_usage_log) ORDER BY event_kind"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("belongs to its last branch")
                .hasMessageContaining("SELECT ... FROM (<set operation>) ORDER BY");
        assertThatThrownBy(() -> frontEnd.plan("SELECT event_kind FROM dial_usage_log"
                + " UNION ALL (SELECT event_kind FROM dial_usage_log) LIMIT 3"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("belongs to its last branch");
        // The same holds when the parenthesis closes the whole operation rather than the last branch;
        // ClickHouse refuses that spelling too.
        assertThatThrownBy(() -> frontEnd.plan("(SELECT event_kind FROM dial_usage_log"
                + " UNION ALL SELECT event_kind FROM dial_usage_log) ORDER BY event_kind"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("belongs to its last branch");
    }

    @Test
    void branchEndingInFunctionCallIsNotMistakenForParens() {
        // The last branch's own span ends at the ')' of length(...), which a naive reverse scan would read
        // as a parenthesized branch and refuse a statement the database accepts.
        final SqlCompositeTranslation plan = composite("SELECT event_kind FROM dial_usage_log UNION ALL"
                + " SELECT event_kind FROM dial_usage_log WHERE length(event_kind) > 2 ORDER BY event_kind");
        assertThat(plan.query().source()).isInstanceOf(SetOpRelation.class);
        // The clause went into the branch, so the block above the operation carries no ordering of its own.
        assertThat(plan.query().block().sort()).isNull();
        // And it really went there rather than nowhere, which is what a dropped collation would look like
        // from the block above.
        assertThat(((SetOpRelation) plan.query().source()).branches().getLast().block().sort()).hasSize(1);
    }

    @Test
    void wrappingTheSetOperationOrdersTheCombinedResult() {
        // The spelling that means the same thing to ADAS and to ClickHouse.
        final SqlCompositeTranslation plan = composite("SELECT event_kind FROM (SELECT event_kind"
                + " FROM dial_usage_log UNION ALL SELECT event_kind FROM dial_usage_log)"
                + " ORDER BY event_kind LIMIT 7");
        assertThat(plan.explicitLimit()).isTrue();
        assertThat(plan.query().block().sort()).hasSize(1);
        assertThat(plan.query().source()).isInstanceOf(SetOpRelation.class);
        final SetOpRelation setOp = (SetOpRelation) plan.query().source();
        assertThat(setOp.branches()).allSatisfy(branch -> {
            assertThat(branch.block().sort()).isNull();
            assertThat(branch.block().page()).isNull();
        });
    }

    @Test
    void branchKeepsItsOwnParenthesizedOrderByAndLimit() {
        final SetOpRelation setOp = setOp("(SELECT event_kind FROM dial_usage_log ORDER BY event_kind"
                + " LIMIT 5) UNION ALL (SELECT event_kind FROM dial_usage_log ORDER BY event_kind LIMIT 5)");
        assertThat(setOp.branches()).hasSize(2);
        assertThat(setOp.branches()).allSatisfy(branch ->
                assertThat(branch.block().page()).isEqualTo(new OffsetPage(0, 5, false)));
    }

    @Test
    void theRulesHoldInsideDerivedTable() {
        // A bare INTERSECT inside a derived table is still ALL. The aggregate above it folds onto the set
        // operation directly — a bare derived table adds no block of its own — so the source is the
        // operation, not a DerivedRelation wrapping it.
        final SqlCompositeTranslation plan = composite("SELECT count(*) AS n FROM (SELECT event_kind"
                + " FROM dial_usage_log INTERSECT SELECT event_kind FROM dial_usage_log) t");
        assertThat(plan.query().source()).isInstanceOf(SetOpRelation.class);
        assertThat(((SetOpRelation) plan.query().source()).all()).isTrue();

        // And a trailing clause inside the derived table still binds to that derived table's last branch,
        // so a parenthesized branch there is refused the same way.
        assertThatThrownBy(() -> frontEnd.plan("SELECT n FROM (SELECT event_kind AS n FROM dial_usage_log"
                + " UNION ALL (SELECT event_kind FROM dial_usage_log) ORDER BY 1) t"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("belongs to its last branch");
    }

    @Test
    void theRulesHoldInsideCteBody() {
        final SqlCompositeTranslation plan = composite("WITH c AS (SELECT event_kind FROM dial_usage_log"
                + " EXCEPT SELECT event_kind FROM dial_usage_log) SELECT event_kind FROM c");
        // Calcite inlines the CTE at its reference, so the set operation is the statement's own source.
        assertThat(plan.query().source()).isInstanceOf(SetOpRelation.class);
        assertThat(((SetOpRelation) plan.query().source()).all()).isTrue();
    }

    @Test
    void theRulesHoldInsideSubquery() {
        assertThatThrownBy(() -> frontEnd.plan("SELECT event_id FROM dial_usage_log WHERE event_kind IN"
                + " (SELECT event_kind FROM dial_usage_log UNION SELECT event_kind FROM dial_usage_log)"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("UNION requires ALL or DISTINCT");
    }

    @Test
    void theRulesHoldInsideJoinCondition() {
        // An ON predicate is a query position like WHERE, and one the pass used to skip — so a bare UNION
        // there reached ClickHouse (which refuses it: Code 558 EXPECTED_ALL_OR_DISTINCT) as Calcite's
        // UNION DISTINCT.
        for (final String operator : List.of("UNION", "MINUS")) {
            assertThatThrownBy(() -> frontEnd.plan("SELECT a.event_kind FROM dial_usage_log a"
                    + " JOIN dial_usage_log b ON a.event_kind = b.event_kind AND a.event_kind IN"
                    + " (SELECT event_kind FROM dial_usage_log " + operator
                    + " SELECT event_kind FROM dial_usage_log)"))
                    .isInstanceOf(SqlTranslationException.class)
                    .hasMessageContaining("UNION".equals(operator)
                            ? "UNION requires ALL or DISTINCT" : "MINUS is not supported");
        }
    }

    @Test
    void theRulesHoldInsideCase() {
        // CASE is a SqlCall that is not a SqlBasicCall, so its WHEN/THEN/ELSE operands need the general
        // walk; without it a set operation nested there kept Calcite's parse.
        for (final String operator : List.of("UNION", "MINUS")) {
            assertThatThrownBy(() -> frontEnd.plan("SELECT CASE WHEN event_kind IN (SELECT event_kind"
                    + " FROM dial_usage_log " + operator + " SELECT event_kind FROM dial_usage_log)"
                    + " THEN 1 ELSE 0 END AS f FROM dial_usage_log"))
                    .isInstanceOf(SqlTranslationException.class)
                    .hasMessageContaining("UNION".equals(operator)
                            ? "UNION requires ALL or DISTINCT" : "MINUS is not supported");
        }
    }

    // --------------------------------------------------------- columns and shape

    @Test
    void outputNamesComeFromTheFirstBranch() {
        final SqlCompositeTranslation plan = composite("SELECT event_kind AS m FROM dial_usage_log"
                + " UNION ALL SELECT event_kind AS other FROM dial_usage_log");
        assertThat(plan.columns()).singleElement()
                .satisfies(column -> assertThat(column.name()).isEqualTo("m"));
    }

    @Test
    void nestedOperationNamesAnUnnamedFirstBranchColumnAsItsBranchDoes() {
        // The operation's columns have to carry the names its rendered branches expose. For an item
        // Calcite had to name itself the two disagree — the row type says EXPR$0, the branch renders the
        // DSL's base name — and a nested operation has no alias injection covering for it, so the block
        // above would reference a column the operation does not expose.
        for (final String sql : List.of(
                "WITH c AS (SELECT max(event_kind) FROM dial_usage_log"
                        + " UNION ALL SELECT max(event_kind) FROM dial_usage_log) SELECT * FROM c",
                "SELECT * FROM (SELECT max(event_kind) FROM dial_usage_log"
                        + " UNION ALL SELECT max(event_kind) FROM dial_usage_log) t")) {
            final SqlCompositeTranslation plan = composite(sql);
            assertThat(plan.query().source()).isInstanceOf(SetOpRelation.class);
            assertThat(plan.query().source().columns()).singleElement()
                    .satisfies(column -> assertThat(column.name()).isEqualTo("max"));
            // And the planner's own synthesized name never reaches the result descriptor.
            assertThat(plan.columns()).singleElement()
                    .satisfies(column -> assertThat(column.name()).isEqualTo("max"));
        }
    }

    @Test
    void agreeingBranchesKeepTheirExactRuntimeKind() {
        // A DECIMAL column in both branches must not be re-derived through Calcite's unification, which
        // would lose the distinction the Arrow schema needs (FLOAT64 and DECIMAL are one declared type).
        final SqlCompositeTranslation plan = composite("SELECT total_price FROM dial_usage_log"
                + " UNION ALL SELECT total_price FROM dial_usage_log");
        assertThat(plan.columns()).singleElement()
                .satisfies(column -> assertThat(column.kind()).isEqualTo(ResultColumnKind.DECIMAL));
    }

    @Test
    void disagreeingBranchesTakeTheUnifiedType() {
        // INTEGER in one branch, LONG in the other: the result column is the widened type, so the
        // descriptor names what actually arrives rather than the first branch's narrower kind.
        final SqlCompositeTranslation plan = composite("SELECT 1 AS n FROM dial_usage_log"
                + " UNION ALL SELECT total_tokens AS n FROM dial_usage_log");
        assertThat(plan.columns()).singleElement()
                .satisfies(column -> assertThat(column.kind()).isEqualTo(ResultColumnKind.INT64));
    }

    @Test
    void textAndTimestampRewritesReachEveryBranch() {
        // The pre-validation rewriters (|| to concat, PostgreSQL-shaped date_trunc) walk each branch like
        // any other query block — a set operation does not shadow them.
        final SqlCompositeTranslation plan = composite(
                "SELECT event_kind || 'a' AS k FROM dial_usage_log"
                        + " UNION ALL SELECT event_kind || 'b' AS k FROM dial_usage_log");
        assertThat(plan.columns()).singleElement()
                .satisfies(column -> assertThat(column.kind()).isEqualTo(ResultColumnKind.STRING));
        final SqlCompositeTranslation truncated = composite(
                "SELECT date_trunc('hour', request_time) AS h FROM dial_usage_log"
                        + " UNION ALL SELECT date_trunc('hour', request_time) AS h FROM dial_usage_log");
        assertThat(truncated.columns()).singleElement()
                .satisfies(column -> assertThat(column.kind()).isEqualTo(ResultColumnKind.TIMESTAMP));
    }

    @Test
    void columnFilteredOutOfTheModelStaysUnknownInEveryBranch() {
        // Governance chokepoint: the Calcite schema is a snapshot of CatalogQueryService.findModel, whose
        // bindings are already filtered by SensitiveColumnAccess. A caller who may not see a sensitive
        // column gets a model without it, so every branch of a set operation sees an unknown column —
        // there is no per-branch resolution that could reintroduce it.
        final Map<String, QueryFieldBinding> bindings =
                new LinkedHashMap<>(DialUsageLogTestModel.model().bindings());
        bindings.remove("dial_usage_log_payload.jwt_claims");
        final QueryEntityModel filtered = new QueryEntityModel(
                DialUsageLogTestModel.ENTITY,
                DSL.table(DSL.name(DialUsageLogTestModel.ENTITY)),
                bindings,
                List.of(),
                DialUsageLogTestModel.model().baseSchema().stream()
                        .filter(field -> !"dial_usage_log_payload.jwt_claims".equals(field.name()))
                        .toList());
        final CatalogQueryService catalog = mock(CatalogQueryService.class);
        when(catalog.queryableEntityNames(any())).thenReturn(List.of(DialUsageLogTestModel.ENTITY));
        when(catalog.findModel(eq(DialUsageLogTestModel.ENTITY), any())).thenReturn(Optional.of(filtered));
        final SqlQueryFrontEnd restricted = new SqlQueryFrontEnd(new CalciteQueryCatalog(catalog));

        for (final String sql : List.of(
                "SELECT \"dial_usage_log_payload.jwt_claims\" FROM dial_usage_log"
                        + " UNION ALL SELECT event_kind FROM dial_usage_log",
                "SELECT event_kind FROM dial_usage_log"
                        + " UNION ALL SELECT \"dial_usage_log_payload.jwt_claims\" FROM dial_usage_log")) {
            assertThatThrownBy(() -> restricted.plan(sql))
                    .isInstanceOf(SqlTranslationException.class)
                    .hasMessageContaining("jwt_claims");
        }
    }

    @Test
    void branchColumnTypesThatDoNotUnifyAreRejectedAsBranchMismatch() {
        // Text against a number is the likeliest branch mistake, and the validator "unifies" it by
        // inserting a CAST to text into the other branch — a cast the Rex translation refuses. The refusal
        // must read as the branch mismatch it is, naming the column and the two types, rather than as a
        // CAST the caller never wrote (which the translation endpoint would answer with "drop the CAST").
        assertThatThrownBy(() -> frontEnd.plan("SELECT event_kind AS v FROM dial_usage_log"
                + " UNION ALL SELECT total_tokens AS v FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessage("the branches of a set operation give column 'v' incompatible types"
                        + " (string vs int64); project the same type in every branch");
        // The pair is named in branch order, whichever branch the validator chose to cast.
        assertThatThrownBy(() -> frontEnd.plan("SELECT request_time AS v FROM dial_usage_log"
                + " UNION ALL SELECT event_kind AS v FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("column 'v' incompatible types (timestamp vs string)");
        // An unnamed column — one no alias injection reaches, so nested — is named by position; Calcite's
        // synthesized name is a planner internal.
        assertThatThrownBy(() -> frontEnd.plan("WITH c AS (SELECT max(event_kind) FROM dial_usage_log"
                + " UNION ALL SELECT max(total_tokens) FROM dial_usage_log) SELECT * FROM c"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("give column 1 incompatible types (string vs int64)")
                .hasMessageNotContaining("EXPR$");
    }

    @Test
    void mismatchedBranchArityIsRejected() {
        assertThatThrownBy(() -> frontEnd.plan("SELECT event_kind FROM dial_usage_log"
                + " UNION ALL SELECT event_kind, event_id FROM dial_usage_log"))
                .isInstanceOf(SqlTranslationException.class)
                .hasMessageContaining("olumn");
    }

    @Test
    void setOperationAsJoinOperandIsSealedIntoDerivedTable() {
        final SqlCompositeTranslation plan = composite("SELECT a.event_kind FROM (SELECT event_kind"
                + " FROM dial_usage_log UNION ALL SELECT event_kind FROM dial_usage_log) a"
                + " JOIN dial_usage_log b ON a.event_kind = b.event_kind");
        assertThat(plan.query().source()).isNotNull();
        assertThat(CompositeQueryEntities.leafEntities(plan.query()))
                .containsExactly(DialUsageLogTestModel.ENTITY);
    }

    @Test
    void multiBranchChainNests() {
        final SetOpRelation setOp = setOp("SELECT event_kind FROM dial_usage_log"
                + " UNION ALL SELECT event_kind FROM dial_usage_log"
                + " UNION ALL SELECT event_kind FROM dial_usage_log");
        // Left-associative: the first operand is itself a union, whether Calcite kept it binary or
        // flattened the chain into one node.
        assertThat(setOp.branches().size() >= 2).isTrue();
    }

    private static SqlQueryFrontEnd newFrontEnd() {
        final Map<String, QueryEntityModel> models =
                Map.of(DialUsageLogTestModel.ENTITY, DialUsageLogTestModel.model(), "events", eventsModel());
        final CatalogQueryService catalog = mock(CatalogQueryService.class);
        when(catalog.queryableEntityNames(any())).thenReturn(List.copyOf(models.keySet()));
        models.forEach((name, model) -> when(catalog.findModel(eq(name), any())).thenReturn(Optional.of(model)));
        return new SqlQueryFrontEnd(new CalciteQueryCatalog(catalog));
    }

    /** Source {@code events(user_id, amount)} — a second entity, and a numeric column for a type mismatch. */
    private static QueryEntityModel eventsModel() {
        final Map<String, QueryFieldBinding> bindings = new LinkedHashMap<>();
        final List<QuerySchemaFieldDto> schema = new ArrayList<>();
        for (final var column : List.of(
                Map.entry("user_id", QueryFieldType.STRING),
                Map.entry("amount", QueryFieldType.LONG))) {
            bindings.put(column.getKey(), new QueryFieldBinding(column.getKey(),
                    DSL.field(DSL.name("events", column.getKey()), JooqTypes.of(column.getValue())),
                    column.getValue()));
            schema.add(new QuerySchemaFieldDto(
                    column.getKey(), column.getValue(), null, null, column.getKey(), null, null, null, false, false));
        }
        return new QueryEntityModel("events", DSL.table(DSL.name("events")), bindings, List.of(), schema);
    }
}
