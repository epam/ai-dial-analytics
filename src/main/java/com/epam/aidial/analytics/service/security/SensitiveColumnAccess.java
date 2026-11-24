package com.epam.aidial.analytics.service.security;

import com.epam.aidial.analytics.service.query.ComputedFields;
import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.table.ColumnMappingEntity;
import com.epam.aidial.analytics.service.table.ColumnMappingRepository;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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
    private final ColumnMappingRepository columns;

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
     * How the caller sees each of {@code definition}'s own columns as far as column policies go — visible, masked or
     * hidden (the clearance is separate and stacks on top) — from the column policies of its <b>governing roles</b> on
     * the table, for an enrichment on its source, naming the enrichment's columns {@code <enrichment>.<column>}:
     *
     * <ul>
     *   <li>when the caller may read the table, the roles that by themselves confer the read ({@link
     *       RowPolicyFilter#readsTable}, {@code *} included); otherwise the roles through which it holds
     *       {@code TABLE_GET};</li>
     *   <li>a column is visible when some governing role with no row policy allows it, or every governing role and every
     *       row-contributing role allows it ("allows": no column policy, or one listing it); masked when it is not
     *       visible but some governing role allows it, absent on the rows the allowing roles' row policies do not admit;
     *       hidden otherwise. A row-contributing role (one whose policy enters the row filter, {@link
     *       RowPolicyFilter#confiningRoles}, but which does not govern) allows a column only when every governing role
     *       does too, so it only narrows;</li>
     *   <li>a column that would be masked is hidden when a table of the entity stores a column named after a table of
     *       the entity, which would capture the qualified names a mask is evaluated over;</li>
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
        final List<Grant> governingGrants = new ArrayList<>();
        final Set<String> governingRoles = new HashSet<>();
        // Sorted, so the mask conditions built from these grants are the same text on every request.
        for (final String role : new TreeSet<>(roles)) {
            final boolean governs = mayRead
                    ? RowPolicyFilter.readsTable(role, access, globalRead, rolePrivileges)
                    : TableCallerPermissions.isGranted(access, Privilege.TABLE_GET, Set.of(role))
                            || globalGet && rolePrivileges.holdsGlobally(role, Privilege.TABLE_GET);
            if (governs) {
                governingRoles.add(role);
                governingGrants.add(Grant.of(columnPolicies.get(role), rowPolicies.get(role)));
            }
        }
        if (governingGrants.isEmpty() || governingGrants.stream().anyMatch(grant -> grant.filter() == null && grant.columns() == null)) {
            return ColumnVisibility.ALL;
        }
        final List<Grant> contributing = new ArrayList<>();
        for (final String role : RowPolicyFilter.confiningRoles(rowPolicies, access, roles, globalRead, rolePrivileges)) {
            if (!governingRoles.contains(role)) {
                contributing.add(Grant.of(columnPolicies.get(role), rowPolicies.get(role)));
            }
        }
        if (governingGrants.stream().allMatch(grant -> grant.columns() == null)
                && contributing.stream().allMatch(grant -> grant.columns() == null)) {
            return ColumnVisibility.ALL;
        }
        final String prefix = enrichment ? definition.getName() + "." : "";
        final Set<String> named = new HashSet<>();
        for (final Grant grant : governingGrants) {
            named.addAll(own(grant.columns(), prefix));
        }
        for (final Grant grant : contributing) {
            named.addAll(own(grant.columns(), prefix));
        }
        final boolean[] fallback = new boolean[2];
        final Set<String> visible = new HashSet<>();
        final Map<String, List<FilterNode>> masked = new HashMap<>();
        for (final String name : named) {
            final List<FilterNode> mask = decide(prefix + name, governingGrants, contributing);
            if (mask == null) {
                visible.add(name);
            } else if (!mask.isEmpty() && !hidesMasks(governed, fallback)) {
                masked.put(name, mask);
            }
        }
        // A column no list names: the same decision for all of them, made on a name no list holds.
        final List<FilterNode> otherwise = decide(null, governingGrants, contributing);
        final boolean otherwiseMasked = otherwise != null && !otherwise.isEmpty() && !hidesMasks(governed, fallback);
        return new ColumnVisibility(visible, masked, named, otherwiseMasked ? otherwise : null);
    }

    /**
     * One governing or row-contributing role's say on a table: the columns its policy allows ({@code null} for every
     * column) and its row policy's filter ({@code null} when it has none).
     */
    private record Grant(List<String> columns, FilterNode filter) {

        static Grant of(List<String> columns, RowPolicy policy) {
            return new Grant(columns, policy == null ? null : policy.filter());
        }

        /** Whether this role allows the column a policy names {@code key}; {@code null} for a name no list holds. */
        boolean allows(String key) {
            return columns == null || key != null && columns.contains(key);
        }
    }

    /** The exposed names in {@code columns} that belong to the table a policy names with {@code prefix}. */
    private static List<String> own(List<String> columns, String prefix) {
        if (columns == null) {
            return List.of();
        }
        if (prefix.isEmpty()) {
            // A source's own column name holds no dot; a dotted name is one of its enrichments'.
            return columns.stream().filter(name -> name.indexOf('.') < 0).toList();
        }
        return columns.stream().filter(name -> name.startsWith(prefix)).map(name -> name.substring(prefix.length())).toList();
    }

    /**
     * The decision on the column a policy names {@code key}: {@code null} when it is visible, the filters of its mask
     * condition when it is masked, and an empty list when it is hidden.
     */
    private static List<FilterNode> decide(String key, List<Grant> governing, List<Grant> contributing) {
        final boolean everyGoverning = governing.stream().allMatch(grant -> grant.allows(key));
        if (governing.stream().anyMatch(grant -> grant.filter() == null && grant.allows(key))
                || everyGoverning && contributing.stream().allMatch(grant -> grant.allows(key))) {
            return null;
        }
        // Every governing role that allows it carries a row policy, or the column would be visible.
        final List<FilterNode> filters = new ArrayList<>();
        governing.stream().filter(grant -> grant.allows(key)).forEach(grant -> filters.add(grant.filter()));
        if (!filters.isEmpty() && everyGoverning) {
            contributing.stream().filter(grant -> grant.allows(key)).forEach(grant -> filters.add(grant.filter()));
        }
        return filters;
    }

    /**
     * Whether masking falls back to hiding on {@code governed}'s entity: a table of it stores a column whose physical name
     * is the name of a table of it, every enrichment counted whatever its status. The store resolves a table-qualified
     * name to such a column's subcolumns, so a mask over {@code "<table>"."<column>"} could read the wrong value. Asked
     * at most once per decision ({@code memo}: asked, answer), and only once a column would be masked.
     */
    private boolean hidesMasks(TableDefinitionEntity governed, boolean[] memo) {
        if (!memo[0]) {
            memo[0] = true;
            final Set<String> tables = new HashSet<>();
            tables.add(governed.getName());
            definitions.findBySourceTable(governed.getName()).forEach(enrichment -> tables.add(enrichment.getName()));
            memo[1] = tables.stream().anyMatch(table -> columns.findByTableNameOrderByOrdinal(table).stream()
                    .anyMatch(column -> tables.contains(column.getSourceName())));
        }
        return memo[1];
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
     * One caller's view of one table's columns, by exposed name (an enrichment's bare, not qualified): each column is
     * visible, masked or hidden. {@link #ALL} places no restriction.
     *
     * @param visible   the visible names, or {@code null} for every column
     * @param masked    the masked names among {@code named}, each with the filters whose disjunction is its mask condition
     * @param named     the names some policy lists; a name outside it is decided by {@code otherwise}
     * @param otherwise the mask filters of a column no policy names, or {@code null} when such a column is hidden
     */
    public record ColumnVisibility(Set<String> visible, Map<String, List<FilterNode>> masked, Set<String> named,
            List<FilterNode> otherwise) {

        /** No column hidden or masked by a column policy. */
        public static final ColumnVisibility ALL = new ColumnVisibility(null, Map.of(), Set.of(), null);

        public ColumnVisibility {
            visible = visible == null ? null : Set.copyOf(visible);
            masked = masked == null ? Map.of() : Map.copyOf(masked);
            named = named == null ? Set.of() : Set.copyOf(named);
            otherwise = otherwise == null ? null : List.copyOf(otherwise);
        }

        /** Exactly {@code visible} visible, every other column hidden. */
        public ColumnVisibility(Set<String> visible) {
            this(visible, Map.of(), visible, null);
        }

        /** Whether the column exposed as {@code column} is visible or masked: listed and referenced like a visible one. */
        public boolean allows(String column) {
            return visible(column) || masks(column) != null;
        }

        /**
         * Whether {@code column} — a catalog column or a computed field's transient descriptor ({@link ComputedFields})
         * among {@code columns} — is visible or masked. A computed field is so exactly when no catalog column whose
         * values it returns is hidden.
         */
        public boolean allows(ColumnMappingEntity column, List<ColumnMappingEntity> columns) {
            return inputs(column, columns).stream().allMatch(this::allows);
        }

        /** Whether the column exposed as {@code column} is visible: its stored value on every row the caller reads. */
        public boolean visible(String column) {
            return visible == null || visible.contains(column);
        }

        /** As {@link #allows(ColumnMappingEntity, List)}, for visible: every column whose values it returns is visible. */
        public boolean visible(ColumnMappingEntity column, List<ColumnMappingEntity> columns) {
            return inputs(column, columns).stream().allMatch(this::visible);
        }

        /** The filters whose disjunction is {@code column}'s mask condition when it is masked, else {@code null}. */
        public List<FilterNode> masks(String column) {
            if (visible(column)) {
                return null;
            }
            return named.contains(column) ? masked.get(column) : otherwise;
        }

        /**
         * The exposed names of the catalog columns whose values {@code column} returns: its own name for a catalog column,
         * the columns a computed field is completed from for one.
         */
        public static List<String> inputs(ColumnMappingEntity column, List<ColumnMappingEntity> columns) {
            if (!ComputedFields.isComputed(column.getTableName() + "." + column.getName())) {
                return List.of(column.getName());
            }
            return columns.stream()
                    .filter(candidate -> ComputedFields.ANCHOR.equals(candidate.getSourceName())
                            || ComputedFields.MARKERS.equals(candidate.getSourceName()))
                    .map(ColumnMappingEntity::getName)
                    .toList();
        }

        /**
         * The columns both this and {@code other} allow: hidden when either hides, else masked when either masks. Read for
         * the listing alone (revision snapshots), so a column both mask carries this side's filters.
         */
        public ColumnVisibility and(ColumnVisibility other) {
            if (other.visible == null) {
                return this;
            }
            if (visible == null) {
                return other;
            }
            final Set<String> names = new HashSet<>(named);
            names.addAll(other.named);
            final Set<String> both = new HashSet<>();
            final Map<String, List<FilterNode>> masks = new HashMap<>();
            for (final String name : names) {
                if (!allows(name) || !other.allows(name)) {
                    continue;
                }
                final List<FilterNode> mask = masks(name) != null ? masks(name) : other.masks(name);
                if (mask == null) {
                    both.add(name);
                } else {
                    masks.put(name, mask);
                }
            }
            return new ColumnVisibility(both, masks, names, otherwise == null || other.otherwise == null ? null : otherwise);
        }

        /** Whether no column policy restricts the caller here: nothing hidden and nothing masked. */
        public boolean isAll() {
            return visible == null;
        }
    }
}
