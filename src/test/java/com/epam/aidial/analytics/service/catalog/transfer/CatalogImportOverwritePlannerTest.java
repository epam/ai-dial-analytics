package com.epam.aidial.analytics.service.catalog.transfer;

import com.epam.aidial.analytics.configuration.JsonMapperConfiguration;
import com.epam.aidial.analytics.service.enrichment.model.TransformPreset;
import com.epam.aidial.analytics.service.enrichment.model.TransformType;
import com.epam.aidial.analytics.service.pipeline.PipelineKind;
import com.epam.aidial.analytics.service.table.TableStatus;
import com.epam.aidial.analytics.service.table.TableType;
import com.epam.aidial.analytics.service.table.TableWriteMode;
import com.epam.aidial.analytics.service.table.TtlUnit;
import com.epam.aidial.analytics.web.dto.catalog.BundlePipeline;
import com.epam.aidial.analytics.web.dto.catalog.BundleTable;
import com.epam.aidial.analytics.web.dto.catalog.CatalogBundle;
import com.epam.aidial.analytics.web.dto.catalog.EnvSpecificValue;
import com.epam.aidial.analytics.web.dto.catalog.ImportAction;
import com.epam.aidial.analytics.web.dto.catalog.ImportEffect;
import com.epam.aidial.analytics.web.dto.catalog.NotApplied;
import com.epam.aidial.analytics.web.dto.catalog.PlannedChange;
import com.epam.aidial.analytics.web.dto.catalog.ResolutionPolicy;
import com.epam.aidial.analytics.web.dto.enrichment.OutputSpec;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineRequests.AdvancedRequest;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineRequests.MeasureRequest;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineRequests.PatchTransformRequest;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineRequests.TriggerRequest;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import com.epam.aidial.analytics.web.dto.table.CreateTableRequest;
import com.epam.aidial.analytics.web.dto.table.CreateTableRequest.ColumnRequest;
import com.epam.aidial.analytics.web.dto.table.DefineSchemaRequest;
import com.epam.aidial.analytics.web.dto.table.TtlRequest;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** The {@code OVERWRITE} diff the planner records, against hand-built target snapshots: no repository, no Spring. */
class CatalogImportOverwritePlannerTest {

    private final CatalogImportPlanner planner = new CatalogImportPlanner(new JsonMapperConfiguration().jsonMapper());

    // --- tables ------------------------------------------------------------------------------------------------------

    @Test
    void newColumnAndChangedDescriptionsAreUpdates() {
        final BundleTable stored = source(column("id"), column("score"));
        final BundleTable bundled = source(column("id"), described(column("score"), "new"), column("client_version"));

        final PlannedObject planned = overwrite(stored, bundled);

        assertThat(planned.action()).isEqualTo(ImportAction.UPDATE);
        assertThat(planned.changes()).containsExactly(
                new PlannedChange(PlannedChange.Op.UPDATE_COLUMN, "score", List.of("description")),
                new PlannedChange(PlannedChange.Op.ADD_COLUMN, "client_version", List.of()));
        assertThat(planned.toEntry().notApplied()).isEmpty();
    }

    @Test
    void tableMetadataIsAnUpdate() {
        final BundleTable stored = source(column("id"));
        final BundleTable bundled = new BundleTable(create("t", TableType.SOURCE, null, "other", List.of("identity")),
                stored.schema());

        assertThat(overwrite(stored, bundled).changes()).containsExactly(
                new PlannedChange(PlannedChange.Op.UPDATE_TABLE, null, List.of("description", "tag_order")));
    }

    @Test
    void columnOnlyTheTargetHasIsKept() {
        final PlannedObject planned = overwrite(source(column("id"), column("local_note")), source(column("id")));

        assertThat(planned.action()).isEqualTo(ImportAction.SKIP);
        assertThat(planned.toEntry().differs()).isTrue();
        assertThat(planned.changes()).isEmpty();
        assertThat(planned.toEntry().notApplied()).extracting(NotApplied::kind, NotApplied::name)
                .containsExactly(tuple(NotApplied.Kind.EXTRA_COLUMN, "local_note"));
    }

    @Test
    void orderOnlyDifferenceIsSkip() {
        final PlannedObject planned = overwrite(source(column("id"), column("a"), column("b")),
                source(column("id"), column("b"), column("a")));

        assertThat(planned.action()).isEqualTo(ImportAction.SKIP);
        assertThat(planned.toEntry().notApplied()).extracting(NotApplied::kind).containsExactly(NotApplied.Kind.COLUMN_ORDER);
    }

    @Test
    void columnAddedMidwayConvergesWithKeptOrder() {
        final PlannedObject planned = overwrite(source(column("id"), column("b")),
                source(column("id"), column("a"), column("b")));

        assertThat(planned.action()).isEqualTo(ImportAction.UPDATE);
        assertThat(planned.toEntry().notApplied()).extracting(NotApplied::kind).containsExactly(NotApplied.Kind.COLUMN_ORDER);
    }

