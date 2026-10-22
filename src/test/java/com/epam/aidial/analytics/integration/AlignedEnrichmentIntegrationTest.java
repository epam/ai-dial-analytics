package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.service.enrichment.support.PartitionEcho;
import com.epam.aidial.analytics.service.query.CatalogQueryService;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.model.ArrayExpr;
import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.ComparisonOp;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.query.model.LogicalNode;
import com.epam.aidial.analytics.service.query.model.LogicalOp;
import com.epam.aidial.analytics.service.query.model.OffsetPage;
import com.epam.aidial.analytics.service.query.model.OutputColumn;
import com.epam.aidial.analytics.service.query.model.QueryMode;
import com.epam.aidial.analytics.service.query.model.SortDir;
import com.epam.aidial.analytics.service.query.model.SortItem;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.ValueType;
import com.epam.aidial.analytics.service.query.translate.StructuredQueryBuilder;
import com.epam.aidial.analytics.web.security.Privilege;
import com.jayway.jsonpath.JsonPath;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SelectQuery;
import org.jooq.conf.ParamType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.epam.aidial.analytics.integration.RunnerCalls.runnerPost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The aligned layout of a row-grain enrichment of a partitioned source (capability table-management, "An eligible
 * row-grain enrichment is laid out aligned with its source"), its writers, its tuple join and the partition
 * pre-selection a lookup on it adds (structured-query, "A lookup on an aligned enrichment pre-selects the source's
 * partitions"), against a real ClickHouse.
 */
class AlignedEnrichmentIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String SOURCE = "dial_usage_log";
    private static final String IDENT = "al_ident";
    private static final String TODAY = FIXTURE_DATE;
    private static final String YESTERDAY = LocalDate.now(ZoneOffset.UTC).minusDays(1).toString();
    private static final String TWO_DAYS_AGO = LocalDate.now(ZoneOffset.UTC).minusDays(2).toString();

    @Autowired
    private CatalogQueryService catalog;

    @Autowired
    private StructuredQueryBuilder builder;

    @Autowired
    private DSLContext renderContext;

    @BeforeEach
    void setUp() {
        truncate();
    }

    @AfterEach
    void tearDown() throws Exception {
        mockMvc.perform(delete("/v1/pipelines/al-rollup"));
        mockMvc.perform(delete("/v1/tables/al_rollup"));
        for (final String table : List.of(IDENT, "al_group", "al_adopt", "al_ttl", "al_bag", "al_sup", "al_llm")) {
            mockMvc.perform(delete("/v1/pipelines/" + table.replace('_', '-')));
            mockMvc.perform(delete("/v1/tables/" + table));
        }
    }

    private void identity() throws Exception {
        IntegrationTables.enrichment(mockMvc, IDENT, SOURCE, "event_id",
                "[{\"source_name\":\"session\",\"name\":\"session\",\"type\":\"string\",\"nullable\":true}]");
    }

    /** One usage-log row at {@code day} {@code time}, returning its event id. */
    private String seedSource(String day, String time, String trace) {
        return seedSource(UUID.randomUUID().toString(), day, time, trace);
    }

    private String seedSource(String eventId, String day, String time, String trace) {
        // An _ingested_at well past the safety lag, so a materialize or a scan reaches the row at once.
        execute("INSERT INTO dial_usage_log (event_id, project_id, deployment, request_time, trace_id, core_span_id,"
                + " _ingested_at) VALUES (toUUID('" + eventId + "'), 'p', 'd', '" + day + " " + time + "', '" + trace
                + "', 's', '2020-01-01 00:00:01.000')");
        return eventId;
    }

    /** The aligned identity row of {@code eventId}'s source row on {@code day}, keyed to {@code session}. */
    private void seedIdentity(String eventId, String day, String session) {
        execute("INSERT INTO " + IDENT + " (event_id, request_time, session) SELECT event_id, request_time, '" + session
                + "' FROM dial_usage_log WHERE event_id = toUUID('" + eventId + "') AND toDate(request_time) = '" + day + "'");
    }

    // ------------------------------------------------------------------------------------------- layout

    @Test
    void eligibleEnrichmentIsCreatedAlignedWithItsColumnHiddenAndTheSourcesTtl() throws Exception {
        identity();

        final String ddl = showCreateTable(IDENT);
        assertThat(ddl).contains("PARTITION BY toYYYYMMDD(request_time)")
                .contains("`request_time` DateTime64(3, 'UTC')")
                .contains("ORDER BY (event_id)")
                .contains("TTL toDateTime(request_time) + toIntervalYear(1)")
                .contains("ttl_only_drop_parts = 1");
        mockMvc.perform(get("/v1/tables/" + IDENT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partition_by.column").value("request_time"))
                .andExpect(jsonPath("$.partition_by.granularity").value("day"))
                .andExpect(jsonPath("$.columns[*].source_name", not(hasItem("request_time"))))
                .andExpect(jsonPath("$.ttl").doesNotExist());
        final QueryEntityModel model = catalog.findModel(SOURCE, Privilege.TABLE_READ).orElseThrow();
        assertThat(model.bindings()).doesNotContainKey(IDENT + ".request_time").containsKey(IDENT + ".session");
    }

    @Test
    void groupGrainEnrichmentIsLaidOutAsBefore() throws Exception {
        IntegrationTables.enrichment(mockMvc, "al_group", SOURCE, "chat_id",
                "[{\"source_name\":\"title\",\"name\":\"title\",\"type\":\"string\",\"nullable\":true}]");

        assertThat(showCreateTable("al_group")).doesNotContain("PARTITION BY").doesNotContain("request_time");
        mockMvc.perform(get("/v1/tables/al_group")).andExpect(jsonPath("$.partition_by").doesNotExist());
    }

    @Test
    void matchingDeclaredColumnIsAdoptedWithItsDescription() throws Exception {
        IntegrationTables.enrichment(mockMvc, "al_adopt", SOURCE, "event_id",
                "[{\"source_name\":\"request_time\",\"name\":\"request_time\",\"type\":\"timestamp\",\"nullable\":false,"
                        + "\"description\":\"kept as declared\"},"
                        + "{\"source_name\":\"baggage\",\"name\":\"baggage\",\"type\":\"string\",\"nullable\":true}]");

        final String ddl = showCreateTable("al_adopt");
        assertThat(ddl).contains("PARTITION BY toYYYYMMDD(request_time)");
        assertThat(ddl.split("`request_time`", -1)).hasSize(2);
        mockMvc.perform(get("/v1/tables/al_adopt"))
                .andExpect(jsonPath("$.columns[?(@.source_name=='request_time')].description").value(hasItem("kept as declared")))
                .andExpect(jsonPath("$.partition_by.column").value("request_time"));
    }

    @Test
    void mismatchedDeclaredColumnIsRefused() throws Exception {
        mockMvc.perform(post("/v1/tables").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"al_adopt\",\"type\":\"enrichment\",\"source_table\":\"" + SOURCE + "\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/v1/tables/al_adopt/schema").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grain_key\":\"event_id\",\"columns\":[{\"source_name\":\"request_time\","
                                + "\"name\":\"request_time\",\"type\":\"string\"}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andExpect(jsonPath("$.message", containsString("alignment column")));
    }

    @Test
    void declaredTtlOverridesTheInheritedOneEvenOnTheHiddenColumn() throws Exception {
        mockMvc.perform(post("/v1/tables").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"al_ttl\",\"type\":\"enrichment\",\"source_table\":\"" + SOURCE + "\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/v1/tables/al_ttl/schema").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grain_key\":\"event_id\",\"ttl\":{\"column\":\"request_time\",\"interval\":90,"
                                + "\"unit\":\"day\"},\"columns\":[{\"source_name\":\"x\",\"name\":\"x\",\"type\":\"string\"}]}"))
                .andExpect(status().isOk());

        assertThat(showCreateTable("al_ttl")).contains("TTL toDateTime(request_time) + toIntervalDay(90)")
                .doesNotContain("ttl_only_drop_parts");
    }

    @Test
    void patchCannotAddTheHiddenAlignmentColumnsName() throws Exception {
        identity();

        mockMvc.perform(patch("/v1/tables/" + IDENT + "/schema").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"add\":[{\"source_name\":\"request_time\",\"name\":\"request_time\",\"type\":\"timestamp\"}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message", containsString("alignment column")));
    }

    // ------------------------------------------------------------------------------------------- join

    @Test
    void sourceRowsSharingAnIdentityAcrossDaysDoNotFanOut() throws Exception {
        identity();
        final String eventId = UUID.randomUUID().toString();
        seedSource(eventId, TODAY, "10:00:00.000", "t-today");
        seedSource(eventId, YESTERDAY, "10:00:00.000", "t-yesterday");
        seedIdentity(eventId, TODAY, "s-today");
        seedIdentity(eventId, YESTERDAY, "s-yesterday");

        mockMvc.perform(post("/v1/queries/execute").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entity\":\"" + SOURCE + "\",\"mode\":\"row\",\"select\":["
                                + "{\"expr\":{\"type\":\"field\",\"name\":\"trace_id\"}},"
                                + "{\"expr\":{\"type\":\"field\",\"name\":\"" + IDENT + ".session\"}}],"
                                + "\"sort\":[{\"field\":\"trace_id\",\"dir\":\"asc\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows", hasSize(2)))
                .andExpect(jsonPath("$.rows[0]['" + IDENT + ".session']").value("s-today"))
                .andExpect(jsonPath("$.rows[1]['" + IDENT + ".session']").value("s-yesterday"));
    }

    // ------------------------------------------------------------------------------------------- writers

    @Test
    void rowsApiRequiresTheAlignmentColumnAndWrongValuesOnlyOrphanTheirRow() throws Exception {
        identity();
        final String eventId = seedSource(TODAY, "10:00:00.000", "t1");

        mockMvc.perform(post("/v1/tables/" + IDENT + "/rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"event_id\":\"" + eventId + "\",\"session\":\"s\"}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andExpect(jsonPath("$.message", containsString("request_time")));
        mockMvc.perform(post("/v1/tables/" + IDENT + "/rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"event_id\":\"" + eventId + "\",\"request_time\":\"" + YESTERDAY
                                + "T10:00:00.000Z\",\"session\":\"wrong-day\"}]}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/v1/queries/execute").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entity\":\"" + SOURCE + "\",\"mode\":\"row\",\"select\":["
                                + "{\"expr\":{\"type\":\"field\",\"name\":\"" + IDENT + ".session\"}}]}"))
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0]['" + IDENT + ".session']").doesNotExist());
    }

    private void sqlPipeline(String target, String outputsJson) throws Exception {
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + target.replace('_', '-') + "\",\"kind\":\"enrich\",\"target\":\"" + target
                                + "\",\"transform\":{\"type\":\"sql\",\"outputs\":" + outputsJson + "},"
                                + "\"trigger\":{\"kind\":\"schedule\"},\"enabled\":true}"))
                .andExpect(status().isCreated());
    }

    private void materialize(String target) throws Exception {
        mockMvc.perform(runnerPost("/v1/pipelines/" + target.replace('_', '-') + "/materialize")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":100}"))
                .andExpect(status().isOk());
    }

    @Test
    void materializeWritesTheSourceRowsPartitionValue() throws Exception {
        identity();
        seedSource(TODAY, "10:00:00.000", "t1");
        seedSource(YESTERDAY, "11:00:00.000", "t2");
        sqlPipeline(IDENT, "{\"session\":\"trace_id\"}");

        materialize(IDENT);

        assertThat(queryScalar("SELECT toString(count()) AS c FROM " + IDENT + " AS e INNER JOIN dial_usage_log AS s "
                + "ON e.event_id = s.event_id AND e.request_time = s.request_time", "c")).isEqualTo("2");
        assertThat(queryScalar("SELECT toString(countIf(toYYYYMMDD(request_time) = 19700101)) AS c FROM " + IDENT, "c"))
                .isEqualTo("0");
    }

    /** A request-baggage shape: the alignment column bound to the source column itself. */
    @Test
    void pipelineBindingTheAlignmentColumnToTheSourceColumnWritesWhatItAlwaysWrote() throws Exception {
        IntegrationTables.enrichment(mockMvc, "al_bag", SOURCE, "event_id",
                "[{\"source_name\":\"baggage\",\"name\":\"baggage\",\"type\":\"string\",\"nullable\":true},"
                        + "{\"source_name\":\"request_time\",\"name\":\"request_time\",\"type\":\"timestamp\",\"nullable\":false}]");
        seedSource(TODAY, "10:00:00.000", "t1");
        seedSource(YESTERDAY, "11:00:00.000", "t2");
        sqlPipeline("al_bag", "{\"baggage\":\"trace_id\",\"request_time\":\"request_time\"}");

        materialize("al_bag");

        assertThat(queryText("SELECT e.baggage, toString(e.request_time) FROM al_bag AS e FINAL INNER JOIN dial_usage_log AS s"
                + " ON e.event_id = s.event_id AND e.request_time = s.request_time ORDER BY e.baggage").lines().skip(2))
                .containsExactly("t1\t" + TODAY + " 10:00:00.000", "t2\t" + YESTERDAY + " 11:00:00.000");
    }

    @Test
    void bindingTheAlignmentColumnToAnythingElseIsSuperseded() throws Exception {
        IntegrationTables.enrichment(mockMvc, "al_sup", SOURCE, "event_id",
                "[{\"source_name\":\"label\",\"name\":\"label\",\"type\":\"string\",\"nullable\":true},"
                        + "{\"source_name\":\"request_time\",\"name\":\"request_time\",\"type\":\"timestamp\",\"nullable\":false}]");
        seedSource(TODAY, "10:00:00.000", "t1");
        sqlPipeline("al_sup", "{\"label\":\"trace_id\",\"request_time\":\"date_add('day', -3, request_time)\"}");

        materialize("al_sup");

        assertThat(queryScalar("SELECT toString(e.request_time) AS v FROM al_sup AS e", "v"))
                .isEqualTo(TODAY + " 10:00:00.000");
    }

    @Test
    void llmScanCarriesThePartitionValueAndTheEchoedWriteBackJoins() throws Exception {
        IntegrationTables.enrichment(mockMvc, "al_llm", SOURCE, "event_id",
                "[{\"source_name\":\"score\",\"name\":\"score\",\"type\":\"long\",\"nullable\":true}]");
        final String eventId = seedSource(TODAY, "10:00:00.000", "t1");
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"al-llm\"," + Transforms.llm().output("score", "A score.").member()
                                + ",\"kind\":\"enrich\",\"target\":\"al_llm\",\"trigger\":{\"kind\":\"schedule\"},\"enabled\":true}"))
                .andExpect(status().isCreated());

        final String page = mockMvc.perform(runnerPost("/v1/pipelines/al-llm/scan")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":10}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        final List<Object> echoed = JsonPath.read(page, "$.rows[*]['" + PartitionEcho.MEMBER + "']");
        assertThat(echoed).hasSize(1);
        final String partition = String.valueOf(echoed.getFirst());

        mockMvc.perform(runnerPost("/v1/pipelines/al-llm/rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"event_id\":\"" + eventId + "\",\"score\":7}]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"));
        mockMvc.perform(runnerPost("/v1/pipelines/al-llm/rows").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"event_id\":\"" + eventId + "\",\"" + PartitionEcho.MEMBER + "\":\""
                                + partition + "\",\"score\":7}]}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/v1/queries/execute").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entity\":\"" + SOURCE + "\",\"mode\":\"row\",\"select\":["
                                + "{\"expr\":{\"type\":\"field\",\"name\":\"al_llm.score\"}}]}"))
                .andExpect(jsonPath("$.rows[0]['al_llm.score']").value(7));
    }

    // ------------------------------------------------------------------------------------------- pre-selection

    private static StructuredQuery lookup(FilterNode filter) {
        return new StructuredQuery(SOURCE, filter, QueryMode.ROW, false,
                List.of(new OutputColumn(new FieldExpr("trace_id"), null), new OutputColumn(new FieldExpr(IDENT + ".session"), null)),
                null, null, List.of(new SortItem("trace_id", SortDir.ASC, null)), new OffsetPage(0, 100, false));
    }

    private static FilterNode eq(String field, String value) {
        return new ComparisonNode(ComparisonOp.EQ, List.of(new FieldExpr(field), new ValueExpr(ValueType.STRING, value)));
    }

    /** Seeds session {@code s1} on two of three days, others on every day. */
    private void seedSessions() throws Exception {
        identity();
        int n = 0;
        for (final String day : List.of(TODAY, YESTERDAY, TWO_DAYS_AGO)) {
            for (int i = 0; i < 3; i++) {
                final String id = seedSource(day, "1%d:00:00.000".formatted(i), "t-" + day + "-" + i);
                seedIdentity(id, day, i == 0 && !day.equals(YESTERDAY) ? "s1" : "other-" + n++);
            }
        }
    }

    private String run(StructuredQuery query, SelectQuery<Record> select, String comment) {
        final QueryEntityModel model = catalog.findModel(SOURCE, Privilege.TABLE_READ).orElseThrow();
        final boolean partitionLocal = model.partitionLocal() && builder.readsFinal(model, query);
        return queryText(select.getSQL(ParamType.INLINED) + " SETTINGS log_comment = '" + comment + "'"
                + (partitionLocal ? ", do_not_merge_across_partitions_select_final = 1" : ""));
    }

    /** Whether {@code select}'s plan reads some table with partitions pruned: a {@code Parts: x/y} line with x < y. */
    private boolean prunesPartitions(SelectQuery<Record> select) {
        final String plan = queryText("EXPLAIN indexes = 1 " + select.getSQL(ParamType.INLINED));
        final Matcher parts = Pattern.compile("Parts: (\\d+)/(\\d+)").matcher(plan);
        while (parts.find()) {
            if (Integer.parseInt(parts.group(1)) < Integer.parseInt(parts.group(2))) {
                return true;
            }
        }
        return false;
    }

    @Test
    void lookupPreSelectsTheSessionsDaysAndReturnsTheUnrewrittenRows() throws Exception {
        seedSessions();
        final QueryEntityModel model = catalog.findModel(SOURCE, Privilege.TABLE_READ).orElseThrow();
        final StructuredQuery query = lookup(eq(IDENT + ".session", "s1"));
        final SelectQuery<Record> shaped = builder.build(renderContext, model, query, StructuredQueryBuilder.MAX_LIMIT);
        final SelectQuery<Record> plain =
                builder.withoutReadShaping().build(renderContext, model, query, StructuredQueryBuilder.MAX_LIMIT);
        assertThat(shaped.getSQL(ParamType.INLINED))
                .contains("toYYYYMMDD(\"dial_usage_log\".\"request_time\") IN (SELECT DISTINCT toYYYYMMDD(\"" + IDENT
                        + "\".\"request_time\") FROM \"" + IDENT + "\" WHERE \"" + IDENT + "\".\"session\" = 's1' SETTINGS final = 0)");

        final String comment = UUID.randomUUID().toString();
        final String rewritten = run(query, shaped, comment + "-shaped");
        assertThat(rewritten).isEqualTo(run(query, plain, comment + "-plain"));
        assertThat(rewritten.lines().skip(2)).hasSize(2).allMatch(line -> line.endsWith("\ts1"));
        // Plan-level, not read-count: on a fixture this small the pre-selection's own read outweighs what it prunes;
        // what is asserted is that the source and the enrichment are pruned to the session's days at all.
        assertThat(prunesPartitions(shaped)).as("rewritten statement prunes partitions").isTrue();
        assertThat(prunesPartitions(plain)).as("unrewritten statement prunes partitions").isFalse();
    }

    @Test
    void inListLookupQualifiesAndDefaultOrNegatedLookupsDoNot() throws Exception {
        seedSessions();
        final QueryEntityModel model = catalog.findModel(SOURCE, Privilege.TABLE_READ).orElseThrow();
        final StructuredQuery in = lookup(new ComparisonNode(ComparisonOp.IN, List.of(new FieldExpr(IDENT + ".session"),
                new ArrayExpr(List.of(new ValueExpr(ValueType.STRING, "s1"), new ValueExpr(ValueType.STRING, "other-0"))))));
        final StructuredQuery empty = lookup(eq(IDENT + ".session", ""));
        final StructuredQuery negated = lookup(new ComparisonNode(ComparisonOp.NE,
                List.of(new FieldExpr(IDENT + ".session"), new ValueExpr(ValueType.STRING, "s1"))));

        for (final StructuredQuery query : List.of(in, empty, negated)) {
            final SelectQuery<Record> shaped = builder.build(renderContext, model, query, StructuredQueryBuilder.MAX_LIMIT);
            final SelectQuery<Record> plain =
                    builder.withoutReadShaping().build(renderContext, model, query, StructuredQueryBuilder.MAX_LIMIT);
            final String comment = UUID.randomUUID().toString();
            assertThat(run(query, shaped, comment)).as(query.filter().toString()).isEqualTo(run(query, plain, comment));
            assertThat(shaped.getSQL(ParamType.INLINED).contains("SETTINGS final = 0")).as(query.filter().toString())
                    .isEqualTo(query == in);
        }
    }

    // ------------------------------------------------------------------------------------------- window transfer

    private static FilterNode since(String day) {
        return new ComparisonNode(ComparisonOp.GE,
                List.of(new FieldExpr("request_time"), new ValueExpr(ValueType.STRING, day + " 00:00:00")));
    }

    private static FilterNode and(FilterNode... args) {
        return new LogicalNode(LogicalOp.AND, List.of(args));
    }

    @Test
    void windowOnlyReadTransfersItsWindowAndReturnsTheKeyedRows() throws Exception {
        // Three days of sessions, the window covering the last two: the enrichment holds rows the window excludes.
        seedSessions();
        final QueryEntityModel model = catalog.findModel(SOURCE, Privilege.TABLE_READ).orElseThrow();
        for (final FilterNode filter : List.of(since(YESTERDAY), and(since(YESTERDAY), eq(IDENT + ".session", "s1")))) {
            final StructuredQuery query = lookup(filter);
            final SelectQuery<Record> transferred = builder.build(renderContext, model, query, StructuredQueryBuilder.MAX_LIMIT);
            final SelectQuery<Record> keyed =
                    builder.withoutWindowTransfer().build(renderContext, model, query, StructuredQueryBuilder.MAX_LIMIT);
            assertThat(transferred.getSQL(ParamType.INLINED)).as(filter.toString())
                    .contains("\"" + IDENT + "\".\"request_time\" >=")
                    .doesNotContain("\"" + IDENT + "\".\"event_id\" IN (select")
                    .doesNotContain("\"" + IDENT + "\".\"request_time\" IN (select");
            assertThat(keyed.getSQL(ParamType.INLINED)).as(filter.toString())
                    .contains("\"" + IDENT + "\".\"event_id\" IN (select")
                    .contains("\"" + IDENT + "\".\"request_time\" IN (select");

            final String comment = UUID.randomUUID().toString();
            final String rows = run(query, transferred, comment + "-transferred");
            assertThat(rows).as(filter.toString()).isEqualTo(run(query, keyed, comment + "-keyed"));
            assertThat(rows.lines().skip(2)).as(filter.toString()).hasSize(filter instanceof ComparisonNode ? 6 : 1);
        }
    }

    @Test
    void windowBesideAnotherSourceConjunctKeepsBothKeySets() throws Exception {
        seedSessions();
        final QueryEntityModel model = catalog.findModel(SOURCE, Privilege.TABLE_READ).orElseThrow();
        final StructuredQuery query = lookup(and(since(YESTERDAY), eq("trace_id", "t-" + TODAY + "-0")));

        final SelectQuery<Record> select = builder.build(renderContext, model, query, StructuredQueryBuilder.MAX_LIMIT);

        assertThat(select.getSQL(ParamType.INLINED)).contains("\"" + IDENT + "\".\"event_id\" IN (select")
                .contains("\"" + IDENT + "\".\"request_time\" IN (select");
        assertThat(run(query, select, UUID.randomUUID().toString()).lines().skip(2)).containsExactly("t-" + TODAY + "-0\ts1");
    }

    @Test
    void aggregateRecomputeThroughTheAlignedEnrichmentPreSelectsAndCountsEveryRow() throws Exception {
        seedSessions();
        IntegrationTables.upsertSource(mockMvc, "al_rollup",
                "[{\"source_name\":\"session\",\"name\":\"session\",\"type\":\"string\"},"
                        + "{\"source_name\":\"events\",\"name\":\"events\",\"type\":\"long\"}]", "[\"session\"]");
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"al-rollup\",\"kind\":\"aggregate\",\"target\":\"al_rollup\",\"inputs\":[\""
                                + SOURCE + "\"],\"filter\":\"length(\\\"" + IDENT + ".session\\\") > 0\","
                                + "\"group_by\":[{\"column\":\"" + IDENT + ".session\",\"as\":\"session\"}],"
                                + "\"measures\":[{\"name\":\"events\",\"fn\":\"count\"}],"
                                + "\"trigger\":{\"kind\":\"schedule\",\"cron\":\"0 0 * * * *\"}}"))
                .andExpect(status().isCreated());
        mockMvc.perform(patch("/v1/pipelines/al-rollup").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/v1/pipelines/al-rollup/refresh")).andExpect(status().isOk());

        assertThat(queryText("SELECT session, events FROM al_rollup FINAL WHERE session = 's1'").lines().skip(2))
                .containsExactly("s1\t2");
        assertThat(queryScalar("SELECT toString(count()) AS c FROM al_rollup FINAL", "c")).isEqualTo("8");
        execute("SYSTEM FLUSH LOGS");
        assertThat(queryText("SELECT substring(query, 1, 4000) FROM system.query_log WHERE type = 'QueryFinish' "
                + "AND query ILIKE 'INSERT INTO%al_rollup%'")).contains("SETTINGS final = 0");

        // The group key's lookup index: added after the registration committed, never materialized, kept on delete.
        final String index = "SELECT toString(count()) AS c FROM system.data_skipping_indices WHERE database = "
                + "currentDatabase() AND table = '" + IDENT + "' AND name = 'idx_session' AND type_full = 'bloom_filter(0.001)'";
        assertThat(queryScalar(index, "c")).isEqualTo("1");
        assertThat(queryScalar("SELECT toString(count()) AS c FROM system.mutations WHERE database = currentDatabase() "
                + "AND table = '" + IDENT + "' AND command ILIKE '%MATERIALIZE INDEX%'", "c")).isEqualTo("0");
        mockMvc.perform(delete("/v1/pipelines/al-rollup")).andExpect(status().isNoContent());
        assertThat(queryScalar(index, "c")).isEqualTo("1");
    }

    @Test
    void groupingBySourceColumnAddsNoLookupIndex() throws Exception {
        IntegrationTables.enrichment(mockMvc, "al_group", SOURCE, "event_id",
                "[{\"source_name\":\"title\",\"name\":\"title\",\"type\":\"string\",\"nullable\":true}]");
        IntegrationTables.source(mockMvc, "al_rollup",
                "[{\"source_name\":\"title\",\"name\":\"title\",\"type\":\"string\"}]", "[\"title\"]");
        // The group key is the source's own column: the enrichment beside it is aligned, but nothing looks it up.
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"al-rollup\",\"kind\":\"aggregate\",\"target\":\"al_rollup\",\"inputs\":[\""
                                + SOURCE + "\"],\"group_by\":[{\"column\":\"deployment\",\"as\":\"title\"}],"
                                + "\"measures\":[],\"trigger\":{\"kind\":\"schedule\",\"cron\":\"0 0 * * * *\"}}"))
                .andExpect(status().isCreated());

        assertThat(queryScalar("SELECT toString(count()) AS c FROM system.data_skipping_indices WHERE database = "
                + "currentDatabase() AND table IN ('al_group', 'al_rollup')", "c")).isEqualTo("0");
    }

    /** A fresh environment holding only the system tables: import re-derives the aligned layout from its own source. */
    @Test
    void exportedAlignedEnrichmentsImportAlignedWithTheirColumnsAsTheyWere() throws Exception {
        identity();
        IntegrationTables.enrichment(mockMvc, "al_bag", SOURCE, "event_id",
                "[{\"source_name\":\"baggage\",\"name\":\"baggage\",\"type\":\"string\",\"nullable\":true},"
                        + "{\"source_name\":\"request_time\",\"name\":\"request_time\",\"type\":\"timestamp\",\"nullable\":false,"
                        + "\"description\":\"drives the TTL\"}]");
        sqlPipeline(IDENT, "{\"session\":\"trace_id\"}");
        sqlPipeline("al_bag", "{\"baggage\":\"trace_id\",\"request_time\":\"request_time\"}");
        final String identColumns = JsonPath.read(mockMvc.perform(get("/v1/tables/" + IDENT)).andReturn().getResponse()
                .getContentAsString(), "$.columns").toString();
        final String bagColumns = JsonPath.read(mockMvc.perform(get("/v1/tables/al_bag")).andReturn().getResponse()
                .getContentAsString(), "$.columns").toString();
        final String bundle = mockMvc.perform(post("/v1/catalog/export").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"components\":[{\"type\":\"pipeline\",\"name\":\"al-ident\"},"
                                + "{\"type\":\"pipeline\",\"name\":\"al-bag\"}]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(bundle).doesNotContain("partition_by");
        for (final String table : List.of(IDENT, "al_bag")) {
            mockMvc.perform(delete("/v1/pipelines/" + table.replace('_', '-'))).andExpect(status().isNoContent());
            mockMvc.perform(delete("/v1/tables/" + table)).andExpect(status().isNoContent());
        }

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/v1/catalog/import")
                        .file(new org.springframework.mock.web.MockMultipartFile("file", "bundle.json",
                                MediaType.APPLICATION_JSON_VALUE, bundle.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .param("resolution_policy", "FAIL_IF_EXISTS")
                        // This environment ran the pipelines a moment ago; a fresh one would not need it.
                        .param("acknowledge_reused_names", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("completed"));

        for (final String table : List.of(IDENT, "al_bag")) {
            assertThat(showCreateTable(table)).as(table).contains("PARTITION BY toYYYYMMDD(request_time)");
            mockMvc.perform(get("/v1/tables/" + table)).andExpect(jsonPath("$.layout").value("aligned"));
        }
        assertThat(JsonPath.read(mockMvc.perform(get("/v1/tables/" + IDENT)).andReturn().getResponse()
                .getContentAsString(), "$.columns").toString()).isEqualTo(identColumns);
        assertThat(JsonPath.read(mockMvc.perform(get("/v1/tables/al_bag")).andReturn().getResponse()
                .getContentAsString(), "$.columns").toString()).isEqualTo(bagColumns);
        assertThat(showCreateTable("al_bag").split("`request_time`", -1)).hasSize(2);
    }
}
