package com.epam.aidial.analytics.web.controller.query;

import com.epam.aidial.analytics.service.query.CatalogQueryService;
import com.epam.aidial.analytics.service.query.QueryFunctionCatalog;
import com.epam.aidial.analytics.service.query.sql.SqlFunctionCatalog;
import com.epam.aidial.analytics.web.dto.query.QueryEntityDto;
import com.epam.aidial.analytics.web.dto.query.QueryEntitySchemaDto;
import com.epam.aidial.analytics.web.dto.query.QueryFunctionDto;
import com.epam.aidial.analytics.web.dto.query.SqlFunctionCatalogDto;
import com.epam.aidial.analytics.web.security.AuthenticatedOnly;
import com.epam.aidial.analytics.web.security.Privilege;
import com.epam.aidial.analytics.web.security.RequiresPrivilege;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Schema discovery for the experimental structured query DSL (subject to change). Publishes which
 * entities can be queried (the catalog's materialized {@code source} tables) and the flat field names
 * valid in a structured query's {@code filter}/{@code select}/{@code sort} sections — the union of a
 * source's columns and its enrichments' exposed columns.
 */
@Slf4j
@RestController
@RequestMapping("/v1/queries")
@RequiredArgsConstructor
@Tag(
        name = "Structured Queries (experimental)",
        description = "Entity and flat-schema discovery for the structured query DSL. Experimental — may change.")
public class QuerySchemaController {

    private final CatalogQueryService catalog;
    private final QueryFunctionCatalog functionCatalog;
    private final SqlFunctionCatalog sqlFunctionCatalog;

    @GetMapping("/entities")
    @RequiresPrivilege(value = Privilege.TABLE_LIST, deferred = true)
    @Operation(
            summary = "List queryable entities",
            description = "Lists the entities available as the `entity` value of a structured query "
                    + "(the catalog's materialized source tables).")
    @ApiResponse(
            responseCode = "200",
            description = "Queryable entities",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = QueryEntityDto.class)))
    public List<QueryEntityDto> listEntities() {
        return catalog.listEntities();
    }

    @GetMapping("/functions")
    @AuthenticatedOnly
    @Operation(
            summary = "List supported query functions",
            description = "Lists the closed catalog of functions accepted in structured query `fn` expressions, "
                    + "with signatures, return types, distinct support, and argument constraints. These are the DSL's "
                    + "`fn` names: writing SQL, use `GET /v1/queries/sql-functions`, since some names here are not "
                    + "callable in SQL and some signatures are written differently there.")
    @ApiResponse(
            responseCode = "200",
            description = "Supported functions",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = QueryFunctionDto.class)))
    public List<QueryFunctionDto> listFunctions() {
        return functionCatalog.listFunctions();
    }

    @GetMapping("/sql-functions")
    @AuthenticatedOnly
    @Operation(
            summary = "List supported SQL functions",
            description = "Lists every function-like spelling the ad-hoc SQL surface accepts (execute-sql, validate-sql, "
                    + "translate-sql, Flight SQL) — functions, operators such as `+` and `||`, and constructs such as "
                    + "`CASE` — each with its SQL signature, aliases, the DSL function it lowers onto, and a runnable "
                    + "example statement. The predicate and statement grammar (comparisons, `IN`, joins, set "
                    + "operations) is not listed.")
    @ApiResponse(
            responseCode = "200",
            description = "Supported SQL functions",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = SqlFunctionCatalogDto.class)))
    public SqlFunctionCatalogDto listSqlFunctions() {
        return new SqlFunctionCatalogDto(sqlFunctionCatalog.listFunctions());
    }

    @GetMapping("/entities/schema/{name}")
    @RequiresPrivilege(value = Privilege.TABLE_GET, deferred = true)
    @Operation(
            summary = "Get an entity's flat schema",
            description = "Returns the flat schema of queryable fields (name, type, source): the source's "
                    + "columns plus every enrichment's exposed columns.")
    @ApiResponse(
            responseCode = "200",
            description = "Entity schema",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = QueryEntitySchemaDto.class)))
    @ApiResponse(responseCode = "404", description = "Unknown entity")
    public QueryEntitySchemaDto getBaseSchema(
            @Parameter(description = "Entity wire name", example = "dial_usage_log") @PathVariable String name) {
        return catalog.getBaseSchema(name);
    }
}
