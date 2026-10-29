package com.epam.aidial.analytics.sync;

import com.epam.aidial.analytics.clickhouse.ClickHouseTableInspector;
import com.epam.aidial.analytics.clickhouse.DdlExecutor;
import com.epam.aidial.analytics.configuration.PayloadRetentionProperties;
import com.epam.aidial.analytics.configuration.UsageLogRetentionProperties;
import com.epam.aidial.analytics.service.table.InheritedTtl;
import com.epam.aidial.analytics.service.table.TableDdlRenderer;
import com.epam.aidial.analytics.service.table.TableDdlRenderer.TtlDdl;
import com.epam.aidial.analytics.service.table.TtlUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes the live {@code TTL} of the usage log and of the payload table equal their retentions, where those are
 * set ({@code sync.usage-log.ttl-days}, {@code sync.payload.ttl-days}). An unset property leaves its table's live
 * {@code TTL} alone, so removing a property never alters a table. Called by {@code ClickHouseMigrationRunner} right
 * after the migrations, so it runs only where ADAS owns the schema, and before anything reads or writes the tables.
 *
 * <p>The payload retention must not exceed the usage log's, because a payload row is only read through its usage
 * row. The bound is judged on effective retentions: a set value, or else the live {@code TTL}. Two set values are
 * checked at construction; the bean exists whether or not migrations run, so that check always fails startup.
 * With one set, {@link #reconcile()} checks it against the other table's live {@code TTL} before any
 * {@code ALTER}. With neither set nothing is altered, so a live violation is only logged.
 *
 * <p>The live clause is read from {@code engine_full}. A single-rule {@code TTL} over {@code request_time} with the
 * set unit and interval is left alone; anything else (another value or unit, another column, several rules) is
 * replaced. {@code -1} removes the clause.
 *
 * <p>The change is a plain {@code MODIFY TTL}. Its materialization mutation reads only {@code request_time} on a
 * table with {@code ttl_only_drop_parts = 1}, which both tables carry: surviving parts keep their data, and day
 * partitions past a shortened retention are dropped whole. The mutation is asynchronous, so startup does not wait
 * for it.
 */
@Slf4j
@Component
public class IngestTtlReconciler {

    /** The column both tables express their {@code TTL} on. */
    static final String TTL_COLUMN = "request_time";
    /** The {@code TTL} clause of an {@code engine_full}, as the server renders it: up to {@code SETTINGS} or the end. */
    private static final Pattern TTL_CLAUSE = Pattern.compile(" TTL (.+?)(?= SETTINGS |$)");

    private final UsageLogRetentionProperties usageLog;
    private final PayloadRetentionProperties payload;
    private final ClickHouseTableInspector inspector;
    private final DdlExecutor ddl;
    private final TableDdlRenderer ddlRenderer;

    /**
     * @throws IllegalStateException if both retentions are set and the payload's is longer than the usage log's
     */
    public IngestTtlReconciler(UsageLogRetentionProperties usageLog, PayloadRetentionProperties payload,
            ClickHouseTableInspector inspector, DdlExecutor ddl, TableDdlRenderer ddlRenderer) {
        checkSetRetentions(usageLog, payload);
        this.usageLog = usageLog;
        this.payload = payload;
        this.inspector = inspector;
        this.ddl = ddl;
        this.ddlRenderer = ddlRenderer;
    }

    /**
     * Checks the payload bound against the live tables, then reconciles the usage log's {@code TTL} and the payload
     * table's, each only where its property is set; a table that does not exist is skipped.
     *
     * @throws IllegalStateException                                              if exactly one retention is set
     *                                                                            and the effective payload retention
     *                                                                            is longer than the usage log's
     * @throws com.epam.aidial.analytics.exception.ClickHouseQueryException       if ClickHouse rejects a change
     * @throws com.epam.aidial.analytics.exception.ClickHouseUnavailableException if ClickHouse cannot be reached
     */
    public void reconcile() {
        final Optional<String> usageLogEngine = inspector.engineFull(usageLog.table());
        final Optional<String> payloadEngine = inspector.engineFull(payload.table());
        checkLiveRetentions(usageLogEngine, payloadEngine);
        // Each final state honours the bound; the order keeps the state between the two ALTERs honouring it too
        // whenever some order provably does, so a failed second ALTER then cannot leave payloads outliving their
        // events. The usage log goes first only when the payload's current retention provably fits under its new
        // one; otherwise the payload goes first, which is also the choice where no order is provably safe.
        if (payloadFitsNewUsageLog(payloadEngine)) {
            reconcile(usageLog.table(), usageLog.ttlDays(), usageLogEngine);
            reconcile(payload.table(), payload.ttlDays(), payloadEngine);
        } else {
            reconcile(payload.table(), payload.ttlDays(), payloadEngine);
            reconcile(usageLog.table(), usageLog.ttlDays(), usageLogEngine);
        }
    }

    private void reconcile(String table, Integer ttlDays, Optional<String> engineFull) {
        if (engineFull.isEmpty()) {
            log.info("Table '{}' does not exist; its TTL reconciliation was skipped", table);
            return;
        }
        if (ttlDays == null) {
            log.info("Table '{}' retention is not set; keeping its live TTL {}", table,
                    ttlClause(engineFull.get()).orElse("none"));
            return;
        }
        final Optional<TtlDdl> target = target(ttlDays);
        final Optional<String> command = command(engineFull.get(), target, ddlRenderer);
        if (command.isEmpty()) {
            log.debug("Table '{}' already carries the configured retention", table);
            return;
        }
        ddl.execute(ddlRenderer.renderAlter(table, List.of(command.get())));
        log.warn("Table '{}' retention changed from {} to {}", table,
                ttlClause(engineFull.get()).orElse("none"), describe(target));
    }

    /**
     * Whether altering the usage log first is safe: it is not being set, its new retention is unlimited, or the
     * payload table's live retention fits under it. An unreadable or missing payload {@code TTL} does not count as
     * fitting a finite one.
     */
    private boolean payloadFitsNewUsageLog(Optional<String> payloadEngine) {
        if (usageLog.unset() || usageLog.noTtl()) {
            return true;
        }
        if (payloadEngine.isEmpty()) {
            return false;
        }
        return effective(null, payloadEngine)
                .map(live -> !exceeds(live, target(usageLog.ttlDays())))
                .orElse(false);
    }

    /** The {@code TTL} a set {@code ttlDays} asks for: empty for {@code -1} (none), else that many days. */
    static Optional<TtlDdl> target(int ttlDays) {
        return ttlDays == UsageLogRetentionProperties.NO_TTL
                ? Optional.empty() : Optional.of(new TtlDdl(TTL_COLUMN, ttlDays, TtlUnit.DAY, true));
    }

    /**
     * The {@code ALTER TABLE} command that makes a table with {@code engineFull} carry {@code target} (empty for no
     * {@code TTL}), or empty when it already does.
     */
    static Optional<String> command(String engineFull, Optional<TtlDdl> target, TableDdlRenderer ddlRenderer) {
        if (target.isEmpty()) {
            return ttlClause(engineFull).isPresent() ? Optional.of(ddlRenderer.removeTtlCommand()) : Optional.empty();
        }
        final TtlDdl ttl = target.get();
        final boolean matches = InheritedTtl.parse(engineFull, ttl.column())
                .map(InheritedTtl::ttl)
                .filter(live -> live.unit() == ttl.unit() && live.interval() == ttl.interval())
                .isPresent();
        return matches ? Optional.empty() : Optional.of(ddlRenderer.modifyTtlCommand(ttl));
    }

    /** The {@code TTL} clause {@code engineFull} declares, without the keyword, or empty when it declares none. */
    static Optional<String> ttlClause(String engineFull) {
        final Matcher matcher = TTL_CLAUSE.matcher(engineFull);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    /** The bound over two set values, which needs no table and so holds whether or not migrations run. */
    static void checkSetRetentions(UsageLogRetentionProperties usageLog, PayloadRetentionProperties payload) {
        if (usageLog.unset() || payload.unset()) {
            return;
        }
        if (exceeds(target(payload.ttlDays()), target(usageLog.ttlDays()))) {
            throw boundViolation(String.valueOf(payload.ttlDays()), String.valueOf(usageLog.ttlDays()));
        }
    }

    /** The bound where at least one side is unset, read off the live tables. */
    private void checkLiveRetentions(Optional<String> usageLogEngine, Optional<String> payloadEngine) {
        if (!usageLog.unset() && !payload.unset()) {
            return;
        }
        final Optional<Optional<TtlDdl>> usageLogTtl = effective(usageLog.ttlDays(), usageLogEngine);
        final Optional<Optional<TtlDdl>> payloadTtl = effective(payload.ttlDays(), payloadEngine);
        if (usageLogEngine.isEmpty() || payloadEngine.isEmpty()) {
            return;
        }
        if (usageLogTtl.isEmpty() || payloadTtl.isEmpty()) {
            log.warn("The live TTL of '{}' has a shape that cannot be read as one interval over {}; the bound of the"
                    + " payload retention by the usage log's was not checked",
                    usageLogTtl.isEmpty() ? usageLog.table() : payload.table(), TTL_COLUMN);
            return;
        }
        if (!exceeds(payloadTtl.get(), usageLogTtl.get())) {
            return;
        }
        final String payloadValue = describeSide(payload.ttlDays(), payloadEngine.get());
        final String usageLogValue = describeSide(usageLog.ttlDays(), usageLogEngine.get());
        if (usageLog.unset() && payload.unset()) {
            log.warn("Table '{}' keeps its payloads longer ({}) than '{}' keeps their events ({}); neither retention"
                    + " is set, so nothing was changed", payload.table(), payloadValue, usageLog.table(), usageLogValue);
            return;
        }
        throw boundViolation(payloadValue, usageLogValue);
    }

    /**
     * A table's effective retention for the bound: its set value, else its live {@code TTL}. The inner value is empty
     * for no {@code TTL}; the outer is empty when the table is missing or its live {@code TTL} cannot be read.
     */
    static Optional<Optional<TtlDdl>> effective(Integer ttlDays, Optional<String> engineFull) {
        if (ttlDays != null) {
            return Optional.of(target(ttlDays));
        }
        if (engineFull.isEmpty()) {
            return Optional.empty();
        }
        if (ttlClause(engineFull.get()).isEmpty()) {
            return Optional.of(Optional.empty());
        }
        return InheritedTtl.parse(engineFull.get(), TTL_COLUMN).map(live -> Optional.of(live.ttl()));
    }

    /**
     * Whether a payload {@code TTL} can outlast a usage-log one (empty for none). Like units compare their intervals;
     * otherwise the payload's widest reading in days is compared with the usage log's narrowest, so a calendar unit
     * never lets a payload outlive its event.
     */
    static boolean exceeds(Optional<TtlDdl> payloadTtl, Optional<TtlDdl> usageLogTtl) {
        if (usageLogTtl.isEmpty()) {
            return false;
        }
        if (payloadTtl.isEmpty()) {
            return true;
        }
        final TtlDdl payloadRule = payloadTtl.get();
        final TtlDdl usageLogRule = usageLogTtl.get();
        if (payloadRule.unit() == usageLogRule.unit()) {
            return payloadRule.interval() > usageLogRule.interval();
        }
        return widestDays(payloadRule) > narrowestDays(usageLogRule);
    }

    private static long widestDays(TtlDdl ttl) {
        final long n = ttl.interval();
        return switch (ttl.unit()) {
            case HOUR -> (n + 23) / 24;
            case DAY -> n;
            case WEEK -> 7 * n;
            case MONTH -> 31 * n;
            case YEAR -> 366 * n;
        };
    }

    private static long narrowestDays(TtlDdl ttl) {
        final long n = ttl.interval();
        return switch (ttl.unit()) {
            case HOUR -> n / 24;
            case DAY -> n;
            case WEEK -> 7 * n;
            case MONTH -> 28 * n;
            case YEAR -> 365 * n;
        };
    }

    private static IllegalStateException boundViolation(String payloadValue, String usageLogValue) {
        return new IllegalStateException("sync.payload.ttl-days (" + payloadValue
                + ") must not exceed sync.usage-log.ttl-days (" + usageLogValue
                + "): a payload row is only read through its usage-log row; lower SYNC_PAYLOAD_TTL_DAYS or raise"
                + " SYNC_USAGE_LOG_TTL_DAYS (-1 on both keeps everything)");
    }

    private static String describeSide(Integer ttlDays, String engineFull) {
        return ttlDays != null ? String.valueOf(ttlDays) : "unset, live TTL " + ttlClause(engineFull).orElse("none");
    }

    private static String describe(Optional<TtlDdl> target) {
        return target.map(ttl -> ttl.interval() + " " + ttl.unit().code() + "(s)").orElse("none");
    }
}
