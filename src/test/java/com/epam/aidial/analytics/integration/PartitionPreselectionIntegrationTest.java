package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.service.query.CatalogQueryService;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
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
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.epam.aidial.analytics.integration.RunnerCalls.runnerPost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The partition pre-selection a lookup on an aligned enrichment adds (structured-query, "A lookup on an aligned
 * enrichment pre-selects the source's partitions"; enrichment-runtime, "Group member selection pre-selects partitions
 * from an aligned group key"; aggregate-pipeline, "A refresh restricted through an aligned enrichment pre-selects
 * partitions") on every path that builds one, against a real ClickHouse: each rewritten statement returns what the
 * unrewritten one does, and prunes what it claims to.
 */
class PartitionPreselectionIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String SOURCE = "dial_usage_log";
    private static final String IDENT = "pp_ident";
    private static final String SESSION = IDENT + ".session_id";
    private static final String CHAT = "pp_chat";
    private static final String BODY = "dial_usage_log_payload.request_body";
    private static final String PRESELECTION = "SETTINGS final = 0";
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
    void setUp() throws Exception {
        truncate();
        IntegrationTables.enrichment(mockMvc, IDENT, SOURCE, "event_id",
                "[{\"source_name\":\"session_id\",\"name\":\"session_id\",\"type\":\"string\",\"nullable\":true}]");
        IntegrationTables.enrichment(mockMvc, CHAT, SOURCE, "chat_id",
                "[{\"source_name\":\"title\",\"name\":\"title\",\"type\":\"string\",\"nullable\":true}]");
    }

    @AfterEach
    void tearDown() throws Exception {
        for (final String pipeline : List.of("pp-group", "pp-form", "pp-sql")) {
            mockMvc.perform(delete("/v1/pipelines/" + pipeline));
        }
        for (final String table : List.of("pp_label", "pp_spine", "pp_form", "pp_sql", IDENT, CHAT)) {
            mockMvc.perform(delete("/v1/tables/" + table));
        }
    }

    // ------------------------------------------------------------------------------------------- fixture

    /**
     * Three rows a day over three days, each with an identity row and a payload row: session {@code s1} on the first
     * row of today and of two days ago — a multi-day session with a day off between — every other row its own session;
     * plus one row today with neither, which only the outer join admits. Chat {@code c-0} and {@code c-1} carry titles.
     */
    private void seedSessions() {
        int n = 0;
        for (final String day : List.of(TODAY, YESTERDAY, TWO_DAYS_AGO)) {
            for (int i = 0; i < 3; i++) {
                final String id = seedSource(day, "1%d:00:00.000".formatted(i), "t-" + day + "-" + i, "c-" + i,
                        "2020-01-01 00:00:01.000");
                seedIdentity(id, i == 0 && !day.equals(YESTERDAY) ? "s1" : "other-" + n++);
                execute("INSERT INTO dial_usage_log_payload (event_id, request_time, trace_id, core_span_id, request_body) "
                        + "SELECT event_id, request_time, trace_id, core_span_id, concat('body-', trace_id) FROM dial_usage_log "
                        + "WHERE event_id = toUUID('" + id + "')");
            }
        }
        seedSource(TODAY, "20:00:00.000", "t-unmatched", "c-9", "2020-01-01 00:00:01.000");
        execute("INSERT INTO " + CHAT + " (chat_id, title) VALUES ('c-0', 'first'), ('c-1', 'second')");
    }

    private String seedSource(String day, String time, String trace, String chat, String ingestedAt) {
        final String eventId = UUID.randomUUID().toString();
        execute("INSERT INTO dial_usage_log (event_id, project_id, deployment, request_time, trace_id, core_span_id, chat_id,"
                + " _ingested_at) VALUES (toUUID('" + eventId + "'), 'p', 'd', '" + day + " " + time + "', '" + trace + "', 's', '"
                + chat + "', '" + ingestedAt + "')");
        return eventId;
    }

    private void seedIdentity(String eventId, String session) {
        execute("INSERT INTO " + IDENT + " (event_id, request_time, session_id) SELECT event_id, request_time, '" + session
                + "' FROM dial_usage_log WHERE event_id = toUUID('" + eventId + "')");
    }

    // ------------------------------------------------------------------------------------------- read path

    private static FilterNode eq(String field, String value) {
        return new ComparisonNode(ComparisonOp.EQ, List.of(new FieldExpr(field), new ValueExpr(ValueType.STRING, value)));
    }

    private static StructuredQuery query(FilterNode filter, int limit, String... select) {
        return new StructuredQuery(SOURCE, filter, QueryMode.ROW, false,
                Arrays.stream(select).map(name -> new OutputColumn(new FieldExpr(name), null)).toList(),
                null, null, List.of(new SortItem("trace_id", SortDir.ASC, null)), new OffsetPage(0, limit, false));
    }

    private QueryEntityModel model() {
        return catalog.findModel(SOURCE, Privilege.TABLE_READ).orElseThrow();
    }

    private SelectQuery<Record> shaped(StructuredQuery query) {
        return builder.build(renderContext, model(), query, StructuredQueryBuilder.MAX_LIMIT);
    }

    private SelectQuery<Record> plain(StructuredQuery query) {
        return builder.withoutReadShaping().build(renderContext, model(), query, StructuredQueryBuilder.MAX_LIMIT);
    }

    /** The settings the executor reads {@code query} under, as a trailing {@code SETTINGS} clause. */
    private String settings(StructuredQuery query) {
        final QueryEntityModel model = model();
        return model.partitionLocal() && builder.readsFinal(model, query)
                ? " SETTINGS do_not_merge_across_partitions_select_final = 1"
                : "";
    }

    private String run(StructuredQuery query, SelectQuery<Record> select) {
        return queryText(select.getSQL(ParamType.INLINED) + settings(query));
    }

    /**
     * Per read of {@code table} in {@code select}'s plan, whether it is pruned — some {@code Parts: x/y} line of it with
     * x &lt; y — in plan order.
     */
    private List<Boolean> prunedReads(StructuredQuery query, SelectQuery<Record> select, String table) {
        final String plan = queryText("EXPLAIN indexes = 1 " + select.getSQL(ParamType.INLINED) + settings(query));
        final List<Boolean> reads = new ArrayList<>();
        final String[] chunks = plan.split("ReadFromMergeTree \\(");
        final Pattern parts = Pattern.compile("Parts: (\\d+)/(\\d+)");
        for (int i = 1; i < chunks.length; i++) {
            final String name = chunks[i].substring(0, chunks[i].indexOf(')'));
            if (!name.equals(table) && !name.endsWith("." + table)) {
                continue;
            }
            boolean pruned = false;
            final Matcher matcher = parts.matcher(chunks[i]);
            while (matcher.find()) {
                pruned |= Integer.parseInt(matcher.group(1)) < Integer.parseInt(matcher.group(2));
            }
            reads.add(pruned);
        }
        return reads;
    }

    private static int occurrences(String sql, String text) {
        return sql.split(Pattern.quote(text), -1).length - 1;
    }

    @Test
    void lookupPrunesTheSourceAndEveryAlignedJoinFromOnePreselection() {
        seedSessions();
        final StructuredQuery query = query(eq(SESSION, "s1"), 100, "trace_id", SESSION, CHAT + ".title");
        final SelectQuery<Record> shaped = shaped(query);
        final String sql = shaped.getSQL(ParamType.INLINED);

        // Once in the WHERE and once in the chat join's key: the chat join has no partition pair to carry it.
        assertThat(occurrences(sql, PRESELECTION)).isEqualTo(2);
        final String rewritten = run(query, shaped);
        assertThat(rewritten).isEqualTo(run(query, plain(query)));
        assertThat(rewritten.lines().skip(2)).containsExactly("t-" + TWO_DAYS_AGO + "-0\ts1\tfirst", "t-" + TODAY + "-0\ts1\tfirst");
        // The source is pruned by its own restriction, the enrichment's derived table through the tuple join; the reads
        // of the enrichment that are not are the pre-selection itself, once per scope that names it.
        assertThat(prunedReads(query, shaped, SOURCE)).contains(true);
        assertThat(prunedReads(query, shaped, IDENT)).contains(true);
        assertThat(prunedReads(query, plain(query), IDENT)).containsOnly(false);
    }

    @Test
    void timeBoundedLookupBoundsThePreselectionsOwnRead() {
        seedSessions();
        final long since = LocalDate.parse(YESTERDAY).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        final StructuredQuery query = query(new LogicalNode(LogicalOp.AND, List.of(
                new ComparisonNode(ComparisonOp.GE, List.of(new FieldExpr("request_time"),
                        new ValueExpr(ValueType.TIMESTAMP, String.valueOf(since)))),
                eq(SESSION, "s1"))), 100, "trace_id", SESSION);
        final SelectQuery<Record> shaped = shaped(query);
        final String sql = shaped.getSQL(ParamType.INLINED);

        assertThat(sql.substring(sql.indexOf("(SELECT DISTINCT"), sql.indexOf(PRESELECTION)))
                .contains("\"" + IDENT + "\".\"request_time\" >=");
        final String rewritten = run(query, shaped);
        assertThat(rewritten).isEqualTo(run(query, plain(query)));
        assertThat(rewritten.lines().skip(2)).containsExactly("t-" + TODAY + "-0\ts1");
        assertThat(prunedReads(query, shaped, IDENT)).isNotEmpty().allMatch(Boolean::booleanValue);
    }

    @Test
    void lateMaterializedPageWithPreselectionReadsWhatTheUnrewrittenStatementReads() {
        seedSessions();
        final StructuredQuery query = query(eq(SESSION, "s1"), 1, "trace_id", SESSION, BODY);
        final SelectQuery<Record> shaped = shaped(query);
        final String sql = shaped.getSQL(ParamType.INLINED);

        assertThat(sql).contains("__adas_lm").contains(PRESELECTION);
        final String rewritten = run(query, shaped);
        assertThat(rewritten).isEqualTo(run(query, plain(query)));
        assertThat(rewritten.lines().skip(2)).containsExactly("t-" + TWO_DAYS_AGO + "-0\ts1\tbody-t-" + TWO_DAYS_AGO + "-0");
    }

    @Test
    void countWithPreselectionCountsWhatTheUnrewrittenCountCounts() {
        seedSessions();
        final StructuredQuery query = query(new LogicalNode(LogicalOp.AND, List.of(eq(SESSION, "s1"), eq(CHAT + ".title", "first"))),
                100, "trace_id");
        final SelectQuery<Record> shaped = builder.buildCount(renderContext, model(), query);

        assertThat(shaped.getSQL(ParamType.INLINED)).contains(PRESELECTION);
        final String counted = run(query, shaped);
        assertThat(counted).isEqualTo(run(query, builder.withoutReadShaping().buildCount(renderContext, model(), query)));
        assertThat(counted.lines().skip(2)).containsExactly("2");
    }

    // ------------------------------------------------------------------------------------------- group-select

    @Test
    void groupSelectionPreselectsAndSelectsTheSessionsMembersAcrossItsDays() throws Exception {
        seedSessions();
        IntegrationTables.source(mockMvc, "pp_spine",
                "[{\"source_name\":\"session_id\",\"name\":\"session_id\",\"type\":\"string\"}]", "[\"session_id\"]");
        IntegrationTables.enrichment(mockMvc, "pp_label", "pp_spine", "session_id",
                "[{\"source_name\":\"title\",\"name\":\"title\",\"type\":\"string\",\"nullable\":true}]");
        final String transform = Transforms.llm()
                .requestTemplate("{\"messages\":[{\"role\":\"user\",\"content\":\"Members ({{members_included}}). {{members}}\"}]}")
                .proseOutputs("title")
                .member();
        final MockHttpServletResponse created = mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"pp-group\"," + transform + ",\"kind\":\"enrich\",\"target\":\"pp_label\","
                                + "\"inputs\":[\"" + SOURCE + "\"],\"trigger\":{\"kind\":\"group\",\"group_by\":\"" + SESSION
                                + "\",\"ready_when\":{\"idle\":\"10m\"}},\"enabled\":true}"))
                .andReturn().getResponse();
        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);

        final String page = mockMvc.perform(runnerPost("/v1/pipelines/pp-group/group-rows")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"group_key\":\"s1\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // The members an unrewritten selection reads: every source row whose identity row carries the key.
        final List<String> expected = queryText("SELECT toString(s.event_id) FROM dial_usage_log AS s INNER JOIN " + IDENT + " AS e FINAL"
                + " ON s.event_id = e.event_id AND s.request_time = e.request_time WHERE e.session_id = 's1'")
                .lines().skip(2).toList();
        final List<String> members = JsonPath.read(page, "$.rows[*].event_id");
        assertThat(expected).hasSize(2);
        assertThat(members).as(page).containsExactlyInAnyOrderElementsOf(expected);
        execute("SYSTEM FLUSH LOGS");
        assertThat(queryScalar("SELECT toString(count()) AS c FROM system.query_log WHERE type = 'QueryFinish' AND query_kind = "
                + "'Select' AND query ILIKE '%" + IDENT + "%session_id\" = ''s1''%" + PRESELECTION + "%'", "c")).isNotEqualTo("0");

        // A blank key is refused before any selection, so no default-valued key reaches the pre-selection.
        mockMvc.perform(runnerPost("/v1/pipelines/pp-group/group-rows")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"group_key\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------------------------------- aggregate

    /** The aggregate an unrestricted recompute writes: every session's event count, read straight off the tables. */
    private List<String> recomputed() {
        return queryText("SELECT e.session_id, count() FROM dial_usage_log AS s FINAL INNER JOIN " + IDENT + " AS e FINAL"
                + " ON s.event_id = e.event_id AND s.request_time = e.request_time WHERE length(e.session_id) > 0"
                + " GROUP BY e.session_id ORDER BY e.session_id").lines().skip(2).toList();
    }

    private List<String> written(String target) {
        return queryText("SELECT session, events FROM " + target + " FINAL ORDER BY session").lines().skip(2).toList();
    }

    private void refresh(String pipeline) throws Exception {
        mockMvc.perform(post("/v1/pipelines/" + pipeline + "/refresh")).andExpect(status().isOk());
    }

    @Test
    void formAndSqlRecomputesThroughTheAlignedColumnWriteWhatAnUnrestrictedRecomputeDoes() throws Exception {
        seedSessions();
        final String columns = "[{\"source_name\":\"session\",\"name\":\"session\",\"type\":\"string\"},"
                + "{\"source_name\":\"events\",\"name\":\"events\",\"type\":\"long\"}]";
        IntegrationTables.upsertSource(mockMvc, "pp_form", columns, "[\"session\"]");
        IntegrationTables.upsertSource(mockMvc, "pp_sql", columns, "[\"session\"]");
        final String trigger = "\"trigger\":{\"kind\":\"schedule\",\"cron\":\"0 0 * * * *\"}";
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"pp-form\",\"kind\":\"aggregate\",\"target\":\"pp_form\",\"inputs\":[\"" + SOURCE
                                + "\"],\"filter\":\"length(\\\"" + SESSION + "\\\") > 0\","
                                + "\"group_by\":[{\"column\":\"" + SESSION + "\",\"as\":\"session\"}],"
                                + "\"measures\":[{\"name\":\"events\",\"fn\":\"count\"}]," + trigger + "}"))
                .andExpect(status().isCreated());
        final String sql = "INSERT INTO pp_sql (session, events) SELECT coalesce(\\\"" + SESSION + "\\\", '') AS session, count(*)"
                + " FROM " + SOURCE + " WHERE length(\\\"" + SESSION + "\\\") > 0 GROUP BY 1";
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"pp-sql\",\"kind\":\"aggregate\",\"target\":\"pp_sql\",\"sql\":\"" + sql + "\"," + trigger + "}"))
                .andExpect(status().isCreated());
        for (final String pipeline : List.of("pp-form", "pp-sql")) {
            mockMvc.perform(patch("/v1/pipelines/" + pipeline).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                    .andExpect(status().isOk());
            refresh(pipeline);
        }
        assertThat(written("pp_form")).isEqualTo(recomputed()).contains("s1\t2").hasSize(8);
        assertThat(written("pp_sql")).isEqualTo(recomputed());

        // A later tick recomputes only the dirty session, pre-selected to its days, and still counts every one of them.
        final String id = seedSource(YESTERDAY, "23:00:00.000", "t-late", "c-9", "2020-01-01 00:00:02.000");
        seedIdentity(id, "s1");
        refresh("pp-form");
        refresh("pp-sql");
        assertThat(written("pp_form")).isEqualTo(recomputed()).contains("s1\t3");
        assertThat(written("pp_sql")).isEqualTo(recomputed());
        execute("SYSTEM FLUSH LOGS");
        for (final String target : List.of("pp_form", "pp_sql")) {
            assertThat(queryScalar("SELECT toString(count()) AS c FROM system.query_log WHERE type = 'QueryFinish' "
                    + "AND query ILIKE 'INSERT INTO%" + target + "%" + PRESELECTION + "%'", "c")).as(target).isNotEqualTo("0");
        }
    }
}
