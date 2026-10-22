package com.epam.aidial.analytics.service.enrichment.support;

import com.epam.aidial.analytics.exception.InvalidCursorException;
import com.epam.aidial.analytics.exception.QueryValidationException;
import org.apache.commons.lang3.StringUtils;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.impl.DSL;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The SQL fragments that define a governed incremental read over a source's {@link CursorColumns}: the
 * cursor tuple's expressions, the safety-lag horizon, the optional caller window, and the keyset seek.
 *
 * <p>Every pipeline-scoped read path composes exactly these — the row scan, group-input assembly, and the
 * Class-1 materialize probe and its {@code INSERT … SELECT}. They live in one place because the paths
 * must agree literally, not approximately: materialize's exactly-once <em>effect</em> rests on its write
 * running the same predicates its probe ran, and the group assembly's safety-lag must match the scan's or
 * a group would assemble from rows whose version the caller has not yet observed.
 *
 * <p>The tuple is projected as {@code toUnixTimestamp64Milli(version)} / {@code toString(identity)} so
 * encoding, decoding, and comparison are type-robust regardless of the ClickHouse client's Java mapping.
 *
 * <p><b>The partition floor.</b> That tuple compare is over expressions on a column outside the source's sort
 * key, so ClickHouse can use it for no index at all: on its own, every window statement reads the whole
 * source however small the window. Every lower bound built here — the seek, and the {@code from} half of the
 * caller window — therefore carries a second predicate when the source declares a {@code partition_column}:
 * {@code "<source>"."<partition>" >= (select min("<partition>") from "<source>" where "<version>" >= v
 * settings final=0)}. Three properties make it what it is:
 *
 * <ul>
 *   <li><b>It is exact — on the sources it is rendered for.</b> Every row the seek admits has a version at or
 *       after {@code v}, so its partition value is at or above the derived minimum. A row the floor removes has a
 *       version below {@code v}, but its part is pruned <em>before</em> {@code FINAL} collapses the key, so it
 *       must not have been the key's winner over a row the seek admits. That holds when every version of a key
 *       shares one partition value (partition-local) or when the engine collapses on the cursor's own version
 *       column; {@link ScanSourceMetadata} leaves {@code partitionColumn} null on any other source. No page,
 *       window, cursor or written row changes.
 *   <li><b>It is derived from the data, inside the statement.</b> Rows reach a source arbitrarily late
 *       relative to their partition value (re-ingest, backfill), so a fixed lag would skip rows; and a value
 *       computed in the service would let a Class-1 probe and its write disagree. A backfilled old row simply
 *       pulls the minimum back, widening the read toward a full scan, never past correctness.
 *   <li><b>The inner {@code final=0} is mandatory.</b> Inheriting the pipeline's {@code final=1} re-expands the
 *       inner read to the whole table.
 * </ul>
 *
 * <p>The obvious alternative — a plain {@code version >= v} — does not work under {@code final=1}: the
 * version column's skip index selects a few parts, then ClickHouse expands the selection back to every part
 * whose primary-key range intersects it ({@code use_skip_indexes_if_final_exact_mode}), which on
 * {@code dial_usage_log}'s {@code (project_id, deployment, …)} key is all of them. The partition column prunes
 * because ClickHouse keeps a per-part minmax on the argument of the partition expression.
 *
 * <p>The outer reference is qualified by the source because window statements join enrichments carrying a
 * same-named column ({@code dial_usage_log_payload.request_time}); the inner ones are not, its own
 * {@code FROM} being their only scope.
 */
public final class CursorPredicates {

    private CursorPredicates() {
    }

    /** The cursor tuple's version half, as epoch milliseconds. */
    public static Field<Long> versionExpr(CursorColumns cursorColumns) {
        return DSL.field("toUnixTimestamp64Milli({0})", Long.class, DSL.field(DSL.name(cursorColumns.version())));
    }

    /**
     * {@link #versionExpr} qualified with the source table, for a statement in which a joined enrichment carries a
     * column of the same name — an {@code upsert_by_key} input versioned by {@code _updated_at} joined to a
     * user-table enrichment, which has its own {@code _updated_at}. Unqualified, the name still binds to the source
     * there (ClickHouse resolves a bare name shared across a join to the left table, under either analyzer; checked
     * on 26.3), but only by that preference; a statement that qualifies one reference qualifies all of them — see
     * {@link #qualifiedSafetyLag} and {@link #qualifiedWindow}.
     */
    public static Field<Long> qualifiedVersionExpr(CursorColumns cursorColumns) {
        return DSL.field("toUnixTimestamp64Milli({0})", Long.class,
                DSL.field(DSL.name(cursorColumns.source(), cursorColumns.version())));
    }

