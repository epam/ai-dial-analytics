package com.epam.aidial.analytics.service.pipeline.model;

import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Time-bucket unit of an aggregate group key. Every unit here compiles through the shared query catalog's
 * {@code date_trunc(unit, timestamp)}, which reaches both the fixed-width units and the calendar ones.
 *
 * <p><b>{@code MONTH} is a calendar month</b>, not a fixed number of days: buckets of differing length,
 * each starting at midnight on the 1st. That is why it compiles through {@code date_trunc} rather than
 * {@code date_bin} — the latter is fixed-width by contract, and treating a month as 30 days would produce
 * buckets that are wrong in a way nobody would notice. The compiler's pruning step
 * ({@code + INTERVAL 1 <keyword>}) is done by the engine, so it advances a true calendar month too.
 *
 * <p>The supported set is narrower than {@code date_trunc}'s, which also accepts {@code second},
 * {@code minute}, {@code quarter} and {@code year}: a rollup finer than an hour would carry about as many
 * keys as the rows it summarizes, and a refresh costs per dirty key; the coarser two simply have no caller
 * yet. Widening it is additive — add the constant here and the catalog already translates it — but an
 * {@code amount} multiplier (a 15-minute grain) is not, because {@link #intervalKeyword()} below is used as
 * a width-one step.
 *
 * <p><b>{@link #isSupported()} is retained even though every unit currently satisfies it.</b> It is the
 * seam the validator and the error contract route through: a unit that is part of the declaration's
 * vocabulary but not implemented must be refused by validation with a 422 naming the alternatives, not by
 * Jackson with a generic 400 — the same reason {@link FreshnessMode#INCREMENTAL} is declared. The next
 * such unit needs this to still be here.
 *
 * <p><b>Alignment follows ClickHouse's {@code toStartOfInterval}</b>, which is epoch-aligned for
 * {@code HOUR}/{@code DAY} but <em>Monday</em>-aligned for {@code WEEK} — the epoch itself fell on a
 * Thursday, so a literally epoch-aligned week would start on one. Monday (ISO) is what the design's D-GE-1
 * pins, and it is what the engine does. {@code MONTH} aligns to the calendar, not the epoch.
 *
 * <p><b>The alignment zone is UTC, always.</b> The bucket is compiled with an explicit {@code 'UTC'}
 * argument, so a {@code DAY} bucket is UTC midnight regardless of how the bucketed column or the ClickHouse
 * server is configured — see {@code ExprTranslator#dateTrunc} for why leaving it to the column would make a
 * rollup's day boundary a property of the deployment.
 *
 * <p>Stored by {@link Enum#name()} inside the pipeline's {@code spec} JSONB; the wire form is the
 * lowercase {@link #code()}.
 */
public enum TruncUnit {
    HOUR("hour", "HOUR", EnumSet.of(QueryFieldType.TIMESTAMP)),
    DAY("day", "DAY", EnumSet.of(QueryFieldType.DATE, QueryFieldType.TIMESTAMP)),
    WEEK("week", "WEEK", EnumSet.of(QueryFieldType.DATE, QueryFieldType.TIMESTAMP)),
    /** Calendar month, starting at UTC midnight on the 1st; months of differing length each form one bucket. */
    MONTH("month", "MONTH", EnumSet.of(QueryFieldType.DATE, QueryFieldType.TIMESTAMP));

    private final String code;
    private final String intervalKeyword;
    private final Set<QueryFieldType> targetTypes;

    TruncUnit(String code, String intervalKeyword, Set<QueryFieldType> targetTypes) {
        this.code = code;
        this.intervalKeyword = intervalKeyword;
        this.targetTypes = targetTypes;
    }

    /** Whether a bucket of this width can actually be compiled; a declared-but-unsupported one is a 422. */
    public boolean isSupported() {
        return intervalKeyword != null;
    }

    /**
     * The declared target column types that can hold a bucket of this width without losing part of it.
     *
     * <p>An {@code HOUR} bucket needs a {@code timestamp}: bound to a {@code date} column the hour is
     * truncated away on insert, and because the bucket is part of the target's {@code ordering_key} the
     * engine then collapses all 24 of a day's rows onto one arbitrary survivor — a silent loss of rows with
     * no error anywhere. A {@code DAY}, {@code WEEK} or {@code MONTH} bucket starts at midnight, so either
     * temporal type holds it losslessly.
     */
    public Set<QueryFieldType> targetTypes() {
        return targetTypes;
    }

    /** The wire codes of the units a bucket can actually be declared with, for an error message to name. */
    public static String supportedCodes() {
        return Arrays.stream(values())
                .filter(TruncUnit::isSupported)
                .map(TruncUnit::code)
                .collect(Collectors.joining(", "));
    }

    @JsonValue
    public String code() {
        return code;
    }

    /**
     * The SQL {@code INTERVAL} keyword for one bucket of this unit. Stepping a bucket start by
     * {@code INTERVAL 1 <keyword>} lands exactly on the next bucket start — and letting the engine do that
     * arithmetic, rather than adding a millisecond count computed here, keeps the step in the same calendar
     * and time zone the bucketing itself used. A closed constant, never caller input, so it is safe to
     * render into a statement.
     *
     * @return null for a unit that is declared but not implemented, which is refused at registration and
     *     never reaches the compiler; every unit currently declared has one
     */
    public String intervalKeyword() {
        return intervalKeyword;
    }
}
