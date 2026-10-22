package com.epam.aidial.analytics.service.security;

import com.clickhouse.client.api.query.QuerySettings;
import com.epam.aidial.analytics.configuration.ClickHouseProperties;
import com.epam.aidial.analytics.exception.RowPolicyConfinedException;
import com.epam.aidial.analytics.service.query.RowPolicyPredicates;
import com.epam.aidial.analytics.service.table.TableAccess;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository.StoredRowPolicies;
import com.epam.aidial.analytics.service.table.grant.TableCallerPermissions;
import com.epam.aidial.analytics.service.table.rowpolicy.RowPolicy;
import com.epam.aidial.analytics.service.table.rowpolicy.RowPolicyCodec;
import com.epam.aidial.analytics.web.security.Privilege;
import com.epam.aidial.analytics.web.security.ProviderRolePrivileges;
import com.epam.aidial.analytics.web.security.SecurityModeResolver;
import io.opentelemetry.api.trace.Span;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Decides which rows the current caller may see of each table, from the tables' row policies, and turns that
 * decision into the ClickHouse {@code additional_table_filters} setting a user read carries.
 *
 * <p><b>The rule.</b> For a table, take the policies whose role is one of the caller's raw provider roles:
 *
 * <ul>
 *   <li>none — the caller is unconfined there;</li>
 *   <li>otherwise, if another of the caller's roles <b>may read the table</b> and has <b>no</b> policy on it,
 *       the caller is unconfined there too: that role's unrestricted read stands on its own;</li>
 *   <li>otherwise the caller sees the rows satisfying at least one of its policies' filters.</li>
 * </ul>
 *
 * <p>Only a role that confers the read counts in the second case. The caller's provider roles are every IdP
 * group or Core role it carries, and nearly all of them have no policy because they have nothing to do with
 * the table; letting any of those lift the confinement would make a policy on a widely-held role meaningless.
 * A role confers the read when the table's {@code read} grant names it, or when it maps to {@code TABLE_READ}
 * globally <em>and</em> the caller actually holds {@code TABLE_READ} globally on this request — the second half
 * because the role-name lookup ({@link ProviderRolePrivileges}) is a union across every configured mapping, and
 * enforcement must not credit a role with a read the caller's own provider does not give it.
 *
 * <p>A policy never grants a read — whether the table is readable at all is still decided by the grant
 * evaluator — and a policy on a role takes that role out of the second case, so a broadly-entitled role is
 * confined by giving it a policy of its own.
 *
 * <p><b>Why the store enforces it.</b> ClickHouse applies {@code additional_table_filters} to every reference
 * of a table a statement makes — joins, subqueries, set operations, after {@code FINAL} — so the guarantee
 * does not depend on the renderer having anticipated a query shape. Two properties of the setting shape what
 * is sent: an entry whose key names no table the statement reads is silently ignored, while a reference with
 * no entry is unfiltered. So the setting carries an entry for <b>every</b> table the caller is confined on,
 * not only those the statement is believed to read, and every key is database-qualified with the database
 * the read client's session resolves the renderer's unqualified names against.
 *
 * <p>Only user reads carry it ({@code StructuredQueryExecutor}); pipeline reads and the system identity —
 * which has no provider roles, so is never confined — never do. Pipeline paths that would read a confined
 * table on a confined caller's behalf refuse instead ({@link #requireUnconfined}).
 *
 * <p>Inert when security is not enforced, through the single {@link SecurityModeResolver}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RowPolicyFilter {

    /** The ClickHouse setting a user read carries the filters in. */
    public static final String SETTING = "additional_table_filters";

    private final TableDefinitionRepository definitions;
    private final RowPolicyCodec codec;
    private final RowPolicyPredicates predicates;
    private final SecurityClaimsExtractor claims;
    private final SecurityModeResolver securityMode;
    private final ClickHouseProperties clickHouse;
    private final ProviderRolePrivileges rolePrivileges;

    /**
     * {@code settings} with the caller's filters merged in, or unchanged when the caller is confined nowhere.
     * Called <b>last</b> on a user read, so no other setting merged afterwards can replace it.
     */
    @Transactional(readOnly = true)
    public QuerySettings apply(QuerySettings settings) {
        final Map<String, String> filters = filters();
        if (filters.isEmpty()) {
            return settings;
        }
        settings.serverSetting(SETTING, settingValue(filters));
        if (log.isDebugEnabled()) {
            log.debug("Row policies confine this read on {} table(s): {} ({} caller role(s))",
                    filters.size(), filters.keySet(), claims.getProviderRoles().size());
        }
        Span.current().setAttribute("adas.row_policy.tables", String.join(",", filters.keySet()));
        return settings;
    }

    /**
     * The caller's filters: database-qualified table name → predicate, for every table the caller is confined
     * on, in name order. Empty when security is not enforced or no policy confines the caller.
     */
    @Transactional(readOnly = true)
    public Map<String, String> filters() {
        if (!securityMode.isEnforced()) {
            return Map.of();
        }
        final Set<String> roles = claims.getProviderRoles();
        if (roles.isEmpty()) {
            return Map.of();
        }
        final String database = clickHouse.sessionDatabase();
        final boolean globalRead = claims.hasPrivilege(Privilege.TABLE_READ);
        final Map<String, String> filters = new TreeMap<>();
        for (final StoredRowPolicies stored : definitions.findStoredRowPolicies()) {
            final List<RowPolicy> confining = confining(stored, roles, globalRead);
            if (!confining.isEmpty()) {
                filters.put(database + "." + stored.getName(), predicate(stored.getName(), confining));
            }
        }
        return filters;
    }

    /**
     * Whether row policies confine the caller's reads of {@code definition} — the {@code rows_restricted} flag.
     * Shares {@link #confining} with enforcement, so the report cannot disagree with what a read carries.
     */
    public boolean isConfined(TableDefinitionEntity definition) {
        if (!securityMode.isEnforced()) {
            return false;
        }
        final Set<String> roles = claims.getProviderRoles();
        return !roles.isEmpty() && !confining(codec.read(definition.getRowPolicies()), definition.getAccess(), roles,
                claims.hasPrivilege(Privilege.TABLE_READ)).isEmpty();
    }

    /**
     * Refuses with {@code 403 row_policy_confined} when the caller is confined on any of {@code tables}: a
     * pipeline about to read them would do so with no filter, on the caller's behalf. No-op when security is
     * not enforced, and for the system identity, which has no provider roles.
     *
     * @param purpose what the caller asked for, completing "may not …" in the message
     */
    @Transactional(readOnly = true)
    public void requireUnconfined(Collection<String> tables, String purpose) {
        if (!securityMode.isEnforced()) {
            return;
        }
        final Set<String> roles = claims.getProviderRoles();
        if (roles.isEmpty()) {
            return;
        }
        final Set<String> wanted = Set.copyOf(tables);
        final boolean globalRead = claims.hasPrivilege(Privilege.TABLE_READ);
        for (final StoredRowPolicies stored : definitions.findStoredRowPolicies()) {
            if (wanted.contains(stored.getName()) && !confining(stored, roles, globalRead).isEmpty()) {
                throw new RowPolicyConfinedException("a row policy confines the caller's reads of table '"
                        + stored.getName() + "', so it may not " + purpose
                        + ": a pipeline reads its sources with no row filter");
            }
        }
    }

    /**
     * {@link #requireUnconfined} over every table a pipeline with this {@code input} and {@code target} reads:
     * the input, the target's own source when the target is an enrichment, and every enrichment of either. That
     * is a superset of what any one declaration joins, deliberately: it keeps the answer independent of a
     * declaration that may not resolve yet, and over-refusing a confined caller costs nothing a confined caller
     * was entitled to.
     */
    @Transactional(readOnly = true)
    public void requireUnconfinedPipeline(String input, String target, String purpose) {
        if (!securityMode.isEnforced() || claims.getProviderRoles().isEmpty()) {
            return;
        }
        final Set<String> reads = new LinkedHashSet<>();
        if (StringUtils.isNotBlank(input)) {
            reads.add(input.trim());
        }
        if (StringUtils.isNotBlank(target)) {
            definitions.findById(target.trim())
                    .map(TableDefinitionEntity::getSourceTable)
                    .ifPresent(reads::add);
        }
        for (final String table : List.copyOf(reads)) {
            definitions.findBySourceTable(table).forEach(enrichment -> reads.add(enrichment.getName()));
        }
        requireUnconfined(reads, purpose);
    }

    private List<RowPolicy> confining(StoredRowPolicies stored, Set<String> roles, boolean globalRead) {
        return confining(codec.read(stored.getPolicies()), codec.readAccess(stored.getAccess()), roles, globalRead);
    }

    /**
     * The policies that confine a caller carrying {@code roles} on a table with these {@code policies} and grant
     * lists: the policies of its roles, or none at all when it carries none, or when one of its roles may read
     * the table and has no policy there (see the class note).
     *
     * @param globalRead whether the caller holds {@code TABLE_READ} globally on this request
     */
    private List<RowPolicy> confining(
            Map<String, RowPolicy> policies, TableAccess access, Set<String> roles, boolean globalRead) {
        // Walked in the policies' (role-sorted) order, so the rendered predicate is stable across requests.
        final List<RowPolicy> matching = new ArrayList<>();
        policies.forEach((role, policy) -> {
            if (roles.contains(role)) {
                matching.add(policy);
            }
        });
        if (matching.isEmpty()) {
            return List.of();
        }
        for (final String role : roles) {
            if (!policies.containsKey(role) && readsTable(role, access, globalRead)) {
                return List.of();
            }
        }
        return matching;
    }

    /** Whether {@code role} by itself confers a read of the table: a {@code read} grant, or a global mapping. */
    private boolean readsTable(String role, TableAccess access, boolean globalRead) {
        return TableCallerPermissions.isGranted(access, Privilege.TABLE_READ, Set.of(role))
                || globalRead && rolePrivileges.holdsGlobally(role, Privilege.TABLE_READ);
    }

    /** The OR of the confining policies' filters, each rendered against the table's own columns. */
    private String predicate(String table, List<RowPolicy> confining) {
        if (confining.size() == 1) {
            return predicates.render(table, confining.getFirst().filter());
        }
        return confining.stream()
                .map(policy -> "(" + predicates.render(table, policy.filter()) + ")")
                .collect(Collectors.joining(" OR "));
    }

    /**
     * The setting's value: a ClickHouse {@code Map(String, String)} literal, each key and predicate a string
     * literal. The predicate is ADAS-rendered SQL whose own literals are already escaped, so this second level
     * of escaping is what keeps it one string value — a value like {@code P0') OR 1 OR ('} stays a literal.
     */
    static String settingValue(Map<String, String> filters) {
        return filters.entrySet().stream()
                .map(entry -> literal(entry.getKey()) + ":" + literal(entry.getValue()))
                .collect(Collectors.joining(",", "{", "}"));
    }

    private static String literal(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
