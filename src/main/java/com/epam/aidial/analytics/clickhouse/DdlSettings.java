package com.epam.aidial.analytics.clickhouse;

import com.clickhouse.client.api.query.QuerySettings;
import com.epam.aidial.analytics.configuration.ClickHouseDdlProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The server settings every DDL the service issues runs under, on every path: runtime DDL
 * ({@link ClickHouseDdlExecutor}), the Flyway connection, the migration pre-create and history-table creates, the
 * startup table conversion and the retention reconciliation.
 *
 * <p>Every statement in a {@code Replicated} database goes through the database's DDL queue, and by default waits
 * for every replica to apply it, then fails with {@code TIMEOUT_EXCEEDED} after {@code distributed_ddl_task_timeout}
 * (180 s) when one is down. {@code null_status_on_timeout_only_active} instead returns once the <em>active</em>
 * replicas applied it, reporting a down replica's status as {@code NULL}; that replica applies the DDL from the
 * database log when it recovers. The bound is {@code clickhouse.ddl.task-timeout}. See
 * {@code openspec/specs/clickhouse-replication/spec.md} "DDL completes while a replica is down".
 */
public final class DdlSettings {

    /** {@code distributed_ddl_output_mode}. */
    static final String OUTPUT_MODE = "distributed_ddl_output_mode";

    /** {@code distributed_ddl_task_timeout}. */
    static final String TASK_TIMEOUT = "distributed_ddl_task_timeout";

    /** The output mode that tolerates a stopped replica. */
    static final String ONLY_ACTIVE = "null_status_on_timeout_only_active";

    private DdlSettings() {
    }

    /** The two settings as {@code name → value}, in a stable order. */
    public static Map<String, String> of(Duration taskTimeout) {
        final Map<String, String> settings = new LinkedHashMap<>();
        settings.put(OUTPUT_MODE, ONLY_ACTIVE);
        settings.put(TASK_TIMEOUT, Long.toString(Math.max(1, taskTimeout.toSeconds())));
        return settings;
    }

    /** {@link #of(Duration)} for {@code properties}. */
    public static Map<String, String> of(ClickHouseDdlProperties properties) {
        return of(properties.taskTimeout());
    }

    /** The settings in the JDBC driver's {@code custom_settings} spelling: {@code name=value}, comma-separated. */
    public static String customSettings(Duration taskTimeout) {
        return of(taskTimeout).entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(","));
    }

    /** Applies the settings to {@code settings} and returns it. */
    public static QuerySettings apply(QuerySettings settings, Duration taskTimeout) {
        of(taskTimeout).forEach(settings::serverSetting);
        return settings;
    }
}
