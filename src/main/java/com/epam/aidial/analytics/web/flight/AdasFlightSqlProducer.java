package com.epam.aidial.analytics.web.flight;

import com.epam.aidial.analytics.observability.SqlLog;
import com.epam.aidial.analytics.service.query.StructuredQueryService;
import com.epam.aidial.analytics.service.query.arrow.ArrowAllocators;
import com.epam.aidial.analytics.service.query.arrow.ArrowBatchWriter;
import com.epam.aidial.analytics.service.query.arrow.ArrowTypeMapper;
import com.epam.aidial.analytics.service.query.model.OffsetPage;
import com.epam.aidial.analytics.service.query.repository.QueryResultPage;
import com.epam.aidial.analytics.service.query.sql.ResultColumn;
import com.epam.aidial.analytics.service.query.sql.SqlCompositeTranslation;
import com.epam.aidial.analytics.service.query.sql.SqlExecutableStatement;
import com.epam.aidial.analytics.service.query.sql.SqlMetadataResult;
import com.epam.aidial.analytics.service.query.sql.SqlQueryFrontEnd;
import com.epam.aidial.analytics.service.query.sql.SqlStatementPlan;
import com.epam.aidial.analytics.service.query.sql.SqlTranslation;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import lombok.extern.slf4j.Slf4j;
import org.apache.arrow.flight.CallStatus;
import org.apache.arrow.flight.FlightDescriptor;
import org.apache.arrow.flight.FlightEndpoint;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.PutResult;
import org.apache.arrow.flight.Result;
import org.apache.arrow.flight.Ticket;
import org.apache.arrow.flight.sql.BasicFlightSqlProducer;
import org.apache.arrow.flight.sql.SqlInfoBuilder;
import org.apache.arrow.flight.sql.impl.FlightSql;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Schema;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The Arrow Flight SQL producer: SQL is
 * translated onto the structured-query DSL and executed through the exact same pipeline as
 * {@code POST /v1/queries/execute}, so all structured-query governance applies unchanged. Honors
 * Flight's two-call trust model — {@code getFlightInfoStatement} translates and validates with NO
 * ClickHouse execution and returns an opaque ticket carrying only the SQL text;
 * {@code getStreamStatement} re-runs the full translate → validate → execute pipeline from that
 * ticket.
 *
 * <p>Catalog metadata RPCs ({@code GetCatalogs}, {@code GetDbSchemas}, {@code GetTables},
 * {@code GetTableTypes}, {@code GetXdbcTypeInfo}, {@code GetSqlInfo}) are supported and stream from
 * {@link FlightCatalogStreams}, i.e. from the same catalog as the {@code /v1/queries} discovery
 * endpoints and under the same per-caller sensitive-column filtering. Prepared statements and the
 * key-metadata RPCs remain UNIMPLEMENTED, and — unlike the getStream half before this change — they
 * now fail at {@code getFlightInfo} rather than promising an endpoint they cannot serve.
 *
 * <p>Row governance is this transport's own: {@code flight.max-rows} (default 10000) replaces the shared
 * 1000-row maximum. It is a ceiling on the rows returned, <em>not</em> a limit on the {@code LIMIT}
 * literal a client may write — no statement is refused for the size of its bound. What matters is
 * whether the client stated a bound this endpoint can honour: an explicit {@code LIMIT} at or under the
 * cap is honoured exactly, with no probe and no truncation error, because stopping where the caller
 * asked is not a surprise. Everything else — no {@code LIMIT} at all, or one above the cap — runs under
 * an implicit bound of the cap.
 *
 * <p>Both of those are the same case for a reason. A statement with no {@code LIMIT} cannot be rejected:
 * that would block results which cannot truncate ({@code SELECT count(*)}) and every query a BI tool's
 * DirectQuery generator emits. A {@code LIMIT} far above the cap cannot be rejected either, because it
 * is not a request for that many rows — it is a client guard rail (Power BI writes
 * {@code LIMIT 1000001} on results of a few dozen rows), and refusing it blocks a query this endpoint
 * can answer. Both are therefore run requesting one row beyond the cap, and fail only if that extra row
 * materializes, proving the result really was truncated. A partial page is never streamed and the probe
 * row never reaches the client. Since {@code getFlightInfo} validates without executing, that failure
 * can only surface at {@code doGet} — so such a query's success depends on the data, while a client
 * supplying an honourable {@code LIMIT} is immune. REST and DSL callers keep the shared 100/1000
 * governance untouched, including its rejection of an oversized explicit limit.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "flight", name = "enabled", havingValue = "true")
