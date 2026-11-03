package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.service.audit.AuditActivityEntity;
import com.epam.aidial.analytics.service.audit.AuditActivityRepository;
import com.epam.aidial.analytics.service.dashboard.DashboardAccess;
import com.epam.aidial.analytics.service.dashboard.DashboardVisibility;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The race the early generation check cannot see: another write commits after a replace or delete has
 * loaded the dashboard, so the comparison against the loaded {@code generation} passes and only the
 * versioned {@code UPDATE} or {@code DELETE} at flush can refuse it. The interfering write is committed on
 * another connection from inside the authority check, which runs after the lookup, so the overlap is forced
 * rather than left to thread scheduling. The loser SHALL answer {@code 409 dashboard_conflict}, change
 * nothing and record no activity.
 */
class DashboardWriteRaceIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String BASE = "/v1/dashboards";

    @MockitoSpyBean
    private DashboardAccess access;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AuditActivityRepository activities;

    /** Committed once, on another connection, the next time a write checks its authority. */
    private final AtomicReference<Runnable> interference = new AtomicReference<>();

    @BeforeEach
    void interfereFromTheAuthorityCheck() {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            final Runnable write = interference.getAndSet(null);
            if (write != null) {
                onAnotherConnection(write);
            }
            return null;
        }).when(access).requireWritable(any(DashboardVisibility.class));
    }

    @Test
    void replaceLosingToReplaceCommittedAfterItsLookupIsConflict() throws Exception {
        final String id = create("Raced replace");
        final long generation = generationOf(id);
        final int before = activitiesOf(id).size();
        interference.set(() -> jdbc.update("UPDATE dashboard SET generation = generation + 1 WHERE id = ?", id));

        mockMvc.perform(put(BASE + "/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(body("Raced replace, lost") + ",\"generation\":" + generation + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("dashboard_conflict"));

        mockMvc.perform(get(BASE + "/" + id))
                .andExpect(jsonPath("$.name").value("Raced replace"))
                .andExpect(jsonPath("$.generation").value(generation + 1));
        assertThat(activitiesOf(id)).hasSize(before);
    }

    @Test
    void replaceLosingToDeleteCommittedAfterItsLookupIsConflict() throws Exception {
        final String id = create("Replace raced by delete");
        final long generation = generationOf(id);
        interference.set(() -> jdbc.update("DELETE FROM dashboard WHERE id = ?", id));

        mockMvc.perform(put(BASE + "/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(body("Replace raced by delete, lost") + ",\"generation\":" + generation + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("dashboard_conflict"));

        mockMvc.perform(get(BASE + "/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void deleteLosingToReplaceCommittedAfterItsLookupIsConflict() throws Exception {
        final String id = create("Delete raced by replace");
        final long generation = generationOf(id);
        final int before = activitiesOf(id).size();
        interference.set(() -> jdbc.update("UPDATE dashboard SET generation = generation + 1 WHERE id = ?", id));

        mockMvc.perform(delete(BASE + "/" + id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("dashboard_conflict"));

        mockMvc.perform(get(BASE + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generation").value(generation + 1));
        assertThat(activitiesOf(id)).hasSize(before);
    }

    private String create(String name) throws Exception {
        return JsonPath.read(mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body(name) + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    private long generationOf(String id) throws Exception {
        final Number generation = JsonPath.read(
                mockMvc.perform(get(BASE + "/" + id)).andReturn().getResponse().getContentAsString(), "$.generation");
        return generation.longValue();
    }

    private List<AuditActivityEntity> activitiesOf(String id) {
        return activities.findAll().stream().filter(activity -> id.equals(activity.getResourceId())).toList();
    }

    /** An open body, closed by the caller, so a replace can append its {@code generation}. */
    private static String body(String name) {
        return "{\"name\":\"" + name + "\",\"visibility\":\"private\",\"widgets\":[]";
    }

    /**
     * Runs the write on a thread with no transaction bound, so it autocommits on its own connection instead
     * of joining the request's transaction.
     */
    private static void onAnotherConnection(Runnable write) throws Exception {
        final ExecutorService thread = Executors.newSingleThreadExecutor();
        try {
            thread.submit(write).get();
        } finally {
            thread.shutdownNow();
        }
    }
}
