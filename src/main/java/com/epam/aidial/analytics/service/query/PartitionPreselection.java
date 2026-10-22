package com.epam.aidial.analytics.service.query;

import com.epam.aidial.analytics.service.table.PartitionGranularity;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.impl.DSL;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The partition pre-selection: a <b>lookup</b> conjunct on an aligned enrichment names the partitions every row it
 * admits lives in, so a statement selected by an enrichment value can be bounded in time without the caller
 * supplying a time bound.
 *
 * <p>For a lookup {@code C} over the columns of an aligned enrichment {@code E}, the statement's own {@code WHERE}
 * gains
 * <pre>
 * partitionExpr(source.p) IN PS
 * PS = (SELECT DISTINCT partitionExpr(E.p) FROM E WHERE C [AND B(E.p)] SETTINGS final = 0)
 * </pre>
 * where {@code p} is the alignment column and {@code B} the statement's own source-only bounds on {@code p}, rendered
 * on {@code E.p} instead. A partition set rather than a time range: a lower bound reads a long session's whole span
 * where the set reads only its active days, and the set is capped by the partition count (366 a year), where a set
 * of exact values grows with the rows {@code C} matches.
 *
 * <p><b>Exact.</b> A row the statement admits matched the enrichment row written from it, which satisfies {@code C}
 * and carries the row's own partition value — the tuple join compares it — so that row also satisfies {@code B} on
 * {@code E.p}, and the row's partition is in {@code PS}. Reading {@code PS} with {@code final = 0} only adds stale
 * versions, which can only widen it — and is what lets a skip index on the lookup column serve it, because a
 * {@code FINAL} read expands every index hit back through the primary key.
 *
 * <p><b>Why the bounds.</b> Without them {@code PS} reads the lookup column over every partition {@code E} holds, so a
 * statement that already bounds its time — {@code request_time >= now() - 1 day} — would pay a scan of all history to
 * name a day it had named itself. With them {@code PS} reads only the partitions the statement can reach.
 *
 * <p><b>The source only, not {@code E}'s derived table.</b> ClickHouse evaluates an uncorrelated subquery once per
 * scope, so a second copy of {@code PS} inside the derived table is a second read of the lookup column. It prunes
 * nothing the first does not: the analyzer carries {@code partitionExpr(source.p) IN PS} through the tuple join's
 * {@code source.p = E.p} into the derived table (measured on 26.3 and 26.8 with
 * {@code do_not_merge_across_partitions_select_final = 1}, which every aligned read carries), and the same holds for
 * every other aligned enrichment the statement joins. A join without an aligned partition pair has no such
 * equality; a caller hands it the restriction as a key predicate instead (see {@link EnrichmentJoins}).
 *
 * <p><b>Lookups only, and the caller decides what is one.</b> {@code C} must be false on an unmatched row and must
 * not hold for a column's type default, or the rewrite drops source rows the statement legitimately admits through
 * the outer join; and it must be selective, or {@code PS} costs a scan to name nearly every partition. Each caller
 * passes only an equality or an {@code IN} over non-default values, rendered on the bare column — a wrapper such as
 * {@code toString(col)} hides the column from the index.
 */
public final class PartitionPreselection {

    private PartitionPreselection() {
    }

    /**
     * The restriction a lookup adds to the statement's {@code WHERE} — {@code partitionExpr(source.p) IN PS} — or empty
     * when {@code join} is not partition-aligned or its granularity is unknown.
     *
     * @param join   the aligned enrichment {@code lookup} reads
     * @param lookup a condition over {@code join}'s enrichment columns alone, two-part qualified
     * @param bounds conditions over {@code join}'s enrichment partition column alone, each the image of a source-only
     *     conjunct of the statement on the source's partition column; empty for none
     */
    public static Optional<Condition> of(EnrichmentJoin join, Condition lookup, List<Condition> bounds) {
        if (!join.partitionAligned() || join.partitionGranularity() == null || lookup == null) {
            return Optional.empty();
        }
        final Field<Object> enrichmentPartition = partitionExpr(join.enrichmentPartition(), join.partitionGranularity());
        final Field<Object> sourcePartition = partitionExpr(join.sourcePartition(), join.partitionGranularity());
        final List<Condition> where = new ArrayList<>(1 + bounds.size());
        where.add(lookup);
        where.addAll(bounds);
        // Plain SQL rather than a jOOQ subquery: jOOQ cannot render a subquery's SETTINGS clause.
        final Field<Object> partitions = DSL.field("(SELECT DISTINCT {0} FROM {1} WHERE {2} SETTINGS final = 0)",
                enrichmentPartition, join.table(), DSL.and(where));
        return Optional.of(DSL.condition("{0} IN {1}", sourcePartition, partitions));
    }

    private static Field<Object> partitionExpr(Field<?> column, PartitionGranularity granularity) {
        final String fn = switch (granularity) {
            case DAY -> "toYYYYMMDD";
            case MONTH -> "toYYYYMM";
            case YEAR -> "toYear";
        };
        return DSL.field(fn + "({0})", column);
    }
}
