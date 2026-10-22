package com.epam.aidial.analytics.service.catalog.transfer;

import com.epam.aidial.analytics.configuration.JsonMapperConfiguration;
import com.epam.aidial.analytics.service.pipeline.PipelineKind;
import com.epam.aidial.analytics.service.table.TableStatus;
import com.epam.aidial.analytics.service.table.TableType;
import com.epam.aidial.analytics.web.dto.catalog.BundlePipeline;
import com.epam.aidial.analytics.web.dto.catalog.BundleTable;
import com.epam.aidial.analytics.web.dto.catalog.CatalogBundle;
import com.epam.aidial.analytics.web.dto.catalog.ImportAction;
import com.epam.aidial.analytics.web.dto.catalog.ResolutionPolicy;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineRequests.MeasureRequest;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineRequests.TriggerRequest;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import com.epam.aidial.analytics.web.dto.table.CreateTableRequest;
import com.epam.aidial.analytics.web.dto.table.CreateTableRequest.ColumnRequest;
import com.epam.aidial.analytics.web.dto.table.DefineSchemaRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The import planner against hand-built target snapshots: no repository, no Spring. */
class CatalogImportPlannerTest {

    private static final String SYSTEM = "dial_usage_log";

    private final CatalogImportPlanner planner = new CatalogImportPlanner(new JsonMapperConfiguration().jsonMapper());

    @Test
    void ordersSourcesBeforeEnrichmentsAndEnrichBeforeAggregate() {
        final ImportPlan plan = plan(bundle(List.of(enrichment("usage_sentiment", "usage_base"), source("usage_base")),
                List.of(aggregate("rollup", "usage_base", "rollup_target"), enrich("sentiment_llm", "usage_sentiment"))),
                ResolutionPolicy.FAIL_IF_EXISTS, target(Map.of()));

        assertThat(plan.tables()).extracting(PlannedObject::name).containsExactly("usage_base", "usage_sentiment");
        assertThat(plan.pipelines()).extracting(PlannedObject::name).containsExactly("sentiment_llm", "rollup");
    }

    @Test
    void existingObjectFailsUnderFailIfExistsAndSkipsUnderSkipIfExists() {
        final BundleTable base = source("usage_base");
        final ImportTarget target = target(Map.of("usage_base", existing(base)));

        final ImportPlan failing = plan(bundle(List.of(base), List.of()), ResolutionPolicy.FAIL_IF_EXISTS, target);
        final ImportPlan skipping = plan(bundle(List.of(base), List.of()), ResolutionPolicy.SKIP_IF_EXISTS, target);

        assertThat(failing.tables().getFirst().action()).isEqualTo(ImportAction.FAIL);
        assertThat(failing.hasConflicts()).isTrue();
        assertThat(skipping.tables().getFirst().action()).isEqualTo(ImportAction.SKIP);
        assertThat(skipping.hasProblems()).isFalse();
        assertThat(skipping.tables().getFirst().toEntry().differs()).isFalse();
    }

    @Test
    void descriptionOnlyDifferenceIsMetadataOnly() {
        final BundleTable stored = source("usage_base");
        final BundleTable bundled = withColumnDescription(stored, "a new description");

        final PlannedObject planned = plan(bundle(List.of(bundled), List.of()), ResolutionPolicy.SKIP_IF_EXISTS,
                target(Map.of("usage_base", existing(stored)))).tables().getFirst();

        assertThat(planned.toEntry().differs()).isTrue();
        assertThat(planned.toEntry().metadataOnly()).isTrue();
    }

    @Test
    void typeChangeIsNotMetadataOnly() {
        final BundleTable stored = source("usage_base");
        final BundleTable bundled = new BundleTable(stored.table(), schema(List.of(
                column("id", QueryFieldType.STRING), column("score", QueryFieldType.DECIMAL)), List.of("id")));

        final PlannedObject planned = plan(bundle(List.of(bundled), List.of()), ResolutionPolicy.SKIP_IF_EXISTS,
                target(Map.of("usage_base", existing(stored)))).tables().getFirst();

        assertThat(planned.toEntry().differs()).isTrue();
        assertThat(planned.toEntry().metadataOnly()).isFalse();
    }

