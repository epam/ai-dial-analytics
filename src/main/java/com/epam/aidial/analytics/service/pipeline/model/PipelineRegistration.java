package com.epam.aidial.analytics.service.pipeline.model;

import com.epam.aidial.analytics.service.enrichment.model.MemberSelect;
import com.epam.aidial.analytics.service.enrichment.model.ReadyWhen;
import com.epam.aidial.analytics.service.enrichment.model.TriggerKind;
import com.epam.aidial.analytics.service.pipeline.PipelineKind;

/**
 * Service-layer input for registering or re-declaring a pipeline of either kind: its identity, the one
 * table it writes, the table it reads, its membership predicate, its trigger, and the kind's own
 * transform declaration.
 *
 * <p>Every member is common to both kinds except {@link #spec}, which <em>is</em> the per-kind half —
 * including, for an {@code enrich} declaration, the {@code transform} block holding what the pipeline runs.
 * A field belonging to the other kind never reaches this record: the wire layer rejects it with 422, which
 * is what makes one flat request shape safe.
 *
 * <p>{@link #enabled} is honoured only where no dry-run gate stands between declaring a pipeline and
 * letting it write. An {@code aggregate} pipeline is always stored disabled whatever this says, because
 * its preview gate is the reason the disabled state exists; an {@code enrich} pipeline has no preview
 * endpoint, so requiring a second call to enable one would add a round trip that gates nothing.
 *
 * <p>{@link #kind} is null on a re-declaration: it is fixed at registration (a different transform over
 * the same target is a different pipeline), so a patch carries the stored one over rather than restating
 * it.
 */
public record PipelineRegistration(
        String name,
        PipelineKind kind,
        String target,
        String inputTable,
        String filterSql,
        PipelineSpec spec,
        TriggerKind triggerKind,
        String triggerCron,
        String triggerGroupBy,
        ReadyWhen readyWhen,
        MemberSelect memberSelect,
        boolean enabled) {}
