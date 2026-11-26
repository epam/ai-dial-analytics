package com.epam.aidial.analytics.service.query.translate;

import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.service.query.model.CaseBranch;
import com.epam.aidial.analytics.service.query.model.CaseExpr;
import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.ComparisonOp;
import com.epam.aidial.analytics.service.query.model.CompositeQuery;
import com.epam.aidial.analytics.service.query.model.DerivedRelation;
import com.epam.aidial.analytics.service.query.model.EntityRelation;
import com.epam.aidial.analytics.service.query.model.Expr;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.FnExpr;
import com.epam.aidial.analytics.service.query.model.OutputColumn;
import com.epam.aidial.analytics.service.query.model.QueryMode;
import com.epam.aidial.analytics.service.query.model.RelationColumn;
import com.epam.aidial.analytics.service.query.model.ScalarSubqueryExpr;
import com.epam.aidial.analytics.service.query.model.SetOpKind;
import com.epam.aidial.analytics.service.query.model.SetOpRelation;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.ValueType;
import com.epam.aidial.analytics.service.query.sql.ResultColumnKind;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The plan-level refusals a masked field brings ({@code openspec/specs/structured-query/spec.md}, "Set operations over a
 * masked output" and the table-named names), over entity {@code t} whose field {@code secret} is masked.
 */
class MaskedPlanRulesTest {

    private static final Function<String, Set<String>> MASKED = Map.of("t", Set.of("secret"))::get;
    private static final Function<String, Set<String>> TABLES = Map.of("t", Set.of("t", "e"))::get;

    @Test
    void intersectOverMaskedOutputIsRefusedAndOverVisibleOneIsNot() {
        assertThatThrownBy(() -> check(setOp(SetOpKind.INTERSECT, "secret")))
                .isInstanceOf(QueryValidationException.class).hasMessageContaining("INTERSECT").hasMessageContaining("'secret'");
        assertThatThrownBy(() -> check(setOp(SetOpKind.EXCEPT, "secret"))).isInstanceOf(QueryValidationException.class);
        assertThatCode(() -> check(setOp(SetOpKind.INTERSECT, "p"))).doesNotThrowAnyException();
        assertThatCode(() -> check(setOp(SetOpKind.UNION, "secret"))).doesNotThrowAnyException();
    }

    @Test
    void maskedFieldReachedThroughDerivedTableStillRefusesIntersect() {
        final CompositeQuery inner = entityBlock(new OutputColumn(new FieldExpr("secret"), "cost"), "cost");
        final CompositeQuery outer = new CompositeQuery(new DerivedRelation(inner),
                block(new OutputColumn(new FieldExpr("cost"), null)), List.of(column("cost")));
        final CompositeQuery visible = entityBlock(new OutputColumn(new FieldExpr("p"), "cost"), "cost");
        final CompositeQuery intersect = new CompositeQuery(
                new SetOpRelation(SetOpKind.INTERSECT, true, List.of(outer, visible), List.of(column("cost"))),
                block(new OutputColumn(new FieldExpr("cost"), null)), List.of(column("cost")));

        assertThatThrownBy(() -> check(intersect)).isInstanceOf(QueryValidationException.class).hasMessageContaining("'secret'");
    }

    /** A scalar subquery in a {@code case} condition decides the output, so a masked field it reads marks it. */
    @Test
    void maskedFieldReachedThroughCaseConditionSubqueryRefusesIntersect() {
        final CompositeQuery total = entityBlock(new OutputColumn(new FnExpr("sum", false, List.of(new FieldExpr("secret"))), "s"), "s");
        final Expr decided = new CaseExpr(List.of(new CaseBranch(new ComparisonNode(ComparisonOp.GT,
                List.of(new ValueExpr(ValueType.INTEGER, "100"), new ScalarSubqueryExpr(total))), str("a"))), str("b"));
        final CompositeQuery left = entityBlock(new OutputColumn(decided, "v"), "v");
        final CompositeQuery right = entityBlock(new OutputColumn(new FieldExpr("p"), "v"), "v");
        final CompositeQuery intersect = new CompositeQuery(new SetOpRelation(SetOpKind.INTERSECT, false, List.of(left, right),
                List.of(column("v"))), block(new OutputColumn(new FieldExpr("v"), null)), List.of(column("v")));

        assertThatThrownBy(() -> check(intersect)).isInstanceOf(QueryValidationException.class).hasMessageContaining("'secret'");
    }

    @Test
    void tableNamedNameIsRefusedOnlyBesideMaskedFieldAndFieldOwnNameIsExempt() {
        assertThatThrownBy(() -> MaskedPlanRules.check(single(new OutputColumn(new FieldExpr("secret"), "e.x")), MASKED, TABLES))
                .isInstanceOf(QueryValidationException.class).hasMessageContaining("'e.x'");
        assertThatThrownBy(() -> MaskedPlanRules.check(single(new OutputColumn(new FieldExpr("secret"), "t")), MASKED, TABLES))
                .isInstanceOf(QueryValidationException.class);
        assertThatCode(() -> MaskedPlanRules.check(single(new OutputColumn(new FieldExpr("p"), "t")), MASKED, TABLES))
                .as("no masked field read").doesNotThrowAnyException();
        assertThatCode(() -> MaskedPlanRules.check(single(new OutputColumn(new FieldExpr("secret"), "secret")), MASKED, TABLES))
                .doesNotThrowAnyException();
    }

    /** Only a qualified enrichment name is exempt: a source field exposed under the table's name is refused projected bare. */
    @Test
    void bareSourceFieldNamedLikeTheTableIsNotExempt() {
        final List<OutputColumn> exposedAsTable = List.of(new OutputColumn(new FieldExpr("t"), null), new OutputColumn(new FieldExpr("secret"), null));
        assertThatThrownBy(() -> MaskedPlanRules.check(new StructuredQuery("t", null, QueryMode.ROW, false, exposedAsTable, null, null, null, null),
                MASKED, TABLES)).isInstanceOf(QueryValidationException.class).hasMessageContaining("'t'");
        final List<OutputColumn> enrichment = List.of(new OutputColumn(new FieldExpr("e.c"), null), new OutputColumn(new FieldExpr("secret"), null));
        assertThatCode(() -> MaskedPlanRules.check(new StructuredQuery("t", null, QueryMode.ROW, false, enrichment, null, null, null, null),
                MASKED, TABLES)).doesNotThrowAnyException();
    }

    private static void check(CompositeQuery query) {
        MaskedPlanRules.check(query, MASKED, TABLES);
    }

    private static CompositeQuery setOp(SetOpKind kind, String field) {
        final CompositeQuery left = entityBlock(new OutputColumn(new FieldExpr(field), "v"), "v");
        final CompositeQuery right = entityBlock(new OutputColumn(new FieldExpr("p"), "v"), "v");
        return new CompositeQuery(new SetOpRelation(kind, true, List.of(left, right), List.of(column("v"))),
                block(new OutputColumn(new FieldExpr("v"), null)), List.of(column("v")));
    }

    private static CompositeQuery entityBlock(OutputColumn output, String name) {
        return new CompositeQuery(new EntityRelation("t", List.of(column("p"), column("secret"))), block(output), List.of(column(name)));
    }

    private static StructuredQuery single(OutputColumn output) {
        return new StructuredQuery("t", null, QueryMode.ROW, false, List.of(output), null, null, null, null);
    }

    private static StructuredQuery block(OutputColumn output) {
        return single(output);
    }

    private static ValueExpr str(String value) {
        return new ValueExpr(ValueType.STRING, value);
    }

    private static RelationColumn column(String name) {
        return new RelationColumn(name, QueryFieldType.STRING, ResultColumnKind.STRING);
    }
}
