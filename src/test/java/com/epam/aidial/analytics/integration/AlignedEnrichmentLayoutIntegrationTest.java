package com.epam.aidial.analytics.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Which enrichments are laid out aligned with their source, which declared columns can stand in for the alignment
 * column, and the lifecycle of the lookup index a grouped-by aligned column carries (capability table-management,
 * "An eligible row-grain enrichment is laid out aligned with its source" and "A grouped-by column of an aligned
 * enrichment carries a lookup index"), against a real ClickHouse.
 */
class AlignedEnrichmentLayoutIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final String SOURCE = "dial_usage_log";
    private static final String IDENT = "li_ident";
    /** An API source's columns: an identity, a partition column and a version. */
    private static final String SRC_COLUMNS = "\"columns\":[{\"source_name\":\"id\",\"name\":\"id\",\"type\":\"string\"},"
            + "{\"source_name\":\"day\",\"name\":\"day\",\"type\":\"timestamp\"},"
            + "{\"source_name\":\"seen_at\",\"name\":\"seen_at\",\"type\":\"timestamp\"}]";

    @BeforeEach
    void setUp() {
        truncate();
    }

    @AfterEach
    void tearDown() throws Exception {
        for (final String pipeline : List.of("li-group", "li-rollup", "li-rollup2")) {
            mockMvc.perform(delete("/v1/pipelines/" + pipeline));
        }
        for (final String table : List.of("li_label", "li_spine", "li_rollup", "li_rollup2", IDENT, "li_chat", "li_src_enr",
                "li_src", "li_adopt")) {
            mockMvc.perform(delete("/v1/tables/" + table));
        }
    }

    private void identity() throws Exception {
        IntegrationTables.enrichment(mockMvc, IDENT, SOURCE, "event_id",
                "[{\"source_name\":\"session\",\"name\":\"session\",\"type\":\"string\",\"nullable\":true}]");
    }

    /** A group-trigger pipeline grouping {@link #SOURCE} by {@code groupBy}, writing a session-grain target. */
    private void groupPipeline(String groupBy) throws Exception {
        // The target's grain key is the group key's column name: a group pipeline's rows are keyed by it.
        IntegrationTables.source(mockMvc, "li_spine",
                "[{\"source_name\":\"session\",\"name\":\"session\",\"type\":\"string\"}]", "[\"session\"]");
        IntegrationTables.enrichment(mockMvc, "li_label", "li_spine", "session",
                "[{\"source_name\":\"title\",\"name\":\"title\",\"type\":\"string\",\"nullable\":true}]");
        final String transform = Transforms.llm()
                .requestTemplate("{\"messages\":[{\"role\":\"user\",\"content\":\"Members ({{members_included}}). {{members}}\"}]}")
                .proseOutputs("title")
                .member();
        final MockHttpServletResponse created = mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"li-group\"," + transform + ",\"kind\":\"enrich\",\"target\":\"li_label\","
                                + "\"inputs\":[\"" + SOURCE + "\"],\"trigger\":{\"kind\":\"group\",\"group_by\":\"" + groupBy
                                + "\",\"ready_when\":{\"idle\":\"10m\"}},\"enabled\":true}"))
                .andReturn().getResponse();
        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
    }

    /** A form aggregate into a fresh {@code target}, grouping {@link #SOURCE} by {@code groupBy}. */
    private void aggregate(String name, String target, String groupBy) throws Exception {
        IntegrationTables.upsertSource(mockMvc, target,
                "[{\"source_name\":\"k\",\"name\":\"k\",\"type\":\"string\"},"
                        + "{\"source_name\":\"events\",\"name\":\"events\",\"type\":\"long\"}]", "[\"k\"]");
        mockMvc.perform(post("/v1/pipelines").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"kind\":\"aggregate\",\"target\":\"" + target + "\",\"inputs\":[\""
                                + SOURCE + "\"],\"group_by\":[{\"column\":\"" + groupBy + "\",\"as\":\"k\"}],"
                                + "\"measures\":[{\"name\":\"events\",\"fn\":\"count\"}],"
                                + "\"trigger\":{\"kind\":\"schedule\",\"cron\":\"0 0 * * * *\"}}"))
                .andExpect(status().isCreated());
    }

    /** {@code table}'s skip indexes as {@code name expr type_full} lines, by name. */
    private List<String> indexes(String table) {
        return queryText("SELECT name, expr, type_full FROM system.data_skipping_indices WHERE database = currentDatabase()"
                + " AND table = '" + table + "' ORDER BY name").lines().skip(2).toList();
    }

    /** How many {@code ADD INDEX} statements the server has finished on {@code table}. */
    private long addIndexStatements(String table) {
        execute("SYSTEM FLUSH LOGS");
        return Long.parseLong(queryScalar("SELECT toString(count()) AS c FROM system.query_log WHERE type = 'QueryFinish'"
                + " AND query_kind = 'Alter' AND query ILIKE 'ALTER TABLE `" + table + "` ADD INDEX%'", "c"));
    }

    // ------------------------------------------------------------------------------------------- lookup index

    @Test
    void groupTriggerIndexesTheColumnOnceAndItsDropDropsTheIndexItOutlived() throws Exception {
        identity();
        final long before = addIndexStatements(IDENT);

        groupPipeline(IDENT + ".session");

        assertThat(indexes(IDENT)).containsExactly("idx_session\tsession\tbloom_filter(0.001)");
        assertThat(addIndexStatements(IDENT)).isEqualTo(before + 1);
        // A second pipeline grouping by the same column issues no further DDL.
        aggregate("li-rollup", "li_rollup", IDENT + ".session");
        assertThat(addIndexStatements(IDENT)).isEqualTo(before + 1);
        assertThat(indexes(IDENT)).hasSize(1);

        // Deleting the pipelines leaves the index; dropping the column then takes it along in the same ALTER.
        mockMvc.perform(delete("/v1/pipelines/li-group")).andExpect(status().isNoContent());
        mockMvc.perform(delete("/v1/pipelines/li-rollup")).andExpect(status().isNoContent());
        assertThat(indexes(IDENT)).hasSize(1);
        mockMvc.perform(patch("/v1/tables/" + IDENT + "/schema").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"drop\":[\"session\"]}"))
                .andExpect(status().isOk());
        assertThat(indexes(IDENT)).isEmpty();
        assertThat(showCreateTable(IDENT)).doesNotContain("`session`");
    }

    /** A rename keeps the index's name and moves its expression: coverage is by expression, not by name. */
    @Test
    void renamedColumnStaysCoveredByItsOldIndexAndTheOldNameGetsTheNextFreeOne() throws Exception {
        identity();
        aggregate("li-rollup", "li_rollup", IDENT + ".session");
        mockMvc.perform(delete("/v1/pipelines/li-rollup")).andExpect(status().isNoContent());
        mockMvc.perform(patch("/v1/tables/" + IDENT + "/schema").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rename\":[{\"from\":\"session\",\"to\":\"sid\"}],"
                                + "\"add\":[{\"source_name\":\"session\",\"name\":\"session\",\"type\":\"string\"}]}"))
                .andExpect(status().isOk());
        assertThat(indexes(IDENT)).containsExactly("idx_session\tsid\tbloom_filter(0.001)");

        mockMvc.perform(delete("/v1/tables/li_rollup")).andExpect(status().isNoContent());
        aggregate("li-rollup", "li_rollup", IDENT + ".sid");
        assertThat(indexes(IDENT)).containsExactly("idx_session\tsid\tbloom_filter(0.001)");

        aggregate("li-rollup2", "li_rollup2", IDENT + ".session");
        assertThat(indexes(IDENT)).containsExactly("idx_session\tsid\tbloom_filter(0.001)",
                "idx_session_2\tsession\tbloom_filter(0.001)");
    }

    @Test
    void nonAlignedEnrichmentColumnGetsNoIndex() throws Exception {
        IntegrationTables.enrichment(mockMvc, "li_chat", SOURCE, "chat_id",
                "[{\"source_name\":\"topic\",\"name\":\"topic\",\"type\":\"string\",\"nullable\":true}]");

        aggregate("li-rollup", "li_rollup", "li_chat.topic");

        assertThat(indexes("li_chat")).isEmpty();
    }

    // ------------------------------------------------------------------------------------------- eligibility

    /** Creates and materializes source {@code li_src} with {@code schema} (columns, keys, partitioning, pair). */
    private void apiSource(String write, String schema) throws Exception {
        mockMvc.perform(post("/v1/tables").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"li_src\",\"type\":\"source\",\"write\":\"" + write + "\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/v1/tables/li_src/schema").contentType(MediaType.APPLICATION_JSON).content(schema))
                .andExpect(status().isOk());
    }

    @Test
    void partitionedAppendApiSourceGetsAnAlignedEnrichment() throws Exception {
        apiSource("append", "{" + SRC_COLUMNS + ",\"ordering_key\":[\"id\"],\"partition_by\":{\"column\":\"day\","
                + "\"granularity\":\"day\"},\"identity_column\":\"id\",\"version_column\":\"seen_at\"}");

        IntegrationTables.enrichment(mockMvc, "li_src_enr", "li_src", "id",
                "[{\"source_name\":\"label\",\"name\":\"label\",\"type\":\"string\",\"nullable\":true}]");

        assertThat(showCreateTable("li_src_enr")).contains("PARTITION BY toYYYYMMDD(day)").contains("`day` DateTime64");
        mockMvc.perform(get("/v1/tables/li_src_enr")).andExpect(jsonPath("$.layout").value("aligned"));
    }

    @Test
    void upsertSourcePartitionedOutsideItsKeyGetsAnUnalignedEnrichment() throws Exception {
        apiSource("upsert_by_key", "{" + SRC_COLUMNS + ",\"ordering_key\":[\"id\"],\"partition_by\":{\"column\":\"day\","
                + "\"granularity\":\"day\"},\"identity_column\":\"id\",\"version_column\":\"_updated_at\"}");

        IntegrationTables.enrichment(mockMvc, "li_src_enr", "li_src", "id",
                "[{\"source_name\":\"label\",\"name\":\"label\",\"type\":\"string\",\"nullable\":true}]");

        assertThat(showCreateTable("li_src_enr")).doesNotContain("PARTITION BY").doesNotContain("`day`");
        mockMvc.perform(get("/v1/tables/li_src_enr")).andExpect(jsonPath("$.partition_by").doesNotExist());
    }

    /** The grain key already is the partition value: there is no second column to add. */
    @Test
    void sourceWhoseIdentityIsItsPartitionColumnGetsAnUnalignedEnrichment() throws Exception {
        apiSource("append", "{" + SRC_COLUMNS + ",\"ordering_key\":[\"day\"],\"partition_by\":{\"column\":\"day\","
                + "\"granularity\":\"day\"},\"identity_column\":\"day\",\"version_column\":\"seen_at\"}");

        IntegrationTables.enrichment(mockMvc, "li_src_enr", "li_src", "day",
                "[{\"source_name\":\"label\",\"name\":\"label\",\"type\":\"string\",\"nullable\":true}]");

        assertThat(showCreateTable("li_src_enr")).doesNotContain("PARTITION BY");
    }

    @Test
    void yearlyPartitionedSourceIsCreated() throws Exception {
        apiSource("append", "{" + SRC_COLUMNS + ",\"ordering_key\":[\"id\"],\"partition_by\":{\"column\":\"day\","
                + "\"granularity\":\"year\"}}");

        assertThat(showCreateTable("li_src")).contains("PARTITION BY toYear(day)");
    }

    // ------------------------------------------------------------------------------------------- adoption

    @Test
    void sensitiveOrHeavyColumnNamedLikeTheAlignmentColumnIsNotAdopted() throws Exception {
        mockMvc.perform(post("/v1/tables").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"li_adopt\",\"type\":\"enrichment\",\"source_table\":\"" + SOURCE + "\"}"))
                .andExpect(status().isCreated());
        for (final String flag : List.of("sensitive", "heavy")) {
            mockMvc.perform(post("/v1/tables/li_adopt/schema").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"grain_key\":\"event_id\",\"columns\":[{\"source_name\":\"request_time\","
                                    + "\"name\":\"request_time\",\"type\":\"timestamp\",\"nullable\":false,\"" + flag + "\":true}]}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error").value("validation_error"))
                    .andExpect(jsonPath("$.message", containsString("alignment column")));
        }
    }
}