    @Test
    void sensitivityIsRaisedButNeverCleared() {
        final PlannedObject planned = overwrite(source(column("id"), column("a"), sensitive(column("b"), true)),
                source(column("id"), sensitive(column("a"), true), column("b")));

        assertThat(planned.changes()).containsExactly(
                new PlannedChange(PlannedChange.Op.UPDATE_COLUMN, "a", List.of("sensitive")));
        assertThat(planned.toEntry().notApplied()).extracting(NotApplied::kind, NotApplied::name)
                .containsExactly(tuple(NotApplied.Kind.SENSITIVE_LOOSENING, "b"));
    }

    @Test
    void eachFrozenColumnMemberFails() {
        final ColumnRequest base = column("a");
        final List<UnaryOperator<ColumnRequest>> changes = List.of(
                c -> new ColumnRequest("a_physical", c.name(), c.type(), c.elementType(), c.enumValues(), c.nullable(),
                        c.description(), c.displayName(), c.tag(), c.sensitive(), c.heavy()),
                c -> new ColumnRequest(c.sourceName(), c.name(), QueryFieldType.LONG, c.elementType(), c.enumValues(),
                        c.nullable(), c.description(), c.displayName(), c.tag(), c.sensitive(), c.heavy()),
                c -> new ColumnRequest(c.sourceName(), c.name(), c.type(), c.elementType(), c.enumValues(), true,
                        c.description(), c.displayName(), c.tag(), c.sensitive(), c.heavy()),
                c -> new ColumnRequest(c.sourceName(), c.name(), c.type(), c.elementType(), List.of("x"), c.nullable(),
                        c.description(), c.displayName(), c.tag(), c.sensitive(), c.heavy()));
        for (final UnaryOperator<ColumnRequest> change : changes) {
            final PlannedObject planned = overwrite(source(column("id"), base), source(column("id"), change.apply(base)));
            assertThat(planned.action()).isEqualTo(ImportAction.FAIL);
            assertThat(planned.problems().getFirst()).contains("column 'a'").contains("cannot be changed by an import");
        }
    }

    @Test
    void frozenTableKeysFail() {
        final BundleTable stored = source(column("id"), column("score"));
        final BundleTable rekeyed = new BundleTable(stored.table(),
                new DefineSchemaRequest(stored.schema().columns(), List.of("score"), null, null, null, null, null, null, null));

        final PlannedObject planned = overwrite(stored, rekeyed);

        assertThat(planned.action()).isEqualTo(ImportAction.FAIL);
        assertThat(planned.problems().getFirst()).startsWith("ordering_key of table 't'");
    }

    @Test
    void ttlIsKept() {
        final BundleTable stored = withTtl(source(column("id"), column("ts")), 30);
        final BundleTable bundled = withTtl(source(column("id"), column("ts")), 365);

        final ImportPlan plan = plan(bundle(List.of(bundled), List.of()), ResolutionPolicy.OVERWRITE,
                target(Map.of("t", existing(stored)), Map.of(), Map.of()));
        final PlannedObject planned = plan.tables().getFirst();

        assertThat(planned.action()).isEqualTo(ImportAction.SKIP);
        assertThat(planned.toEntry().notApplied()).extracting(NotApplied::name).containsExactly("ttl");
        assertThat(plan.envSpecific()).containsExactly(new EnvSpecificValue("table", "t", "ttl", "365 " + TtlUnit.DAY, "30 " + TtlUnit.DAY, true));
    }

    @Test
    void ttlOverAnotherColumnIsKept() {
        final PlannedObject planned = overwrite(withTtl(source(column("id"), column("ts"), column("seen")), "ts", 30),
                withTtl(source(column("id"), column("ts"), column("seen")), "seen", 30));

        assertThat(planned.action()).isEqualTo(ImportAction.SKIP);
        assertThat(planned.toEntry().notApplied()).extracting(NotApplied::kind, NotApplied::name)
                .containsExactly(tuple(NotApplied.Kind.TARGET_OWNED, "ttl"));
    }

    @Test
    void hiddenColumnFailsOnlyWhenThereAreChanges() {
        final BundleTable stored = source(column("id"), sensitive(column("pii"), true));
        final BundleTable visible = source(column("id"));
        final ImportTarget.Table hidden = new ImportTarget.Table("t", TableType.SOURCE, false, TableStatus.ACTIVE, stored,
                visible, Map.of(), false);

        final PlannedObject changed = plan(bundle(List.of(new BundleTable(create("t", TableType.SOURCE, null, "changed", null),
                stored.schema())), List.of()), ResolutionPolicy.OVERWRITE, target(Map.of("t", hidden), Map.of(), Map.of()))
                .tables().getFirst();
        final PlannedObject same = plan(bundle(List.of(stored), List.of()), ResolutionPolicy.OVERWRITE,
                target(Map.of("t", hidden), Map.of(), Map.of())).tables().getFirst();

        assertThat(changed.action()).isEqualTo(ImportAction.FAIL);
        assertThat(changed.problems().getFirst()).contains("needs every column of it visible to the caller");
        assertThat(same.action()).isEqualTo(ImportAction.SKIP);
    }

