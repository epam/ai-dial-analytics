package com.epam.aidial.analytics.service.pipeline;

import com.epam.aidial.analytics.exception.PipelineConflictException;
import com.epam.aidial.analytics.exception.PipelineNotFoundException;
import com.epam.aidial.analytics.exception.PipelineValidationException;
import com.epam.aidial.analytics.observability.TransitionLog;
import com.epam.aidial.analytics.observability.trace.LogExecution;
import com.epam.aidial.analytics.service.enrichment.EnrichResolver;
import com.epam.aidial.analytics.service.enrichment.model.EnrichDeclaration;
import com.epam.aidial.analytics.service.enrichment.model.OutputBinding;
import com.epam.aidial.analytics.service.enrichment.model.ResolvedEnrichPipeline;
import com.epam.aidial.analytics.service.enrichment.model.TransformType;
import com.epam.aidial.analytics.service.enrichment.model.TransformVar;
import com.epam.aidial.analytics.service.enrichment.model.TriggerKind;
import com.epam.aidial.analytics.service.enrichment.support.RunnerContactMetrics;
import com.epam.aidial.analytics.service.enrichment.validation.EnrichDeclarationCompleteness;
import com.epam.aidial.analytics.service.enrichment.validation.EnrichSpecValidator;
import com.epam.aidial.analytics.service.pipeline.model.AggregateSpec;
import com.epam.aidial.analytics.service.pipeline.model.EnrichSpec;
import com.epam.aidial.analytics.service.pipeline.model.PipelineEnabledFilter;
import com.epam.aidial.analytics.service.pipeline.model.PipelineRegistration;
import com.epam.aidial.analytics.service.pipeline.model.PipelineSpec;
import com.epam.aidial.analytics.service.security.RowPolicyFilter;
import com.epam.aidial.analytics.service.security.SecurityClaimsExtractor;
import com.epam.aidial.analytics.service.table.Identifiers;
import com.epam.aidial.analytics.service.table.PipelineTargetCascade;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Manages the pipeline registry — one registry for both kinds: CRUD, the listing that also serves as the
 * Enrichment Runner's sync feed, and the enable/disable gate. Every mutation runs the gate its
 * {@code kind} selects when it leaves the pipeline armed — a disabled pipeline is a draft, and its gate runs
 * when it is armed or previewed — and bumps {@code generation}, which is what a change-detecting consumer
 * diffs.
 *
 * <p>A pipeline's {@code name} is its identity, is immutable, and takes the identity grammar rather than
 * the catalog's identifier grammar — see {@link Identifiers#requirePipelineName}.
 *
 * <h2>Where the two kinds genuinely differ</h2>
 *
 * <p>Four places, each with a reason that is about the target's write semantics rather than about
 * convenience. Everything else is common, which is the point of the merge.
 *
 * <ol>
 *   <li><b>The gate.</b> {@link AggregateSpecValidator} or {@link EnrichSpecValidator}, dispatched on
 *       {@code kind}. The shared filter grammar reports under the pipeline error code either way, labelled
 *       with the kind whose declaration was refused.</li>
 *   <li><b>Registration-time {@code enabled}.</b> An {@code aggregate} pipeline is always stored disabled,
 *       because its dry-run preview is the reason the disabled state exists — an operator reads the
 *       compiled statement and a sample before anything writes. An {@code enrich} pipeline may register
 *       armed, and is then gated by that registration, because the write arms it.</li>
 *   <li><b>Editing an enabled pipeline's declaration.</b> 409 for {@code aggregate}, permitted for
 *       {@code enrich} — see {@link #patch}.</li>
 *   <li><b>What a declaration change resets.</b> Only the execution state ADAS owns for that kind — see
 *       {@link #resetCursor}.</li>
 * </ol>
 *
 * <p><b>A declaration change resets an aggregate pipeline's scan position.</b> Keeping the cursor would
 * confine the new declaration to windows that have not been read yet, so every key materialized before the
 * edit would keep the values the <em>previous</em> declaration produced — indefinitely, and with nothing in
 * the target saying so. Clearing it makes the next run rescan the input from the beginning and recompute
 * every key under the new declaration; the backlog then drains one window per tick through the ordinary
 * {@code has_more} path, so no new mechanism is needed. Rows whose key the new {@code group_by} no longer
 * produces are <em>not</em> removed: recomputation can only replace keys it derives. A {@code group_by}
 * change must match the target's {@code ordering_key}, so it implies a differently-keyed target anyway —
 * rebuilding or replacing that table stays the operator's call.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@LogExecution
public class PipelineService implements PipelineTargetCascade {

    /** What each pipeline CHECK means, in the words a refusal names it with. */
    private static final Map<String, String> CHECK_REFUSALS = Map.of(
            "pipeline_member_select_only_group", "trigger.member_select is only valid for a group pipeline",
            "pipeline_aggregate_is_scheduled", "an 'aggregate' pipeline is triggered on a schedule",
            "pipeline_input_required_unless_enrich", "an armed 'aggregate' pipeline must name its input",
            "pipeline_cron_iff_schedule", "an armed pipeline has a trigger.cron exactly when it is scheduled",
            "pipeline_group_by_iff_group", "an armed pipeline has a trigger.group_by exactly when it is a group"
                    + " pipeline",
            "pipeline_armed_is_complete", "an armed pipeline must name its trigger kind and, unless it is"
                    + " 'enrich', its input",
            "pipeline_trigger_kind_check", "trigger.kind is not supported (allowed: schedule, group)",
            "pipeline_kind_check", "kind is not supported; supported kinds: " + PipelineKind.implementedCodes());

    private final PipelineRepository pipelines;
    private final TableDefinitionRepository definitions;
    private final AggregateSpecValidator aggregateValidator;
    private final EnrichSpecValidator enrichValidator;
    private final PipelineSpecCodec specCodec;
    private final EnrichResolver enrichResolver;
    private final AggregateMetrics metrics;
    private final RunnerContactMetrics runnerContactMetrics;
    private final TransitionLog transitions;
    private final SecurityClaimsExtractor claims;
    private final RowPolicyFilter rowPolicies;
    private final AggregateSqlReader sqlReader;
    private final LookupIndexService lookupIndexes;

    @Transactional
    public PipelineEntity create(PipelineRegistration registration) {
        Identifiers.requirePipelineName(registration.name());
        if (pipelines.existsById(registration.name())) {
            throw new PipelineConflictException("pipeline '" + registration.name() + "' already exists");
        }
        requireSupportedKind(registration);
        requireTargetAvailable(registration.target(), null);

        final PipelineEntity pipeline = new PipelineEntity();
        pipeline.setName(registration.name());
        // One instant for the whole registration, truncated to what TIMESTAMPTZ stores — because
        // `updated_at` is the sync feed's watermark and a response reporting an untruncated value would
        // name an instant that was never stored. Shared across the three stamps rather than read three
        // times so a freshly registered pipeline reports created_at == updated_at, instead of a
        // microsecond of drift that reads as an edit nobody made.
        final Instant registeredAt = PipelineTimestamps.now();
        pipeline.setCreatedAt(registeredAt);
        // A pipeline is stored with no cursor, so its whole contents are about to be built from current
        // enrichment values: any re-derivation recorded before now is already accounted for.
        pipeline.setRebuiltAt(registeredAt);
        pipeline.setKind(registration.kind());
        // An aggregate pipeline is always disabled at registration, whatever the caller asked for: the
        // preview gate comes first. An enrich pipeline has no preview gate to come first.
        final boolean enabled = registration.enabled() && pipeline.getKind() != PipelineKind.AGGREGATE;
        if (enabled) {
            requireUnconfinedReads(registration.inputTable(), registration.target(), "arm this pipeline");
        }
        final PipelineSpec spec = apply(pipeline, registration, enabled);
        stamp(pipeline, registeredAt);
        pipeline.setEnabled(enabled);
        pipeline.setNextRunAt(armedAt(pipeline, enabled));

        // Gated only when the registration arms the pipeline: a disabled pipeline is a draft, stored with
        // whatever it was given, and its gate runs at arming and at preview. See validateAndStore.
        validateAndStore(pipeline, spec, enabled, false);
        final PipelineEntity saved = save(pipeline);
        lookupIndexes.ensureAfterCommit(saved);
        logGenerationChange(saved, "register");
        log.info("Pipeline '{}' registered: kind={}, input='{}', target='{}', trigger={}/{}; enabled={}, generation={}",
                saved.getName(), saved.getKind(), saved.getInputTable(), saved.getTarget(),
                saved.getTriggerKind(), saved.getTriggerCron(), saved.isEnabled(), saved.getGeneration());
        return saved;
    }

    /**
     * Patches a pipeline. The declaration ({@code target}, {@code inputs}, {@code filter}, the transform
     * spec, the trigger) is supplied whole or not at all; {@code enabled} may always change. {@code kind}
     * is not patchable — a different transform over the same target is a different pipeline — so the stored
     * one is carried over.
     *
     * <p><b>Whether a declaration may be edited while the pipeline is enabled depends on the kind</b>, and
     * the reason is the target's write semantics:
     *
     * <ul>
     *   <li>{@code aggregate} — <b>409</b>. Its target holds windowed results replaced by key, so an edit
     *       mid-flight leaves the target holding rows computed two different ways with nothing recording
     *       which row came from which. A state conflict, not a bad declaration: the spec sent may be
     *       perfectly good and only the timing wrong, and a validation code would tell a client to fix
     *       something that has nothing wrong with it.</li>
     *   <li>{@code enrich} — <b>permitted</b>, and this is correctness rather than convenience. Its target
     *       is a grain-keyed {@code ReplacingMergeTree}: after an edit new rows compute the new way and rows
     *       already written keep their previous values, which is <em>already</em> how that path behaves and
     *       is precisely why it carries a re-derivation marker and a backfill lane of its own. The invariant
     *       above is not being weakened for this kind; it was never this kind's invariant. Requiring a
     *       disable/enable cycle for a routine runtime edit ({@code cadence}, {@code rate_rpm},
     *       {@code batch_scan_limit}) would instead cost real work — the runner drops a disabled pipeline
     *       from its cache, and its dead-letter items lose their stored labels while it is disabled.</li>
     * </ul>
     *
     * <p><b>A patch that leaves the pipeline disabled is not gated.</b> Completeness and the kind gate are
     * asked of a declaration about to produce rows, so they run when the patch leaves the pipeline armed —
     * re-declaring an enabled one, or enabling one — and not on a draft save. The derived members are
     * still derived on every declaration write, best-effort, so a draft save never erases them.
     *
     * <p>{@code target} is <b>sticky</b>: a declaration that omits it keeps the stored one, and one naming
     * another repoints the pipeline. Every other member keeps replace semantics.
     *
     * <p><b>A complete declaration stays complete.</b> Replace semantics make a partial patch of a disabled
     * pipeline clear what it omits, and nothing stored tells a draft from a pipeline that ran and was paused
     * — so {@code PATCH {"advanced": …}} on a paused production pipeline would erase its transform, trigger
     * and inputs with a 200. A patch leaving the pipeline disabled is therefore refused when the stored
     * declaration names every member its kind authors and the patched one would not. A draft that is still
     * incomplete may be saved with any subset, as before.
     *
     * <p>What that refusal protects against is <em>omission</em>, so it asks only about members the patch
     * left out. An {@code enrich} transform the patch restates with its {@code type} is taken as sent, outputs
     * included — emptying them is how the last output comes off a paused pipeline so its target column can
     * be dropped — and so is a {@code trigger} restated with its {@code kind}. The armed gate still refuses
     * either at enable.
     *
     * @param registration the replacement declaration, or null when the patch only flips {@code enabled}
     */
    @Transactional
    public PipelineEntity patch(String name, PipelineRegistration registration, Boolean enabled) {
        final PipelineEntity pipeline = require(name);
        boolean mutated = false;
        boolean validated = false;
        // Whether the pipeline is armed AFTER this patch, which is what decides whether the kind gate runs at
        // all. Read here rather than inside the declaration branch because that branch
        // runs before `enabled` is applied, so the entity still holds the old value there — and a patch
        // that re-declares and enables in one call must be gated as armed, not as a draft save.
        final boolean armed = enabled != null ? enabled : pipeline.isEnabled();
        // An enable-only patch never runs apply(), so an SQL-mode draft whose statement named no catalog table
        // when it was saved is given the input it reads now, before anything asks for it.
        if (registration == null && armed && !pipeline.isEnabled()) {
            deriveSqlInput(pipeline);
        }
        // A patch that leaves the pipeline armed — a re-declaration of an enabled one, or an enable — hands its
        // reads to an identity no row policy confines, so a confined caller is refused. Asked of the tables the
        // pipeline will read after the patch: here for an enable-only patch, and after apply() for a declaration,
        // whose SQL-mode input is derived there from the statement — the declared `inputs` is optional in that
        // mode, so asking it would skip the very table the refresh reads.
        if (registration == null && armed && !pipeline.isEnabled()) {
            requireUnconfinedReads(pipeline.getInputTable(), pipeline.getTarget(), "arm this pipeline");
        }
        // What this patch did, for the generation line: a patch bumps once however much it combines, so the
        // one line names all of it. The re-declare and enable lines below are logged before that bump, so
        // they report the generation the patch results in rather than the one it started from.
        final List<String> actions = new ArrayList<>(2);
        final long resultingGeneration = pipeline.getGeneration() + 1;
        if (registration != null) {
            requireDeclarationEditable(pipeline);
            final PipelineRegistration declared = withStickyTarget(registration, pipeline);
            requireTargetAvailable(declared.target(), name);
            // Captured before apply() overwrites them: a derived member a draft save cannot re-derive is
            // carried over only while it still describes the same tables. apply() never touches the stored
            // spec itself, so that is read lazily, and only when derivation fails.
            final boolean sameTables = Objects.equals(pipeline.getTarget(), declared.target())
                    && Objects.equals(pipeline.getInputTable(), StringUtils.trimToNull(declared.inputTable()));
            // Also captured before apply(): whether the stored declaration was complete, which is what a
            // patch leaving the pipeline disabled may not undo. An armed patch is held to completeness by
            // the mapper and the gate instead.
            final boolean storedComplete = !armed && isStoredDeclarationComplete(pipeline);
            final PipelineSpec spec = apply(pipeline, declared, armed);
            // Inside the transaction, so a refusal rolls back what apply() copied onto the entity.
            if (armed) {
                requireUnconfinedReads(pipeline.getInputTable(), pipeline.getTarget(), "arm this pipeline");
            }
            if (storedComplete) {
                requireStillComplete(pipeline, spec, declared);
            }
            // The new declaration owns the whole target, not just the windows still unread: see the class
            // note. Clearing the position makes the next run rescan from the beginning.
            resetCursor(pipeline);
            validateAndStore(pipeline, spec, armed, sameTables);
            validated = true;
            mutated = true;
            actions.add("re-declare");
            log.info("Pipeline '{}' re-declared: kind={}, input='{}', target='{}', trigger={}/{}; generation={}", name,
                    pipeline.getKind(), pipeline.getInputTable(), pipeline.getTarget(),
                    pipeline.getTriggerKind(), pipeline.getTriggerCron(), resultingGeneration);
        }
        if (enabled != null && enabled != pipeline.isEnabled()) {
            if (enabled) {
                // Re-validate before arming: the input's schema may have moved since registration. A patch
                // that re-declared in the same call has just run the identical gate over the identical
                // state, so running it twice would only pay for it twice.
                //
                // The enable-only path never ran apply(), so the entity-level completeness a declaration
                // write asks when it arms — a trigger kind, a cron that is present and parses — is asked
                // here too. Without it an aggregate draft declaring `schedule` with no cron would reach
                // AggregateScheduler.nextRun(null) below and answer 500 before any guard could refuse it.
                if (!validated) {
                    requireArmableTrigger(pipeline);
                    validateAndStore(pipeline, specCodec.read(pipeline), true, false);
                }
            }
            pipeline.setNextRunAt(armedAt(pipeline, enabled));
            pipeline.setEnabled(enabled);
            mutated = true;
            actions.add(enabled ? "enable" : "disable");
            if (enabled) {
                log.info("Pipeline '{}' enabled; next ADAS-driven run at {}; generation={}", name,
                        pipeline.getNextRunAt(), resultingGeneration);
            } else {
                // A disabled pipeline leaves the runner's enabled=true feed by design, so the runner
                // correctly stops calling it. Its runner-contact series is therefore dropped rather than
                // left to climb: derived at observation time from an instant nothing will move again, it
                // would report "the runner has stopped calling" forever for a pipeline an operator
                // deliberately switched off — the intended behaviour raising the loudest alarm on the
                // dashboard. Re-enabling it re-registers the series on the next call, measured from a
                // contact that actually happened.
                runnerContactMetrics.forget(name);
                transitions.forget(name);
                log.info("Pipeline '{}' disabled; it will not write on a trigger or on demand, and its "
                        + "runner-contact series is dropped", name);
            }
        }
        // Exactly one bump per accepted mutation: `generation` is an ETag, so a no-op patch must not move it,
        // and a patch that both re-declares and enables is still one change to the pipeline.
        if (mutated) {
            stamp(pipeline, PipelineTimestamps.now());
        }
        final PipelineEntity saved = save(pipeline);
        if (mutated) {
            lookupIndexes.ensureAfterCommit(saved);
            logGenerationChange(saved, "patch(" + String.join(",", actions) + ")");
        }
        return saved;
    }

    /**
     * Deletes a pipeline, leaving its target table and rows intact — a target that stops being fed is
     * stale, not invalid, and dropping data as a side effect of removing a process would be a surprise.
     *
     * <p>Its metric series <b>are</b> dropped, unlike its rows. The staleness gauge is derived at scrape time
     * from a watermark, so one left behind climbs forever on a pipeline that no longer exists — a permanent
     * alert on the very signal a stall raises. The rows are data an operator may still want; the series is
     * only a statement about a process, and the process is gone.
     */
    @Transactional
    public void delete(String name) {
        final PipelineEntity pipeline = require(name);
        pipelines.delete(pipeline);
        forgetMetrics(name);
        log.info("Pipeline '{}' deleted; its metric series are dropped and target '{}' keeps its rows",
                name, pipeline.getTarget());
    }

    @Transactional(readOnly = true)
    public PipelineEntity get(String name) {
        return require(name);
    }

    /**
     * The parent source of the table a declaration patch leaves the pipeline targeting — the target the patch
     * names, else the stored one — or null when that table has none or does not exist (the patch itself then
     * fails on the missing target). It is the table an enrich pipeline's omitted {@code inputs} follows.
     */
    @Transactional(readOnly = true)
    public String patchedTargetParentOf(PipelineEntity stored, String patchedTarget) {
        final String target = StringUtils.isNotBlank(patchedTarget) ? patchedTarget.trim() : stored.getTarget();
        return target == null ? null : definitions.findById(target)
                .map(TableDefinitionEntity::getSourceTable)
                .orElse(null);
    }

    /**
     * Deletes the pipeline whose {@code target} is {@code target}, if one exists, through this
     * repository rather than {@code pipeline.target}'s {@code ON DELETE CASCADE}: a database cascade
     * fires no Hibernate event, so the pipeline's own {@code Delete} would be silently absent from the
     * audit trail. {@code MANDATORY} propagation: this must land in the same transaction, and therefore
     * the same revision, as the target table's own deletion.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void deletePipelineTargetingTable(String target) {
        final Optional<PipelineEntity> pipeline = pipelines.findByTarget(target);
        if (pipeline.isPresent()) {
            pipelines.delete(pipeline.get());
            pipelines.flush();
            // The same deregistration {@link #delete} does, and for the same reason: this path deletes a
            // pipeline just as permanently, so a series left behind here climbs just as forever. It was
            // missing — a live instance of the leak forget()'s own javadoc says it exists to prevent —
            // and both metrics are dropped together so a third deletion path cannot inherit half of it.
            forgetMetrics(pipeline.get().getName());
        }
    }

    /**
     * Drops every metric series keyed by this pipeline, from <b>both</b> registries that hold one. Called
     * from every path that deletes a pipeline; disabling one drops only the runner-contact series, because
     * an aggregate pipeline that is switched off still has a last-successful-run watermark worth reading.
     * The change-only log states go with them (disabling drops those too).
     */
    private void forgetMetrics(String name) {
        metrics.forget(name);
        runnerContactMetrics.forget(name);
        transitions.forget(name);
    }

    /**
     * The registry listing, which is also the runner's sync feed — the same read under different filters.
     * Every filter is optional and they apply together; narrowing on one and silently dropping another
     * would return rows the caller did not ask for and look like data rather than a mistake.
     *
     * <p>The feed asks for {@link PipelineEnabledFilter#ENABLED} and {@code kind=enrich}: a runner must
     * never be handed a pipeline an operator switched off. An operator listing widens it.
     *
     * <p>One ordering for every listing — {@code updated_at ASC, name ASC} — decided in the repository
     * rather than here, so a consumer's ordering guarantee does not depend on which filters it passed.
     */
    @Transactional(readOnly = true)
    public List<PipelineEntity> list(
            String target, PipelineKind kind, PipelineEnabledFilter enabled, Instant updatedSince) {
        return pipelines.list(StringUtils.trimToNull(target), kind,
                enabled == null ? null : enabled.value(), updatedSince);
    }

    /**
     * As {@link #list}, narrowed to ENRICH and resolved in a constant number of queries.
     *
     * <p>Takes the same {@code target} filter the unresolved listing does, so an {@code enrich} pipeline is
     * described the same way whichever filters selected it. Resolving only the unfiltered feed would make
     * {@code ?kind=enrich&target=x} answer with the response schema, grain key, version column and
     * resolved input all absent — one resource with two shapes, and the narrower one silently missing the fields a caller
     * added a filter to go and read.
     */
    @Transactional(readOnly = true)
    public List<ResolvedEnrichPipeline> listEnrich(
            String target, PipelineEnabledFilter enabled, Instant updatedSince) {
        return withResolvableOutputMapping(
                enrichResolver.resolveAll(list(target, PipelineKind.ENRICH, enabled, updatedSince)));
    }

    /**
     * Drops an {@code enrich} pipeline the feed cannot describe completely — one whose output mapping
     * resolves to <b>no columns</b>, or whose declared {@code outputs} <b>compose to no schema</b> —
     * logging the omission.
     *
     * <p>This is the one silent, destructive failure mode this surface has, and the reason it is guarded
     * here rather than left to the consumer. The runner's {@code OutputMapper} iterates the binding list,
     * so an empty one yields an empty column map — and {@code GroupWorkerService} then still writes the
     * grain key plus the five provenance columns and still stamps the group as computed. Against a
     * {@code ReplacingMergeTree} keyed on the grain that is an <b>upsert of a row whose label columns are
     * all NULL over an existing, already-paid-for label</b>, never retried because the stamp landed, with
     * the model call made and charged first. ADAS cannot catch it downstream either:
     * {@code EnrichWriteValidator}'s allow-list is {@code {bindings} ∪ {grain} ∪ {provenance}}, so a row
     * carrying only grain and provenance is legal by construction.
     *
     * <p>An empty mapping is therefore a bug in the derivation, never a configuration — so the feed refuses
     * to hand it over rather than serving a pipeline that looks healthy. Omitting it costs one pipeline's
     * work until the derivation is fixed; serving it costs labels that were paid for.
     *
     * <p>Scoped to the {@code llm} lane by the shape of the check itself: a Class-1 pipeline is never
     * executed by the runner (it is materialized by ADAS), and its own empty-mapping case is refused at
     * registration and again at every materialize call.
     *
     * <p><b>The missing schema is the same failure wearing different clothes.</b> A version registered in
     * the {@code outputs} shape stores no {@code response_schema}, so the schema the runner sends is the
     * composed one — and the resolver deliberately <em>swallows</em> a composition failure so that one
     * drifted target cannot fail the whole listing. Served that way the entry looks healthy, carries a
     * full output mapping, and has no schema: {@code response_format} is never sent, the model answers
     * free-form, and every row lands in the DLQ at validation, with the call made and charged first. Same
     * class of silence, same answer — omit it, and let the composer's own refusal (which the resolver has
     * already logged, and which {@code POST /v1/pipelines/&#123;name&#125;/preview} reports in full) say
     * what to fix. A transform that <em>declares</em> no {@code outputs} is a different case and is left
     * alone: a folded legacy declaration with no stored schema called the model unconstrained before this
     * change too, and refusing to serve it now would retire a live pipeline rather than protect it.
     */
    private static List<ResolvedEnrichPipeline> withResolvableOutputMapping(
            List<ResolvedEnrichPipeline> resolved) {
        final List<ResolvedEnrichPipeline> servable = new ArrayList<>(resolved.size());
        for (final ResolvedEnrichPipeline entry : resolved) {
            // Logged at ERROR only for an ENABLED pipeline. A disabled one may be a draft being authored,
            // for which an incomplete declaration is the expected state rather than a fault — logging it as
            // an error would fire once per draft per compiled listing call, which is how an alarm stops
            // being read.
            final boolean enabled = entry.pipeline().isEnabled();
            // Completeness first: a draft with no transform has no output mapping either, and "missing
            // transform" names what to author where "resolves to no columns" only names a symptom of it.
            final List<String> missing = entry.spec() == null
                    ? List.of() : EnrichDeclarationCompleteness.missingMembers(entry.declaration());
            if (!missing.isEmpty()) {
                omission(enabled, "omitting pipeline '{}' from the enrich feed: its declaration is incomplete,"
                        + " missing {}", entry.pipeline().getName(), String.join(", ", missing));
                continue;
            }
            if (entry.spec() != null && entry.spec().getOutputBindings().isEmpty()) {
                omission(enabled, "omitting pipeline '{}' from the enrich feed: its output mapping resolves to"
                        + " no columns, and a consumer handed an empty mapping would overwrite existing labels"
                        + " with NULLs and stamp the work as done. Re-register or patch the pipeline to"
                        + " re-derive it.", entry.pipeline().getName());
                continue;
            }
            if (expectsComposedSchema(entry) && entry.effectiveResponseSchema() == null) {
                omission(enabled, "omitting pipeline '{}' from the enrich feed: its transform declares outputs"
                        + " but they compose to no response schema against target '{}', and a consumer handed"
                        + " no schema would call the model unconstrained and DLQ every row it paid for. POST"
                        + " /v1/pipelines/{}/preview reports why.",
                        entry.pipeline().getName(), entry.pipeline().getTarget(), entry.pipeline().getName());
                continue;
            }
            servable.add(entry);
        }
        return servable;
    }

    /** One feed omission: ERROR for an armed pipeline, DEBUG for a disabled one — see the caller. */
    private static void omission(boolean enabled, String message, Object... args) {
        if (enabled) {
            log.error(message, args);
        } else {
            log.debug(message, args);
        }
    }

    /**
     * Whether this entry's transform is one a composed schema is <b>expected</b> of: {@code llm}, carrying
     * the {@code outputs} shape. The type check is load-bearing rather than defensive — a {@code sql}
     * transform declares {@code outputs} too and composes no schema by design, because it asks no model
     * anything, so omitting the type would drop every Class-1 pipeline from the feed.
     */
    private static boolean expectsComposedSchema(ResolvedEnrichPipeline entry) {
        final EnrichSpec.TransformSpec transform = entry.transform();
        return transform != null
                && transform.getType() == TransformType.LLM
                && transform.getOutputs() != null
                && !transform.getOutputs().isEmpty();
    }

    /** One ENRICH pipeline, fully resolved. */
    @Transactional(readOnly = true)
    public ResolvedEnrichPipeline getEnrich(String name) {
        return enrichResolver.resolve(requireEnrich(name));
    }

    /**
     * One ENRICH pipeline, fully resolved <b>for its compiled projection</b> — refused, rather than served
     * with empty members, when the declaration cannot be compiled.
     *
     * <p>The same two omissions the compiled listing makes, raised as a 422 instead, because a named read
     * has a caller to tell: a declaration missing a member its kind requires, named; and one whose outputs
     * compose to no response schema, naming the preview that reports why. A compiled projection with empty
     * members is precisely the shape a consumer turns into a silent data loss, which is why the listing
     * omits such an entry rather than serving it.
     */
    @Transactional(readOnly = true)
    public ResolvedEnrichPipeline getEnrichCompiled(String name) {
        final ResolvedEnrichPipeline resolved = enrichResolver.resolve(requireEnrich(name));
        EnrichDeclarationCompleteness.requireComplete(resolved.declaration(), "be compiled");
        if (expectsComposedSchema(resolved) && resolved.effectiveResponseSchema() == null) {
            throw new PipelineValidationException("pipeline '" + name + "' cannot be compiled: its transform"
                    + " declares outputs but they compose to no response schema against target '"
                    + resolved.pipeline().getTarget() + "'; POST /v1/pipelines/" + name + "/preview reports why");
        }
        return resolved;
    }

    /**
     * Whether {@code name} is an existing, enabled {@code enrich} pipeline. A lookup without refusal: a
     * data-plane handler asks it only after a call was refused, to decide whether the refusal counts as a
     * runner attempt; the refusal itself came from the service it delegated to.
     */
    @Transactional(readOnly = true)
    public boolean isEnabledEnrich(String name) {
        return pipelines.existsByNameAndKindAndEnabledTrue(name, PipelineKind.ENRICH);
    }

    /** Scan-path resolve; see {@link EnrichResolver#resolveForScan}. */
    @Transactional(readOnly = true)
    public ResolvedEnrichPipeline resolveForScan(String name) {
        return enrichResolver.resolveForScan(requireEnrich(name));
    }

    /** Write-path resolve; see {@link EnrichResolver#resolveForWrite}. */
    @Transactional(readOnly = true)
    public ResolvedEnrichPipeline resolveForWrite(String name) {
        return enrichResolver.resolveForWrite(requireEnrich(name));
    }

    /**
     * Saves and flushes, so a storage guard refuses <em>here</em> — inside the mapping below — rather than
     * at commit, where it would surface as a data-access failure and answer 503.
     *
     * <p>Mapped <b>by constraint name</b>, because the guards are not all collisions. The PK on
     * {@code name} and {@code UNIQUE (target)} are a race slipping past the pre-checks and answer 409;
     * every CHECK is a declaration the service layer should have refused first, and reporting one as "name
     * or target already in use" would tell an author their name is taken for a declaration that merely
     * contradicts itself. A draft with a {@code member_select} on a {@code schedule} trigger is the
     * ordinary way to reach one.
     */
    private PipelineEntity save(PipelineEntity pipeline) {
        try {
            return pipelines.saveAndFlush(pipeline);
        } catch (DataIntegrityViolationException ex) {
            final String constraint = constraintOf(ex);
            final String refusal = constraint == null ? null
                    : constraint.endsWith("_fkey") ? "it names a table that does not exist"
                    : CHECK_REFUSALS.get(constraint);
            if (refusal != null) {
                throw new PipelineValidationException("pipeline '" + pipeline.getName() + "': " + refusal);
            }
            // Backstop for a race that slips past the pre-checks (the PK on name, UNIQUE on target).
            throw new PipelineConflictException("pipeline name or target already in use");
        }
    }

    /** The violated constraint's name, from Hibernate's translation of the driver exception, or null. */
    private static String constraintOf(DataIntegrityViolationException ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation && violation.getConstraintName() != null) {
                return violation.getConstraintName();
            }
        }
        return null;
    }

    /**
     * Copies the declaration onto the entity and returns its transform spec <b>unserialized</b>.
     *
     * <p>Deliberately not written to the entity here: the gate <em>normalizes</em> parts of a declaration
     * as it validates — an output binding's column is rewritten to the target's physical {@code source_name}
     * — and it does that by mutating the spec object it is handed. Serializing before validation would
     * store the un-normalized form and silently discard the rewrite, which the write paths depend on. So
     * the object travels to {@link #validateAndStore} and is serialized only once the gate has run.
     */
    private PipelineSpec apply(PipelineEntity pipeline, PipelineRegistration registration, boolean armed) {
        pipeline.setTarget(registration.target());
        // Blank means "follow the target enrichment's parent", re-applied on every write: a patch that
        // changes `target` without naming an input re-defaults against the NEW target rather than silently
        // keeping the old target's source table.
        pipeline.setInputTable(StringUtils.trimToNull(registration.inputTable()));
        pipeline.setFilterSql(StringUtils.trimToNull(registration.filterSql()));
        final PipelineSpec spec = specOf(pipeline.getKind(), registration);
        // In SQL mode an omitted `inputs` is the statement's FROM, read best-effort: a statement that cannot be
        // read yet leaves the input absent, which arming refuses as an incomplete `sql`.
        if (pipeline.getInputTable() == null && sqlOf(spec) != null) {
            pipeline.setInputTable(sqlReader.inputTableOf(sqlOf(spec)).orElse(null));
        }
        requireTablesExist(pipeline);
        pipeline.setTriggerKind(triggerKindOf(pipeline.getKind(), registration.triggerKind(), armed));
        pipeline.setTriggerCron(cronOf(pipeline, registration.triggerCron()));
        pipeline.setTriggerGroupBy(StringUtils.trimToNull(registration.triggerGroupBy()));
        pipeline.setReadyWhen(registration.readyWhen());
        pipeline.setMemberSelect(registration.memberSelect());
        requireNoTriggerContradiction(pipeline);
        requireValidCron(pipeline.getTriggerKind(), pipeline.getTriggerCron(), armed);
        return spec;
    }

    /** The SQL declaration {@code spec} carries, or null for a form-mode aggregate or another kind. */
    private static String sqlOf(PipelineSpec spec) {
        return spec instanceof AggregateSpec aggregate ? aggregate.getSql() : null;
    }

    /**
     * Gives a stored SQL-mode declaration that has no input table the one its statement reads, best-effort —
     * the derivation {@link #apply} runs on a declaration write, for the enable-only path that runs no
     * {@code apply}. A declared input, a form-mode declaration or an unreadable statement is left alone.
     */
    private void deriveSqlInput(PipelineEntity pipeline) {
        if (pipeline.getKind() != PipelineKind.AGGREGATE || pipeline.getInputTable() != null) {
            return;
        }
        final String sql = sqlOf(storedSpec(pipeline, true));
        if (sql != null) {
            sqlReader.inputTableOf(sql).ifPresent(pipeline::setInputTable);
        }
    }

    /**
     * The cron as declared, or — for an {@code enrich} {@code schedule} trigger that declares none — the
     * name-derived default from {@link DefaultSchedule}. Resolved on every write, a draft included, so the
     * stored declaration always says when the pipeline runs. An {@code aggregate} cron is never defaulted:
     * a rollup's refresh cost scales with its cadence, so its absence stays an arming refusal.
     */
    private static String cronOf(PipelineEntity pipeline, String declared) {
        final String cron = StringUtils.trimToNull(declared);
        if (cron == null && pipeline.getKind() == PipelineKind.ENRICH
                && pipeline.getTriggerKind() == TriggerKind.SCHEDULE) {
            return DefaultSchedule.cronFor(pipeline.getName());
        }
        return cron;
    }

    /** Refuses a row-confined caller arming a pipeline over these tables; see {@link RowPolicyFilter#requireUnconfinedPipeline}. */
    private void requireUnconfinedReads(String input, String target, String purpose) {
        rowPolicies.requireUnconfinedPipeline(input, target, purpose);
    }

    /**
     * A declaration patch that omits {@code target} keeps the stored one. Required for the authoring loop
     * rather than a nicety — {@link #requireTargetAvailable} refuses a blank target before anything else,
     * so without it every draft save would have to restate {@code target} forever — and it does not reopen
     * the merge-patch hazard: {@code target} is required at registration and can never legitimately become
     * absent, so "omitted" has no "cleared" reading to lose, unlike {@code filter} or a stored schema.
     */
    private static PipelineRegistration withStickyTarget(PipelineRegistration registration, PipelineEntity stored) {
        if (StringUtils.isNotBlank(registration.target())) {
            return registration;
        }
        return new PipelineRegistration(registration.name(), registration.kind(), stored.getTarget(),
                registration.inputTable(), registration.filterSql(), registration.spec(),
                registration.triggerKind(), registration.triggerCron(), registration.triggerGroupBy(),
                registration.readyWhen(), registration.memberSelect(), registration.enabled());
    }

    /**
     * The tables a declaration names must exist, on <b>every</b> write, armed or not. Not part of the
     * deferred gate: both columns carry a foreign key to {@code table_definition}, which the database
     * enforces on every row, so a draft naming a table that does not exist would otherwise be refused at
     * flush as an integrity violation — reported as a name collision that did not happen. Whether the table
     * is the right <em>kind</em> of table, ACTIVE, scannable and so on stays the gate's question.
     */
    private void requireTablesExist(PipelineEntity pipeline) {
        if (StringUtils.isNotBlank(pipeline.getTarget()) && !definitions.existsById(pipeline.getTarget())) {
            throw new PipelineValidationException("target table '" + pipeline.getTarget() + "' does not exist");
        }
        if (pipeline.getInputTable() != null && !definitions.existsById(pipeline.getInputTable())) {
            throw new PipelineValidationException("input table '" + pipeline.getInputTable() + "' does not exist");
        }
    }

    /**
     * The two trigger contradictions refused on <b>every</b> write, armed or not.
     *
     * <p>They are not incompleteness — they are two stated members that cannot both be true, which no
     * further authoring resolves — and the database refuses both on every row
     * ({@code pipeline_member_select_only_group}, {@code pipeline_aggregate_is_scheduled}), so deferring
     * them would only move the author's error from a named 422 to an integrity violation at flush. A
     * trigger kind that is not stated yet contradicts nothing, which is also how the CHECKs read it.
     */
    private static void requireNoTriggerContradiction(PipelineEntity pipeline) {
        final TriggerKind kind = pipeline.getTriggerKind();
        if (kind == null) {
            return;
        }
        if (pipeline.getKind() == PipelineKind.AGGREGATE && kind != TriggerKind.SCHEDULE) {
            throw new PipelineValidationException("trigger.kind '" + kind.name().toLowerCase(Locale.ROOT)
                    + "' is not supported for an '" + PipelineKind.AGGREGATE.code() + "' pipeline, which ADAS"
                    + " refreshes on its own cron; declare trigger.kind 'schedule' or omit it");
        }
        if (pipeline.getMemberSelect() != null && kind != TriggerKind.GROUP) {
            throw new PipelineValidationException("trigger.member_select is only valid for a group pipeline");
        }
    }

    /**
     * A disabled pipeline writes to its target by <b>no route</b>: the pipeline-scoped write-back and the
     * Class-1 materialize refuse it with 409, as the aggregate on-demand refresh does. A state conflict, not a
     * validation failure — the call may be perfectly good and only the pipeline's state wrong.
     *
     * <p>Nothing legitimate relied on the old tolerance. The Enrichment Runner reads only the
     * {@code enabled=true} feed, drops a disabled pipeline from its cache, and stalls its own backfill of one
     * until it is re-enabled, so the "paused backfill" these paths once tolerated a disabled pipeline for was
     * never reachable from it. The honest cost is work already in flight when an operator disables a
     * pipeline: a runner that has not yet synced the disable has its write refused. Disabling means "stop
     * now", and that is what it now does. The two read paths — scan and group assembly — stay open, because
     * they write nothing and a disabled pipeline's preview samples through them.
     */
    public static void requireEnabledToWrite(PipelineEntity pipeline, String operation) {
        if (!pipeline.isEnabled()) {
            throw new PipelineConflictException("pipeline '" + pipeline.getName() + "' is disabled and cannot "
                    + operation + ": a disabled pipeline writes to its target by no route. Enable it with PATCH "
                    + "/v1/pipelines/" + pipeline.getName() + " first");
        }
    }

    /**
     * The entity-level completeness a stored declaration must meet before the kind gate runs over it on
     * the way to being armed: the same trigger-kind and cron rules {@link #apply} asks of a declaration
     * write that arms. Asked by an enable-only patch, which never runs {@link #apply}, and by the dry-run
     * preview of a disabled pipeline — which reproduces every arming refusal, so that "preview passes"
     * means "arming will pass". Reads the entity and changes nothing.
     */
    public static void requireArmableTrigger(PipelineEntity pipeline) {
        requireValidCron(triggerKindOf(pipeline.getKind(), pipeline.getTriggerKind(), true),
                pipeline.getTriggerCron(), true);
    }

    /** An absent declaration is the kind's empty one, so a registration is never stored specless. */
    private static PipelineSpec specOf(PipelineKind kind, PipelineRegistration registration) {
        if (registration.spec() != null) {
            return registration.spec();
        }
        return kind == PipelineKind.ENRICH ? new EnrichSpec() : new AggregateSpec();
    }

    /**
     * The trigger kind, defaulting to {@code SCHEDULE} for an aggregate pipeline that named none.
     *
     * <p>The default is required rather than tidy: {@code trigger} has always been optional on the
     * aggregate request and nothing read its {@code kind}, so without this every aggregate registration
     * body that omits it — or sends {@code trigger} with only a {@code cron} — would become a 422 the day
     * {@code trigger_kind} became mandatory. It applies on every write, since an aggregate has only the one
     * trigger it could mean.
     *
     * <p>The refusal for an {@code enrich} pipeline that names none is <b>arm-scoped</b>: a draft that has
     * not picked its trigger yet is stored with none, and is refused when it is armed.
     */
    private static TriggerKind triggerKindOf(PipelineKind kind, TriggerKind declared, boolean armed) {
        if (declared != null) {
            return declared;
        }
        if (kind == PipelineKind.AGGREGATE) {
            return TriggerKind.SCHEDULE;
        }
        if (!armed) {
            return null;
        }
        throw new PipelineValidationException("trigger.kind is required (allowed: schedule, group)");
    }

    /**
     * When ADAS should next run this pipeline, or null when nothing ADAS drives should.
     *
     * <p>Null for every ENRICH pipeline, and that is one of three independent guards against the in-process
     * aggregate tick picking one up — the others being the kind filter in
     * {@link PipelineRepository#findDue} and the assertion in the refresh service. One guard would not be
     * enough: an aggregate refresh against an enrichment target writes wrong rows rather than failing.
     */
    private static Instant armedAt(PipelineEntity pipeline, boolean enabled) {
        return enabled && pipeline.getKind() == PipelineKind.AGGREGATE
                ? AggregateScheduler.nextRun(pipeline.getTriggerCron())
                : null;
    }

    /** Records that the pipeline changed: one {@code generation} bump and a fresh {@code updated_at}. */
    private static void stamp(PipelineEntity pipeline, Instant at) {
        pipeline.setGeneration(pipeline.getGeneration() + 1);
        pipeline.setUpdatedAt(at);
    }

    /**
     * One INFO line naming the {@link #stamp} bump, its cause and the acting principal. The generation qualifies
     * the materialized position, the runner's scan state and every row's stamp, so a bump nobody can see
     * explains a rollup freeze or a runner re-scan only after a database read. Logged once the write has been
     * accepted, so a registration or patch refused at validation or by a constraint reports no bump.
     */
    private void logGenerationChange(PipelineEntity saved, String cause) {
        log.info("pipeline {} generation {} -> {} (cause={}, actor={})",
                saved.getName(), saved.getGeneration() - 1, saved.getGeneration(), cause, actor());
    }

    /**
     * The authenticated principal behind this mutation, or {@code -} when there is none — an anonymous
     * token in {@code security.mode=none}, or no security context at all. Never a fabricated identity, and
     * a failure to read one degrades to {@code -} rather than failing the write it describes.
     */
    private String actor() {
        try {
            final String author = claims.isAuthenticated() ? claims.getAuthor() : null;
            return StringUtils.isBlank(author) ? "-" : author;
        } catch (RuntimeException ex) {
            log.debug("could not read the acting principal for a generation line", ex);
            return "-";
        }
    }

    /**
     * Drops the execution state ADAS owns for this pipeline's kind, so a new declaration does not inherit
     * a position that described the old one.
     *
     * <p>For an aggregate pipeline that is the scan position and everything that only described it:
     * {@code has_more}, which refers to a window that no longer exists; the last error, recorded against
     * the declaration being replaced; and the clamp the last run recorded. Re-stamping {@code rebuilt_at}
     * is what makes a re-declaration count as a rebuild — every group is about to be recomputed from
     * current enrichment values, so a re-derivation recorded before this instant can no longer have left a
     * stale value behind, and leaving the baseline where it was would keep telling the operator to rebuild
     * a rollup they just rebuilt, which is how an indicator stops being read.
     *
     * <p><b>An ENRICH pipeline is skipped, and not because the reset would be harmless.</b> Its cursor
     * lives in the Enrichment Runner, so clearing the columns here is a no-op — but {@code rebuilt_at} is
     * the <em>rollup</em> rebuild anchor, and stamping it on a row that is not a rollup writes a fact that
     * does not apply to it. Its Class-1 materialized position is deliberately not reset here either: that
     * is {@link PipelineRepository#discardMarkOfOtherGeneration}, keyed on {@code generation}, which the
     * bump this patch performs handles on its own.
     */
    private void resetCursor(PipelineEntity pipeline) {
        if (pipeline.getKind() == PipelineKind.ENRICH) {
            return;
        }
        pipeline.setCursorVersion(null);
        pipeline.setCursorIdentity(null);
        pipeline.setHasMore(false);
        pipeline.setLastError(null);
        pipeline.setClampedBy(null);
        pipeline.setClampedToVersion(null);
        pipeline.setClampedToIdentity(null);
        pipeline.setRebuiltAt(PipelineTimestamps.now());
    }

    /**
     * Runs the gate the pipeline's {@code kind} selects over {@code spec} when the write arms the pipeline,
     * or only the gate's <b>derivation</b> half when it does not, then stores the spec that leaves behind —
     * see {@link #apply} for why the order matters.
     *
     * <p><b>The gate is two jobs, and only one of them is deferred.</b> It refuses, and it also derives:
     * {@code group_by} from the target's {@code ordering_key}, {@code output_bindings} from the transform's
     * outputs. Neither is authored and the gate is their only writer, while the spec handed in here is a
     * fresh one built from the request, which carries neither. So a draft save still runs the derivation,
     * best-effort, and where it cannot derive a member it carries the <b>stored</b> one over rather than
     * writing an empty one — an erased {@code output_bindings} would disarm the catalog's drop/rename guard
     * for the columns this pipeline writes. Only the refusal is deferred, to the write that arms the
     * pipeline and to preview.
     *
     * <p>{@code armed} says whether this write leaves the pipeline <b>enabled</b>, and it decides whether
     * the gate runs at all. So the enrich gate always runs its armed checks — the {@code request_template}'s
     * placeholders held to {@code transform.inputs} included — because a disabled write never reaches it:
     * a template whose placeholders bind to nothing yet is an author composing a declaration, not an error.
     * It is passed in rather than read off the entity because the declaration path validates
     * <em>before</em> the flag is applied, so the entity still holds the old value at that point.
     *
     * @param carryStored whether a derived member this write cannot re-derive may be carried over from the
     *     spec stored on the entity — true only for a re-declaration that keeps the stored target and input
     */
    private void validateAndStore(PipelineEntity pipeline, PipelineSpec spec, boolean armed, boolean carryStored) {
        if (!armed) {
            deriveBestEffort(pipeline, spec, carryStored);
            pipeline.setSpec(specCodec.write(spec));
            return;
        }
        switch (pipeline.getKind()) {
            case AGGREGATE -> aggregateValidator.validate(pipeline.getName(), pipeline.getInputTable(),
                    pipeline.getTarget(), pipeline.getFilterSql(), (AggregateSpec) spec, "aggregate");
            case ENRICH -> enrichValidator.validate(
                    new EnrichDeclaration(pipeline, (EnrichSpec) spec));
            // Every kind the registry can store is above; a declared-but-unimplemented one is refused at
            // registration, so reaching here means a stored row of a kind this build cannot run.
            default -> throw new PipelineValidationException("kind '" + pipeline.getKind().code()
                    + "' is not implemented; supported kinds: " + PipelineKind.implementedCodes());
        }
        pipeline.setSpec(specCodec.write(spec));
    }

    /**
     * The derivation half of the gate alone, for a write that leaves the pipeline disabled. See
     * {@link #validateAndStore}.
     *
     * <p>The carried-over member is the one derived for the <b>stored</b> target and input, so it is carried
     * only while both are unchanged: {@code group_by} names the old target's keys and {@code output_bindings}
     * its columns, and kept across a repoint either would protect, or write, columns of a table this
     * pipeline no longer targets. A repointed draft that cannot derive stores an empty member instead.
     *
     * <p>Called before {@link #validateAndStore} overwrites the entity's spec — {@link #apply} never touches
     * it — so what it reads there is still the stored one. Read lazily, only when derivation fails: a row
     * whose stored spec no longer parses must stay repairable by a re-declaration, so an unreadable one
     * counts as nothing stored rather than a 500.
     *
     * @param carryStored whether the stored spec may supply a member this write could not re-derive
     */
    private void deriveBestEffort(PipelineEntity pipeline, PipelineSpec spec, boolean carryStored) {
        switch (pipeline.getKind()) {
            case AGGREGATE -> {
                final AggregateSpec aggregate = (AggregateSpec) spec;
                if (aggregate.getSql() != null) {
                    // SQL mode derives no group keys: the statement's GROUP BY declares them, and a derived
                    // group_by stored beside sql would be served as a form-mode member the author never wrote.
                    aggregate.setGroupBy(new ArrayList<>());
                    aggregate.setMeasures(new ArrayList<>());
                } else if (!aggregateValidator.deriveGroupKeysBestEffort(
                        pipeline.getInputTable(), pipeline.getTarget(), aggregate)) {
                    aggregate.setGroupBy(storedSpec(pipeline, carryStored) instanceof AggregateSpec previous
                            && previous.getGroupBy() != null ? previous.getGroupBy() : new ArrayList<>());
                }
                if (aggregate.getMeasures() == null) {
                    aggregate.setMeasures(new ArrayList<>());
                }
            }
            case ENRICH -> {
                final EnrichSpec enrich = (EnrichSpec) spec;
                if (!enrichValidator.deriveOutputBindingsBestEffort(new EnrichDeclaration(pipeline, enrich))) {
                    enrich.setOutputBindings(storedSpec(pipeline, carryStored) instanceof EnrichSpec previous
                            && previous.getOutputBindings() != null
                            ? stillDeclared(previous.getOutputBindings(), enrich) : new ArrayList<>());
                }
            }
            default -> throw new PipelineValidationException("kind '" + pipeline.getKind().code()
                    + "' is not implemented; supported kinds: " + PipelineKind.implementedCodes());
        }
    }

    /**
     * The carried-over bindings whose output a typed transform in this write still declares. A binding
     * for an output the write removed would keep its column guarded against a drop by a pipeline that no
     * longer writes it; an untyped or absent transform restated nothing, so everything is carried.
     */
    private static List<OutputBinding> stillDeclared(List<OutputBinding> bindings, EnrichSpec enrich) {
        final EnrichSpec.TransformSpec transform = enrich.getTransform();
        if (transform == null || transform.getType() == null) {
            return bindings;
        }
        final Set<String> outputs = transform.effectiveOutputVars().stream()
                .map(TransformVar::getName)
                .collect(Collectors.toSet());
        return bindings.stream()
                .filter(binding -> outputs.contains(binding.getVar()))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    /**
     * Whether the entity, as stored, names every member its kind authors. An unreadable stored spec counts
     * as incomplete: there is nothing whole to protect, and a re-declaration is how such a row is repaired.
     */
    private boolean isStoredDeclarationComplete(PipelineEntity pipeline) {
        final PipelineSpec stored = storedSpec(pipeline, true);
        if (stored == null) {
            return false;
        }
        // An SQL-mode declaration is complete when it holds a statement, the input that statement was read for and
        // its cron. Not re-parsed: a parse plans as the current caller, so a caller who may not see a column the
        // statement reads would see a complete declaration as incomplete, and its patch could erase the statement.
        if (sqlOf(stored) != null) {
            return StringUtils.isNotBlank(sqlOf(stored)) && pipeline.getInputTable() != null
                    && StringUtils.isNotBlank(pipeline.getTriggerCron());
        }
        return missingAuthoredMembers(pipeline, stored).isEmpty();
    }

    /**
     * Refuses a patch that would leave a complete declaration incomplete — see {@link #patch}. A member
     * nested in a block the patch restated is the caller's deliberate choice, not an omission, so only the
     * members of blocks it left out are asked.
     */
    private void requireStillComplete(
            PipelineEntity pipeline, PipelineSpec spec, PipelineRegistration declared) {
        final List<String> missing = missingAuthoredMembers(pipeline, spec).stream()
                .filter(member -> !restatedByPatch(member, spec, declared))
                .toList();
        if (!missing.isEmpty()) {
            throw new PipelineValidationException("pipeline '" + pipeline.getName() + "': this patch would leave"
                    + " its complete declaration incomplete, missing " + String.join(", ", missing) + ". A"
                    + " declaration patch replaces every member but target, so restate the ones to keep (GET"
                    + " /v1/pipelines/" + pipeline.getName() + "?view=source returns them); to start the"
                    + " declaration over, delete and re-register the pipeline");
        }
    }

    /**
     * Whether {@code member} sits inside a block the patch restated: an {@code enrich} transform naming its
     * {@code type}, or a trigger naming its {@code kind}. The type and the kind are what make a block a
     * restatement — a patch carrying only a runtime knob maps to a transform with neither.
     */
    private static boolean restatedByPatch(String member, PipelineSpec spec, PipelineRegistration declared) {
        if (member.startsWith("transform.")) {
            return spec instanceof EnrichSpec enrich && enrich.getTransform() != null
                    && enrich.getTransform().getType() != null;
        }
        if (member.startsWith("trigger.")) {
            return declared.triggerKind() != null;
        }
        return false;
    }

    /**
     * The members an author writes that this declaration lacks, in wire spelling. Derived members — the
     * output mapping, the group keys — are not asked: a draft may lack them because its tables are not
     * ready, which is not something a patch took away.
     *
     * <p>An {@code aggregate} in SQL mode authors {@code sql} and {@code trigger.cron} only — never
     * {@code inputs}, {@code measures} or {@code group_by}, which the statement carries. Its {@code sql} is never
     * reported missing here: a patched declaration in SQL mode carries a statement, sent or carried over, and it is
     * taken as sent, readable or not — that is how a paused statement is edited.
     */
    private List<String> missingAuthoredMembers(PipelineEntity pipeline, PipelineSpec spec) {
        if (spec instanceof EnrichSpec enrich) {
            return EnrichDeclarationCompleteness.missingAuthoredMembers(new EnrichDeclaration(pipeline, enrich));
        }
        if (!(spec instanceof AggregateSpec aggregate)) {
            return List.of();
        }
        final List<String> missing = new ArrayList<>();
        if (aggregate.getSql() != null) {
            if (StringUtils.isBlank(pipeline.getTriggerCron())) {
                missing.add("trigger.cron");
            }
            return missing;
        }
        if (pipeline.getInputTable() == null) {
            missing.add("inputs");
        }
        if (aggregate.getMeasures() == null || aggregate.getMeasures().isEmpty()) {
            missing.add("measures");
        }
        if (StringUtils.isBlank(pipeline.getTriggerCron())) {
            missing.add("trigger.cron");
        }
        return missing;
    }

    /** The spec still stored on the entity, or null when it may not be carried or does not parse. */
    private PipelineSpec storedSpec(PipelineEntity pipeline, boolean carryStored) {
        if (!carryStored || pipeline.getSpec() == null) {
            return null;
        }
        try {
            return specCodec.read(pipeline);
        } catch (IllegalStateException ex) {
            log.debug("pipeline '{}': stored spec is unreadable, so nothing is carried over", pipeline.getName(), ex);
            return null;
        }
    }

    /**
     * The kind gate. The discriminator's closed set carries the kinds this object is meant to grow into, so
     * a caller naming one gets told it is not implemented yet — a 422 listing what is — instead of the
     * generic binding failure an omitted enum member would produce.
     */
    private void requireSupportedKind(PipelineRegistration registration) {
        if (registration.kind() == null) {
            throw new PipelineValidationException("kind is required; supported kinds: "
                    + PipelineKind.implementedCodes());
        }
        if (!registration.kind().isImplemented()) {
            throw new PipelineValidationException("kind '" + registration.kind().code() + "' is not "
                    + "implemented; supported kinds: " + PipelineKind.implementedCodes());
        }
    }

    /**
     * One writer per target, across both kinds. Checked up front so the failure names the pipeline that
     * already holds the target, and backstopped by the {@code UNIQUE (target)} constraint for a race that
     * slips past this.
     */
    private void requireTargetAvailable(String target, String selfName) {
        if (StringUtils.isBlank(target)) {
            throw new PipelineValidationException("target must not be blank");
        }
        pipelines.findByTarget(target)
                .filter(existing -> !existing.getName().equals(selfName))
                .ifPresent(existing -> {
                    throw new PipelineConflictException("target '" + target + "' is already written by "
                            + "pipeline '" + existing.getName() + "' (one pipeline per target)");
                });
    }

    /**
     * Refuses a declaration edit only where the kind's target could then hold rows computed two ways; see
     * {@link #patch} for why that is the aggregate path alone.
     */
    private static void requireDeclarationEditable(PipelineEntity pipeline) {
        if (pipeline.isEnabled() && pipeline.getKind() == PipelineKind.AGGREGATE) {
            throw new PipelineConflictException("pipeline '" + pipeline.getName() + "' is enabled; disable "
                    + "it before editing its declaration, so the target cannot hold rows computed two "
                    + "different ways");
        }
    }

    /**
     * A cron that does not parse would silently never fire, so it is rejected up front — and it is required
     * exactly for a {@code SCHEDULE} trigger and forbidden otherwise, which the
     * {@code pipeline_cron_iff_schedule} constraint backstops.
     *
     * <p>Conditional on the trigger kind rather than unconditional, as it was while the only trigger was a
     * cron: a {@code group} pipeline has no cron, and running the old check over one
     * would refuse every enrich declaration.
     *
     * <p>The parse check runs on every write — an unparseable cron is a malformed value, not an unfinished
     * one. The required-iff-scheduled pair is <b>arm-scoped</b>, as the constraint now is: "picked a
     * schedule, has not typed the cron yet" is exactly the state a draft is in.
     */
    private static void requireValidCron(TriggerKind triggerKind, String cron, boolean armed) {
        if (!armed) {
            if (StringUtils.isNotBlank(cron) && !CronExpression.isValidExpression(cron)) {
                throw new PipelineValidationException("trigger.cron '" + cron + "' is not a valid cron expression");
            }
            return;
        }
        if (triggerKind != TriggerKind.SCHEDULE) {
            if (StringUtils.isNotBlank(cron)) {
                throw new PipelineValidationException("trigger.cron is only valid for a scheduled pipeline");
            }
            return;
        }
        if (StringUtils.isBlank(cron)) {
            throw new PipelineValidationException("trigger.cron is required for a scheduled pipeline");
        }
        if (!CronExpression.isValidExpression(cron)) {
            throw new PipelineValidationException("trigger.cron '" + cron + "' is not a valid cron expression");
        }
    }

    private PipelineEntity require(String name) {
        return pipelines.findById(name)
                .orElseThrow(() -> new PipelineNotFoundException("pipeline '" + name + "' not found"));
    }

    /**
     * The pipeline named, asserted to be of the enrich kind. A data-plane call addressed to an aggregate
     * pipeline is a 422 rather than a 404: the pipeline exists, and saying so is more useful than pretending
     * it does not.
     */
    private PipelineEntity requireEnrich(String name) {
        final PipelineEntity pipeline = require(name);
        if (pipeline.getKind() != PipelineKind.ENRICH) {
            throw new PipelineValidationException("pipeline '" + name + "' is of kind '"
                    + pipeline.getKind().code() + "'; this endpoint serves the '"
                    + PipelineKind.ENRICH.code() + "' kind");
        }
        return pipeline;
    }
}