public class AdasFlightSqlProducer extends BasicFlightSqlProducer {

    private final SqlQueryFrontEnd frontEnd;
    private final StructuredQueryService queryService;
    private final FlightCatalogStreams catalogStreams;
    private final FlightProperties properties;
    private final FlightStreamGate gate;
    private final ArrowAllocators allocators;
    private final JsonMapper jsonMapper;

    public AdasFlightSqlProducer(
            SqlQueryFrontEnd frontEnd,
            StructuredQueryService queryService,
            FlightCatalogStreams catalogStreams,
            FlightProperties properties,
            FlightStreamGate gate,
            ArrowAllocators allocators,
            JsonMapper jsonMapper) {
        this.frontEnd = frontEnd;
        this.queryService = queryService;
        this.catalogStreams = catalogStreams;
        this.properties = properties;
        this.gate = gate;
        this.allocators = allocators;
        this.jsonMapper = jsonMapper;
    }

    // ---- Ad-hoc statement path -----------------------------------------------------------

    @Override
    public FlightInfo getFlightInfoStatement(
            FlightSql.CommandStatementQuery command, CallContext context, FlightDescriptor descriptor) {
        final String sql = command.getQuery();
        try {
            // Plan under the caller's identity so the advertised Arrow schema reflects only the
            // columns that caller may see (a sensitive column is hidden without TABLE_SENSITIVE on
            // the table that owns it).
            final SqlStatementPlan plan = asCaller(context, () -> frontEnd.plan(sql));
            // Nothing here depends on the row cap any more: an oversized LIMIT is clamped at execution
            // rather than refused, and whether a clamped result actually truncated is only knowable
            // once it has run — so it is decided at doGet, like the no-LIMIT case always was.
            final List<ResultColumn> columns = switch (plan) {
                case SqlMetadataResult metadata -> metadata.columns();
                case SqlExecutableStatement statement -> statement.columns();
            };
            final Schema schema = ArrowTypeMapper.schema(columns);
            final Ticket ticket = new Ticket(Any.pack(FlightSql.TicketStatementQuery.newBuilder()
                    .setStatementHandle(ByteString.copyFromUtf8(sql))
                    .build()).toByteArray());
            return new FlightInfo(schema, descriptor, List.of(new FlightEndpoint(ticket)), -1, -1);
        } catch (RuntimeException e) {
            throw FlightErrorMapper.toFlightException(e);
        }
    }

    @Override
    public void getStreamStatement(
            FlightSql.TicketStatementQuery ticket, CallContext context, ServerStreamListener listener) {
        streamQuery(ticket.getStatementHandle().toStringUtf8(), context, listener);
    }

    /**
     * Whether the statement stated a bound this endpoint can honour — an explicit {@code LIMIT} at or
     * under {@code flight.max-rows}. Such a query runs as written: no probe row, no truncation check.
     *
     * <p>An explicit {@code LIMIT} <em>above</em> the cap deliberately answers false, putting it in the
     * same bucket as a statement that stated no bound at all. The cap limits the rows returned, not the
     * literal a client may write, and an oversized literal is a client guard rail rather than a request
     * — Power BI's DirectQuery generator emits {@code LIMIT 1000001} on results of a few dozen rows, so
     * refusing it would block a query this endpoint can answer while protecting nothing.
     *
     * <p>Both callers share this one predicate so the clamp and the truncation check cannot drift into
     * disagreeing about which statements are bounded.
     */
    private boolean honorsStatedLimit(SqlExecutableStatement translation) {
        return translation.explicitLimit()
                && translation.page() instanceof OffsetPage page
                && page.limit() <= properties.maxRows();
    }

