package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.web.security.JwtTestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The sensitivity clearance end to end, in {@code oidc} mode, for callers authorized <b>only</b> by a
 * table's grant lists: their raw roles map to no privilege, so nothing global can mask the rule.
 *
 * <p>The table is delegated the way an operator would delegate a department's table: {@code owners} get
 * {@code read}, {@code modify} and {@code sensitive}; {@code stewards} get {@code modify} alone. The owner may
 * see, read and reclassify everything; the steward works on a table whose sensitive column does not exist
 * for it — absent from the definition and its column count, unknown to every schema operation — and may not
 * classify a column it can see.
 */
class SensitiveClearanceIntegrationTest extends AbstractOidcAuthIntegrationTest {

    private static final String BASE = "/v1/tables";
    private static final String TABLE = "clearance_probe";
    private static final String OWNERS = "clearance-owners";
    private static final String STEWARDS = "clearance-stewards";

    @BeforeEach
    void createDelegatedTable() throws Exception {
        perform(post(BASE), fullAdmin(),
                "{\"name\":\"" + TABLE + "\",\"description\":\"clearance probe\",\"type\":\"source\"}")
                .andExpect(status().isCreated());
        perform(post(BASE + "/" + TABLE + "/schema"), fullAdmin(),
                "{\"columns\":[{\"source_name\":\"k\",\"name\":\"k\",\"type\":\"string\"},"
                        + "{\"source_name\":\"amount\",\"name\":\"amount\",\"type\":\"long\"},"
                        // Physical and exposed names differ on purpose: a collision check that looked at only
                        // one of them would let an add take the other and split ClickHouse from the catalog.
                        + "{\"source_name\":\"employee_name_raw\",\"name\":\"employee_name\",\"type\":\"string\","
                        + "\"sensitive\":true}],\"ordering_key\":[\"k\"]}")
                .andExpect(status().isOk());
        perform(put(BASE + "/" + TABLE + "/access"), fullAdmin(),
                "{\"read\":[\"" + OWNERS + "\"],\"modify\":[\"" + OWNERS + "\",\"" + STEWARDS + "\"],"
                        + "\"sensitive\":[\"" + OWNERS + "\"]}")
                .andExpect(status().isOk());
    }

