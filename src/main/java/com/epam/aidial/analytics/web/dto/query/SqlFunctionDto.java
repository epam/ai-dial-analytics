package com.epam.aidial.analytics.web.dto.query;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * One spelling the ad-hoc SQL surface accepts, as listed by {@code GET /v1/queries/sql-functions}. A SQL
 * entry names a <em>spelling</em>, not a DSL function: one DSL function may have several ({@code modulo} is
 * {@code %} and {@code mod}), and some spellings lower onto no catalog function at all ({@code coalesce},
 * {@code case}), which is why {@code dsl_function} may be null.
 */
public record SqlFunctionDto(
        @Schema(description = "Primary SQL spelling, lowercase; matched case-insensitively", example = "length")
        String name,

        @Schema(description = "How the entry is written: a function call, an operator, or a keyword construct",
                example = "function")
        SqlFunctionForm form,

        @Schema(description = "Other spellings accepted with the same meaning, lowercase; possibly empty",
                example = "[\"char_length\", \"character_length\"]")
        List<String> aliases,

        @Schema(description = "Function group, as in the DSL catalog", example = "scalar")
        QueryFunctionGroup group,

        @Schema(description = "The spelling as written in SQL",
                example = "percentile_cont(fraction) WITHIN GROUP (ORDER BY value)")
        String signature,

        @Schema(description = "Declared return type, as in the DSL catalog", example = "integer")
        QueryFunctionReturnType returns,

        @Schema(description = "Whether a DISTINCT quantifier is meaningful for this entry", example = "false")
        @JsonProperty("distinct_supported") boolean distinctSupported,

        @Schema(description = "Whether two evaluations over the same rows are guaranteed to agree; false for now()",
                example = "true")
        boolean deterministic,

        @Schema(description = "Short human-readable description, with the SQL-specific rules")
        String description,

        // Serialized as null rather than omitted: "lowers onto no catalog function" is an answer a client
        // joins on, and an absent member would read as a service too old to say.
        @Schema(description = "The DSL catalog function this spelling lowers onto (GET /v1/queries/functions), "
                + "or null where the target is not a catalog function", example = "length", nullable = true)
        @JsonProperty("dsl_function") @JsonInclude(JsonInclude.Include.ALWAYS) String dslFunction,

        @Schema(description = "One complete SELECT statement using the entry, accepted by the SQL surface",
                example = "SELECT deployment, length(deployment) AS n FROM dial_usage_log LIMIT 10")
        String example,

        @Schema(description = "Ordered argument list, as in the DSL catalog")
        List<QueryFunctionArgDto> args) {}
