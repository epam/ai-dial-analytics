package com.epam.aidial.analytics.web.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the single {@link Privilege} a request mapping requires. Replaces {@code @FullAdminOnly} and
 * the hand-written {@code @PreAuthorize("@tableAccess.can*(#name)")} expressions with one vocabulary,
 * enforced by {@link PrivilegeAuthorizationManager}.
 *
 * <p>Exactly one of three enforcement shapes applies:
 *
 * <ul>
 *   <li><b>Global</b> — neither {@link #table()} nor {@link #deferred()} is set. The caller must hold the
 *       privilege globally; otherwise {@code 403 forbidden}.</li>
 *   <li><b>Per-table</b> — {@link #table()} names the handler parameter carrying the table name. The
 *       caller must hold the privilege globally or by a grant on that table. A missing read-side
 *       privilege answers {@code 404 not_found} rather than {@code 403}, so the refusal does not disclose
 *       that the table exists; a missing write-side privilege is {@code 403 forbidden}.</li>
 *   <li><b>Deferred</b> — {@link #deferred()} is {@code true}. What the request needs is not decidable
 *       here: the tables are not known (the listing is filtered, or the query resolves its tables as it is
 *       built), or <em>which</em> privilege applies depends on the request body (a saved query's
 *       {@code scope} selects {@code SAVED_QUERY_WRITE} or {@code SAVED_QUERY_PUBLISH}). Authorization
 *       still happens, downstream, at the chokepoint where the discriminator is in hand — the query model
 *       builder, the listing filter, or {@code SavedQueryAccess.requireWritable}. The declaration here is
 *       what the coverage test checks and what documents the endpoint; the gate itself requires only
 *       authentication, so that chokepoint must be total.</li>
 * </ul>
 *
 * <p>Because the {@code /v1/**} URL rule no longer authorizes by role, a mapping that carries neither this
 * annotation nor {@link AuthenticatedOnly} is a build failure — see
 * {@code EndpointAuthorizationCoverageTest}. Enforced only in {@code oidc} mode, where the advisor is
 * registered; in {@code none} mode it is inert, exactly as method security was.
 *
 * <p><b>Method target only.</b> A class-level declaration would read as covering every handler on the
 * controller while authorizing nothing — {@link PrivilegeAuthorizationManager} resolves the annotation from
 * the invoked method, and the coverage test looks in the same place — so the target excludes {@code TYPE}
 * rather than leaving behind a shape that looks enforcing and is not.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPrivilege {

    /** The privilege this endpoint requires. */
    Privilege value();

    /**
     * The name of the handler parameter holding the table the privilege is checked against. Empty for a
     * global or deferred check. Only meaningful for a table-scoped privilege.
     */
    String table() default "";

    /**
     * Whether enforcement happens downstream rather than at this gate — because the tables the request
     * resolves are not known here, or because the request body decides which privilege applies. Mutually
     * exclusive with {@link #table()}: a check is made here from a named parameter, or downstream, not both.
     */
    boolean deferred() default false;
}
