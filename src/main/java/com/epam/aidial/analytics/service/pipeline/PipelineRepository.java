package com.epam.aidial.analytics.service.pipeline;

import jakarta.persistence.QueryHint;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository over the {@code pipeline} registry, keyed by {@code name} — the single identity
 * for both kinds.
 *
 * <p><b>Kind scoping is load-bearing in two places</b> and silent if missed. {@link #findDue} must return
 * only {@code AGGREGATE} rows, because an ENRICH row reaching the in-process aggregate tick would attempt
 * a rollup refresh against an enrichment target — wrong rows written, not an error raised. And
 * {@link #findByInputTableAndKind} exists because {@link #findByInputTable}'s two callers want different
 * scopes: the catalog's delete guard wants every kind, while the version-column repoint bump wants ENRICH
 * only.
 */
@Repository
public interface PipelineRepository
        extends JpaRepository<PipelineEntity, String>, JpaSpecificationExecutor<PipelineEntity> {

    /**
     * The pipeline writing {@code target}, if any. An {@link Optional} rather than a list because
     * {@code target} is UNIQUE in the schema — one pipeline per target, now across both kinds rather than
     * by the two registries' targets happening to be disjoint — so this one method serves both the
     * registration conflict check and the {@code ?target=} list filter.
     */
    Optional<PipelineEntity> findByTarget(String target);

    /** Pipelines keyed on any of {@code targets} — the pipelines of one source, resolved in one query. */
    List<PipelineEntity> findByTargetIn(Collection<String> targets);

    /**
     * Pipelines that <b>declare</b> {@code inputTable} as their read source, of any kind. Backs the
     * table-delete guard: deleting a table a pipeline reads is rejected so the dependency breaks at the
     * delete rather than at the next run.
     *
     * <p>Deliberately not the same set as the pipelines of that source's enrichments: one declaring
     * another read source is not among these, and one declaring this source need have no enrichment on it
     * at all.
     */
    List<PipelineEntity> findByInputTable(String inputTable);

    /**
     * As {@link #findByInputTable}, narrowed to one kind. The version-column repoint bump uses this rather
     * than the cross-kind form: bumping every rollup reading {@code dial_usage_log} would move its
     * {@code generation} <em>and</em> {@code updated_at} on every repoint, for a signal no aggregate
     * consumer reads.
     */
    List<PipelineEntity> findByInputTableAndKind(String inputTable, PipelineKind kind);

    /**
     * Whether an enabled pipeline named {@code name} of {@code kind} exists. The runner attempt series asks this
     * of a refused data-plane call, so a caller-chosen name that resolves to nothing, or to a disabled pipeline
     * whose series was dropped, creates no series.
     */
    boolean existsByNameAndKindAndEnabledTrue(String name, PipelineKind kind);

    /**
     * Pipelines that <b>derive</b> their read source, keyed on any of {@code targets}. The null
     * {@code input_table} is the whole point: one that declares a source follows that table instead of its
     * target's, so it must not be reached through its target.
     *
     * <p>No kind filter is needed and none is implied: an AGGREGATE row's {@code input_table} is never
     * null ({@code pipeline_input_required_unless_enrich}), so this already selects ENRICH rows only.
     */
    List<PipelineEntity> findByTargetInAndInputTableIsNull(Collection<String> targets);

    /**
     * The table the pipeline writing {@code target} <b>effectively reads</b> — its declared
     * {@code input_table}, or its target's {@code source_table} when it declares none. An {@link Optional}
     * because {@code target} is UNIQUE, so there is at most one such pipeline.
     *
     * <p>The same resolution {@link #findEffectiveInputTable} performs, keyed the other way round: that one
     * answers "what does pipeline <em>p</em> read", this one "what does the pipeline writing table
     * <em>t</em> read". The laundering invariant needs the second form, because it starts from a table
     * whose grants are being edited and has no pipeline name in hand.
     *
     * <p><b>A scalar projection, not an entity load, for the reason
     * {@link #findEffectiveInputTable} spells out</b>: {@link PipelineEntity}'s JSON-converted columns
     * compare unequal on every dirty check, so loading one inside the write transaction that is editing
     * grants would flush a full-row {@code UPDATE} and open an Envers revision on a pipeline nobody
     * touched. The same hazard cost {@code TableAccess} two audit-feed failures before it was given value
     * equality.
     */
    @Query("select coalesce(p.inputTable, d.sourceTable) from PipelineEntity p "
            + "left join TableDefinitionEntity d on d.name = p.target where p.target = :target")
    Optional<String> findEffectiveInputTableOfTarget(@Param("target") String target);

    /**
     * The targets of every pipeline declaring {@code inputTable} as its read source, of any kind — the
     * tables derived <b>from</b> it through a pipeline rather than through catalog parentage.
     *
     * <p>Names rather than entities, for the dirty-check reason above, and the counterpart of
     * {@link #findEffectiveInputTableOfTarget}: together they give the laundering invariant both
     * directions of the reads-from edge. {@code TableDefinitionRepository.findBySourceTable} covers only
     * the catalog edge, which an {@code AGGREGATE} rollup target does not have at all and an
     * {@code ENRICH} pipeline declaring its own read source does not follow.
     */
    @Query("select p.target from PipelineEntity p where p.inputTable = :inputTable")
    List<String> findTargetsByInputTable(@Param("inputTable") String inputTable);

    /**
     * The {@code target} of every pipeline of one kind — the exact set of tables that kind writes, since
     * {@code target} is UNIQUE in the schema.
     *
     * <p>A scalar projection rather than an entity load, and not only for economy: its caller is the
     * startup provenance reconciler, which wants a set of table names and must not put pipeline rows in
     * the persistence context to get one.
     */
    @Query("select p.target from PipelineEntity p where p.kind = :kind")
    List<String> findTargetsByKind(@Param("kind") PipelineKind kind);

    /**
     * Every pipeline's column-bearing declaration, as a <b>detached</b> projection — see
     * {@link PipelineDeclaration} for why a managed entity must not be loaded here.
     */
    @Query("select new com.epam.aidial.analytics.service.pipeline.PipelineDeclaration("
            + "p.name, p.kind, p.inputTable, p.target, p.triggerGroupBy, p.filterSql, p.readyWhen,"
            + " p.memberSelect, p.spec) from PipelineEntity p")
    List<PipelineDeclaration> findAllDeclarations();

    /**
     * The table {@code name} <b>effectively reads</b> — its declared {@code input_table}, or, when it
     * declares none, its target enrichment's {@code source_table} — or empty when there is no such
     * pipeline or neither resolves. The same resolution every governed read applies, expressed as one
     * query so the audit trail's parent lookup cannot disagree with it.
     *
     * <p><b>A scalar projection, not an entity load, and that is the point.</b> Its one caller is
     * {@code AuditResourceMapper}, which Envers reaches <em>during flush</em> of the very transaction
     * that changed this row. Loading {@link PipelineEntity} there would put it in the persistence
     * context with the two consequences this codebase has already been bitten by: its JSON-converted
     * columns ({@code ready_when}, {@code member_select}) compare unequal on every dirty check, so
     * Hibernate flushes a full-row {@code UPDATE} — which clobbers the {@code @Modifying} bulk writes to
     * the Class-1 position columns, and, inside a table-delete transaction, targets a row the delete has
     * already cascaded away and fails the whole delete with a stale-state error. See
     * {@link PipelineDeclaration} for the same hazard in its other form.
     *
     * <p>The {@code left join} is an ad-hoc entity join rather than a mapped association: {@code target}
     * is a plain string reference, and mapping it would give {@link PipelineEntity} an association the
     * registry has deliberately never had.
     *
     * <p>{@code MANUAL} flush mode for the same reason: this executes inside Envers' before-commit hook,
     * where the session has already flushed, and a query left on the default {@code AUTO} would ask for
     * another flush from inside one. Nothing needs flushing for this read to be correct — it reads a row
     * whose write is what triggered the lookup.
     */
    @Query("select coalesce(p.inputTable, d.sourceTable) from PipelineEntity p "
            + "left join TableDefinitionEntity d on d.name = p.target where p.name = :name")
    @QueryHints(@QueryHint(name = "org.hibernate.flushMode", value = "MANUAL"))
    Optional<String> findEffectiveInputTable(@Param("name") String name);

    /**
     * The registry listing: four independent, all-optional predicates in one query, each disabled by
     * passing null. One query rather than a fetch-then-filter, so the {@code kind} filter is applied by the
     * database and every listing shares one ordering.
     *
     * <p>Ordered {@code updated_at ASC, name ASC}. The tiebreaker is required rather than cosmetic:
     * {@code updated_at} is truncated to microseconds, so pipelines written in one transaction share a
     * value and would otherwise shuffle between two calls over an unchanged registry — which a consumer
     * diffing or paging the feed would read as a change. Declared here rather than at each call site so a
     * consumer's ordering guarantee cannot depend on which filters it passed.
     *
     * <p>Built as a {@link Specification} rather than as a JPQL string with {@code :x is null or …}
     * clauses, and that is a bug fix rather than a style choice: those clauses bind a null parameter of
     * unknown type, and PostgreSQL refuses the statement outright — {@code could not determine data type
     * of parameter} — for the {@code updated_since} timestamp. Composing the predicate in Java omits an
     * absent filter entirely, so no untyped null is ever sent and the SQL carries only what was asked for.
     */
    default List<PipelineEntity> list(
            String target, PipelineKind kind, Boolean enabled, Instant updatedSince) {
        return findAll((root, query, builder) -> {
            final List<Predicate> predicates = new ArrayList<>(4);
            if (target != null) {
                predicates.add(builder.equal(root.get("target"), target));
            }
            if (kind != null) {
                predicates.add(builder.equal(root.get("kind"), kind));
            }
            if (enabled != null) {
                predicates.add(builder.equal(root.get("enabled"), enabled));
            }
            if (updatedSince != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("updatedAt"), updatedSince));
            }
            return builder.and(predicates.toArray(new Predicate[0]));
        }, Sort.by(Sort.Order.asc("updatedAt"), Sort.Order.asc("name")));
    }

    /**
     * Enabled pipelines whose next run time has arrived — exactly what the in-process aggregate scheduler
     * tick iterates. A null {@code next_run_at} is never due, which is how a disabled-then-enabled
     * pipeline is armed (enable sets it) and a disabled one is disarmed (disable clears it).
     *
     * <p><b>Scoped to {@code AGGREGATE}</b>, and this is the most consequential predicate in the
     * repository: ADAS drives no schedule for an ENRICH pipeline, and one selected here would be handed to
     * a rollup refresh against an enrichment target, which writes wrong rows rather than failing. The
     * migration also leaves {@code next_run_at} null on every folded row and the refresh service asserts
     * the kind — three guards, because this failure is silent.
     */
    @Query("SELECT p FROM PipelineEntity p WHERE p.kind = com.epam.aidial.analytics.service.pipeline.PipelineKind.AGGREGATE "
            + "AND p.enabled = true AND p.nextRunAt IS NOT NULL AND p.nextRunAt <= :now ORDER BY p.nextRunAt")
    List<PipelineEntity> findDue(@Param("now") Instant now);

    /**
     * Discards a materialized mark recorded under a superseded {@code generation}, and claims the current
     * one. A no-op when the stored generation already matches, so it is safe to run before every record.
     *
     * <p>Run as a separate statement rather than folded into the three writes below because one
     * {@code generation} governs <b>two</b> facts (the position and the drained horizon), and a write that
     * stamped the new generation while leaving the other fact in place would silently revive it: a position
     * recorded before a {@code version_column} repoint would start reading as valid again, on an axis that
     * no longer exists. Clearing both first makes that unrepresentable instead of relying on every writer
     * to remember.
     *
     * <p><b>Native, and it returns the generation it replaced.</b> The discard is worth logging — a discarded
     * position holds every dependent rollup's clamp until the pipeline materializes again — and the log line
     * needs the stored generation. Reading it in a separate statement would let a concurrent writer claim the
     * mark in between, so it is read by this statement: the {@code FOR UPDATE} sub-select locks the row and
     * yields its values as they were before the update, which {@code RETURNING} then reports. Plain
     * {@code RETURNING} would give the new row, and {@code RETURNING old.*} needs PostgreSQL 18. Not
     * {@code @Modifying}, because the statement returns rows; the calling transaction is what makes it a write.
     *
     * @return empty when nothing was discarded (the stored generation already matched); otherwise the mark
     *     that was replaced, whose {@link DiscardedMark#getStoredGeneration() stored generation} is null on a
     *     pipeline's first mark ever — a claim, not a discard
     */
    @Transactional
    @Query(nativeQuery = true, value = "UPDATE pipeline p SET materialized_through_version = NULL, "
            + "materialized_through_identity = NULL, drained_at = NULL, materialized_generation = :generation "
            + "FROM (SELECT name, materialized_generation FROM pipeline WHERE name = :name FOR UPDATE) prev "
            + "WHERE p.name = prev.name "
            + "AND (prev.materialized_generation IS NULL OR prev.materialized_generation <> :generation) "
            + "RETURNING prev.materialized_generation AS \"storedGeneration\"")
    Optional<DiscardedMark> discardMarkOfOtherGeneration(@Param("name") String name, @Param("generation") long generation);

    /** What {@link #discardMarkOfOtherGeneration} replaced. */
    interface DiscardedMark {

        /** The generation the replaced mark was stored under; null when none was. */
        Long getStoredGeneration();
    }

    /**
     * Advances the position ADAS has materialized this pipeline's target enrichment through,
     * <b>monotonically</b>: a call carrying a lower tuple leaves the stored one untouched. The comparison
     * is lexicographic on {@code (version, identity)}, the same order the cursor itself is defined by.
     *
     * <p>Guarded in SQL rather than read-modify-write so two concurrent materialize calls for one pipeline
     * cannot interleave into a regression, and deliberately touching neither {@code updated_at} nor
     * {@code generation}: the runner's sync feed selects on {@code updated_at}, so stamping it here would
     * hand every pipeline back as changed on every materialize, and bumping {@code generation} would
     * invalidate the very mark being written.
     *
     * @return 1 when the position advanced, 0 when the stored one was already at or past it
     */
    @Modifying
    @Query("update PipelineEntity p set p.materializedThroughVersion = :version, "
            + "p.materializedThroughIdentity = :identity where p.name = :name "
            + "and (p.materializedThroughVersion is null or p.materializedThroughVersion < :version "
            + "or (p.materializedThroughVersion = :version and p.materializedThroughIdentity < :identity))")
    int advanceMaterializedThrough(
            @Param("name") String name, @Param("version") long version, @Param("identity") String identity);

    /**
     * Records that ADAS probed this pipeline and found nothing left matching, as of {@code at}. Monotonic
     * for the same reason the position is, and it never touches the position: the two answer different
     * questions, and a drained pipeline still has a last-written position that a consumer may need.
     */
    @Modifying
    @Query("update PipelineEntity p set p.drainedAt = :at where p.name = :name "
            + "and (p.drainedAt is null or p.drainedAt < :at)")
    int recordDrained(@Param("name") String name, @Param("at") Instant at);

    /**
     * Records that this pipeline's history was re-derived, as of {@code at}. Not qualified by
     * {@code generation} and so not cleared by {@link #discardMarkOfOtherGeneration}: it describes values
     * already published to consumers, and editing the declaration does not make a rollup that aggregated
     * them correct again.
     */
    @Modifying
    @Query("update PipelineEntity p set p.historyRederivedAt = :at where p.name = :name "
            + "and (p.historyRederivedAt is null or p.historyRederivedAt < :at)")
    int recordHistoryRederived(@Param("name") String name, @Param("at") Instant at);
}
