package com.epam.aidial.analytics.web.security;

import com.epam.aidial.analytics.exception.TableNotFoundException;
import com.epam.aidial.analytics.service.table.grant.PrivilegeScope;
import com.epam.aidial.analytics.service.table.grant.TableAccessEvaluator;
import lombok.extern.slf4j.Slf4j;
import org.aopalliance.intercept.MethodInvocation;
import org.jspecify.annotations.Nullable;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.lang.reflect.Method;
import java.util.function.Supplier;

/**
 * Enforces {@link RequiresPrivilege} and {@link AuthenticatedOnly} on request mappings, replacing
 * {@code @FullAdminOnly} and the hand-written {@code @PreAuthorize("@tableAccess.can*(#name)")}
 * expressions with one mechanism.
 *
 * <p>Three shapes, as documented on {@link RequiresPrivilege}: a global privilege is checked against the
 * caller's authorities; a per-table privilege is checked against {@link TableAccessEvaluator} with the
 * table resolved from the named handler parameter; a deferred one is enforced downstream and only
 * requires authentication here.
 *
 * <p>Refusal preserves non-disclosure. A missing <em>read-side</em> table privilege throws
 * {@link TableNotFoundException}, so the caller is told the table does not exist rather than that it may
 * not see it — the same answer an absent table gives, through the same handler. Everything else is an
 * ordinary authorization denial, rendered {@code 403 forbidden}.
 *
 * <p>Registered only in {@code oidc} mode (see {@code OidcSecurityConfiguration}), so in {@code none}
 * mode these annotations are inert exactly as method security was.
 */
@Slf4j
public class PrivilegeAuthorizationManager implements AuthorizationManager<MethodInvocation> {

    private static final ParameterNameDiscoverer PARAMETER_NAMES = new DefaultParameterNameDiscoverer();

    private final TableAccessEvaluator tableAccess;

    public PrivilegeAuthorizationManager(TableAccessEvaluator tableAccess) {
        this.tableAccess = tableAccess;
    }

    @Override
    public @Nullable AuthorizationResult authorize(
            Supplier<? extends @Nullable Authentication> authentication, MethodInvocation invocation) {
        final Method method = invocation.getMethod();
        final RequiresPrivilege requirement = AnnotatedElementUtils.findMergedAnnotation(method, RequiresPrivilege.class);
        if (requirement == null) {
            // @AuthenticatedOnly: the endpoint declares that it needs no privilege, which the coverage
            // test counts as its one requirement. Authentication is already established by the URL layer.
            return new AuthorizationDecision(true);
        }
        final Privilege required = requirement.value();
        if (requirement.deferred()) {
            // Per-table enforcement happens at the chokepoint that assembles the query model or filters the
            // listing, where every resolved table is in hand. Nothing to decide here.
            return new AuthorizationDecision(true);
        }
        if (!requirement.table().isEmpty()) {
            return authorizeTable(required, requirement.table(), invocation);
        }
        return new AuthorizationDecision(holdsGlobally(authentication.get(), required));
    }

    /** The per-table shape: the privilege held globally, or granted on the table the request names. */
    private AuthorizationResult authorizeTable(Privilege required, String parameterName, MethodInvocation invocation) {
        final Object argument = invocation.getArguments()[parameterIndex(invocation.getMethod(), parameterName)];
        if (argument == null) {
            // No table to check against; fail closed rather than fall through to the handler.
            return new AuthorizationDecision(false);
        }
        final String tableName = argument.toString();
        if (tableAccess.isAllowed(tableName, required)) {
            return new AuthorizationDecision(true);
        }
        if (PrivilegeScope.isReadSide(required)) {
            log.debug("Reporting table '{}' as absent: caller lacks {}", tableName, required);
            throw new TableNotFoundException("table '" + tableName + "' not found");
        }
        return new AuthorizationDecision(false);
    }

    private static boolean holdsGlobally(@Nullable Authentication authentication, Privilege required) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        final String wanted = PrivilegeAuthority.name(required);
        return authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).anyMatch(wanted::equals);
    }

    /**
     * The index of the handler parameter named {@code parameterName}. A name the method does not declare
     * is a misdeclared annotation, which must fail loudly rather than silently open the endpoint — so this
     * throws instead of returning a sentinel. {@code EndpointAuthorizationCoverageTest} catches it at
     * build time; this is the runtime backstop.
     */
    public static int parameterIndex(Method method, String parameterName) {
        final String[] names = PARAMETER_NAMES.getParameterNames(method);
        if (names == null) {
            throw new IllegalStateException("parameter names are unavailable on " + method
                    + "; the -parameters compiler flag is required for @RequiresPrivilege(table = ...)");
        }
        for (int i = 0; i < names.length; i++) {
            if (parameterName.equals(names[i])) {
                return i;
            }
        }
        throw new IllegalStateException("@RequiresPrivilege on " + method + " names parameter '"
                + parameterName + "', which the method does not declare");
    }
}