    @Test
    void presentSystemTableIsSkipped() {
        final PlannedObject planned = plan(requiring(SYSTEM), ResolutionPolicy.FAIL_IF_EXISTS,
                target(Map.of(SYSTEM, system()))).systemTables().getFirst();

        assertThat(planned.action()).isEqualTo(ImportAction.SKIP);
        assertThat(planned.problems()).isEmpty();
        assertThat(planned.isConflict()).isFalse();
    }

    @Test
    void missingSystemTableFails() {
        final PlannedObject planned = plan(requiring(SYSTEM), ResolutionPolicy.SKIP_IF_EXISTS, target(Map.of()))
                .systemTables().getFirst();

        assertThat(planned.action()).isEqualTo(ImportAction.FAIL);
        assertThat(planned.problems().getFirst()).contains("does not exist");
    }

    @Test
    void userTableRequiredAsSystemTableFails() {
        final PlannedObject planned = plan(requiring("usage_base"), ResolutionPolicy.SKIP_IF_EXISTS,
                target(Map.of("usage_base", existing(source("usage_base"))))).systemTables().getFirst();

        assertThat(planned.action()).isEqualTo(ImportAction.FAIL);
        assertThat(planned.problems().getFirst()).contains("is not a system table");
    }

    @Test
    void missingDependencyFailsThePipeline() {
        final PlannedObject planned = plan(bundle(List.of(), List.of(aggregate("rollup", "usage_base", "rollup_target"))),
                ResolutionPolicy.FAIL_IF_EXISTS, target(Map.of())).pipelines().getFirst();

        assertThat(planned.action()).isEqualTo(ImportAction.FAIL);
        assertThat(planned.problems()).anyMatch(problem -> problem.contains("'usage_base' is neither in the bundle"));
    }

    @Test
    void enrichmentReferencedOnlyInFilterIsDependency() {
        final BundlePipeline rollup = new BundlePipeline(new BundlePipeline.Declaration("rollup", PipelineKind.AGGREGATE,
                "rollup_target", List.of("usage_base"), "\"usage_sentiment.sentiment\" = 'positive'",
                new TriggerRequest("schedule", "0 * * * * *", null, null, null), null,
                List.of(new MeasureRequest("n", "count", null, null, null)), null, null, null), false);
        final Map<String, ImportTarget.Table> tables = new HashMap<>();
        tables.put("usage_base", existing(source("usage_base")));
        tables.put("rollup_target", existing(source("rollup_target")));

        assertThat(CatalogImportPlanner.dependencies(rollup.pipeline())).contains("usage_sentiment");
        final PlannedObject planned = plan(bundle(List.of(), List.of(rollup)), ResolutionPolicy.SKIP_IF_EXISTS,
                target(tables)).pipelines().getFirst();
        assertThat(planned.problems()).anyMatch(problem -> problem.contains("'usage_sentiment'"));
    }

    @Test
    void tableLimitIsCountedOnTheTotal() {
        final ImportTarget nearlyFull = new ImportTarget(Map.of("other", existing(source("other"))), Map.of(), true,
                Map.of(), Set.of(), 1, 2, 256);

        final ImportPlan plan = plan(bundle(List.of(source("a"), source("b")), List.of()), ResolutionPolicy.FAIL_IF_EXISTS,
                nearlyFull);

        assertThat(plan.validationErrors()).anyMatch(error -> error.contains("maximum of 2 tables"));
        assertThat(plan.hasProblems()).isTrue();
    }