    /**
     * The query to execute for {@code translation}. A statement whose stated bound can be honoured runs
     * as written — the caller asked for a bound, so stopping at it is not a surprise. Every other
     * statement — no {@code LIMIT}, or one above the cap — is clamped to the cap and run with one row
     * <em>more</em> than it, so that {@link #rejectIfTruncated} can tell "all the rows there are" from
     * "the first {@code flight.max-rows} of more than that" and fail rather than pass off a partial page
     * as complete. Any {@code OFFSET} the statement carried is preserved.
     */
    private SqlExecutableStatement effectiveQuery(SqlExecutableStatement translation) {
        if (honorsStatedLimit(translation)) {
            return translation;
        }
        final long offset = translation.page() instanceof OffsetPage page ? page.offset() : 0;
        return translation.withPage(new OffsetPage(offset, properties.maxRows() + 1, false));
    }

    /** Runs either executable shape through the shared service under the Flight row ceiling. */
    private QueryResultPage execute(SqlExecutableStatement statement, int maxLimit) {
        return switch (statement) {
            case SqlTranslation translation -> queryService.execute(translation.query(), maxLimit);
            case SqlCompositeTranslation composite -> queryService.execute(composite.query(), maxLimit);
        };
    }

    /**
     * Fails when a query running under the implicit bound returned more rows than the cap — the probe
     * row proves the result was truncated. Deliberately has no "trim and continue" branch: that is
     * silent truncation wearing a different hat, and never returning a truncated page as if it were
     * complete is the whole point. Because {@code getFlightInfo} validates without executing, this can
     * only be discovered at {@code doGet}, so the row count — and therefore the data — decides whether
     * the stream succeeds. That now applies to an over-cap explicit {@code LIMIT} too: it is clamped
     * rather than refused, so it must be proven not to have truncated like any other clamped query.
     */
    private void rejectIfTruncated(SqlExecutableStatement translation, QueryResultPage page) {
        if (honorsStatedLimit(translation) || page.rows().size() <= properties.maxRows()) {
            return;
        }
        throw CallStatus.INVALID_ARGUMENT
                .withDescription("the result exceeds the maximum of " + properties.maxRows()
                        + " rows this endpoint returns; add an explicit LIMIT of at most "
                        + properties.maxRows() + " rows, or aggregate the query to fewer rows")
                .toRuntimeException();
    }

    // ---- Catalog metadata ------------------------------------------------------------------

    @Override
    public void getStreamCatalogs(CallContext context, ServerStreamListener listener) {
        streamCatalog(context, listener, catalogStreams::catalogs);
    }

    @Override
    public void getStreamSchemas(
            FlightSql.CommandGetDbSchemas command, CallContext context, ServerStreamListener listener) {
        streamCatalog(context, listener, catalogStreams::dbSchemas);
    }

    @Override
    public void getStreamTableTypes(CallContext context, ServerStreamListener listener) {
        streamCatalog(context, listener, catalogStreams::tableTypes);
    }

    @Override
    public void getStreamTables(
            FlightSql.CommandGetTables command, CallContext context, ServerStreamListener listener) {
        streamCatalog(context, listener, allocator -> catalogStreams.tables(command, allocator));
    }

    @Override
    public void getStreamTypeInfo(
            FlightSql.CommandGetXdbcTypeInfo command, CallContext context, ServerStreamListener listener) {
        streamCatalog(context, listener, allocator -> catalogStreams.xdbcTypeInfo(command, allocator));
    }

