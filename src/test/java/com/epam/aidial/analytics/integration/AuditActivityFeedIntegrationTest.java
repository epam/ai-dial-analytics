package com.epam.aidial.analytics.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The audit trail as a caller sees it: what the table-management endpoints record, and what
 * {@code /v1/activities} and the snapshot route then answer.
 *
 * <p>Complements {@code AuditTrailIntegrationTest}, which pins the persistence wiring. This one is about
 * <em>grain</em> — that one administrative act is recoverable as one act, and that a change names the
 * column it changed rather than the table it belongs to.
 *
 * <p>Requires a running Docker daemon (real ClickHouse for the DDL, real PostgreSQL for the catalog).
 */
class AuditActivityFeedIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    /** Per-test names: audit history outlives the rows, so a shared name would leak between tests. */
    private static final AtomicInteger NAMES = new AtomicInteger();

    private static final String COLUMNS =
            "[{\"source_name\":\"widget_id\",\"name\":\"widget_id\",\"type\":\"string\"},"
                    + "{\"source_name\":\"score\",\"name\":\"score\",\"type\":\"long\"}]";
    private static final String ORDERING = "[\"widget_id\"]";

    private String table;

    @BeforeEach
    void nameThisTestsTable() {
        table = "audit_feed_" + NAMES.incrementAndGet();
    }

    @AfterEach
    void cleanUp() throws Exception {
        mockMvc.perform(delete("/v1/tables/" + table));
    }

    @Test
    void tableCreateRecordsOneTableActivity() throws Exception {
        mockMvc.perform(post("/v1/tables").contentType(JSON)
                        .content("{\"name\":\"" + table + "\",\"type\":\"source\"}"))
                .andExpect(status().isCreated());

        feed(filter("resourceId", "eq", table))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].activityType").value("Create"))
                .andExpect(jsonPath("$.data[0].resourceType").value("Table"))
                .andExpect(jsonPath("$.data[0].parentActivityId").doesNotExist())
                .andExpect(jsonPath("$.data[0].importId").doesNotExist());
    }

    @Test
    void definingSchemaRecordsEveryDeclaredColumn() throws Exception {
        IntegrationTables.source(mockMvc, table, COLUMNS, ORDERING);

        // Two columns declared, so two TableColumn activities naming exactly which columns they are —
        // the grain the design exists to keep, rather than one "the table changed" entry.
        feed(filter("resourceId", "in", table + ":widget_id," + table + ":score"))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.data[*].resourceType", everyItem(is("TableColumn"))))
                .andExpect(jsonPath("$.data[*].activityType", everyItem(is("Create"))))
                // Each names its own column, and both hang off the same act that created them.
                .andExpect(jsonPath("$.data[0].parentActivityId").exists())
                .andExpect(jsonPath("$.data[1].parentActivityId").exists());
    }

    @Test
    void oneSchemaPatchIsRetrievableAsOneAct() throws Exception {
        IntegrationTables.source(mockMvc, table, COLUMNS, ORDERING);
        mockMvc.perform(patch("/v1/tables/" + table + "/schema").contentType(JSON)
                        .content("{\"add\":[{\"source_name\":\"extra\",\"name\":\"extra\",\"type\":\"string\"}]}"))
                .andExpect(status().isOk());

        final int revision = revisionOf(table + ":extra");

        // Filtering by the revision of any one activity returns everything that act produced and nothing
        // from another transaction — the whole point of grouping per transaction.
        feed(filter("revision", "eq", String.valueOf(revision)))
                .andExpect(jsonPath("$.data[*].resourceId",
                        everyItem(org.hamcrest.Matchers.startsWith(table))));
    }

    @Test
    void renameEndsOneColumnResourceAndStartsAnother() throws Exception {
        IntegrationTables.source(mockMvc, table, COLUMNS, ORDERING);
        mockMvc.perform(patch("/v1/tables/" + table + "/schema").contentType(JSON)
                        .content("{\"rename\":[{\"from\":\"score\",\"to\":\"rating\"}]}"))
                .andExpect(status().isOk());

        // A column's physical name is part of its identity, so a rename genuinely is one resource ending
        // and another beginning — and the trail says so rather than calling it an update.
        feed(filter("resourceId", "eq", table + ":score"))
                .andExpect(jsonPath("$.data[0].activityType").value("Delete"));
        feed(filter("resourceId", "eq", table + ":rating"))
                .andExpect(jsonPath("$.data[0].activityType").value("Create"));
    }

    @Test
    void deletingTableRecordsItsColumnsToo() throws Exception {
        IntegrationTables.source(mockMvc, table, COLUMNS, ORDERING);
        mockMvc.perform(delete("/v1/tables/" + table)).andExpect(status().isNoContent());

        // Without the ORM delete of the children this is where the trail would go quiet: the database
        // cascade fires no Hibernate event, so the columns would vanish unrecorded.
        feed(filter("resourceId", "eq", table + ":widget_id"), sort("epochTimestampMs", "DESC"))
                .andExpect(jsonPath("$.data[0].activityType").value("Delete"));
        feed(filter("resourceId", "eq", table), sort("epochTimestampMs", "DESC"))
                .andExpect(jsonPath("$.data[0].activityType").value("Delete"));
    }

    @Test
    void snapshotReturnsTheTableAsItStoodAtThatRevision() throws Exception {
        IntegrationTables.source(mockMvc, table, COLUMNS, ORDERING);
        final int beforePatch = revisionOf(table + ":score");

        mockMvc.perform(patch("/v1/tables/" + table + "/schema").contentType(JSON)
                        .content("{\"drop\":[\"score\"]}"))
                .andExpect(status().isOk());
        final int afterPatch = revisionOf(table + ":score");

        // Same shape as the live read, so a client diffs two identical shapes.
        mockMvc.perform(get("/v1/tables/" + table + "/revision/" + beforePatch))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(table))
                .andExpect(jsonPath("$.columns", hasSize(2)))
                .andExpect(jsonPath("$.ordering_key[0]").value("widget_id"));
        mockMvc.perform(get("/v1/tables/" + table + "/revision/" + afterPatch))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns", hasSize(1)))
                .andExpect(jsonPath("$.columns[0].source_name").value("widget_id"));
    }

    @Test
    void snapshotCarriesTheGrantsInNoneModeAndTheLiveReadDoesNot() throws Exception {
        IntegrationTables.source(mockMvc, table, COLUMNS, ORDERING);
        mockMvc.perform(put("/v1/tables/" + table + "/access").contentType(JSON)
                        .content("{\"read\":[\"team-a\"]}"))
                .andExpect(status().isOk());
        final int granted = revisionOf(table);

        // In none mode the grant reads are open, so the snapshot carries both members to every caller —
        // the policy map present and empty, never absent.
        mockMvc.perform(get("/v1/tables/" + table + "/revision/" + granted))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access.read[0]").value("team-a"))
                .andExpect(jsonPath("$.access.write").isEmpty())
                .andExpect(jsonPath("$.row_policies").isMap())
                .andExpect(jsonPath("$.row_policies").isEmpty());
        mockMvc.perform(get("/v1/tables/" + table))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access").doesNotExist())
                .andExpect(jsonPath("$.row_policies").doesNotExist());
    }

    @Test
    void snapshotBeforeTheTableExistedIsNotFound() throws Exception {
        // A revision is burned by an unrelated table first, so there is definitely a real revision that
        // predates this test's table. Asking for revision 1 outright would be a coin flip: `revinfo` is
        // global and starts at 1, so whenever this method happens to be the run's first audited write,
        // revision 1 IS this table's own creation and the endpoint correctly answers 200.
        final String earlier = table + "_earlier";
        mockMvc.perform(post("/v1/tables").contentType(JSON)
                        .content("{\"name\":\"" + earlier + "\",\"type\":\"source\"}"))
                .andExpect(status().isCreated());
        final int beforeThisTable = revisionOf(earlier);

        IntegrationTables.source(mockMvc, table, COLUMNS, ORDERING);
        try {
            mockMvc.perform(get("/v1/tables/" + table + "/revision/" + beforeThisTable))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("revision_not_found"));
        } finally {
            mockMvc.perform(delete("/v1/tables/" + earlier));
        }
    }

    @Test
    void deletedTableColumnsDoNotSurviveInLaterSnapshots() throws Exception {
        IntegrationTables.source(mockMvc, table, COLUMNS, ORDERING);
        mockMvc.perform(delete("/v1/tables/" + table)).andExpect(status().isNoContent());
        final int afterDelete = revisionOf(table);

        // The assertion that catches a missing delete row: without one, a removed column keeps answering
        // as though it still existed at every future revision, forever.
        mockMvc.perform(get("/v1/tables/" + table + "/revision/" + afterDelete))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("revision_not_found"));
    }

    @Test
    void snapshotAtRevisionThatWasNeverIssuedIsNotFound() throws Exception {
        IntegrationTables.source(mockMvc, table, COLUMNS, ORDERING);

        // Regression guard for a defect found by running the service, not by the suite: Envers resolves
        // an entity at a revision from its latest row at or before that number, so a revision in the
        // future silently returns the CURRENT state. Answering 200 there is worse than useless — a
        // client diffing that "past" against the present would conclude nothing ever changed.
        mockMvc.perform(get("/v1/tables/" + table + "/revision/99999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("revision_not_found"));
    }

    @Test
    void snapshotAtNonPositiveRevisionIsNotFound() throws Exception {
        IntegrationTables.source(mockMvc, table, COLUMNS, ORDERING);

        mockMvc.perform(get("/v1/tables/" + table + "/revision/0"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("revision_not_found"));
    }

    @Test
    void revisionThatIsNotNumericIsNotFound() throws Exception {
        IntegrationTables.source(mockMvc, table, COLUMNS, ORDERING);

        // Bound as a String and parsed, because an int @PathVariable raises a binding exception the
        // error handler does not map — which made this a 500 for a plainly client-side mistake.
        mockMvc.perform(get("/v1/tables/" + table + "/revision/abc"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("revision_not_found"));
        mockMvc.perform(get("/v1/tables/" + table + "/revision/99999999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("revision_not_found"));
    }

    @Test
    void notEqualOnParentIncludesTopLevelActivities() throws Exception {
        IntegrationTables.source(mockMvc, table, COLUMNS, ORDERING);
        final String childParent = readJson(
                feedBody(filter("resourceId", "eq", table + ":widget_id")), "$.data[0].parentActivityId");

        // The Table activities have no parent at all; a `ne` filter must still return them, or the feed
        // offers no way to see anything that is not somebody's child.
        feed(filter("parentActivityId", "ne", childParent))
                .andExpect(jsonPath("$.data[?(@.resourceType=='Table')]").exists());
    }

    @Test
    void importIdFiltersFollowTheIdentifierRules() throws Exception {
        IntegrationTables.source(mockMvc, table, COLUMNS, ORDERING);
        final String someImport = "00000000-0000-0000-0000-000000000001";

        // A hand-made table carries no importId, so `ne` must still return it, as for the parent identifier.
        feed(filter("importId", "ne", someImport) + "," + filter("resourceId", "eq", table))
                .andExpect(jsonPath("$.data[0]").exists())
                .andExpect(jsonPath("$.data[*].importId").doesNotExist());
        feed(filter("importId", "in", someImport + ",00000000-0000-0000-0000-000000000002"))
                .andExpect(jsonPath("$.data", hasSize(0)));
        for (final String operator : List.of("lt", "co")) {
            mockMvc.perform(post("/v1/activities").contentType(JSON)
                            .content("{\"filters\":[" + filter("importId", operator, someImport) + "]}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_filter_value"));
        }
        mockMvc.perform(post("/v1/activities").contentType(JSON)
                        .content("{\"filters\":[" + filter("importId", "eq", "not-a-uuid") + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_filter_value"));
    }

    @Test
    void anUnknownActivityIs404() throws Exception {
        mockMvc.perform(get("/v1/activities/00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("activity_not_found"));
    }

    @Test
    void anActivityCanBeFetchedByTheIdTheFeedReturned() throws Exception {
        mockMvc.perform(post("/v1/tables").contentType(JSON)
                        .content("{\"name\":\"" + table + "\",\"type\":\"source\"}"))
                .andExpect(status().isCreated());

        final String id = readJson(feedBody(filter("resourceId", "eq", table)), "$.data[0].activityId");
        mockMvc.perform(get("/v1/activities/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceId").value(table))
                .andExpect(jsonPath("$.initiatedAuthor").value("unknown"));
    }

    @Test
    void anUnknownFilterColumnIs400() throws Exception {
        mockMvc.perform(post("/v1/activities").contentType(JSON)
                        .content("{\"filters\":[{\"column\":\"noSuchColumn\",\"operator\":\"co\",\"value\":\"a\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_filter_column"));
    }

    @Test
    void anUnparseableFilterValueIs400() throws Exception {
        mockMvc.perform(post("/v1/activities").contentType(JSON)
                        .content("{\"filters\":[{\"column\":\"epochTimestampMs\",\"operator\":\"ge\","
                                + "\"value\":\"not-a-number\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_filter_value"));
    }

    @Test
    void nullTermInsideTheRequestIs400() throws Exception {
        // @Valid cascades into list elements but does not reject a null one, so these reached the query
        // builder and NPE'd into a 500 — for a malformed request this layer exists to answer with a 400.
        mockMvc.perform(post("/v1/activities").contentType(JSON).content("{\"filters\":[null]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/v1/activities").contentType(JSON).content("{\"sorts\":[null]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anOutOfRangePageSizeIs400() throws Exception {
        mockMvc.perform(post("/v1/activities").contentType(JSON).content("{\"pageSize\":5000}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theFeedPagesAndReportsTheWholeMatch() throws Exception {
        IntegrationTables.source(mockMvc, table, COLUMNS, ORDERING);

        // Exact ids, not `co` on the table name: names are numbered, so `audit_feed_1` is a substring of
        // `audit_feed_10` and a contains-filter would sweep in a sibling test's activities. That would
        // leave the counts unassertable — which is the only thing this test is for.
        final String ids = table + ":widget_id," + table + ":score";
        mockMvc.perform(post("/v1/activities").contentType(JSON)
                        .content("{\"pageSize\":1,\"filters\":[{\"column\":\"resourceId\",\"operator\":\"in\","
                                + "\"value\":\"" + ids + "\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    /**
     * The ENRICH kind through the same feed and the same snapshot route as the aggregate case below —
     * one resource type for one registry since the fold. What is asserted here and not there is the part
     * that is kind-specific: the snapshot reconstructs the pipeline's own stored row and does <b>not</b>
     * resolve the derived grain key, which the live read does — that would need historical state for
     * another entity this capability does not reconstruct.
     *
     * <p>There is no pinned evaluator left to resolve, and the snapshot is <b>better</b> for it: the
     * transform lives in the {@code spec} that {@code pipeline_aud} mirrors, so a revision now carries
     * the declaration — the prompt included — that produced a row. The evaluator registry never did.
     */
    @Test
    void enrichPipelineAppearsInTheFeedAndItsSnapshotSkipsCrossEntityResolution() throws Exception {
        final String source = "audit_feed_enr_src_" + NAMES.incrementAndGet();
        final String enrichment = "audit_feed_enr_tgt_" + NAMES.incrementAndGet();
        final String name = "audit-feed-enrich-" + NAMES.incrementAndGet();
        IntegrationTables.source(mockMvc, source, COLUMNS, ORDERING);
        IntegrationTables.enrichment(mockMvc, enrichment, source, "widget_id",
                "[{\"source_name\":\"tox_score\",\"name\":\"tox_score\",\"type\":\"long\"}]");

        try {
            final String body = "{\"name\":\"" + name + "\",\"kind\":\"enrich\",\"target\":\"" + enrichment + "\","
                    + Transforms.llm().proseOutputs("tox_score").member()
                    + ",\"trigger\":{\"kind\":\"schedule\"},"
                    + "\"filter\":\"score > 5\","
                    + "\"enabled\":true}";
            mockMvc.perform(post("/v1/pipelines").contentType(JSON).content(body))
                    .andExpect(status().isCreated());
            final int createdRevision = revisionOf(name);

            feed(filter("resourceId", "eq", name))
                    .andExpect(jsonPath("$.data", hasSize(1)))
                    .andExpect(jsonPath("$.data[0].activityType").value("Create"))
                    .andExpect(jsonPath("$.data[0].resourceType").value("Pipeline"))
                    .andExpect(jsonPath("$.data[0].parentActivityId").doesNotExist());

            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .patch("/v1/pipelines/" + name).contentType(JSON).content("{\"enabled\":false}"))
                    .andExpect(status().isOk());
            final int updatedRevision = revisionOf(name);

            mockMvc.perform(get("/v1/pipelines/" + name + "/revision/" + createdRevision))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value(name))
                    .andExpect(jsonPath("$.kind").value("enrich"))
                    .andExpect(jsonPath("$.enabled").value(true))
                    // The transform IS reconstructed — it is part of the spec the mirror table carries —
                    // while the derived grain key is not, which is the line this asserts.
                    .andExpect(jsonPath("$.transform.model").value("gpt-4o"))
                    .andExpect(jsonPath("$.evaluator").doesNotExist())
                    .andExpect(jsonPath("$.grain_key").doesNotExist());
            mockMvc.perform(get("/v1/pipelines/" + name + "/revision/" + updatedRevision))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled").value(false));
        } finally {
            mockMvc.perform(delete("/v1/pipelines/" + name));
            mockMvc.perform(delete("/v1/tables/" + enrichment));
            mockMvc.perform(delete("/v1/tables/" + source));
        }
    }

    @Test
    void pipelineCreateUpdateDeleteAppearInTheFeedAndItsSnapshotRoundTrips() throws Exception {
        final String input = "audit_feed_pi_" + NAMES.incrementAndGet();
        final String target = "audit_feed_pt_" + NAMES.incrementAndGet();
        final String name = "audit_feed_pipeline_" + NAMES.incrementAndGet();
        // A scannable source (identity/version pair) so AggregateSpecValidator accepts it as an input.
        IntegrationTables.source(mockMvc, input,
                "[{\"source_name\":\"widget_id\",\"name\":\"widget_id\",\"type\":\"string\"},"
                        + "{\"source_name\":\"created_at\",\"name\":\"created_at\",\"type\":\"timestamp\"}]",
                "[\"widget_id\"]", "widget_id", "created_at");
        // group_by must equal the target's ordering_key exactly, so the target declares the same single
        // key column the pipeline groups by.
        IntegrationTables.upsertSource(mockMvc, target,
                "[{\"source_name\":\"widget_id\",\"name\":\"widget_id\",\"type\":\"string\"},"
                        + "{\"source_name\":\"call_count\",\"name\":\"call_count\",\"type\":\"long\"}]",
                "[\"widget_id\"]");

        try {
            final String body = "{\"name\":\"" + name + "\",\"kind\":\"aggregate\",\"target\":\"" + target + "\","
                    + "\"inputs\":[\"" + input + "\"],\"group_by\":[{\"column\":\"widget_id\"}],"
                    + "\"measures\":[{\"name\":\"call_count\",\"fn\":\"count\"}],"
                    + "\"trigger\":{\"kind\":\"schedule\",\"cron\":\"0 0 * * * *\"}}";
            mockMvc.perform(post("/v1/pipelines").contentType(JSON).content(body))
                    .andExpect(status().isCreated());
            final int createdRevision = revisionOf(name);

            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .patch("/v1/pipelines/" + name).contentType(JSON).content("{\"enabled\":true}"))
                    .andExpect(status().isOk());
            mockMvc.perform(delete("/v1/pipelines/" + name)).andExpect(status().isNoContent());

            feed(filter("resourceId", "eq", name))
                    .andExpect(jsonPath("$.data[*].resourceType", everyItem(is("Pipeline"))))
                    .andExpect(jsonPath("$.data[*].parentActivityId").doesNotExist());

            mockMvc.perform(get("/v1/pipelines/" + name + "/revision/" + createdRevision))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value(name))
                    .andExpect(jsonPath("$.target").value(target));
            mockMvc.perform(get("/v1/pipelines/" + name + "/revision/99999"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("revision_not_found"));
        } finally {
            mockMvc.perform(delete("/v1/tables/" + target));
            mockMvc.perform(delete("/v1/tables/" + input));
        }
    }

    @Test
    void savedQueryCreateUpdateDeleteAppearInTheFeedAndItsSnapshotRoundTrips() throws Exception {
        final String body = "{\"name\":\"audit feed saved query\",\"sql\":\"SELECT project_id FROM dial_usage_log\"}";
        final String created = mockMvc.perform(post("/v1/saved-queries").contentType(JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        final String id = readJson(created, "$.id");
        final int createdRevision = revisionOf(id);

        final String updateBody =
                "{\"name\":\"audit feed saved query\",\"sql\":\"SELECT event_kind FROM dial_usage_log\"}";
        mockMvc.perform(put("/v1/saved-queries/" + id).contentType(JSON).content(updateBody))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/v1/saved-queries/" + id)).andExpect(status().isNoContent());

        feed(filter("resourceId", "eq", id))
                .andExpect(jsonPath("$.data[*].resourceType", everyItem(is("SavedQuery"))))
                .andExpect(jsonPath("$.data[*].parentActivityId").doesNotExist());

        mockMvc.perform(get("/v1/saved-queries/" + id + "/revision/" + createdRevision))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.sql").value("SELECT project_id FROM dial_usage_log"));
        mockMvc.perform(get("/v1/saved-queries/" + id + "/revision/99999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("revision_not_found"));
    }

    @Test
    void materializationIsRecordedAsItsOwnRevision() throws Exception {
        IntegrationTables.source(mockMvc, table, COLUMNS, ORDERING);

        // Defining a schema is deliberately two transactions — the catalog is committed BEFORE the
        // ClickHouse CREATE is attempted, so a failure can never leave an ACTIVE definition without a
        // real table, nor an orphan table with no definition. The trail reports that honestly as two
        // revisions: the schema write, then the confirmation that materialization succeeded. Asserted
        // rather than glossed, because a reader could otherwise expect one endpoint call to be one
        // revision, and because a snapshot taken at the first of them truthfully says `pending`.
        final int schemaWrite = revisionOf(table + ":widget_id");
        final int materialized = revisionOf(table);
        assertThat(materialized).isGreaterThan(schemaWrite);

        mockMvc.perform(get("/v1/tables/" + table + "/revision/" + schemaWrite))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.columns", hasSize(2)));
        mockMvc.perform(get("/v1/tables/" + table + "/revision/" + materialized))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"));
    }

    private static String filter(String column, String operator, String value) {
        return "{\"column\":\"" + column + "\",\"operator\":\"" + operator + "\",\"value\":\"" + value + "\"}";
    }

    private static String sort(String column, String direction) {
        return "{\"column\":\"" + column + "\",\"direction\":\"" + direction + "\"}";
    }

    private org.springframework.test.web.servlet.ResultActions feed(String filterJson) throws Exception {
        return mockMvc.perform(post("/v1/activities").contentType(JSON)
                        .content("{\"filters\":[" + filterJson + "]}"))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions feed(String filterJson, String sortJson)
            throws Exception {
        return mockMvc.perform(post("/v1/activities").contentType(JSON)
                        .content("{\"filters\":[" + filterJson + "],\"sorts\":[" + sortJson + "]}"))
                .andExpect(status().isOk());
    }

    private String feedBody(String filterJson) throws Exception {
        return feed(filterJson).andReturn().getResponse().getContentAsString();
    }

    private static String readJson(String body, String path) {
        return com.jayway.jsonpath.JsonPath.read(body, path).toString();
    }

    /** The revision of the most recent activity for a resource — the handle a snapshot is taken by. */
    private int revisionOf(String resourceId) throws Exception {
        final String body = feed(filter("resourceId", "eq", resourceId), sort("epochTimestampMs", "DESC"))
                .andReturn().getResponse().getContentAsString();
        return Integer.parseInt(readJson(body, "$.data[0].revision"));
    }
}
