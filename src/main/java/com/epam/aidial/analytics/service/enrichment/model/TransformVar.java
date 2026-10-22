package com.epam.aidial.analytics.service.enrichment.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A declared transform variable, stored inside the {@code input_vars}/{@code output_vars} JSONB
 * lists. Input vars name the values the request template references; output vars additionally carry a
 * logical type the runtime write must satisfy plus <em>one</em> way of producing the value, depending
 * on the transform's type: {@code jsonata} over the model response for {@code LLM}, or {@code sql} —
 * a scalar expression over the source's declared columns — for {@code SQL}. ADAS validates the type
 * and parses the JSONata (parse-only), and validates the SQL expression, when the pipeline declaring it
 * is written, where the source is known; runtime JSONata evaluation is the runner's.
 * Unknown properties are tolerated so the runner may carry extra fields.
 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TransformVar {

    private String name;

    /** Logical type of the variable (e.g. {@code string}, {@code long}, {@code boolean}). */
    private String type;

    /** JSONata expression over the model response (LLM output vars only); null otherwise. */
    private String jsonata;

    /**
     * ClickHouse scalar expression over the source's declared columns (SQL output vars only), the
     * {@code type=SQL} sibling of {@link #jsonata}. Validated at pipeline registration under the same
     * bounded Calcite grammar as a pipeline filter, then rendered into the materialize {@code INSERT …
     * SELECT}; the runner never interprets it.
     */
    private String sql;
}
