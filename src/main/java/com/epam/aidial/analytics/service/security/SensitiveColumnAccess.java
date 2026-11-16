package com.epam.aidial.analytics.service.security;

import com.epam.aidial.analytics.service.query.ComputedFields;
import com.epam.aidial.analytics.service.table.ColumnMappingEntity;
import com.epam.aidial.analytics.service.table.TableAccess;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import com.epam.aidial.analytics.service.table.TableType;
import com.epam.aidial.analytics.service.table.grant.ColumnPolicyCodec;
import com.epam.aidial.analytics.service.table.grant.GoverningAccess;
import com.epam.aidial.analytics.service.table.grant.TableAccessEvaluator;
import com.epam.aidial.analytics.service.table.grant.TableCallerPermissions;
import com.epam.aidial.analytics.service.table.rowpolicy.RowPolicy;
import com.epam.aidial.analytics.service.table.rowpolicy.RowPolicyCodec;
import com.epam.aidial.analytics.web.security.Privilege;
import com.epam.aidial.analytics.web.security.ProviderRolePrivileges;
import com.epam.aidial.analytics.web.security.SecurityModeResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * <p>Each column is judged on its owning table. A queryable entity is a composite — a source flattened with
 * its active enrichments — and an enrichment's read side is its source's ({@code GoverningAccess}), so within
 * one entity every column answers the same read and clearance questions; the verb bars still differ, since an
 * enrichment's own {@code modify} confers {@code TABLE_GET} on it alone.
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
    private final TableDefinitionRepository definitions;
    private final GoverningAccess governing;
    private final SecurityClaimsExtractor claims;
    private final ProviderRolePrivileges rolePrivileges;
    private final RowPolicyCodec rowPolicyCodec;
    private final ColumnPolicyCodec columnPolicyCodec;

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

    /** {@link #visibleColumns(TableDefinitionEntity)} for {@code owningTable} as stored; every column when it does not exist. */
    @Transactional(readOnly = true)
    public ColumnVisibility visibleColumns(String owningTable) {
        if (!securityMode.isEnforced()) {
            return ColumnVisibility.ALL;
        }
        return definitions.findById(owningTable).map(this::visibleColumns).orElse(ColumnVisibility.ALL);
    }

    /**
     * Which of {@code definition}'s own columns the caller sees as far as column policies go (the clearance is
     * separate and stacks on top), from the column policies of its <b>governing roles</b> on the table — for an
     * enrichment, on its source, naming the enrichment's columns {@code <enrichment>.<column>}:
     *
     * <ul>
     *   <li>when the caller may read the table, the roles that by themselves confer the read ({@link
     *       RowPolicyFilter#readsTable}, {@code *} included); otherwise the roles through which it holds
     *       {@code TABLE_GET};</li>
     *   <li>a column is visible when some governing role with no row policy allows it, or every governing role allows
     *       it ("allows": no column policy, or one listing it) — so one role's columns are never shown on rows only
     *       another role admits;</li>
     *   <li>a caller with no governing role is not narrowed, nor is one in {@code none} mode or the system identity
     *       (which carries no provider role); an enrichment maintainer that may not read the source sees every column
     *       of the enrichment.</li>
     * </ul>
     *
     * <p>Computed per call and never memoised beyond it: Flight and scheduler threads have no request scope, so a
     * table-keyed cache would serve one caller's set to another.
     */
    public ColumnVisibility visibleColumns(TableDefinitionEntity definition) {
        if (!securityMode.isEnforced()) {
            return ColumnVisibility.ALL;
        }
        final Set<String> roles = claims.getProviderRoles();
        if (roles.isEmpty()) {
            return ColumnVisibility.ALL;
        }
        final TableDefinitionEntity governed = definition.getType() == TableType.ENRICHMENT ? governing.sourceOf(definition) : definition;
        return governed == null ? ColumnVisibility.ALL : visibleColumns(definition, governed, roles);
    }

    /** {@link #visibleColumns(TableDefinitionEntity)} judged on {@code governed}: the table itself, or an enrichment's source. */
    private ColumnVisibility visibleColumns(TableDefinitionEntity definition, TableDefinitionEntity governed, Set<String> roles) {
        final boolean enrichment = definition.getType() == TableType.ENRICHMENT;
        final Set<Privilege> held = claims.getPrivileges();
        final TableAccess access = governed.getAccess();
        final boolean globalRead = held.contains(Privilege.TABLE_READ);
        final boolean mayRead = globalRead || TableCallerPermissions.isGranted(access, Privilege.TABLE_READ, roles);
        if (enrichment && !mayRead && TableCallerPermissions.isGranted(definition.getAccess(), Privilege.TABLE_MODIFY, roles)) {
            return ColumnVisibility.ALL;
        }
        final boolean globalGet = held.contains(Privilege.TABLE_GET);
        final Map<String, RowPolicy> rowPolicies = rowPolicyCodec.read(governed.getRowPolicies());
        final Map<String, List<String>> columnPolicies = columnPolicyCodec.read(governed.getColumnPolicies());
        final Set<String> union = new HashSet<>();
        Set<String> intersection = null;
        boolean anyGoverning = false;
        for (final String role : roles) {
            final boolean governs = mayRead
                    ? RowPolicyFilter.readsTable(role, access, globalRead, rolePrivileges)
                    : TableCallerPermissions.isGranted(access, Privilege.TABLE_GET, Set.of(role))
                            || globalGet && rolePrivileges.holdsGlobally(role, Privilege.TABLE_GET);
            if (!governs) {
                continue;
            }
            anyGoverning = true;
            final List<String> allowed = columnPolicies.get(role);
            final boolean rowUnrestricted = !rowPolicies.containsKey(role);
            if (allowed == null) {
                if (rowUnrestricted) {
                    return ColumnVisibility.ALL;
                }
                continue;
            }
            if (rowUnrestricted) {
                union.addAll(allowed);
            }
            if (intersection == null) {
                intersection = new HashSet<>(allowed);
            } else {
                intersection.retainAll(allowed);
            }
        }
        if (!anyGoverning || intersection == null) {
            return ColumnVisibility.ALL;
        }
        union.addAll(intersection);
        if (!enrichment) {
            return new ColumnVisibility(Set.copyOf(union));
        }
        final String prefix = definition.getName() + ".";
        final Set<String> own = new HashSet<>();
        union.stream().filter(name -> name.startsWith(prefix)).forEach(name -> own.add(name.substring(prefix.length())));
        return new ColumnVisibility(Set.copyOf(own));
    }

    /**
     * Which of a revision snapshot's columns the caller sees: those both the snapshot's column policies and those of
     * {@code life}, the table's state in the life containing the revision, allow, so a restriction added since the
     * revision, or recorded before column policies existed, still hides the column, and a later table of the same name
     * has no say. An enrichment's columns are judged on {@code sourceLife}, the source state that speaks for the
     * enrichment's life. Without a state, every column is hidden. Names are matched as the snapshot spells them; an old
     * name the life's allowlist does not list is hidden.
     */
    public ColumnVisibility visibleColumnsAtRevision(TableDefinitionEntity snapshot, TableDefinitionEntity life, TableDefinitionEntity sourceLife) {
        if (!securityMode.isEnforced()) {
            return ColumnVisibility.ALL;
        }
        final Set<String> roles = claims.getProviderRoles();
        if (roles.isEmpty()) {
            return ColumnVisibility.ALL;
        }
        final boolean enrichment = snapshot.getType() == TableType.ENRICHMENT;
        if (life == null || enrichment && sourceLife == null) {
            return new ColumnVisibility(Set.of());
        }
        return visibleColumns(snapshot, enrichment ? sourceLife : snapshot, roles)
                .and(visibleColumns(life, enrichment ? sourceLife : life, roles));
    }

    /**
     * One caller's visible columns of one table, by exposed name (an enrichment's bare, not qualified); {@link #ALL}
     * places no restriction.
     *
     * @param visible the visible names, or {@code null} for every column
     */
    public record ColumnVisibility(Set<String> visible) {

        /** No column hidden by a column policy. */
        public static final ColumnVisibility ALL = new ColumnVisibility(null);

        /** Whether the column exposed as {@code column} is visible. */
        public boolean allows(String column) {
            return visible == null || visible.contains(column);
        }

        /**
         * Whether {@code column} — a catalog column or a computed field's transient descriptor ({@link ComputedFields})
         * among {@code columns} — is visible. A computed field is visible exactly when every catalog column whose values
         * it returns is.
         */
        public boolean allows(ColumnMappingEntity column, List<ColumnMappingEntity> columns) {
            if (visible == null) {
                return true;
            }
            if (!ComputedFields.isComputed(column.getTableName() + "." + column.getName())) {
                return allows(column.getName());
            }
            return columns.stream()
                    .filter(candidate -> ComputedFields.ANCHOR.equals(candidate.getSourceName())
                            || ComputedFields.MARKERS.equals(candidate.getSourceName()))
                    .allMatch(candidate -> allows(candidate.getName()));
        }

        /** The columns both this and {@code other} allow. */
        public ColumnVisibility and(ColumnVisibility other) {
            if (other.visible == null) {
                return this;
            }
            if (visible == null) {
                return other;
            }
            final Set<String> both = new HashSet<>(visible);
            both.retainAll(other.visible);
            return new ColumnVisibility(Set.copyOf(both));
        }

        /** Whether no column policy restricts the caller here. */
        public boolean isAll() {
            return visible == null;
        }
    }
}