    @Test
    void columnLimitCountsInjectedProvenance() {
        final ImportTarget narrow = new ImportTarget(Map.of("usage_base", existing(source("usage_base"))), Map.of(), true,
                Map.of(), Set.of(), 1, 100, 3);

        final ImportPlan plan = plan(bundle(List.of(enrichment("usage_sentiment", "usage_base")), List.of()),
                ResolutionPolicy.SKIP_IF_EXISTS, narrow);

        assertThat(plan.tables().getFirst().action()).isEqualTo(ImportAction.FAIL);
        assertThat(plan.validationErrors()).anyMatch(error -> error.contains("at most 3 columns"));
    }

    @Test
    void reusedNameFailsUnlessAcknowledged() {
        final Map<String, ImportTarget.Table> tables = Map.of("usage_base", existing(source("usage_base")),
                "rollup_target", existing(source("rollup_target")));
        final ImportTarget target = new ImportTarget(tables, Map.of(), true, Map.of(), Set.of("rollup"), tables.size(), 100,
                256);
        final CatalogBundle bundle = bundle(List.of(), List.of(aggregate("rollup", "usage_base", "rollup_target")));

        final PlannedObject refused = planner.plan(bundle, false, ResolutionPolicy.SKIP_IF_EXISTS, target)
                .pipelines().getFirst();
        final PlannedObject accepted = planner.plan(bundle, true, ResolutionPolicy.SKIP_IF_EXISTS,
                target).pipelines().getFirst();

        assertThat(refused.action()).isEqualTo(ImportAction.FAIL);
        assertThat(refused.toEntry().reusedName()).isTrue();
        assertThat(accepted.action()).isEqualTo(ImportAction.CREATE);
        assertThat(accepted.toEntry().reusedName()).isTrue();
    }

    @Test
    void targetHeldByAnotherPipelineFailsUnderEitherPolicy() {
        final Map<String, ImportTarget.Table> tables = Map.of("usage_base", existing(source("usage_base")),
                "rollup_target", existing(source("rollup_target")));
        final ImportTarget target = new ImportTarget(tables, Map.of(), true, Map.of("rollup_target", "someone_else"),
                Set.of(), tables.size(), 100, 256);

        final PlannedObject planned = plan(bundle(List.of(), List.of(aggregate("rollup", "usage_base", "rollup_target"))),
                ResolutionPolicy.SKIP_IF_EXISTS, target).pipelines().getFirst();

        assertThat(planned.action()).isEqualTo(ImportAction.FAIL);
        assertThat(planned.problems()).anyMatch(problem -> problem.contains("someone_else"));
    }

    @Test
    void nonActiveExistingTableIsRefusedUnderEitherPolicy() {
        final BundleTable base = source("usage_base");
        final ImportTarget target = target(Map.of("usage_base", existing(base, TableStatus.FAILED)));

        for (final ResolutionPolicy policy : List.of(ResolutionPolicy.SKIP_IF_EXISTS, ResolutionPolicy.FAIL_IF_EXISTS)) {
            final ImportPlan plan = plan(bundle(List.of(base, enrichment("usage_sentiment", "usage_base")), List.of()),
                    policy, target);

            assertThat(plan.tables().getFirst().action()).isEqualTo(ImportAction.FAIL);
            assertThat(plan.tables().getFirst().isConflict()).isFalse();
            assertThat(plan.tables().getFirst().problems())
                    .containsExactly("table 'usage_base' exists but is FAILED; delete it and import again");
            assertThat(plan.tables().get(1).action()).isEqualTo(ImportAction.FAIL);
            assertThat(plan.hasConflicts()).isFalse();
        }
    }

    @Test
    void deletedNameThatNeverRanIsNotReused() {
        // The reader lists only names deleted after being enabled; one compensation deleted is absent.
        final Map<String, ImportTarget.Table> tables = Map.of("usage_base", existing(source("usage_base")),
                "rollup_target", existing(source("rollup_target")));
        final ImportTarget target = new ImportTarget(tables, Map.of(), true, Map.of(), Set.of("other"), tables.size(), 100,
                256);

        final PlannedObject planned = plan(bundle(List.of(), List.of(aggregate("rollup", "usage_base", "rollup_target"))),
                ResolutionPolicy.SKIP_IF_EXISTS, target).pipelines().getFirst();

        assertThat(planned.action()).isEqualTo(ImportAction.CREATE);
        assertThat(planned.toEntry().reusedName()).isFalse();
    }