    /**
     * Streams one catalog result as a single batch. The rows are built under the caller's identity, so
     * sensitive columns stay hidden from a caller who may not see them — a catalog that named them would
     * leak their existence just as surely as returning their values would.
     *
     * <p>Memory comes from the same bounded per-request child allocator the query path uses, always
     * closed — a builder that fails part-way closes its half-built root itself (see
     * {@link FlightCatalogStreams}), since that root never reaches the try-with-resources here and would
     * otherwise leave the child allocator with outstanding allocation it can never release. The
     * {@link FlightStreamGate} is deliberately NOT acquired: it caps concurrent ClickHouse
     * executions, and no catalog RPC touches ClickHouse. Gating them would let query saturation break a
     * client's discovery — for an ODBC driver, on a call it treats as infallible.
     */
    private void streamCatalog(
            CallContext context, ServerStreamListener listener, Function<BufferAllocator, VectorSchemaRoot> rows) {
        BufferAllocator child = null;
        try {
            child = allocators.newChildAllocator(
                    "flight-meta-" + UUID.randomUUID(), properties.requestMemoryLimitBytes());
            final BufferAllocator allocator = child;
            try (VectorSchemaRoot root = asCaller(context, () -> rows.apply(allocator))) {
                listener.start(root);
                listener.putNext();
                listener.completed();
            }
        } catch (RuntimeException e) {
            log.debug("Flight catalog RPC failed: {}", e.getMessage());
            listener.error(FlightErrorMapper.toFlightException(e));
        } finally {
            if (child != null) {
                child.close();
            }
        }
    }

    // ---- Prepared statements: statement-only v1 ------------------------------------------

    @Override
    public void createPreparedStatement(
            FlightSql.ActionCreatePreparedStatementRequest request, CallContext context,
            StreamListener<Result> listener) {
        listener.onError(CallStatus.UNIMPLEMENTED
                .withDescription("prepared statements are not supported; use ad-hoc statements")
                .toRuntimeException());
    }

    @Override
    public Runnable acceptPutPreparedStatementQuery(
            FlightSql.CommandPreparedStatementQuery command, CallContext context, FlightStream flightStream,
            StreamListener<PutResult> ackStream) {
        return () -> ackStream.onError(CallStatus.UNIMPLEMENTED
                .withDescription("prepared statements are not supported; use ad-hoc statements")
                .toRuntimeException());
    }

    @Override
    public FlightInfo getFlightInfoPreparedStatement(
            FlightSql.CommandPreparedStatementQuery command, CallContext context, FlightDescriptor descriptor) {
        throw CallStatus.UNIMPLEMENTED
                .withDescription("prepared statements are not supported; use ad-hoc statements")
                .toRuntimeException();
    }

    @Override
    public void getStreamPreparedStatement(
            FlightSql.CommandPreparedStatementQuery command, CallContext context, ServerStreamListener listener) {
        listener.error(CallStatus.UNIMPLEMENTED
                .withDescription("prepared statements are not supported; use ad-hoc statements")
                .toRuntimeException());
    }

    // ---- Capability advertisement ---------------------------------------------------------

