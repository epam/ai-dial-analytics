package com.epam.aidial.analytics.service.pipeline.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * The aggregate declaration stored as a pipeline's {@code spec} JSONB: what to group by, what to measure,
 * and how fresh to keep it. The persisted form of an aggregate query, in the engine-agnostic vocabulary
 * of the design note — it names no ClickHouse function and no engine construct, so the same declaration
 * could target another engine behind the adapter seam.
 *
 * <p>The declaration is compiled (not interpreted) at execution: it is lowered to a structured query in
 * aggregate mode and rendered through the one SQL builder the service already has, so the measure
 * vocabulary and the query DSL cannot drift apart.
 *
 * <p>Unknown properties are tolerated so a spec written by a later version stays readable.
 *
 * <p>One of the two {@link PipelineSpec} implementations; which one a stored {@code spec} column holds is
 * decided by the pipeline's {@code kind}, never by anything inside the JSON.
 */
@Getter
@Setter
@EqualsAndHashCode
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public final class AggregateSpec implements PipelineSpec {

    /**
     * The group-by tuple, in order. The ordered list of each part's output name is validated to equal the
     * target's {@code ordering_key}, which is what keeps the rollup's grain from being declared twice.
     */
    @JsonProperty("group_by")
    private List<GroupKey> groupBy = new ArrayList<>();

    /** The measures, each one function over one column; see {@link Measure}. */
    private List<Measure> measures = new ArrayList<>();

    /**
     * The declaration as one {@code INSERT INTO <target> (<columns>) SELECT …} statement, stored verbatim —
     * the alternative to {@link #groupBy} + {@link #measures}, never alongside them. Null for a form
     * declaration, and then omitted from the stored JSON, so a form spec reads and writes exactly as before.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String sql;

    /** Declared freshness intent; only {@link FreshnessMode#PERIODIC} is implemented. */
    private Freshness freshness = new Freshness();

    /**
     * The output (target) column names of the group-by tuple, in declared order — empty for a declaration
     * that has none yet, which a disabled draft may be.
     */
    public List<String> groupKeyOutputNames() {
        return groupBy == null ? List.of() : groupBy.stream().map(GroupKey::outputName).toList();
    }

    /** Freshness intent wrapper — an object so an interval can be added later without breaking clients. */
    @Getter
    @Setter
    @EqualsAndHashCode
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Freshness {
        private FreshnessMode mode = FreshnessMode.PERIODIC;
    }
}
