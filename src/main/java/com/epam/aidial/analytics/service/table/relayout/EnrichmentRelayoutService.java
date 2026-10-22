package com.epam.aidial.analytics.service.table.relayout;

import com.clickhouse.client.api.query.GenericRecord;
import com.epam.aidial.analytics.clickhouse.ClickHouseDdlExecutor;
import com.epam.aidial.analytics.clickhouse.ClickHouseTableInspector;
import com.epam.aidial.analytics.configuration.PipelineBudgetProperties;
import com.epam.aidial.analytics.service.enrichment.model.TransformType;
import com.epam.aidial.analytics.service.pipeline.LookupIndexService;
import com.epam.aidial.analytics.service.pipeline.PipelineEntity;
import com.epam.aidial.analytics.service.pipeline.PipelineKind;
import com.epam.aidial.analytics.service.pipeline.PipelineRepository;
import com.epam.aidial.analytics.service.pipeline.PipelineSpecCodec;
import com.epam.aidial.analytics.service.pipeline.model.EnrichSpec;
import com.epam.aidial.analytics.service.table.ColumnMappingEntity;
import com.epam.aidial.analytics.service.table.ColumnMappingRepository;
import com.epam.aidial.analytics.service.table.EnrichmentAlignment;
import com.epam.aidial.analytics.service.table.Identifiers;
import com.epam.aidial.analytics.service.table.PartitionGranularity;
import com.epam.aidial.analytics.service.table.TableDdlRenderer;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import com.epam.aidial.analytics.service.table.TableSchemaCreationService;
import com.epam.aidial.analytics.service.table.TableStatus;
import com.epam.aidial.analytics.service.table.TableType;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InterruptedIOException;
import java.nio.channels.ClosedByInterruptException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.sql.DataSource;

