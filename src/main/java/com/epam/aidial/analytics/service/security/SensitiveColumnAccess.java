package com.epam.aidial.analytics.service.security;

import com.epam.aidial.analytics.service.table.grant.TableAccessEvaluator;
import com.epam.aidial.analytics.web.security.Privilege;
import com.epam.aidial.analytics.web.security.SecurityModeResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Decides, <b>per owning table</b>, whether the current caller may read that table's values at all and
 * whether it may see its {@code sensitive} columns. Each answer is a table-scoped privilege held globally
 * or granted on that table.
 *
 * <p><b>Sensitivity is a clearance, so there are two sensitive predicates and the call site picks.</b>
 * {@link Privilege#TABLE_SENSITIVE} extends whatever verb the caller holds, so a sensitive column is
 * reachable only with the verb <em>and</em> the clearance on its table:
 *
 * <ul>
 *   <li>{@link #maySeeSensitive} is the clearance alone. It is right only where the surface has already
 *       enforced its verb bar on the same table — the query model ({@code findModel(entity, bar)}), the
 *       catalog read surface ({@code TABLE_GET} at the endpoint), a schema mutation ({@code TABLE_MODIFY}).</li>
 *   <li>{@link #mayReadSensitive} is {@code TABLE_READ} and the clearance. It is for a path that enforces
 *       no table verb of its own — pipeline authoring behind {@code REGISTRY_ADMIN}, the runner's data-plane
 *       guards — where the clearance alone would let a caller with no read reference sensitive values. The
 *       former {@code TABLE_READ_SENSITIVE} carried that read implicitly; this carries it explicitly.</li>
 * </ul>
 *
 * <p>Ownership matters because a queryable entity is a composite — a source flattened with its active
 * enrichments — so one request can legitimately see the source's sensitive columns and not an
 * enrichment's, or the reverse. The decision is therefore taken where each column's owning table is in
 * hand rather than hoisted once per request, which is what the previous single global answer did.
 *
 * <p>This is the one signal the query model consults ({@link
 * com.epam.aidial.analytics.service.query.CatalogQueryService}); because discovery, structured execution,
 * SQL execution, translation and Flight all resolve through that one model, the decisions made here govern
 * every query surface uniformly. <b>Route every new read surface through it</b>, or it bypasses both
 * filters with nothing failing.
 *
 * <p>When security is disabled ({@code security.mode=none}) both checks fail open, so local/dev and the
 * {@code none} deployment mode behave as they did before per-table reads existed. The mode comes from the
 * single {@link SecurityModeResolver}, so this cannot disagree with the HTTP or Flight gate about whether
 * authorization is enforced — a divergence that previously served sensitive columns to anonymous Flight
 * callers whenever {@code security.mode} was spelled {@code OIDC}.
 */
@Component
@RequiredArgsConstructor
public class SensitiveColumnAccess {

    private final TableAccessEvaluator tableAccess;
    private final SecurityModeResolver securityMode;

    /** Whether the caller may see that {@code table} exists. */
    public boolean mayList(String table) {
        return !securityMode.isEnforced() || tableAccess.isAllowed(table, Privilege.TABLE_LIST);
    }

    /** Whether the caller may read {@code owningTable}'s non-sensitive values. */
    public boolean mayRead(String owningTable) {
        return !securityMode.isEnforced() || tableAccess.isAllowed(owningTable, Privilege.TABLE_READ);
    }

    /**
     * Whether the caller holds the sensitive clearance on {@code owningTable}: its sensitive columns are
     * visible at whatever verb bar the calling surface has <b>already</b> enforced on that table.
     */
    public boolean maySeeSensitive(String owningTable) {
        return !securityMode.isEnforced() || tableAccess.isAllowed(owningTable, Privilege.TABLE_SENSITIVE);
    }

    /**
     * Whether the caller may read {@code owningTable}'s sensitive column values: {@code TABLE_READ} and
     * the clearance, each global or granted. For a path that enforces no table verb of its own.
     */
    public boolean mayReadSensitive(String owningTable) {
        return mayRead(owningTable) && maySeeSensitive(owningTable);
    }

    /** Whether the caller may read {@code owningTable}'s schema (its column inventory). */
    public boolean mayGet(String owningTable) {
        return !securityMode.isEnforced() || tableAccess.isAllowed(owningTable, Privilege.TABLE_GET);
    }

}
