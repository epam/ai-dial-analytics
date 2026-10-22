package com.epam.aidial.analytics.web.controller.query;

import com.epam.aidial.analytics.service.query.sql.SqlValidationService;
import com.epam.aidial.analytics.web.dto.query.SqlQueryRequestDto;
import com.epam.aidial.analytics.web.dto.query.SqlValidationResponseDto;
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
 * Reports whether {@link SqlQueryController}'s endpoint would accept a statement, without executing it.
 *
 * <p>Kept apart from the execute and translate controllers because it is neither: it runs the execute
 * path's checks but returns a verdict instead of rows, and — unlike {@code /translate-sql} — it covers
 * the whole accepted surface, composite statements included.
 *
 * <p><b>An invalid statement is a 200, not a 400.</b> The endpoint's product is the verdict, so
 * "this statement is wrong, here is why" is a successful answer to the question asked. This is a
 * deliberate departure from the service-wide rule that a caller error is an {@code ErrorView}: that rule
 * exists so a failure is never mistaken for a result, and here the failure <em>is</em> the result. A
 * malformed request — a missing or blank {@code sql} — is still a 400, because that is a question the
 * endpoint cannot answer rather than an answer the caller will not like.
 */
@Slf4j
@RestController
@RequestMapping("/v1/queries")
@RequiredArgsConstructor
@Tag(
        name = "Structured Queries (experimental)",
        description = "Execute structured queries against analytics data. Experimental — may change.")
public class SqlValidationController {

    private final SqlValidationService validationService;

    @PostMapping("/validate-sql")
    @RequiresPrivilege(value = Privilege.TABLE_GET, deferred = true)
    @Operation(
            summary = "Check whether an ad-hoc SQL SELECT would be accepted, without executing it",
            description = "Runs every check `/v1/queries/execute-sql` runs except the ClickHouse round trip:"
                    + " the statement screen, parse, star expansion, entity and field validation, the closed"
                    + " function catalog, DSL or composite translation, and the row governance (default limit"
                    + " 100, maximum 1000). ClickHouse is never contacted, so this is safe to call repeatedly"
                    + " while composing a statement.\n\n"
                    + "The response is always `200` for a well-formed request; `valid` reports the statement."
                    + " `valid: true` does not promise execution succeeds, for two reasons. The query budget"
                    + " (`query.budget.*`) is enforced by ClickHouse as the statement runs, so a breach is"
                    + " only observable then. And this endpoint resolves the catalog at `TABLE_GET` while"
                    + " execute-sql resolves it at `TABLE_READ`, so a caller entitled to a table's schema"
                    + " but not its rows is answered `valid: true` here and `400 entity not queryable`"
                    + " there — deliberately, since composing a statement discloses schema rather than"
                    + " data, but it means `valid` answers whether the shape would be accepted, not"
                    + " whether this caller may run it. `warnings`"
                    + " carries advisory notes for the shapes that pass every check and still tend to breach"
                    + " it, and for the default limit being applied; warnings never affect `valid`, and they"
                    + " are heuristics — a composite statement receives only the limit note, because its"
                    + " blocks reference relation columns rather than one entity's fields.\n\n"
                    + "`issues[0]` is the rejection execute-sql itself would return, classified with a stable"
                    + " `code` and, where one applies, a `remedy`. Further entries are additional"
                    + " unresolvable identifiers, so a statement misspelling several names need not be fixed"
                    + " one round trip at a time; an unrecognised diagnosis is reported as `invalid_sql` with"
                    + " its message intact rather than guessed at.\n\n"
                    + "Column visibility is the caller's own: a `sensitive` column is absent from a"
                    + " non-entitled caller's catalog model, so naming one is reported as an unresolvable"
                    + " column — the same answer execution gives, and deliberately so.")
    @ApiResponse(
            responseCode = "200",
            description = "The verdict — for a valid statement and an invalid one alike",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = SqlValidationResponseDto.class)))
    @ApiResponse(responseCode = "400",
            description = "Malformed request: a missing, null or blank `sql`, or an unreadable JSON body."
                    + " An invalid *statement* is a 200 with `valid: false`.")
    public SqlValidationResponseDto validateSql(@Valid @RequestBody SqlQueryRequestDto request) {
        return SqlValidationResponseDto.of(validationService.validate(request.sql()));
    }
}