    @Test
    void anEnrichmentColumnChangeBumpsItsPipeline() {
        final BundleTable stored = enrichment(column("label"));
        final BundleTable bundled = enrichment(described(column("label"), "new"), column("extra"));

        final PlannedObject planned = plan(bundle(List.of(bundled), List.of()), ResolutionPolicy.OVERWRITE,
                target(Map.of("e", existing(stored)), Map.of(), Map.of("e", "e-live"))).tables().getFirst();

        assertThat(planned.toEntry().effects()).containsExactly(new ImportEffect(ImportEffect.Kind.GENERATION_BUMP, "e-live"));
    }

    @Test
    void skipIfExistsListsWhatOverwriteWouldChange() {
        final BundleTable stored = source(column("id"));
        final BundleTable bundled = source(column("id"), column("added"));

        final PlannedObject planned = plan(bundle(List.of(bundled), List.of()), ResolutionPolicy.SKIP_IF_EXISTS,
                target(Map.of("t", existing(stored)), Map.of(), Map.of())).tables().getFirst();

        assertThat(planned.action()).isEqualTo(ImportAction.SKIP);
        assertThat(planned.toEntry().changes()).extracting(PlannedChange::op).containsExactly(PlannedChange.Op.ADD_COLUMN);
    }

    @Test
    void failIfExistsListsNothing() {
        final PlannedObject planned = plan(bundle(List.of(source(column("id"), column("added"))), List.of()),
                ResolutionPolicy.FAIL_IF_EXISTS, target(Map.of("t", existing(source(column("id")))), Map.of(), Map.of()))
                .tables().getFirst();

        assertThat(planned.isConflict()).isTrue();
        assertThat(planned.toEntry().changes()).isEmpty();
    }

    // --- pipelines ---------------------------------------------------------------------------------------------------

    @Test
    void changedOutputExpressionRedeclaresAnEnrichPipelineInPlace() {
        final BundlePipeline stored = enrich(llm(TransformPreset.CHAT_COMPLETION, "model-a", null, "old"), null, true);
        final BundlePipeline bundled = enrich(llm(TransformPreset.CHAT_COMPLETION, "model-a", null, "new"), null, false);

        final PlannedObject planned = overwrite(stored, bundled);

        assertThat(planned.action()).isEqualTo(ImportAction.UPDATE);
        assertThat(planned.changes()).containsExactly(
                new PlannedChange(PlannedChange.Op.REDECLARE, null, List.of("transform.outputs")));
        assertThat(planned.toEntry().effects()).extracting(ImportEffect::kind)
                .containsExactly(ImportEffect.Kind.GENERATION_BUMP, ImportEffect.Kind.EXISTING_ROWS_NOT_REDERIVED);
    }

    @Test
    void enabledAggregateIsDisabledAndLeftForReArming() {
        final PlannedObject planned = overwrite(aggregate("a > 1", true), aggregate("a > 2", false));

        assertThat(planned.changes()).extracting(PlannedChange::op)
                .containsExactly(PlannedChange.Op.DISABLE, PlannedChange.Op.REDECLARE);
        assertThat(planned.toEntry().effects()).extracting(ImportEffect::kind).containsExactly(
                ImportEffect.Kind.GENERATION_BUMP, ImportEffect.Kind.REBUILD_FROM_ZERO, ImportEffect.Kind.REARM_REQUIRED);
    }

    @Test
    void disabledAggregateIsRedeclaredWithoutDisable() {
        final PlannedObject planned = overwrite(aggregate("a > 1", false), aggregate("a > 2", false));

        assertThat(planned.changes()).extracting(PlannedChange::op).containsExactly(PlannedChange.Op.REDECLARE);
        assertThat(planned.toEntry().effects()).extracting(ImportEffect::kind)
                .containsExactly(ImportEffect.Kind.GENERATION_BUMP, ImportEffect.Kind.REBUILD_FROM_ZERO);
    }

