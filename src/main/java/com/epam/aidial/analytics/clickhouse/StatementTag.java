package com.epam.aidial.analytics.clickhouse;

import com.clickhouse.client.api.query.QuerySettings;
import org.slf4j.MDC;

import java.util.UUID;

/**
 * Names one pipeline statement for ClickHouse's {@code system.query_log}: the pipeline it runs for and its role
 * in the call ({@code probe}, {@code write}, {@code scan}, …). {@link #applyTo} mints a fresh
 * {@code adas-{pipeline}-{stage}-{uuid}} id per statement and sets it on that statement's settings.
 *
 * <p><b>Minted per statement, never carried over.</b> A narrowing retry or a re-probe is a new statement and
 * gets a new id, so each attempt is its own row in {@code system.query_log}. The budget settings for each
 * attempt are built fresh, and {@link ClickHouseBudget#remainingTime} drops the id from the settings it copies,
 * so no path hands one statement's id to the next.
 *
 * <p><b>Published as MDC {@value #MDC_KEY}</b> and left in place after the statement. It is the id of the
 * thread's most recent pipeline statement, so the lines logged after it (the call's outcome line, or the
 * refusal the error handler logs when the statement failed) carry the id they describe, and the OTLP appender
 * exports it. The request's access-log filter restores it when the request ends; a scheduled refresh clears it
 * per pipeline.
 */
public record StatementTag(String pipeline, String stage) {

    /** MDC key of the most recent pipeline statement's ClickHouse query id; exported by the OTLP appender. */
    public static final String MDC_KEY = "query_id";

    /** A fresh id for one statement. */
    public String mint() {
        return "adas-" + pipeline + "-" + stage + "-" + UUID.randomUUID();
    }

    /**
     * Mints an id, sets it on {@code settings} and publishes it as MDC {@value #MDC_KEY}.
     *
     * @return the id
     */
    public String applyTo(QuerySettings settings) {
        final String id = mint();
        settings.setQueryId(id);
        MDC.put(MDC_KEY, id);
        return id;
    }

    /** The id of this thread's most recent pipeline statement, or {@code null} when none ran. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }

    /** Forgets the thread's most recent statement id. */
    public static void clear() {
        MDC.remove(MDC_KEY);
    }
}
