package com.epam.aidial.analytics.service.table.grant;

import com.epam.aidial.analytics.service.security.RowPolicyFilter;
import com.epam.aidial.analytics.service.security.SecurityClaimsExtractor;
import com.epam.aidial.analytics.service.table.TableAccess;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.web.security.Privilege;
import com.epam.aidial.analytics.web.security.PrivilegeImplications;
import com.epam.aidial.analytics.web.security.SecurityModeResolver;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes the calling identity's {@link EffectivePermissions} for a catalog table, reported on the table
 * read surfaces so clients need not re-implement authorization, and returned from a grant replacement so
 * an operator can see immediately when a grant has no effect.
 *
 * <p>The answer mirrors what enforcement would decide:
 *
 * <ul>
 *   <li>a {@code system} table reports {@code write=false, modify=false} for every caller — every
 *       modifying operation on it is refused whatever any list says — while its <b>read-side</b> flags are
 *       evaluated from the grant lists like any other table's. Reporting all-false for a system table, as
 *       this used to, denied the very read grants that make per-table read applicable to
 *       {@code dial_usage_log};</li>
 *   <li>in {@code none} mode nothing is enforced, so every non-system flag is true;</li>
 *   <li>otherwise each flag is true when the caller holds the privilege globally, or when one of its raw
 *       provider roles appears in a list that grants or <b>implies</b> it.</li>
 * </ul>
 *
 * <p>{@code rowsRestricted} is answered by {@link RowPolicyFilter#isConfined}, the evaluation enforcement
 * uses, on system and non-system tables alike; it is false whenever security is not enforced.
 *
 * <p>{@link #isGranted} is the single matcher, shared with {@link TableAccessEvaluator}, so reporting and
 * enforcement cannot diverge. There is no role-name bypass: a caller holding every privilege is reported
 * as holding everything because it holds the privileges globally.
 */
@Component
public class TableCallerPermissions {

    private final SecurityClaimsExtractor claimsExtractor;
    private final SecurityModeResolver securityMode;
    private final RowPolicyFilter rowPolicies;

    public TableCallerPermissions(
            SecurityClaimsExtractor claimsExtractor, SecurityModeResolver securityMode, RowPolicyFilter rowPolicies) {
        this.claimsExtractor = claimsExtractor;
        this.securityMode = securityMode;
        this.rowPolicies = rowPolicies;
    }

    /** The caller's effective permissions for {@code definition}. */
    public EffectivePermissions resolve(TableDefinitionEntity definition) {
        if (!securityMode.isEnforced()) {
            return definition.isSystem() ? EffectivePermissions.readSideOnly(true) : EffectivePermissions.uniform(true);
        }
        final Set<Privilege> held = claimsExtractor.getPrivileges();
        final Set<String> callerRoles = claimsExtractor.getProviderRoles();
        final Map<Privilege, Boolean> flags = new EnumMap<>(Privilege.class);
        for (final Privilege privilege : PrivilegeScope.TABLE_SCOPED) {
            final boolean allowed = !(definition.isSystem() && PrivilegeScope.isModifying(privilege))
                    && (held.contains(privilege) || isGranted(definition.getAccess(), privilege, callerRoles));
            flags.put(privilege, allowed);
        }
        return EffectivePermissions.of(flags, rowPolicies.isConfined(definition));
    }

    /**
     * Whether one of {@code callerRoles} appears in a list of {@code access} that grants {@code required}
     * or confers it by implication; null-safe on both the access object and its lists.
     *
     * <p>This is where implication is <em>derived</em>: rather than expanding a grant when it is written,
     * the check consults every list whose privilege's closure contains {@code required}. Deriving is
     * unavoidable anyway — a caller holding the privilege globally has no entry in any list — and
     * expanding at grant time would mutate the operator's stated intent and make revoke asymmetric.
     */
    public static boolean isGranted(TableAccess access, Privilege required, Set<String> callerRoles) {
        if (access == null || callerRoles.isEmpty()) {
            return false;
        }
        for (final Privilege conferring : PrivilegeImplications.conferring(required)) {
            if (!PrivilegeScope.isGrantable(conferring)) {
                continue;
            }
            final List<String> grantedRoles = access.rolesFor(conferring);
            if (grantedRoles.stream().anyMatch(callerRoles::contains)) {
                return true;
            }
        }
        return false;
    }
}
