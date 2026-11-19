package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.web.openapi.ApplicationControllers;
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

/**
 * The committed {@code docs/openapi.json} is the document this code serves, normalised.
 *
 * <p>By default the test compares the two and fails with the first differing JSON pointer and the regenerate
 * command. With {@code -Dopenapi.update=true}, which {@code ./gradlew updateOpenApi} sets, it writes the file
 * instead. Runs on the shared PostgreSQL context: springdoc builds the document from controller annotations, so
 * neither ClickHouse nor a credential is involved. See {@code openspec/specs/openapi-document/spec.md}.
 */
class OpenApiDocumentSnapshotIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String APPLICATION_PACKAGE = "com.epam.aidial.analytics.";

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void committedDocumentMatchesTheServedDocument() throws Exception {
        final Path file = OpenApiDocuments.COMMITTED_FILE;
        final JsonNode served = OpenApiDocuments.normalise(OpenApiDocuments.served(mockMvc));
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
        final Optional<String> difference = OpenApiDocuments.firstDifference(OpenApiDocuments.parseCommitted(committed), served);
        throw new AssertionError(file + " no longer matches the document the code serves"
                + difference.map(pointer -> pointer.isEmpty() ? " (the documents differ at the root)" : " (first difference at " + pointer + ")")
                        .orElse(" (formatting only)")
                + ". Run " + OpenApiDocuments.REGENERATE_COMMAND + " and commit the result.");
    }

    /**
     * The document's context registers every {@code @RestController} of the application. The document is built over
     * the PostgreSQL-only context, so a controller that context leaves out (one gated by a property it turns off,
     * say) would be missing from both the handler mapping and the document, and {@code updateOpenApi} would drop
     * its operations from the committed file without anything failing. The classpath scan is the side that does not
     * depend on the context.
     */
    @Test
    void documentContextRegistersEveryApplicationController() throws Exception {
        final Set<String> registered = new TreeSet<>();
        handlerMapping.getHandlerMethods().values().forEach(handler -> registered.add(handler.getBeanType().getName()));
        final Set<String> missing = new TreeSet<>();
        for (final Class<?> controller : ApplicationControllers.scan()) {
            if (!registered.contains(controller.getName())) {
                missing.add(controller.getName());
            }
        }
        assertThat(missing).as("controllers the document's context does not register, so the document would leave them out").isEmpty();
    }

    /**
     * Every handler the context registers is in the document, and nothing else is: springdoc neither drops a
     * registered handler nor publishes one the context does not map. Together with the test above, every handler of
     * the application is published.
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
        final JsonNode paths = OpenApiDocuments.served(mockMvc).path("paths");
        for (final String path : paths.propertyNames()) {
            for (final String method : paths.get(path).propertyNames()) {
                if (OpenApiDocuments.HTTP_METHODS.contains(method)) {
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
}