    @Test
    void conflictingTableIsNotRefusedForWhatNeedsIt() {
        final BundleTable base = source("usage_base");

        final ImportPlan plan = plan(bundle(List.of(base, enrichment("usage_sentiment", "usage_base")), List.of()),
                ResolutionPolicy.FAIL_IF_EXISTS, target(Map.of("usage_base", existing(base))));

        assertThat(plan.tables().getFirst().isConflict()).isTrue();
        assertThat(plan.tables().get(1).action()).isEqualTo(ImportAction.CREATE);
    }

    @Test
    void nameCarriedAsTableAndSystemTableIsRefused() {
        final CatalogBundle bundle = new CatalogBundle(CatalogBundle.FORMAT, 1, null, List.of(),
                List.of("usage_base"), List.of(source("usage_base")), List.of());

        final ImportPlan plan = plan(bundle, ResolutionPolicy.SKIP_IF_EXISTS, target(Map.of()));

        assertThat(plan.validationErrors()).anyMatch(error -> error.contains("both as a table and as a required system table"));
    }

    @Test
    void hiddenPrevIsReportedAsAbsentButStillCompared() {
        final BundleTable stored = source("usage_base");
        final ImportTarget.Table hidden = new ImportTarget.Table("usage_base", TableType.SOURCE, false, TableStatus.ACTIVE,
                stored, null);

        final PlannedObject planned = plan(bundle(List.of(withColumnDescription(stored, "changed")), List.of()),
                ResolutionPolicy.SKIP_IF_EXISTS, target(Map.of("usage_base", hidden))).tables().getFirst();

        assertThat(planned.toEntry().prev()).isNull();
        assertThat(planned.toEntry().differs()).isTrue();
        assertThat(planned.existing()).isSameAs(stored);
    }

    @Test
    void sqlStatementReadsAreDependencies() {
        final BundlePipeline.Declaration declaration = sqlAggregate("rollup", "rollup_target", """
                -- daily rollup
                INSERT INTO rollup_target (id, n)
                WITH recent AS (SELECT id, "usage_sentiment.sentiment" AS s FROM usage_base WHERE request_time >= '2026-08-05')
                SELECT id, count(*) FROM recent WHERE extract(day FROM request_time) > 0 GROUP BY id""").pipeline();

        assertThat(CatalogImportPlanner.dependencies(declaration)).containsExactlyInAnyOrder("rollup_target", "usage_sentiment");
        assertThat(CatalogImportPlanner.sqlReadTables(declaration)).containsExactly("usage_base", "request_time");
    }

    @Test
    void sqlQuotedNamesAreReadCaseInsensitively() {
        final BundlePipeline.Declaration declaration = sqlAggregate("rollup", "rollup_target", """
                INSERT INTO rollup_target (id, n)
                WITH "Recent" AS (SELECT id, u."Usage_Sentiment.sentiment" AS s FROM "Dial_Usage_Log" u)
                SELECT id, count(*) FROM recent GROUP BY id""").pipeline();

        assertThat(CatalogImportPlanner.dependencies(declaration)).containsExactlyInAnyOrder("rollup_target", "usage_sentiment");
        assertThat(CatalogImportPlanner.sqlReadTables(declaration)).containsExactly("dial_usage_log");
    }

    @Test
    void sqlPipelineReadingMixedCaseQuotedMissingSystemTableFails() {
        final CatalogBundle bundle = new CatalogBundle(CatalogBundle.FORMAT, CatalogBundle.FORMAT_VERSION, null, List.of(),
                List.of(SYSTEM), List.of(), List.of(sqlAggregate("rollup", "rollup_target",
                        "INSERT INTO rollup_target (id, n) SELECT id, count(*) FROM \"Dial_Usage_Log\" GROUP BY id")));

        final PlannedObject planned = plan(bundle, ResolutionPolicy.SKIP_IF_EXISTS,
                target(Map.of("rollup_target", existing(source("rollup_target"))))).pipelines().getFirst();

        assertThat(planned.action()).isEqualTo(ImportAction.FAIL);
        assertThat(planned.problems()).anyMatch(problem -> problem.contains("'" + SYSTEM + "'"));
    }