    @AfterEach
    void dropTable() throws Exception {
        mockMvc.perform(delete(BASE + "/" + TABLE).header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin())));
    }

    @Test
    void theOwnerSeesTheSensitiveColumnAndTheStewardDoesNot() throws Exception {
        mockMvc.perform(get(BASE + "/" + TABLE).header(HttpHeaders.AUTHORIZATION, bearer(caller(OWNERS))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns.length()").value(3))
                .andExpect(jsonPath("$.columns[*].name", hasItem("employee_name")))
                .andExpect(jsonPath("$.permissions.sensitive").value(true));

        mockMvc.perform(get(BASE + "/" + TABLE).header(HttpHeaders.AUTHORIZATION, bearer(caller(STEWARDS))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns.length()").value(2))
                .andExpect(jsonPath("$.columns[*].name", not(hasItem("employee_name"))))
                .andExpect(jsonPath("$.permissions.modify").value(true))
                .andExpect(jsonPath("$.permissions.sensitive").value(false));

        // The list count is the caller's too, so it always equals the length of the caller's own columns.
        mockMvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, bearer(caller(STEWARDS))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables[?(@.name == '" + TABLE + "')].column_count").value(2));
        mockMvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, bearer(caller(OWNERS))))
                .andExpect(jsonPath("$.tables[?(@.name == '" + TABLE + "')].column_count").value(3));
    }

    @Test
    void theOwnerReclassifiesInBothDirections() throws Exception {
        perform(patch(BASE + "/" + TABLE + "/schema"), caller(OWNERS),
                "{\"update\":[{\"name\":\"employee_name\",\"sensitive\":false},"
                        + "{\"name\":\"amount\",\"sensitive\":true}]}")
                .andExpect(status().isOk());

        // The declassified column is now visible to the steward, and the newly classified one is not.
        mockMvc.perform(get(BASE + "/" + TABLE).header(HttpHeaders.AUTHORIZATION, bearer(caller(STEWARDS))))
                .andExpect(jsonPath("$.columns[*].name", hasItem("employee_name")))
                .andExpect(jsonPath("$.columns[*].name", not(hasItem("amount"))));
    }

    @Test
    void theStewardMayNotTouchSensitivity() throws Exception {
        perform(patch(BASE + "/" + TABLE + "/schema"), caller(STEWARDS),
                "{\"update\":[{\"name\":\"employee_name\",\"sensitive\":false}]}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value("unknown column 'employee_name'"));
        perform(patch(BASE + "/" + TABLE + "/schema"), caller(STEWARDS), "{\"drop\":[\"employee_name\"]}")
                .andExpect(status().isUnprocessableContent());
        perform(patch(BASE + "/" + TABLE + "/schema"), caller(STEWARDS),
                "{\"update\":[{\"name\":\"amount\",\"sensitive\":true}]}")
                .andExpect(status().isForbidden());
        perform(patch(BASE + "/" + TABLE + "/schema"), caller(STEWARDS),
                "{\"add\":[{\"source_name\":\"salary\",\"name\":\"salary\",\"type\":\"long\",\"sensitive\":true}]}")
                .andExpect(status().isForbidden());

        // Everything that does not touch sensitivity stays the steward's to change.
        perform(patch(BASE + "/" + TABLE + "/schema"), caller(STEWARDS),
                "{\"update\":[{\"name\":\"amount\",\"description\":\"settled amount\"}]}")
                .andExpect(status().isOk());
    }

    @Test
    void collisionWithTheHiddenExposedNameIsRefusedBeforeAnyAlter() throws Exception {
        perform(patch(BASE + "/" + TABLE + "/schema"), caller(STEWARDS),
                "{\"add\":[{\"source_name\":\"e2\",\"name\":\"employee_name\",\"type\":\"string\"}]}")
                .andExpect(status().isUnprocessableContent());
        perform(patch(BASE + "/" + TABLE + "/schema"), caller(STEWARDS),
                "{\"rename\":[{\"from\":\"amount\",\"to\":\"employee_name\"}]}")
                .andExpect(status().isUnprocessableContent());

        // Nothing reached ClickHouse: the table still takes a plain add, and `amount` is where it was.
        perform(patch(BASE + "/" + TABLE + "/schema"), caller(STEWARDS),
                "{\"add\":[{\"source_name\":\"e2\",\"name\":\"e2\",\"type\":\"string\"}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[*].name", hasItem("amount")));
    }

    @Test
    void theClearanceDenialNamesTheMissingPrivilege() throws Exception {
        perform(patch(BASE + "/" + TABLE + "/schema"), caller(STEWARDS),
                "{\"update\":[{\"name\":\"amount\",\"sensitive\":true}]}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("TABLE_SENSITIVE")));
    }

    @Test
    void definingSensitiveColumnWithoutTheClearanceIsForbidden() throws Exception {
        final String pending = "clearance_pending";
        perform(post(BASE), fullAdmin(), "{\"name\":\"" + pending + "\",\"description\":\"p\",\"type\":\"source\"}")
                .andExpect(status().isCreated());
        try {
            perform(put(BASE + "/" + pending + "/access"), fullAdmin(), "{\"modify\":[\"" + STEWARDS + "\"]}")
                    .andExpect(status().isOk());
            final String schema = "{\"columns\":[{\"source_name\":\"k\",\"name\":\"k\",\"type\":\"string\"},"
                    + "{\"source_name\":\"ssn\",\"name\":\"ssn\",\"type\":\"string\",\"sensitive\":true}],"
                    + "\"ordering_key\":[\"k\"]}";
            perform(post(BASE + "/" + pending + "/schema"), caller(STEWARDS), schema)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("ssn")));
            mockMvc.perform(get(BASE + "/" + pending).header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin())))
                    .andExpect(jsonPath("$.status").value("pending"));
        } finally {
            mockMvc.perform(delete(BASE + "/" + pending).header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin())));
        }
    }

    @Test
    void theGrantPrivilegeAloneDoesNotChangeTheFlag() throws Exception {
        final String granter = JwtTestTokens.token(FULL_ISSUER, AUDIENCE,
                Map.of("sub", "granter-user", "roles", List.of("granter"), "email", "granter@test"));
        perform(patch(BASE + "/" + TABLE + "/schema"), granter,
                "{\"update\":[{\"name\":\"amount\",\"sensitive\":true}]}")
                .andExpect(status().isForbidden());
    }

    @Test
    void revisionReadHidesTheSensitiveColumnFromTheSteward() throws Exception {
        // The latest revision of the table is the grant edit in setUp. A revision read is authorized, and its
        // sensitive columns filtered, against the table's live grants — the same ones that revision recorded.
        final String feed = mockMvc.perform(post("/v1/activities")
                        .header(HttpHeaders.AUTHORIZATION, bearer(fullAdmin()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filters\":[{\"column\":\"resourceId\",\"operator\":\"eq\","
                                + "\"value\":\"" + TABLE + "\"}],"
                                + "\"sorts\":[{\"column\":\"epochTimestampMs\",\"direction\":\"DESC\"}]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        final int revision = Integer.parseInt(com.jayway.jsonpath.JsonPath.read(feed, "$.data[0].revision").toString());
        final String path = BASE + "/" + TABLE + "/revision/" + revision;

        mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(caller(STEWARDS))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[*].name", not(hasItem("employee_name"))));
        mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(caller(OWNERS))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[*].name", hasItem("employee_name")));
    }

    @Test
    void theRemovedGrantKeyIsRefused() throws Exception {
        perform(put(BASE + "/" + TABLE + "/access"), fullAdmin(), "{\"read_sensitive\":[\"" + OWNERS + "\"]}")
                .andExpect(status().isBadRequest());
    }

    // --- helpers --------------------------------------------------------------------------------------

    private ResultActions perform(MockHttpServletRequestBuilder request, String token, String body) throws Exception {
        return mockMvc.perform(request
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String fullAdmin() {
        return JwtTestTokens.token(FULL_ISSUER, AUDIENCE,
                Map.of("sub", "full-user", "roles", List.of("admin"), "email", "full@test"));
    }

    /** A caller whose only role is {@code role}, unmapped on the readonly issuer: grants alone decide. */
    private static String caller(String role) {
        return JwtTestTokens.token(READONLY_ISSUER, AUDIENCE,
                Map.of("sub", role + "-user", "roles", List.of(role), "email", role + "@test"));
    }
}
