package com.epam.aidial.analytics.clickhouse;

import com.clickhouse.client.api.command.CommandResponse;
import com.clickhouse.client.api.command.CommandSettings;
import com.epam.aidial.analytics.configuration.ResourceBudget;
import com.epam.aidial.analytics.exception.ClickHouseQueryException;
import com.epam.aidial.analytics.exception.ClickHouseUnavailableException;
import com.epam.aidial.analytics.observability.SqlLog;
import com.epam.aidial.analytics.observability.trace.LogExecution;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Runs a server-side {@code INSERT … SELECT} against ClickHouse on the dedicated <em>writer</em>
 * credential ({@code clickhouse.writer.*}, via the shared {@link ClickHouseWriterClient}) and returns the
 * server-reported written-row count. This is the Class-1 materialize path's only write: the derived values
 * are computed inside ClickHouse and no row payload ever crosses the wire, which is both the performance
 * argument (set-based) and the privacy argument (sensitive bodies never leave the database).
 *
 * <p>Because the statement <em>reads</em> the source as well as writing the target, the writer
 * credential needs {@code SELECT} on scan sources in addition to {@code INSERT} — see the operational
 * invariants in {@code openspec/config.yaml}. The statement is built entirely by ADAS from a stored,
 * already-governed declaration; no caller-supplied SQL reaches it.
 *
 * <p>A single ClickHouse {@code INSERT} is atomic, so a window is all-or-nothing: a failure leaves the
 * target untouched for that window and the caller's cursor unmoved, and an identical retry is safe.
 * Connectivity failures surface as {@link ClickHouseUnavailableException} (503); a rejected statement
 * surfaces as {@link ClickHouseQueryException} (400). Raw ClickHouse messages are never propagated.
 */
@Component
@Slf4j
@RequiredArgsConstructor
@LogExecution
public class ClickHouseInsertSelectExecutor {

    /**
     * Lifted ({@code 0}) on every pipeline and catalog row write. An aligned enrichment is partitioned by day like its
     * source, so a Class-1 window or a write-back batch spanning more than the server default of 100 day partitions —
     * any backfill over a long range — would be refused on every tick, and narrowing cannot help: the window is
     * bounded by rows, not days. The parts such a write creates are the ones its rows need; the limit guards against
     * an accidental partition key, which ADAS never renders.
     */
    static final String MAX_PARTITIONS_PER_INSERT_BLOCK = "max_partitions_per_insert_block";

    private final ClickHouseWriterClient writer;
    private final ClickHouseSpans clickHouseSpans;

    /**
     * Executes one complete {@code INSERT … SELECT} statement (identifiers quoted, parameters inlined)
     * with {@code final=1} applied so the {@code SELECT} half sees only the latest version per source
     * identity — and, when {@code partitionLocal}, deduplicating inside each partition
     * ({@link FinalSettings}), which the caller asserts from the input entity's model exactly as the read
     * path does — under {@code budget}'s ceilings, and tagged with a fresh query id minted from {@code tag}.
     *
     * <p>The write carries a ceiling for the same reason every read does: which statement of a pipeline
     * reads an enrichment is decided per statement, so a pipeline whose filter names no enrichment does its
     * whole enrichment read <em>here</em>. Left unbounded, that is the one statement in the system with no
     * ceiling, and it can be the largest single consumer of memory on the node. The Class-1 caller narrows
     * its window and retries on a refusal; the aggregate caller fails the run. There is no unbudgeted form:
     * both callers are pipeline writes.
     *
     * @param target         the target table, used only as the span's {@code db.collection.name} attribute
     * @param sql            the complete statement
     * @param partitionLocal whether every version-collapsing table the statement reads is partition-local
     * @param budget         the ceilings the statement runs under
     * @param tag            names the statement; a fresh id is minted per call, so each retry has its own
     * @return the number of rows ClickHouse reports written
     * @throws ClickHouseUnavailableException if ClickHouse cannot be reached
     * @throws ClickHouseQueryException       if ClickHouse rejects the statement
     */
    public long insertSelect(String target, String sql, boolean partitionLocal, ResourceBudget budget, StatementTag tag) {
        SqlLog.LOG.debug("Executing ClickHouse INSERT … SELECT into {}: {}", target, sql);
        final CommandSettings settings = FinalSettings.pipeline(new CommandSettings(), partitionLocal);
        settings.serverSetting(MAX_PARTITIONS_PER_INSERT_BLOCK, "0");
        ClickHouseBudget.apply(settings, Objects.requireNonNull(budget, "budget"));
        final String queryId = tag.applyTo(settings);
        return clickHouseSpans.insertSelect(target, queryId, () -> {
            try (CommandResponse response = writer.client().execute(sql, settings).get()) {
                return response.getWrittenRows();
            } catch (InterruptedException ex) {
                throw ClickHouseWriterClient.interrupted("Interrupted while writing to ClickHouse", ex);
            } catch (Exception ex) {
                if (ClickHouseFailures.isUnavailable(ex)) {
                    throw new ClickHouseUnavailableException("ClickHouse is unreachable", ex);
                }
                throw new ClickHouseQueryException("ClickHouse rejected the statement", ex);
            }
        });
    }
}
