package com.epam.aidial.analytics.service.table.grant;

import com.epam.aidial.analytics.service.security.RowPolicyFilter;
import com.epam.aidial.analytics.service.security.SecurityClaimsExtractor;
import com.epam.aidial.analytics.service.security.SensitiveColumnAccess;
import com.epam.aidial.analytics.service.security.SensitiveColumnAccess.ColumnVisibility;
import com.epam.aidial.analytics.service.table.ColumnMappingEntity;
import com.epam.aidial.analytics.service.table.ColumnMappingRepository;
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
import java.util.function.BooleanSupplier;

/**
 * Computes the calling identity's {@link EffectivePermissions} for a catalog table, reported on the table
 * read surfaces so clients need not re-implement authorization.
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
 *   <li>{@code grant} is the caller's global {@code TABLE_GRANT} (true in {@code none} mode), on every table alike;</li>
 *   <li>otherwise each flag is true when the caller holds the privilege globally, or when one of its raw
 *       provider roles appears in a list that grants or <b>implies</b> it — for an enrichment, the lists
 *       {@link GoverningAccess} composes from its source's read side and its own {@code write}/{@code modify}.</li>
 * </ul>
 *
 * <p>{@code rowsRestricted} is answered by {@link RowPolicyFilter#isConfined}, the evaluation enforcement
 * uses, on system and non-system tables alike — for an enrichment, on its source or on the enrichment itself;
 * {@code columnsRestricted} by {@link SensitiveColumnAccess#visibleColumns}, the decision every read surface applies.
 * Both are false whenever security is not enforced.
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
    private final GoverningAccess governing;
    private final SensitiveColumnAccess columnAccess;
    private final ColumnMappingRepository columns;

    public TableCallerPermissions(SecurityClaimsExtractor claimsExtractor, SecurityModeResolver securityMode,
            RowPolicyFilter rowPolicies, GoverningAccess governing, SensitiveColumnAccess columnAccess,
            ColumnMappingRepository columns) {
        this.claimsExtractor = claimsExtractor;
        this.securityMode = securityMode;
        this.rowPolicies = rowPolicies;
        this.governing = governing;
        this.columnAccess = columnAccess;
        this.columns = columns;
    }

    /**
     * The caller's effective permissions for {@code definition}, {@code columnsRestricted} judged over its current
     * columns only, so never right for a revision snapshot; package-private for the tests, production passes the columns.
     */
    EffectivePermissions resolve(TableDefinitionEntity definition) {
        return resolve(definition, () -> {
            final ColumnVisibility visibility = columnAccess.visibleColumns(definition);
            return hides(visibility, visibility.isAll() ? List.of() : columns.findByTableNameOrderByOrdinal(definition.getName()));
        });
    }

    /**
     * As {@link #resolve(TableDefinitionEntity)}, with {@code columnsRestricted} judged by {@code visibility} over
     * {@code ownColumns}: the columns the response is built from, which for a revision snapshot are the revision's. A
     * {@code null} list, a system table's snapshot whose columns are not tracked, reports restricted whenever
     * {@code visibility} confines the caller at all: judged without columns, it fails closed.
     */
    public EffectivePermissions resolve(TableDefinitionEntity definition, ColumnVisibility visibility, List<ColumnMappingEntity> ownColumns) {
        return resolve(definition, () -> hides(visibility, ownColumns));
    }

    private EffectivePermissions resolve(TableDefinitionEntity definition, BooleanSupplier columnsRestricted) {
        if (!securityMode.isEnforced()) {
            return definition.isSystem()
                    ? new EffectivePermissions(true, true, true, true, false, false, true, false, false)
                    : EffectivePermissions.uniform(true);
        }
        final Set<Privilege> held = claimsExtractor.getPrivileges();
        final Set<String> callerRoles = claimsExtractor.getProviderRoles();
        final TableDefinitionEntity source = governing.sourceOf(definition);
        final TableAccess access = GoverningAccess.compose(definition, source);
        final Map<Privilege, Boolean> flags = new EnumMap<>(Privilege.class);
        for (final Privilege privilege : PrivilegeScope.TABLE_SCOPED) {
            final boolean allowed = !(definition.isSystem() && PrivilegeScope.isModifying(privilege))
                    && (held.contains(privilege) || isGranted(access, privilege, callerRoles));
            flags.put(privilege, allowed);
        }
        // Global only: no entry confers it, and a system table or an enrichment accepts entries, so it is never masked.
        flags.put(Privilege.TABLE_GRANT, held.contains(Privilege.TABLE_GRANT));
        // An enrichment's rows are its source's; its own term is true only for a policy an earlier build left there.
        final boolean confined = rowPolicies.isConfined(definition) || source != null && rowPolicies.isConfined(source);
        return EffectivePermissions.of(flags, confined, columnsRestricted.getAsBoolean());
    }

    /**
     * Whether the column-visibility decision hides one of {@code ownColumns} from the caller — the one decision every
     * read surface applies, never a separate union. False when nothing restricts the caller; true when anything does and
     * {@code ownColumns} is {@code null}, not tracked.
     */
    private static boolean hides(ColumnVisibility visibility, List<ColumnMappingEntity> ownColumns) {
        return !visibility.isAll() && (ownColumns == null || ownColumns.stream().anyMatch(column -> !visibility.allows(column.getName())));
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
            final boolean modifying = PrivilegeScope.isModifying(conferring);
            // A '*' stored in a modifying list before the wildcard existed confers nothing: fail closed.
            if (access.rolesFor(conferring).stream()
                    .anyMatch(role -> callerRoles.contains(role) && !(modifying && SecurityClaimsExtractor.ANYONE.equals(role)))) {
                return true;
            }
        }
        return false;
    }
}
