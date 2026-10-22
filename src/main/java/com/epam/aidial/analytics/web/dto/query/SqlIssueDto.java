package com.epam.aidial.analytics.web.dto.query;

import com.epam.aidial.analytics.service.query.sql.SqlIssue;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One entry of {@code /v1/queries/validate-sql}'s {@code issues} or {@code warnings} list.
 *
 * <p>{@code code} is the stable machine-readable classification; {@code message} the underlying
 * diagnosis, carried verbatim. {@code line}/{@code column} are 1-based and absent where the diagnosis
 * has no position, {@code remedy} absent where the classification implies no concrete fix.
 */
@Schema(description = "One problem found in the statement, or one advisory note about a valid one")
public record SqlIssueDto(
        @Schema(description = "Stable machine-readable classification", example = "unknown_column")
        String code,

        @Schema(description = "The underlying diagnosis, verbatim")
        String message,

        @Schema(description = "1-based line in the submitted statement, absent when the diagnosis has no position")
        Integer line,

        @Schema(description = "1-based column in the submitted statement, absent when the diagnosis has no position")
        Integer column,

        @Schema(description = "How to fix it, absent when the classification implies no concrete fix",
                example = "did you mean 'event_kind'?")
        String remedy) {

    public static SqlIssueDto of(SqlIssue issue) {
        return new SqlIssueDto(issue.code(), issue.message(), issue.line(), issue.column(), issue.remedy());
    }
}
