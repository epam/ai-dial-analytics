package com.epam.aidial.analytics.integration;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

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
    private static final String ACCESS_PATH = "/v1/tables/{name}/access";
    private static final String PREVIEW = "x-preview";
    private static final String INTERNAL = "x-internal";
    private static final List<String[]> QUERY_OPERATIONS = List.of(
            new String[] {"post", "/v1/queries/execute"},
            new String[] {"post", "/v1/queries/execute-sql"},
            new String[] {"post", "/v1/queries/execute-csv"},
            new String[] {"post", "/v1/queries/translate"},
            new String[] {"get", "/v1/queries/entities"});

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

    /**
     * The access list and the one-role read are two handlers on one path and method, which OpenAPI cannot tell apart.
     * The document must publish them as one {@code GET} whose {@code role} is optional, with both answers described:
     * a required {@code role} would leave a generated client no way to list the entries.
     */
    @Test
    void tableAccessPublishesOneGetWithAnOptionalRoleCoveringListAndRead() throws Exception {
        final JsonNode document = document();
        final JsonNode get = document.path("paths").path(ACCESS_PATH).path("get");
        final JsonNode role = parameter(document, ACCESS_PATH, "role");

        assertThat(role.path("in").asString()).isEqualTo("query");
        assertThat(role.path("required").asBoolean(false)).as("role must be optional: without it the GET lists every entry").isFalse();
        assertThat(role.path("description").asString()).contains("omit it to list every role's entry");
        assertThat(get.path("summary").asString()).contains("List a table's access entries").contains("read one role's entry");
        assertThat(get.path("description").asString()).contains("Without `role`").contains("With `role`");
        final List<String> answers = new ArrayList<>();
        for (final JsonNode media : get.path("responses").path("200").path("content")) {
            media.path("schema").path("oneOf").forEach(shape -> answers.add(shape.path("$ref").asString()));
        }
        assertThat(answers).containsExactlyInAnyOrder("#/components/schemas/AccessEntriesDto", "#/components/schemas/AccessEntryDto");
        for (final String method : List.of("put", "delete")) {
            final JsonNode required = document.path("paths").path(ACCESS_PATH).path(method).path("parameters");
            assertThat(required.toString()).as("%s keeps a required role", method).contains("\"name\":\"role\"");
            for (final JsonNode parameter : required) {
                if ("role".equals(parameter.path("name").asString())) {
                    assertThat(parameter.path("required").asBoolean(false)).as("%s role required", method).isTrue();
                }
            }
        }
    }

    /**
     * Handlers that share a Java method name publish resource-named ids. Derived from the method name, all three
     * were {@code create} and told apart only by a scan-order {@code _N} suffix.
     */
    @Test
    void handlersSharingMethodNamePublishResourceNamedIds() throws Exception {
        final JsonNode document = document();

        assertThat(operation(document, "post", "/v1/dashboards").path("operationId").asString()).isEqualTo("createDashboard");
        assertThat(operation(document, "post", "/v1/saved-queries").path("operationId").asString()).isEqualTo("createSavedQuery");
        assertThat(operation(document, "post", "/v1/tables").path("operationId").asString()).isEqualTo("createTable");
        final List<String> suffixed = new ArrayList<>();
        forEachOperation(document, (name, op) -> {
            if (op.path("operationId").asString().matches(".*_\\d+")) {
                suffixed.add(name);
            }
        });
        assertThat(suffixed).as("operations with a collision suffix").isEmpty();
    }

    /** The query surfaces share one tag, declared once, whose description carries the experimental status. */
    @Test
    void querySurfacesShareTheQueriesTag() throws Exception {
        final JsonNode document = document();

        for (final String[] op : QUERY_OPERATIONS) {
            assertThat(strings(operation(document, op[0], op[1]).path("tags")))
                    .as("tags of %s %s", op[0], op[1])
                    .containsExactly("Queries");
        }
        final List<JsonNode> declared = new ArrayList<>();
        document.path("tags").forEach(tag -> {
            if ("Queries".equals(tag.path("name").asString())) {
                declared.add(tag);
            }
        });
        assertThat(declared).as("the Queries tag is declared once").hasSize(1);
        assertThat(declared.get(0).path("description").asString()).contains("Experimental");
        assertThat(document.toString()).doesNotContain("Structured Queries (experimental)");
    }

    /** {@code x-preview} is the boolean {@code true} on the query surfaces and absent on stable client operations. */
    @Test
    void querySurfacesAreMarkedPreview() throws Exception {
        final JsonNode document = document();

        for (final String[] op : QUERY_OPERATIONS) {
            final JsonNode preview = operation(document, op[0], op[1]).path(PREVIEW);
            assertThat(preview.isBoolean() && preview.asBoolean()).as("%s %s carries x-preview: true as a boolean", op[0], op[1]).isTrue();
        }
        for (final String[] op : List.of(new String[] {"get", LIST_PATH}, new String[] {"post", "/v1/saved-queries"},
                new String[] {"get", "/v1/dashboards"})) {
            assertThat(operation(document, op[0], op[1]).has(PREVIEW)).as("%s %s carries no x-preview", op[0], op[1]).isFalse();
        }
    }

    /**
     * {@code x-internal} marks the OTLP receivers and the runner data plane, and nothing a client calls: not the
     * operator surfaces {@code preview} and {@code refresh} beside the runner endpoints, nor the sink registry.
     */
    @Test
    void machineProducerOperationsAreMarkedInternal() throws Exception {
        final JsonNode document = document();

        for (final String path : List.of("/v1/pipelines/{name}/scan", "/v1/pipelines/{name}/group-rows",
                "/v1/pipelines/{name}/materialize", "/v1/pipelines/{name}/rows", "/v1/otel/sink/{sinkName}/v1/logs",
                "/v1/otel/sink/{sinkName}/v1/traces", "/v1/otel/sink/{sinkName}/v1/metrics")) {
            final JsonNode internal = operation(document, "post", path).path(INTERNAL);
            assertThat(internal.isBoolean() && internal.asBoolean()).as("POST %s carries x-internal: true as a boolean", path).isTrue();
        }
        for (final String[] op : List.of(new String[] {"post", "/v1/pipelines/{name}/preview"},
                new String[] {"post", "/v1/pipelines/{name}/refresh"}, new String[] {"get", LIST_PATH},
                new String[] {"post", "/v1/queries/execute"}, new String[] {"post", "/v1/otel/sinks"})) {
            assertThat(operation(document, op[0], op[1]).has(INTERNAL)).as("%s %s carries no x-internal", op[0], op[1]).isFalse();
        }
    }

    private static JsonNode operation(JsonNode document, String method, String path) {
        final JsonNode operation = document.path("paths").path(path).path(method);
        assertThat(operation.isObject()).as("the document publishes %s %s", method, path).isTrue();
        return operation;
    }

    private static List<String> strings(JsonNode array) {
        final List<String> strings = new ArrayList<>();
        array.forEach(value -> strings.add(value.asString()));
        return strings;
    }

    private static void forEachOperation(JsonNode document, BiConsumer<String, JsonNode> action) {
        final JsonNode paths = document.path("paths");
        for (final String path : paths.propertyNames()) {
            for (final String method : paths.get(path).propertyNames()) {
                action.accept(method + " " + path, paths.get(path).get(method));
            }
        }
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
