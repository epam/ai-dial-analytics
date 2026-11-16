package com.epam.aidial.analytics.service.table;

import com.epam.aidial.analytics.exception.AccessConflictException;
import com.epam.aidial.analytics.exception.EnrichmentFollowsSourceException;
import com.epam.aidial.analytics.exception.SystemTableException;
import com.epam.aidial.analytics.exception.TableNotFoundException;
import com.epam.aidial.analytics.exception.TableValidationException;
import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.security.SecurityClaimsExtractor;
import com.epam.aidial.analytics.service.table.grant.ColumnPolicyCodec;
import com.epam.aidial.analytics.service.table.grant.PrivilegeScope;
import com.epam.aidial.analytics.service.table.grant.RoleGenerationCodec;
import com.epam.aidial.analytics.service.table.grant.TableCallerPermissions;
import com.epam.aidial.analytics.service.table.rowpolicy.RowPolicy;
import com.epam.aidial.analytics.service.table.rowpolicy.RowPolicyCodec;
import com.epam.aidial.analytics.service.table.rowpolicy.RowPolicyConditions;
import com.epam.aidial.analytics.service.table.rowpolicy.RowPolicyService;
import com.epam.aidial.analytics.web.security.Privilege;
import com.epam.aidial.analytics.web.security.ProviderRolePrivileges;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A table's access configuration as one entry per raw provider role, {@code {privileges, rows, columns}}: a view over
 * the three per-table documents {@code access} (the grant lists), {@code row_policies} and {@code column_policies}.
 * A {@code PUT} replaces one role's whole entry in all three, a {@code DELETE} removes it from all three; a write that
 * changes the entry bumps the table's access generation and records it as the role's last-change mark, under the
 * definition's row lock. A write conflicts only when its own role changed after the generation it carries (see
 * {@code openspec/specs/table-access-control/spec.md}, "Access configuration generation").
 *
 * <p>Every rule here constrains what may be stored, so it applies in {@code none} mode too. Authorization sits at the
 * web layer ({@code TABLE_GRANT} on every method).
 */
@Service
@RequiredArgsConstructor
public class TableAccessService {

    private static final AccessEntry EMPTY = new AccessEntry(List.of(), null, null);

    private static final Set<Privilege> READ_SIDE_GRANTS = EnumSet.of(Privilege.TABLE_GET, Privilege.TABLE_READ, Privilege.TABLE_SENSITIVE);

    private final TableDefinitionRepository definitions;
    private final ColumnMappingRepository columns;
    private final ProviderRolePrivileges providerRolePrivileges;
    private final RowPolicyCodec rowPolicyCodec;
    private final ColumnPolicyCodec columnPolicyCodec;
    private final RowPolicyService rowPolicies;
    private final RowPolicyConditions conditions;
    private final RoleGenerationCodec roleGenerations;

    /** Why a privilege in an entry changes nothing. */
    public enum IneffectiveReason {

        /** The role already holds the privilege over every table, so the grant adds nothing. */
        ALREADY_GLOBAL("already_global"),

        /** A clearance for a role holding no verb it could extend on the table — it confers nothing alone. */
        SENSITIVE_WITHOUT_VERB("sensitive_without_verb"),

        /** A read for {@code *} with no row policy on a table where other roles carry one: it lifts them all. */
        LIFTS_ROW_POLICIES("lifts_row_policies"),

        /** A read for {@code *} with neither rows nor columns on a table where other roles carry columns: it lifts them all. */
        LIFTS_COLUMN_POLICIES("lifts_column_policies");

        private final String code;

