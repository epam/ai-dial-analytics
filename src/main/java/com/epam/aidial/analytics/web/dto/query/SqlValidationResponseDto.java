package com.epam.aidial.analytics.web.dto.query;

import com.epam.aidial.analytics.service.query.sql.SqlValidation;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * The verdict on one statement submitted to {@code POST /v1/queries/validate-sql}.
 *
 * <p>{@code valid} answers only whether {@code POST /v1/queries/execute-sql} would accept the statement.
 * {@code warnings} never bear on it: a warning describes a statement that passes every check and can
 * still be refused at execution by the query budget, which ClickHouse enforces as the statement runs.
 */
@Schema(description = "Whether execute-sql would accept the statement, and what it would cost")
public record SqlValidationResponseDto(
        @Schema(description = "True when POST /v1/queries/execute-sql would accept the statement")
        boolean valid,

        @JsonProperty("effective_limit")
        @Schema(description = "The outermost row ceiling execution would apply — the statement's own LIMIT,"
                + " or the default where it carries none. Absent for an invalid statement and for a"
                + " catalog-metadata statement, which no row limit governs.")
        Integer effectiveLimit,

        @Schema(description = "Why the statement would be rejected; empty when it is valid. The first entry"
                + " is the rejection execute-sql itself would return; any further entries are additional"
                + " unresolvable identifiers found so they need not be discovered one round trip at a time.")
        List<SqlIssueDto> issues,

        @Schema(description = "Advisory notes about a valid statement — shapes that pass every check and can"
                + " still be refused at execution by the query budget. Heuristics, never verdicts.")
        List<SqlIssueDto> warnings) {

    public static SqlValidationResponseDto of(SqlValidation validation) {
        return new SqlValidationResponseDto(
                validation.valid(),
                validation.effectiveLimit(),
                validation.issues().stream().map(SqlIssueDto::of).toList(),
                validation.warnings().stream().map(SqlIssueDto::of).toList());
    }
}
