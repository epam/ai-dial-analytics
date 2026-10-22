package com.epam.aidial.analytics.web.controller.query;

import com.epam.aidial.analytics.service.query.StructuredQueryService;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.repository.QueryResultPage;
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
 * Executes structured queries for the experimental query DSL (subject to change). A single
 * entity-agnostic endpoint: the {@code entity} in the request body selects which backing table runs
 * the query. Discover valid entities and field names via the schema endpoints on
 * {@link QuerySchemaController}.
 */
@Slf4j
@RestController
@RequestMapping("/v1/queries")
@RequiredArgsConstructor
@Tag(
        name = "Structured Queries (experimental)",
        description = "Execute structured queries against analytics data. Experimental — may change.")
public class StructuredQueryController {

    private final StructuredQueryService queryService;

    @PostMapping("/execute")
    @RequiresPrivilege(value = Privilege.TABLE_READ, deferred = true)
    @Operation(
            summary = "Execute a structured query",
            description = "Translates the structured query to ClickHouse SQL and runs it against the entity named"
                    + " in `entity`. Returns the projected rows and, for row-mode offset paging with"
                    + " `include_total`, the total match count. Unknown entities, fields, functions, or"
                    + " unsupported features are rejected with HTTP 400.")
    @ApiResponse(
            responseCode = "200",
            description = "Query result",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = StructuredQueryResultDto.class)))
    @ApiResponse(responseCode = "400", description = "Unknown entity/field/function or unsupported query feature")
    @ApiResponse(responseCode = "422",
            description = "Query exceeded the configured execution budget (`query_budget_exceeded`): the wall-clock"
                    + " or memory ceiling in `query.budget.*`. Deterministic — narrow the query rather than retrying.")
    public StructuredQueryResultDto execute(@Valid @RequestBody StructuredQuery query) {
        final QueryResultPage page = queryService.execute(query);
        return new StructuredQueryResultDto(page.rows(), page.totalCount());
    }
}
