package com.epam.aidial.analytics.service.query;

import com.epam.aidial.analytics.configuration.QueryBudgetProperties;
import com.epam.aidial.analytics.configuration.QueryDslConfiguration;
import com.epam.aidial.analytics.service.query.model.CompositeQuery;
import com.epam.aidial.analytics.service.query.model.EntityRelation;
import com.epam.aidial.analytics.service.query.model.QueryMode;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.repository.QueryResultPage;
import com.epam.aidial.analytics.service.query.repository.StructuredQueryExecutor;
import com.epam.aidial.analytics.service.query.translate.CompositeQueryBuilder;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * The composite half of the partition-local rule: one statement carries one partition switch, and the switch
 * affects only the tables it reads FINAL, so a plan deduplicates inside each partition only when it reads at
 * least one entity final and <em>every</em> entity it reads final is partition-local. The builder is mocked to
 * report which entities it read final, which is exactly where the conjunction is taken.
 */
@ExtendWith(MockitoExtension.class)
class StructuredQueryServiceTest {

    private static final QueryBudgetProperties BUDGET = new QueryBudgetProperties(
            Duration.ofSeconds(20), DataSize.ofGigabytes(2), DataSize.ofGigabytes(1));

    private final DSLContext ctx = new QueryDslConfiguration().clickhouseRenderContext();

    @Mock
    private CatalogQueryService catalog;
    @Mock
    private StructuredQueryExecutor executor;
    @Mock
    private CompositeQueryBuilder compositeBuilder;

    /** A plan whose content is irrelevant: the mocked builder decides which entities get resolved. */
    private static CompositeQuery plan() {
        final StructuredQuery block = new StructuredQuery("a", null, QueryMode.ROW, false, List.of(), null, null, null, null);
        return new CompositeQuery(new EntityRelation("a", List.of()), block, List.of());
    }

    /** The mocked builder reports {@code finalReads} as the entities it rendered FINAL. */
    private void givenPlanReadsFinal(QueryEntityModel... finalReads) {
        given(compositeBuilder.build(any(), any(), any(), anyInt(), any())).willAnswer(invocation -> {
            final Consumer<QueryEntityModel> reported = invocation.getArgument(4);
            for (final QueryEntityModel model : finalReads) {
                reported.accept(model);
            }
            return ctx.selectQuery();
        });
        given(executor.executeComposite(any(), any(), any(Boolean.class))).willReturn(new QueryResultPage(List.of(), List.of(), null));
    }

    private StructuredQueryService service() {
        return new StructuredQueryService(catalog, executor, compositeBuilder, ctx, BUDGET);
    }

    @Test
    void compositeOverPartitionLocalEntitiesDeduplicatesInsideEachPartition() {
        givenPlanReadsFinal(DialUsageLogTestModel.model(), DialUsageLogTestModel.model());

        service().execute(plan());

        verify(executor).executeComposite(any(), any(), eq(true));
    }

    @Test
    void planReadingNothingFinalCarriesNoPartitionSwitch() {
        givenPlanReadsFinal();

        service().execute(plan());

        verify(executor).executeComposite(any(), any(), eq(false));
    }

    @Test
    void oneCrossPartitionEntityKeepsTheWholeCompositeCrossPartition() {
        // The second entity cannot vouch for its partitions, so the statement — which reads both final — cannot either.
        givenPlanReadsFinal(DialUsageLogTestModel.model(), DialUsageLogTestModel.crossPartitionModel());

        service().execute(plan());

        verify(executor).executeComposite(any(), any(), eq(false));
    }
}