    @Test
    void everyTargetOwnedMemberIsKept() {
        final BundlePipeline stored = enrich(llm(TransformPreset.CHAT_COMPLETION, "model-b", null, "same"),
                new AdvancedRequest(100, 1, 20, 0.5, null), true, "group", "0 */30 * * * *");
        final BundlePipeline bundled = enrich(llm(TransformPreset.CHAT_COMPLETION, "model-a", null, "same"),
                new AdvancedRequest(500, 2, 60, 1.0, 8000), false, "group", "0 */10 * * * *");

        final ImportPlan plan = plan(bundle(List.of(), List.of(bundled)), ResolutionPolicy.OVERWRITE,
                target(Map.of(), Map.of("p", stored), Map.of()));
        final PlannedObject planned = plan.pipelines().getFirst();

        assertThat(planned.action()).isEqualTo(ImportAction.SKIP);
        assertThat(planned.toEntry().notApplied()).extracting(NotApplied::name).containsExactlyInAnyOrder(
                "transform.model", "advanced.rows_per_scan", "advanced.rows_per_call", "advanced.rate_rpm",
                "advanced.sample_fraction", "advanced.input_token_budget", "trigger.cron");
        assertThat(plan.envSpecific()).filteredOn(value -> value.field().startsWith("advanced.")).hasSize(5)
                .allMatch(EnvSpecificValue::kept);
        assertThat(plan.envSpecific()).filteredOn(value -> "advanced.input_token_budget".equals(value.field()))
                .singleElement().satisfies(value -> {
                    assertThat(value.value()).isEqualTo("8000");
                    assertThat(value.targetValue()).isNull();
                });
    }

    @Test
    void anUnsetTargetAdvancedMemberStaysUnsetWhenTheDeclarationChanges() {
        final BundlePipeline stored = enrich(llm(TransformPreset.CHAT_COMPLETION, "m", null, "old"),
                new AdvancedRequest(null, null, 20, null, null), true);
        final BundlePipeline bundled = enrich(llm(TransformPreset.CHAT_COMPLETION, "m", null, "new"),
                new AdvancedRequest(500, null, 60, null, null), false);

        final PlannedObject planned = overwrite(stored, bundled);

        assertThat(planned.redeclaration().advanced()).isEqualTo(new AdvancedRequest(null, null, 20, null, null));
        assertThat(planned.redeclaration().transform().outputs().get("label").text()).isEqualTo("new");
    }

    @Test
    void triggerGroupKeyAndParamsFollowTheBundle() {
        final BundlePipeline stored = enrich(withParams(llm(TransformPreset.CHAT_COMPLETION, "m", null, "same"),
                Map.of("temperature", 0)), null, false);
        final BundlePipeline bundled = enrich(withParams(llm(TransformPreset.CHAT_COMPLETION, "m", null, "same"),
                Map.of("temperature", 1)), null, false);

        final PlannedObject planned = overwrite(stored, bundled);

        assertThat(planned.changes()).containsExactly(
                new PlannedChange(PlannedChange.Op.REDECLARE, null, List.of("transform.params")));
    }

    @Test
    void changedPresetTakesTheBundlesWholeBinding() {
        final BundlePipeline stored = enrich(llm(TransformPreset.CHAT_COMPLETION, "model-b", null, "same"), null, false);
        final BundlePipeline bundled = enrich(llm(TransformPreset.DIAL_APP_ROUTE, "insights-app", "insights", "same"),
                null, false);

        final ImportPlan plan = plan(bundle(List.of(), List.of(bundled)), ResolutionPolicy.OVERWRITE,
                target(Map.of("e", existing(enrichment(column("label")))), Map.of("p", stored), Map.of("e", "p")));
        final PlannedObject planned = plan.pipelines().getFirst();

        assertThat(planned.action()).isEqualTo(ImportAction.UPDATE);
        assertThat(planned.redeclaration().transform().model()).isEqualTo("insights-app");
        assertThat(planned.redeclaration().transform().route()).isEqualTo("insights");
        assertThat(planned.changes().getFirst().fields())
                .containsExactly("transform.preset", "transform.model", "transform.route");
        assertThat(plan.envSpecific()).filteredOn(value -> value.field().startsWith("transform."))
                .extracting(EnvSpecificValue::field, EnvSpecificValue::kept)
                .containsExactly(tuple("transform.preset", false),
                        tuple("transform.model", false),
                        tuple("transform.route", false));
    }

    @Test
    void samePresetKeepsTheTargetsModel() {
        final BundlePipeline stored = enrich(llm(TransformPreset.CHAT_COMPLETION, "model-b", null, "old"), null, false);
        final BundlePipeline bundled = enrich(llm(null, "model-a", null, "new"), null, false);

        final PlannedObject planned = overwrite(stored, bundled);

        assertThat(planned.redeclaration().transform().model()).isEqualTo("model-b");
        assertThat(planned.changes().getFirst().fields()).containsExactly("transform.outputs");
    }

