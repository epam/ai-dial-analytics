package com.epam.aidial.analytics.sync;

import com.epam.aidial.analytics.clickhouse.ClickHouseTableInspector;
import com.epam.aidial.analytics.clickhouse.DdlExecutor;
import com.epam.aidial.analytics.configuration.PayloadRetentionProperties;
import com.epam.aidial.analytics.service.table.InheritedTtl;
import com.epam.aidial.analytics.service.table.TableDdlRenderer;
import com.epam.aidial.analytics.service.table.TtlUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes the payload table's live {@code TTL} equal the configured retention ({@code sync.payload.ttl-days}).
 * Called by {@code ClickHouseMigrationRunner} right after the migrations, so it runs only where ADAS owns the
 * schema, and before anything reads or writes the table.
 *
 * <p>The live clause is read from {@code engine_full}. A single-rule {@code TTL} over {@code request_time} in days
 * that equals the configured value is left alone; anything else (another value or unit, another column, several
 * rules) is replaced. {@code -1} removes the clause.
 *
 * <p>The change is a plain {@code MODIFY TTL}. Its materialization mutation reads only {@code request_time} on a
 * table with {@code ttl_only_drop_parts = 1}, which the payload table carries: surviving parts keep their data, and
 * day partitions past a shortened retention are dropped whole. The mutation is asynchronous, so startup does not
 * wait for it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PayloadTtlReconciler {

    /** The column the payload table's {@code TTL} is expressed on. */
    static final String TTL_COLUMN = "request_time";
    /** The {@code TTL} clause of an {@code engine_full}, as the server renders it: up to {@code SETTINGS} or the end. */
    private static final Pattern TTL_CLAUSE = Pattern.compile(" TTL (.+?)(?= SETTINGS |$)");

    private final PayloadRetentionProperties properties;
    private final ClickHouseTableInspector inspector;
    private final DdlExecutor ddl;
    private final TableDdlRenderer ddlRenderer;

    /**
     * Reconciles the payload table's {@code TTL}; a table that does not exist is skipped.
     *
     * @throws com.epam.aidial.analytics.exception.ClickHouseQueryException       if ClickHouse rejects the change
     * @throws com.epam.aidial.analytics.exception.ClickHouseUnavailableException if ClickHouse cannot be reached
     */
    public void reconcile() {
        final String table = properties.table();
        final Optional<String> engineFull = inspector.engineFull(table);
        if (engineFull.isEmpty()) {
            log.info("Payload table '{}' does not exist; its TTL reconciliation was skipped", table);
            return;
        }
        final Optional<String> command = command(engineFull.get(), properties.ttlDays(), ddlRenderer);
        if (command.isEmpty()) {
            log.debug("Payload table '{}' already carries the configured retention", table);
            return;
        }
        ddl.execute(ddlRenderer.renderAlter(table, List.of(command.get())));
        log.warn("Payload table '{}' retention changed from {} to {}", table,
                ttlClause(engineFull.get()).orElse("none"), describe(properties.ttlDays()));
    }

    /**
     * The {@code ALTER TABLE} command that makes a table with {@code engineFull} carry {@code ttlDays}, or empty when
     * it already does.
     */
    static Optional<String> command(String engineFull, int ttlDays, TableDdlRenderer ddlRenderer) {
        if (ttlDays == PayloadRetentionProperties.NO_TTL) {
            return ttlClause(engineFull).isPresent() ? Optional.of(ddlRenderer.removeTtlCommand()) : Optional.empty();
        }
        final boolean matches = InheritedTtl.parse(engineFull, TTL_COLUMN)
                .map(InheritedTtl::ttl)
                .filter(ttl -> ttl.unit() == TtlUnit.DAY && ttl.interval() == ttlDays)
                .isPresent();
        return matches ? Optional.empty() : Optional.of(ddlRenderer.modifyTtlCommand(
                new TableDdlRenderer.TtlDdl(TTL_COLUMN, ttlDays, TtlUnit.DAY, true)));
    }

    /** The {@code TTL} clause {@code engineFull} declares, without the keyword, or empty when it declares none. */
    static Optional<String> ttlClause(String engineFull) {
        final Matcher matcher = TTL_CLAUSE.matcher(engineFull);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private static String describe(int ttlDays) {
        return ttlDays == PayloadRetentionProperties.NO_TTL ? "none" : ttlDays + " day(s)";
    }
}
