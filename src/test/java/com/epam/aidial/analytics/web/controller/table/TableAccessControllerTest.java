package com.epam.aidial.analytics.web.controller.table;

import com.epam.aidial.analytics.service.table.TableAccessService;
import com.epam.aidial.analytics.service.table.TableAccessService.AccessEntry;
import com.epam.aidial.analytics.service.table.TableAccessService.EntryReplacement;
import com.epam.aidial.analytics.service.table.TableAccessService.RoleEntry;
import com.epam.aidial.analytics.web.security.Privilege;
import org.junit.jupiter.api.Test;
import org.mockito.BDDMockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Binding of {@code /v1/tables/{name}/access?role=}: a missing or blank {@code role}, or a {@code PUT} body that does not
 * bind — an unknown member, an unknown {@code rows} member, a {@code privileges} or {@code columns} that is not an array
 * of strings — is {@code 400} before the service is asked anything, so before the privilege, table and generation checks
 * it runs. The role is an ordinary query value: any character arrives decoded and trimmed.
 */
@WebMvcTest(TableAccessController.class)
@AutoConfigureMockMvc(addFilters = false)
class TableAccessControllerTest {

    private static final String ENTRY = "/v1/tables/t/access?role=X";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TableAccessService service;

    @Test
    void bodiesThatDoNotBindAre400BeforeTheService() throws Exception {
        for (final String body : List.of(
                "{\"generation\":1,\"list\":[\"x\"]}",
                "{\"generation\":1,\"rows\":{\"all_rows\":true}}",
                "{\"generation\":1,\"privileges\":\"read\"}",
                "{\"generation\":1,\"privileges\":[[\"read\"]]}",
                "{\"generation\":1,\"privileges\":[null]}",
                "{\"generation\":1,\"privileges\":[1]}",
                "{\"generation\":1,\"columns\":[[\"a\"]]}",
                "{\"generation\":1,\"columns\":[null]}",
                "{\"generation\":1,\"columns\":[1]}",
                "{\"generation\":1,\"rows\":{\"where\":5}}",
                "{\"generation\":1,\"rows\":{\"where\":true}}",
                "{\"generation\":1,\"rows\":{\"where\":[\"a = 'x'\"]}}",
                "{\"generation\":1,")) {
            mockMvc.perform(put(ENTRY).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("bad_request"));
        }
        verifyNoInteractions(service);
    }

    @Test
    void missingOrBlankRoleIs400BeforeTheService() throws Exception {
        final String body = "{\"generation\":1,\"privileges\":[\"read\"]}";
        mockMvc.perform(put("/v1/tables/t/access").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
        mockMvc.perform(delete("/v1/tables/t/access")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("bad_request"));
        // MockMvc does not decode a query written into the URL template, so a value is passed as a parameter.
        for (final String blank : List.of("", "  ")) {
            mockMvc.perform(put("/v1/tables/t/access").param("role", blank).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("bad_request"));
            mockMvc.perform(delete("/v1/tables/t/access").param("role", blank))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("bad_request"));
            mockMvc.perform(get("/v1/tables/t/access").param("role", blank)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @Test
    void repeatedRoleIs400BeforeTheService() throws Exception {
        mockMvc.perform(put("/v1/tables/t/access").param("role", "a", "b").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"generation\":1,\"privileges\":[\"read\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value("role must be given once"));
        mockMvc.perform(delete("/v1/tables/t/access").param("role", "a", "b")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
        mockMvc.perform(get("/v1/tables/t/access").param("role", "a", "b")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
        verifyNoInteractions(service);
    }

    @Test
    void anyCharacterInTheRoleArrivesDecodedAndTrimmed() throws Exception {
        BDDMockito.given(service.get(eq("t"), any())).willReturn(new RoleEntry(2, "x", new AccessEntry(List.of(), null, List.of("a")), List.of()));
        for (final String role : List.of("/analytics/viewers", "team%ops", "a;b", "a\\b", "//x", ".", "..")) {
            mockMvc.perform(get("/v1/tables/t/access").param("role", " " + role + " ")).andExpect(status().isOk());
            org.mockito.Mockito.verify(service).get("t", role);
        }
    }

    @Test
    void deletePassesTheOptionalGenerationAsSent() throws Exception {
        mockMvc.perform(delete("/v1/tables/t/access?role=X")).andExpect(status().isNoContent());
        org.mockito.Mockito.verify(service).delete("t", "X", null);
        mockMvc.perform(delete("/v1/tables/t/access?role=X&generation=7")).andExpect(status().isNoContent());
        org.mockito.Mockito.verify(service).delete("t", "X", "7");
    }

    @Test
    void anUnknownMemberIsNamed() throws Exception {
        mockMvc.perform(put(ENTRY).contentType(MediaType.APPLICATION_JSON).content("{\"generation\":1,\"list\":[\"x\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("list")));
    }

    @Test
    void bindingBodyReachesTheServiceAndAnswersTheStoredEntry() throws Exception {
        BDDMockito.given(service.put(eq("t"), eq("X"), any())).willReturn(new EntryReplacement(5, "X",
                new AccessEntry(List.of(Privilege.TABLE_READ), null, List.of("a")), List.of()));

        mockMvc.perform(put(ENTRY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"generation\":4,\"privileges\":[\"read\"],\"columns\":[\"a\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generation").value(5))
                .andExpect(jsonPath("$.role").value("X"))
                .andExpect(jsonPath("$.privileges[0]").value("read"))
                .andExpect(jsonPath("$.rows").isEmpty())
                .andExpect(jsonPath("$.columns[0]").value("a"))
                .andExpect(jsonPath("$.ineffective_grants").isArray())
                .andExpect(jsonPath("$.permissions").doesNotExist());
    }
}