    @Test
    void changedKindOrTargetFails() {
        final BundlePipeline stored = aggregate("a > 1", false);
        final BundlePipeline.Declaration d = stored.pipeline();
        final BundlePipeline retargeted = new BundlePipeline(new BundlePipeline.Declaration(d.name(), d.kind(), "other",
                d.inputs(), d.filter(), d.trigger(), d.groupBy(), d.measures(), d.sql(), d.transform(), d.advanced()), false);

        final PlannedObject planned = overwrite(stored, retargeted);

        assertThat(planned.action()).isEqualTo(ImportAction.FAIL);
        assertThat(planned.problems().getFirst()).startsWith("target of pipeline 'p'");
    }

    @Test
    void newPipelineListsEveryTargetOwnedFieldItSets() {
        final ImportPlan plan = plan(bundle(List.of(), List.of(enrich(llm(TransformPreset.CHAT_COMPLETION, "m", null, "x"),
                new AdvancedRequest(500, null, null, 0.5, null), false))), ResolutionPolicy.OVERWRITE,
                target(Map.of(), Map.of(), Map.of()));

        assertThat(plan.envSpecific()).extracting(EnvSpecificValue::field).containsExactly("transform.preset",
                "transform.model", "advanced.rows_per_scan", "advanced.sample_fraction", "trigger.cron");
        assertThat(plan.envSpecific()).noneMatch(EnvSpecificValue::kept);
    }

    // --- what a caller who cannot see the object is told -------------------------------------------------------------

    @Test
    void tableTheCallerMayNotGetReportsNothingItWouldChange() {
        final BundleTable stored = withTtl(source(column("id"), column("ts"), sensitive(column("ssn"), true)), 30);
        final BundleTable bundled = source(column("id"), column("ts"), column("added"));

        final ImportPlan plan = plan(bundle(List.of(bundled), List.of()), ResolutionPolicy.SKIP_IF_EXISTS,
                target(Map.of("t", existing(stored, null, false)), Map.of(), Map.of()));
        final PlannedObject planned = plan.tables().getFirst();

        assertThat(planned.toEntry().changes()).isEmpty();
        assertThat(planned.toEntry().notApplied()).isEmpty();
        assertThat(plan.envSpecific()).singleElement().satisfies(value -> assertThat(value.targetValue()).isNull());
    }

    @Test
    void refusalOnTableTheCallerMayNotGetNamesNoStoredValueAndAsksForUpdatePrivileges() {
        final BundleTable stored = source(column("id"), column("ssn"));
        final BundleTable retyped = source(column("id"), typed(column("ssn"), QueryFieldType.LONG));

        final PlannedObject planned = plan(bundle(List.of(retyped), List.of()), ResolutionPolicy.OVERWRITE,
                target(Map.of("t", existing(stored, null, false)), Map.of(), Map.of())).tables().getFirst();

        assertThat(planned.action()).isEqualTo(ImportAction.FAIL);
        assertThat(planned.intendsUpdate()).isTrue();
        assertThat(planned.problems()).singleElement().asString().contains("the caller may not read it")
                .doesNotContain("ssn").doesNotContain("STRING");
    }

    @Test
    void hiddenColumnIsNeitherListedNorEchoed() {
        final BundleTable stored = source(column("id"), sensitive(column("ssn"), true), column("local"));
        final BundleTable visible = source(column("id"), column("local"));
        final BundleTable retyped = source(column("id"), sensitive(typed(column("ssn"), QueryFieldType.LONG), true));

        final PlannedObject planned = plan(bundle(List.of(retyped), List.of()), ResolutionPolicy.OVERWRITE,
                target(Map.of("t", existing(stored, visible, false)), Map.of(), Map.of())).tables().getFirst();

        assertThat(planned.action()).isEqualTo(ImportAction.FAIL);
        assertThat(planned.toEntry().notApplied()).extracting(NotApplied::name).containsExactly("local");
        assertThat(planned.problems()).singleElement().asString().contains("hidden from the caller")
                .doesNotContain("STRING");
    }

    @Test
    void pipelineTheCallerMayNotReadReportsNoTargetValue() {
        final BundlePipeline stored = enrich(llm(TransformPreset.CHAT_COMPLETION, "m-internal", null, "old"),
                new AdvancedRequest(null, null, 20, null, null), true, "schedule", "0 */30 * * * *");
        final BundlePipeline bundled = enrich(llm(TransformPreset.CHAT_COMPLETION, "m", null, "new"), null, false);

        final ImportPlan plan = plan(bundle(List.of(), List.of(bundled)), ResolutionPolicy.SKIP_IF_EXISTS,
                target(Map.of(), Map.of("p", stored), Map.of(), false, Set.of(), Set.of()));
        final PlannedObject planned = plan.pipelines().getFirst();

        assertThat(planned.toEntry().changes()).isEmpty();
        assertThat(planned.toEntry().notApplied()).isEmpty();
        assertThat(plan.envSpecific()).filteredOn(value -> value.field().startsWith("transform.")
                || value.field().startsWith("advanced.") || value.field().startsWith("trigger."))
                .isNotEmpty().allSatisfy(value -> assertThat(value.targetValue()).isNull());
    }

