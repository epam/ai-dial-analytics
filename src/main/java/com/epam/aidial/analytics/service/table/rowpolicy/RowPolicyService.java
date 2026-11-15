package com.epam.aidial.analytics.service.table.rowpolicy;

import com.epam.aidial.analytics.exception.ColumnInRowPolicyException;
import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.exception.TableValidationException;
import com.epam.aidial.analytics.service.query.RowPolicyPredicates;
import com.epam.aidial.analytics.service.query.translate.StructuredQueryBuilder;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.web.security.Privilege;
import com.epam.aidial.analytics.web.security.RoleBundle;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Validates a table's row policies (the {@code row_policies} column of {@link TableDefinitionEntity}, one per raw
 * provider role, each a filter) and guards the columns they reference. The access API
 * ({@code TableAccessService}) writes them as the {@code rows} of a role's entry.
 *
 * <p>Validation runs in every security mode. A policy is not an authorization check but stored state, and a
 * document written while security was off goes live the moment the deployment switches to {@code oidc},
 * when refusing it would be too late.
 */
@Service
@RequiredArgsConstructor
public class RowPolicyService {

    private final RowPolicyCodec codec;
    private final RowPolicyPredicates predicates;

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
     * Refuses, with {@code 409 column_in_row_policy}, a schema of {@code definition} that a stored policy no
     * longer validates against. Called after the new columns are written and inside the same transaction, so
     * a refusal rolls them back.
     */
    public void requireStillValid(TableDefinitionEntity definition) {
        codec.read(definition.getRowPolicies()).forEach((role, policy) -> {
            try {
                predicates.render(definition.getName(), policy.filter());
            } catch (QueryValidationException ex) {
                throw new ColumnInRowPolicyException("the schema of table '" + definition.getName() + "' does not fit "
                        + "the row policy of role '" + role + "': " + ex.getMessage()
                        + "; change or remove that policy first");
            }
        });
    }

    /**
     * The policy as it will be stored: a filter that validates and renders against the table's own columns
     * within the policy scope. There is no unrestricted shape: a role that may read the table and should see
     * every row simply has no policy.
     *
     * @throws TableValidationException if the policy is invalid (422)
     */
    public RowPolicy validate(String table, String role, RowPolicy policy) {
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

    /**
     * A raw provider role name as an access entry's key: trimmed, non-blank, and not a bundle or privilege name.
     *
     * @throws TableValidationException otherwise (422)
     */
    public static String requireRole(String role) {
        if (StringUtils.isBlank(role)) {
            throw new TableValidationException("an access role must be non-blank");
        }
        final String trimmed = role.trim();
        if (RoleBundle.byName(trimmed) != null || isPrivilegeName(trimmed)) {
            throw new TableValidationException("'" + trimmed + "' is a bundle or privilege name, not a provider "
                    + "role; an access entry names a raw provider role");
        }
        return trimmed;
    }

    private static boolean isPrivilegeName(String name) {
        final List<String> names = Arrays.stream(Privilege.values()).map(Enum::name).toList();
        return names.contains(name);
    }
}
