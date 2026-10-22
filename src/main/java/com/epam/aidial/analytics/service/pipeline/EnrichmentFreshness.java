package com.epam.aidial.analytics.service.pipeline;

import com.epam.aidial.analytics.configuration.ScanProperties;
import com.epam.aidial.analytics.service.enrichment.model.EnrichDeclaration;
import com.epam.aidial.analytics.service.enrichment.model.TransformType;
import com.epam.aidial.analytics.service.enrichment.model.TriggerKind;
import com.epam.aidial.analytics.service.enrichment.support.CursorPredicates;
import com.epam.aidial.analytics.service.enrichment.validation.EnrichDeclarationCompleteness;
import com.epam.aidial.analytics.service.pipeline.model.EnrichSpec;
import com.epam.aidial.analytics.sync.mapping.SyncTarget;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * How far an enrichment's values can be trusted, and whether that question has an answer at all.
 *
 * <p>One component rather than logic at two call sites, because the aggregate path asks the same question
 * twice for different purposes and the two answers must not diverge: {@code AggregateSpecValidator} asks it
 * to refuse a declaration it could never keep sound, and {@code AggregateRefreshService} asks it to bound a
 * window. A declaration accepted under one reading and clamped under another would be worse than either.
 *
 * <p>Everything here is about the <b>Class-1</b> path, where ADAS runs the {@code INSERT … SELECT} itself
 * and therefore knows what it wrote. Nothing reports a position for any other shape, which is why
 * {@link Clampability} has more members than "yes" and "no".
 *
 * <p><b>Why it lives in {@code service.pipeline}</b> rather than beside the enrichment runtime it is named
 * for: what it reads is the pipeline registry — the position columns and the trigger kind of the pipeline
 * writing an enrichment. Left in {@code service.enrichment.support} while reading
 * {@link PipelineRepository}, it would make that package and this one depend on each other, a cycle that
 * exists in neither direction otherwise. Its callers are in both packages either way, so the deciding
 * argument is the data it owns, not the callers it has.
 */
@Component
@RequiredArgsConstructor
public class EnrichmentFreshness {

    private final PipelineRepository pipelines;
    private final PipelineSpecCodec specCodec;
    private final ScanProperties scanProperties;
    /**
     * The data-sync targets, to tell an enrichment nothing writes from one data-sync writes. The list bean
     * is the same one {@code BlobIngestor} fans records out to, so the two cannot disagree about what
     * data-sync populates.
     */
    private final List<SyncTarget> syncTargets;

    /** Whether a position can be established for an enrichment, and when not, why not. */
    public enum Clampability {

        /**
         * A Class-1 ENRICH pipeline on the reading pipeline's own input writes it; its recorded position
         * bounds the window.
         */
        CLAMPABLE(true),

        /**
         * Nothing writes this enrichment — no pipeline targets it and no data-sync mapping does. It imposes
         * no clamp: an unwritten enrichment will never gain another row, so its absent values are <b>final
         * rather than late</b> and there is nothing to wait for. Freezing every dependent rollup to protect
         * against data that cannot arrive would leave no operator action that released it.
         */
        NO_PIPELINE(false),

        /**
         * Data-sync writes it, in the same run as its source and — by {@code RunSink}'s flush order — never
         * after the source rows it belongs to become visible. No pipeline position exists to clamp to, and
         * none is needed: a source row the scan can see already has its enrichment row, so an absent value
         * here is final on the same terms as {@link #NO_PIPELINE}, for a different reason. Kept as its own
         * state so the diagnostics say what is true — the pre-payload {@code no_pipeline} rationale,
         * "nothing writes it", was false for a table data-sync writes continuously, and a false rationale on
         * a clamp decision is how the next reader talks themselves out of the invariant that holds it up.
         */
        INGEST_WRITTEN(false),

        /**
         * A group-triggered pipeline writes it, and here the position is not merely unavailable but
         * useless. A
         * group's members span arbitrary source positions, so the only sound position is the one below
         * which every group has settled — which one long-lived conversation pins at its earliest member
         * indefinitely. Worse, the value <em>changes</em> on every recompute by design, which no clamp
         * addresses. Refused, because admitting it produces a rollup that is silently and permanently wrong
         * in both directions.
         */
        GROUP_TRIGGER(false),

        /**
         * A pipeline ADAS does not execute itself writes it, so the write-through position is held by the
         * runner and never reported. Refused for the same reason as {@link #GROUP_TRIGGER}: no
         * establishable position. Class-1 is the only path where ADAS is the writer.
         */
        NOT_CLASS1(false),