    @Test
    void sqlPipelineReadingRefusedSourceFails() {
        final BundleTable base = source("usage_base");
        final ImportTarget target = target(Map.of("usage_base", existing(base, TableStatus.FAILED),
                "rollup_target", existing(source("rollup_target"))));

        final PlannedObject planned = plan(bundle(List.of(base), List.of(sqlAggregate("rollup", "rollup_target",
                "INSERT INTO rollup_target (id, n) SELECT id, count(*) FROM usage_base GROUP BY id"))),
                ResolutionPolicy.SKIP_IF_EXISTS, target).pipelines().getFirst();

        assertThat(planned.action()).isEqualTo(ImportAction.FAIL);
        assertThat(planned.problems()).anyMatch(problem -> problem.contains("'usage_base' cannot be imported"));
    }

    @Test
    void sqlPipelineEnrichmentReferenceIsDependency() {
        final PlannedObject planned = plan(bundle(List.of(), List.of(sqlAggregate("rollup", "rollup_target",
                "INSERT INTO rollup_target (id, n) SELECT u.\"usage_sentiment.sentiment\", count(*) FROM usage_base u GROUP BY 1"))),
                ResolutionPolicy.SKIP_IF_EXISTS, target(Map.of("usage_base", existing(source("usage_base")),
                        "rollup_target", existing(source("rollup_target"))))).pipelines().getFirst();

        assertThat(planned.action()).isEqualTo(ImportAction.FAIL);
        assertThat(planned.problems()).anyMatch(problem -> problem.contains("'usage_sentiment' is neither in the bundle"));
    }

    @Test
    void sqlPipelineReadingMissingSystemTableFails() {
        final CatalogBundle bundle = new CatalogBundle(CatalogBundle.FORMAT, CatalogBundle.FORMAT_VERSION, null, List.of(),
                List.of(SYSTEM), List.of(), List.of(sqlAggregate("rollup", "rollup_target",
                        "INSERT INTO rollup_target (id, n) SELECT id, count(*) FROM " + SYSTEM + " GROUP BY id")));

        final PlannedObject planned = plan(bundle, ResolutionPolicy.SKIP_IF_EXISTS,
                target(Map.of("rollup_target", existing(source("rollup_target"))))).pipelines().getFirst();

        assertThat(planned.action()).isEqualTo(ImportAction.FAIL);
        assertThat(planned.problems()).anyMatch(problem -> problem.contains("'" + SYSTEM + "'"));
    }

    @Test
    void unparseableSqlDraftDoesNotFailByItself() {
        final PlannedObject planned = plan(bundle(List.of(), List.of(sqlAggregate("rollup", "rollup_target",
                "INSERT INTO rollup_target (id, n) SELECT id, count( FROM usage_base WHERE GROUP"))),
                ResolutionPolicy.SKIP_IF_EXISTS, target(Map.of("usage_base", existing(source("usage_base")),
                        "rollup_target", existing(source("rollup_target"))))).pipelines().getFirst();

        assertThat(planned.action()).isEqualTo(ImportAction.CREATE);
    }

    @Test
    void sqlDateLiteralIsEnvSpecific() {
        final ImportPlan plan = plan(bundle(List.of(), List.of(sqlAggregate("rollup", "rollup_target",
                "INSERT INTO rollup_target (id, n) SELECT id, count(*) FROM usage_base WHERE request_time >= '2026-08-05' GROUP BY id"))),
                ResolutionPolicy.SKIP_IF_EXISTS, target(Map.of("usage_base", existing(source("usage_base")),
                        "rollup_target", existing(source("rollup_target")))));

        assertThat(plan.envSpecific()).anyMatch(value -> "sql".equals(value.field()) && "2026-08-05".equals(value.value())
                && "rollup".equals(value.name()));
    }

