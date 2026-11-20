package com.epam.aidial.analytics.clickhouse;

import com.clickhouse.client.api.command.CommandSettings;
import com.clickhouse.client.api.insert.InsertSettings;

/**
 * Caps ClickHouse's Keeper retries on one insert whose caller must hear promptly that Keeper is gone: the catalog row
 * writer behind {@code POST /v1/tables/{name}/rows}, the pipeline write-back and OTel ingest, the pipeline
 * {@code INSERT … SELECT}, and the data-sync requeue. Each of those callers is waiting on an HTTP response, or is a
 * pipeline run that the next tick retries with its cursor unmoved.
 *
 * <p>An insert into a replicated table whose replica has lost its Keeper session retries {@value #MAX_RETRIES} times
 * with a backoff doubling from 100 ms to {@value #MAX_BACKOFF_MS} ms, so it answers {@code TABLE_IS_READ_ONLY} after
 * about ten seconds. ClickHouse's own defaults (20 retries, up to 10 s apart) hold it for about two minutes: past a
 * proxy's read timeout, after which the insert may still land while the caller retries it.
 *
 * <p>The cap goes on the statement, never on a client: the writer clients also carry DDL, the startup checks, the
 * replica syncs and the enrichment relayout's copies, which keep ClickHouse's defaults, and a settings profile
 * constraint on either setting then refuses only the statements that carry it. Data-sync ingest keeps the defaults
 * too. See {@code openspec/specs/clickhouse-replication/spec.md}.
 */
public final class KeeperRetries {

    static final String MAX_RETRIES_SETTING = "insert_keeper_max_retries";
    static final String MAX_RETRIES = "8";
    static final String MAX_BACKOFF_MS_SETTING = "insert_keeper_retry_max_backoff_ms";
    static final String MAX_BACKOFF_MS = "2000";

    private KeeperRetries() {
    }

    /** Caps the Keeper retries of the {@code INSERT … SELECT} {@code settings} accompanies. */
    public static CommandSettings failFast(CommandSettings settings) {
        settings.serverSetting(MAX_RETRIES_SETTING, MAX_RETRIES);
        settings.serverSetting(MAX_BACKOFF_MS_SETTING, MAX_BACKOFF_MS);
        return settings;
    }

    /** Caps the Keeper retries of the insert {@code settings} accompanies. */
    public static InsertSettings failFast(InsertSettings settings) {
        settings.serverSetting(MAX_RETRIES_SETTING, MAX_RETRIES);
        settings.serverSetting(MAX_BACKOFF_MS_SETTING, MAX_BACKOFF_MS);
        return settings;
    }
}