        /**
         * The pipeline targeting it is a <b>disabled draft whose declaration is incomplete</b> — missing any
         * member its kind requires, the transform or its type included — so it writes nothing and there is
         * no question of what it will compute. Admitted, imposing no clamp, on the
         * same terms as {@link #NO_PIPELINE}: a draft is driven by nothing, so its absent values are final
         * until it is armed — and arming runs its own gate, after which this reading becomes
         * {@link #CLAMPABLE} or {@link #NOT_CLASS1} like any other writer.
         *
         * <p>Its own state rather than folded into {@link #NOT_CLASS1}, and the reason is the message. A
         * draft was otherwise classified as a writer ADAS does not execute — so saving a name for an
         * enrichment made every pipeline reading it refuse with "its evaluator is not 'sql'", a cause that
         * was not true of anything, and stopped an armed rollup refreshing because somebody began authoring
         * something next to it. A disabled draft whose declaration is <em>complete</em> is classified by
         * what it declares, as it would be once armed. An <em>enabled</em> pipeline with no transform keeps
         * {@link #NOT_CLASS1}: that is the pre-fold write signature, and it is refused rather than admitted.
         */
        UNDECLARED_WRITER(false),

        /**
         * The pipeline writing it reads a different table from the reading pipeline's input, so the position
         * it records is on that table's cursor axis and not on the one the read windows over. <b>Admitted,
         * but imposes no
         * clamp</b> — see {@link #bound} for why no translation exists — and reported so the rollup's
         * exposure is visible rather than assumed away.
         */
        FOREIGN_AXIS(false);

        private final boolean clamps;

        Clampability(boolean clamps) {
            this.clamps = clamps;
        }

        /** Whether an enrichment in this state contributes a bound to the clamp. */
        public boolean clamps() {
            return clamps;
        }

        /** Whether a declaration referencing such an enrichment must be refused outright. */
        public boolean refused() {
            return this == GROUP_TRIGGER || this == NOT_CLASS1;
        }

        /** The stable wire spelling, for a state read to report and a refusal to name. */
        public String code() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** Why an enrichment in this state reports no position, in one clause for an error message. */
        public String reason() {
            return switch (this) {
                case CLAMPABLE -> "its Class-1 pipeline records the position it wrote through";
                case NO_PIPELINE -> "nothing writes it, so its absent values are final rather than late";
                case INGEST_WRITTEN -> "data-sync writes it beside its source and flushes it first, so a"
                        + " visible source row already has its row and an absent value is final";
                case GROUP_TRIGGER -> "a group-triggered pipeline writes it, and a group's members span"
                        + " arbitrary source positions, so no settled position exists and the value changes"
                        + " on every recompute by design";
                case NOT_CLASS1 -> "a pipeline ADAS does not execute itself writes it, so the write-through"
                        + " position is held by the runner and never reported";
                case UNDECLARED_WRITER -> "the pipeline targeting it is a disabled draft whose declaration is"
                        + " incomplete, so it writes nothing and its absent values are final until it is armed";
                case FOREIGN_AXIS -> "the pipeline writing it reads a different table, so the position it"
                        + " records is on another cursor axis and does not translate";
            };
        }
    }

    /**
     * A position no refresh window may close past, and the enrichment that imposed it.
     *
     * @param version the version half, or null for "this pipeline has written nothing that can be trusted
     *     yet",
     *     which bounds the window at <em>nothing</em> rather than at a position. Kept as an absent value
     *     instead of a sentinel epoch so a caller cannot accidentally render it as a real tuple.
     */
    public record Bound(String enrichment, Long version, String identity) {

        /** Whether this bound withholds the whole window rather than trimming it. */
        public boolean withholdsEverything() {
            return version == null;
        }
    }

    /**
     * Whether {@code enrichment} can be clamped against by a refresh whose input is {@code pipelineInput}.
     *
     * @param pipelineInput the table the refresh windows over — the axis a recorded position has to be on
     *     to mean anything
     */
    public Clampability of(String enrichment, String pipelineInput) {
        final Optional<PipelineEntity> writer = pipelines.findByTarget(enrichment);
        if (writer.isEmpty()) {
            return unwritten(enrichment);
        }
        return clampabilityOf(writer.get(), pipelineInput);
    }

