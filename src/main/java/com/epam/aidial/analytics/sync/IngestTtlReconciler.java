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
 * Makes the live {@code TTL} of the usage log and of the payload table equal their configured retentions
 * ({@code sync.usage-log.ttl-days}, {@code sync.payload.ttl-days}). Called by {@code ClickHouseMigrationRunner}
 * right after the migrations, so it runs only where ADAS owns the schema, and before anything reads or writes the
 * tables.
 *
 * <p>Constructing it checks that the payload retention does not exceed the usage log's, because a payload row is
 * only read through its usage row. The bean exists whether or not migrations run, so the check always fails
 * startup on such a configuration, even where nothing is reconciled.
 *
 * <p>The live clause is read from {@code engine_full}. A single-rule {@code TTL} over {@code request_time} with the
 * configured unit and interval is left alone; anything else (another value or unit, another column, several rules)
 * is replaced. {@code -1} removes the clause.
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
    /** The usage log's provisioned retention (V1), kept while {@code sync.usage-log.ttl-days} is unset. */
    static final TtlDdl PROVISIONED_USAGE_LOG_TTL = new TtlDdl(TTL_COLUMN, 1, TtlUnit.YEAR, true);
    /** The provisioned year in days for the payload bound: the shortest a year can be, so the bound never admits more. */
    static final int PROVISIONED_YEAR_DAYS = 365;
    /** The {@code TTL} clause of an {@code engine_full}, as the server renders it: up to {@code SETTINGS} or the end. */
    private static final Pattern TTL_CLAUSE = Pattern.compile(" TTL (.+?)(?= SETTINGS |$)");

    private final UsageLogRetentionProperties usageLog;
    private final PayloadRetentionProperties payload;
    private final ClickHouseTableInspector inspector;
    private final DdlExecutor ddl;
    private final TableDdlRenderer ddlRenderer;

    /**
     * @throws IllegalStateException if the payload retention is longer than the usage log's
     */
    public IngestTtlReconciler(UsageLogRetentionProperties usageLog, PayloadRetentionProperties payload,
            ClickHouseTableInspector inspector, DdlExecutor ddl, TableDdlRenderer ddlRenderer) {
        checkPayloadWithinUsageLog(usageLog, payload);
        this.usageLog = usageLog;
        this.payload = payload;
        this.inspector = inspector;
        this.ddl = ddl;
        this.ddlRenderer = ddlRenderer;
    }

    /**
     * Reconciles the usage log's {@code TTL}, then the payload table's; a table that does not exist is skipped.
     *
     * @throws com.epam.aidial.analytics.exception.ClickHouseQueryException       if ClickHouse rejects a change
     * @throws com.epam.aidial.analytics.exception.ClickHouseUnavailableException if ClickHouse cannot be reached
     */
    public void reconcile() {
        reconcile(usageLog.table(), usageLogTtl(usageLog));
        reconcile(payload.table(), payloadTtl(payload));
    }

    private void reconcile(String table, Optional<TtlDdl> target) {
        final Optional<String> engineFull = inspector.engineFull(table);
        if (engineFull.isEmpty()) {
            log.info("Table '{}' does not exist; its TTL reconciliation was skipped", table);
            return;
        }
        final Optional<String> command = command(engineFull.get(), target, ddlRenderer);
        if (command.isEmpty()) {
            log.debug("Table '{}' already carries the configured retention", table);
            return;
        }
        ddl.execute(ddlRenderer.renderAlter(table, List.of(command.get())));
        log.warn("Table '{}' retention changed from {} to {}", table,
                ttlClause(engineFull.get()).orElse("none"), describe(target));
    }

    /** The usage log's target {@code TTL}: empty for none, the provisioned year while unset, else days. */
    static Optional<TtlDdl> usageLogTtl(UsageLogRetentionProperties properties) {
        if (properties.noTtl()) {
            return Optional.empty();
        }
        return Optional.of(properties.provisionedYear() ? PROVISIONED_USAGE_LOG_TTL : days(properties.ttlDays()));
    }

    /** The payload table's target {@code TTL}: empty for none, else days. */
    static Optional<TtlDdl> payloadTtl(PayloadRetentionProperties properties) {
        return properties.noTtl() ? Optional.empty() : Optional.of(days(properties.ttlDays()));
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

    static void checkPayloadWithinUsageLog(UsageLogRetentionProperties usageLog, PayloadRetentionProperties payload) {
        final long payloadDays = payload.noTtl() ? Long.MAX_VALUE : payload.ttlDays();
        if (payloadDays > usageLogDays(usageLog)) {
            final String usageLogValue = usageLog.provisionedYear()
                    ? "unset, one year counted as " + PROVISIONED_YEAR_DAYS + " days" : String.valueOf(usageLog.ttlDays());
            throw new IllegalStateException("sync.payload.ttl-days (" + payload.ttlDays()
                    + ") must not exceed sync.usage-log.ttl-days (" + usageLogValue
                    + "): a payload row is only read through its usage-log row; lower SYNC_PAYLOAD_TTL_DAYS or raise"
                    + " SYNC_USAGE_LOG_TTL_DAYS (-1 on both keeps everything)");
        }
    }

    /** The usage-log retention in days for the payload bound; no {@code TTL} is unbounded. */
    private static long usageLogDays(UsageLogRetentionProperties usageLog) {
        if (usageLog.noTtl()) {
            return Long.MAX_VALUE;
        }
        return usageLog.provisionedYear() ? PROVISIONED_YEAR_DAYS : usageLog.ttlDays();
    }

    private static TtlDdl days(int days) {
        return new TtlDdl(TTL_COLUMN, days, TtlUnit.DAY, true);
    }

    private static String describe(Optional<TtlDdl> target) {
        return target.map(ttl -> ttl.interval() + " " + ttl.unit().code() + "(s)").orElse("none");
    }
}
