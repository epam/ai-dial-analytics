package com.epam.aidial.analytics.web.controller.query;

import com.epam.aidial.analytics.service.query.StructuredQueryService;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.repository.QueryResultPage;
import com.epam.aidial.analytics.web.dto.query.SqlQueryRequestDto;
import com.epam.aidial.analytics.web.security.Privilege;
import com.epam.aidial.analytics.web.security.RequiresPrivilege;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * The CSV response form of {@link StructuredQueryController} and {@link SqlQueryController}: the same
 * request bodies, run through the same service calls — so the same governance, limits and errors — with
 * the result written as an RFC 4180 attachment by {@link CsvResultWriter} instead of the JSON envelope.
 *
 * <p>The response's {@code Content-Type} is set explicitly, which bypasses content negotiation: the CSV is
 * returned whatever the request's {@code Accept} says. A failure still leaves as the {@code ErrorView} JSON
 * of the matching {@code DefaultExceptionHandler} method.
 */
@RestController
@RequiredArgsConstructor
@Tag(
        name = "Structured Queries (experimental)",
        description = "Execute structured queries against analytics data. Experimental — may change.")
public class QueryCsvController {

    static final String EXECUTE_CSV_PATH = "/v1/queries/execute-csv";
    static final String EXECUTE_SQL_CSV_PATH = "/v1/queries/execute-sql-csv";
    static final MediaType TEXT_CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);
    static final String TOTAL_COUNT_HEADER = "X-Total-Count";

    private static final String CONTENT_DISPOSITION =
            ContentDisposition.attachment().filename("result.csv").build().toString();

    private final StructuredQueryService queryService;
    private final SqlStatementRunner sqlStatementRunner;
    private final CsvResultWriter csvResultWriter;

    @PostMapping(EXECUTE_CSV_PATH)
    @RequiresPrivilege(value = Privilege.TABLE_READ, deferred = true)
    @Operation(
            summary = "Execute a structured query, returning CSV",
            description = "Runs the structured query exactly as `/v1/queries/execute` does — same request body,"
                    + " validation, default limit 100 and maximum limit 1000 (a higher limit is rejected, never"
                    + " clamped), budget and errors — and returns the result as an RFC 4180 CSV attachment: UTF-8"
                    + " without a BOM, CRLF line ends, a header row that is present even when no row matches."
                    + " A null cell is an empty field, the empty string is `\"\"`, arrays and objects are compact"
                    + " JSON text, and nothing is escaped for spreadsheets. `totalCount` (offset paging with"
                    + " `include_total`) is returned in the `X-Total-Count` header. Errors are `ErrorView` JSON.")
    @ApiResponse(
            responseCode = "200",
            description = "Query result as CSV",
            headers = @Header(name = TOTAL_COUNT_HEADER,
                    description = "Total matching rows; only for row-mode offset paging with include_total=true",
                    schema = @Schema(type = "integer")),
            content = @Content(mediaType = "text/csv", schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "400", description = "Unknown entity/field/function or unsupported query feature")
    @ApiResponse(responseCode = "422",
            description = "Query exceeded the configured execution budget (`query_budget_exceeded`).")
    public ResponseEntity<byte[]> executeCsv(@Valid @RequestBody StructuredQuery query) {
        return csv(queryService.execute(query));
    }

    @PostMapping(EXECUTE_SQL_CSV_PATH)
    @RequiresPrivilege(value = Privilege.TABLE_READ, deferred = true)
    @Operation(
            summary = "Execute an ad-hoc SQL SELECT, returning CSV",
            description = "Runs the statement exactly as `/v1/queries/execute-sql` does — same SQL subset,"
                    + " governance, limits, `INFORMATION_SCHEMA` handling and errors — and returns the result as"
                    + " an RFC 4180 CSV attachment in the format `/v1/queries/execute-csv` describes. The header"
                    + " row carries the same column names execute-sql uses as row keys.")
    @ApiResponse(
            responseCode = "200",
            description = "Query result as CSV",
            content = @Content(mediaType = "text/csv", schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "400", description = "SQL parse/validation failure or unsupported SQL construct")
    @ApiResponse(responseCode = "422",
            description = "Query exceeded the configured execution budget (`query_budget_exceeded`).")
    public ResponseEntity<byte[]> executeSqlCsv(@Valid @RequestBody SqlQueryRequestDto request) {
        return csv(sqlStatementRunner.run(request.sql()));
    }

    private ResponseEntity<byte[]> csv(QueryResultPage page) {
        final ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(TEXT_CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, CONTENT_DISPOSITION);
        if (page.totalCount() != null) {
            response.header(TOTAL_COUNT_HEADER, page.totalCount().toString());
        }
        return response.body(csvResultWriter.write(page.columns(), page.rows()));
    }
}