    /**
     * Classifies every enrichment in {@code enrichments} at once — one query for the whole set rather than
     * one per enrichment, since a declaration may reference several. The transform each writer runs arrives
     * inside its own stored declaration, so classifying costs no second lookup at all now.
     */
    public Map<String, Clampability> classify(Collection<String> enrichments, String pipelineInput) {
        final Map<String, Clampability> byEnrichment = new LinkedHashMap<>();
        if (enrichments.isEmpty()) {
            return byEnrichment;
        }
        final List<PipelineEntity> owning = pipelines.findByTargetIn(enrichments);
        final Map<String, PipelineEntity> byTarget = new LinkedHashMap<>();
        owning.forEach(writer -> byTarget.put(writer.getTarget(), writer));
        for (final String enrichment : enrichments) {
            final PipelineEntity writer = byTarget.get(enrichment);
            byEnrichment.put(enrichment, writer == null
                    ? unwritten(enrichment)
                    : clampabilityOf(writer, pipelineInput));
        }
        return byEnrichment;
    }

    /**
     * An enrichment no pipeline targets is either written by data-sync — a mapping names it as a target —
     * or by nothing at all. Neither clamps; the two are told apart so the diagnostics report the reason that
     * actually holds (see {@link Clampability#INGEST_WRITTEN}).
     */
    private Clampability unwritten(String enrichment) {
        for (final SyncTarget target : syncTargets) {
            if (enrichment.equals(target.table())) {
                return Clampability.INGEST_WRITTEN;
            }
        }
        return Clampability.NO_PIPELINE;
    }

    /**
     * The position no refresh over {@code pipelineInput} may close past, given that it joins
     * {@code enrichments} — the <b>earliest</b> across every enrichment that contributes one, since the
     * window has to be safe for all of them. Empty when none does, which is the case that keeps a refresh
     * referencing no enrichment (or only unclampable ones) byte-identically unaffected.
     *
     * <p>Each contributing enrichment's position is
     * {@code max(materialized_through, drained_at − scan safety lag)}, and both halves are load-bearing.
     * The materialized position alone is correct as a record and unsound as a clamp: the probe applies the
     * writing pipeline's {@code filter_sql}, so it advances only to that pipeline's last <em>matching</em>
     * source row while non-matching rows sit above it permanently unenriched — a filtered declaration would
     * hold every dependent rollup at its last match forever, while looking healthy. The drained horizon is
     * what releases the window for rows nothing is ever going to enrich.
     *
     * <p>A pipeline that exists but has recorded nothing usable still bounds the window — at nothing. That
     * pipeline is expected to write, so its absent values are late rather than final, and reading past them
     * would be the very thing this exists to prevent. It resolves within one enrichment cadence of a
     * deploy.
     */
    public Optional<Bound> bound(Collection<String> enrichments, String pipelineInput) {
        if (enrichments.isEmpty()) {
            return Optional.empty();
        }
        final List<PipelineEntity> owning = pipelines.findByTargetIn(enrichments);
        Bound earliest = null;
        for (final PipelineEntity writer : owning) {
            if (!clampabilityOf(writer, pipelineInput).clamps()) {
                continue;
            }
            final Bound candidate = positionOf(writer);
            if (candidate.withholdsEverything()) {
                // Nothing is earlier than "nothing", and no later enrichment can raise it.
                return Optional.of(candidate);
            }
            if (earliest == null || CursorPredicates.compareCursors(
                    candidate.version(), candidate.identity(),
                    earliest.version(), earliest.identity()) < 0) {
                earliest = candidate;
            }
        }
        return Optional.ofNullable(earliest);
    }

    /**
     * The instant an enrichment's history was last re-derived, or empty when it never was. Deliberately not
     * qualified by {@code generation}, unlike a position: a declaration edit does not make a rollup that
     * already aggregated the re-derived values correct again.
     */
    public Optional<Instant> historyRederivedAt(String enrichment) {
        return pipelines.findByTarget(enrichment).map(PipelineEntity::getHistoryRederivedAt);
    }

