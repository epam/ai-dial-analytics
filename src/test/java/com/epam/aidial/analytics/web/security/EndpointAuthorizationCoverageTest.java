package com.epam.aidial.analytics.web.security;

import com.epam.aidial.analytics.service.table.grant.PrivilegeScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The fail-closed guarantee, enforced at build time.
 *
 * <p>The {@code /v1/**} URL rule requires authentication and nothing more, so authorization lives entirely
 * in the per-endpoint annotations. That inverts the old failure mode: a handler that carries no annotation
 * used to be admin-only by accident and is now reachable by any authenticated caller. With fifty-odd
 * endpoints across fifteen controllers and a failure that is silent and security-relevant, review is not a
 * control. This is.
 *
 * <p>Every request mapping on a controller must declare exactly one of {@link RequiresPrivilege} or
 * {@link AuthenticatedOnly}, and a {@link RequiresPrivilege} must be coherent: a table-scoped privilege
 * names the handler parameter carrying the table or is marked deferred; a global-only privilege does
 * neither; and a named parameter must exist on the method.
 */
class EndpointAuthorizationCoverageTest {

    /**
     * The whole application package, not just {@code web/controller}. A controller placed elsewhere would
     * otherwise be skipped silently, and its handlers would reach production unannotated — the exact
     * failure this test exists to prevent, reintroduced by the test's own scope.
     */
    private static final String APPLICATION_PACKAGE = "com/epam/aidial/analytics";

    @Test
    @DisplayName("every /v1 request mapping declares exactly one authorization requirement")
    void everyRequestMappingDeclaresOneRequirement() throws Exception {
        final List<String> violations = new ArrayList<>();
        for (final Class<?> controller : controllers()) {
            for (final Method method : controller.getDeclaredMethods()) {
                if (!method.getDeclaringClass().equals(controller)
                        || !AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)) {
                    continue;
                }
                violations.addAll(check(controller, method));
            }
        }
        assertThat(violations)
                .as("each violation names a handler that would be reachable by any authenticated caller")
                .isEmpty();
    }

    /**
     * The handlers allowed to defer a <b>global-only</b> privilege, each with the chokepoint that then
     * carries the whole decision.
     *
     * <p>An allowlist rather than a blanket rule, because {@code deferred} on a global-only privilege
     * makes the endpoint authenticated-only and nothing in the annotation says where enforcement went.
     * For a table-scoped privilege the shape is self-describing — the tables are resolved downstream — so
     * it needs no entry. These three are the case where the request body picks the privilege: a saved
     * query's {@code scope} selects {@code SAVED_QUERY_WRITE} or {@code SAVED_QUERY_PUBLISH}, which the
     * gate cannot see, and {@code SavedQueryAccess.requireWritable} decides both.
     */
    private static final Set<String> DEFERRED_GLOBAL_ALLOWLIST = Set.of(
            "SavedQueryController#create",
            "SavedQueryController#replace",
            "SavedQueryController#delete");

    /** Every declaration problem for one handler, so one run reports them all. */
    private static List<String> check(Class<?> controller, Method method) {
        final String where = controller.getSimpleName() + "#" + method.getName();
        final RequiresPrivilege requirement =
                AnnotatedElementUtils.findMergedAnnotation(method, RequiresPrivilege.class);
        final boolean authenticatedOnly = AnnotatedElementUtils.hasAnnotation(method, AuthenticatedOnly.class);

        if (requirement == null && !authenticatedOnly) {
            return List.of(where + " declares no authorization requirement; add @RequiresPrivilege, or "
                    + "@AuthenticatedOnly if it genuinely carries no tenant data");
        }
        if (requirement != null && authenticatedOnly) {
            return List.of(where + " declares both @RequiresPrivilege and @AuthenticatedOnly; exactly one");
        }
        if (requirement == null) {
            return List.of();
        }

        final List<String> problems = new ArrayList<>();
        final Privilege privilege = requirement.value();
        final boolean named = !requirement.table().isEmpty();
        if (named && requirement.deferred()) {
            problems.add(where + " sets both table = \"" + requirement.table() + "\" and deferred = true; "
                    + "a check is made here from a named parameter, or downstream, not both");
        }
        if (PrivilegeScope.isTableScoped(privilege)) {
            if (!named && !requirement.deferred()) {
                problems.add(where + " requires table-scoped " + privilege + " without naming the table "
                        + "parameter or marking the check deferred, so it would be decided globally and a "
                        + "per-table grant could never authorize it");
            }
        } else {
            if (named) {
                problems.add(where + " requires global-only " + privilege + " but names table parameter \""
                        + requirement.table() + "\"; a global privilege has no grant list to be scoped to");
            }
            if (requirement.deferred() && !DEFERRED_GLOBAL_ALLOWLIST.contains(where)) {
                problems.add(where + " requires global-only " + privilege + " and marks the check"
                        + " deferred, which makes the endpoint authenticated-only. That is legal where the"
                        + " request body decides which privilege applies, but it is not inferable from the"
                        + " annotation — add the handler to DEFERRED_GLOBAL_ALLOWLIST with the chokepoint"
                        + " that enforces it, so the exemption stays deliberate");
            }
        }
        if (named) {
            try {
                PrivilegeAuthorizationManager.parameterIndex(method, requirement.table());
            } catch (IllegalStateException badParameter) {
                problems.add(where + ": " + badParameter.getMessage());
            }
        }
        return problems;
    }

    /**
     * Every class in the application that declares request mappings, read from the class path.
     *
     * <p>A class qualifies on either of two grounds: it carries a type-level {@code @RequestMapping} (the
     * shape every controller here uses), or it is a {@code @Controller}/{@code @RestController} whose path
     * lives only on its methods. Requiring the type-level annotation alone would skip the second shape
     * entirely, which is a handler reaching production with no authorization declaration.
     */
    private static List<Class<?>> controllers() throws Exception {
        final ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        final MetadataReaderFactory readers = new CachingMetadataReaderFactory(resolver);
        final List<Class<?>> classes = new ArrayList<>();
        for (final var resource : resolver.getResources("classpath*:" + APPLICATION_PACKAGE + "/**/*.class")) {
            final String name = readers.getMetadataReader(resource).getClassMetadata().getClassName();
            final Class<?> type;
            try {
                type = Class.forName(name);
            } catch (Throwable notLoadable) {
                // A class the test class path cannot initialise carries no request mapping worth checking.
                continue;
            }
            if (declaresRequestMappings(type)) {
                classes.add(type);
            }
        }
        assertThat(classes)
                .as("the scan must actually find the controllers, or this test passes vacuously")
                .hasSizeGreaterThanOrEqualTo(15);
        return classes;
    }

    /** Whether {@code type} is a web controller that declares request mappings, either way round. */
    private static boolean declaresRequestMappings(Class<?> type) {
        if (AnnotatedElementUtils.hasAnnotation(type, RequestMapping.class)) {
            return true;
        }
        if (!AnnotatedElementUtils.hasAnnotation(type, Controller.class)) {
            return false;
        }
        return Arrays.stream(type.getDeclaredMethods())
                .anyMatch(method -> AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class));
    }

    @Test
    @DisplayName("every privilege is classified as table-scoped or global-only")
    void everyPrivilegeIsClassified() {
        // PrivilegeScope.grantKey throws for a privilege with no grant list, so exercising both branches over
        // the whole enum is what proves no value falls between them.
        for (final Privilege privilege : Privilege.values()) {
            if (PrivilegeScope.isGrantable(privilege)) {
                assertThat(PrivilegeScope.isTableScoped(privilege)).isTrue();
                assertThat(PrivilegeScope.grantKey(privilege)).isNotBlank();
            } else {
                assertThat(PrivilegeScope.GRANTABLE).doesNotContain(privilege);
                assertThatThrownBy(() -> PrivilegeScope.grantKey(privilege))
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }
        assertThat(PrivilegeScope.TABLE_SCOPED).hasSize(6);
        // TABLE_LIST is checked per table but never granted on one: every grant that shows a table confers it.
        assertThat(PrivilegeScope.GRANTABLE).hasSize(5).doesNotContain(Privilege.TABLE_LIST);
        assertThat(Arrays.stream(Privilege.values()).count()).isEqualTo(16);
    }
}
