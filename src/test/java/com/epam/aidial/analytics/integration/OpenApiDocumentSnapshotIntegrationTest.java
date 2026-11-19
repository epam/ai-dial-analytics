package com.epam.aidial.analytics.integration;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The committed {@code docs/openapi.json} is the document this code serves, normalised.
 *
 * <p>By default the test compares the two and fails with the first differing JSON pointer and the regenerate
 * command. With {@code -Dopenapi.update=true}, which {@code ./gradlew updateOpenApi} sets, it writes the file
 * instead. Runs on the shared PostgreSQL context: springdoc builds the document from controller annotations, so
 * neither ClickHouse nor a credential is involved. See {@code openspec/specs/openapi-document/spec.md}.
 */
class OpenApiDocumentSnapshotIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String API_DOCS = "/v3/api-docs";
    private static final String APPLICATION_PACKAGE = "com.epam.aidial.analytics.";
    private static final Set<String> HTTP_METHODS = Set.of("get", "put", "post", "delete", "patch", "head", "options", "trace");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void committedDocumentMatchesTheServedDocument() throws Exception {
        final Path file = Path.of(System.getProperty("openapi.document", "docs/openapi.json"));
        final JsonNode served = OpenApiDocuments.normalise(OpenApiDocuments.parse(servedDocument()));
        final String rendered = OpenApiDocuments.render(served);

        if (Boolean.getBoolean("openapi.update")) {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, rendered, StandardCharsets.UTF_8);
            return;
        }

        assertThat(file)
                .as("%s is missing; run %s and commit it", file, OpenApiDocuments.REGENERATE_COMMAND)
                .exists();
        final String committed = Files.readString(file, StandardCharsets.UTF_8);
        if (committed.equals(rendered)) {
            return;
        }
        final Optional<String> difference = OpenApiDocuments.firstDifference(OpenApiDocuments.parse(committed), served);
        throw new AssertionError(file + " no longer matches the document the code serves"
                + difference.map(pointer -> " (first difference at " + pointer + ")").orElse(" (formatting only)")
                + ". Run " + OpenApiDocuments.REGENERATE_COMMAND + " and commit the result.");
    }

    /**
     * Every published handler of the application is in the document, and nothing else is. The document is built
     * over the PostgreSQL-only context, so a controller missing from that context would otherwise be dropped from
     * the committed file without anything failing.
     */
    @Test
    void documentPublishesExactlyTheApplicationHandlers() throws Exception {
        final Set<String> handlers = new TreeSet<>();
        for (final Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            final HandlerMethod handler = entry.getValue();
            if (!handler.getBeanType().getName().startsWith(APPLICATION_PACKAGE) || hidden(handler)) {
                continue;
            }
            final Set<String> methods = new TreeSet<>();
            entry.getKey().getMethodsCondition().getMethods().forEach(method -> methods.add(method.name().toLowerCase()));
            assertThat(methods).as("%s maps no HTTP method, so it cannot be published as one operation", handler).isNotEmpty();
            for (final String path : entry.getKey().getPatternValues()) {
                methods.forEach(method -> handlers.add(method + " " + path));
            }
        }

        final Set<String> operations = new TreeSet<>();
        final JsonNode paths = OpenApiDocuments.parse(servedDocument()).path("paths");
        for (final String path : paths.propertyNames()) {
            for (final String method : paths.get(path).propertyNames()) {
                if (HTTP_METHODS.contains(method)) {
                    operations.add(method + " " + path);
                }
            }
        }

        assertThat(operations)
                .as("the published operations are exactly the application's handlers")
                .isEqualTo(handlers);
        assertThat(operations).as("the scan must find the controllers, or this test passes vacuously").hasSizeGreaterThan(50);
    }

    /** A handler springdoc leaves out on purpose: {@code @Hidden}, or {@code @Operation(hidden = true)}. */
    private static boolean hidden(HandlerMethod handler) {
        if (handler.hasMethodAnnotation(Hidden.class) || AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), Hidden.class)) {
            return true;
        }
        final Operation operation = handler.getMethodAnnotation(Operation.class);
        return operation != null && operation.hidden();
    }

    private String servedDocument() throws Exception {
        return mockMvc.perform(get(API_DOCS))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }
}