    /**
     * Whether writing a window that ends at {@code (version, identity)} <b>re-derives history</b>: whether
     * that end lies at or below the position the pipeline is already recorded complete through.
     *
     * <p>The second half of the backfill discriminator, and the half that carries the shape the wire cannot
     * show. A backfill that names a {@code from}/{@code to} window is recognised in the request handler; a
     * <em>full replay</em> is not, because it carries a cursor and no window and so is byte-identical to a
     * live tick. What separates them is not the request but the effect: a live tick always writes rows the
     * pipeline has never been complete through, and a replay by definition rewrites rows it has. So the
     * question is asked of the write rather than of the call.
     *
     * <p>Asked against the same effective position {@link #bound} clamps to — the later of what was written
     * and where the pipeline was last found drained — because that is precisely what a dependent rollup was
     * allowed to read past. A window ending below it moved values that rollup may already have aggregated,
     * and no future refresh revisits those source rows, because they never moved.
     *
     * <p><b>One false positive is accepted, deliberately.</b> A live tick whose response was lost re-executes
     * an identical window on the next fire, which lands here as a re-derivation even though it rewrites the
     * same values from the same expression. Nothing on the wire or in the catalog separates that from a
     * genuine replay of one page, and the choice is between a rare spurious rebuild instruction and a
     * systematic miss on the default backfill shape — the same safe direction every other decision here
     * takes. A mark whose {@code generation} has moved reads as absent, so a declaration edit is not
     * reported: that is a definition change, which this signal deliberately does not cover.
     */
    public boolean reDerivesHistory(PipelineEntity pipeline, long version, String identity) {
        final Bound complete = positionOf(pipeline);
        return !complete.withholdsEverything() && CursorPredicates.compareCursors(
                version, identity, complete.version(), complete.identity()) <= 0;
    }

    /**
     * The effective position of a pipeline that is known to be clampable: the later of what it wrote and
     * where it was last found drained, with a mark from a superseded {@code generation} treated as absent.
     */
    private Bound positionOf(PipelineEntity pipeline) {
        final String enrichment = pipeline.getTarget();
        final boolean current = pipeline.getMaterializedGeneration() != null
                && pipeline.getMaterializedGeneration() == pipeline.getGeneration();
        final Long written = current ? pipeline.getMaterializedThroughVersion() : null;
        final Instant drained = current ? pipeline.getDrainedAt() : null;
        if (drained == null) {
            return written == null
                    ? new Bound(enrichment, null, null)
                    : new Bound(enrichment, written, pipeline.getMaterializedThroughIdentity());
        }
        // The drained horizon carries no identity: the fact is "nothing matching below this instant", not a
        // row. An empty tiebreaker is the conservative reading of that — it excludes rows sharing exactly
        // that version rather than including them, so the horizon can never release more than it observed.
        final long horizon = drained.minus(scanProperties.safetyLag()).toEpochMilli();
        if (written == null || CursorPredicates.compareCursors(
                written, pipeline.getMaterializedThroughIdentity(), horizon, "") < 0) {
            return new Bound(enrichment, horizon, "");
        }
        return new Bound(enrichment, written, pipeline.getMaterializedThroughIdentity());
    }

    private Clampability clampabilityOf(PipelineEntity writer, String pipelineInput) {
        // The undeclared draft first, ahead of the trigger: a first draft saved with only
        // `{trigger: {kind: group}}` or `{transform: {type: sql}}` has not declared a writer yet, and reading
        // it as a group or non-Class-1 writer would refuse every armed rollup reading its target over a
        // declaration nobody has finished. "Undeclared" is the completeness sweep's answer, not a narrower
        // "no transform" test, so the fragment a draft is at any moment cannot freeze its target's readers.
        final EnrichSpec spec = specOf(writer);
        final EnrichSpec.TransformSpec transform = spec == null ? null : spec.getTransform();
        if (!writer.isEnabled() && writer.getKind() == PipelineKind.ENRICH && (spec == null
                || !EnrichDeclarationCompleteness.missingMembers(new EnrichDeclaration(writer, spec)).isEmpty())) {
            return Clampability.UNDECLARED_WRITER;
        }
        if (writer.getTriggerKind() == TriggerKind.GROUP) {
            return Clampability.GROUP_TRIGGER;
        }
        // Governance already forbids a sql transform the group trigger, so the two checks cannot both fire —
        // the group branch above exists only to give the more specific diagnostic, since it is the shape
        // somebody will actually try. A declaration carrying no readable transform is treated as
        // not-Class-1: the question asked is "does ADAS write this", and an unreadable declaration is not
        // evidence that it does.
        if (transform == null || transform.getType() != TransformType.SQL) {
            return Clampability.NOT_CLASS1;
        }
        if (StringUtils.isNotBlank(writer.getInputTable()) && !writer.getInputTable().equals(pipelineInput)) {
            return Clampability.FOREIGN_AXIS;
        }
        return Clampability.CLAMPABLE;
    }

    /**
     * The declaration a writing pipeline stores, or null when it is not an enrich pipeline or its stored
     * declaration cannot be read as one. Tolerant deliberately: this runs while answering a question
     * <em>about</em> another pipeline, and one pipeline's drift must not fail an unrelated read.
     */
    private EnrichSpec specOf(PipelineEntity writer) {
        if (writer.getKind() != PipelineKind.ENRICH) {
            return null;
        }
        try {
            return specCodec.readEnrich(writer);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