    // --- fixtures ------------------------------------------------------------------------------------

    private ImportPlan plan(CatalogBundle bundle, ResolutionPolicy policy, ImportTarget target) {
        return planner.plan(bundle, false, policy, target);
    }

    private static CatalogBundle bundle(List<BundleTable> tables, List<BundlePipeline> pipelines) {
        return new CatalogBundle(CatalogBundle.FORMAT, 1, null, List.of(), List.of(), tables, pipelines);
    }

    private static ImportTarget target(Map<String, ImportTarget.Table> tables) {
        return new ImportTarget(tables, Map.of(), true, Map.of(), Set.of(), tables.size(), 100, 256);
    }

    private static BundleTable source(String name) {
        return new BundleTable(create(name, TableType.SOURCE, null),
                schema(List.of(column("id", QueryFieldType.STRING), column("score", QueryFieldType.LONG)), List.of("id")));
    }

    private static BundleTable enrichment(String name, String source) {
        return new BundleTable(create(name, TableType.ENRICHMENT, source),
                new DefineSchemaRequest(List.of(column("sentiment", QueryFieldType.STRING)), null, null, "id", null, null,
                        null, null, null));
    }

    private static CreateTableRequest create(String name, TableType type, String source) {
        return new CreateTableRequest(name, "desc", type, null, null, null, null, null, source, null, null, null, null);
    }

    private static DefineSchemaRequest schema(List<ColumnRequest> columns, List<String> orderingKey) {
        return new DefineSchemaRequest(columns, orderingKey, null, null, null, null, null, null, null);
    }

    private static ColumnRequest column(String name, QueryFieldType type) {
        return new ColumnRequest(name, name, type, null, null, false, null, null, null, false, false);
    }

    private static BundleTable withColumnDescription(BundleTable table, String description) {
        final List<ColumnRequest> columns = new ArrayList<>();
        for (final ColumnRequest col : table.schema().columns()) {
            columns.add(new ColumnRequest(col.sourceName(), col.name(), col.type(), col.elementType(), col.enumValues(),
                    col.nullable(), description, col.displayName(), col.tag(), col.sensitive(), col.heavy()));
        }
        return new BundleTable(table.table(), schema(columns, table.schema().orderingKey()));
    }

    private static ImportTarget.Table existing(BundleTable table) {
        return existing(table, TableStatus.ACTIVE);
    }

    private static ImportTarget.Table existing(BundleTable table, TableStatus status) {
        return new ImportTarget.Table(table.table().name(), table.table().type(), false, status, table, table);
    }

    private static ImportTarget.Table system() {
        return new ImportTarget.Table(SYSTEM, TableType.SOURCE, true, TableStatus.ACTIVE, null, null);
    }

    private static CatalogBundle requiring(String systemTable) {
        return new CatalogBundle(CatalogBundle.FORMAT, 1, null, List.of(), List.of(systemTable), List.of(),
                List.of());
    }

    private static BundlePipeline aggregate(String name, String input, String target) {
        return new BundlePipeline(new BundlePipeline.Declaration(name, PipelineKind.AGGREGATE, target, List.of(input),
                null, new TriggerRequest("schedule", "0 * * * * *", null, null, null), null,
                List.of(new MeasureRequest("n", "count", null, null, null)), null, null, null), true);
    }

    private static BundlePipeline sqlAggregate(String name, String target, String sql) {
        return new BundlePipeline(new BundlePipeline.Declaration(name, PipelineKind.AGGREGATE, target, null, null,
                new TriggerRequest("schedule", "0 * * * * *", null, null, null), null, null, sql, null, null), false);
    }

    private static BundlePipeline enrich(String name, String target) {
        return new BundlePipeline(new BundlePipeline.Declaration(name, PipelineKind.ENRICH, target, null, null,
                new TriggerRequest("schedule", "0 * * * * *", null, null, null), null, null, null, null, null), false);
    }
}
