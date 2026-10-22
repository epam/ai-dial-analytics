package com.epam.aidial.analytics.service.query.sql;

import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.ComparisonOp;
import com.epam.aidial.analytics.service.query.model.CompositeQuery;
import com.epam.aidial.analytics.service.query.model.DerivedRelation;
import com.epam.aidial.analytics.service.query.model.EntityRelation;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.JoinKind;
import com.epam.aidial.analytics.service.query.model.JoinRelation;
import com.epam.aidial.analytics.service.query.model.OutputColumn;
import com.epam.aidial.analytics.service.query.model.QueryMode;
import com.epam.aidial.analytics.service.query.model.ScalarSubqueryExpr;
import com.epam.aidial.analytics.service.query.model.SetOpKind;
import com.epam.aidial.analytics.service.query.model.SetOpRelation;
import com.epam.aidial.analytics.service.query.model.SortDir;
import com.epam.aidial.analytics.service.query.model.SortItem;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.model.SubqueryNode;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.ValueType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link QueryReferences}: entity references counted per occurrence, and the constructs that carry them. */
class QueryReferencesTest {

    private static StructuredQuery rowBlock() {
        return new StructuredQuery(null, null, QueryMode.ROW, false, List.of(), null, null, null, null);
    }

    private static CompositeQuery over(String entity) {
        return new CompositeQuery(new EntityRelation(entity, List.of()), rowBlock(), List.of());
    }

    @Test
    void singleScanCountsOnce() {
        final QueryReferences refs = QueryReferences.of(over("dial_usage_log"));
        assertThat(refs.entityCounts()).isEqualTo(Map.of("dial_usage_log", 1));
        assertThat(refs.occurrences()).isEqualTo(1);
        assertThat(refs.joins()).isZero();
        assertThat(refs.subqueries()).isZero();
        assertThat(refs.setOperations()).isEmpty();
        assertThat(refs.nestedSealing()).isEmpty();
    }

    @Test
    void selfJoinCountsEveryOccurrence() {
        final JoinRelation join = new JoinRelation(
                new DerivedRelation(over("dial_usage_log")), new DerivedRelation(over("dial_usage_log")),
                JoinKind.INNER, null, List.of());
        final QueryReferences refs = QueryReferences.of(new CompositeQuery(join, rowBlock(), List.of()));
        assertThat(refs.entityCounts()).isEqualTo(Map.of("dial_usage_log", 2));
        assertThat(refs.occurrences()).isEqualTo(2);
        assertThat(refs.joins()).isEqualTo(1);
    }

    @Test
    void setOperationBranchesAreCounted() {
        final SetOpRelation union = new SetOpRelation(
                SetOpKind.UNION, true, List.of(over("dial_usage_log"), over("turns")), List.of());
        final QueryReferences refs = QueryReferences.of(new CompositeQuery(union, rowBlock(), List.of()));
        assertThat(refs.setOperations()).containsExactly(SetOpKind.UNION);
        assertThat(refs.entityCounts()).containsOnlyKeys("dial_usage_log", "turns");
    }

    @Test
    void subqueriesInSelectAndFilterAreCounted() {
        final StructuredQuery block = new StructuredQuery(
                "dial_usage_log",
                new SubqueryNode(SubqueryNode.SubqueryKind.IN, List.of(new FieldExpr("deployment")), over("events")),
                QueryMode.ROW, false,
                List.of(new OutputColumn(new ScalarSubqueryExpr(over("dial_usage_log")), "s")),
                null, null, null, null);
        final QueryReferences refs = QueryReferences.of(block);
        assertThat(refs.subqueries()).isEqualTo(2);
        assertThat(refs.entityCounts()).isEqualTo(Map.of("dial_usage_log", 2, "events", 1));
    }

    @Test
    void plainBlockCountsItsEntityOnce() {
        final StructuredQuery block = new StructuredQuery(
                "dial_usage_log",
                new ComparisonNode(ComparisonOp.EQ, List.of(new FieldExpr("deployment"), new ValueExpr(ValueType.STRING, "gpt"))),
                QueryMode.ROW, false, List.of(), null, null, null, null);
        assertThat(QueryReferences.of(block).entityCounts()).isEqualTo(Map.of("dial_usage_log", 1));
    }

    @Test
    void nestedBlockSealingIsNamed() {
        final StructuredQuery inner = new StructuredQuery(
                null, null, QueryMode.AGGREGATE, true, List.of(), List.of("deployment"), null,
                List.of(new SortItem("deployment", SortDir.ASC, null)), null);
        final CompositeQuery derived = new CompositeQuery(new EntityRelation("dial_usage_log", List.of()), inner, List.of());
        final QueryReferences refs = QueryReferences.of(new CompositeQuery(new DerivedRelation(derived), rowBlock(), List.of()));
        assertThat(refs.nestedSealing()).containsExactly("an aggregate", "DISTINCT", "ORDER BY");
        assertThat(refs.occurrences()).isEqualTo(1);
    }
}
