package com.epam.aidial.analytics.service.pipeline;

import com.epam.aidial.analytics.configuration.JsonMapperConfiguration;
import com.epam.aidial.analytics.exception.PipelineValidationException;
import com.epam.aidial.analytics.exception.SensitiveColumnNotEntitledException;
import com.epam.aidial.analytics.service.enrichment.support.EntityFieldResolver;
import com.epam.aidial.analytics.service.enrichment.support.SensitiveProjectionGuard;
import com.epam.aidial.analytics.service.enrichment.validation.FilterSqlValidator;
import com.epam.aidial.analytics.service.pipeline.model.AggregateSpec;
import com.epam.aidial.analytics.service.pipeline.model.TruncUnit;
import com.epam.aidial.analytics.service.query.CatalogQueryService;
import com.epam.aidial.analytics.service.query.DialUsageLogTestModel;
import com.epam.aidial.analytics.service.query.JooqTypes;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.QueryFieldBinding;
import com.epam.aidial.analytics.service.query.QueryFunctionCatalog;
import com.epam.aidial.analytics.service.query.sql.CalciteQueryCatalog;
import com.epam.aidial.analytics.service.query.sql.SqlQueryFrontEnd;
import com.epam.aidial.analytics.service.security.SensitiveColumnAccess;
import com.epam.aidial.analytics.service.table.ColumnMappingEntity;
import com.epam.aidial.analytics.service.table.ColumnMappingRepository;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import com.epam.aidial.analytics.service.table.TableStatus;
import com.epam.aidial.analytics.service.table.TableType;
import com.epam.aidial.analytics.service.table.TableWriteMode;
import com.epam.aidial.analytics.service.table.grant.SensitiveGrantInvariant;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import com.epam.aidial.analytics.web.dto.query.QuerySchemaFieldDto;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * {@link AggregateSqlValidator}, reached the way every caller reaches it — {@link AggregateSpecValidator#validate} with
 * an SQL-mode spec — over the real SQL front end and a real {@link SensitiveProjectionGuard}, so each refusal is the
 * one the planned statement actually produces. One test per refusal, plus acceptance cases for typical aggregate
 * shapes: {@code turns_rollup}'s per-chat conditional and distinct measures, and {@code sessions_rollup}'s per-session
 * count keyed on a coalesced enrichment column.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AggregateSqlValidatorTest {

    private static final String INPUT = DialUsageLogTestModel.ENTITY;
    private static final String ENRICHMENT = DialUsageLogTestModel.ENRICHMENT;
    private static final String TARGET = "usage_daily";

    @Mock
    private TableDefinitionRepository definitions;
    @Mock
    private ColumnMappingRepository columns;
    @Mock
    private PipelineRepository pipelines;
    @Mock
    private CatalogQueryService catalog;
    @Mock
    private CatalogQueryService frontEndCatalog;
    @Mock
    private SensitiveColumnAccess sensitiveAccess;
    @Mock
    private EnrichmentFreshness freshness;
    @Mock
    private SensitiveGrantInvariant sensitiveGrants;

    private AggregateSpecValidator validator;

    @BeforeEach
    void setUp() {
        given(frontEndCatalog.queryableEntityNames(any())).willReturn(List.of(INPUT, "turns"));
        given(frontEndCatalog.findModel(eq(INPUT), any())).willReturn(Optional.of(DialUsageLogTestModel.modelWithKeyedEnrichment()));
        given(frontEndCatalog.findModel(eq("turns"), any())).willReturn(Optional.of(turnsModel()));
        final AggregateSqlReader reader = new AggregateSqlReader(new SqlQueryFrontEnd(new CalciteQueryCatalog(frontEndCatalog)), definitions);
        final SensitiveProjectionGuard guard = new SensitiveProjectionGuard(sensitiveAccess, columns, catalog);
        validator = new AggregateSpecValidator(definitions, pipelines, new QueryFunctionCatalog(), mock(FilterSqlValidator.class),
                guard, new EntityFieldResolver(columns, catalog), freshness,
                new PipelineSpecCodec(new JsonMapperConfiguration().jsonMapper()), sensitiveGrants, reader);

        final TableDefinitionEntity input = table(INPUT, TableType.SOURCE);
        input.setVersionColumn("_ingested_at");
        input.setIdentityColumn("event_id");
        given(definitions.findById(INPUT)).willReturn(Optional.of(input));
        given(definitions.existsById(INPUT)).willReturn(true);
        given(definitions.findById(ENRICHMENT)).willReturn(Optional.of(table(ENRICHMENT, TableType.ENRICHMENT)));
        given(catalog.activeEnrichmentsOf(INPUT)).willReturn(List.of(table(ENRICHMENT, TableType.ENRICHMENT)));
        given(columns.findByTableNameOrderByOrdinal(INPUT)).willReturn(List.of(
                column(INPUT, "event_id", QueryFieldType.UUID),
                column(INPUT, "_ingested_at", QueryFieldType.TIMESTAMP),
                column(INPUT, "request_time", QueryFieldType.TIMESTAMP),
                column(INPUT, "deployment", QueryFieldType.STRING),
                column(INPUT, "chat_id", QueryFieldType.STRING),
                nullable(column(INPUT, "parent_deployment", QueryFieldType.STRING)),
                sensitive(column(INPUT, "user_hash", QueryFieldType.STRING)),
                column(INPUT, "total_tokens", QueryFieldType.LONG),
                column(INPUT, "prompt_tokens", QueryFieldType.LONG),
                column(INPUT, "deployment_price", QueryFieldType.DECIMAL),
                column(INPUT, "response_status", QueryFieldType.INTEGER),
                column(INPUT, "success", QueryFieldType.BOOLEAN),
                array(INPUT, "execution_path", QueryFieldType.STRING)));
        given(columns.findByTableNameOrderByOrdinal(ENRICHMENT)).willReturn(List.of(
                nullable(column(ENRICHMENT, "client_session_id", QueryFieldType.STRING)),
                column(ENRICHMENT, "client_type", QueryFieldType.STRING),
                nullable(column(ENRICHMENT, "client_ts", QueryFieldType.TIMESTAMP))));
        given(sensitiveAccess.mayReadSensitive(anyString())).willReturn(true);
        given(sensitiveAccess.maySeeSensitive(anyString())).willReturn(true);

        // The baseline target: (deployment, day) → call_count, tokens.
        target(TARGET, List.of("deployment", "day"),
                column(TARGET, "deployment", QueryFieldType.STRING),
                column(TARGET, "day", QueryFieldType.TIMESTAMP),
                column(TARGET, "call_count", QueryFieldType.LONG),
                column(TARGET, "tokens", QueryFieldType.LONG));
    }

    private static final String HEAD = "INSERT INTO usage_daily (deployment, day, call_count, tokens) ";
    private static final String KEYS = "SELECT deployment, date_trunc('day', request_time), count(*), sum(total_tokens) ";

    // --- fixtures ----------------------------------------------------------------------------------

    private static TableDefinitionEntity table(String name, TableType type) {
        final TableDefinitionEntity entity = new TableDefinitionEntity();
        entity.setName(name);
        entity.setType(type);
        entity.setStatus(TableStatus.ACTIVE);
        return entity;
    }

    private void target(String name, List<String> orderingKey, ColumnMappingEntity... cols) {
        final TableDefinitionEntity entity = table(name, TableType.SOURCE);
        entity.setWriteMode(TableWriteMode.UPSERT_BY_KEY);
        entity.setOrderingKey(new ArrayList<>(orderingKey));
        given(definitions.findById(name)).willReturn(Optional.of(entity));
        given(columns.findByTableNameOrderByOrdinal(name)).willReturn(List.of(cols));
    }

    private static ColumnMappingEntity column(String table, String name, QueryFieldType type) {
        final ColumnMappingEntity col = new ColumnMappingEntity();
        col.setTableName(table);
        col.setSourceName(name);
        col.setName(name);
        col.setType(type);
        return col;
    }

    private static ColumnMappingEntity nullable(ColumnMappingEntity column) {
        column.setNullable(true);
        return column;
    }

    private static ColumnMappingEntity sensitive(ColumnMappingEntity column) {
        column.setSensitive(true);
        return column;
    }

    private static ColumnMappingEntity array(String table, String name, QueryFieldType element) {
        final ColumnMappingEntity column = column(table, name, QueryFieldType.ARRAY);
        column.setElementType(element);
        return column;
    }

    /** A second entity, for the "reads another entity" refusal. */
    private static QueryEntityModel turnsModel() {
        final Map<String, QueryFieldBinding> bindings = new LinkedHashMap<>();
        bindings.put("chat_id", new QueryFieldBinding(
                "chat_id", DSL.field(DSL.name("turns", "chat_id"), JooqTypes.of(QueryFieldType.STRING)), QueryFieldType.STRING));
        return new QueryEntityModel("turns", DSL.table(DSL.name("turns")), bindings, List.of(),
                List.of(new QuerySchemaFieldDto("chat_id", QueryFieldType.STRING, null, null, "chat_id", null, null, null, false, false)));
    }

    private AggregateSqlValidator.Analysis validate(String sql) {
        return validate(TARGET, sql);
    }

    private AggregateSqlValidator.Analysis validate(String target, String sql) {
        final AggregateSpec spec = new AggregateSpec();
        spec.setSql(sql);
        return validator.validate("p", null, target, null, spec, "aggregate").sql();
    }

    private void assertRefused(String sql, String... fragments) {
        assertRefusedInto(TARGET, sql, fragments);
    }

    private void assertRefusedInto(String target, String sql, String... fragments) {
        var thrown = assertThatThrownBy(() -> validate(target, sql)).isInstanceOf(PipelineValidationException.class);
        for (final String fragment : fragments) {
            thrown = thrown.hasMessageContaining(fragment);
        }
    }

    // --- acceptance --------------------------------------------------------------------------------

    @Test
    void baselineStatementIsAcceptedWithItsKeysAndBucket() {
        final AggregateSqlValidator.Analysis analysis = validate(HEAD + KEYS + "FROM dial_usage_log GROUP BY 1, 2");

        assertThat(analysis.keyPositions()).containsExactlyInAnyOrder(0, 1);
        assertThat(analysis.bucket()).isEqualTo(new AggregateSqlValidator.Bucket(1, TruncUnit.DAY));
    }

    @Test
    void keySetInAnotherOrderIsAccepted() {
        final AggregateSqlValidator.Analysis analysis = validate("INSERT INTO usage_daily (day, deployment, call_count, tokens) "
                + "SELECT date_trunc('day', request_time), deployment, count(*), sum(total_tokens) FROM dial_usage_log GROUP BY 2, 1");

        assertThat(analysis.keyPositions()).containsExactlyInAnyOrder(0, 1);
    }

    @Test
    void turnsRollupShapedStatementIsAccepted() {
        target("turns_rollup", List.of("chat_id"),
                column("turns_rollup", "chat_id", QueryFieldType.STRING),
                column("turns_rollup", "hops", QueryFieldType.LONG),
                column("turns_rollup", "total_tokens", QueryFieldType.LONG),
                column("turns_rollup", "successes", QueryFieldType.LONG),
                column("turns_rollup", "deployments_used", QueryFieldType.LONG),
                column("turns_rollup", "first_seen", QueryFieldType.TIMESTAMP),
                nullable(column("turns_rollup", "root_tokens", QueryFieldType.LONG)));

        final AggregateSqlValidator.Analysis analysis = validate("turns_rollup",
                """
                        INSERT INTO turns_rollup (chat_id, hops, total_tokens, successes, deployments_used, first_seen, root_tokens)
                        SELECT chat_id, count(*), sum(total_tokens), sum(CASE WHEN success THEN 1 ELSE 0 END),
                               count(DISTINCT deployment), min(request_time),
                               sum(CASE WHEN parent_deployment IS NULL THEN total_tokens END)
                        FROM dial_usage_log WHERE chat_id <> '' GROUP BY chat_id""");

        assertThat(analysis.keyPositions()).containsExactly(0);
        assertThat(analysis.bucket()).isNull();
    }

    @Test
    void sessionsRollupShapedCoalescedEnrichmentKeyIsAccepted() {
        target("sessions_rollup", List.of("client_session_id"),
                column("sessions_rollup", "client_session_id", QueryFieldType.STRING),
                column("sessions_rollup", "requests", QueryFieldType.LONG),
                column("sessions_rollup", "total_tokens", QueryFieldType.LONG));

        final AggregateSqlValidator.Analysis analysis = validate("sessions_rollup",
                "INSERT INTO sessions_rollup (client_session_id, requests, total_tokens) "
                        + "SELECT coalesce(\"usage_client_identity.client_session_id\", ''), count(*), sum(total_tokens) "
                        + "FROM dial_usage_log WHERE length(\"usage_client_identity.client_session_id\") > 0 GROUP BY 1");

        assertThat(analysis.keyPositions()).containsExactly(0);
    }

    @Test
    void cteThatOnlyFiltersAndComputesIsAccepted() {
        target("chat_weights", List.of("chat_id"),
                column("chat_weights", "chat_id", QueryFieldType.STRING),
                column("chat_weights", "weighted", QueryFieldType.LONG));

        final AggregateSqlValidator.Analysis analysis = validate("chat_weights", "INSERT INTO chat_weights (chat_id, weighted) "
                + "WITH chat AS (SELECT chat_id, total_tokens * 2 AS w FROM dial_usage_log WHERE chat_id <> '') "
                + "SELECT chat_id, sum(w) FROM chat GROUP BY chat_id");

        assertThat(analysis.keyPositions()).containsExactly(0);
    }

    @Test
    void expressionMeasureIntoDecimalIsAccepted() {
        target("costs", List.of("deployment"),
                column("costs", "deployment", QueryFieldType.STRING),
                column("costs", "cost", QueryFieldType.DECIMAL));

        validate("costs", "INSERT INTO costs (deployment, cost) "
                + "SELECT deployment, sum(prompt_tokens * deployment_price) FROM dial_usage_log GROUP BY deployment");
    }

    @Test
    void dayBucketIntoDateColumnIsAccepted() {
        target("daily", List.of("day"),
                column("daily", "day", QueryFieldType.DATE),
                column("daily", "calls", QueryFieldType.LONG));

        final AggregateSqlValidator.Analysis analysis = validate("daily",
                "INSERT INTO daily (day, calls) SELECT date_trunc('day', request_time), count(*) FROM dial_usage_log GROUP BY 1");

        assertThat(analysis.bucket()).isEqualTo(new AggregateSqlValidator.Bucket(0, TruncUnit.DAY));
    }

    @Test
    void keyProjectedTwiceBindsToTheOrderingKeyColumn() {
        target("copies", List.of("deployment"),
                column("copies", "dep_copy", QueryFieldType.STRING),
                column("copies", "deployment", QueryFieldType.STRING),
                column("copies", "n", QueryFieldType.LONG));

        final AggregateSqlValidator.Analysis analysis = validate("copies", "INSERT INTO copies (dep_copy, deployment, n) "
                + "SELECT deployment, deployment, count(*) FROM dial_usage_log GROUP BY 2");

        assertThat(analysis.keyPositions()).containsExactly(1);
    }

    @Test
    void caseInsideAggregateOverNonNullableEnrichmentColumnIsAccepted() {
        target("typed", List.of("deployment"),
                column("typed", "deployment", QueryFieldType.STRING),
                column("typed", "cli_calls", QueryFieldType.LONG));

        validate("typed", "INSERT INTO typed (deployment, cli_calls) SELECT deployment, "
                + "sum(CASE WHEN \"usage_client_identity.client_type\" = 'cli' THEN 1 ELSE 0 END) FROM dial_usage_log GROUP BY 1");
    }

    // --- statement shape ---------------------------------------------------------------------------

    @Test
    void columnListNamingUpdatedAtIsRefused() {
        assertRefused("INSERT INTO usage_daily (deployment, day, call_count, tokens, _updated_at) "
                + KEYS + ", now() FROM dial_usage_log GROUP BY 1, 2", "_updated_at", "stamps");
    }

    @Test
    void columnListNamingColumnTwiceIsRefused() {
        assertRefused("INSERT INTO usage_daily (deployment, day, call_count, deployment) "
                + "SELECT deployment, date_trunc('day', request_time), count(*), deployment FROM dial_usage_log GROUP BY 1, 2",
                "'deployment' more than once");
    }

    @Test
    void columnListNamingAnUnknownColumnIsRefused() {
        assertRefused("INSERT INTO usage_daily (deployment, day, call_count, tokenz) " + KEYS + "FROM dial_usage_log GROUP BY 1, 2",
                "'tokenz'", "not a column of target");
    }

    @Test
    void columnListMissingTargetColumnIsRefused() {
        assertRefused("INSERT INTO usage_daily (deployment, day, call_count) "
                + "SELECT deployment, date_trunc('day', request_time), count(*) FROM dial_usage_log GROUP BY 1, 2", "[tokens]");
    }

    @Test
    void columnAndSelectListsOfDifferentLengthsAreRefused() {
        assertRefused(HEAD + KEYS + ", max(total_tokens) FROM dial_usage_log GROUP BY 1, 2", "names 4 column(s)", "projects 5");
    }

    // --- sources -----------------------------------------------------------------------------------

    @Test
    void selfJoinThroughCteIsRefused() {
        assertRefused(HEAD + "WITH x AS (SELECT deployment, request_time, total_tokens FROM dial_usage_log) "
                + "SELECT a.deployment, date_trunc('day', a.request_time), count(*), sum(a.total_tokens) "
                + "FROM x a JOIN x b ON a.deployment = b.deployment GROUP BY 1, 2", "referenced 2 times");
    }

    @Test
    void secondEntityIsRefusedNamingIt() {
        assertRefused(HEAD + "SELECT d.deployment, date_trunc('day', d.request_time), count(*), sum(d.total_tokens) "
                + "FROM dial_usage_log d JOIN turns t ON d.chat_id = t.chat_id GROUP BY 1, 2", "JOIN", "turns");
    }

    @Test
    void setOperationIsRefused() {
        assertRefused(HEAD + KEYS + "FROM dial_usage_log GROUP BY 1, 2 UNION ALL " + KEYS + "FROM dial_usage_log GROUP BY 1, 2",
                "set operation");
    }

    @Test
    void subqueryExpressionIsRefused() {
        assertRefused(HEAD + KEYS + "FROM dial_usage_log WHERE deployment IN (SELECT deployment FROM dial_usage_log) GROUP BY 1, 2",
                "subquery");
    }

    @Test
    void selectStarIsRefused() {
        assertRefused(HEAD + "WITH x AS (SELECT * FROM dial_usage_log) "
                + "SELECT deployment, date_trunc('day', request_time), count(*), sum(total_tokens) FROM x GROUP BY 1, 2", "SELECT *");
    }

    @Test
    void userWrittenFinalIsRefused() {
        assertRefused(HEAD + KEYS + "FROM dial_usage_log FINAL GROUP BY 1, 2", "FINAL", "collapses versions itself");
    }

    // --- block shape -------------------------------------------------------------------------------

    @Test
    void statementWithNoAggregationIsRefused() {
        assertRefused(HEAD + "SELECT deployment, request_time, total_tokens, total_tokens FROM dial_usage_log",
                "must be an aggregation");
    }

    @Test
    void aggregateBelowTheTopLevelIsRefused() {
        assertRefused(HEAD + "SELECT deployment, date_trunc('day', t), count(*), sum(n) FROM "
                + "(SELECT deployment, max(request_time) AS t, count(*) AS n FROM dial_usage_log GROUP BY deployment) d GROUP BY 1, 2",
                "flatten to one aggregating block");
    }

    @Test
    void havingIsRefused() {
        assertRefused(HEAD + KEYS + "FROM dial_usage_log GROUP BY 1, 2 HAVING count(*) > 10", "HAVING");
    }

    @Test
    void distinctIsRefused() {
        assertRefused(HEAD + "SELECT DISTINCT deployment, date_trunc('day', request_time), count(*), sum(total_tokens) "
                + "FROM dial_usage_log GROUP BY 1, 2", "DISTINCT");
    }

    @Test
    void orderByIsRefused() {
        assertRefused(HEAD + KEYS + "FROM dial_usage_log GROUP BY 1, 2 ORDER BY 1", "ORDER BY");
    }

    @Test
    void limitIsRefused() {
        assertRefused(HEAD + KEYS + "FROM dial_usage_log GROUP BY 1, 2 LIMIT 5", "LIMIT");
    }

    @Test
    void windowFunctionIsRefused() {
        assertRefused(HEAD + "SELECT deployment, date_trunc('day', request_time), row_number() OVER (ORDER BY deployment), "
                + "sum(total_tokens) FROM dial_usage_log GROUP BY 1, 2", "window function");
    }

    @Test
    void offsetIsRefused() {
        assertRefused(HEAD + KEYS + "FROM dial_usage_log GROUP BY 1, 2 LIMIT 5 OFFSET 5", "LIMIT");
    }

    @Test
    void distinctInDerivedTableIsRefused() {
        assertRefused(HEAD + KEYS + "FROM (SELECT DISTINCT deployment, request_time, total_tokens FROM dial_usage_log) d GROUP BY 1, 2",
                "DISTINCT");
    }

    @Test
    void orderByInDerivedTableIsRefused() {
        assertRefused(HEAD + KEYS + "FROM (SELECT deployment, request_time, total_tokens FROM dial_usage_log ORDER BY deployment) d "
                + "GROUP BY 1, 2", "ORDER BY");
    }

    @Test
    void ratioOfAggregatesIntoNullableDecimalIsAccepted() {
        target("ratios", List.of("deployment"),
                column("ratios", "deployment", QueryFieldType.STRING),
                nullable(column("ratios", "avg_tokens", QueryFieldType.DECIMAL)));

        validate("ratios", "INSERT INTO ratios (deployment, avg_tokens) "
                + "SELECT deployment, sum(total_tokens) / count(*) FROM dial_usage_log GROUP BY deployment");
    }

    @Test
    void argMaxIsAcceptedAsMeasure() {
        target("latest", List.of("chat_id"),
                column("latest", "chat_id", QueryFieldType.STRING),
                column("latest", "last_deployment", QueryFieldType.STRING));

        validate("latest", "INSERT INTO latest (chat_id, last_deployment) "
                + "SELECT chat_id, arg_max(deployment, request_time) FROM dial_usage_log GROUP BY chat_id");
    }

    @Test
    void dateBinOfOneUnitIsAcceptedAsBucket() {
        final AggregateSqlValidator.Analysis analysis = validate(HEAD
                + "SELECT deployment, date_bin(1, 'day', request_time), count(*), sum(total_tokens) FROM dial_usage_log GROUP BY 1, 2");

        assertThat(analysis.bucket()).isEqualTo(new AggregateSqlValidator.Bucket(1, TruncUnit.DAY));
    }

    // --- determinism and the function catalog ------------------------------------------------------

    @Test
    void nonDeterministicFunctionInMeasureIsRefused() {
        assertRefused(HEAD + "SELECT deployment, date_trunc('day', request_time), count(*), "
                + "sum(CASE WHEN request_time > now() THEN total_tokens ELSE 0 END) FROM dial_usage_log GROUP BY 1, 2",
                "non-deterministic function 'now'");
    }

    @Test
    void nonDeterministicFunctionInWhereIsRefused() {
        assertRefused(HEAD + KEYS + "FROM dial_usage_log WHERE request_time >= now() - INTERVAL '7' DAY GROUP BY 1, 2",
                "non-deterministic function 'now'", "WHERE");
    }

    @Test
    void functionOutsideTheCatalogIsRefusedPointingAtTheSqlCatalog() {
        assertRefused(HEAD + "SELECT deployment, date_trunc('day', request_time), countIf(success), sum(total_tokens) "
                + "FROM dial_usage_log GROUP BY 1, 2", "countIf", "/v1/queries/sql-functions");
    }

    // --- group keys --------------------------------------------------------------------------------

    @Test
    void keySetThatDiffersFromTheOrderingKeyIsRefusedReportingBoth() {
        assertRefused("INSERT INTO usage_daily (day, deployment, call_count, tokens) "
                + "SELECT date_trunc('day', request_time), max(deployment), count(*), sum(total_tokens) FROM dial_usage_log GROUP BY 1",
                "[deployment, day]", "[day]");
    }

    @Test
    void constantKeyIsRefused() {
        assertRefused(HEAD + "SELECT 'all', date_trunc('day', request_time), count(*), sum(total_tokens) FROM dial_usage_log GROUP BY 1, 2",
                "constant", "must read the input row");
    }

    @Test
    void twoTimeBucketsAreRefused() {
        target("two_buckets", List.of("hour", "day"),
                column("two_buckets", "hour", QueryFieldType.TIMESTAMP),
                column("two_buckets", "day", QueryFieldType.TIMESTAMP),
                column("two_buckets", "calls", QueryFieldType.LONG));
        assertRefusedInto("two_buckets", "INSERT INTO two_buckets (hour, day, calls) "
                + "SELECT date_trunc('hour', request_time), date_trunc('day', request_time), count(*) FROM dial_usage_log GROUP BY 1, 2",
                "at most one time bucket");
    }

    @Test
    void bucketWithStepOtherThanOneIsRefused() {
        assertRefused(HEAD + "SELECT deployment, date_bin(15, 'minute', request_time), count(*), sum(total_tokens) "
                + "FROM dial_usage_log GROUP BY 1, 2", "step by one unit");
    }

    @Test
    void bucketOfAnUnsupportedUnitIsRefused() {
        assertRefused(HEAD + "SELECT deployment, date_trunc('minute', request_time), count(*), sum(total_tokens) "
                + "FROM dial_usage_log GROUP BY 1, 2", "'minute' time bucket", "hour, day, week, month");
    }

    @Test
    void hourBucketIntoDateColumnIsRefused() {
        target("daily", List.of("day"),
                column("daily", "day", QueryFieldType.DATE),
                column("daily", "calls", QueryFieldType.LONG));

        assertRefusedInto("daily", "INSERT INTO daily (day, calls) SELECT date_trunc('hour', request_time), count(*) "
                + "FROM dial_usage_log GROUP BY 1", "'hour' time bucket", "declared 'date'");
    }

    @Test
    void nullableKeyIsRefusedSuggestingCoalesce() {
        target("sessions_rollup", List.of("client_session_id"),
                column("sessions_rollup", "client_session_id", QueryFieldType.STRING),
                column("sessions_rollup", "requests", QueryFieldType.LONG));

        assertRefusedInto("sessions_rollup", "INSERT INTO sessions_rollup (client_session_id, requests) "
                        + "SELECT \"usage_client_identity.client_session_id\", count(*) FROM dial_usage_log GROUP BY 1",
                "group key 'client_session_id' can be NULL", "never recomputed",
                "coalesce(\"usage_client_identity.client_session_id\", '')");
    }

    @Test
    void nullableSourceKeyIsRefusedEvenWithWhere() {
        target("parents", List.of("parent_deployment"),
                column("parents", "parent_deployment", QueryFieldType.STRING),
                column("parents", "calls", QueryFieldType.LONG));

        assertRefusedInto("parents", "INSERT INTO parents (parent_deployment, calls) SELECT parent_deployment, count(*) "
                + "FROM dial_usage_log WHERE parent_deployment IS NOT NULL GROUP BY 1", "can be NULL", "coalesce(parent_deployment, '')");
    }

    @Test
    void keyOverNonNullableEnrichmentColumnIsRefused() {
        target("clients", List.of("client_type"),
                column("clients", "client_type", QueryFieldType.STRING),
                column("clients", "calls", QueryFieldType.LONG));

        assertRefusedInto("clients", "INSERT INTO clients (client_type, calls) "
                        + "SELECT coalesce(\"usage_client_identity.client_type\", ''), count(*) FROM dial_usage_log GROUP BY 1",
                "'usage_client_identity.client_type'", "enrichment '" + ENRICHMENT + "'", "type default rather than as NULL");
    }

    @Test
    void keyOfDifferentTypeThanItsTargetColumnIsRefused() {
        target("by_status", List.of("status"),
                column("by_status", "status", QueryFieldType.LONG),
                column("by_status", "calls", QueryFieldType.LONG));

        assertRefusedInto("by_status", "INSERT INTO by_status (status, calls) SELECT deployment, count(*) FROM dial_usage_log GROUP BY 1",
                "group key 'status'", "'string'", "'long'");
    }

    @Test
    void rowExpandingKeyIsRefused() {
        target("paths", List.of("hop"),
                column("paths", "hop", QueryFieldType.STRING),
                column("paths", "calls", QueryFieldType.LONG));

        assertRefusedInto("paths", "INSERT INTO paths (hop, calls) SELECT array_join(execution_path), count(*) FROM dial_usage_log GROUP BY 1",
                "group key 'hop'", "row-expanding function cannot form a group key");
    }

    // --- measures ----------------------------------------------------------------------------------

    @Test
    void caseAboveAggregateOverNonNullableEnrichmentColumnIsRefused() {
        target("typed", List.of("deployment"),
                column("typed", "deployment", QueryFieldType.STRING),
                column("typed", "has_type", QueryFieldType.LONG));

        assertRefusedInto("typed", "INSERT INTO typed (deployment, has_type) SELECT deployment, "
                        + "CASE WHEN max(\"usage_client_identity.client_type\") = '' THEN 0 ELSE 1 END FROM dial_usage_log GROUP BY 1",
                "measure 'has_type'", "'usage_client_identity.client_type'", "declared non-nullable");
    }

    @Test
    void timestampExpressionMeasureIntoDateColumnIsRefused() {
        target("latest_hour", List.of("deployment"),
                column("latest_hour", "deployment", QueryFieldType.STRING),
                column("latest_hour", "last_hour", QueryFieldType.DATE));

        assertRefusedInto("latest_hour", "INSERT INTO latest_hour (deployment, last_hour) "
                        + "SELECT deployment, max(date_trunc('hour', request_time)) FROM dial_usage_log GROUP BY 1",
                "measure 'last_hour'", "'timestamp'", "'date'", "same type");
    }

    @Test
    void stringExpressionMeasureIntoLongColumnIsRefused() {
        target("lowered", List.of("chat_id"),
                column("lowered", "chat_id", QueryFieldType.STRING),
                column("lowered", "last_deployment", QueryFieldType.LONG));

        assertRefusedInto("lowered", "INSERT INTO lowered (chat_id, last_deployment) "
                        + "SELECT chat_id, max(lower(deployment)) FROM dial_usage_log GROUP BY 1",
                "measure 'last_deployment'", "'string'", "'long'");
    }

    @Test
    void stringExpressionMeasureIntoStringColumnIsAccepted() {
        target("lowered", List.of("chat_id"),
                column("lowered", "chat_id", QueryFieldType.STRING),
                column("lowered", "last_deployment", QueryFieldType.STRING));

        validate("lowered", "INSERT INTO lowered (chat_id, last_deployment) "
                + "SELECT chat_id, max(lower(deployment)) FROM dial_usage_log GROUP BY 1");
    }

    @Test
    void fractionalExpressionIntoIntegerColumnIsRefused() {
        assertRefused(HEAD + "SELECT deployment, date_trunc('day', request_time), count(*), sum(total_tokens) / count(*) "
                + "FROM dial_usage_log GROUP BY 1, 2", "measure 'tokens'", "fractional");
    }

    @Test
    void avgIntoIntegerColumnIsRefusedAsOnTheFormPath() {
        assertRefused(HEAD + "SELECT deployment, date_trunc('day', request_time), count(*), avg(total_tokens) "
                + "FROM dial_usage_log GROUP BY 1, 2", "computes 'avg'", "fractional", "'decimal'");
    }

    @Test
    void countIntoDecimalColumnIsRefusedAsOnTheFormPath() {
        target("counts", List.of("deployment"),
                column("counts", "deployment", QueryFieldType.STRING),
                column("counts", "calls", QueryFieldType.DECIMAL));

        assertRefusedInto("counts", "INSERT INTO counts (deployment, calls) SELECT deployment, count(*) FROM dial_usage_log GROUP BY 1",
                "measure 'calls' returns long", "declared 'decimal'");
    }

    @Test
    void nullableEnrichmentMeasureIntoNonNullableColumnIsRefused() {
        target("latest", List.of("deployment"),
                column("latest", "deployment", QueryFieldType.STRING),
                column("latest", "last_client_ts", QueryFieldType.TIMESTAMP));

        assertRefusedInto("latest", "INSERT INTO latest (deployment, last_client_ts) "
                + "SELECT deployment, max(\"usage_client_identity.client_ts\") FROM dial_usage_log GROUP BY 1",
                "'last_client_ts'", "usage_client_identity.client_ts", "non-nullable");
    }

    @Test
    void caseWithNoElseIntoNonNullableColumnIsRefused() {
        assertRefused(HEAD + "SELECT deployment, date_trunc('day', request_time), count(*), "
                + "sum(CASE WHEN success THEN total_tokens END) FROM dial_usage_log GROUP BY 1, 2", "measure 'tokens' can yield NULL");
    }

    @Test
    void measureOverNonNullableEnrichmentColumnIsRefused() {
        assertRefused(HEAD + "SELECT deployment, date_trunc('day', request_time), count(*), "
                + "sum(length(\"usage_client_identity.client_type\")) FROM dial_usage_log GROUP BY 1, 2",
                "measure 'tokens'", "'usage_client_identity.client_type'", "declared non-nullable");
    }

    @Test
    void arrayMeasureOfTheWrongElementTypeIsRefused() {
        target("statuses", List.of("deployment"),
                column("statuses", "deployment", QueryFieldType.STRING),
                array("statuses", "codes", QueryFieldType.STRING));

        assertRefusedInto("statuses", "INSERT INTO statuses (deployment, codes) "
                + "SELECT deployment, group_uniq_array(response_status) FROM dial_usage_log GROUP BY 1", "'integer'", "'string'");
    }

    @Test
    void distinctOnGroupUniqArrayIsRefusedAsOnTheFormPath() {
        // min/max(DISTINCT x) is not asserted: the planner drops a quantifier that cannot change their result.
        target("deployments", List.of("chat_id"),
                column("deployments", "chat_id", QueryFieldType.STRING),
                array("deployments", "used", QueryFieldType.STRING));

        assertRefusedInto("deployments", "INSERT INTO deployments (chat_id, used) "
                + "SELECT chat_id, group_uniq_array(DISTINCT deployment) FROM dial_usage_log GROUP BY 1", "DISTINCT", "already returns");
    }

    // --- sensitivity, enrichments and the input ----------------------------------------------------

    @Test
    void hiddenSensitiveColumnIsA403ForAnUnclearedCaller() {
        // The un-cleared caller's model omits the sensitive column, so the statement fails to plan as an unknown column.
        given(frontEndCatalog.findModel(eq(INPUT), any())).willReturn(Optional.of(DialUsageLogTestModel.model()));
        given(columns.findByTableNameOrderByOrdinal(INPUT)).willReturn(List.of(
                column(INPUT, "deployment", QueryFieldType.STRING),
                sensitive(column(INPUT, "user_email", QueryFieldType.STRING))));
        given(sensitiveAccess.mayReadSensitive(anyString())).willReturn(false);
        given(sensitiveAccess.maySeeSensitive(anyString())).willReturn(false);

        assertThatThrownBy(() -> validate(HEAD + KEYS + "FROM dial_usage_log WHERE user_email = 'x' GROUP BY 1, 2"))
                .isInstanceOf(SensitiveColumnNotEntitledException.class)
                .hasMessageContaining("user_email");
    }

    @Test
    void visibleSensitiveColumnIsRefusedForCallerWhoMayNotReadIt() {
        given(sensitiveAccess.mayReadSensitive(INPUT)).willReturn(false);

        assertThatThrownBy(() -> validate(HEAD + KEYS + "FROM dial_usage_log WHERE user_hash <> '' GROUP BY 1, 2"))
                .isInstanceOf(SensitiveColumnNotEntitledException.class)
                .hasMessageContaining("user_hash");
    }

    @Test
    void sensitiveSourceColumnLandingInPlainTargetColumnIsRefused() {
        target("users", List.of("deployment"),
                column("users", "deployment", QueryFieldType.STRING),
                column("users", "last_user", QueryFieldType.STRING));

        assertRefusedInto("users", "INSERT INTO users (deployment, last_user) SELECT deployment, max(user_hash) FROM dial_usage_log GROUP BY 1",
                "'last_user'", "'user_hash'", "sensitive");
    }

    @Test
    void sensitiveColumnOnlyInCaseConditionIsNotLineage() {
        target("hashed", List.of("deployment"),
                column("hashed", "deployment", QueryFieldType.STRING),
                column("hashed", "hashed_calls", QueryFieldType.LONG));

        validate("hashed", "INSERT INTO hashed (deployment, hashed_calls) "
                + "SELECT deployment, sum(CASE WHEN user_hash <> '' THEN 1 ELSE 0 END) FROM dial_usage_log GROUP BY 1");
    }

    @Test
    void sensitiveColumnInKeyCaseConditionIsLineage() {
        target("segments", List.of("segment"),
                column("segments", "segment", QueryFieldType.STRING),
                column("segments", "calls", QueryFieldType.LONG));

        assertRefusedInto("segments", "INSERT INTO segments (segment, calls) "
                        + "SELECT CASE WHEN user_hash LIKE '%@example.com%' THEN 'internal' ELSE 'external' END, count(*) FROM dial_usage_log GROUP BY 1",
                "'segment'", "'user_hash'", "sensitive");
    }

    @Test
    void sensitiveColumnInCaseAboveAggregateIsLineage() {
        target("hashed", List.of("deployment"),
                column("hashed", "deployment", QueryFieldType.STRING),
                column("hashed", "late_hash", QueryFieldType.LONG));

        assertRefusedInto("hashed", "INSERT INTO hashed (deployment, late_hash) "
                        + "SELECT deployment, CASE WHEN max(user_hash) > 'm' THEN 1 ELSE 0 END FROM dial_usage_log GROUP BY 1",
                "'late_hash'", "'user_hash'", "sensitive");
    }

    @Test
    void unplannableStatementNamingTwoSourcesIsA403OverEach() {
        // With no declared input and two source tables in the text, which one it reads is unknown; the gate runs over both.
        given(frontEndCatalog.findModel(eq(INPUT), any())).willReturn(Optional.of(DialUsageLogTestModel.model()));
        given(definitions.findById("turns")).willReturn(Optional.of(table("turns", TableType.SOURCE)));
        given(columns.findByTableNameOrderByOrdinal(INPUT)).willReturn(List.of(
                column(INPUT, "deployment", QueryFieldType.STRING),
                sensitive(column(INPUT, "user_email", QueryFieldType.STRING))));
        given(sensitiveAccess.mayReadSensitive(anyString())).willReturn(false);
        given(sensitiveAccess.maySeeSensitive(anyString())).willReturn(false);

        assertThatThrownBy(() -> validate(HEAD + KEYS + "FROM dial_usage_log WHERE user_email = 'x' "
                + "AND chat_id IN (SELECT chat_id FROM turns) GROUP BY 1, 2"))
                .isInstanceOf(SensitiveColumnNotEntitledException.class)
                .hasMessageContaining("user_email");
    }

    @Test
    void nonClampableEnrichmentIsRefused() {
        given(freshness.classify(any(), eq(INPUT))).willReturn(Map.of(ENRICHMENT, EnrichmentFreshness.Clampability.GROUP_TRIGGER));
        target("sessions_rollup", List.of("client_session_id"),
                column("sessions_rollup", "client_session_id", QueryFieldType.STRING),
                column("sessions_rollup", "requests", QueryFieldType.LONG));

        assertRefusedInto("sessions_rollup", "INSERT INTO sessions_rollup (client_session_id, requests) "
                        + "SELECT coalesce(\"usage_client_identity.client_session_id\", ''), count(*) FROM dial_usage_log GROUP BY 1",
                "enrichment '" + ENRICHMENT + "'", "group trigger");
    }

    @Test
    void inputWithNoCursorColumnsIsRefused() {
        given(definitions.findById(INPUT)).willReturn(Optional.of(table(INPUT, TableType.SOURCE)));

        assertRefused(HEAD + KEYS + "FROM dial_usage_log GROUP BY 1, 2", "not scannable");
    }
}
