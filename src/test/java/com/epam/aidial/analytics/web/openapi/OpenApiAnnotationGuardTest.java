package com.epam.aidial.analytics.web.openapi;

import com.epam.aidial.analytics.web.contract.RunnerContractExempt;
import com.epam.aidial.analytics.web.controller.otel.OtelIngestController;
import com.epam.aidial.analytics.web.controller.pipeline.PipelineDataController;
import com.epam.aidial.analytics.web.controller.query.QueryApiTag;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OpenAPI annotation rules a generated client depends on, enforced on the controllers rather than on the
 * document: by the time springdoc builds the document it has already resolved id collisions with a {@code _N}
 * suffix. Each rule fails naming the handler. See {@code openspec/specs/openapi-document/spec.md}.
 */
class OpenApiAnnotationGuardTest {

    private static final String APPLICATION_PACKAGE = "com/epam/aidial/analytics";

    /** lowerCamelCase, which also excludes springdoc's {@code _<digits>} collision suffix. */
    private static final Pattern LOWER_CAMEL = Pattern.compile("[a-z][a-zA-Z0-9]*");

    /** The verbs an operation id may begin with. A new verb is fine when it names what the operation does. */
    private static final Set<String> VERBS = Set.of("list", "get", "create", "replace", "update", "patch", "delete",
            "define", "execute", "translate", "validate", "preview", "export", "import", "trigger", "requeue", "fetch",
            "materialize", "scan", "write", "refresh");

    @Test
    void everyPublishedHandlerDeclaresUniqueStableOperationId() throws Exception {
        assertThat(operationIdViolations(controllers())).isEmpty();
    }

    @Test
    void everyPublishedHandlerCarriesExactlyOneDescribedTag() throws Exception {
        assertThat(tagViolations(controllers())).isEmpty();
    }

    @Test
    void previewAndInternalMarkingsMatchTheSurfacesTheyBelongTo() throws Exception {
        assertThat(markingViolations(controllers())).isEmpty();
    }

    @Test
    void handlerWithoutIdIsNamed() {
        assertThat(operationIdViolations(List.of(NoIdController.class)))
                .containsExactly("NoIdController#create declares no operationId");
    }

    @Test
    void duplicateIdNamesBothHandlers() {
        assertThat(operationIdViolations(List.of(DuplicateIdController.class)))
                .containsExactly("operationId 'getThing' is declared by both DuplicateIdController#first and DuplicateIdController#second");
    }

    @Test
    void suffixedOrVerblessIdIsNamed() {
        assertThat(operationIdViolations(List.of(BadIdController.class))).containsExactlyInAnyOrder(
                "BadIdController#suffixed declares operationId 'getThing_1', which is not lowerCamelCase",
                "BadIdController#verbless declares operationId 'thingList', which does not begin with a known verb");
    }

    @Test
    void anUntaggedHandlerIsNamed() {
        assertThat(tagViolations(List.of(NoIdController.class)))
                .containsExactly("NoIdController#create publishes 0 tags; it must publish exactly one");
    }

    @Test
    void oneTagNameWithTwoDescriptionsNamesBothControllers() {
        assertThat(tagViolations(List.of(FirstThingController.class, SecondThingController.class)))
                .containsExactly("tag 'Things' is declared with different descriptions by FirstThingController and SecondThingController");
    }

    @Test
    void strayOrDoubleMarkingIsNamed() {
        assertThat(markingViolations(List.of(MarkedController.class))).containsExactlyInAnyOrder(
                "MarkedController#preview is marked @ApiPreview but is not a Queries operation",
                "MarkedController#both is marked @ApiPreview but is not a Queries operation",
                "MarkedController#both is marked @ApiInternal but is not an OTLP ingest or runner data-plane operation",
                "MarkedController#both is marked both @ApiPreview and @ApiInternal");
    }