    /**
     * Advertises the endpoint's SQL capabilities, including — critically — that neither a catalog nor a
     * schema namespace exists.
     *
     * <p>An empty term plus an empty supported-actions list is how the protocol says "this namespace
     * level is not there". {@code withSqlDdlCatalog}/{@code withSqlDdlSchema} do NOT say that: they
     * declare whether DDL can create such objects, which is a different question, and a client that sees
     * no answer to <em>this</em> one assumes the namespace exists. That assumption is not academic — an
     * ODBC driver then reports {@code SQL_SCHEMA_TERM='schema'} and {@code SQL_SCHEMA_USAGE=1}, Power BI
     * qualifies every generated statement with the schema name, {@code GetDbSchemas} streams no rows so
     * that name is the empty string, and ADAS is handed
     * {@code select "a", "b" from ""."rate_analytics"} — a parse error on an empty quoted identifier.
     * Declaring the terms is what lets a client discover the unqualified-names design decision instead of
     * guessing the opposite. Catalogs already behaved correctly without a declaration; they are declared
     * anyway so the guarantee is asserted on the wire rather than left to a default.
     *
     * <p>That "left to a default" is the general problem, not a quirk of the namespace keys — and the
     * cost of leaving a value out is larger than one missing row. {@link SqlInfoBuilder#send} looks up a
     * provider per requested code with no null check, so a code this builder holds no value for throws
     * and aborts the whole response through {@code listener.error}: every other value the client asked
     * for in the same call is lost with it. An unadvertised value is therefore an <em>error</em> whose
     * blast radius is however many codes the client batched, which is what a driver surfaces as
     * {@code <Error>} from {@code SQLGetInfo}. A value ADAS has a settled answer to is worth declaring
     * for that reason alone.
     *
     * <p>Which values may be declared is governed by a rule, so extending this list does not mean
     * re-deriving the risk analysis. A value belongs here only when it is <b>determined</b> (its answer
     * follows from behaviour the {@code flight-sql-endpoint} or {@code sql-query-translation} specs
     * already fix, or from the total absence of a feature) <b>and non-steering</b> (it cannot make a
     * client generate SQL the front end then rejects). That admits a negative declaration, a limit of
     * {@code 0}, and a positive declaration this codebase covers with a test. Anything else stays off.
     *
     * <p>Hence the deliberate omissions: the function-support sets ({@code SQL_STRING_FUNCTIONS},
     * {@code SQL_NUMERIC_FUNCTIONS}, {@code SQL_SYSTEM_FUNCTIONS}, {@code SQL_DATETIME_FUNCTIONS}) and the
     * grammar-level claims ({@code SQL_SUPPORTED_GRAMMAR}, {@code SQL_ANSI92_SUPPORTED_LEVEL},
     * {@code SQL_SUPPORTED_GROUP_BY}). Those say what ADAS can <em>execute</em>, and a client acts on them
     * by folding expressions into the statement it generates: over-claiming turns a computation that
     * succeeded locally into a rejected query. Getting them right means mapping each bit against the
     * closed function catalog and measuring how clients treat a partial answer — a change of its own, and
     * a prerequisite for DirectQuery. Until then {@code SQL_DATETIME_FUNCTIONS} keeps erroring on purpose.
     */
    @Override
    public void getStreamSqlInfo(
            FlightSql.CommandGetSqlInfo command, CallContext context, ServerStreamListener listener) {
        new SqlInfoBuilder()
                // Server identity and posture.
                .withFlightSqlServerName("analytics-data-access-service")
                .withFlightSqlServerVersion("1.0")
                .withFlightSqlServerArrowVersion("18.3.0")
                .withFlightSqlServerReadOnly(true)
                .withFlightSqlServerSql(true)
                .withFlightSqlServerSubstrait(false)
                .withFlightSqlServerTransaction(FlightSql.SqlSupportedTransaction.SQL_SUPPORTED_TRANSACTION_NONE)
                .withSqlTransactionsSupported(false)
                .withSqlDdlCatalog(false)
                .withSqlDdlSchema(false)
                .withSqlDdlTable(false)
                // No catalog and no schema namespace: empty term, no supported element actions. Empty
                // varargs is deliberate — it publishes an empty action list rather than omitting the key.
                .withSqlCatalogTerm("")
                .withSqlCatalogsSupportedActions()
                .withSqlSchemaTerm("")
                .withSqlSchemasSupportedActions()
                // Identifiers. Unquoted names carry nothing beyond [A-Za-z0-9_] — the one dotted form,
                // an enrichment column, is addressable only double-quoted — so the extra-name-character
                // set is empty. This is ODBC's SQL_SPECIAL_CHARACTERS, which failed outright before.
                // Quoted names match case-insensitively for the same reason unquoted ones do: the front
                // end's identifier matching is. The backslash is what the catalog RPCs' LIKE patterns
                // escape %/_ with. ADAS reserves no keyword beyond standard SQL: date_bin and
                // width_bucket are function names, so quoting them as identifiers is unaffected.
                .withSqlIdentifierCase(FlightSql.SqlSupportedCaseSensitivity.SQL_CASE_SENSITIVITY_CASE_INSENSITIVE)
                .withSqlIdentifierQuoteChar("\"")
                .withSqlQuotedIdentifierCase(
                        FlightSql.SqlSupportedCaseSensitivity.SQL_CASE_SENSITIVITY_CASE_INSENSITIVE)
                .withSqlExtraNameCharacters("")
                .withSqlSearchStringEscape("\\")
                .withSqlKeywords(new String[0])
                // Set operations execute, so this is the one positive declaration in the block: a client
                // generating UNION or UNION ALL from it writes something the front end accepts. INTERSECT
                // and EXCEPT have no SqlInfo code of their own, so their absence here says nothing about
                // them. A bare UNION is refused — as the database itself refuses it — which is the one
                // spelling a client could generate from SQL_UNION and have rejected.
                .withSqlSupportedUnions(
                        FlightSql.SqlSupportedUnions.SQL_UNION, FlightSql.SqlSupportedUnions.SQL_UNION_ALL)
                // Absences: every construct the SQL front end rejects, and every feature the endpoint has
                // no implementation of. An empty varargs list publishes a zero bitmask — the key is
                // present and says "none", which is the whole point; omitting it would say nothing.
                //
                // The next two are INHERITED UNDER-CLAIMS, not absences: subqueries and outer joins both
                // execute since extend-sql-surface, and these declarations were never updated. They are
                // left as they are deliberately — each under-claims in the safe direction (a client that
                // believes them generates simpler SQL that ADAS accepts), and correcting them means one
                // derivation across them and SQL_MAX_TABLES_IN_SELECT below: which bitmask members hold,
                // given that Flight's quantified-subquery bit is still genuinely rejected. That is a
                // change of its own; see the flight-sql-endpoint spec, which records all three.
                .withSqlSubQueriesSupported()
                .withSqlOuterJoinSupportLevel()
                .withSqlSupportedPositionedCommands()
                .withSqlSupportedTransactionsIsolationLevels()
                .withSqlSupportsConvert(Map.of())
                .withSqlProcedureTerm("")
                .withSqlDefaultTransactionIsolation(0)
                .withSqlStoredProceduresSupported(false)
                .withSqlStoredFunctionsUsingCallSyntaxSupported(false)
                .withSqlCorrelatedSubqueriesSupported(false)
                .withSqlSelectForUpdateSupported(false)
                .withSqlBatchUpdatesSupported(false)
                .withSqlSavepointsSupported(false)
                .withSqlNamedParametersSupported(false)
                .withSqlSupportsLikeEscapeClause(false)
                .withSqlSupportsIntegrityEnhancementFacility(false)
                .withSqlSupportsNonNullableColumns(false)
                .withSqlDataDefinitionCausesTransactionCommit(false)
                .withSqlDataDefinitionsInTransactionsIgnored(false)
                .withSqlLocatorsUpdateCopy(false)
                .withSqlCatalogAtStart(false)
                .withSqlMaxRowSizeIncludesBlobs(false)
                // Limits. Zero is the ODBC/JDBC encoding of "the source imposes no limit", which is
                // literally ADAS's position — it validates neither an identifier's length nor a
                // statement's size. It is NOT a claim that ClickHouse or the transport are unbounded.
                .withSqlMaxCatalogNameLength(0)
                .withSqlDbSchemaNameLength(0)
                .withSqlMaxTableNameLength(0)
                .withSqlMaxColumnNameLength(0)
                .withSqlMaxCursorNameLength(0)
                .withSqlMaxProcedureNameLength(0)
                .withSqlMaxUsernameLength(0)
                .withSqlMaxBinaryLiteralLength(0)
                .withSqlMaxCharLiteralLength(0)
                .withSqlMaxColumnsInGroupBy(0)
                .withSqlMaxColumnsInIndex(0)
                .withSqlMaxColumnsInOrderBy(0)
                .withSqlMaxColumnsInSelect(0)
                .withSqlMaxColumnsInTable(0)
                .withSqlMaxConnections(0)
                .withSqlMaxIndexLength(0)
                .withSqlMaxRowSize(0)
                .withSqlMaxStatementLength(0)
                .withSqlMaxStatements(0)
                // The third inherited under-claim (see SQL_SUPPORTED_UNIONS above). This was a real bound
                // when the translator accepted a single-entity FROM only; joins and now set operations
                // name any number of entities, so `1` under-claims a supported construct. Left as it is
                // with the other two, to be corrected in one derivation rather than piecemeal.
                .withSqlMaxTablesInSelect(1)
                // Transport facts. A Flight stream is consumed once, forward; nothing here cancels a
                // running query or enforces a server-side timeout.
                .withSqlSupportedResultSetTypes(
                        FlightSql.SqlSupportedResultSetType.SQL_RESULT_SET_TYPE_FORWARD_ONLY)
                .withFlightSqlServerCancel(false)
                .withFlightSqlServerBulkIngestion(false)
                .withFlightSqlServerBulkIngestionTransaction(false)
                .withFlightSqlServerStatementTimeout(0)
                .withFlightSqlServerTransactionTimeout(0)
                // Capabilities ADAS actually has. Each is pinned by a test rather than asserted here:
                // aliasing and correlation names by statements the integration suite executes, and
                // all-tables-selectable by FlightMetadataAuthIntegrationTest issuing a SELECT against
                // every name GetTables streamed to a restricted caller. That claim now rests on the
                // catalog being access-filtered per table at the TABLE_READ rung: GetTables names only
                // what CatalogQueryService resolves for this caller, and what it resolves is exactly what
                // a statement can read. Filtering at a lower rung (TABLE_GET, as HTTP discovery does)
                // would break the claim, which is why Flight deliberately filters at READ instead —
                // Flight is an execution protocol and must not advertise a table DoGet would refuse.
                .withSqlSupportsColumnAliasing(true)
                .withSqlSupportsTableCorrelationNames(true)
                .withSqlSupportsDifferentTableCorrelationNames(false)
                .withSqlAllTablesAreSelectable(true)
                .withSqlNullPlusNullIsNull(true)
                // Narrower than reality, on purpose. Both are genuinely mixed: ORDER BY count(*) resolves
                // through a select output and works, while ORDER BY price * 2 is rejected as arithmetic;
                // ordering by an unselected column works in row mode but not in aggregate mode. Declaring
                // false steers a client to name the output column, which always works. Declaring true
                // would let it generate the half that does not.
                .withSqlSupportsExpressionsInOrderBy(false)
                .withSqlSupportsOrderByUnrelated(false)
                // Measured, not derived: null placement is ClickHouse's behaviour, not ADAS's, and the
                // DSL exposes explicit NULLS FIRST/LAST without documenting a default. ClickHouse sorts
                // nulls last ascending — i.e. as the high values — and an integration test fails if that
                // ever stops being true.
                .withSqlNullOrdering(FlightSql.SqlNullOrdering.SQL_NULLS_SORTED_HIGH)
                .send(command.getInfoList(), listener);
    }

