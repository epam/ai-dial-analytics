package com.epam.aidial.analytics.service.dashboard;

import com.epam.aidial.analytics.exception.UnattributableDashboardException;
import com.epam.aidial.analytics.service.security.SecurityClaimsExtractor;
import com.epam.aidial.analytics.web.security.Privilege;
import com.epam.aidial.analytics.web.security.SecurityModeResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Decides who may write a dashboard, and reports it back as the {@code permissions} flags, from the
 * saved-query privileges: {@code PRIVATE} needs {@link Privilege#SAVED_QUERY_WRITE} and {@code PUBLIC}
 * needs {@link Privilege#SAVED_QUERY_PUBLISH}. No dashboard-specific privilege exists, because publishing
 * a dashboard publishes its inline query bodies.
 *
 * <p>The sibling of {@code SavedQueryAccess}, with the same shape and the same {@code none}-mode fail-open.
 * Who may <em>see</em> a dashboard is not decided here: that is the repository's lookup predicate, so an
 * invisible dashboard and a missing one share one {@code 404} path.
 *
 * <p>The flags are computed from the same predicates enforcement uses, so they cannot drift from it. They
 * report authorization only: a {@code write} of {@code true} does not promise that the stored inline
 * bodies revalidate against the caller's catalog view, or that the caller's {@code generation} is current.
 */
@Slf4j
@Component
public class DashboardAccess {

    private final SecurityClaimsExtractor claims;
    private final boolean securityEnabled;

    public DashboardAccess(SecurityClaimsExtractor claims, SecurityModeResolver securityMode) {
        this.claims = claims;
        this.securityEnabled = securityMode.isEnforced();
    }

    /** The identity that owns what this caller creates; {@code null} when security is disabled. */
    public String currentAuthor() {
        return securityEnabled ? claims.getAuthor() : null;
    }

    /** The caller's email, recorded for display only; never an identity. */
    public String currentAuthorEmail() {
        return securityEnabled ? claims.getEmail() : null;
    }

    /** Whether authorization is enforced at all; {@code false} in {@code none} mode. */
    public boolean isSecurityEnabled() {
        return securityEnabled;
    }

    /**
     * Refuses a caller that could write at <b>no</b> visibility, before a lookup can disclose whether a
     * dashboard exists. The write gates are deferred, so without this a caller holding neither privilege
     * would get {@code 404} for an absent id and {@code 403} for a {@code public} one.
     */
    public void requireAnyWriteAuthority() {
        if (!securityEnabled
                || claims.hasPrivilege(Privilege.SAVED_QUERY_WRITE)
                || claims.hasPrivilege(Privilege.SAVED_QUERY_PUBLISH)) {
            return;
        }
        throw new AccessDeniedException("writing a dashboard requires SAVED_QUERY_WRITE or SAVED_QUERY_PUBLISH");
    }

    /**
     * Asserts that the caller may write at {@code visibility}. A replace that changes visibility calls this
     * for both the stored and the requested one, which is what makes every visibility change need
     * {@code SAVED_QUERY_PUBLISH}.
     */
    public void requireWritable(DashboardVisibility visibility) {
        if (!mayWrite(visibility)) {
            throw new AccessDeniedException("writing a " + visibility.token() + " dashboard requires " + required(visibility));
        }
    }

    /**
     * Refuses to create a {@code private} dashboard no one could reach again: while security is enabled, a
     * caller whose principal does not resolve would leave it with a null owner, which {@code owner_id =
     * :author} matches for no caller. {@code public} dashboards stay readable whatever their owner.
     */
    public void requireAttributable(DashboardVisibility visibility) {
        if (orphans(visibility, currentAuthor())) {
            log.warn("Refusing a private dashboard: the caller authenticated but the configured principal claim "
                    + "yielded no value, so the dashboard would be unreachable by everyone");
            throw new UnattributableDashboardException(
                    "your identity could not be resolved, so a private dashboard cannot be attributed to you; it "
                            + "would be unreachable. Contact an administrator — the service's principal claim is "
                            + "not present on your token.");
        }
    }

    /** Whether storing a dashboard at {@code visibility} with {@code ownerId} would leave it unreachable. */
    public boolean orphans(DashboardVisibility visibility, String ownerId) {
        return securityEnabled && visibility == DashboardVisibility.PRIVATE && ownerId == null;
    }

    /**
     * This caller's flags on a dashboard at {@code visibility} owned by {@code ownerId}. {@code publish}
     * needs both privileges, because a visibility change satisfies the write rule for both visibilities,
     * and is {@code false} on an ownerless {@code public} dashboard, which cannot be withdrawn.
     */
    public DashboardPermissions permissions(DashboardVisibility visibility, String ownerId) {
        if (!securityEnabled) {
            return new DashboardPermissions(true, true);
        }
        final DashboardVisibility other = visibility == DashboardVisibility.PUBLIC
                ? DashboardVisibility.PRIVATE
                : DashboardVisibility.PUBLIC;
        return new DashboardPermissions(
                mayWrite(visibility),
                mayWrite(visibility) && mayWrite(other) && !orphans(other, ownerId));
    }

    /** What this caller may create, the unresolved-principal refusal included. */
    public DashboardCreatePermissions createPermissions() {
        return new DashboardCreatePermissions(
                mayWrite(DashboardVisibility.PRIVATE) && !orphans(DashboardVisibility.PRIVATE, currentAuthor()),
                mayWrite(DashboardVisibility.PUBLIC));
    }

    private boolean mayWrite(DashboardVisibility visibility) {
        return !securityEnabled || claims.hasPrivilege(required(visibility));
    }

    private static Privilege required(DashboardVisibility visibility) {
        return visibility == DashboardVisibility.PUBLIC ? Privilege.SAVED_QUERY_PUBLISH : Privilege.SAVED_QUERY_WRITE;
    }
}
