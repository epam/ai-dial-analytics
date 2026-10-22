package com.epam.aidial.analytics.web.controller.query;

import com.epam.aidial.analytics.service.query.repository.QueryResultPage;
import com.epam.aidial.analytics.web.dto.query.SqlQueryRequestDto;
import com.epam.aidial.analytics.web.dto.query.StructuredQueryResultDto;
import com.epam.aidial.analytics.web.security.Privilege;
import com.epam.aidial.analytics.web.security.RequiresPrivilege;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Executes ad-hoc SQL over HTTP: the same SQL subset the Flight SQL endpoint accepts, translated to
 * a structured query and run through the same pipeline as {@link StructuredQueryController}, with the
 * same response envelope. {@code totalCount} is never populated on this path (SQL has no total-count
 * opt-in), so the shared NON_NULL serialization omits the key from the JSON.
 */
@Slf4j
@RestController
@RequestMapping("/v1/queries")
@RequiredArgsConstructor
@Tag(
        name = "Structured Queries (experimental)",
        description = "Execute structured queries against analytics data. Experimental — may change.")
public class SqlQueryController {

    private final SqlStatementRunner sqlStatementRunner;

    @PostMapping("/execute-sql")
    @RequiresPrivilege(value = Privilege.TABLE_READ, deferred = true)
    @Operation(
            summary = "Execute an ad-hoc SQL SELECT",
            description = "Translates a single read-only SQL SELECT statement to the structured query DSL and runs"
                    + " it through the same pipeline as `/v1/queries/execute`, with the same governance: entity and"
                    + " field validation, the closed function catalog, default limit 100, and maximum limit 1000"
                    + " on the outermost statement (a higher LIMIT is rejected, never clamped). Accepted: joins"
                    + " (INNER/LEFT/RIGHT/FULL/CROSS, with at least one equality in ON), non-recursive CTEs, derived"
                    + " tables of any accepted shape, uncorrelated IN/EXISTS/scalar subqueries, CASE, arithmetic"
                    + " operators and window functions (row_number/rank/dense_rank and count/sum/avg/min/max OVER),"
                    + " and set operations. Set operations follow ClickHouse rather than the SQL standard, so the"
                    + " same text means the same thing here and against the database: a bare INTERSECT or EXCEPT"
                    + " means ALL, a bare UNION is rejected (ClickHouse has no default union mode), MINUS is"
                    + " rejected (ClickHouse has no such keyword), and a trailing ORDER BY/LIMIT applies to the"
                    + " LAST BRANCH — wrap the operation (`SELECT * FROM (<set operation>) ORDER BY ...`) to order"
                    + " or limit the combined result."
                    + " Rejected before execution: WITH RECURSIVE, correlated subqueries,"
                    + " general CAST, ROLLUP/CUBE and functions outside the catalog. Outer joins yield NULL for an"
                    + " unmatched side. `LIKE` and `ILIKE` are"
                    + " supported only for `'%text%'` patterns, each with standard semantics: `LIKE` matches"
                    + " case-sensitively, `ILIKE` case-insensitively."
                    + " Returns the projected rows; `totalCount` is never populated (the key is omitted)."
                    + "\n\nA statement whose tables are all in `INFORMATION_SCHEMA` (`TABLES`, `COLUMNS`,"
                    + " `SCHEMATA`) is answered from ADAS's own catalog instead, in the same response envelope."
                    + " Because it never reaches ClickHouse, none of the governance above applies to it: no"
                    + " default limit, no maximum limit, and a wider expression surface (arithmetic and CAST"
                    + " included) — its size is bounded by the catalog. It cannot be combined with a queryable"
                    + " entity, and a construct the in-process evaluator cannot answer correctly (a join, a"
                    + " subquery, a window function, a DISTINCT aggregate) is rejected with 400.")
    @ApiResponse(
            responseCode = "200",
            description = "Query result",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = StructuredQueryResultDto.class)))
    @ApiResponse(responseCode = "400", description = "SQL parse/validation failure or unsupported SQL construct")
    @ApiResponse(responseCode = "422",
            description = "Query exceeded the configured execution budget (`query_budget_exceeded`): the wall-clock"
                    + " or memory ceiling in `query.budget.*`. Deterministic — narrow the query rather than retrying.")
    public StructuredQueryResultDto executeSql(@Valid @RequestBody SqlQueryRequestDto request) {
        final QueryResultPage page = sqlStatementRunner.run(request.sql());
        return new StructuredQueryResultDto(page.rows(), page.totalCount());
    }
}
