package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.service.audit.ActivityResourceType;
import com.epam.aidial.analytics.service.audit.ActivityType;
import com.epam.aidial.analytics.service.audit.AuditActivityEntity;
import com.epam.aidial.analytics.service.audit.AuditActivityRepository;
import com.epam.aidial.analytics.service.dashboard.DashboardEntity;
import com.epam.aidial.analytics.service.dashboard.DashboardRepository;
import com.epam.aidial.analytics.service.dashboard.DashboardVisibility;
import com.epam.aidial.analytics.service.table.TableAccess;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import com.epam.aidial.analytics.service.table.TableStatus;
import com.epam.aidial.analytics.service.table.TableType;
import com.epam.aidial.analytics.service.table.TableWriteMode;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The {@code /v1/dashboards} surface in {@code none} security mode, over the full application against the
 * real PostgreSQL catalog and the real translation front ends. ClickHouse is never contacted: an inline
 * body is validated by translation, which plans it and discards the result.
 *
 * <p>{@code none} mode is the fail-open case: no owner is recorded, no owner filter applies, every write is
 * permitted at both visibilities and every permission flag is {@code true}. Visibility and privilege rules
 * are covered in {@link DashboardAuthIntegrationTest}.
 */
class DashboardIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String BASE = "/v1/dashboards";

    /** A structured inline widget carrying every widget member. */
    private static final String QUERY_WIDGET = """
            {"id":"w1","title":"Chats","description":"Per project","row_limit":100,"view":"time_series",
             "value_column":"total","change_direction":"higher_is_better","layout":{"x":0,"y":0,"w":6,"h":4},
             "inline_query":{"name":"Chats by project",
               "query":{"entity":"dial_usage_log","mode":"row","select":[{"expr":{"type":"field","name":"project_id"}}]},
               "result_view":"chart","chart":{"type":"bar","x_field":"project_id"}}}""";

    /** A SQL inline widget with only the required members. */
    private static final String SQL_WIDGET = """
            {"id":"w2","row_limit":10,"layout":{"x":6,"y":0,"w":6,"h":4},
             "inline_query":{"name":"Raw","sql":"SELECT project_id FROM dial_usage_log"}}""";

    /** A widget referencing a saved query that does not exist. */
    private static final String REF_WIDGET = """
            {"id":"w3","saved_query_id":"sq_00000000000000000000000001","row_limit":5,
             "layout":{"x":0,"y":4,"w":12,"h":2}}""";

    @Autowired
    private DashboardRepository dashboards;
    @Autowired
    private TableDefinitionRepository definitions;
    @Autowired
    private AuditActivityRepository activities;
    @Autowired
    private TransactionTemplate transactions;
    @Autowired
    private JdbcTemplate jdbc;

    // ---------------------------------------------------------------- CRUD and shape

    @Test
    void createsReadsAndDeletesDashboard() throws Exception {
        final String created = mockMvc.perform(json(post(BASE)).content(body("CRUD", "private", QUERY_WIDGET + "," + SQL_WIDGET)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(startsWith("dash_")))
                .andExpect(jsonPath("$.generation").isNumber())
                .andExpect(jsonPath("$.created_at").exists())
                .andExpect(jsonPath("$.updated_at").exists())
                // None mode: no owner, so the member is omitted rather than sent as null.
                .andExpect(jsonPath("$.owner_id").doesNotExist())
                .andExpect(jsonPath("$.description").doesNotExist())
                .andExpect(jsonPath("$.widgets[0].id").value("w1"))
                .andExpect(jsonPath("$.widgets[1].id").value("w2"))
                .andExpect(jsonPath("$.permissions.write").value(true))
                .andExpect(jsonPath("$.permissions.publish").value(true))
                .andReturn().getResponse().getContentAsString();
        final String id = JsonPath.read(created, "$.id");

        mockMvc.perform(get(BASE + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("CRUD"))
                .andExpect(jsonPath("$.visibility").value("private"));

        mockMvc.perform(delete(BASE + "/" + id)).andExpect(status().isNoContent());
        mockMvc.perform(get(BASE + "/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void roundTripsEveryWidgetMemberTheTimeAndTheComparison() throws Exception {
        final String id = create(body("Round trip", "public", QUERY_WIDGET,
                "\"description\":\"All of it\",\"time\":{\"mode\":\"relative\",\"period\":\"last_7d\"},"
                        + "\"compare\":\"previous-period\""));

        mockMvc.perform(get(BASE + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("All of it"))
                .andExpect(jsonPath("$.time.mode").value("relative"))
                .andExpect(jsonPath("$.time.period").value("last_7d"))
                .andExpect(jsonPath("$.time.from").doesNotExist())
                .andExpect(jsonPath("$.compare").value("previous-period"))
                .andExpect(jsonPath("$.widgets[0].title").value("Chats"))
                .andExpect(jsonPath("$.widgets[0].description").value("Per project"))
                .andExpect(jsonPath("$.widgets[0].row_limit").value(100))
                .andExpect(jsonPath("$.widgets[0].view").value("time_series"))
                .andExpect(jsonPath("$.widgets[0].value_column").value("total"))
                .andExpect(jsonPath("$.widgets[0].change_direction").value("higher_is_better"))
                .andExpect(jsonPath("$.widgets[0].layout.w").value(6))
                .andExpect(jsonPath("$.widgets[0].inline_query.name").value("Chats by project"))
                .andExpect(jsonPath("$.widgets[0].inline_query.query.entity").value("dial_usage_log"))
                .andExpect(jsonPath("$.widgets[0].inline_query.result_view").value("chart"))
                .andExpect(jsonPath("$.widgets[0].inline_query.chart.x_field").value("project_id"));
    }

    @Test
    void omitsOptionalWidgetMembersAndFillsInTheResultView() throws Exception {
        final String id = create(body("Sparse", "private", SQL_WIDGET));

        mockMvc.perform(get(BASE + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.compare").doesNotExist())
                .andExpect(jsonPath("$.time").doesNotExist())
                .andExpect(jsonPath("$.widgets[0].title").doesNotExist())
                .andExpect(jsonPath("$.widgets[0].view").doesNotExist())
                .andExpect(jsonPath("$.widgets[0].change_direction").doesNotExist())
                .andExpect(jsonPath("$.widgets[0].saved_query_id").doesNotExist())
                .andExpect(jsonPath("$.widgets[0].inline_query.result_view").value("table"));
    }

    @Test
    void keepsAnExplicitOffComparisonAndTrimsTheName() throws Exception {
        final String id = create(body("  Off  ", "private", "", "\"compare\":\"off\""));

        mockMvc.perform(get(BASE + "/" + id))
                .andExpect(jsonPath("$.name").value("Off"))
                .andExpect(jsonPath("$.compare").value("off"));
    }

    @Test
    void doesNotRoundTripBlankReferenceOrSqlBody() throws Exception {
        final String widget = QUERY_WIDGET.replace("{\"id\":\"w1\",", "{\"id\":\"w1\",\"saved_query_id\":\"\",")
                .replace("\"result_view\":\"chart\"", "\"sql\":\"  \",\"result_view\":\"chart\"");
        final String id = create(body("Blanks", "private", widget));

        mockMvc.perform(get(BASE + "/" + id))
                .andExpect(jsonPath("$.widgets[0].saved_query_id").doesNotExist())
                .andExpect(jsonPath("$.widgets[0].inline_query.sql").doesNotExist())
                .andExpect(jsonPath("$.widgets[0].inline_query.query.entity").value("dial_usage_log"));
    }

    @Test
    void listsItemsWithoutWidgetsMostRecentlyUpdatedFirst() throws Exception {
        final String older = create(body("List older", "private", SQL_WIDGET));
        final String newer = create(body("List newer", "public", SQL_WIDGET, "\"description\":\"Shown\""));

        mockMvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions.create_private").value(true))
                .andExpect(jsonPath("$.permissions.create_public").value(true))
                .andExpect(jsonPath("$.dashboards[?(@.id=='" + newer + "')].description").value("Shown"))
                .andExpect(jsonPath("$.dashboards[?(@.id=='" + newer + "')].widgets").isEmpty())
                .andExpect(jsonPath("$.dashboards[?(@.id=='" + older + "')].description").isEmpty())
                .andExpect(jsonPath("$.dashboards[?(@.id=='" + older + "')].permissions.write").value(true));

        replace(older, body("List older, edited", "private", SQL_WIDGET), generationOf(older));
        final String list = mockMvc.perform(get(BASE)).andReturn().getResponse().getContentAsString();
        final List<String> ids = JsonPath.read(list, "$.dashboards[*].id");
        assertThat(ids.indexOf(older)).isLessThan(ids.indexOf(newer));
    }

    @Test
    void createsBothVisibilitiesWithNoOwnerInNoneMode() throws Exception {
        final String privateId = create(body("None private", "private", ""));
        final String publicId = create(body("None public", "public", ""));

        mockMvc.perform(get(BASE))
                .andExpect(jsonPath("$.dashboards[?(@.id=='" + privateId + "')]", hasSize(1)))
                .andExpect(jsonPath("$.dashboards[?(@.id=='" + publicId + "')]", hasSize(1)))
                .andExpect(jsonPath("$.dashboards[?(@.id=='" + publicId + "')].permissions.publish").value(true));
        mockMvc.perform(get(BASE + "/" + privateId)).andExpect(jsonPath("$.owner_id").doesNotExist());
    }

    // ---------------------------------------------------------------- references and inline bodies

    @Test
    void storesReferenceToSavedQueryThatDoesNotExist() throws Exception {
        mockMvc.perform(json(post(BASE)).content(body("Loose", "private", REF_WIDGET)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.widgets[0].saved_query_id").value("sq_00000000000000000000000001"));
    }

    @Test
    void deletingReferencedSavedQueryLeavesTheDashboardUnchanged() throws Exception {
        final String savedQuery = JsonPath.read(mockMvc.perform(json(post("/v1/saved-queries"))
                        .content("{\"name\":\"Referenced\",\"sql\":\"SELECT project_id FROM dial_usage_log\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
        final String id = create(body("References a saved query", "public",
                REF_WIDGET.replace("sq_00000000000000000000000001", savedQuery)));
        final String before = mockMvc.perform(get(BASE + "/" + id)).andReturn().getResponse().getContentAsString();

        mockMvc.perform(delete("/v1/saved-queries/" + savedQuery)).andExpect(status().isNoContent());

        final String after = mockMvc.perform(get(BASE + "/" + id)).andReturn().getResponse().getContentAsString();
        assertThat(after).isEqualTo(before);
    }

    @Test
    void deletingTableAnInlineBodyReadsLeavesTheDashboardUnchanged() {
        // Stored directly: the table is never materialized, so a body reading it could not pass translation.
        // What is under test is that removing the catalog row cascades to nothing.
        final String table = "dashboard_cascade_probe";
        transactions.executeWithoutResult(status -> {
            final TableDefinitionEntity definition = new TableDefinitionEntity();
            definition.setName(table);
            definition.setType(TableType.SOURCE);
            definition.setStatus(TableStatus.PENDING);
            definition.setWriteMode(TableWriteMode.APPEND);
            definition.setAccess(new TableAccess());
            definitions.save(definition);
        });
        final DashboardEntity saved = transactions.execute(status -> {
            final DashboardEntity entity = new DashboardEntity();
            entity.setId("dash_cascadeprobe00000000000000001");
            entity.setName("Reads a table");
            entity.setVisibility(DashboardVisibility.PUBLIC);
            entity.setWidgets("[{\"id\":\"w1\",\"row_limit\":1,\"layout\":{\"x\":0,\"y\":0,\"w\":1,\"h\":1},"
                    + "\"inline_query\":{\"name\":\"q\",\"sql\":\"SELECT * FROM " + table + "\",\"result_view\":\"table\"}}]");
            entity.setCreatedAt(Instant.now());
            entity.setUpdatedAt(Instant.now());
            return dashboards.saveAndFlush(entity);
        });
        // Re-read, so both sides of the comparison carry the column's microsecond precision.
        final DashboardEntity stored = dashboards.findById(saved.getId()).orElseThrow();

        transactions.executeWithoutResult(status -> definitions.deleteById(table));

        final DashboardEntity after = dashboards.findById(stored.getId()).orElseThrow();
        assertThat(after.getGeneration()).isEqualTo(stored.getGeneration());
        assertThat(after.getUpdatedAt()).isEqualTo(stored.getUpdatedAt());
        assertThat(after.getWidgets()).contains(table);
    }

    @Test
    void widgetReadingDeletedTableBlocksEveryReplaceThatKeepsIt() throws Exception {
        // Stored directly, naming a table with no catalog row: to translation, a deleted table is one that
        // does not exist.
        final String table = "dashboard_deleted_table_probe";
        final String widget = SQL_WIDGET.replace("dial_usage_log", table);
        final String id = transactions.execute(status -> {
            final DashboardEntity entity = new DashboardEntity();
            entity.setId("dash_deletedtableprobe0000000000001");
            entity.setName("Reads a deleted table");
            entity.setVisibility(DashboardVisibility.PUBLIC);
            entity.setWidgets("[" + widget + "]");
            entity.setCreatedAt(Instant.now());
            entity.setUpdatedAt(Instant.now());
            return dashboards.saveAndFlush(entity).getId();
        });
        final long generation = generationOf(id);

        // Even a rename re-translates the stored body, and nobody can resolve a table that is gone.
        mockMvc.perform(json(put(BASE + "/" + id)).content(withGeneration(body("Renamed", "public", widget), generation)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(startsWith("widget w2: ")));
        assertThat(dashboards.findById(id).orElseThrow().getName()).isEqualTo("Reads a deleted table");

        // Replacing the broken widget is the way out.
        replace(id, body("Renamed", "public", SQL_WIDGET), generation);
        assertThat(dashboards.findById(id).orElseThrow().getName()).isEqualTo("Renamed");
    }

    @Test
    void rejectsAnUntranslatableInlineBodyNamingTheWidget() throws Exception {
        final String widget = SQL_WIDGET.replace("SELECT project_id FROM dial_usage_log", "SELEKT nonsense");
        mockMvc.perform(json(post(BASE)).content(body("Broken", "private", widget)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value(startsWith("widget w2: ")));
    }

    @Test
    void rejectsAnInlineBodyReadingAnUnresolvableEntity() throws Exception {
        final String widget = SQL_WIDGET.replace("dial_usage_log", "no_such_entity_anywhere");
        mockMvc.perform(json(post(BASE)).content(body("Unresolvable", "private", widget)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value(startsWith("widget w2: ")));
    }

    @Test
    void hasNoExecuteEndpoint() throws Exception {
        final String id = create(body("Not executable", "private", SQL_WIDGET));
        mockMvc.perform(json(post(BASE + "/" + id + "/execute")).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void acceptsAnInlineBodyBindingLiteralToSensitiveColumn() throws Exception {
        final String widget = SQL_WIDGET.replace("SELECT project_id FROM dial_usage_log",
                "SELECT project_id FROM dial_usage_log WHERE \\\"dial_usage_log_payload.request_body\\\" = 'a secret'");
        mockMvc.perform(json(post(BASE)).content(body("Sensitive literal", "private", widget)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.widgets[0].inline_query.sql").value(containsString("'a secret'")));
    }

    // ---------------------------------------------------------------- binding and validation

    @Test
    void rejectsDashboardWithoutWidgetsButAcceptsAnEmptyArray() throws Exception {
        mockMvc.perform(json(post(BASE)).content("{\"name\":\"No widgets\",\"visibility\":\"private\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"));
        mockMvc.perform(json(post(BASE)).content(body("Empty widgets", "private", "")))
                .andExpect(status().isCreated());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"visibility\":1", "\"visibility\":\"1\"", "\"visibility\":\"secret\"", "\"compare\":1"})
    void rejectsAnEnumThatIsNotOneOfItsTokens(String member) throws Exception {
        final String request = "{\"name\":\"Enum\",\"widgets\":[]," + member
                + (member.startsWith("\"visibility\"") ? "" : ",\"visibility\":\"private\"") + "}";
        mockMvc.perform(json(post(BASE)).content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"view\":2", "\"view\":\"pie\"", "\"view\":\"1\""})
    void rejectsWidgetViewThatIsNotOneOfItsTokens(String view) throws Exception {
        final String widget = SQL_WIDGET.replace("\"row_limit\":10", view + ",\"row_limit\":10");
        mockMvc.perform(json(post(BASE)).content(body("View", "private", widget)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @Test
    void rejectsNumericInlineResultView() throws Exception {
        final String widget = SQL_WIDGET.replace("\"name\":\"Raw\"", "\"name\":\"Raw\",\"result_view\":1");
        mockMvc.perform(json(post(BASE)).content(body("Result view", "private", widget)))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2.5", "2.0", "\"5\"", "0", "1001"})
    void rejectsRowLimitThatIsNotWholeNumberInRangeWithoutCoercingIt(String rowLimit) throws Exception {
        final String widget = SQL_WIDGET.replace("\"row_limit\":10", "\"row_limit\":" + rowLimit);
        mockMvc.perform(json(post(BASE)).content(body("Row limit", "private", widget)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"));
    }

    @Test
    void rejectsWidgetPastTheRightEdge() throws Exception {
        final String widget = SQL_WIDGET.replace("{\"x\":6,\"y\":0,\"w\":6,\"h\":4}", "{\"x\":8,\"y\":0,\"w\":6,\"h\":4}");
        mockMvc.perform(json(post(BASE)).content(body("Too wide", "private", widget)))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void rejectsAnUnknownMemberNamingIt() throws Exception {
        mockMvc.perform(json(post(BASE)).content(body("Unknown", "private", "", "\"colour\":\"red\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("'colour'")));
    }

    @Test
    void rejectsSavedQueryCopiedWholeIntoWidget() throws Exception {
        final String widget = SQL_WIDGET.replace("\"name\":\"Raw\"",
                "\"name\":\"Raw\",\"time\":{\"mode\":\"relative\",\"period\":\"last_2d\"}");
        mockMvc.perform(json(post(BASE)).content(body("Copied whole", "private", widget)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("'time'")));
    }

    @Test
    void rejectsServerAssignedMembersOnCreateAndOnReplace() throws Exception {
        mockMvc.perform(json(post(BASE)).content(body("Assigned", "private", "", "\"generation\":0")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(containsString("generation")));
        final String id = create(body("Assigned", "private", ""));
        mockMvc.perform(json(put(BASE + "/" + id))
                        .content(body("Assigned", "private", "", "\"generation\":" + generationOf(id) + ",\"owner_id\":\"x\"")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(containsString("owner_id")));
    }

    // ---------------------------------------------------------------- generation

    @Test
    void replacesWithTheCurrentGenerationAndIncrementsIt() throws Exception {
        final String id = create(body("Generation", "private", SQL_WIDGET));
        final long generation = generationOf(id);

        mockMvc.perform(json(put(BASE + "/" + id)).content(body("Generation, edited", "private", SQL_WIDGET,
                        "\"generation\":" + generation)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generation").value(generation + 1))
                .andExpect(jsonPath("$.name").value("Generation, edited"));
    }

    @Test
    void refusesStaleReplaceAndChangesNothing() throws Exception {
        final String id = create(body("Stale", "private", SQL_WIDGET));
        final long generation = generationOf(id);
        replace(id, body("Stale, moved on", "private", SQL_WIDGET), generation);

        mockMvc.perform(json(put(BASE + "/" + id)).content(body("Stale, overwritten", "private", SQL_WIDGET,
                        "\"generation\":" + generation)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("dashboard_conflict"));
        mockMvc.perform(get(BASE + "/" + id))
                .andExpect(jsonPath("$.name").value("Stale, moved on"))
                .andExpect(jsonPath("$.generation").value(generation + 1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ",\"generation\":null", ",\"generation\":\"3\"", ",\"generation\":3.0", ",\"generation\":3.5"})
    void rejectsReplaceWithoutUsableGeneration(String generation) throws Exception {
        final String id = create(body("No generation", "private", ""));
        mockMvc.perform(json(put(BASE + "/" + id))
                        .content("{\"name\":\"No generation\",\"visibility\":\"private\",\"widgets\":[]" + generation + "}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("validation_error"))
                .andExpect(jsonPath("$.message").value(containsString("generation")));
    }

    @Test
    void ignoresIfMatch() throws Exception {
        final String id = create(body("If-Match", "private", ""));
        mockMvc.perform(json(put(BASE + "/" + id)).header("If-Match", "\"999\"")
                        .content(body("If-Match", "private", "", "\"generation\":" + generationOf(id))))
                .andExpect(status().isOk());
    }

    @Test
    void twoConcurrentReplacesFromOneReadGiveOneSuccessAndOneConflict() throws Exception {
        final String id = create(body("Race", "private", SQL_WIDGET));
        final long generation = generationOf(id);

        final List<Integer> statuses = concurrently(List.of(
                () -> putStatus(id, body("Race A", "private", SQL_WIDGET, "\"generation\":" + generation)),
                () -> putStatus(id, body("Race B", "private", SQL_WIDGET, "\"generation\":" + generation))));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        mockMvc.perform(get(BASE + "/" + id)).andExpect(jsonPath("$.generation").value(generation + 1));
    }

    @Test
    void twoConcurrentDeletesGiveOneSuccess() throws Exception {
        final String id = create(body("Double delete", "private", ""));

        final List<Integer> statuses = concurrently(List.of(
                () -> mockMvc.perform(delete(BASE + "/" + id)).andReturn().getResponse().getStatus(),
                () -> mockMvc.perform(delete(BASE + "/" + id)).andReturn().getResponse().getStatus()));

        assertThat(statuses).containsOnlyOnce(204);
        assertThat(statuses).anyMatch(status -> status == 404 || status == 409);
    }

    // ---------------------------------------------------------------- audit

    @Test
    void recordsCreateUpdateAndDeleteAsDashboardActivitiesWithNoParent() throws Exception {
        final String id = create(body("Audited", "private", SQL_WIDGET));
        replace(id, body("Audited, edited", "private", SQL_WIDGET), generationOf(id));
        mockMvc.perform(delete(BASE + "/" + id)).andExpect(status().isNoContent());

        final List<AuditActivityEntity> recorded = activitiesOf(id);
        assertThat(recorded).extracting(AuditActivityEntity::getActivityType)
                .containsExactlyInAnyOrder(ActivityType.Create, ActivityType.Update, ActivityType.Delete);
        assertThat(recorded).allSatisfy(activity -> {
            assertThat(activity.getResourceType()).isEqualTo(ActivityResourceType.Dashboard);
            assertThat(activity.getParentActivityId()).isNull();
        });
    }

    @Test
    void conflictOrReadRecordsNothing() throws Exception {
        final String id = create(body("Quiet", "private", SQL_WIDGET));
        final long generation = generationOf(id);
        replace(id, body("Quiet, edited", "private", SQL_WIDGET), generation);
        final int before = activitiesOf(id).size();

        mockMvc.perform(json(put(BASE + "/" + id)).content(body("Quiet, stale", "private", SQL_WIDGET,
                "\"generation\":" + generation))).andExpect(status().isConflict());
        mockMvc.perform(get(BASE + "/" + id)).andExpect(status().isOk());
        mockMvc.perform(get(BASE)).andExpect(status().isOk());

        assertThat(activitiesOf(id)).hasSize(before);
    }

    @Test
    void theAuditMirrorDoesNotCarryTheGeneration() {
        final List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'dashboard_aud'", String.class);
        assertThat(columns).contains("widgets", "visibility").doesNotContain("generation");
    }

    // ---------------------------------------------------------------- helpers

    private String create(String body) throws Exception {
        return JsonPath.read(mockMvc.perform(json(post(BASE)).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    private void replace(String id, String body, long generation) throws Exception {
        mockMvc.perform(json(put(BASE + "/" + id)).content(withGeneration(body, generation))).andExpect(status().isOk());
    }

    private static String withGeneration(String body, long generation) {
        return body.substring(0, body.length() - 1) + ",\"generation\":" + generation + "}";
    }

    private int putStatus(String id, String body) throws Exception {
        return mockMvc.perform(json(put(BASE + "/" + id)).content(body)).andReturn().getResponse().getStatus();
    }

    private long generationOf(String id) throws Exception {
        final Number generation = JsonPath.read(
                mockMvc.perform(get(BASE + "/" + id)).andReturn().getResponse().getContentAsString(), "$.generation");
        return generation.longValue();
    }

    private List<AuditActivityEntity> activitiesOf(String id) {
        return activities.findAll().stream().filter(activity -> id.equals(activity.getResourceId())).toList();
    }

    private static List<Integer> concurrently(List<Callable<Integer>> calls) throws Exception {
        final ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            final List<Integer> statuses = new ArrayList<>();
            for (final Future<Integer> future : pool.invokeAll(calls)) {
                statuses.add(future.get());
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder) {
        return builder.contentType(MediaType.APPLICATION_JSON);
    }

    private static String body(String name, String visibility, String widgets) {
        return body(name, visibility, widgets, null);
    }

    private static String body(String name, String visibility, String widgets, String extra) {
        return "{\"name\":\"" + name + "\",\"visibility\":\"" + visibility + "\",\"widgets\":[" + widgets + "]"
                + (extra == null ? "" : "," + extra) + "}";
    }
}
