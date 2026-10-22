package com.epam.aidial.analytics.service.pipeline;

import com.epam.aidial.analytics.service.enrichment.model.MemberSelect;
import com.epam.aidial.analytics.service.enrichment.model.ReadyWhen;
import com.epam.aidial.analytics.service.enrichment.model.TriggerKind;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * JPA entity for a row of the {@code pipeline} registry: a declared process that produces rows for
 * exactly one {@code target} table. One row serves both kinds — an {@code AGGREGATE} rollup ADAS
 * refreshes on its own cron, and an {@code ENRICH} declaration the Enrichment Runner drives (except its
 * Class-1 case, which ADAS materializes in-database). {@code name} is the identity for both.
 *
 * <h2>Which fields belong to which kind</h2>
 *
 * <p>The kind-independent half is {@code name}, {@code kind}, {@code target}, {@link #inputTable},
 * {@link #filterSql}, the trigger, {@code enabled}, {@link #generation} and the timestamps. Everything
 * else is per-kind, and there are two groups of it — each here rather than in {@link #spec} for a
 * reason that is not symmetry:
 *
 * <ul>
 *   <li><b>The trigger fields</b> ({@link #triggerKind}, {@link #triggerCron}, {@link #triggerGroupBy},
 *       {@link #readyWhen}, {@link #memberSelect}) carry the database's structural guards — a cron iff a
 *       schedule, a group key iff a group trigger, a member selection only for a group — and a CHECK
 *       cannot see inside JSON.
 *   <li><b>The Class-1 position</b> ({@link #materializedThroughVersion} onwards) is written by
 *       {@code @Modifying} bulk updates and read by the freshness clamp. Note the trap when touching that
 *       code: loading this entity in a transaction that bulk-updates the same row silently loses the bulk
 *       write.
 * </ul>
 *
 * <p>Three columns are nullable <b>because</b> a row legitimately has none, and Hibernate enforces
 * {@code @Column(nullable = false)} at flush rather than only in generated DDL, so this is behaviour and
 * not documentation: {@link #inputTable} (an ENRICH pipeline may follow its target enrichment's parent,
 * and a disabled draft may not have named one yet), {@link #triggerCron} (only a SCHEDULE trigger has
 * one) and {@link #triggerKind} (a disabled draft may not have picked its trigger yet — an armed row
 * always has one, which {@code pipeline_armed_is_complete} asserts).
 *
 * <p>{@link #cursorVersion}/{@link #cursorIdentity} and {@link #nextRunAt} are the <b>ADAS-driven</b>
 * schedule's state and stay null on an ENRICH row, whose scan cursor lives in the Enrichment Runner.
 * {@code nextRunAt} being null there is one of three independent guards against the in-process aggregate
 * scheduler picking up an ENRICH row — the others are the kind filter in
 * {@code PipelineRepository#findDue} and the kind assertion in the refresh service. That failure would
 * write wrong rows rather than fail, which is why one guard is not enough.
 *
 * <p>{@link #generation} is bumped on every mutation and is the Enrichment Runner's <b>only</b>
 * change-detection token.
 */
@Entity
@Audited
@Table(name = "pipeline")
@Getter
@Setter
@NoArgsConstructor
public class PipelineEntity {

    @Id
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PipelineKind kind;

    /** The one table this pipeline writes; UNIQUE in the schema (one pipeline per target, any kind). */
    @Column(nullable = false)
    private String target;

    /**
     * The declared <b>read source</b>: the single table this pipeline scans. Null is legal only for an
     * ENRICH pipeline, where it means "derive from the target enrichment's {@code source_table}" — so a
     * null is a pipeline that <em>follows</em> its target rather than one that happens to name the same
     * table, and the version-column generation bump and the delete guard key off that distinction. The
     * database backs it with {@code pipeline_input_required_unless_enrich}.
     *
     * <p>Resolve through the service layer rather than reading this field: every governed read (refresh,
     * scan, group assembly, materialize) and every governed SQL fragment must see the same resolution.
     */
    @Column(name = "input_table")
    private String inputTable;

    /** Membership predicate over the input, in the bounded SQL-boolean grammar; null means "all rows". */
    @Column(name = "filter_sql")
    private String filterSql;

    /**
     * The kind's transform declaration, as stored JSON: an {@code AggregateSpec} or an {@code EnrichSpec}.
     *
     * <p>Untyped here deliberately. Hibernate binds a JSON column through the declared field type, so a
     * concretely-typed field could not carry two shapes and no converter can see the sibling {@code kind}
     * to choose. Read and write it through {@code PipelineSpecCodec}, which dispatches on {@code kind} —
     * that is also what lets a declaration written before the two registries merged be read back
     * unchanged, with no migration stamp inside the JSON.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "spec", nullable = false)
    private String spec = "{}";

    /**
     * How this pipeline is triggered, and at which grain; see {@link TriggerKind}. Null only on a disabled
     * pipeline whose declaration is still being authored. Converted rather than {@code @Enumerated} so an audit
     * revision written before {@code on_ingest} was retired still loads; see {@link TriggerKindConverter}.
     */
    @Convert(converter = TriggerKindConverter.class)
    @Column(name = "trigger_kind")
    private TriggerKind triggerKind;

    /** The cron of a {@link TriggerKind#SCHEDULE} pipeline; null for every other trigger kind. */
    @Column(name = "trigger_cron")
    private String triggerCron;

    /**
     * The group key a {@link TriggerKind#GROUP} pipeline fires per; non-null iff the trigger is
     * {@code GROUP} (constraint {@code pipeline_group_by_iff_group}) and validated to equal the target
     * enrichment's grain key, since the enrichment is physically keyed on — and collapses by — that
     * column.
     *
     * <p>Named {@code triggerGroupBy} rather than {@code groupBy} because the two would otherwise
     * collide: an aggregate pipeline's {@code group_by} is its rollup grain, a list of key parts inside
     * {@link #spec}. The group key is a property of the trigger, not of the transform.
     */
    @Column(name = "trigger_group_by")
    private String triggerGroupBy;

    /** A group pipeline's readiness declaration; see {@link ReadyWhen}. Null at row grain. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ready_when")
    private ReadyWhen readyWhen;

    /**
     * A group pipeline's member-selection policy; see {@link MemberSelect}. Null means the group
     * assembly's default policy, which is what every declaration predating the column means (constraint
     * {@code pipeline_member_select_only_group} keeps it off a non-group pipeline).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "member_select")
    private MemberSelect memberSelect;


    @Column(nullable = false)
    private boolean enabled;

    /**
     * The version half of this pipeline's scan position in its input's total order (epoch millis), or
     * null when it has never run — and always null for a kind whose cursor the runner owns. Advanced only
     * after a successful write.
     *
     * <p>{@code @NotAudited}: this is a runtime-position field written by {@code PipelineStateWriter} on
     * every scheduler tick. Excluding it (and the other runtime-position fields below) keeps the audit
     * trail a control-plane trail — Envers creates no revision when only {@code @NotAudited} properties
     * change, so a one-minute-cron pipeline does not flood the feed with ~1,440 entries per day.
     */
    @NotAudited
    @Column(name = "cursor_version")
    private Long cursorVersion;

    /** The tiebreaker half of the scan position; see {@link #cursorVersion}. */
    @NotAudited
    @Column(name = "cursor_identity")
    private String cursorIdentity;

    /**
     * When ADAS should next refresh this pipeline; null when disabled (never due), and null for an ENRICH
     * pipeline, which ADAS drives no schedule for. See the class javadoc on why that null is a guard.
     */
    @NotAudited
    @Column(name = "next_run_at")
    private Instant nextRunAt;

    @NotAudited
    @Column(name = "last_run_at")
    private Instant lastRunAt;

    /** The last refresh failure, or null after a success — so a stall is visible without reading logs. */
    @NotAudited
    @Column(name = "last_error")
    private String lastError;

    /**
     * Whether the last refresh left input behind (its probe filled the window limit). Distinguishes
     * "caught up, and lag is just the safety margin" from "still working through a backlog" — a
     * distinction {@link #cursorVersion}-derived lag alone cannot make, because a pipeline whose input
     * arrives at about its drain rate settles at a large constant lag that looks like a steady state.
     */
    @NotAudited
    @Column(name = "has_more", nullable = false)
    private boolean hasMore;

    /**
     * The enrichment whose materialized position held the last refresh's window short of its input's
     * available cursor, or null when the last refresh was not clamped. Persisted rather than derived
     * because it is a fact about the <em>last run</em>: the enrichment moves on, and recomputing the bound
     * at read time would report "not clamped" for a rollup that in fact stopped early.
     *
     * <p>Plain text rather than a reference: this is a state snapshot, and an enrichment must stay
     * deletable while a stale snapshot names it.
     */
    @NotAudited
    @Column(name = "clamped_by")
    private String clampedBy;

    /** The version half of the position the last refresh's window was clamped to; see {@link #clampedBy}. */
    @NotAudited
    @Column(name = "clamped_to_version")
    private Long clampedToVersion;

    /** The tiebreaker half of the clamped-to position; see {@link #clampedBy}. */
    @NotAudited
    @Column(name = "clamped_to_identity")
    private String clampedToIdentity;

    /**
     * The point from which this rollup's current contents were built — the anchor a recorded enrichment
     * re-derivation is compared against to decide whether the rollup needs rebuilding. A re-derivation
     * after this instant may have moved values already aggregated; one before it cannot have.
     *
     * <p>Not {@link #createdAt}, and that is the point: re-declaring an aggregate pipeline resets its
     * cursor so the next run rescans its input from the beginning, which is a rebuild in everything but
     * name and must clear the flag. Stamped at registration and wherever that cursor is reset, so both
     * documented rebuild shapes clear it and no rebuild operation has to be introduced.
     *
     * <p>It is a <b>rollup</b> anchor and means nothing on an ENRICH row, which is why the cursor reset is
     * kind-dispatched: re-stamping it there would be writing a rollup fact onto something that is not a
     * rollup.
     */
    @NotAudited
    @Column(name = "rebuilt_at", nullable = false)
    private Instant rebuiltAt;

    @Column(nullable = false)
    private long generation;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * The version half of the position ADAS has materialized this pipeline's target enrichment through:
     * the end tuple of the last window it wrote, on the read source's cursor axis. Advanced monotonically
     * — a replay at an earlier cursor must not lower it — and null until the first window is written.
     *
     * <p>Recorded rather than reported: on the Class-1 path ADAS runs the {@code INSERT … SELECT} itself
     * and is holding this tuple when it returns, so it is an observation. The scan cursor cannot serve
     * instead: it lives in the runner and advances at chunk <em>enqueue</em>, marking what is queued for
     * evaluation rather than what is written.
     *
     * <p>Read it through {@code EnrichmentFreshness} rather than directly — the value alone is unsafe,
     * because it is only meaningful together with {@link #materializedGeneration} and, for a filtered
     * declaration, {@link #drainedAt}.
     *
     * <p>{@code @NotAudited}, like the other four Class-1 position fields below and for a second reason
     * beyond keeping the trail a control-plane trail: every writer of these five is a {@code @Modifying}
     * bulk update (see {@code PipelineRepository}), which bypasses the persistence context entirely, so
     * Envers never observes the write. Auditing them would therefore mirror not the recorded position but
     * whatever the entity last held in memory — a stored value that silently disagrees with the live row.
     */
    @NotAudited
    @Column(name = "materialized_through_version")
    private Long materializedThroughVersion;

    /** The tiebreaker half of the materialized position; see {@link #materializedThroughVersion}. */
    @NotAudited
    @Column(name = "materialized_through_identity")
    private String materializedThroughIdentity;

    /**
     * When ADAS last probed this pipeline and found nothing left matching — a distinct fact from the
     * position, and the one that keeps the position usable.
     *
     * <p>The probe applies the declaration's {@code filter_sql}, so the position is the last row
     * <em>matching</em> the filter and non-matching rows sit <b>above</b> it, permanently unenriched by
     * construction. Without this, a filtered declaration would pin every dependent rollup at its last
     * match indefinitely while looking healthy. Being an instant rather than a position is deliberate: it
     * says "nothing left, as of when I looked", which is exactly what releases the clamp for rows nothing
     * will ever enrich.
     */
    @NotAudited
    @Column(name = "drained_at")
    private Instant drainedAt;

    /**
     * When this pipeline's history was last re-derived — a materialize carrying {@code from}/{@code to}
     * bounds, which rewrites values for source rows consumers may already have read.
     *
     * <p><b>Deliberately not qualified by {@link #materializedGeneration}</b>, unlike the position: a
     * declaration edit does not make an already-published wrong rollup right again, so this survives one.
     * It is a fact about values, not about an axis.
     */
    @NotAudited
    @Column(name = "history_rederived_at")
    private Instant historyRederivedAt;

    /**
     * The {@link #generation} under which {@link #materializedThroughVersion} and {@link #drainedAt} were
     * recorded. A record whose generation has since moved is treated as <b>absent</b>: a mutation can
     * change what a position means, and a {@code version_column} repoint on the read source makes it
     * meaningless rather than merely stale, because the axis it is expressed on no longer exists. That
     * repoint bumps this pipeline's generation transactionally, so the check is exact.
     */
    @NotAudited
    @Column(name = "materialized_generation")
    private Long materializedGeneration;

    /** Whether this pipeline has a stored scan position (it has run at least once successfully). */
    public boolean hasCursor() {
        return cursorVersion != null && cursorIdentity != null;
    }

    /**
     * How far behind the input a rollup sitting at {@code cursorVersion} (epoch millis) is, in seconds; 0
     * when there is no position yet. Lives here as the single definition, so the refresh outcome and the
     * read surface cannot report staleness by two subtly different formulas.
     */
    public static long stalenessSeconds(Long cursorVersion) {
        return cursorVersion == null ? 0L : Math.max(0L, (System.currentTimeMillis() - cursorVersion) / 1000L);
    }

    /** This pipeline's staleness in seconds, or null when it has never run — the read-surface form. */
    public Long lagSeconds() {
        return cursorVersion == null ? null : stalenessSeconds(cursorVersion);
    }
}
