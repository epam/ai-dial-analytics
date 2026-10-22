package com.epam.aidial.analytics.web.security;

import java.util.EnumSet;
import java.util.Set;

/**
 * A reserved name accepted in any {@code roles-mapping} that expands to a set of {@link Privilege}s.
 *
 * <p>A bundle exists only in configuration: it is expanded once when the mapping is parsed and is
 * invisible below that point — no endpoint, filter or evaluator branches on a bundle name. That is what
 * removes the {@code FULL_ADMIN} short-circuit the per-table evaluator used to carry: a caller holding
 * every privilege is authorized because it holds the privilege, not because of its role name.
 *
 * <p>Each bundle stores its privileges <b>minimally</b> and is closed by {@link PrivilegeImplications} at
 * expansion time, so implication has one source of truth. {@code FULL_ADMIN} and {@code READ_ONLY_ADMIN}
 * expand to exactly the authority the corresponding application role carried before privileges existed,
 * with a single deliberate exception, declared in the {@code authentication} capability:
 * {@code GET /v1/tables/{name}/access} now requires {@code TABLE_GRANT}, which {@code READ_ONLY_ADMIN}
 * does not hold, because the grant lists disclose which provider roles exist and what each may do. No
 * endpoint widens: {@code REGISTRY_READ} reproduces the reach the old {@code /v1/**} role rule gave
 * pipeline reads rather than extending it.
 *
 * <p>Bundle names are not valid grantees in a table's access lists, which name raw provider roles.
 */
public enum RoleBundle {

    /** Every privilege. The pre-privilege {@code FULL_ADMIN} application role. */
    FULL_ADMIN(EnumSet.allOf(Privilege.class)),

    /** Read everything, plus keep personal saved queries. The pre-privilege {@code READ_ONLY_ADMIN}. */
    READ_ONLY_ADMIN(EnumSet.of(
            Privilege.TABLE_READ,
            Privilege.REGISTRY_READ,
            Privilege.AUDIT_READ,
            Privilege.SAVED_QUERY_READ,
            Privilege.SAVED_QUERY_WRITE)),

    /** An analyst: read every table's non-sensitive values and keep personal saved queries. */
    READ_ONLY_ANALYTIC(EnumSet.of(
            Privilege.TABLE_READ,
            Privilege.SAVED_QUERY_READ,
            Privilege.SAVED_QUERY_WRITE)),

    /**
     * An analyst entitled to sensitive values. The bundle that finally makes PII read reachable without
     * administrative authority — no catalog DDL, no grant management, no registry authoring.
     */
    READ_ONLY_SENSITIVE_ANALYTIC(EnumSet.of(
            Privilege.TABLE_READ,
            Privilege.TABLE_SENSITIVE,
            Privilege.SAVED_QUERY_READ,
            Privilege.SAVED_QUERY_WRITE));

    private final Set<Privilege> declared;

    RoleBundle(Set<Privilege> declared) {
        this.declared = Set.copyOf(declared);
    }

    /** The privileges as declared, before implication. Exposed so a test can assert the closure. */
    public Set<Privilege> declaredPrivileges() {
        return declared;
    }

    /** The privileges this bundle confers: the declared set closed under implication. */
    public Set<Privilege> privileges() {
        return PrivilegeImplications.closure(declared);
    }

    /** The bundle with this name, or {@code null} when the name is not a bundle. */
    public static RoleBundle byName(String name) {
        for (final RoleBundle bundle : values()) {
            if (bundle.name().equals(name)) {
                return bundle;
            }
        }
        return null;
    }
}
