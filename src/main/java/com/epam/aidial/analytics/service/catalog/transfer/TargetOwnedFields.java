package com.epam.aidial.analytics.service.catalog.transfer;

import com.epam.aidial.analytics.service.enrichment.model.TransformPreset;
import com.epam.aidial.analytics.service.enrichment.model.TransformType;
import com.epam.aidial.analytics.web.dto.catalog.BundlePipeline;
import com.epam.aidial.analytics.web.dto.catalog.BundleTable;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineRequests.AdvancedRequest;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineRequests.PatchTransformRequest;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineRequests.TriggerRequest;
import com.epam.aidial.analytics.web.dto.table.TtlRequest;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * The environment-specific fields whose value belongs to the target: the one list both the {@code env_specific} report
 * and the {@code OVERWRITE} update read, so a field an update keeps is always a field the report shows.
 *
 * <p>A field is target-owned when its right value depends on the environment — its deployments, capacity, cost or
 * retention — and changing it does not change what a row means: every {@code advanced} member, the schedule's
 * {@code trigger.cron} (not the trigger's kind, group key, readiness or member selection, which decide what is grouped
 * and when), the deployment binding {@code transform.preset} / {@code model} / {@code route}, and a table's {@code ttl}.
 * The binding is one unit, because {@code route} is required with {@code dial_app_route} and refused otherwise: the
 * target's binding is kept while the bundle's preset is the same, and the bundle's whole binding taken when it is not.
 */
final class TargetOwnedFields {

    /** The deployment binding's fields, which are kept or taken together. */
    static final List<String> BINDING = List.of("transform.preset", "transform.model", "transform.route");

    /** One target-owned pipeline field. */
    record Field(String path, Function<BundlePipeline.Declaration, Object> reader) {

        Object read(BundlePipeline.Declaration declaration) {
            return declaration == null ? null : reader.apply(declaration);
        }

        boolean binding() {
            return BINDING.contains(path);
        }
    }

    /** Every target-owned pipeline field, in report order. */
    static final List<Field> PIPELINE = List.of(
            new Field("transform.preset", d -> d.transform() == null ? null : d.transform().preset()),
            new Field("transform.model", d -> d.transform() == null ? null : d.transform().model()),
            new Field("transform.route", d -> d.transform() == null ? null : d.transform().route()),
            new Field("advanced.rows_per_scan", d -> d.advanced() == null ? null : d.advanced().rowsPerScan()),
            new Field("advanced.rows_per_call", d -> d.advanced() == null ? null : d.advanced().rowsPerCall()),
            new Field("advanced.rate_rpm", d -> d.advanced() == null ? null : d.advanced().rateRpm()),
            new Field("advanced.sample_fraction", d -> d.advanced() == null ? null : d.advanced().sampleFraction()),
            new Field("advanced.input_token_budget", d -> d.advanced() == null ? null : d.advanced().inputTokenBudget()),
            new Field("trigger.cron", d -> d.trigger() == null ? null : d.trigger().cron()));

    /** The target-owned table field. */
    static final String TABLE_TTL = "ttl";

    private TargetOwnedFields() {
    }

    /** A table's TTL as the report spells it, or null when it has none. */
    static String ttl(BundleTable table) {
        final TtlRequest ttl = table == null ? null : table.schema().ttl();
        return ttl == null || ttl.isEmpty() ? null : ttl.interval() + " " + ttl.unit();
    }

    /**
     * Whether an update keeps the target's deployment binding: both transforms call a deployment through the same preset.
     * A {@code sql} transform has no binding, and an absent preset is the default {@code chat_completion}.
     */
    static boolean bindingKept(BundlePipeline.Declaration bundle, BundlePipeline.Declaration target) {
        final PatchTransformRequest bundled = bundle.transform();
        final PatchTransformRequest stored = target.transform();
        if (bundled == null || stored == null || bundled.type() == TransformType.SQL || stored.type() == TransformType.SQL) {
            return false;
        }
        return effectivePreset(bundled) == effectivePreset(stored);
    }

    /** Whether the target's trigger cron replaces the bundle's: both triggers are of one kind and the target's has a cron. */
    static boolean cronKept(BundlePipeline.Declaration bundle, BundlePipeline.Declaration target) {
        final TriggerRequest bundled = bundle.trigger();
        final TriggerRequest stored = target.trigger();
        return bundled != null && stored != null && stored.cron() != null && Objects.equals(bundled.kind(), stored.kind());
    }

    /** Whether an update keeps the target's value of {@code field} rather than taking the bundle's. */
    static boolean kept(Field field, BundlePipeline.Declaration bundle, BundlePipeline.Declaration target) {
        if (field.binding()) {
            return bindingKept(bundle, target);
        }
        if ("trigger.cron".equals(field.path())) {
            return cronKept(bundle, target);
        }
        return bundle.kind() == target.kind();
    }

    /**
     * {@code bundle} with the target's target-owned members in place of its own: the declaration an {@code OVERWRITE}
     * update writes. The whole {@code advanced} block is the target's, so a member the target leaves unset stays unset.
     */
    static BundlePipeline.Declaration withTargetOwned(BundlePipeline.Declaration bundle, BundlePipeline.Declaration target) {
        final AdvancedRequest advanced = bundle.kind() == target.kind() ? target.advanced() : bundle.advanced();
        TriggerRequest trigger = bundle.trigger();
        if (cronKept(bundle, target)) {
            trigger = new TriggerRequest(trigger.kind(), target.trigger().cron(), trigger.groupBy(), trigger.readyWhen(),
                    trigger.memberSelect());
        }
        PatchTransformRequest transform = bundle.transform();
        if (bindingKept(bundle, target)) {
            final PatchTransformRequest stored = target.transform();
            transform = new PatchTransformRequest(transform.type(), stored.preset(), stored.model(), stored.route(),
                    transform.params(), transform.requestTemplate(), transform.inputs(), transform.outputs(),
                    transform.outputVars(), transform.responseSchema());
        }
        return new BundlePipeline.Declaration(bundle.name(), bundle.kind(), bundle.target(), bundle.inputs(), bundle.filter(),
                trigger, bundle.groupBy(), bundle.measures(), bundle.sql(), transform, advanced);
    }

    private static TransformPreset effectivePreset(PatchTransformRequest transform) {
        return transform.preset() == null ? TransformPreset.CHAT_COMPLETION : transform.preset();
    }
}
