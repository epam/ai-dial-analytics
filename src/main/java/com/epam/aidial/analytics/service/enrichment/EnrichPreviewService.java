package com.epam.aidial.analytics.service.enrichment;

import com.epam.aidial.analytics.exception.PipelinePreviewBudgetExceededException;
import com.epam.aidial.analytics.exception.PipelineReadBudgetExceededException;
import com.epam.aidial.analytics.exception.PipelineValidationException;
import com.epam.aidial.analytics.service.enrichment.model.EnrichDeclaration;
import com.epam.aidial.analytics.service.enrichment.model.ResolvedEnrichPipeline;
import com.epam.aidial.analytics.service.enrichment.model.TransformOutput;
import com.epam.aidial.analytics.service.enrichment.model.TransformType;
import com.epam.aidial.analytics.service.enrichment.model.TriggerKind;
import com.epam.aidial.analytics.service.enrichment.support.PipelineAuthorizer;
import com.epam.aidial.analytics.service.enrichment.support.TargetOutputColumns;
import com.epam.aidial.analytics.service.enrichment.validation.EnrichSpecValidator;
import com.epam.aidial.analytics.service.pipeline.EnrichmentFreshness;
import com.epam.aidial.analytics.service.pipeline.PipelineService;
import com.epam.aidial.analytics.service.pipeline.PipelineSpecCodec;
import com.epam.aidial.analytics.service.pipeline.model.EnrichSpec;
import com.epam.aidial.analytics.service.pipeline.preview.PipelinePreview;
import com.epam.aidial.analytics.service.pipeline.preview.PreviewColumn;
import com.epam.aidial.analytics.service.pipeline.preview.PreviewNote;
import com.epam.aidial.analytics.service.pipeline.preview.PreviewNoteCode;
import com.epam.aidial.analytics.service.pipeline.preview.PreviewNotes;
import com.epam.aidial.analytics.service.pipeline.preview.PreviewValueCapper;
import com.epam.aidial.analytics.service.pipeline.preview.TargetCoercion;
import com.epam.aidial.analytics.service.table.ColumnMappingEntity;
import com.epam.aidial.analytics.service.table.ColumnMappingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The dry run of an {@code enrich} declaration — the checkpoint an author has between declaring a
 * pipeline and letting the Enrichment Runner spend money on it.
 *
 * <p>It previews what is <b>stored</b>, not a candidate declaration supplied in the request body. With
 * the transform on the pipeline, a prompt change and its arming are one {@code PATCH}, so the two-step
 * "stage a new version, then repoint the pin" option is gone; a preview that accepted a candidate
 * transform is additive and deliberately out of scope here.
 *
 * <p><b>It writes nothing, calls no model, and renders no prompt.</b> The first two are what make it
 * free to run; the third is a boundary rather than an omission. The template renderer and the
 * group-input assembler live in the runner, and their escaping decides what the model actually receives;
 * a second implementation here would be the last place anyone would notice drift. No rendered-request dry
 * run exists on either service — the runner exposes runtime introspection and operator actions, not a
 * rendering surface — and one belongs to the runner, which owns the renderer.
 *
 * <p>It answers in the shared preview shape and echoes nothing the pipeline read serves: no schema, no
 * mapping, no inputs. What it adds is what only a read can tell — the sampled values, their types, and the
 * coded notes about them.
 *
 * <p><b>Every read rides an already-bounded governed path</b> — {@link ScanService},
 * {@link GroupFetchService}, or {@link MaterializeService#preview} — under an explicit row cap, rather than the
 * aggregate preview's compiled-statement path. Every read that returns row values is capped at
 * {@link #SAMPLE_ROWS}; the others project no bound column and carry caps of their own — the group choice and
 * the clamp probe read one row, and a group's member count reads up to one past the runner's fetch cap. That is not a
 * preference for reuse: the aggregate preview's budget bounds memory and time but <em>not rows read</em>,
 * and an {@code llm} pipeline's bindings routinely name the request and response bodies, which are
 * refused unbounded at the query surface and cost tens of KB a row where they are not. Riding these
 * paths also means the sensitive-column rule, the pipeline's own membership filter and its authorization
 * are applied by the same code that applies them at runtime, so a preview cannot show an author
 * anything the pipeline itself would not read.
 *
 * <p>Those paths are the runner's too, and refuse an over-budget read under the runner's code. A preview's
 * refusal is the author's, so every read this service issues is translated at its boundary into
 * {@code pipeline_preview_budget_exceeded} with remedies the arm accepts.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnrichPreviewService {

    /**
     * The row cap on every sample, deliberately a constant and not a configured knob.
     *
     * <p>A dry run's response carries row payloads: an {@code llm} pipeline's bound columns and a
     * Class-1 expression's extracted values both reach the caller, and heavy body columns are the normal
     * case rather than the pathological one. The row count bounds the read, and the value cap
     * ({@link PreviewValueCapper}) the response; making either settable would let it be raised into exactly
     * the unbounded read this endpoint was routed away from the aggregate path to avoid. Five rows is enough
     * to see whether a binding resolved to the column the author meant, which is the question.
     */
    public static final int SAMPLE_ROWS = 5;

    private static final String WINDOWED_REMEDY = "Pass a narrower `from`/`to` window, bind fewer heavy columns, "
            + "or have pipeline.budget.* raised.";

    private static final String GROUP_REMEDY = "Bind fewer heavy columns, narrow the group with a "
            + "`member_select`, or have pipeline.budget.* raised.";

    private final PipelineService pipelines;
    private final PipelineAuthorizer authorizer;
    private final EnrichResolver resolver;
    private final EnrichSpecValidator validator;
    private final ColumnMappingRepository columns;
    private final ScanService scans;
    private final GroupFetchService groups;
    private final MaterializeService materialize;
    private final PreviewValueCapper capper;

    /** A sample before its values are capped: its columns, rows, the group it describes, and its notes. */
    private record Sample(List<PreviewColumn> columns, List<Map<String, Object>> rows, String groupKey,
            List<PreviewNote> notes) {}

    /**
     * Previews one {@code enrich} pipeline. {@code groupKey} names the group to sample for a
     * {@code group}-triggered pipeline (the newest is chosen when it names none); {@code from}/{@code to}
     * restrict a row-grain or Class-1 sample to a version window.
     */
    public PipelinePreview preview(String name, String groupKey, Instant from, Instant to) {
        final ResolvedEnrichPipeline resolved = pipelines.getEnrich(name);  // 404 unknown, 422 wrong kind
        final EnrichDeclaration declaration = resolved.declaration();
        // Authorized here as well as inside each delegate, so the target's grant is required before anything
        // is checked, rather than only the endpoint's admin role, which is inert in `none` mode.
        authorizer.authorize(resolved.source(), declaration.target());
        final boolean enabled = resolved.pipeline().isEnabled();
        if (!enabled) {
            // A DISABLED pipeline has never been gated — it may be a draft stored with any subset of its
            // declaration — so its preview is the author's diagnostic and asks everything arming would, in
            // the order arming asks it: the trigger's entity-level rules, then the whole gate. Run before the
            // transform checks below so the first problem reported is the one arming would report; the gate
            // refuses a missing transform or type itself, in arming's words.
            PipelineService.requireArmableTrigger(resolved.pipeline());
            validator.validate(declaration);
            // The gate derived the output mapping into `resolved`'s spec, in memory. The sampling delegates
            // below are handed `resolved` rather than the name for that reason: a reload would read the
            // mapping stored when the draft was saved — empty if its target's columns were defined only
            // afterwards — and refuse a preview of a declaration arming would accept.
        }
        // Through the shared guard: a stored declaration with no transform is what a write served by a
        // pre-fold ADAS pod leaves behind, and the preview is where an author is most likely to meet it.
        // Reporting "no type" for it would send them to fix a member that is not the one that is missing.
        // Neither refusal below is reachable for a disabled pipeline that passed the gate above; there this
        // only obtains the transform.
        final EnrichSpec.TransformSpec transform =
                PipelineSpecCodec.requireTransform(name, declaration.spec(), enabled);
        if (transform.getType() == null) {
            throw new PipelineValidationException("pipeline '" + name + "' declares a transform with no"
                    + " type; there is nothing to preview");
        }
        if (enabled) {
            // An ARMED pipeline passed the full gate when it was armed, so the preview previews what is
            // stored rather than re-validating it — re-asking would report it unpreviewable for drift the
            // operator did not cause. Only the check the gate scopes to arming is run: a template
            // placeholder bound to nothing would render unsubstituted, which is the question the author came
            // to have answered rather than a shape to report.
            validator.validateForPreview(declaration);
        }
        final boolean group = declaration.triggerKind() == TriggerKind.GROUP;
        try {
            return transform.getType() == TransformType.SQL
                    ? class1Preview(resolved, declaration, groupKey, from, to)
                    : llmPreview(resolved, declaration, transform, groupKey, from, to);
        } catch (PipelineReadBudgetExceededException ex) {
            // The read paths are the runner's and refuse under its code; the preview's refusal is the author's.
            // Which read was refused is named, because a preview issues several and their remedies differ: a
            // refused member read is the bound columns, a refused member count is the filter.
            final String exceeded = ex.exceeded() == null ? "its resource budget" : ex.exceeded();
            final String read = ex.what() == null ? "" : " (" + ex.what() + ")";
            throw new PipelinePreviewBudgetExceededException("this pipeline's preview" + read + " exceeded " + exceeded
                    + " (pipeline.budget.*). " + (group ? GROUP_REMEDY : WINDOWED_REMEDY), ex);
        }
    }

    /**
     * The Class-1 arm: a sample of the values the declaration computes, with their statement, delegated to
     * {@link MaterializeService} so the expressions previewed are analyzed by the same code that renders the
     * write. The sample is unclamped; the bound a materialize would clamp to is reported, not applied.
     */
    private PipelinePreview class1Preview(ResolvedEnrichPipeline resolved, EnrichDeclaration declaration,
            String groupKey, Instant from, Instant to) {
        requireNoGroupKey(groupKey, declaration);
        final MaterializeService.Class1Preview preview = materialize.preview(resolved, from, to);
        final List<PreviewNote> notes = new ArrayList<>();
        final EnrichmentFreshness.Bound bound = preview.bound();
        if (bound != null && bound.withholdsEverything()) {
            notes.add(PreviewNotes.heldByEnrichment(bound.enrichment()));
        } else if (bound != null && preview.newest() != null && preview.newest().isPast(bound)) {
            notes.add(PreviewNotes.enrichmentBehind(bound.enrichment(), preview.newest().version(), bound.version(), true));
        }
        if (preview.rows().isEmpty()) {
            notes.add(noMatchingRows());
        }
        notes.addAll(TargetCoercion.notes(preview.columns(), preview.rows(), Set.of()));
        return finish(preview.statement(), new Sample(preview.columns(), preview.rows(), null, notes));
    }

    /**
     * The {@code llm} arm: a sample of the input the transform would receive, and the notes about the response
     * it would be held to.
     */
    private PipelinePreview llmPreview(ResolvedEnrichPipeline resolved,
            EnrichDeclaration declaration, EnrichSpec.TransformSpec transform,
            String groupKey, Instant from, Instant to) {
        final Map<String, Object> stored = transform.getResponseSchema();
        // Composed through the throwing entry point, not the resolver's swallowing one, although the schema is
        // no longer returned: a target whose columns no longer fit the transform's outputs is the single most
        // valuable thing this endpoint can report, and the composer's refusal names the output, the column and
        // the spelling it wanted. Its null result is the unconstrained-response note.
        final Map<String, Object> schema = stored != null
                ? stored
                : resolver.composeSchema(resolved.pipeline(), transform);

        final Sample sample = declaration.triggerKind() == TriggerKind.GROUP
                ? groupSample(resolved, declaration, groupKey, from, to)
                : rowSample(resolved, declaration, groupKey, from, to);
        final List<PreviewNote> notes = new ArrayList<>(sample.notes());
        if (schema == null) {
            notes.add(PreviewNote.of(PreviewNoteCode.UNCONSTRAINED_RESPONSE, "this declaration stores no "
                    + "`transform.response_schema` and declares no `transform.outputs`, so nothing holds the "
                    + "model's response to a shape; patch the pipeline with a `transform.outputs` map"));
        }
        descriptionlessOutputs(declaration, transform, stored).forEach(output -> notes.add(PreviewNote.about(
                PreviewNoteCode.OUTPUT_WITHOUT_DESCRIPTION, output, "output '" + output + "' composed no "
                        + "`description`: neither its `prose` nor the target column's `description` carries one, so "
                        + "the model is told nothing about what the field means. Declare prose on the output, or a "
                        + "description on the column")));
        return finish(null, new Sample(sample.columns(), sample.rows(), sample.groupKey(), notes));
    }

    /**
     * A row-grain sample through the runner scan's plan, newest first and clamped as the scan is. Where the clamp
     * left matching rows out, the clamp is named rather than the filter blamed.
     */
    private Sample rowSample(ResolvedEnrichPipeline resolved, EnrichDeclaration declaration, String groupKey,
            Instant from, Instant to) {
        requireNoGroupKey(groupKey, declaration);
        final ScanService.RowSample sample = scans.previewRows(resolved, SAMPLE_ROWS, from, to);
        final List<PreviewNote> notes = new ArrayList<>();
        if (sample.heldBy() != null) {
            notes.add(PreviewNotes.heldByEnrichment(sample.heldBy()));
        } else if (sample.aboveBound() != null) {
            final EnrichmentFreshness.Bound bound = sample.bound();
            notes.add(sample.rows().isEmpty()
                    ? PreviewNotes.heldByEnrichment(bound.enrichment(), sample.aboveBound(), bound.version())
                    : PreviewNotes.enrichmentBehind(bound.enrichment(), sample.aboveBound(), bound.version(), false));
        } else if (sample.rows().isEmpty()) {
            notes.add(noMatchingRows());
        }
        return new Sample(sample.columns(), sample.rows(), null, notes);
    }

    /**
     * A group sample: the named group, or the newest one the runner's marking would reach when none is named,
     * and a note when the runner's fetch would deliver fewer of its members than the group holds.
     */
    private Sample groupSample(ResolvedEnrichPipeline resolved, EnrichDeclaration declaration, String groupKey,
            Instant from, Instant to) {
        requireNoRowWindow(from, to, declaration);
        // Used exactly as named, as the runner's fetch uses it: only an absent or empty key asks for the newest
        // group, and a whitespace-only one is refused by the fetch rather than read as absent.
        String key = groupKey;
        // A named group is sampled whether or not the clamp would let the runner mark it — the group fetch has no
        // clamp — but an enrichment with no bound at all still means an enabled run would write nothing, which the
        // spec reports on every arm. Asked of the scan's plan, which issues no statement.
        String heldBy = null;
        if (StringUtils.isEmpty(key)) {
            final ScanService.GroupPick pick = scans.pickGroup(resolved);
            if (pick.heldBy() != null) {
                return new Sample(pick.columns(), List.of(), null, List.of(PreviewNotes.heldByEnrichment(pick.heldBy())));
            }
            if (pick.groupKey() == null) {
                return new Sample(pick.columns(), List.of(), null, List.of(PreviewNote.of(PreviewNoteCode.NO_GROUP_FOUND,
                        "no `group_key` was named and no input row matching this pipeline's `filter` carries a "
                                + "non-empty `" + declaration.triggerGroupBy() + "`, so there is no group to sample")));
            }
            key = pick.groupKey();
        } else {
            heldBy = scans.withholdingEnrichment(resolved);
        }
        final GroupFetchService.MemberSample members = groups.previewMembers(resolved, key, SAMPLE_ROWS);
        final List<PreviewNote> notes = new ArrayList<>();
        if (heldBy != null) {
            notes.add(PreviewNotes.heldByEnrichment(heldBy));
        }
        if (members.rows().isEmpty() && members.selected() == 0) {
            notes.add(PreviewNote.of(PreviewNoteCode.NO_MATCHING_ROWS, "group '" + key + "' has no member row "
                    + "satisfying this pipeline's `filter`"));
        } else if (members.rows().isEmpty()) {
            // Members matched, but none could be read back by its lookup key: the source changed between the
            // two reads (a re-ingest), or the point lookup addressed nothing. Not the filter, so it is not
            // reported as no_matching_rows; the closed note set has no code for it, so the sample is simply
            // empty, and the refusal-free answer is to preview again.
            log.warn("Preview of pipeline '{}' selected {} member(s) of group '{}' and read none of them back",
                    declaration.name(), members.selected(), key);
        }
        if (members.capIsDefault() && members.counted() > members.cap()) {
            notes.add(PreviewNote.of(PreviewNoteCode.GROUP_CUT_BY_FETCH_LIMIT, "group '" + key + "' has more than "
                    + members.cap() + " members, and the runner's group fetch delivers at most " + members.cap()
                    + " (enrichment.group-fetch.default-rows): it delivers " + members.cap() + " of "
                    + members.counted() + "+ members; showing " + members.rows().size()));
        }
        return new Sample(members.columns(), members.rows(), key, notes);
    }

    /** Caps the sample's values last, after every note that needs the full values, and assembles the response. */
    private PipelinePreview finish(String statement, Sample sample) {
        final PreviewValueCapper.Capped capped = capper.cap(sample.columns(), sample.rows());
        final List<PreviewNote> notes = new ArrayList<>(sample.notes());
        notes.addAll(capped.notes());
        return new PipelinePreview(statement, capped.columns(), capped.rows(), null, sample.groupKey(), notes);
    }

    private static PreviewNote noMatchingRows() {
        return PreviewNote.of(PreviewNoteCode.NO_MATCHING_ROWS, "no input row matched this pipeline's "
                + "`filter` inside the sampled window; widen the filter, or pass `from`/`to` to sample a "
                + "different stretch of the input");
    }

    /**
     * The outputs composition produced no {@code description} for, in declaration order — none when every output
     * got one.
     *
     * <p>Skipped entirely when a schema is <b>stored</b>: composition does not run then, so no column
     * description is read and reporting one as missing would describe a computation that did not happen.
     * Skipped for the legacy output shape too, which has no prose member at all.
     *
     * <p>The output→column step goes through {@link TargetOutputColumns}, the same resolution the composer
     * itself uses, rather than a local map keyed on the exposed name. The two agreed, but agreeing by
     * coincidence is the defect {@code TargetOutputColumns} was extracted to remove: this note describes
     * what composition <em>did</em>, so a second implementation of "which column is this output's" could
     * report a description missing from a column the composer never read. Nothing here can throw in
     * practice — composition ran a few lines earlier through its throwing entry point, so every output is
     * already known to resolve — and if it ever did, the composer's refusal is the right message anyway.
     */
    private List<String> descriptionlessOutputs(EnrichDeclaration declaration,
            EnrichSpec.TransformSpec transform, Map<String, Object> stored) {
        if (stored != null || transform.getOutputs() == null || transform.getOutputs().isEmpty()) {
            return List.of();
        }
        final List<ColumnMappingEntity> targetColumns =
                columns.findByTableNameOrderByOrdinal(declaration.target());
        return transform.getOutputs().stream()
                .filter(output -> StringUtils.isBlank(output.getProse()))
                .filter(output -> StringUtils.isBlank(TargetOutputColumns
                        .requireExposed(output.getName(), targetColumns, declaration.target())
                        .getDescription()))
                .map(TransformOutput::getName)
                .toList();
    }

    /**
     * Refuses any {@code group_key} the body carries, whitespace-only included: the group arm reads only an absent
     * or empty key as absent, so the row-grain arms must not read a blank one as absent either.
     */
    private static void requireNoGroupKey(String groupKey, EnrichDeclaration declaration) {
        if (StringUtils.isNotEmpty(groupKey)) {
            throw new PipelineValidationException("`group_key` names the group to sample for a "
                    + "group-triggered pipeline, and '" + declaration.name() + "' is triggered "
                    + codeOf(declaration.triggerKind()) + ", whose sample is the input rows themselves; "
                    + "omit it");
        }
    }

    private static void requireNoRowWindow(Instant from, Instant to, EnrichDeclaration declaration) {
        if (from != null || to != null) {
            throw new PipelineValidationException("`from` and `to` restrict a row-grain sample to a stretch of "
                    + "the input, and '" + declaration.name() + "' is triggered per group; name the group to "
                    + "sample with `group_key` instead, or omit it to sample the newest group");
        }
    }

    private static String codeOf(TriggerKind kind) {
        return kind == null ? "per row" : kind.name().toLowerCase(Locale.ROOT);
    }
}
