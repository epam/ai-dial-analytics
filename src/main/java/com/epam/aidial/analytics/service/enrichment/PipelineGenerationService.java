package com.epam.aidial.analytics.service.enrichment;

import com.epam.aidial.analytics.exception.DeclarationUnresolvableException;
import com.epam.aidial.analytics.exception.PipelineValidationException;
import com.epam.aidial.analytics.service.enrichment.model.EnrichDeclaration;
import com.epam.aidial.analytics.service.enrichment.model.TransformType;
import com.epam.aidial.analytics.service.enrichment.validation.EnrichSpecValidator;
import com.epam.aidial.analytics.service.pipeline.AggregateSpecValidator;
import com.epam.aidial.analytics.service.pipeline.PipelineDeclaration;
import com.epam.aidial.analytics.service.pipeline.PipelineEntity;
import com.epam.aidial.analytics.service.pipeline.PipelineKind;
import com.epam.aidial.analytics.service.pipeline.PipelineRepository;
import com.epam.aidial.analytics.service.pipeline.PipelineSpecCodec;
import com.epam.aidial.analytics.service.pipeline.PipelineTimestamps;
import com.epam.aidial.analytics.service.pipeline.model.EnrichSpec;
import com.epam.aidial.analytics.service.table.PipelineGenerationBump;
import com.epam.aidial.analytics.service.table.PipelineInputDependents;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The registry's side of {@link PipelineGenerationBump} and {@link PipelineInputDependents}: when a source
 * repoints its {@code version_column}, every ENRICH pipeline reading that source is marked changed so the
 * runner sync feed hands it back with the new name; and the catalog's three dependency questions are
 * answered here so their answers cannot disagree.
 *
 * <p>A pipeline reads its <b>declared</b> input, or its target enrichment's parent when it declares none,
 * so the affected set is the union of two disjoint halves: pipelines that name this source directly, and
 * pipelines that derive it through an enrichment of it. One whose target hangs off this source but which
 * declares another read source is deliberately excluded — bumping it would be a change signal for a rename
 * it never sees. Within each half every pipeline counts, not only the materialized ones: a pipeline read
 * publishes the version column whatever state its target is in.
 *
 * <p><b>The bump is scoped to {@code ENRICH}.</b> Cross-kind, a repoint on {@code dial_usage_log} would
 * move the {@code generation} <em>and {@code updated_at}</em> of every rollup reading it, for a signal no
 * aggregate consumer diffs — and {@code updated_at} decides a pipeline's place in every listing. The
 * derive-through-target half needs no such filter: an aggregate pipeline always names its input
 * ({@code pipeline_input_required_unless_enrich}), so a null {@code input_table} already selects ENRICH
 * rows only.
 *
 * <p>{@code MANDATORY} propagation on the bump, deliberately. It is only meaningful together with the
 * repoint that caused it, so it joins the catalog write's transaction; a call outside one is a wiring
 * mistake and fails loudly here rather than committing a bump on its own.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineGenerationService implements PipelineGenerationBump, PipelineInputDependents {

    private final PipelineRepository pipelines;
    private final TableDefinitionRepository definitions;
    private final EnrichSpecValidator enrichGovernance;
    private final PipelineSpecCodec specCodec;
    private final AggregateSpecValidator aggregateGovernance;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void onVersionColumnRepointed(String source) {
        final List<String> enrichments = definitions.findBySourceTable(source).stream()
                .map(TableDefinitionEntity::getName)
                .toList();
        final List<PipelineEntity> affected =
                new ArrayList<>(pipelines.findByInputTableAndKind(source, PipelineKind.ENRICH));
        if (!enrichments.isEmpty()) {
            affected.addAll(pipelines.findByTargetInAndInputTableIsNull(enrichments));
        }
        if (affected.isEmpty()) {
            return;
        }
        // updated_at as well as generation: the sync feed selects on updated_at (`updated_since`), so a
        // bumped generation the feed would never return is no signal at all.
        final Instant now = PipelineTimestamps.now();
        for (final PipelineEntity pipeline : affected) {
            bump(pipeline, now, "version_column_repoint");
        }
        pipelines.saveAll(affected);
        log.info("Source '{}' repointed its version column; bumped {} pipeline generation(s)",
                source, affected.size());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void onTargetColumnsChanged(String enrichment) {
        final PipelineEntity pipeline = pipelines.findByTarget(enrichment).orElse(null);
        if (pipeline == null || pipeline.getKind() != PipelineKind.ENRICH) {
            return;
        }
        final EnrichSpec spec = specCodec.readEnrich(pipeline);
        final EnrichDeclaration declaration = new EnrichDeclaration(pipeline, spec);
        final TransformType type;
        if (!pipeline.isEnabled()) {
            // A disabled pipeline is a draft, stored without the gate, so an output mapping that does not
            // resolve against its target is a stage of authoring rather than a fault — and throwing here
            // would fail this schema patch after its ALTER has already run. Derived best-effort instead, as
            // a draft save derives it; where it cannot be, the stored mapping stands and nothing is bumped.
            if (!enrichGovernance.deriveOutputBindingsBestEffort(declaration)) {
                log.debug("target '{}' changed columns; draft pipeline '{}' does not re-derive against it,"
                        + " so its output mapping is left as stored", enrichment, pipeline.getName());
                return;
            }
            type = spec.getTransform().getType();
        } else {
            try {
                type = enrichGovernance.rederiveOutputBindings(declaration).orElse(null);
            } catch (PipelineValidationException ex) {
                // The BACKSTOP, not the guard. A change that leaves this pipeline's output mapping
                // unresolvable — dropping or renaming a column it writes — is refused before any DDL is
                // rendered, by TableSchemaService.requireNoPipelineWrites, precisely because this transaction
                // begins after the ClickHouse ALTER has already been applied and rolling it back here would
                // leave the two stores disagreeing about which columns exist. Reaching this branch therefore
                // means the pre-check missed a case, and committing a schema the pipeline cannot write to is
                // still the worse of the two outcomes: the alternative is a pipeline that looks healthy,
                // resolves to nothing, and is only stopped later by the feed's own guard. Named, because the
                // caller is patching a table and the reason lives on a pipeline.
                throw new PipelineValidationException("cannot change the columns of '" + enrichment
                        + "': pipeline '" + pipeline.getName() + "' writes it, and the change leaves its"
                        + " output mapping unresolvable (" + ex.getMessage() + "). Re-point or delete the"
                        + " pipeline first, or keep the columns its transform declares outputs for.");
            }
        }
        if (type == null) {
            // The transform or the target no longer resolves. Nothing is rewritten and nothing is bumped:
            // an operator's schema patch must not fail, nor silently re-derive from a half-resolved
            // declaration, because a pipeline they were not touching has drifted.
            log.warn("target '{}' changed columns, but its pipeline '{}' does not currently resolve;"
                    + " its output mapping is left as stored", enrichment, pipeline.getName());
            return;
        }
        // Always rewrite the mapping - the stored copy is what three fail-closed write paths read, so it
        // must match the target it describes whichever lane executes it.
        pipeline.setSpec(specCodec.write(spec));
        if (type != TransformType.LLM) {
            // A Class-1 pipeline is NOT bumped, and that is the load-bearing half of this method. ADAS
            // re-resolves and re-analyzes on every materialize call, so the new column is picked up on the
            // next tick with no signal needed - while a generation change here would null
            // materialized_through_version, materialized_through_identity and drained_at, and every rollup
            // reading this enrichment is then held back entirely by the freshness clamp, writing zero rows
            // per tick until the next materialize re-records a position. Nothing is re-scanned: the
            // materialize cursor is caller-held, in the runner, and no bump moves it. But adding one column
            // would still cost a clamp blackout it did not cost before the binding patch was removed.
            log.info("target '{}' changed columns; re-derived the output mapping of Class-1 pipeline '{}'"
                    + " without bumping its generation", enrichment, pipeline.getName());
            pipelines.save(pipeline);
            return;
        }
        // updated_at as well as generation, for the same reason as a version-column repoint: the sync feed
        // selects on updated_at, so a bumped generation the feed would never return is no signal at all.
        bump(pipeline, PipelineTimestamps.now(), "target_columns_changed");
        pipelines.save(pipeline);
        log.info("target '{}' changed columns; re-derived the output mapping of pipeline '{}'",
                enrichment, pipeline.getName());
    }

    /**
     * One generation bump, logged per pipeline with its old and new value — never only a count, since the
     * line is how an operator connects a runner re-scan or a rollup freeze to the schema edit behind it. A
     * cascaded bump carries no actor: the schema edit's principal is not threaded down here, and the line
     * says so rather than inventing one.
     */
    private static void bump(PipelineEntity pipeline, Instant at, String cause) {
        final long previous = pipeline.getGeneration();
        pipeline.setGeneration(previous + 1);
        pipeline.setUpdatedAt(at);
        log.info("pipeline {} generation {} -> {} (cause={}, actor=-)",
                pipeline.getName(), previous, pipeline.getGeneration(), cause);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> pipelinesReadingEnrichment(String enrichment) {
        // Read as a detached projection, never as managed entities: this runs inside the catalog's table
        // DELETE transaction, and a loaded PipelineEntity would be flushed as an UPDATE at commit against a
        // row the delete has already cascaded away. See PipelineDeclaration.
        return declarations().stream()
                .filter(declaration -> referencedEnrichments(declaration).contains(enrichment))
                .map(PipelineDeclaration::name)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> pipelinesReadingEnrichmentColumn(String enrichment, String column) {
        // The qualified flat name is the only spelling an enrichment column has in a declaration's
        // namespace, so a declaration references this column exactly when it mentions that one name.
        final String qualified = enrichment + "." + column;
        return declarations().stream()
                .filter(declaration -> declaredFieldsOf(declaration).contains(qualified))
                .filter(declaration -> referencedEnrichments(declaration).contains(enrichment))
                .map(PipelineDeclaration::name)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> pipelinesReadingSourceColumn(String source, String column) {
        // Bare name, not qualified: within a declaration's namespace a source column is spelled exactly as
        // written. Scoped to declarations that actually read this table, so an unrelated pipeline naming
        // the same common column ("user_id", say) on its own source does not freeze this one's schema.
        // For an enrich declaration, readFieldsOf rather than declaredFieldsOf: the latter sees only
        // qualified tokens in SQL fragments, so an expression reading `length(deployment)` — the exact shape
        // of a rename-repoint attack — was invisible to this guard. An aggregate keeps its own resolver.
        // In form mode it returns its group keys and measure columns but, like the old enrich one, only
        // qualified tokens from `filter_sql` and `measure.where` — so a bare column read there is not
        // protected here. That is tolerated rather than closed because the aggregate refresh re-runs the
        // whole aggregate gate, entitlement and sensitivity propagation included, before every run. In SQL
        // mode the tolerance does not apply: the resolver scans the stored statement's bare identifiers too
        // (its INSERT head excluded, so a target column name does not pin an input column), and every bare
        // column the statement reads is covered.
        return declarations().stream()
                .filter(declaration -> source.equals(readSourceOf(declaration)))
                .filter(declaration -> (declaration.kind() == PipelineKind.ENRICH
                        ? enrichGovernance.readFieldsOf(declaration)
                        : declaredFieldsOf(declaration)).contains(column))
                .map(PipelineDeclaration::name)
                .toList();
    }

    /** The table a declaration reads: its declared {@code input_table}, else its target's parent. */
    private String readSourceOf(PipelineDeclaration declaration) {
        if (StringUtils.isNotBlank(declaration.inputTable())) {
            return declaration.inputTable();
        }
        return definitions.findById(declaration.target())
                .map(TableDefinitionEntity::getSourceTable)
                .orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> targetsReadingEnrichment(String enrichment) {
        return declarations().stream()
                .filter(declaration -> referencedEnrichmentsOrRefuse(declaration).contains(enrichment))
                .map(PipelineDeclaration::target)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> enrichmentsReadByPipelineTargeting(String target) {
        // `target` is UNIQUE across both kinds, so this selects at most one declaration; expressed as a
        // stream anyway so it cannot break if that ever stops being true.
        return declarations().stream()
                .filter(declaration -> target.equals(declaration.target()))
                .flatMap(declaration -> referencedEnrichmentsOrRefuse(declaration).stream())
                .distinct()
                .toList();
    }

    /**
     * {@link #referencedEnrichments} for the two lookups above, which back the laundering subset rule and
     * so must not read a drifted declaration as "reads nothing".
     *
     * <p>The lenient resolver degrades to the empty set, which is right for the delete guard and the
     * freshness diagnostics and wrong here: under the subset rule an empty answer <em>accepts</em> a grant.
     * The lenient one also differs by kind — the aggregate resolver swallows, the enrich one does not — so
     * one drifted row either reopened the laundering path or turned every grant edit into a 500 naming an
     * unrelated pipeline. Both are refused here instead, named, and in one shape.
     */
    private Set<String> referencedEnrichmentsOrRefuse(PipelineDeclaration declaration) {
        try {
            return declaration.kind() == PipelineKind.AGGREGATE
                    ? aggregateGovernance.referencedEnrichmentsOrThrow(declaration)
                    : enrichGovernance.referencedEnrichmentsOrThrow(declaration);
        } catch (RuntimeException unresolvable) {
            throw new DeclarationUnresolvableException("pipeline '" + declaration.name()
                    + "' cannot be resolved, so what it reads is unknown and a grant on a table it may"
                    + " touch cannot be decided. Repair or delete that pipeline, then retry: "
                    + unresolvable.getMessage(), unresolvable);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> pipelinesWritingEnrichmentColumn(String enrichment, String column) {
        // The stored mapping's `column` is the target's PHYSICAL name (EnrichSpecValidator resolves it
        // there), so the caller passes a source_name and no spelling has to be reconciled here.
        return declarations().stream()
                .filter(declaration -> declaration.kind() == PipelineKind.ENRICH)
                .filter(declaration -> enrichment.equals(declaration.target()))
                .filter(declaration -> declaration.outputBindings(specCodec).stream()
                        .anyMatch(binding -> column.equals(binding.getColumn())))
                .map(PipelineDeclaration::name)
                .toList();
    }

    /**
     * The declarations these guards consult: <b>every kind</b>.
     *
     * <p>It was once ENRICH-only, and the gap that left was real rather than theoretical. An aggregate
     * rollup can group on an enrichment column, so dropping that enrichment breaks it — yet with ENRICH-only
     * guards the delete is blocked only if some enrich pipeline happens to reference the same enrichment.
     * Retire that one declaration and an enrichment several rollups read becomes droppable, with the failure
     * arriving at the next refresh rather than at the delete. An input lookup cannot cover it either: those
     * rollups read {@code dial_usage_log}, not the enrichment.
     */
    private List<PipelineDeclaration> declarations() {
        return pipelines.findAllDeclarations();
    }

    /**
     * The enrichments one stored declaration reads, from the gate its {@code kind} selects.
     *
     * <p>Two gates, one question — and they cannot be collapsed, because the two kinds name their columns
     * in different places: an enrich declaration in its bindings, filter, readiness signal and its
     * transform's output expressions; an aggregate one in its group keys, measures and the {@code where}
     * of each measure.
     */
    private Set<String> referencedEnrichments(PipelineDeclaration declaration) {
        return declaration.kind() == PipelineKind.AGGREGATE
                ? aggregateGovernance.referencedEnrichments(declaration)
                : enrichGovernance.referencedEnrichments(declaration);
    }

    /** Every field name one stored declaration mentions; see {@link #referencedEnrichments}. */
    private Set<String> declaredFieldsOf(PipelineDeclaration declaration) {
        return declaration.kind() == PipelineKind.AGGREGATE
                ? aggregateGovernance.declaredFieldsOf(declaration)
                : enrichGovernance.declaredFieldsOf(declaration);
    }
}
