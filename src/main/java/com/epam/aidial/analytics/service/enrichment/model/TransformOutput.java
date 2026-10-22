package com.epam.aidial.analytics.service.enrichment.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * One entry of a transform's {@code outputs} — the single declaration of what the transform produces,
 * keyed by the target column that receives it. Stored inside the {@code outputs} JSONB column as an
 * <b>ordered array</b> of these, replacing the {@code input_vars} + {@code response_schema} +
 * {@code output_vars} trio a version registered before this shape carries.
 *
 * <p><b>Why an array and not an object.</b> {@code outputs} is a map on the wire, which is the right
 * authoring shape — most outputs are one key and one string. But its order is load-bearing:
 * it is the composed response schema's property order, and under {@code strict: true} that is the model's
 * generation order, so a field whose prose tells it to derive itself from its siblings must be declarable
 * after them. A {@code jsonb} object cannot hold order — Postgres normalises an object's keys to
 * length-then-bytes — so a stored object silently reorders generation, and a field can end up generated
 * ahead of the fields it derives itself from. An array
 * inside {@code jsonb} keeps its order, so the wire map is parsed into a {@link java.util.LinkedHashMap}
 * and stored as this list.
 *
 * <p>Which members are populated depends on the transform's {@code type}, and the two are disjoint:
 *
 * <ul>
 *   <li>{@code LLM} — {@link #prose} (what the model is told about the field, and the composed schema's
 *       {@code description}), plus <em>at most one</em> of {@link #values} (a closed domain for a field
 *       whose target column cannot carry one) or {@link #jsonata} (a transform applied to the model's
 *       field before it reaches the column).
 *   <li>{@code SQL} — {@link #sql} alone: a scalar expression over the read source's declared columns,
 *       validated at <em>pipeline</em> registration where a source is in hand.
 * </ul>
 *
 * <p>There is deliberately <b>no logical type</b>. For an {@code llm} output the type comes from the
 * target column; for a {@code sql} output it is what the SQL front end infers for the expression, checked
 * directly against that column. The token that used to sit between the two carried no information either
 * end did not already carry — see {@code EnrichmentTypeMapping#isCompatible}, which is compatibility
 * rather than equality, so the column's type was never the variable's type anyway.
 *
 * <p>Unknown properties are tolerated: this is a persisted shape, read by a mapper with
 * {@code FAIL_ON_UNKNOWN_PROPERTIES} disabled, so a row written by a later version stays readable. The
 * <em>request</em> shape is strict — see {@code OutputSpec}.
 */
@Getter
@Setter
@EqualsAndHashCode
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TransformOutput {

    /** The output's name, which is also the target column that receives it. */
    private String name;

    /** What the model is told about this field; the composed schema's {@code description}. LLM only. */
    private String prose;

    /**
     * A closed domain for an output whose target column cannot carry one — a {@code string} column whose
     * value set is expected to move, so {@code enum_values} would have to be dropped and re-added. Refused
     * when the column already declares {@code enum_values}: a field has exactly one authority for its
     * domain, chosen by which of the two can hold it. Buys schema enforcement only — a {@code string}
     * column accepts anything at insert. LLM only.
     */
    private List<String> values;

    /**
     * A transform applied to the model's field before it reaches the column. Absent already means a direct
     * field lookup by name, so the identity expression is refused at registration. The one live use is a
     * mandatory sentinel mapped to NULL. LLM only.
     */
    private String jsonata;

    /** A scalar expression over the read source's declared columns. SQL only. */
    private String sql;
}