    @Test
    void columnAddedUnderColumnPolicyFails() {
        final BundleTable stored = source(column("id"), column("a"));

        final PlannedObject added = plan(bundle(List.of(source(column("id"), column("a"), column("x"))), List.of()),
                ResolutionPolicy.OVERWRITE, target(Map.of("t", existing(stored, stored, true)), Map.of(), Map.of()))
                .tables().getFirst();
        final PlannedObject described = plan(bundle(List.of(source(column("id"), described(column("a"), "new"))), List.of()),
                ResolutionPolicy.OVERWRITE, target(Map.of("t", existing(stored, stored, true)), Map.of(), Map.of()))
                .tables().getFirst();

        assertThat(added.action()).isEqualTo(ImportAction.FAIL);
        assertThat(added.problems().getFirst()).contains("column policy");
        assertThat(described.action()).isEqualTo(ImportAction.UPDATE);
    }

    // --- the bundle compared in stored form ---------------------------------------------------------------------------

    @Test
    void defaultsAndUntrimmedMetadataLeftImplicitConverge() {
        final BundleTable stored = source(column("id"), described(column("score"), "points"));
        final ColumnRequest implicit = new ColumnRequest("score", "score", QueryFieldType.STRING, null, null, null,
                "points ", " ", null, false, false);
        final BundleTable bundled = new BundleTable(stored.table(), new DefineSchemaRequest(List.of(column("id"), implicit),
                List.of("id"), null, null, null, null, null, null, null));

        final PlannedObject planned = overwrite(new BundleTable(new CreateTableRequest("t", "desc", TableType.SOURCE, null,
                TableWriteMode.APPEND, null, null, null, null, null, null, null, null),
                stored.schema()), bundled);

        assertThat(planned.action()).isEqualTo(ImportAction.SKIP);
        assertThat(planned.changes()).isEmpty();
    }

    @Test
    void anAggregateTriggerWithoutKindConverges() {
        final BundlePipeline stored = aggregate("a > 1", true);
        final BundlePipeline.Declaration d = stored.pipeline();
        final BundlePipeline implicit = new BundlePipeline(new BundlePipeline.Declaration(d.name(), d.kind(), d.target(),
                d.inputs(), d.filter(), new TriggerRequest(null, "0 * * * * *", null, null, null), d.groupBy(), d.measures(),
                d.sql(), d.transform(), d.advanced()), false);

        assertThat(overwrite(stored, implicit).action()).isEqualTo(ImportAction.SKIP);
    }

    @Test
    void reorderedOutputsRedeclare() {
        final LinkedHashMap<String, OutputSpec> first = new LinkedHashMap<>();
        first.put("summary", new OutputSpec("s", null));
        first.put("reasoning", new OutputSpec("r", null));
        final LinkedHashMap<String, OutputSpec> second = new LinkedHashMap<>();
        second.put("reasoning", new OutputSpec("r", null));
        second.put("summary", new OutputSpec("s", null));

        final PlannedObject planned = overwrite(enrich(withOutputs(llm(TransformPreset.CHAT_COMPLETION, "m", null, "x"), first),
                null, false), enrich(withOutputs(llm(TransformPreset.CHAT_COMPLETION, "m", null, "x"), second), null, false));

        assertThat(planned.action()).isEqualTo(ImportAction.UPDATE);
        assertThat(planned.changes()).containsExactly(
                new PlannedChange(PlannedChange.Op.REDECLARE, null, List.of("transform.outputs")));
    }

    // --- pipelines an update could not restore or would not apply ----------------------------------------------------

    @Test
    void budgetIsKeptOnlyWhileBothTriggersAreGroup() {
        final BundlePipeline stored = enrich(llm(TransformPreset.CHAT_COMPLETION, "m", null, "x"),
                new AdvancedRequest(null, null, 20, null, 8000), false, "group", null);
        final BundlePipeline bundled = enrich(llm(TransformPreset.CHAT_COMPLETION, "m", null, "x"), null, false,
                "schedule", "0 * * * * *");

        final ImportPlan plan = plan(bundle(List.of(), List.of(bundled)), ResolutionPolicy.OVERWRITE,
                target(Map.of(), Map.of("p", stored), Map.of()));
        final PlannedObject planned = plan.pipelines().getFirst();

        assertThat(planned.redeclaration().advanced()).isEqualTo(new AdvancedRequest(null, null, 20, null, null));
        assertThat(planned.toEntry().notApplied()).extracting(NotApplied::name).doesNotContain("advanced.input_token_budget");
        assertThat(plan.envSpecific()).filteredOn(value -> "advanced.input_token_budget".equals(value.field()))
                .singleElement().satisfies(value -> assertThat(value.kept()).isFalse());
    }

