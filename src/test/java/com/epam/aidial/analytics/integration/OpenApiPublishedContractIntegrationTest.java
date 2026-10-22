package com.epam.aidial.analytics.integration;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the <b>published</b> OpenAPI document says about the registry's optional enum filters, as opposed to
 * what the handler does with them.
 *
 * <p>These are the assertions whose absence let a contract defect ship. Nothing in this repo asserted
 * {@code /v3/api-docs} at all, so a parameter whose default was correct in code, correct in its own prose
 * description, and <em>absent</em> from the document looked fine at every level a test was looking. It was
 * not fine: a generated client, an OpenAPI-rendered filter form and a {@code <select>} built from an enum
 * with no empty option all fill an optional parameter from its first accepted value or its {@code example},
 * so the published ordering and example <em>are</em> the effective default for every such caller. With
 * {@code compiled} first and {@code example = "compiled"}, callers that never mentioned {@code view} sent
 * {@code view=compiled} — answered 200 with authored declarations under the compiled name until the
 * contract guard turned that silent mis-answer into a refusal.
 *
 * <p>Boots over PostgreSQL only: the document is produced by springdoc from the controller annotations, so
 * no ClickHouse backend is involved in generating it.
 */
class OpenApiPublishedContractIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String API_DOCS = "/v3/api-docs";
    private static final String LIST_PATH = "/v1/pipelines";
    private static final String READ_PATH = "/v1/pipelines/{name}";
    private static final String BACKFILL_PATH = "/v1/data-sync/backfill";

    /**
     * The default has to be published, not merely true. Both reads carry {@code view}, and a consumer that
     * reads only this document must arrive at {@code source} for both.
     *
     * <p>The {@code enum} assertion is not redundant with the default one: the rejected alternative in this
     * change's design — supplying {@code schema} on {@code @Parameter} — can publish the default while
     * <em>replacing</em> springdoc's derived schema, dropping the accepted values entirely. A test that
     * checked only the default would pass that broken document.
     */
    @Test
    void viewPublishesSourceAsItsDefaultOnBothPipelineReads() throws Exception {
        final JsonNode document = document();

        for (final String path : List.of(LIST_PATH, READ_PATH)) {
            final JsonNode view = parameter(document, path, "view");

            assertThat(view.path("schema").path("default").asString())
                    .as("published default of view on GET %s", path)
                    .isEqualTo("source");
            assertThat(values(view))
                    .as("published accepted values of view on GET %s", path)
                    .containsExactly("source", "compiled");
            assertThat(view.path("example").asString())
                    .as("published example of view on GET %s", path)
                    .isEqualTo("source");
        }
    }

    /**
     * {@code kind} is the parameter that must <b>not</b> gain a default, for the opposite reason: an absent
     * {@code kind} returns every kind, so publishing one would silently narrow a listing that asked to be
     * unnarrowed — dropping rows rather than refusing, which is worse than the defect above. It still owes
     * an ordering, because {@code external} is declared but unimplemented and a form picking the first or
     * the middle member must not land on it.
     */
    @Test
    void kindPublishesNoDefaultAndOffersTheUnimplementedKindLast() throws Exception {
        final JsonNode kind = parameter(document(), LIST_PATH, "kind");

        assertThat(kind.path("schema").has("default"))
                .as("kind must publish no default: an absent kind returns every kind")
                .isFalse();
        assertThat(values(kind))
                .as("published accepted values of kind")
                .endsWith("external");
    }

    /**
     * The backfill list's {@code state} filter publishes its <b>wire</b> spelling. The enum has no {@code @JsonValue},
     * so without an explicit schema springdoc would publish the constant names, which the binder accepts but the
     * status never reports. Like {@code kind}, an absent {@code state} means every run, so it must publish no default.
     */
    @Test
    void backfillStatePublishesItsWireValuesAndNoDefault() throws Exception {
        final JsonNode state = parameter(document(), BACKFILL_PATH, "state");

        assertThat(state.path("schema").has("default"))
                .as("state must publish no default: an absent state lists every run")
                .isFalse();
        assertThat(values(state))
                .as("published accepted values of state")
                .containsExactly("running", "completed", "failed");
    }

    private JsonNode document() throws Exception {
        final String body = mockMvc.perform(get(API_DOCS))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonMapper.builder().build().readTree(body);
    }

    /** The named query parameter of a {@code GET}, failing with the ones that are published if it is absent. */
    private static JsonNode parameter(JsonNode document, String path, String name) {
        final JsonNode parameters = document.path("paths").path(path).path("get").path("parameters");
        final List<String> published = new ArrayList<>();

        for (final JsonNode parameter : parameters) {
            final String publishedName = parameter.path("name").asString();
            published.add(publishedName);
            if (name.equals(publishedName)) {
                return parameter;
            }
        }
        throw new AssertionError("GET " + path + " publishes no '" + name + "' parameter; it publishes " + published);
    }

    /** The parameter's published accepted values, in the order the document offers them. */
    private static List<String> values(JsonNode parameter) {
        final List<String> accepted = new ArrayList<>();
        parameter.path("schema").path("enum").forEach(value -> accepted.add(value.asString()));
        return accepted;
    }
}