    /** {@link #identityExpr} qualified with the source table, for the reason {@link #qualifiedVersionExpr} gives. */
    public static Field<String> qualifiedIdentityExpr(CursorColumns cursorColumns) {
        return DSL.field("toString({0})", String.class,
                DSL.field(DSL.name(cursorColumns.source(), cursorColumns.identity())));
    }

    /** The cursor tuple's tiebreaker half, as text. */
    public static Field<String> identityExpr(CursorColumns cursorColumns) {
        return DSL.field("toString({0})", String.class, DSL.field(DSL.name(cursorColumns.identity())));
    }

    /**
     * Excludes the still-mutating recent window ({@code version > now64(3) - lag}). Rows inside it may
     * still gain a newer version, so reading them would break the "never re-read a row" guarantee.
     */
    public static Condition safetyLag(CursorColumns cursorColumns, Duration lag) {
        return lagBelow(DSL.field(DSL.name(cursorColumns.version())), lag);
    }

    /** {@link #safetyLag} over the source-qualified version column, for the reason {@link #qualifiedVersionExpr} gives. */
    public static Condition qualifiedSafetyLag(CursorColumns cursorColumns, Duration lag) {
        return lagBelow(qualifiedVersion(cursorColumns), lag);
    }

    private static Condition lagBelow(Field<?> version, Duration lag) {
        return DSL.condition("{0} <= now64(3) - toIntervalMillisecond({1})", version, DSL.inline(lag.toMillis()));
    }

    /**
     * The optional half-open {@code (from, to]} predicate over the source's version column, so adjacent
     * windows neither overlap nor leave a gap. Compares as {@code DateTime64(3)} via
     * {@code fromUnixTimestamp64Milli} so the bound and the column are the same type — a bare
     * epoch-milliseconds integer would not compare correctly against a DateTime64.
     */
    public static List<Condition> window(CursorColumns cursorColumns, Instant from, Instant to) {
        return windowAndSeek(cursorColumns, from, to, null);
    }

    /**
     * The caller window and, when {@code cursorValues} is non-null, the keyset seek past it — the whole lower
     * and upper bound of a caller-driven read, with a <b>single</b> partition floor at the tighter of the two
     * lower bounds. Every row admitted is past both, so one floor at {@code max(from, cursor)} is as exact as
     * one per bound, and it saves the {@code from} floor's sub-select, which on a long window re-reads every
     * row since {@code from} on each page.
     *
     * @throws InvalidCursorException (400) if the cursor's version half is not a number
     */
    public static List<Condition> windowAndSeek(CursorColumns cursorColumns, Instant from, Instant to, List<String> cursorValues) {
        return boundsOver(cursorColumns, DSL.field(DSL.name(cursorColumns.version())), from, to, cursorValues);
    }

    /**
     * {@link #window} over the source-qualified version column, for the reason {@link #qualifiedVersionExpr} gives.
     * Takes no cursor: the only statement that needs it — the Class-1 preview sample — reads newest first and
     * never seeks.
     */
    public static List<Condition> qualifiedWindow(CursorColumns cursorColumns, Instant from, Instant to) {
        return boundsOver(cursorColumns, qualifiedVersion(cursorColumns), from, to, null);
    }

    private static Field<?> qualifiedVersion(CursorColumns cursorColumns) {
        return DSL.field(DSL.name(cursorColumns.source(), cursorColumns.version()));
    }

    private static List<Condition> boundsOver(CursorColumns cursorColumns, Field<?> version, Instant from, Instant to,
            List<String> cursorValues) {
        List<Condition> conditions = new ArrayList<>(4);
        Long floorAt = null;
        if (from != null) {
            conditions.add(DSL.condition(
                    "{0} > fromUnixTimestamp64Milli({1})", version, DSL.inline(from.toEpochMilli())));
            floorAt = from.toEpochMilli();
        }
        if (to != null) {
            conditions.add(DSL.condition(
                    "{0} <= fromUnixTimestamp64Milli({1})", version, DSL.inline(to.toEpochMilli())));
        }
        if (cursorValues != null) {
            final long cursorVersion = parseVersion(cursorValues);
            conditions.add(tupleSeek(cursorColumns, cursorVersion, cursorIdentity(cursorValues)));
            floorAt = floorAt == null ? cursorVersion : Math.max(floorAt, cursorVersion);
        }
        if (floorAt != null && cursorColumns.partitioned()) {
            conditions.add(partitionFloor(cursorColumns, floorAt));
        }
        return conditions;
    }

