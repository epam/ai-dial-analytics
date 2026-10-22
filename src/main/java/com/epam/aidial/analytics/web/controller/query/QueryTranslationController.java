package com.epam.aidial.analytics.web.controller.query;

import com.epam.aidial.analytics.service.query.QueryTranslationService;
import com.epam.aidial.analytics.service.query.StructuredQueryService;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.sql.SqlQueryFrontEnd;
import com.epam.aidial.analytics.web.dto.query.SqlQueryRequestDto;
import com.epam.aidial.analytics.web.dto.query.SqlTranslationResponseDto;
import com.epam.aidial.analytics.web.dto.query.StructuredQueryTranslationResponseDto;
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
 * Translation-only counterparts of the execute pair: {@code /translate-sql} maps a SQL SELECT to the
 * structured query DSL (the exact translation {@code /execute-sql} would run), and {@code /translate}
 * renders a DSL document as external-dialect SQL (the subset {@code /execute-sql} accepts). Both
 * validate against the catalog with the execute endpoints' rules and never contact ClickHouse.
 */
@Slf4j
@RestController
@RequestMapping("/v1/queries")
@RequiredArgsConstructor
@Tag(
        name = "Structured Queries (experimental)",
        description = "Execute structured queries against analytics data. Experimental — may change.")
public class QueryTranslationController {

    private final SqlQueryFrontEnd sqlQueryFrontEnd;
    private final QueryTranslationService translationService;
    private final StructuredQueryService queryService;

    @PostMapping("/translate-sql")
    @RequiresPrivilege(value = Privilege.TABLE_GET, deferred = true)
    @Operation(
            summary = "Translate an ad-hoc SQL SELECT to the structured query DSL",
            description = "Translates a single read-only SQL SELECT statement to the structured query DSL without"
                    + " executing it — the exact translation `/v1/queries/execute-sql` would run, with the same"
                    + " governance: entity and field validation, the closed function catalog, and maximum limit 1000."
                    + " Contains matching maps 1:1: `LIKE '%text%'` → `co`/`nc` (case-sensitive) and"
                    + " `ILIKE '%text%'` → `ico`/`inc` (case-insensitive). `CASE` and window functions translate to the"
                    + " DSL's `case` and `window` expressions. A statement that joins, reads a derived table or CTE, or"
                    + " carries a subquery executes over `/v1/queries/execute-sql` but has no structured-query document"
                    + " and is answered with 400 here. The returned `query` document is accepted"
                    + " verbatim by `/v1/queries/execute`. ClickHouse is never contacted.")
    @ApiResponse(
            responseCode = "200",
            description = "The DSL translation",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = SqlTranslationResponseDto.class)))
    @ApiResponse(responseCode = "400", description = "SQL parse/validation failure or unsupported SQL construct")
    public SqlTranslationResponseDto translateSql(@Valid @RequestBody SqlQueryRequestDto request) {
        // TABLE_GET on both passes, not TABLE_READ: this endpoint resolves the statement against the
        // catalog model and never contacts ClickHouse, so it discloses schema rather than data. Both calls
        // have to carry the bar — validating at TABLE_READ would reject, as an unknown entity, exactly the
        // schema-only caller the lower bar exists for.
        final StructuredQuery translated =
                sqlQueryFrontEnd.translate(request.sql(), Privilege.TABLE_GET).query();
        // The front end validates the SQL against the catalog, but the row governance lives in the
        // builder — so without this pass the endpoint returned a document `/v1/queries/execute` then
        // rejected (most visibly one carrying a LIMIT above the maximum), contradicting this endpoint's
        // own contract that what it emits is executable as returned — by any caller entitled to execute
        // it. The catalog bar here is TABLE_GET, so a caller holding only that gets a document it cannot
        // itself run: the emitted DSL is well-formed and within governance, and /v1/queries/execute
        // refuses it at the higher bar. That is the lower bar working, not a contradiction of it.
        queryService.validate(translated, Privilege.TABLE_GET);
        return new SqlTranslationResponseDto(translated);
    }

    @PostMapping("/translate")
    @RequiresPrivilege(value = Privilege.TABLE_GET, deferred = true)
    @Operation(
            summary = "Translate a structured query to SQL",
            description = "Validates the structured query with the same rules as `/v1/queries/execute` (entity and"
                    + " field validation, closed function catalog, offset paging with maximum limit 1000) and,"
                    + " without executing it, renders it as a single SELECT statement in the SQL subset accepted by"
                    + " `/v1/queries/execute-sql`. Contains operators render exactly: `co`/`nc` → `LIKE`/`NOT LIKE`"
                    + " (case-sensitive), `ico`/`inc` → `ILIKE`/`NOT ILIKE` (case-insensitive). DSL constructs the"
                    + " SQL subset cannot express — a contains value containing `%` or `_`, a null literal outside"
                    + " `eq`/`ne`, `include_total`, `having` on a row-mode query, `distinct` on an aggregate-mode"
                    + " query, or an empty `select` on an entity"
                    + " whose source columns include an object/array-typed field — are rejected with HTTP 400."
                    + " ClickHouse is never contacted.")
    @ApiResponse(
            responseCode = "200",
            description = "The SQL translation",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = StructuredQueryTranslationResponseDto.class)))
    @ApiResponse(responseCode = "400", description = "Unknown entity/field/function, unsupported query feature, or DSL construct inexpressible in SQL")
    public StructuredQueryTranslationResponseDto translate(@Valid @RequestBody StructuredQuery query) {
        return new StructuredQueryTranslationResponseDto(translationService.toSql(query));
    }
}