    /**
     * Hands out an endpoint only for a command this producer can actually stream. Returning one for
     * every command type — as this method used to — makes an unimplemented command look supported until
     * {@code doGet} fails, which drivers treat as a protocol violation: Dremio's Flight SQL ODBC driver
     * crashes its host process when a promised {@code SQLTables} stream comes back UNIMPLEMENTED. The
     * statement and prepared-statement paths do not route through here.
     */
    @Override
    protected <T extends Message> List<FlightEndpoint> determineEndpoints(
            T request, FlightDescriptor descriptor, Schema schema) {
        final boolean supported = request instanceof FlightSql.CommandGetSqlInfo
                || request instanceof FlightSql.CommandGetCatalogs
                || request instanceof FlightSql.CommandGetDbSchemas
                || request instanceof FlightSql.CommandGetTables
                || request instanceof FlightSql.CommandGetTableTypes
                || request instanceof FlightSql.CommandGetXdbcTypeInfo;
        if (!supported) {
            throw CallStatus.UNIMPLEMENTED
                    .withDescription(request.getDescriptorForType().getName() + " is not supported")
                    .toRuntimeException();
        }
        return List.of(new FlightEndpoint(new Ticket(Any.pack(request).toByteArray())));
    }

    // ---- Caller identity propagation -------------------------------------------------------