    /** An inverted or empty window is always a caller bug; failing loudly beats reporting "exhausted". */
    public static void requireOrderedWindow(Instant from, Instant to) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw new QueryValidationException("'from' must be strictly before 'to'");
        }
    }

    /**
     * The keyset seek past a position: a tuple compare on the projected cursor expressions, which can neither
     * skip nor repeat a row because the tuple is a unique total order — plus, on a partitioned source, the
     * partition floor that lets the read prune. Takes the position as typed values, for a server-owned cursor
     * stored in its own columns; a caller's opaque token is decoded by {@link #windowAndSeek}.
     */
    public static Condition seekPast(CursorColumns cursorColumns, long version, String identity) {
        final Condition seek = tupleSeek(cursorColumns, version, identity);
        return cursorColumns.partitioned() ? seek.and(partitionFloor(cursorColumns, version)) : seek;
    }

    private static Condition tupleSeek(CursorColumns cursorColumns, long version, String identity) {
        return DSL.condition("({0}, {1}) > ({2}, {3})",
                versionExpr(cursorColumns), identityExpr(cursorColumns),
                DSL.inline(version), DSL.inline(identity));
    }

    private static String cursorIdentity(List<String> cursorValues) {
        return cursorValues.size() > 1 ? cursorValues.get(1) : "";
    }

    /**
     * The partition floor for a lower bound at {@code versionMillis}: the source's partition column at or above
     * its smallest value among rows whose version is at or after the bound (see the class comment for why it
     * is exact, why it is derived in-statement, and why the inner read must not deduplicate).
     */
    private static Condition partitionFloor(CursorColumns cursorColumns, long versionMillis) {
        return DSL.condition(
                "{0} >= (select min({1}) from {2} where {3} >= fromUnixTimestamp64Milli({4}) settings final=0)",
                DSL.field(DSL.name(cursorColumns.source(), cursorColumns.partitionColumn())),
                DSL.field(DSL.name(cursorColumns.partitionColumn())),
                DSL.table(DSL.name(cursorColumns.source())),
                DSL.field(DSL.name(cursorColumns.version())),
                DSL.inline(versionMillis));
    }

    /**
     * The upper bound of a probed window: a tuple compare pinning a read to at most the end tuple the
     * probe chose. Pairing this with {@link #seekPast} is what makes a written range identical to the
     * probed one, so a retried or duplicated run re-executes exactly the same window — the property both
     * the Class-1 materialize path and the aggregate refresh rest their repeatability on.
     */
    public static Condition atMost(CursorColumns cursorColumns, long version, String identity) {
        return DSL.condition("({0}, {1}) <= ({2}, {3})",
                versionExpr(cursorColumns), identityExpr(cursorColumns),
                DSL.inline(version), DSL.inline(identity));
    }

    /**
     * Compares two cursor tuples in the order the cursor itself is defined by — version first, identity as
     * the tiebreaker — returning the usual negative / zero / positive.
     *
     * <p>The Java counterpart of {@link #atMost} and {@link #seekPast}, and it lives beside them so the two
     * cannot disagree about what "before" means. Needed because the enrichment clamp decides <em>in
     * Java</em> whether a probed window end already sits at or below a bound, and only then renders a
     * predicate: a comparison written at the call site would be one {@code compareTo} away from ordering by
     * version alone, which is wrong exactly when two rows share a version — the case the identity half of
     * the tuple exists for.
     */
    public static int compareCursors(long leftVersion, String leftIdentity, long rightVersion, String rightIdentity) {
        final int byVersion = Long.compare(leftVersion, rightVersion);
        return byVersion != 0
                ? byVersion
                : StringUtils.defaultString(leftIdentity).compareTo(StringUtils.defaultString(rightIdentity));
    }

    /** The version half of a decoded cursor tuple. */
    public static long parseVersion(List<String> cursorValues) {
        try {
            return Long.parseLong(cursorValues.getFirst());
        } catch (NumberFormatException ex) {
            throw new InvalidCursorException("cursor version is not a number");
        }
    }
}