/**
 * Relays out every existing enrichment that the aligned layout applies to but that predates it (see
 * {@link EnrichmentAlignment}), by itself, after the application is ready — no call, property or runbook step.
 *
 * <p><b>Copied, never re-derived.</b> An enrichment's values may come from inputs that no longer exist —
 * an enrichment can read payload columns the payload table has already expired for its oldest rows —
 * so the existing rows are copied into an aligned table with their {@code _updated_at} unchanged, the alignment
 * column taken from each row's source row. Then the two tables are exchanged by name.
 *
 * <p><b>Steps</b> ({@link RelayoutStep}), persisted after each so a restart resumes:
 * <ol>
 *   <li>create the aligned copy — its DDL already carrying every lookup index the table's pipelines need, so the
 *       copy indexes the whole history, and no TTL yet — and copy it one slice of grain keys per statement, up to a
 *       mark taken first;</li>
 *   <li>copy what was written during that copy;</li>
 *   <li>hold the table's writers ({@code 503 table_relayout_in_progress}) and copy what was written since;</li>
 *   <li>exchange the two tables, and repair the epoch partition;</li>
 *   <li>mark the catalog aligned and release the hold;</li>
 *   <li>copy what an old instance wrote into the old table after the last mark, repair the epoch partition again,
 *       check the counts, drop the old table and apply the TTL.</li>
 * </ol>
 *
 * <p><b>Bounded statements.</b> Every statement that reads a table's rows — the copies, the catch-ups and the check
 * that nothing was lost — reads one {@link Slice} of the grain keys at a time, holding about {@link #SLICE_BYTES} of
 * the table, and is capped at {@code pipeline.budget.max-memory}. A month or a day of the source is not a bound: its
 * size follows the traffic, and the join that stamps the alignment column holds the source rows of its whole chunk.
 * A slice is a range of the table's sort key, so the table side reads only its slice; the source side reads only its
 * identity and partition columns, restricted to the keys the slice holds.
 *
 * <p><b>Deploy skew.</b> Under a rolling update the previous build keeps serving writes; it knows neither the hold
 * nor the alignment column. It can lose rows two ways, and each has a measure: rows it writes into the old table
 * after the last catch-up are re-copied before the old table is dropped, and rows it writes into the new table with
 * no alignment value land in the epoch partition, which is re-stamped from the source and dropped. The grace delay
 * before the job starts is what makes both rare; either repair finding anything logs a WARN, because it means the
 * delay was too short.
 *
 * <p><b>Failure.</b> A failure before the exchange leaves the old table in service, drops the copy, releases the hold
 * and records the error ({@link RelayoutStep#FAILED}); the next start relays the table out again from the beginning,
 * as it re-judges a skipped ({@link RelayoutStep#UNSUPPORTED}) one. A failure after the exchange keeps the state for the
 * next start to resume. A stop — the application shutting down, or the job interrupted — is neither: the hold is
 * released, nothing is dropped and the state is kept. Nothing is ever thrown into startup.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnrichmentRelayoutService {

    /** How long after readiness the job starts: longer than a one-pod rollout, so the previous build is gone. */
    static final Duration GRACE = Duration.ofMinutes(10);

    /**
     * What the hold waits on top of {@code pipeline.budget.timeout} for writes already past its check to land before
     * the final copy: a materialize checks the hold before it starts, and runs for at most that budget.
     */
    static final Duration SETTLE_MARGIN = Duration.ofSeconds(15);

    /** How often the hold's row is saved again while the hold settles, so it does not expire meanwhile. */
    static final Duration HEARTBEAT = Duration.ofMinutes(1);

    /** How long a shutdown waits for the job to stop. */
    static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(10);

    /**
     * The prefix of the aligned copy's name while it is filled — and of the old table's after the exchange. A user
     * table cannot start with {@code _}, so the name never collides with one.
     */
    static final String COPY_PREFIX = "_relayout_";

    /**
     * The uncompressed bytes of the copied table one slice holds. With a typical row-grain enrichment's shape
     * over a tens-of-millions-row source, a slice copy of this size peaks just under 1 GiB — several times its
     * uncompressed bytes, and about half the default pipeline memory budget it is capped at.
     */
    static final long SLICE_BYTES = 256L << 20;

    private static final int MAX_IDENTIFIER = 64;

    private static final String UPDATED_AT = TableDdlRenderer.UPDATED_AT;
    private static final String GRAIN = "__g";
    private static final String PARTITION = "__p";

    private final TableDefinitionRepository definitions;
    private final ColumnMappingRepository columns;
    private final PipelineRepository pipelines;
    private final PipelineSpecCodec specCodec;
    private final TableSchemaCreationService schemaCreation;
    private final TableDdlRenderer ddlRenderer;
    private final ClickHouseDdlExecutor ddl;
    private final ClickHouseTableInspector inspector;
    private final LookupIndexService lookupIndexes;
    private final RelayoutStateStore states;
    private final DataSource dataSource;
    private final TransactionTemplate transactions;
    private final PipelineBudgetProperties budget;

    /** How long the hold waits before its final copy: {@code pipeline.budget.timeout} plus {@link #SETTLE_MARGIN}. */
    private Duration settle;

    /**
     * How far below its mark a catch-up copy reaches: {@code pipeline.budget.timeout} plus {@link #SETTLE_MARGIN},
     * the longest a write can run between the start that stamps its rows and its commit. A field of its own, apart
     * from {@link #settle}, so a test can skip the wait without narrowing the copy.
     */
    private Duration lookback;

    /** The bytes one slice holds: {@link #SLICE_BYTES}, a field so a test can slice a small table. */
    private long sliceBytes = SLICE_BYTES;

    private volatile boolean stopping;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "enrichment-relayout");
        thread.setDaemon(true);
        return thread;
    });

    /** Starts the job {@link #GRACE} after the application is ready, off the startup and probe paths. */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        executor.schedule(this::runAll, GRACE.toMillis(), TimeUnit.MILLISECONDS);
    }

    @PostConstruct
    void init() {
        settle = budget.timeout().plus(SETTLE_MARGIN);
        lookback = settle;
    }

    /** Stops the job, keeping its state for the next start to resume, and waits a bounded time for it to stop. */
    @PreDestroy
    void shutdown() {
        stopping = true;
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(SHUTDOWN_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("Enrichment relayout did not stop within {}; the next start resumes it", SHUTDOWN_WAIT);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Relays out every candidate table, one after another; a table another instance is relaying out is tried again
     * {@link #GRACE} later. Never throws.
     */
    public void runAll() {
        boolean retry = false;
        try {
            for (final TableDefinitionEntity table : candidates()) {
                if (stopping || Thread.currentThread().isInterrupted()) {
                    return;
                }
                final String name = table.getName();
                try {
                    retry |= !withLock(name, () -> relayout(name));
                } catch (RuntimeException ex) {
                    recordFailure(name, ex);
                }
            }
        } catch (RuntimeException ex) {
            log.error("Enrichment relayout stopped: {}", ex.getMessage(), ex);
        }
        if (retry && !stopping) {
            try {
                executor.schedule(this::runAll, GRACE.toMillis(), TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException ignored) {
                // Shutting down: the next start picks the table up.
            }
        }
    }

    /**
     * The enrichments to relay out: every active user enrichment that qualifies for alignment and is not aligned —
     * a failed or skipped relayout included, so each start re-judges it — plus any whose relayout is in progress.
     */
    List<TableDefinitionEntity> candidates() {
        final List<TableDefinitionEntity> found = new ArrayList<>();
        for (final TableDefinitionEntity table : definitions.findByTypeAndStatus(TableType.ENRICHMENT, TableStatus.ACTIVE)) {
            if (table.isSystem()) {
                continue;
            }
            final Optional<RelayoutStateStore.State> state = states.find(table.getName());
            if (state.map(s -> s.step().inProgress()).orElse(false)) {
                found.add(table);
                continue;
            }
            final TableDefinitionEntity source = definitions.findById(table.getSourceTable()).orElse(null);
            if (state.map(s -> s.step() != RelayoutStep.DONE).orElse(true) && EnrichmentAlignment.alignedColumn(table, source) == null
                    && EnrichmentAlignment.eligibleColumn(table.getGrainKey(), source) != null) {
                found.add(table);
            }
        }
        return found;
    }

    /**
     * Relays out {@code name}, from wherever its persisted state says it got to; a failed or skipped relayout is
     * judged again and, when nothing refuses it now, started from the beginning.
     */
    void relayout(String name) {
        final TableDefinitionEntity table = definitions.findById(name).orElse(null);
        if (table == null) {
            return;
        }
        final TableDefinitionEntity source = definitions.findById(table.getSourceTable()).orElseThrow();
        RelayoutStateStore.State state = states.find(name).orElse(null);
        if (state != null && state.step() == RelayoutStep.DONE) {
            return;
        }
        if (state == null || !state.step().inProgress()) {
            final String refusal = refusal(table, source);
            if (refusal != null) {
                if (state != null && state.step() == RelayoutStep.UNSUPPORTED && refusal.equals(state.error())) {
                    log.info("Enrichment '{}' is still not relaid out into the aligned layout: {}", name, refusal);
                    return;
                }
                log.warn("Enrichment '{}' is not relaid out into the aligned layout: {}", name, refusal);
                states.start(name, RelayoutStep.UNSUPPORTED, copyName(name), refusal);
                return;
            }
            if (state != null) {
                log.info("Relayout of '{}' is started again after it was {}: {}", name,
                        state.step() == RelayoutStep.FAILED ? "failed" : "skipped", state.error());
            }
            // From the beginning, whatever an earlier attempt got to: the first step recreates the copy.
            states.start(name, RelayoutStep.STARTED, copyName(name), null);
            state = states.find(name).orElseThrow();
            log.info("Relayout of '{}' into the aligned layout started", name);
        }
        try {
            final Plan plan = plan(table, source, state.copyTable());
            if (state.step() == RelayoutStep.HELD || state.step() == RelayoutStep.SWAPPED) {
                state = holdAgain(state);
            }
            while (state.step().inProgress()) {
                if (stopping) {
                    throw new RelayoutStopped();
                }
                state = advance(plan, state);
                states.save(state);
            }
        } catch (RuntimeException ex) {
            if (stopped(ex)) {
                stop(state);
            } else {
                fail(state, ex);
            }
        }
    }

    /**
     * The aligned copy's name for {@code table}: {@link #COPY_PREFIX} and the table's name, which is cut short and
     * given a hash of the whole when the two would be longer than an identifier may be.
     */
    public static String copyName(String table) {
        final String name = COPY_PREFIX + table;
        if (name.length() <= MAX_IDENTIFIER) {
            return name;
        }
        final String hash = String.format("%08x", table.hashCode());
        return COPY_PREFIX + table.substring(0, MAX_IDENTIFIER - COPY_PREFIX.length() - hash.length() - 1) + "_" + hash;
    }

    /**
     * Why {@code table} cannot be relaid out, or null when it can: a writer that cannot write an aligned table, an
     * existing column of the alignment column's name that cannot be adopted, or a database engine that cannot
     * exchange tables.
     */
    private String refusal(TableDefinitionEntity table, TableDefinitionEntity source) {
        final String writer = writerRefusal(table.getName());
        if (writer != null) {
            return writer;
        }
        final String alignment = source.getPartitionColumn();
        final ColumnMappingEntity sourceColumn = sourceColumn(source.getName(), alignment);
        for (final ColumnMappingEntity column : columns.findByTableNameOrderByOrdinal(table.getName())) {
            if (column.getSourceName().equals(alignment) && !EnrichmentAlignment.adoptable(column, sourceColumn)) {
                return "its column '" + alignment + "' is not a non-nullable, non-sensitive, non-heavy "
                        + sourceColumn.getType().code() + " like its source's, so it cannot become the alignment column";
            }
        }
        final String engine = inspector.databaseEngine();
        if (!"Atomic".equals(engine) && !"Replicated".equals(engine)) {
            return "the database engine '" + engine + "' cannot exchange tables";
        }
        return null;
    }

    /** Why {@code table}'s writer stops its relayout — a row-grain llm pipeline, or one whose spec cannot be read. */
    private String writerRefusal(String table) {
        final Optional<PipelineEntity> writer = pipelines.findByTarget(table);
        if (writer.isEmpty() || writer.get().getKind() != PipelineKind.ENRICH) {
            return null;
        }
        final EnrichSpec spec;
        try {
            spec = specCodec.readEnrich(writer.get());
        } catch (RuntimeException ex) {
            // Closed: a writer that cannot be judged may be one whose write-back carries no source partition value.
            return "the spec of its writer pipeline '" + writer.get().getName() + "' cannot be read: " + ex.getMessage();
        }
        if (spec != null && spec.getTransform() != null && spec.getTransform().getType() != null
                && spec.getTransform().getType() != TransformType.SQL) {
            return "it is written by the llm pipeline '" + writer.get().getName() + "', whose write-back carries no "
                    + "source partition value";
        }
        return null;
    }

    private RelayoutStateStore.State advance(Plan plan, RelayoutStateStore.State state) {
        return switch (state.step()) {
            case STARTED -> copyHistory(plan, state);
            case COPIED -> {
                final long mark = maxUpdatedAt(plan.table());
                final long rows = copySince(plan, plan.table(), plan.copy(), state.copyMark());
                log.info("Relayout of '{}': {} row(s) written during the copy copied", plan.table(), rows);
                yield withMarks(state, RelayoutStep.CAUGHT_UP, state.copiedThrough(), state.copyMark(), mark, false);
            }
            case CAUGHT_UP -> {
                final String writer = writerRefusal(plan.table());
                if (writer != null) {
                    yield abandon(plan, state, writer);
                }
                final RelayoutStateStore.State held = withMarks(state, RelayoutStep.CAUGHT_UP, state.copiedThrough(),
                        state.copyMark(), state.catchUpMark(), true);
                states.save(held);
                log.info("Relayout of '{}': writers held", plan.table());
                settle(held);
                final long mark = maxUpdatedAt(plan.table());
                copySince(plan, plan.table(), plan.copy(), state.catchUpMark());
                yield withMarks(state, RelayoutStep.HELD, state.copiedThrough(), state.copyMark(), mark, true);
            }
            case HELD -> {
                if (!aligned(plan.table())) {
                    final String writer = writerRefusal(plan.table());
                    if (writer != null) {
                        yield abandon(plan, state, writer);
                    }
                }
                exchange(plan);
                repairEpoch(plan, "after the exchange");
                yield state.withStep(RelayoutStep.SWAPPED);
            }
            case SWAPPED -> {
                markAligned(plan);
                log.info("Relayout of '{}': catalog aligned on '{}', writers released", plan.table(), plan.alignment());
                yield withMarks(state, RelayoutStep.RELEASED, state.copiedThrough(), state.copyMark(),
                        state.catchUpMark(), false);
            }
            case RELEASED -> {
                finish(plan, state);
                yield state.withStep(RelayoutStep.DONE);
            }
            default -> state;
        };
    }

    /**
     * Step 1: creates the aligned copy and fills it, one slice per statement, saving after each slice the key it
     * copied through — so a restart resumes after the last completed slice. The slices are taken as the copy goes, so
     * a resumed copy slices what is left; the last slice is open above, so a row written since is copied either way.
     */
    private RelayoutStateStore.State copyHistory(Plan plan, RelayoutStateStore.State state) {
        final Long mark;
        if (state.copyMark() == null) {
            ddl.execute(ddlRenderer.renderDrop(plan.copy()));
            ddl.execute(ddlRenderer.renderCreate(plan.copy(), plan.createSpec()));
            mark = maxUpdatedAt(plan.table());
            states.save(withMarks(state, RelayoutStep.STARTED, null, mark, null, false));
        } else {
            mark = state.copyMark();
        }
        final String[] through = {state.copiedThrough()};
        final int slices = forEachSlice(plan, plan.table(), null, through[0], slice -> {
            copy(plan, plan.table(), plan.copy(), slice, null);
            if (slice.through() != null) {
                through[0] = slice.through();
                states.save(withMarks(state, RelayoutStep.STARTED, through[0], mark, null, false));
            }
        });
        log.info("Relayout of '{}': history copied into '{}' in {} slice(s) ({} row version(s))", plan.table(), plan.copy(),
                slices, count(plan.copy(), false));
        final Missing missing = missing(plan, plan.table(), plan.copy(), quote(UPDATED_AT) + " <= " + millis(mark));
        if (missing.orphans() > 0) {
            log.info("Relayout of '{}': {} row(s) have no source row and are not carried over", plan.table(),
                    missing.orphans());
        }
        return withMarks(state, RelayoutStep.COPIED, through[0], mark, null, false);
    }

    /**
     * Abandons the relayout before the exchange, because {@code refusal} — a writer appeared that cannot write an
     * aligned table: the hold released, the copy dropped, the old table left in service.
     */
    private RelayoutStateStore.State abandon(Plan plan, RelayoutStateStore.State state, String refusal) {
        states.release(plan.table());
        ddl.execute(ddlRenderer.renderDrop(plan.copy()));
        log.warn("Enrichment '{}' is not relaid out into the aligned layout: {}; the relayout is abandoned before the "
                + "exchange and the table is unchanged and in service", plan.table(), refusal);
        return new RelayoutStateStore.State(state.table(), RelayoutStep.UNSUPPORTED, state.copyTable(), null, null,
                null, false, refusal);
    }

    /** Step 4: exchanges the tables, unless an earlier attempt already did. */
    private void exchange(Plan plan) {
        if (aligned(plan.table())) {
            return;
        }
        ddl.execute("EXCHANGE TABLES " + quote(plan.table()) + " AND " + quote(plan.copy()));
        log.info("Relayout of '{}': tables exchanged; the previous table is '{}'", plan.table(), plan.copy());
    }

    private void markAligned(Plan plan) {
        transactions.executeWithoutResult(status -> {
            final TableDefinitionEntity definition = definitions.findById(plan.table()).orElseThrow();
            definition.setPartitionColumn(plan.alignment());
            definition.setPartitionGranularity(plan.granularity());
            definitions.save(definition);
        });
    }

    /**
     * Step 6: the pre-drop re-copy, the second epoch repair, the check that nothing was lost, a last re-copy of what
     * the previous table received meanwhile, and the drop.
     *
     * <p>The check is by grain key, not by count: the aligned table keeps receiving writes, which a count would let
     * hide a loss; and a row of the previous table whose source row is gone (expired) is not copied, by design, which
     * a count would report as one. Such orphans are logged; a row that has a source row and is still missing keeps
     * the previous table for inspection.
     */
    private void finish(Plan plan, RelayoutStateStore.State state) {
        if (inspector.exists(plan.copy())) {
            final long mark = maxUpdatedAt(plan.copy());
            warnLate(plan, copySince(plan, plan.copy(), plan.table(), state.catchUpMark()));
            repairEpoch(plan, "before the drop");
            final Missing missing = missing(plan, plan.copy(), plan.table(), null);
            if (missing.orphans() > 0) {
                log.warn("Relayout of '{}': {} row(s) of the previous table have no source row and are not carried "
                        + "over; expected for rows whose source rows have expired", plan.table(), missing.orphans());
            }
            if (missing.lost() > 0) {
                log.error("Relayout of '{}': {} row(s) of the previous table have a source row but are missing from the "
                        + "aligned table; the previous table is kept as '{}' for inspection", plan.table(), missing.lost(),
                        plan.copy());
            } else {
                // Immediately before the drop, so nothing the previous table received since the first re-copy is lost.
                warnLate(plan, copySince(plan, plan.copy(), plan.table(), mark));
                ddl.execute(ddlRenderer.renderDrop(plan.copy()));
            }
        } else {
            repairEpoch(plan, "before the drop");
        }
        applyTtl(plan);
        lookupIndexes.ensure(plan.table(), new LinkedHashSet<>(lookupIndexes.requiredColumns(plan.table())));
        log.info("Relayout of '{}' into the aligned layout finished", plan.table());
    }

    private void warnLate(Plan plan, long late) {
        if (late > 0) {
            log.warn("Relayout of '{}': {} row(s) written to the previous table after the last catch-up were "
                    + "re-copied; the grace delay was shorter than the rollout", plan.table(), late);
        }
    }

    /**
     * Applies the table's TTL — its own, or the one it inherits — now that no repair is left to run. With
     * {@code materialize_ttl_recalculate_only} the mutation the modification starts only recomputes each part's TTL
     * information instead of rewriting its data, so expiry then drops whole parts as it would on a table created so.
     * The modification waits for that mutation ({@code mutations_sync}), so the setting can be reset after it: it is
     * read when the mutation runs, and left in place it would turn every later TTL change into a recalculation only.
     */
    private void applyTtl(Plan plan) {
        if (plan.ttl() == null) {
            return;
        }
        ddl.execute(ddlRenderer.renderAlter(plan.table(), List.of("MODIFY SETTING materialize_ttl_recalculate_only = 1"
                + (plan.ttlOnlyDropParts() ? ", ttl_only_drop_parts = 1" : ""))));
        ddl.execute(ddlRenderer.renderAlter(plan.table(), List.of(ddlRenderer.modifyTtlCommand(plan.ttl())))
                + " SETTINGS mutations_sync = 1");
        ddl.execute(ddlRenderer.renderAlter(plan.table(), List.of("RESET SETTING materialize_ttl_recalculate_only")));
    }

    /**
     * A stop — shutdown or interruption — keeps the state for the next start to resume and drops nothing; it only
     * releases the hold, so writers are not refused until then. Postgres only: no ClickHouse call is made.
     */
    private void stop(RelayoutStateStore.State state) {
        uninterrupted(() -> {
            try {
                states.release(state.table());
            } finally {
                log.info("Relayout of '{}' stopped at step {}; it resumes on the next start", state.table(), state.step());
            }
        });
    }

    private void fail(RelayoutStateStore.State state, RuntimeException ex) {
        // The hold first, before any ClickHouse call that could fail in turn and leave writers refused.
        try {
            states.release(state.table());
        } finally {
            failReleased(state, ex);
        }
    }

    private void failReleased(RelayoutStateStore.State state, RuntimeException ex) {
        final String table = state.table();
        boolean beforeExchange;
        try {
            beforeExchange = state.step().ordinal() < RelayoutStep.SWAPPED.ordinal() && !aligned(table);
        } catch (RuntimeException unknown) {
            // Not knowing whether the exchange ran, keep the state: every step is safe to resume.
            beforeExchange = false;
        }
        if (beforeExchange) {
            try {
                ddl.execute(ddlRenderer.renderDrop(state.copyTable()));
            } catch (RuntimeException ignored) {
                // The copy is recreated from scratch by a retry; a leftover one is dropped then.
            }
            states.save(new RelayoutStateStore.State(table, RelayoutStep.FAILED, state.copyTable(),
                    state.copiedThrough(), state.copyMark(), state.catchUpMark(), false, String.valueOf(ex.getMessage())));
            log.error("Relayout of '{}' failed before the exchange; the table is unchanged and in service, and the next "
                    + "start relays it out again: {}", table, ex.getMessage(), ex);
        } else {
            // Past the exchange every remaining step is repeatable, so the state is kept for the next start to resume;
            // the hold is released so writers are not refused until then.
            states.save(new RelayoutStateStore.State(table, state.step(), state.copyTable(), state.copiedThrough(),
                    state.copyMark(), state.catchUpMark(), false, String.valueOf(ex.getMessage())));
            log.error("Relayout of '{}' failed at step {}; it resumes on the next start: {}",
                    table, state.step(), ex.getMessage(), ex);
        }
    }

    /**
     * A table's relayout threw outside its own failure handling: the hold is released and, unless a relayout is in
     * progress (which the next start resumes), the table recorded failed, for the next start to judge again.
     */
    private void recordFailure(String table, RuntimeException ex) {
        log.error("Relayout of '{}' failed: {}", table, ex.getMessage(), ex);
        try {
            final Optional<RelayoutStateStore.State> state = states.find(table);
            if (state.isPresent()) {
                states.release(table);
            }
            if (state.map(s -> !s.step().inProgress()).orElse(true)) {
                states.start(table, RelayoutStep.FAILED, copyName(table), String.valueOf(ex.getMessage()));
            }
        } catch (RuntimeException ignored) {
            // Postgres unreachable too: the hold expires by itself (RelayoutStateStore.HOLD_EXPIRY).
        }
    }

    /** Whether {@code ex} is the job being stopped — shutdown or interruption — rather than a failure. */
    private boolean stopped(Throwable ex) {
        if (stopping || Thread.currentThread().isInterrupted()) {
            return true;
        }
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof RelayoutStopped || cause instanceof InterruptedException
                    || cause instanceof InterruptedIOException || cause instanceof ClosedByInterruptException) {
                return true;
            }
        }
        return false;
    }

    /** Runs {@code body} with the thread's interrupt flag cleared, restoring it after: Postgres calls must not see it. */
    private static void uninterrupted(Runnable body) {
        final boolean interrupted = Thread.interrupted();
        try {
            body.run();
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Re-stamps the rows of the epoch partition — written with no alignment value, by a writer that predates the
     * layout — from their source rows, then drops the partition, the rows with no source row in it included. Skipped
     * when the source itself has rows there.
     */
    private void repairEpoch(Plan plan, String when) {
        final String epoch = plan.epochPartitionId();
        if (!hasPartition(plan.table(), epoch) || hasPartition(plan.source(), epoch)) {
            return;
        }
        final String inEpoch = plan.partitionFunction() + "(" + quote(plan.alignment()) + ") = " + epoch;
        final long rows = countWhere(plan.table(), inEpoch);
        final long restamped = countWhere(plan.table(), inEpoch + " AND " + quote(plan.grainKey()) + " IN ("
                + sourceGrains(plan, "SELECT " + quote(plan.grainKey()) + " FROM " + quote(plan.table()) + " WHERE " + inEpoch)
                + ")");
        // A re-stamped row leaves the epoch partition, so the slices still taken see only the rows not yet copied.
        forEachSlice(plan, plan.table(), inEpoch, null, slice -> copy(plan, plan.table(), plan.table(), slice, inEpoch));
        ddl.execute(ddlRenderer.renderAlter(plan.table(), List.of("DROP PARTITION ID '" + epoch + "'")));
        if (restamped > 0) {
            log.warn("Relayout of '{}': {} row(s) in the epoch partition re-stamped from the source {}; the grace delay "
                    + "was shorter than the rollout", plan.table(), restamped, when);
        }
        if (rows > restamped) {
            log.warn("Relayout of '{}': {} row(s) in the epoch partition have no source row and are dropped {}",
                    plan.table(), rows - restamped, when);
        }
    }

    /**
     * Copies the rows of {@code from} written after {@code mark}, or up to {@link #lookback} before it, into
     * {@code into}, stamped from their source rows, one slice of them per statement: a catch-up after a re-derivation
     * can hold the whole table. Before, not only after: {@code _updated_at} is the time an insert started, so a write
     * that started before a later one but committed after the mark was read carries a stamp below the mark — and a
     * write runs for at most the pipeline budget, which the lookback covers. A row of that lookback {@code into}
     * already holds is skipped — it would only collapse with itself (same grain key, partition and
     * {@code _updated_at}), but not before a merge.
     *
     * @return the rows of {@code from} written after {@code mark}: the ones the copy is there for
     */
    private long copySince(Plan plan, String from, String into, Long mark) {
        if (mark == null) {
            return 0;
        }
        final String after = quote(UPDATED_AT) + " > " + millis(mark);
        final String recent = quote(UPDATED_AT) + " >= " + millis(mark - lookback.toMillis());
        final String version = "(" + quote(plan.grainKey()) + ", " + quote(UPDATED_AT) + ")";
        final long newer = countWhere(from, after);
        final String since = "(" + after + " OR (" + recent + " AND " + version + " NOT IN (SELECT " + version
                + " FROM " + quote(into) + " WHERE " + recent + ")))";
        forEachSlice(plan, from, since, null, slice -> copy(plan, from, into, slice, since));
        return newer;
    }

    /**
     * The rows of {@code from} (restricted by {@code where}) whose grain key {@code into} does not hold, split into
     * those with no source row — orphans, which a copy leaves behind by design — and those with one, which are lost.
     * Counted slice by slice: the slices are disjoint ranges of the key, so the counts add up.
     */
    private Missing missing(Plan plan, String from, String into, String where) {
        final long[] counts = new long[2];
        forEachSlice(plan, from, where, null, slice -> {
            final String key = quote(plan.grainKey());
            final String absent = "SELECT a." + GRAIN + " FROM (SELECT " + key + " AS " + GRAIN + " FROM " + quote(from)
                    + " WHERE " + (where == null ? "" : where + " AND ") + slice.where(key, plan.grainType())
                    + ") AS a LEFT ANTI JOIN (SELECT " + key + " AS " + GRAIN + " FROM " + quote(into) + " WHERE "
                    + slice.where(key, plan.grainType()) + ") AS b ON a." + GRAIN + " = b." + GRAIN;
            final long all = single("SELECT toInt64(uniqExact(" + GRAIN + ")) AS c FROM (" + absent + ") SETTINGS final = 0, "
                    + memoryCap());
            if (all > 0) {
                final long lost = single("SELECT toInt64(count()) AS c FROM (" + sourceGrains(plan, absent) + " AND "
                        + slice.where(quote(plan.identity()), plan.grainType()) + ") SETTINGS final = 0, " + memoryCap());
                counts[0] += all - lost;
                counts[1] += lost;
            }
        });
        return new Missing(counts[0], counts[1]);
    }

    /** The distinct source identities among the grain keys {@code grains} selects. */
    private static String sourceGrains(Plan plan, String grains) {
        return "SELECT DISTINCT " + quote(plan.identity()) + " FROM " + quote(plan.source()) + " WHERE "
                + quote(plan.identity()) + " IN (" + grains + ")";
    }

    /**
     * {@code INSERT INTO into SELECT … FROM from JOIN source}: every row of {@code from} in {@code slice} (and
     * restricted by {@code where}, when given) whose grain key is a source row, with the alignment column taken from
     * that source row and {@code _updated_at} kept. One statement may span any number of the target's partitions: a
     * slice is a range of keys, not of time.
     *
     * <p>The source side is restricted to the keys the slice holds, so what the join holds stays the slice's size even
     * when the table is sparse against its source. It is an {@code ANY} join rather than a {@code DISTINCT} over the
     * source: an identity has one partition value (the source is partition-local, which eligibility requires), so the
     * source's unmerged duplicates of a row are interchangeable, and a {@code DISTINCT} would hold a second set of the
     * slice's keys for nothing.
     */
    private void copy(Plan plan, String from, String into, Slice slice, String where) {
        final String target = String.join(", ", plan.insertColumns().stream().map(EnrichmentRelayoutService::quote).toList());
        final String values = "e." + quote(plan.grainKey()) + ", s." + PARTITION + ", "
                + plan.payloadColumns().stream().map(column -> "e." + quote(column) + ", ").collect(Collectors.joining())
                + "e." + quote(UPDATED_AT);
        final String rows = (where == null ? "" : where + " AND ") + slice.where(quote(plan.grainKey()), plan.grainType());
        final String sql = "INSERT INTO " + quote(into) + " (" + target + ") SELECT " + values + " FROM (SELECT * FROM "
                + quote(from) + " WHERE " + rows + ") AS e ANY INNER JOIN (SELECT " + quote(plan.identity()) + " AS " + GRAIN
                + ", " + quote(plan.alignment()) + " AS " + PARTITION + " FROM " + quote(plan.source()) + " WHERE "
                + slice.where(quote(plan.identity()), plan.grainType()) + " AND " + quote(plan.identity()) + " IN (SELECT "
                + quote(plan.grainKey()) + " FROM " + quote(from) + " WHERE " + rows + ")) AS s ON e." + quote(plan.grainKey())
                + " = s." + GRAIN + " SETTINGS final = 0, max_partitions_per_insert_block = 0, " + memoryCap();
        ddl.execute(sql);
    }

    /**
     * Runs {@code body} on each slice of {@code table}'s rows matching {@code where} (all rows when null) above the key
     * {@code after} (from the lowest when null), in key order, each holding about {@link #sliceBytes} of the table.
     * Each slice's upper key is read just before it runs — the key {@code n} rows on, in the table's own sort order —
     * and the last slice is open above, so together the slices cover every key above {@code after}, whatever is
     * written meanwhile. A key's row versions all fall in one slice: the upper key is inclusive.
     *
     * @return the number of slices run
     */
    private int forEachSlice(Plan plan, String table, String where, String after, Consumer<Slice> body) {
        final long rows = rowsPerSlice(table);
        final String key = quote(plan.grainKey());
        String lower = after;
        int slices = 0;
        while (true) {
            if (stopping || Thread.currentThread().isInterrupted()) {
                throw new RelayoutStopped();
            }
            final String condition = Stream.of(where, lower == null ? null : key + " > " + plan.literal(lower))
                    .filter(Objects::nonNull).collect(Collectors.joining(" AND "));
            final List<GenericRecord> upper = inspector.query("SELECT toString(" + key + ") AS k FROM " + quote(table)
                    + (condition.isEmpty() ? "" : " WHERE " + condition) + " ORDER BY " + key + " LIMIT 1 OFFSET "
                    + (rows - 1) + " SETTINGS final = 0", Map.of());
            final Slice slice = new Slice(lower, upper.isEmpty() ? null : upper.getFirst().getString("k"));
            body.accept(slice);
            slices++;
            if (slice.through() == null) {
                return slices;
            }
            lower = slice.through();
        }
    }

    /** How many of {@code table}'s rows hold {@link #sliceBytes}, by its parts' uncompressed bytes per row. */
    private long rowsPerSlice(String table) {
        final List<GenericRecord> parts = inspector.query("SELECT toInt64(sum(rows)) AS r, toInt64(sum(data_uncompressed_bytes)) "
                + "AS b FROM system.parts WHERE active AND database = currentDatabase() AND table = {table:String}",
                Map.of("table", table));
        final long rows = parts.isEmpty() ? 0 : parts.getFirst().getLong("r");
        final long bytes = parts.isEmpty() ? 0 : parts.getFirst().getLong("b");
        if (rows <= 0 || bytes <= 0) {
            return Integer.MAX_VALUE;
        }
        return Math.max(1, (long) (sliceBytes / ((double) bytes / rows)));
    }

    /** The memory cap every statement that reads a slice runs under: {@code pipeline.budget.max-memory}. */
    private String memoryCap() {
        return "max_memory_usage = " + budget.maxMemory().toBytes();
    }

    private Plan plan(TableDefinitionEntity table, TableDefinitionEntity source, String copy) {
        final String alignment = source.getPartitionColumn();
        final List<String> payload = columns.findByTableNameOrderByOrdinal(table.getName()).stream()
                .map(ColumnMappingEntity::getSourceName)
                .filter(column -> !column.equals(alignment))
                .toList();
        final List<String> insert = new ArrayList<>();
        insert.add(table.getGrainKey());
        insert.add(alignment);
        insert.addAll(payload);
        insert.add(UPDATED_AT);
        // The spec the table would get were it created today: a copy of the definition carrying the alignment the
        // catalog will record once the exchange is done — a copy, so no persistence context can ever flush it.
        final TableDefinitionEntity aligned = new TableDefinitionEntity();
        BeanUtils.copyProperties(table, aligned);
        aligned.setPartitionColumn(alignment);
        aligned.setPartitionGranularity(source.getPartitionGranularity());
        final TableDdlRenderer.CreateSpec full =
                schemaCreation.createSpec(aligned, true, lookupIndexes.requiredColumns(table.getName()));
        // The copy is created with no TTL, which is applied once the last repair has run: a row the previous build
        // writes with no alignment value sits at the epoch, already expired, and a TTL in force would drop its part
        // before the repair could re-stamp it.
        final TableDdlRenderer.CreateSpec spec = new TableDdlRenderer.CreateSpec(full.kind(), full.write(), full.columns(),
                full.orderingKey(), full.partitionColumn(), full.partitionGranularity(), null, false, full.lookupIndexes());
        return new Plan(table.getName(), copy, source.getName(), table.getGrainKey(), grainType(table), source.getIdentityColumn(),
                alignment, source.getPartitionGranularity(), payload, insert, spec, full.ttl(), full.ttlOnlyDropParts());
    }

    /** The ClickHouse type of {@code table}'s grain key column, which a slice's bounds are cast to. */
    private String grainType(TableDefinitionEntity table) {
        final List<GenericRecord> rows = inspector.query("SELECT type FROM system.columns WHERE database = currentDatabase() "
                + "AND table = {table:String} AND name = {column:String}", Map.of("table", table.getName(), "column", table.getGrainKey()));
        if (rows.isEmpty()) {
            throw new IllegalStateException("table '" + table.getName() + "' has no column '" + table.getGrainKey() + "'");
        }
        return rows.getFirst().getString("type");
    }

    private boolean aligned(String table) {
        return inspector.engineFull(table).map(engine -> engine.contains(" PARTITION BY ")).orElse(false);
    }

    private boolean hasPartition(String table, String partitionId) {
        return !inspector.query("SELECT 1 FROM system.parts WHERE active AND database = currentDatabase() "
                + "AND table = {table:String} AND partition_id = {id:String} LIMIT 1",
                Map.of("table", table, "id", partitionId)).isEmpty();
    }

    private long maxUpdatedAt(String table) {
        final List<GenericRecord> rows = inspector.query("SELECT toInt64(toUnixTimestamp64Milli(max(" + quote(UPDATED_AT)
                + "))) AS m FROM " + quote(table), Map.of());
        return rows.isEmpty() ? 0 : rows.getFirst().getLong("m");
    }

    private long count(String table, boolean collapsed) {
        return single("SELECT toInt64(count()) AS c FROM " + quote(table) + (collapsed ? " FINAL" : ""));
    }

    private long countWhere(String table, String where) {
        return single("SELECT toInt64(count()) AS c FROM " + quote(table) + " WHERE " + where);
    }

    /** The one {@code c} value {@code sql} selects. */
    private long single(String sql) {
        final List<GenericRecord> rows = inspector.query(sql, Map.of());
        return rows.isEmpty() ? 0 : rows.getFirst().getLong("c");
    }

    private static String millis(long mark) {
        return "fromUnixTimestamp64Milli(toInt64(" + mark + "))";
    }

    private ColumnMappingEntity sourceColumn(String source, String column) {
        return columns.findByTableNameOrderByOrdinal(source).stream()
                .filter(col -> col.getSourceName().equals(column))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("source '" + source + "' has no column '" + column + "'"));
    }

    private static RelayoutStateStore.State withMarks(RelayoutStateStore.State state, RelayoutStep step,
            String through, Long copyMark, Long catchUpMark, boolean hold) {
        return new RelayoutStateStore.State(state.table(), step, state.copyTable(), through, copyMark, catchUpMark,
                hold, null);
    }

    /**
     * Takes the hold again for a relayout resumed at {@link RelayoutStep#HELD} or {@link RelayoutStep#SWAPPED}: the
     * stop or failure that ended the earlier attempt released it, or it has since expired, so a writer that read the
     * catalog unaligned may be writing — into the aligned table, once exchanged, with no alignment value. Settling
     * again before the exchange, the epoch repair and the catalog update lets every such write land where the repair
     * still finds it.
     */
    private RelayoutStateStore.State holdAgain(RelayoutStateStore.State state) {
        final RelayoutStateStore.State held = withMarks(state, state.step(), state.copiedThrough(), state.copyMark(),
                state.catchUpMark(), true);
        states.save(held);
        log.info("Relayout of '{}': writers held again to resume at step {}", state.table(), state.step());
        settle(held);
        return held;
    }

    /**
     * Waits {@link #settle} inside the hold, saving {@code held} again every {@link #HEARTBEAT} so the hold does not
     * expire meanwhile ({@link RelayoutStateStore#HOLD_EXPIRY}).
     */
    private void settle(RelayoutStateStore.State held) {
        long remaining = settle.toMillis();
        while (remaining > 0) {
            final long slice = Math.min(remaining, HEARTBEAT.toMillis());
            try {
                Thread.sleep(slice);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new RelayoutStopped();
            }
            remaining -= slice;
            states.save(held);
        }
    }

    private static String quote(String identifier) {
        return Identifiers.quote(identifier);
    }

    /**
     * Runs {@code body} under a session-level Postgres advisory lock on {@code table}, on a connection held for the
     * whole run — so exactly one instance relays a table out, and a crashed instance's lock goes with its
     * connection. Skips {@code body} when another instance holds the lock, or when the lock cannot be taken.
     *
     * @return whether {@code body} ran
     */
    private boolean withLock(String table, Runnable body) {
        try (Connection connection = dataSource.getConnection()) {
            if (!advisory(connection, "pg_try_advisory_lock", table)) {
                log.info("Relayout of '{}' is being run by another instance; it is tried again in {}", table, GRACE);
                return false;
            }
            try {
                body.run();
            } finally {
                uninterrupted(() -> unlock(connection, table));
            }
            return true;
        } catch (SQLException ex) {
            log.error("Relayout of '{}' could not take its lock; it is tried again in {}: {}", table, GRACE, ex.getMessage());
            return false;
        }
    }

    private static void unlock(Connection connection, String table) {
        try {
            advisory(connection, "pg_advisory_unlock", table);
        } catch (SQLException ex) {
            log.warn("Relayout of '{}' could not release its lock; it is released when its connection closes: {}",
                    table, ex.getMessage());
        }
    }

    private static boolean advisory(Connection connection, String function, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + function + "(hashtext(?))")) {
            statement.setString(1, "enrichment_relayout:" + table);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    /** The job being stopped, thrown out of a wait so the state is kept rather than recorded failed. */
    private static final class RelayoutStopped extends RuntimeException {

        RelayoutStopped() {
            super("relayout stopped");
        }
    }

    /**
     * A range of grain keys: above {@code after} and up to {@code through}, both as {@code toString} renders a key;
     * null leaves that side open.
     */
    record Slice(String after, String through) {

        /** The condition holding {@code column} (already quoted, of ClickHouse type {@code type}) to this slice. */
        String where(String column, String type) {
            final List<String> bounds = new ArrayList<>();
            if (after != null) {
                bounds.add(column + " > " + literal(after, type));
            }
            if (through != null) {
                bounds.add(column + " <= " + literal(through, type));
            }
            return bounds.isEmpty() ? "1" : String.join(" AND ", bounds);
        }

        /**
         * {@code key} as a literal of type {@code type}: a string literal cast to the column's own type, so a key of any
         * type — a UUID, a string, a number, a timestamp — compares in the column's order and keeps its primary key
         * usable for the range.
         */
        static String literal(String key, String type) {
            return "CAST('" + key.replace("\\", "\\\\").replace("'", "\\'") + "' AS " + type + ")";
        }
    }

    /** The rows of one table missing from another: with no source row ({@code orphans}), and with one ({@code lost}). */
    record Missing(long orphans, long lost) {
    }

    /**
     * Everything a relayout of one table needs to render its statements.
     *
     * @param grainType      the ClickHouse type of the grain key column — and of the source's identity column, which
     *     it was created from
     * @param payloadColumns the table's catalog columns other than the alignment column, copied as they are
     * @param insertColumns  every column a copy writes: the grain key, the alignment column, the payload columns
     *     and {@code _updated_at}
     */
    record Plan(String table, String copy, String source, String grainKey, String grainType, String identity,
            String alignment, PartitionGranularity granularity, List<String> payloadColumns, List<String> insertColumns,
            TableDdlRenderer.CreateSpec createSpec, TableDdlRenderer.TtlDdl ttl, boolean ttlOnlyDropParts) {

        /** {@code key}, a grain key as {@code toString} rendered it, as a literal of the grain key's type. */
        String literal(String key) {
            return Slice.literal(key, grainType);
        }

        /** The function the table partitions the alignment column by. */
        String partitionFunction() {
            return switch (granularity) {
                case DAY -> "toYYYYMMDD";
                case MONTH -> "toYYYYMM";
                case YEAR -> "toYear";
            };
        }

        /** The partition id an alignment column left at its type default lands in. */
        String epochPartitionId() {
            return switch (granularity) {
                case DAY -> "19700101";
                case MONTH -> "197001";
                case YEAR -> "1970";
            };
        }
    }
}