    @Test
    void bareDeclarationOrIncompleteDraftIsNotUpdated() {
        final BundlePipeline complete = aggregate("a > 1", false);
        final BundlePipeline bare = new BundlePipeline(new BundlePipeline.Declaration("p", PipelineKind.AGGREGATE, "rollup",
                null, null, null, null, null, null, null, null), false);

        final PlannedObject clearing = overwrite(complete, bare);
        final PlannedObject completing = plan(bundle(List.of(), List.of(complete)), ResolutionPolicy.OVERWRITE,
                target(Map.of("rollup", existing(source(column("id")))), Map.of("p", aggregate("a > 2", false)),
                        Map.of("rollup", "p"), true, Set.of(), Set.of("p"))).pipelines().getFirst();

        assertThat(clearing.action()).isEqualTo(ImportAction.FAIL);
        assertThat(clearing.problems().getFirst()).contains("declares no member");
        assertThat(completing.action()).isEqualTo(ImportAction.FAIL);
        assertThat(completing.problems().getFirst()).contains("incomplete draft");
    }

    @Test
    void columnChangeBumpsNoClass1Pipeline() {
        final PlannedObject planned = plan(bundle(List.of(enrichment(column("label"), column("extra"))), List.of()),
                ResolutionPolicy.OVERWRITE, target(Map.of("e", existing(enrichment(column("label")))), Map.of(),
                        Map.of("e", "e-sql"), true, Set.of(), Set.of())).tables().getFirst();

        assertThat(planned.action()).isEqualTo(ImportAction.UPDATE);
        assertThat(planned.toEntry().effects()).isEmpty();
    }

    @Test
    void enrichmentUpdateOverRefusedSourceFails() {
        final BundleTable storedSource = new BundleTable(create("src", TableType.SOURCE, null, "desc", null),
                source(column("id"), column("score")).schema());
        final BundleTable retypedSource = new BundleTable(storedSource.table(),
                source(column("id"), typed(column("score"), QueryFieldType.LONG)).schema());

        final ImportPlan plan = plan(bundle(List.of(retypedSource, enrichment(described(column("label"), "new"))), List.of()),
                ResolutionPolicy.OVERWRITE, target(Map.of("src", existing(storedSource), "e", existing(enrichment(column("label")))),
                        Map.of(), Map.of()));

        assertThat(plan.tables()).extracting(PlannedObject::name, PlannedObject::action)
                .containsExactly(tuple("src", ImportAction.FAIL), tuple("e", ImportAction.FAIL));
        assertThat(plan.tables().get(1).problems()).anyMatch(problem -> problem.contains("source_table 'src'"));
    }

    // --- fixtures ----------------------------------------------------------------------------------------------------

    private PlannedObject overwrite(BundleTable stored, BundleTable bundled) {
        return plan(bundle(List.of(bundled), List.of()), ResolutionPolicy.OVERWRITE,
                target(Map.of(stored.table().name(), existing(stored)), Map.of(), Map.of())).tables().getFirst();
    }

    private PlannedObject overwrite(BundlePipeline stored, BundlePipeline bundled) {
        final String target = stored.pipeline().target();
        return plan(bundle(List.of(), List.of(bundled)), ResolutionPolicy.OVERWRITE,
                target(Map.of(target, existing(source(column("id")))), Map.of(stored.pipeline().name(), stored),
                        Map.of(target, stored.pipeline().name()))).pipelines().getFirst();
    }

    private ImportPlan plan(CatalogBundle bundle, ResolutionPolicy policy, ImportTarget target) {
        return planner.plan(bundle, false, policy, target, false);
    }

    private static CatalogBundle bundle(List<BundleTable> tables, List<BundlePipeline> pipelines) {
        return new CatalogBundle(CatalogBundle.FORMAT, CatalogBundle.FORMAT_VERSION, null, List.of(), List.of(), tables, pipelines);
    }

    /** A target on which every pipeline targeting a bundle table is an llm one, and every declaration complete. */
    private static ImportTarget target(Map<String, ImportTarget.Table> tables, Map<String, BundlePipeline> pipelines,
            Map<String, String> byTarget) {
        return target(tables, pipelines, byTarget, true, Set.copyOf(byTarget.values()), Set.of());
    }

    private static ImportTarget target(Map<String, ImportTarget.Table> tables, Map<String, BundlePipeline> pipelines,
            Map<String, String> byTarget, boolean pipelinesVisible, Set<String> bumped, Set<String> incomplete) {
        final Map<String, ImportTarget.Table> all = new LinkedHashMap<>(tables);
        all.putIfAbsent("src", existing(source(column("id"))));
        return new ImportTarget(all, pipelines, pipelinesVisible, byTarget, bumped, incomplete, Set.of(), all.size(), 100, 256);
    }

