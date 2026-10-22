package com.epam.aidial.analytics.service.query.saved;

import com.epam.aidial.analytics.exception.UnattributableSavedQueryException;
import com.epam.aidial.analytics.service.security.SecurityClaimsExtractor;
import com.epam.aidial.analytics.web.security.Privilege;
import com.epam.aidial.analytics.web.security.SecurityModeResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Decides who may see and who may write a saved query.
 *
 * <p>The {@code common} gate cannot be an endpoint annotation: the deciding input is the request body's
 * {@code scope}, not the URL, and the annotation would also block a legitimate {@code personal} write on
 * the same endpoint. It is therefore evaluated here, during request handling, and thrown as
 * {@link AccessDeniedException} — which {@code DefaultExceptionHandler} already renders as
 * {@code 403 forbidden}, the same shape a denial at the gate produces.
 *
 * <p>Publishing is {@link Privilege#SAVED_QUERY_PUBLISH}, separately grantable from the authority to read
 * sensitive columns. Before privileges existed both were {@code FULL_ADMIN}, and this class justified its
 * gate by pointing at that coincidence — curating a shared query definition and reading PII are not the
 * same authority, and nothing narrower existed to name.
 *
 * <p>In {@code none} mode the whole capability fails open, mirroring {@code SensitiveColumnAccess}: there
 * is no authenticated caller, so there is no owner to filter by and no role to gate on. No synthetic
 * owner is invented — a placeholder would become a real, colliding identity the moment the deployment
 * switched to {@code oidc}.
 */
@Slf4j
@Component
public class SavedQueryAccess {

    private final SecurityClaimsExtractor claims;
    private final boolean securityEnabled;

    public SavedQueryAccess(SecurityClaimsExtractor claims, SecurityModeResolver securityMode) {
        this.claims = claims;
        this.securityEnabled = securityMode.isEnforced();
    }

    /**
     * The identity that owns what this caller creates, and the one {@code personal} rows are filtered by.
     * {@code null} when security is disabled, which the read path reads as "no owner filter".
     *
     * <p>Stored opaquely and never parsed. For a DIAL project key this is the project name rather than a
     * person, so {@code personal} isolates per <em>credential identity</em>: everyone holding a shared
     * project key shares its personal saved queries.
     */
    public String currentAuthor() {
        return securityEnabled ? claims.getAuthor() : null;
    }

    /**
     * The caller's email, recorded alongside {@link #currentAuthor()} purely so a UI can show who saved
     * a query. <strong>Not an identity.</strong> Nothing may branch on it — see
     * {@code SavedQueryEntity#ownerEmail}.
     *
     * <p>Null whenever security is off, when the token carries no email claim
     * ({@code security.require-email} defaults to false), or when the caller is a plain DIAL project key,
     * which has no person behind it.
     */
    public String currentAuthorEmail() {
        return securityEnabled ? claims.getEmail() : null;
    }

    /** Whether authorization is enforced at all; {@code false} in {@code none} mode. */
    public boolean isSecurityEnabled() {
        return securityEnabled;
    }

    /**
     * Whether the caller may see <b>every</b> saved query, regardless of scope or owner — the
     * administrative visibility the audit feed's narrowing and the revision read both key off.
     *
     * <p>{@link Privilege#SAVED_QUERY_PUBLISH}: the authority that curates the shared catalogue is the
     * closest existing one, and mapping the bypass to it reproduces today's behaviour exactly, since the
     * {@code FULL_ADMIN} bundle holds it and {@code READ_ONLY_ADMIN} does not.
     */
    public boolean maySeeEverySavedQuery() {
        return claims.hasPrivilege(Privilege.SAVED_QUERY_PUBLISH);
    }

    /**
     * Refuses to write a {@code personal} saved query that no one could ever reach again.
     *
     * <p>Visibility is decided by {@code owner_id = :author}. A {@code personal} row whose owner is null
     * therefore matches no caller: it could not be read, replaced, or deleted by anyone, including the
     * caller that created it. Storing it would leave unreachable data behind and report success.
     *
     * <p>Only possible while security is enabled and the caller's principal does not resolve — the token
     * authenticated but carries no value for the configured principal claim, which is a deployment
     * misconfiguration the caller cannot fix. In {@code none} mode a null owner is the normal, intended
     * state and the whole capability fails open, so this does not apply.
     *
     * <p>{@code common} rows are deliberately <strong>not</strong> guarded: they stay readable by every
     * caller regardless of owner, so an unresolved principal costs only the "Saved by" attribution rather
     * than reachability — and refusing them would break a deployment that curates shared definitions
     * under tokens that carry no principal claim.
     */
    public void requireAttributable(SavedQueryScope scope, String ownerId) {
        if (securityEnabled && scope == SavedQueryScope.PERSONAL && ownerId == null) {
            log.warn("Refusing a personal saved query: the caller authenticated but the configured "
                    + "principal claim yielded no value, so the row would be unreachable by everyone");
            throw new UnattributableSavedQueryException(
                    "your identity could not be resolved, so a personal saved query cannot be attributed "
                            + "to you; it would be unreachable. Contact an administrator — the service's "
                            + "principal claim is not present on your token.");
        }
    }

    /**
     * Refuses a caller that could not write at <b>any</b> scope, before a lookup can disclose whether a row
     * exists.
     *
     * <p>Needed because the write gates are {@code deferred}: `replace` and `delete` load the row to learn
     * its scope, and that load answers {@code 404} for an absent saved query and {@code 403} for one the
     * caller may not write — an existence oracle on `common` rows for a caller holding no saved-query
     * privilege at all, which the non-deferred gate used to refuse outright. Asking the scope-independent
     * half of the question first restores that, and leaves the scope-dependent half to
     * {@link #requireWritable}.
     */
    public void requireAnyWriteAuthority() {
        if (!securityEnabled
                || claims.hasPrivilege(Privilege.SAVED_QUERY_WRITE)
                || claims.hasPrivilege(Privilege.SAVED_QUERY_PUBLISH)) {
            return;
        }
        throw new AccessDeniedException(
                "writing a saved query requires SAVED_QUERY_WRITE or SAVED_QUERY_PUBLISH");
    }

    /**
     * Asserts that the caller may write at {@code scope}. A replace that changes scope must satisfy this
     * for <strong>both</strong> the stored and the requested scope, so a non-admin can neither promote its
     * own row into {@code common} nor demote an existing {@code common} row to hide it.
     *
     * <p><b>Both scopes are checked here, and that is what makes this the chokepoint.</b> The endpoint gates
     * are {@code deferred}: scope arrives in the request body, so the gate cannot decide which privilege the
     * call needs and the decision belongs where the scope is known. Checking only {@code common} left two
     * holes at once — an authenticated caller holding no saved-query privilege could create a personal row,
     * and a {@code SAVED_QUERY_PUBLISH} holder was refused at a gate demanding {@code SAVED_QUERY_WRITE}, so
     * the privilege whose entire purpose is curating the shared catalogue authorized nothing on its own.
     */
    public void requireWritable(SavedQueryScope scope) {
        if (!securityEnabled) {
            return;
        }
        final Privilege required = scope == SavedQueryScope.COMMON
                ? Privilege.SAVED_QUERY_PUBLISH
                : Privilege.SAVED_QUERY_WRITE;
        if (!claims.hasPrivilege(required)) {
            throw new AccessDeniedException("writing a " + scope.name().toLowerCase(Locale.ROOT) + " saved query requires " + required);
        }
    }

    // No post-fetch ownership check lives here, deliberately. Ownership is enforced by the repository's
    // visibility predicate (SavedQueryRepository.findVisibleInScope) via SavedQueryService.loadVisible, so
    // an invisible row and a missing row share one path and both answer 404. A helper like
    // `isOwner(entity)` would invite a caller to fetch first and branch after — and the natural branch
    // answers 403, which is exactly the disclosure this capability must not make.
}