    static List<String> operationIdViolations(List<Class<?>> controllers) {
        final List<String> violations = new ArrayList<>();
        final Map<String, String> owners = new TreeMap<>();
        for (final Class<?> controller : controllers) {
            for (final Method handler : published(controller)) {
                final String name = name(controller, handler);
                final Operation operation = handler.getAnnotation(Operation.class);
                final String id = operation == null ? "" : operation.operationId();
                if (id.isBlank()) {
                    violations.add(name + " declares no operationId");
                    continue;
                }
                if (!LOWER_CAMEL.matcher(id).matches()) {
                    violations.add(name + " declares operationId '" + id + "', which is not lowerCamelCase");
                } else if (VERBS.stream().noneMatch(verb -> id.equals(verb) || id.startsWith(verb) && Character.isUpperCase(id.charAt(verb.length())))) {
                    violations.add(name + " declares operationId '" + id + "', which does not begin with a known verb");
                }
                final String previous = owners.putIfAbsent(id, name);
                if (previous != null) {
                    violations.add("operationId '" + id + "' is declared by both " + previous + " and " + name);
                }
            }
        }
        return violations;
    }

    static List<String> tagViolations(List<Class<?>> controllers) {
        final List<String> violations = new ArrayList<>();
        final Map<String, String> descriptions = new LinkedHashMap<>();
        final Map<String, String> declarers = new LinkedHashMap<>();
        for (final Class<?> controller : controllers) {
            final List<Tag> declared = new ArrayList<>(Arrays.asList(controller.getAnnotationsByType(Tag.class)));
            for (final Method handler : published(controller)) {
                final List<Tag> methodTags = Arrays.asList(handler.getAnnotationsByType(Tag.class));
                declared.addAll(methodTags);
                final Set<String> names = new TreeSet<>();
                Arrays.stream(controller.getAnnotationsByType(Tag.class)).forEach(tag -> names.add(tag.name()));
                methodTags.forEach(tag -> names.add(tag.name()));
                final Operation operation = handler.getAnnotation(Operation.class);
                if (operation != null) {
                    names.addAll(Arrays.asList(operation.tags()));
                }
                if (names.size() != 1) {
                    violations.add(name(controller, handler) + " publishes " + names.size() + " tags; it must publish exactly one");
                }
            }
            for (final Tag tag : declared) {
                if (tag.description().isBlank()) {
                    violations.add("tag '" + tag.name() + "' is declared without a description by " + controller.getSimpleName());
                    continue;
                }
                final String previous = descriptions.putIfAbsent(tag.name(), tag.description());
                if (previous == null) {
                    declarers.put(tag.name(), controller.getSimpleName());
                } else if (!previous.equals(tag.description())) {
                    violations.add("tag '" + tag.name() + "' is declared with different descriptions by "
                            + declarers.get(tag.name()) + " and " + controller.getSimpleName());
                }
            }
        }
        return violations;
    }

    /**
     * The expected sets are derived from what each surface is, not from the markers: preview is every handler of a
     * controller tagged {@link QueryApiTag#NAME}; internal is every OTLP ingest handler and every runner data-plane
     * {@code POST} that is not {@link RunnerContractExempt}, the same deny-by-default rule the runner contract uses.
     */
    static List<String> markingViolations(List<Class<?>> controllers) {
        final List<String> violations = new ArrayList<>();
        for (final Class<?> controller : controllers) {
            for (final Method handler : published(controller)) {
                final String name = name(controller, handler);
                final boolean preview = OpenApiMarkings.marked(handler, controller, ApiPreview.class);
                final boolean internal = OpenApiMarkings.marked(handler, controller, ApiInternal.class);
                final boolean shouldBePreview = Arrays.stream(controller.getAnnotationsByType(Tag.class))
                        .anyMatch(tag -> QueryApiTag.NAME.equals(tag.name()));
                final boolean shouldBeInternal = controller == OtelIngestController.class
                        || controller == PipelineDataController.class && isPost(handler) && !handler.isAnnotationPresent(RunnerContractExempt.class);
                if (preview != shouldBePreview) {
                    violations.add(preview ? name + " is marked @ApiPreview but is not a Queries operation"
                            : name + " is a Queries operation but is not marked @ApiPreview");
                }
                if (internal != shouldBeInternal) {
                    violations.add(internal ? name + " is marked @ApiInternal but is not an OTLP ingest or runner data-plane operation"
                            : name + " is an OTLP ingest or runner data-plane operation but is not marked @ApiInternal");
                }
                if (preview && internal) {
                    violations.add(name + " is marked both @ApiPreview and @ApiInternal");
                }
            }
        }
        return violations;
    }

