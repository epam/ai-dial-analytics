package com.epam.aidial.analytics.service.security;

import com.epam.aidial.analytics.web.security.CallerAuthorities;
import com.epam.aidial.analytics.web.security.Privilege;
import com.epam.aidial.analytics.web.security.PrivilegeImplications;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Runs ADAS's own background work under an authenticated system principal holding <b>exactly two</b>
 * privileges: {@link Privilege#TABLE_READ} and the {@link Privilege#TABLE_SENSITIVE} clearance, globally.
 *
 * <p>Column-level governance is decided from the <em>calling</em> identity
 * ({@link SensitiveColumnAccess} over {@link SecurityClaimsExtractor}), which is the right rule for a
 * request but has no answer on a scheduler thread: there is no caller, so in {@code oidc} mode the thread
 * reads as unauthenticated and every sensitive column is hidden from it. A pipeline registered over such a
 * column would then validate, preview and refresh on demand, yet fail on every single tick —
 * permanently, and only in a secured deployment. Giving the schedule an identity of its own is what makes
 * scheduled execution behave like the on-demand execution it is supposed to automate.
 *
 * <p>Those two privileges are the <b>whole</b> of what a scheduled refresh needs, and naming them is what
 * bounds the elevation. Before privileges existed, the narrowest thing that could be granted was
 * {@code FULL_ADMIN} — catalog DDL, grant management, registry authoring and backfill included — which had
 * to be justified in prose rather than by construction. It now cannot administer anything: an operation
 * requiring {@link Privilege#TABLE_GRANT}, {@link Privilege#TABLE_CREATE}, {@link Privilege#TABLE_DELETE}
 * or {@link Privilege#REGISTRY_ADMIN} is denied under this principal.
 *
 * <p>A refresh <em>writes</em> its target table, and it does so through the service layer rather than
 * through the gated {@code POST /v1/tables/{name}/rows}, so no {@link Privilege#TABLE_WRITE} check is on
 * its path today. If service-layer writes are ever brought under that privilege, this principal must be
 * extended to hold it.
 *
 * <p>The laundering risk that follows from the read elevation (a protected input column aggregated into
 * an unprotected target column) is closed at declaration time instead: the aggregate validator requires a
 * target column derived from a sensitive input column to be declared sensitive itself — <em>including</em>
 * a column the declaration reaches through the input's enrichment grain join, which it resolves through
 * the same flat entity view and governs identically. That totality is the justification for the
 * elevation, so a field the pipeline surface can read and that rule does not cover would invalidate it.
 *
 * <p><b>The context is always restored.</b> Spring's scheduler thread pool is shared with the blob poller
 * and the data-sync job, and {@link SecurityContextHolder}'s default strategy is thread-local — a leaked
 * elevated context would silently elevate whatever ran next on the same thread. The previous context is
 * therefore captured and put back in a {@code finally}, including on the exceptional path.
 */
@Slf4j
@Component
public class SystemIdentity {

    /** The principal name background work runs as; appears in nothing user-facing, only in traces/logs. */
    static final String SYSTEM_PRINCIPAL = "adas-system";

    /**
     * Exactly what a scheduled refresh over a sensitive column requires, and nothing more — closed under
     * the implication DAG, like every other held privilege set.
     *
     * <p>The closure is not cosmetic. Checks test exact containment, so an unclosed
     * {@code {TABLE_READ, TABLE_SENSITIVE}} holds no {@code TABLE_GET} and no {@code TABLE_LIST} — and
     * the ladder's whole claim is that holding a rung confers every rung below it. The clearance adds
     * nothing to the closure: it implies nothing, which is why it has to be named here beside the verb. Nothing on the scheduled
     * path asks at {@code TABLE_GET} today, so the gap would stay latent until some step resolved the
     * catalog at that bar (as the translation and validation paths do) and scheduled refresh began failing
     * in {@code oidc} mode only — precisely the failure this class exists to prevent.
     */
    static final Set<Privilege> SYSTEM_PRIVILEGES = PrivilegeImplications.closure(
            EnumSet.of(Privilege.TABLE_READ, Privilege.TABLE_SENSITIVE));

    private static final Authentication SYSTEM = new UsernamePasswordAuthenticationToken(
            SYSTEM_PRINCIPAL, null, CallerAuthorities.build(SYSTEM_PRIVILEGES, List.of()));

    /** Runs {@code work} as the system principal, restoring the caller's context afterwards. */
    public void runAsSystem(Runnable work) {
        final SecurityContext previous = SecurityContextHolder.getContext();
        final SecurityContext elevated = SecurityContextHolder.createEmptyContext();
        elevated.setAuthentication(SYSTEM);
        SecurityContextHolder.setContext(elevated);
        try {
            work.run();
        } finally {
            // Restore rather than clear: this thread may be servicing something that had its own context.
            SecurityContextHolder.setContext(previous);
        }
    }
}
