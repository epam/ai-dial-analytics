package com.epam.aidial.analytics.service.pipeline;

import com.epam.aidial.analytics.service.enrichment.model.EnrichDeclaration;
import com.epam.aidial.analytics.service.enrichment.validation.EnrichSpecValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The two freshness facts about a pipeline that are true <em>now</em> rather than as of its last run: which
 * enrichments it reads without a bound, and whether an enrichment's history moved under it.
 *
 * <p>Derived on read rather than stored, unlike the clamp. The clamp describes what one refresh actually
 * did, so nothing but that refresh can know it; these two describe the current state of the pipelines a
 * declaration points at, and storing them would mean a pipeline that has never run reports nothing, and one
 * that ran before an upstream declaration changed reports the old answer — in both cases exactly when an
 * operator is asking.
 *
 * <p>One component for both kinds, because it was the same class twice before the registries merged. Two
 * things do differ per kind, and both are decided here rather than by the caller:
 *
 * <ul>
 *   <li><b>Which enrichments the declaration reads</b> — the aggregate spec's group keys, measures and
 *       filter, or the enrich declaration's bindings, filter and transform expressions. Two validators, one
 *       question.
 *   <li><b>What "already absorbed" is anchored on.</b> A rollup's contents were built from wherever its
 *       cursor last started over, so {@code rebuilt_at} is the anchor. An ENRICH pipeline has no such
 *       anchor: the only instant ADAS records about its own progress is {@code drained_at} ("nothing left
 *       matching, as of when I looked"), so that is the anchor, and a pipeline that has never drained
 *       reports a re-derivation <em>unconditionally</em> — it has never confirmed it caught up, so it cannot
 *       claim to have absorbed anything, and staying silent would be the one failure this exists to
 *       prevent. Only Class-1 pipelines ever record a drained instant, so a runner-executed one always falls
 *       into that case. That is honest rather than precise: its write-through position is held by the runner
 *       and never reported, which is the same fact that makes it unclampable as an upstream.
 * </ul>
 *
 * <p><b>Reported, never acted on.</b> An enrichment whose history was re-derived invalidates the values
 * derived from it, and re-deriving them stays an operator action: the downstream of an {@code llm}
 * declaration is metered, so cascading over history would spend budget nobody approved, and doing it safely
 * would need ordering, a cost ceiling and a kill switch. Reporting the exposure is what lets an operator
 * decide.
 *
 * <p><b>Best-effort by design.</b> Working out which enrichments a declaration reads means resolving it
 * against the input's current schema, which can fail for a stored pipeline whose input has drifted. That is
 * not a reason to fail a state read: the pipeline is already reporting the drift through {@code last_error}
 * or through its next run, and answering {@code GET /v1/pipelines/{name}} with a validation error would hide
 * the very state being asked for. So a failure here degrades to "no freshness diagnostics" and is logged.
 *
 * <p><b>Caller-independent.</b> The resolution is deliberately not routed through the sensitive-column gate
 * — see {@link #referencedEnrichments(PipelineEntity)} for why that choice is load-bearing rather than
 * incidental. A pipeline read therefore reports the same diagnostics to every role entitled to make it,
 * and the degradation above is the only way the fields come back empty.
 *
 * <p>Note the asymmetry with the gate: a declaration reading an enrichment whose position cannot be
 * established at all is <em>refused</em> when it is armed, so an {@code unclampedReads} entry here is
 * normally a benign case — {@code no_pipeline}, or {@code undeclared_writer} for an enrichment whose only
 * writer is a draft whose declaration is still incomplete — or a pipeline armed before that refusal existed, or a
 * disabled draft that has never been gated. The {@code undeclared_writer} reason exists so a draft writer
 * is reported as what it is rather than as a writer ADAS does not execute.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineFreshnessDiagnostics {

    private final AggregateSpecValidator aggregateValidator;
    private final EnrichSpecValidator enrichValidator;
    private final PipelineSpecCodec specCodec;
    private final EnrichmentFreshness freshness;

    /** An enrichment the pipeline reads that contributes no bound, with the reason as a wire code. */
    public record UnclampedRead(String enrichment, String reason) {}

    /** An enrichment whose history was re-derived after this pipeline's output was last built. */
    public record Rebuild(String enrichment, Instant rederivedAt) {}

    /** What to report about one pipeline's freshness beyond its stored clamp. */
    public record Report(List<UnclampedRead> unclampedReads, Rebuild rebuild) {

        /** Nothing to report — also what a pipeline whose declaration cannot be resolved gets. */
        public static final Report NONE = new Report(List.of(), null);
    }

    /** The report for one pipeline. */
    @Transactional(readOnly = true)
    public Report of(PipelineEntity pipeline) {
        final Set<String> enrichments;
        final String input;
        try {
            enrichments = referencedEnrichments(pipeline);
            input = inputOf(pipeline);
        } catch (RuntimeException ex) {
            log.debug("Could not resolve the enrichments pipeline '{}' reads, so its freshness diagnostics "
                    + "are omitted: {}", pipeline.getName(), ex.getMessage());
            return Report.NONE;
        }
        if (enrichments.isEmpty() || input == null) {
            return Report.NONE;
        }
        final List<UnclampedRead> unclamped = new ArrayList<>();
        freshness.classify(enrichments, input).forEach((enrichment, clampability) -> {
            if (!clampability.clamps()) {
                unclamped.add(new UnclampedRead(enrichment, clampability.code()));
            }
        });
        return new Report(List.copyOf(unclamped), rebuildOf(pipeline, enrichments));
    }

    /** The reports for many pipelines, keyed by name — one resolution per pipeline, not per read of one. */
    @Transactional(readOnly = true)
    public Map<String, Report> of(List<PipelineEntity> pipelines) {
        final Map<String, Report> byName = new LinkedHashMap<>();
        pipelines.forEach(pipeline -> byName.put(pipeline.getName(), of(pipeline)));
        return byName;
    }

    /**
     * Which enrichments this pipeline's declaration reaches, resolved for <b>both</b> kinds from the
     * declaration's own column tokens rather than by re-running the SQL front end over it.
     *
     * <p>The aggregate branch once resolved through the front end, which analyses the {@code filter} behind
     * the sensitive-column gate. Two reasons it no longer does, and the first is a correctness bug rather
     * than a preference:
     *
     * <ul>
     *   <li>A gated resolution <b>throws</b> for a caller not entitled to a column the filter names.
     *       Because the resolver is {@code @Transactional}, its throw marks this method's transaction
     *       rollback-only — and the {@code catch} in {@link #of(PipelineEntity)} then swallows the
     *       exception only for the commit to fail with {@code UnexpectedRollbackException}, answering a
     *       state read with 500 instead of the degraded report the class contract promises. Both resolvers
     *       used here are documented not to throw, which is what makes that degradation work. Keep it that
     *       way: any resolver reached from here must swallow its own failure, not raise one.</li>
     *   <li>It makes the report independent of the caller's entitlement, so a pipeline read is the same
     *       for every role that may make it — which is what lets the read carry no per-role filtering.</li>
     * </ul>
     *
     * <p>Both kinds now resolve the same way: one answer per declaration whatever its kind.
     */
    private Set<String> referencedEnrichments(PipelineEntity pipeline) {
        return switch (pipeline.getKind()) {
            case AGGREGATE -> aggregateValidator.referencedEnrichments(PipelineDeclaration.of(pipeline));
            case ENRICH -> enrichValidator.referencedEnrichments(declarationOf(pipeline));
            case EXTERNAL -> Set.of();
        };
    }

    /**
     * The table this pipeline's read windows over — the axis a recorded position has to be on to mean
     * anything. An ENRICH pipeline may leave it unstated and follow its target enrichment's parent, so the
     * resolution goes through the gate rather than reading the column.
     */
    private String inputOf(PipelineEntity pipeline) {
        return pipeline.getKind() == PipelineKind.ENRICH
                ? enrichValidator.readSourceOf(declarationOf(pipeline)).orElse(null)
                : pipeline.getInputTable();
    }

    private EnrichDeclaration declarationOf(PipelineEntity pipeline) {
        return new EnrichDeclaration(pipeline, specCodec.readEnrich(pipeline));
    }

    /**
     * The first enrichment this pipeline reads whose history was re-derived after its own output was last
     * built, or null. The question is whether the values it has <b>already written</b> could have been
     * derived from inputs that have since changed underneath them — no future window revisits those rows,
     * because their source rows never moved.
     *
     * <p>See the class javadoc for why the anchor differs by kind, and why the ENRICH anchor is
     * conservative in both directions it can be wrong.
     */
    private Rebuild rebuildOf(PipelineEntity pipeline, Set<String> enrichments) {
        final boolean anchored;
        final Instant absorbedThrough;
        if (pipeline.getKind() == PipelineKind.ENRICH) {
            absorbedThrough = pipeline.getDrainedAt();
            anchored = true;
        } else {
            absorbedThrough = pipeline.getRebuiltAt();
            // A rollup with no rebuild anchor cannot be compared against one, and unlike the enrich case
            // there is no "never confirmed caught up" reading to fall back on.
            anchored = absorbedThrough != null;
        }
        if (!anchored) {
            return null;
        }
        for (final String enrichment : enrichments) {
            final Instant rederived = freshness.historyRederivedAt(enrichment).orElse(null);
            if (rederived != null && (absorbedThrough == null || rederived.isAfter(absorbedThrough))) {
                return new Rebuild(enrichment, rederived);
            }
        }
        return null;
    }
}