    /** The controller's request-mapping handlers springdoc publishes, in a stable order. */
    private static List<Method> published(Class<?> controller) {
        return Arrays.stream(controller.getDeclaredMethods())
                .filter(method -> AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class))
                .filter(method -> !method.isAnnotationPresent(Hidden.class) && !controller.isAnnotationPresent(Hidden.class))
                .filter(method -> method.getAnnotation(Operation.class) == null || !method.getAnnotation(Operation.class).hidden())
                .sorted(Comparator.comparing(Method::getName).thenComparing(Method::toGenericString))
                .toList();
    }

    private static boolean isPost(Method handler) {
        final RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(handler, RequestMapping.class);
        return mapping != null && Arrays.asList(mapping.method()).contains(RequestMethod.POST);
    }

    private static String name(Class<?> controller, Method handler) {
        return controller.getSimpleName() + "#" + handler.getName();
    }

    private static List<Class<?>> controllers() throws Exception {
        final ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        final MetadataReaderFactory readers = new CachingMetadataReaderFactory(resolver);
        final List<Class<?>> classes = new ArrayList<>();
        for (final var resource : resolver.getResources("classpath*:" + APPLICATION_PACKAGE + "/**/*.class")) {
            final String className = readers.getMetadataReader(resource).getClassMetadata().getClassName();
            final Class<?> type;
            try {
                type = Class.forName(className);
            } catch (Throwable notLoadable) {
                continue;
            }
            if (AnnotatedElementUtils.hasAnnotation(type, RestController.class) && type.getEnclosingClass() == null) {
                classes.add(type);
            }
        }
        classes.sort(Comparator.comparing(Class::getName));
        assertThat(classes).as("the scan must find the controllers, or this test passes vacuously").hasSizeGreaterThanOrEqualTo(15);
        return classes;
    }

    // Fixtures carry no @RestController: component scanning would otherwise register them in every Spring test
    // context, and they would be published in the document the snapshot test pins.
    static class NoIdController {
        @PostMapping("/things")
        @Operation(summary = "Create a thing")
        public void create() {
        }
    }

    @Tag(name = "Things", description = "Things.")
    static class DuplicateIdController {
        @GetMapping("/things/first")
        @Operation(operationId = "getThing")
        public void first() {
        }

        @GetMapping("/things/second")
        @Operation(operationId = "getThing")
        public void second() {
        }
    }

    @Tag(name = "Things", description = "Things.")
    static class BadIdController {
        @GetMapping("/things/suffixed")
        @Operation(operationId = "getThing_1")
        public void suffixed() {
        }

        @GetMapping("/things/verbless")
        @Operation(operationId = "thingList")
        public void verbless() {
        }
    }

    @Tag(name = "Things", description = "Things.")
    static class FirstThingController {
        @GetMapping("/things/a")
        @Operation(operationId = "getFirstThing")
        public void get() {
        }
    }

    @Tag(name = "Things", description = "Other things.")
    static class SecondThingController {
        @GetMapping("/things/b")
        @Operation(operationId = "getSecondThing")
        public void get() {
        }
    }

    @Tag(name = "Things", description = "Things.")
    static class MarkedController {
        @GetMapping("/things/preview")
        @Operation(operationId = "getPreviewThing")
        @ApiPreview
        public void preview() {
        }

        @GetMapping("/things/both")
        @Operation(operationId = "getBothThing")
        @ApiPreview
        @ApiInternal
        public void both() {
        }
    }
}