    private static ImportTarget.Table existing(BundleTable table) {
        return new ImportTarget.Table(table.table().name(), table.table().type(), false, TableStatus.ACTIVE, table, table,
                Map.of(), false);
    }

    /** A table the caller sees as {@code visible}, or not at all when it is null, under an optional column policy. */
    private static ImportTarget.Table existing(BundleTable table, BundleTable visible, boolean columnPolicy) {
        return new ImportTarget.Table(table.table().name(), table.table().type(), false, TableStatus.ACTIVE, table, visible,
                Map.of(), columnPolicy);
    }

    private static BundleTable source(ColumnRequest... columns) {
        return new BundleTable(create("t", TableType.SOURCE, null, "desc", null),
                new DefineSchemaRequest(List.of(columns), List.of("id"), null, null, null, null, null, null, null));
    }

    private static BundleTable enrichment(ColumnRequest... columns) {
        return new BundleTable(create("e", TableType.ENRICHMENT, "src", "desc", null),
                new DefineSchemaRequest(List.of(columns), null, null, "id", null, null, null, null, null));
    }

    private static BundleTable withTtl(BundleTable table, int days) {
        return withTtl(table, "ts", days);
    }

    private static BundleTable withTtl(BundleTable table, String column, int days) {
        final DefineSchemaRequest s = table.schema();
        return new BundleTable(table.table(), new DefineSchemaRequest(s.columns(), s.orderingKey(), s.partitionBy(),
                s.grainKey(), s.cardinality(), s.identityColumn(), s.versionColumn(), new TtlRequest(column, days, TtlUnit.DAY),
                s.write()));
    }

    private static CreateTableRequest create(String name, TableType type, String source, String description,
            List<String> tagOrder) {
        return new CreateTableRequest(name, description, type, null, null, null, null, null, source, null, null, null,
                tagOrder);
    }

    private static ColumnRequest column(String name) {
        return new ColumnRequest(name, name, QueryFieldType.STRING, null, null, false, null, null, null, false, false);
    }

    private static ColumnRequest described(ColumnRequest c, String description) {
        return new ColumnRequest(c.sourceName(), c.name(), c.type(), c.elementType(), c.enumValues(), c.nullable(),
                description, c.displayName(), c.tag(), c.sensitive(), c.heavy());
    }

    private static ColumnRequest typed(ColumnRequest c, QueryFieldType type) {
        return new ColumnRequest(c.sourceName(), c.name(), type, c.elementType(), c.enumValues(), c.nullable(),
                c.description(), c.displayName(), c.tag(), c.sensitive(), c.heavy());
    }

    private static ColumnRequest sensitive(ColumnRequest c, boolean value) {
        return new ColumnRequest(c.sourceName(), c.name(), c.type(), c.elementType(), c.enumValues(), c.nullable(),
                c.description(), c.displayName(), c.tag(), value, c.heavy());
    }

    private static PatchTransformRequest llm(TransformPreset preset, String model, String route, String output) {
        final LinkedHashMap<String, OutputSpec> outputs = new LinkedHashMap<>();
        outputs.put("label", new OutputSpec(output, null));
        return new PatchTransformRequest(TransformType.LLM, preset, model, route, null, "{}", null, outputs, null, null);
    }

    private static PatchTransformRequest withOutputs(PatchTransformRequest t, LinkedHashMap<String, OutputSpec> outputs) {
        return new PatchTransformRequest(t.type(), t.preset(), t.model(), t.route(), t.params(), t.requestTemplate(), t.inputs(),
                outputs, t.outputVars(), t.responseSchema());
    }

    private static PatchTransformRequest withParams(PatchTransformRequest t, Map<String, Object> params) {
        return new PatchTransformRequest(t.type(), t.preset(), t.model(), t.route(), params, t.requestTemplate(), t.inputs(),
                t.outputs(), t.outputVars(), t.responseSchema());
    }

    private static BundlePipeline enrich(PatchTransformRequest transform, AdvancedRequest advanced, boolean enabled) {
        return enrich(transform, advanced, enabled, "schedule", "0 * * * * *");
    }

    private static BundlePipeline enrich(PatchTransformRequest transform, AdvancedRequest advanced, boolean enabled,
            String triggerKind, String cron) {
        return new BundlePipeline(new BundlePipeline.Declaration("p", PipelineKind.ENRICH, "e", null, null,
                new TriggerRequest(triggerKind, cron, null, null, null), null, null, null, transform, advanced), enabled);
    }

    private static BundlePipeline aggregate(String filter, boolean enabled) {
        return new BundlePipeline(new BundlePipeline.Declaration("p", PipelineKind.AGGREGATE, "rollup", List.of("src"),
                filter, new TriggerRequest("schedule", "0 * * * * *", null, null, null), null,
                List.of(new MeasureRequest("n", "count", null, null, null)), null, null, null), enabled);
    }
}