    /**
     * Runs {@code action} with the Flight caller's {@link Authentication} installed in the Spring
     * {@link SecurityContextHolder}, so the shared query pipeline resolves the caller's roles exactly as
     * it does on the HTTP path and filters sensitive columns accordingly. The principal is captured per
     * call by {@link FlightPrincipalMiddleware}; when absent (e.g. {@code security.mode=none}, where no
     * middleware is registered) the action runs with no context and sensitivity filtering fails open, as
     * specified. The previous context is always restored, since Flight runs on pooled gRPC threads.
     */
    private <T> T asCaller(CallContext context, Supplier<T> action) {
        final FlightPrincipalMiddleware principal = context.getMiddleware(FlightPrincipalMiddleware.KEY);
        final Authentication authentication = principal != null ? principal.authentication() : null;
        if (authentication == null) {
            return action.get();
        }
        final SecurityContext previous = SecurityContextHolder.getContext();
        try {
            final SecurityContext scoped = SecurityContextHolder.createEmptyContext();
            scoped.setAuthentication(authentication);
            SecurityContextHolder.setContext(scoped);
            return action.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    // ---- Shared streaming core -------------------------------------------------------------

    /** Re-runs the full translate → validate → execute pipeline and streams Arrow batches. */
    private void streamQuery(String sql, CallContext context, ServerStreamListener listener) {
        boolean acquired = false;
        BufferAllocator child = null;
        try {
            // Planned before the gate is taken. Planning reads the catalog, never ClickHouse, and a
            // catalog statement must not queue behind a gate that governs ClickHouse execution — the
            // same reason the catalog RPCs stay available at saturation.
            // Planned and executed under the caller's identity so sensitive-column filtering matches
            // the caller's role (see getFlightInfoStatement).
            final SqlStatementPlan plan = asCaller(context, () -> frontEnd.plan(sql));
            final Streamable streamable = switch (plan) {
                case SqlMetadataResult metadata -> new Streamable(metadata.columns(), metadata.rows());
                case SqlExecutableStatement translation -> {
                    gate.acquire();
                    acquired = true;
                    // The clamp lives here rather than at getFlightInfo because the ticket carries
                    // client-supplied SQL: a hand-forged ticket must not reach execution unbounded.
                    // maxRows + 1 is the ceiling the shared governance must authorise, because the
                    // truncation probe is expressed as the page limit. A client-supplied LIMIT can never
                    // reach that headroom — effectiveQuery has already bounded it by maxRows.
                    final SqlExecutableStatement executable = effectiveQuery(translation);
                    final QueryResultPage page = asCaller(
                            context, () -> execute(executable, properties.maxRows() + 1));
                    // Before anything is streamed: a partial page must never reach the client.
                    rejectIfTruncated(translation, page);
                    yield new Streamable(translation.columns(), page.rows());
                }
            };
            child = allocators.newChildAllocator(
                    "flight-" + UUID.randomUUID(), properties.requestMemoryLimitBytes());
            final Schema schema = ArrowTypeMapper.schema(streamable.columns());
            try (VectorSchemaRoot root = VectorSchemaRoot.create(schema, child)) {
                listener.start(root);
                ArrowBatchWriter.drain(root, streamable.rows(), streamable.columns(),
                        properties.batchRows(), properties.batchMaxBytes(), child, jsonMapper,
                        batch -> listener.putNext());
                listener.completed(); // clean-completion marker; omitted on the error paths
            }
        } catch (RuntimeException e) {
            // The type on the class logger; the message only on the SQL logger, because a translation or
            // ClickHouse failure message can quote the client's statement, literals included.
            log.debug("Flight statement failed: {}", e.getClass().getSimpleName());
            SqlLog.LOG.debug("Flight statement failed: {}", e.getMessage());
            listener.error(FlightErrorMapper.toFlightException(e));
        } finally {
            if (acquired) {
                gate.release();
            }
            if (child != null) {
                child.close();
            }
        }
    }

    /**
     * Rows plus their column descriptors, whatever produced them. The two statement kinds differ in
     * how the rows are obtained and in what governs them — a catalog statement takes no concurrency
     * permit and has no row cap — but not in how they are written to Arrow, so the writer sees one
     * shape.
     */
    private record Streamable(List<ResultColumn> columns, List<Map<String, Object>> rows) {}
}