        IneffectiveReason(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** One privilege that was accepted but changes nothing. */
    public record IneffectiveGrant(String role, Privilege privilege, IneffectiveReason reason, String message) {}

    /**
     * One role's entry: privileges in canonical order, its row policy or {@code null}, and its column allowlist (sorted)
     * or {@code null}.
     */
    public record AccessEntry(List<Privilege> privileges, RowPolicy rows, List<String> columns) {

        /** Whether the entry carries nothing, and so is not stored. */
        public boolean isEmpty() {
            return privileges.isEmpty() && rows == null && columns == null;
        }
    }

    /** Every role's entry on a table, sorted by role, and the table's access generation. */
    public record AccessEntries(long generation, Map<String, AccessEntry> roles) {}

    /** One role's stored entry and the table's access generation. */
    public record RoleEntry(long generation, String role, AccessEntry entry) {}

    /** A completed {@code PUT}: the entry as stored, the new generation, and any privilege that changes nothing. */
    public record EntryReplacement(long generation, String role, AccessEntry entry, List<IneffectiveGrant> ineffectiveGrants) {}

    /**
     * A {@code PUT} body as bound. {@code generation} is whatever JSON the client sent (judged here, not at binding);
     * {@code rows} is {@code null} when absent or {@code null}.
     */
    public record EntryRequest(JsonNode generation, List<String> privileges, RowsRequest rows, List<String> columns) {}

    /** A {@code rows} member as sent: one of {@code filter} and {@code where}. */
    public record RowsRequest(FilterNode filter, String where) {}

    /** Every role's entry on {@code table}. */
    @Transactional(readOnly = true)
    public AccessEntries list(String table) {
        final TableDefinitionEntity definition = require(table);
        return new AccessEntries(definition.getAccessGeneration(), entries(definition));
    }

    /**
     * {@code role}'s entry on {@code table}, the role trimmed.
     *
     * @throws TableNotFoundException when the table does not exist or the role has no entry on it (404)
     */
    @Transactional(readOnly = true)
    public RoleEntry get(String table, String role) {
        final TableDefinitionEntity definition = require(table);
        final String key = StringUtils.trimToEmpty(role);
        final AccessEntry entry = entries(definition).get(key);
        if (entry == null) {
            throw noEntry(table, key);
        }
        return new RoleEntry(definition.getAccessGeneration(), key, entry);
    }

    /**
     * Replaces {@code role}'s whole entry on {@code table}, checked in the order the spec lists under the row lock. A
     * request that would store exactly the entry the role has answers with it and writes nothing, whatever generation it
     * carries, so a retry is safe. Otherwise the generation must not be above the table's nor older than the role's last
     * change ({@code 409 access_conflict}); the entry is then written, the generation bumped and the role marked.
     */
    @Transactional
    public EntryReplacement put(String table, String role, EntryRequest request) {
        final TableDefinitionEntity definition = lock(table);
        requireNoReadSideOnEnrichment(definition, request);
        final long generation = requireGeneration(request.generation());
        final String key = StringUtils.trimToEmpty(role);
        // The remaining checks run now, so the unchanged case compares a validated entry, but a refusal is reported only
        // after the conflict check, which the spec orders first.
        AccessEntry entry = null;
        RuntimeException refusal = null;
        try {
            entry = validated(definition, role, request);
        } catch (TableValidationException | SystemTableException ex) {
            refusal = ex;
        }
        if (entry != null && entry.equals(entries(definition).getOrDefault(key, EMPTY))) {
            return new EntryReplacement(definition.getAccessGeneration(), key, entry, reportIneffective(definition, key, entry));
        }
        requireNoConflict(definition, key, generation);
        if (refusal != null) {
            throw refusal;
        }
        write(definition, key, entry);
        bump(definition, key);
        return new EntryReplacement(definition.getAccessGeneration(), key, entry, reportIneffective(definition, key, entry));
    }

    /**
     * Removes {@code role}'s entry on {@code table}, bumps the generation and marks the role. With a {@code generation}
     * the removal is conditional on it as a {@code PUT} is; without one it is not.
     *
     * @throws TableNotFoundException when the table does not exist or the role has no entry on it (404)
     */
    @Transactional
    public void delete(String table, String role, String generation) {
        final TableDefinitionEntity definition = lock(table);
        final Long expected = generation == null ? null : requireGeneration(generation);
        final String key = StringUtils.trimToEmpty(role);
        if (!entries(definition).containsKey(key)) {
            throw noEntry(table, key);
        }
        if (expected != null) {
            requireNoConflict(definition, key, expected);
        }
        write(definition, key, EMPTY);
        bump(definition, key);
    }

    /** The entry a {@code PUT} would store: the role, then the system refusal, then the remaining body checks. */
    private AccessEntry validated(TableDefinitionEntity definition, String role, EntryRequest request) {
        final String table = definition.getName();
        final String grantee = RowPolicyService.requireRole(role);
        final List<String> sentPrivileges = request.privileges() == null ? List.of() : request.privileges();
        if (definition.isSystem() && sentPrivileges.stream().map(StringUtils::trimToEmpty).anyMatch(key -> key.equals("write") || key.equals("modify"))) {
            throw new SystemTableException("table '" + table + "' is system-owned; an entry on it may not carry 'write' or "
                    + "'modify', because every modifying operation on it is refused regardless of any grant");
        }
        final List<Privilege> privileges = privileges(grantee, sentPrivileges);
        final RowPolicy rows = rows(table, grantee, request.rows());
        final List<String> columnPolicy = columnPolicy(definition, grantee, request.columns());
        return new AccessEntry(privileges, rows, columnPolicy);
    }

    /** Moves the table's generation on by one and records it as {@code role}'s last change. */
    private void bump(TableDefinitionEntity definition, String role) {
        definition.setAccessGeneration(definition.getAccessGeneration() + 1);
        definition.setAccessRoleGenerations(roleGenerations.mark(definition.getAccessRoleGenerations(), List.of(role),
                definition.getAccessGeneration()));
    }

    /** Every role's entry assembled from the three documents, sorted by role. */
    public Map<String, AccessEntry> entries(TableAccess access, String rowPoliciesJson, String columnPoliciesJson) {
        final Map<String, RowPolicy> policies = rowPolicyCodec.read(rowPoliciesJson);
        final Map<String, List<String>> columnPolicies = columnPolicyCodec.read(columnPoliciesJson);
        final Set<String> roles = new TreeSet<>(policies.keySet());
        roles.addAll(columnPolicies.keySet());
        if (access != null) {
            PrivilegeScope.GRANTABLE.forEach(privilege -> roles.addAll(access.rolesFor(privilege)));
        }
        final Map<String, AccessEntry> entries = new LinkedHashMap<>();
        for (final String role : roles) {
            entries.put(role, new AccessEntry(storedPrivileges(access, role), policies.get(role), columnPolicies.get(role)));
        }
        return entries;
    }

    private Map<String, AccessEntry> entries(TableDefinitionEntity definition) {
        return entries(definition.getAccess(), definition.getRowPolicies(), definition.getColumnPolicies());
    }

    private static List<Privilege> storedPrivileges(TableAccess access, String role) {
        final List<Privilege> held = new ArrayList<>();
        if (access != null) {
            for (final Privilege privilege : PrivilegeScope.GRANTABLE) {
                if (access.rolesFor(privilege).contains(role)) {
                    held.add(privilege);
                }
            }
        }
        return held;
    }

    /**
     * Writes {@code entry} for {@code role} into each of the three documents whose decoded value for the role changes.
     * An empty entry removes the role from all three.
     */
    private void write(TableDefinitionEntity definition, String role, AccessEntry entry) {
        final TableAccess stored = definition.getAccess() == null ? new TableAccess() : definition.getAccess();
        if (!Set.copyOf(storedPrivileges(stored, role)).equals(Set.copyOf(entry.privileges()))) {
            final TableAccess updated = new TableAccess();
            for (final Privilege privilege : PrivilegeScope.GRANTABLE) {
                final List<String> list = new ArrayList<>(stored.rolesFor(privilege));
                list.removeIf(role::equals);
                if (entry.privileges().contains(privilege)) {
                    list.add(role);
                }
                updated.setRolesFor(privilege, list);
            }
            definition.setAccess(updated);
        }
        final Map<String, RowPolicy> policies = new TreeMap<>(rowPolicyCodec.read(definition.getRowPolicies()));
        if (!Objects.equals(policies.get(role), entry.rows())) {
            if (entry.rows() == null) {
                policies.remove(role);
            } else {
                policies.put(role, entry.rows());
            }
            definition.setRowPolicies(rowPolicyCodec.write(policies));
        }
        final Map<String, List<String>> columnPolicies = new TreeMap<>(columnPolicyCodec.read(definition.getColumnPolicies()));
        if (!Objects.equals(columnPolicies.get(role), entry.columns())) {
            if (entry.columns() == null) {
                columnPolicies.remove(role);
            } else {
                columnPolicies.put(role, entry.columns());
            }
            definition.setColumnPolicies(columnPolicyCodec.write(columnPolicies));
        }
    }

    /**
     * Refuses, on the body as sent, a read-side privilege or a non-null {@code rows} or {@code columns} on an
     * enrichment, whose read side, rows and columns are its source's.
     */
    private static void requireNoReadSideOnEnrichment(TableDefinitionEntity definition, EntryRequest request) {
        if (definition.getType() != TableType.ENRICHMENT) {
            return;
        }
        final List<String> sent = request.privileges() == null ? List.of() : request.privileges();
        final boolean readSide = sent.stream().map(StringUtils::trimToEmpty)
                .anyMatch(key -> READ_SIDE_GRANTS.stream().anyMatch(privilege -> PrivilegeScope.grantKey(privilege).equals(key)));
        final String what = readSide ? "a get, read or sensitive grant" : request.rows() != null ? "a row policy"
                : request.columns() != null ? "a column policy" : null;
        if (what != null) {
            throw new EnrichmentFollowsSourceException("table '" + definition.getName() + "' is an enrichment; its read-side "
                    + "access, rows and columns are its source's, so its entry takes no " + what + ". Configure its source '"
                    + definition.getSourceTable() + "' instead");
        }
    }

    /** A {@code PUT}'s generation as a JSON integer within a signed 64-bit range ({@code 422} otherwise). */
    private static long requireGeneration(JsonNode generation) {
        if (generation == null || generation.isNull()) {
            throw new TableValidationException("'generation' is required: send the generation of the access you read");
        }
        if (!generation.isIntegralNumber() || !generation.canConvertToLong()) {
            throw new TableValidationException("'generation' must be an integer");
        }
        return generation.longValue();
    }

    /** A {@code DELETE}'s optional {@code generation} query value as a signed 64-bit integer ({@code 422} otherwise). */
    private static long requireGeneration(String generation) {
        try {
            return Long.parseLong(generation);
        } catch (NumberFormatException ex) {
            throw new TableValidationException("'generation' must be an integer");
        }
    }

    /**
     * Refuses {@code 409 access_conflict} a write carrying a generation the caller cannot have read (above the table's)
     * or one older than {@code role}'s last change. A change to another role since the read does not conflict.
     */
    private void requireNoConflict(TableDefinitionEntity definition, String role, long generation) {
        if (generation > definition.getAccessGeneration()
                || roleGenerations.markOf(definition.getAccessRoleGenerations(), role) > generation) {
            // No generation is named, neither the current nor the sent one: the caller must re-read and reapply.
            throw new AccessConflictException("the access entry of role '" + role + "' on table '" + definition.getName()
                    + "' has changed since you read it; read it again and reapply your change");
        }
    }

    /** The sent privileges as grantable privileges, de-duplicated in canonical order; an unknown one is {@code 422}. */
    private static List<Privilege> privileges(String role, List<String> sent) {
        final Set<Privilege> held = EnumSet.noneOf(Privilege.class);
        for (final String value : sent) {
            final String key = StringUtils.trimToEmpty(value);
            final Privilege privilege = PrivilegeScope.GRANTABLE.stream()
                    .filter(candidate -> PrivilegeScope.grantKey(candidate).equals(key))
                    .findFirst()
                    .orElseThrow(() -> new TableValidationException("'" + value + "' is not a grantable privilege; an entry's "
                            + "privileges are get, read, sensitive, write and modify"));
            if (SecurityClaimsExtractor.ANYONE.equals(role) && PrivilegeScope.isModifying(privilege)) {
                throw new TableValidationException("'" + SecurityClaimsExtractor.ANYONE + "' stands for every authenticated "
                        + "caller and may carry only get, read and sensitive, not '" + key + "'");
            }
            held.add(privilege);
        }
        return PrivilegeScope.GRANTABLE.stream().filter(held::contains).toList();
    }

    /** The row policy as it will be stored, or {@code null}; translated from {@code where} and validated. */
    private RowPolicy rows(String table, String role, RowsRequest rows) {
        if (rows == null) {
            return null;
        }
        if ((rows.filter() == null) == (rows.where() == null)) {
            throw new TableValidationException("'rows' takes exactly one of 'filter' and 'where'; to let role '" + role
                    + "' read every row, send 'rows': null");
        }
        final FilterNode filter = rows.where() == null ? rows.filter() : conditions.toFilter(table, rows.where());
        return rowPolicies.validate(table, role, new RowPolicy(filter));
    }

    /**
     * The column policy as it will be stored, or {@code null}: a non-empty list of the exposed names of the table's
     * catalog columns and of {@code <enrichment>.<column>} for its enrichments' columns, matched case-sensitively,
     * de-duplicated and sorted. Validated against the stored columns, whatever the writing caller may see.
     */
    private List<String> columnPolicy(TableDefinitionEntity definition, String role, List<String> names) {
        if (names == null) {
            return null;
        }
        if (names.isEmpty()) {
            throw new TableValidationException("'columns' must not be empty: a role meant to see no column should not "
                    + "read the table; send 'columns': null for every column");
        }
        final Set<String> stored = storedColumnNames(definition.getName());
        for (final String name : names) {
            if (StringUtils.isBlank(name) || !stored.contains(name)) {
                throw new TableValidationException("the column policy of role '" + role + "' names '" + name + "', which is "
                        + "not a column of table '" + definition.getName() + "' or of one of its enrichments (named "
                        + "'<enrichment>.<column>')");
            }
        }
        return ColumnPolicyCodec.canonical(names);
    }

    /** Every name a column policy on {@code table} may list: its own columns' exposed names and its enrichments' qualified ones. */
    private Set<String> storedColumnNames(String table) {
        final Set<String> names = new HashSet<>();
        columns.findByTableNameOrderByOrdinal(table).forEach(column -> names.add(column.getName()));
        for (final TableDefinitionEntity enrichment : definitions.findBySourceTable(table)) {
            columns.findByTableNameOrderByOrdinal(enrichment.getName())
                    .forEach(column -> names.add(enrichment.getName() + "." + column.getName()));
        }
        return names;
    }

    /**
     * Reports, without failing, each privilege of the stored entry that changes nothing. Refusing would block a
     * legitimate intermediate state; saying nothing would leave the operator to discover it from a query result.
     */
    private List<IneffectiveGrant> reportIneffective(TableDefinitionEntity definition, String role, AccessEntry entry) {
        final List<IneffectiveGrant> report = new ArrayList<>();
        final Set<String> policyRoles = rowPolicyCodec.read(definition.getRowPolicies()).keySet();
        final Set<String> columnRoles = columnPolicyCodec.read(definition.getColumnPolicies()).keySet();
        for (final Privilege privilege : entry.privileges()) {
            final boolean everyoneReads = privilege == Privilege.TABLE_READ && SecurityClaimsExtractor.ANYONE.equals(role) && entry.rows() == null;
            if (everyoneReads && !policyRoles.isEmpty()) {
                report.add(new IneffectiveGrant(role, privilege, IneffectiveReason.LIFTS_ROW_POLICIES,
                        "table '" + definition.getName() + "' has row policies for " + policyRoles + " and none for '"
                                + role + "'; every caller carries '" + role + "', which now reads with no policy, so "
                                + "those policies confine no one. Give '" + role + "' a row policy to keep them"));
            }
            if (everyoneReads && entry.columns() == null && !columnRoles.isEmpty()) {
                report.add(new IneffectiveGrant(role, privilege, IneffectiveReason.LIFTS_COLUMN_POLICIES,
                        "table '" + definition.getName() + "' has column policies for " + columnRoles + "; every caller "
                                + "carries '" + role + "', which now reads with neither a row nor a column restriction, so "
                                + "those column policies hide nothing. Give '" + role + "' rows or columns to keep them"));
            }
            if (everyoneReads && (!policyRoles.isEmpty() || entry.columns() == null && !columnRoles.isEmpty())) {
                continue;
            }
            if (providerRolePrivileges.holdsGlobally(role, privilege)) {
                report.add(new IneffectiveGrant(role, privilege, IneffectiveReason.ALREADY_GLOBAL,
                        "role '" + role + "' already holds " + privilege + " over every table, so this grant "
                                + "changes nothing for it"));
            } else if (privilege == Privilege.TABLE_SENSITIVE && !holdsAnyVerb(role, definition)) {
                report.add(new IneffectiveGrant(role, privilege, IneffectiveReason.SENSITIVE_WITHOUT_VERB,
                        "role '" + role + "' holds no get, read or modify on table '" + definition.getName()
                                + "', so the sensitive clearance changes nothing for it until one is granted"));
            }
        }
        return List.copyOf(report);
    }

    /**
     * Whether {@code role} holds a verb the clearance extends on {@code definition}, globally or by an entry, or
     * {@code modify} on any of its enrichments, whose sensitive columns this clearance governs.
     */
    private boolean holdsAnyVerb(String role, TableDefinitionEntity definition) {
        for (final Privilege verb : List.of(Privilege.TABLE_GET, Privilege.TABLE_READ, Privilege.TABLE_MODIFY)) {
            if (providerRolePrivileges.holdsGlobally(role, verb)) {
                return true;
            }
        }
        final Set<String> carried = SecurityClaimsExtractor.carriedWith(role);
        return TableCallerPermissions.isGranted(definition.getAccess(), Privilege.TABLE_GET, carried)
                || definitions.findBySourceTable(definition.getName()).stream()
                        .anyMatch(enrichment -> TableCallerPermissions.isGranted(enrichment.getAccess(), Privilege.TABLE_MODIFY, carried));
    }

    private TableDefinitionEntity require(String table) {
        return definitions.findById(table).orElseThrow(() -> new TableNotFoundException("table '" + table + "' not found"));
    }

    private TableDefinitionEntity lock(String table) {
        return definitions.findForUpdate(table).orElseThrow(() -> new TableNotFoundException("table '" + table + "' not found"));
    }

    private static TableNotFoundException noEntry(String table, String role) {
        return new TableNotFoundException("table '" + table + "' has no access entry for role '" + role + "'");
    }
}
