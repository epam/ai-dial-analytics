package com.epam.aidial.analytics.service.table.rowpolicy;

import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.exception.TableNotFoundException;
import com.epam.aidial.analytics.exception.TableValidationException;
import com.epam.aidial.analytics.service.query.RowPolicyPredicates;
import com.epam.aidial.analytics.service.query.translate.StructuredQueryBuilder;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import com.epam.aidial.analytics.web.security.Privilege;
import com.epam.aidial.analytics.web.security.RoleBundle;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Manages a table's row policies (the {@code row_policies} column of {@link TableDefinitionEntity}): one
 * per raw provider role, each a filter.
 *
 * <p>Validation runs in every security mode. A policy is not an authorization check but stored state, and a
 * document written while security was off goes live the moment the deployment switches to {@code oidc},
 * when refusing it would be too late. Authorization sits at the web layer ({@code TABLE_GRANT} on every
 * method); this service carries none.
 */
@Service
@RequiredArgsConstructor
public class RowPolicyService {

    private final TableDefinitionRepository definitions;
    private final RowPolicyCodec codec;
    private final RowPolicyPredicates predicates;

    /** The table's policies keyed by role, in role order. */
    @Transactional(readOnly = true)
    public Map<String, RowPolicy> list(String table) {
        return codec.read(require(table).getRowPolicies());
    }

    /**
     * Creates or replaces {@code role}'s policy on {@code table} and returns it as stored.
     *
     * @throws TableNotFoundException   if the table does not exist (404)
     * @throws TableValidationException if the role or the policy is invalid (422)
     */
    @Transactional
    public RowPolicy put(String table, String role, RowPolicy policy) {
        final TableDefinitionEntity definition = require(table);
        final String grantee = requireRole(role);
        final RowPolicy stored = validated(table, grantee, policy);
        final Map<String, RowPolicy> policies = new TreeMap<>(codec.read(definition.getRowPolicies()));
        // The column is text on JSONB, which Postgres re-serialises, so the stored text never equals the
        // codec's: writing it back would always dirty the entity and record an empty revision. Compare the
        // decoded policy instead, so repeating a PUT changes nothing, as the API promises.
        if (stored.equals(policies.get(grantee))) {
            return stored;
        }
        policies.put(grantee, stored);
        definition.setRowPolicies(codec.write(policies));
        definitions.save(definition);
        return stored;
    }

    /**
     * Removes {@code role}'s policy on {@code table}.
     *
     * @throws TableNotFoundException if the table does not exist, or the role has no policy on it (404)
     */
    @Transactional
    public void delete(String table, String role) {
        final TableDefinitionEntity definition = require(table);
        final String grantee = StringUtils.trimToEmpty(role);
        final Map<String, RowPolicy> policies = new TreeMap<>(codec.read(definition.getRowPolicies()));
        if (policies.remove(grantee) == null) {
            throw new TableNotFoundException("table '" + table + "' has no row policy for role '" + grantee + "'");
        }
        definition.setRowPolicies(codec.write(policies));
        definitions.save(definition);
    }

    /**
     * For each column a filter policy on {@code definition} references, the roles whose policies reference it,
     * keyed by the column's exposed name. Read by the schema guard that refuses dropping or renaming one.
     */
    public Map<String, Set<String>> referencedColumns(TableDefinitionEntity definition) {
        final Map<String, Set<String>> byColumn = new LinkedHashMap<>();
        codec.read(definition.getRowPolicies()).forEach((role, policy) -> {
            if (policy.filter() != null) {
                for (final String field : StructuredQueryBuilder.referencedFields(policy.filter())) {
                    byColumn.computeIfAbsent(field, ignored -> new TreeSet<>()).add(role);
                }
            }
        });
        return byColumn;
    }

    /**
     * The policy as it will be stored: a filter that validates and renders against the table's own columns
     * within the policy scope. There is no unrestricted shape: a role that may read the table and should see
     * every row simply has no policy.
     */
    private RowPolicy validated(String table, String role, RowPolicy policy) {
        if (policy == null || policy.filter() == null) {
            throw new TableValidationException("a row policy requires a 'filter'; to let role '" + role
                    + "' read every row, give it no policy");
        }
        try {
            predicates.render(table, policy.filter());
        } catch (QueryValidationException ex) {
            throw new TableValidationException("row policy for role '" + role + "': 'filter' " + ex.getMessage());
        }
        return new RowPolicy(policy.filter());
    }

    /** A raw provider role name: trimmed, non-blank, and not a bundle or privilege name. */
    private static String requireRole(String role) {
        if (StringUtils.isBlank(role)) {
            throw new TableValidationException("a row policy role must be non-blank");
        }
        final String trimmed = role.trim();
        if (RoleBundle.byName(trimmed) != null || isPrivilegeName(trimmed)) {
            throw new TableValidationException("'" + trimmed + "' is a bundle or privilege name, not a provider "
                    + "role; a row policy names a raw provider role, as a grant list does");
        }
        return trimmed;
    }

    private static boolean isPrivilegeName(String name) {
        final List<String> names = Arrays.stream(Privilege.values()).map(Enum::name).toList();
        return names.contains(name);
    }

    private TableDefinitionEntity require(String table) {
        return definitions.findById(table)
                .orElseThrow(() -> new TableNotFoundException("table '" + table + "' not found"));
    }
}
