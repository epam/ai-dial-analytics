package com.epam.aidial.analytics.service.pipeline.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One measure of an aggregate pipeline, stored inside the pipeline's {@code spec} JSONB: a single
 * aggregate function over a single input column, written to the target column {@link #name}.
 *
 * <p><b>One function over one column, deliberately (D-DECL-4).</b> There is no expression grammar
 * inside a measure, and that restriction is structural here rather than a rule to enforce. Per-row
 * arithmetic belongs in a derived input column; ratios and other expressions <em>over</em> measures
 * (e.g. {@code sum(cost)/sum(tokens)}) belong at query time over the stored <b>additive</b> measures,
 * because a ratio cannot be re-aggregated across buckets — storing one would produce a number that is
 * silently wrong as soon as two buckets are combined.
 *
 * <p><b>{@link #where} does not weaken that.</b> It is a row-<em>membership</em> predicate, not an
 * expression over the aggregated value: it chooses which of the group's rows the function reads, and the
 * function still reads one column of each. That distinction is what keeps the restriction above intact
 * while making the declarations it was blocking expressible — a chain cost that must sum only the rows
 * starting a chain, a turn count that must count only the rows starting a turn. Without it those measures
 * have no declarable form at all, because the pipeline's own {@code filter} is one predicate over the
 * whole declaration and narrowing it would narrow every other measure with it.
 *
 * <p>{@link #fn} is resolved against the aggregate group of the shared query function catalog, so the
 * measure vocabulary and the query DSL can never drift apart. Unknown properties are tolerated.
 */
@Getter
@Setter
@EqualsAndHashCode
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Measure {

    /** The target column this measure writes. */
    private String name;

    /** The aggregate function, from the shared catalog's aggregate group (e.g. {@code sum}). */
    private String fn;

    /** The input column aggregated; omitted only for {@code count} (row count). */
    private String column;

    /**
     * Optional row-membership predicate scoping this measure to a subset of its group's rows, in the same
     * bounded SQL-boolean grammar as the pipeline's {@code filter}. Absent means every row of the group.
     *
     * <p>A measure carrying one makes its own result nullable whatever its input column's nullability: a
     * group in which no row satisfies the predicate has no value to report, which is a NULL and not a zero.
     * {@code AggregateSpecValidator} therefore requires a nullable target column for it.
     */
    private String where;

    /**
     * Deduplicates the values of {@link #column} before {@link #fn} is applied, so the measure aggregates the
     * group's distinct values rather than its rows. Defaults to {@code false}, and a declaration that omits it
     * compiles exactly as it did before the quantifier existed.
     *
     * <p><b>A quantifier, not an expression.</b> Like {@link #where}, this does not weaken the
     * one-function-over-one-column rule above: {@code distinct} qualifies the operand the function reads, and
     * the function still reads one column. It composes with {@link #where} — the predicate chooses the rows,
     * this deduplicates the values on them — and the two are independent.
     *
     * <p><b>Why a quantifier and not a {@code count_distinct} function.</b> The shared catalog already carries
     * {@code distinct_supported} per aggregate and the query surface already accepts the quantifier for
     * {@code count}, {@code sum} and {@code avg}; a second entry per function would duplicate the catalog and
     * still leave the {@code sum}/{@code avg} forms unreachable from a measure. {@code AggregateSpecValidator}
     * resolves acceptability from that descriptor rather than from a list of names here, so the two surfaces
     * cannot disagree about the same function.
     *
     * <p><b>What this buys, and its one caveat.</b> It is the only way to count the <em>entities</em> in a
     * group whose grain is coarser than its input's row grain — which row of a group represents a distinct
     * value is a property of the group, so no {@link #where} can express it. The result is exact at the grain
     * it is stored at, because a refresh recomputes whole group keys rather than merging increments; but
     * unlike the additive measures around it, a distinct result must not be summed <em>across</em> stored rows,
     * which would double-count any value two groups share.
     */
    private boolean distinct;
}
