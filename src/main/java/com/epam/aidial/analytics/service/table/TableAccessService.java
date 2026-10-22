package com.epam.aidial.analytics.service.table;

import com.epam.aidial.analytics.exception.SystemTableException;
import com.epam.aidial.analytics.exception.TableNotFoundException;
import com.epam.aidial.analytics.exception.TableValidationException;
import com.epam.aidial.analytics.service.table.grant.EffectivePermissions;
import com.epam.aidial.analytics.service.table.grant.PrivilegeScope;
import com.epam.aidial.analytics.service.table.grant.SensitiveGrantInvariant;
import com.epam.aidial.analytics.service.table.grant.TableCallerPermissions;
import com.epam.aidial.analytics.web.security.Privilege;
import com.epam.aidial.analytics.web.security.ProviderRolePrivileges;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Manages a table's grant lists (the JSONB {@code access} column of {@link TableDefinitionEntity}).
 *
 * <p>A replacement is a <b>merge</b>: a key present in the request replaces that list, an absent key
 * leaves the stored list alone, and an explicit empty array revokes it. That is what lets the document
 * grow from two keys to five without turning an un-updated two-key client into one that silently revokes
 * every read grant on each call.
 *
 * <p>A {@code system} table is restricted <b>by key, not by object</b>. Read-side keys are storable on one
 * like any other table; {@code write} and {@code modify} are refused with {@code 409 table_is_system} only
 * when <b>non-empty</b>, so a five-key body carrying empty write/modify succeeds. Refusing every
 * replacement — as this did — was wrong on the guard's own terms: the {@code system} flag's own migration
 * says such a table "stays fully readable and queryable; only the modifying endpoints" are refused, and a
 * blanket refusal made per-table read grants inapplicable to {@code dial_usage_log}, the primary
 * analytics table and the motivating case for having them.
 *
 * <p>The target table must exist (404 otherwise) and role names must be non-blank (422). Authorization
 * sits at the web layer ({@code TABLE_GRANT} on both the read and the write); this service carries none.
 */
@Service
@RequiredArgsConstructor
public class TableAccessService {

    private final TableDefinitionRepository definitions;
    private final TableCallerPermissions callerPermissions;
    private final ProviderRolePrivileges providerRolePrivileges;
    private final SensitiveGrantInvariant sensitiveGrants;

    /** Why a grant in the request changes nothing. */
    public enum IneffectiveReason {

        /** The role already holds the privilege over every table, so the grant adds nothing. */
        ALREADY_GLOBAL("already_global"),

        /** A read grant on an enrichment, which is not a queryable entity — no statement reaches its rows. */
        ENRICHMENT_READ_INERT("enrichment_read_inert"),

        /** A clearance for a role holding no verb it could extend on the table — it confers nothing alone. */
        SENSITIVE_WITHOUT_VERB("sensitive_without_verb");

        private final String code;

        IneffectiveReason(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** One grant that was accepted but changes nothing. */
    public record IneffectiveGrant(String role, Privilege privilege, IneffectiveReason reason, String message) {}

    /** A completed merge: the stored lists, the caller's effective permissions, and any inert grant. */
    public record AccessReplacement(
            TableAccess access, EffectivePermissions permissions, List<IneffectiveGrant> ineffectiveGrants) {}

    @Transactional(readOnly = true)
    public TableAccess getAccess(String tableName) {
        return require(tableName).getAccess();
    }

    /**
     * Merges {@code requested} into the table's stored grant lists. A {@code null} value for a privilege
     * means the key was absent from the request and its stored list is kept.
     */
    @Transactional
    public AccessReplacement replaceAccess(String tableName, Map<Privilege, List<String>> requested) {
        final TableDefinitionEntity definition = require(tableName);
        final TableAccess stored = definition.getAccess() == null ? new TableAccess() : definition.getAccess();
        final TableAccess merged = new TableAccess();
        for (final Privilege privilege : PrivilegeScope.GRANTABLE) {
            final List<String> requestedRoles = requested.get(privilege);
            final List<String> storedRoles = stored.rolesFor(privilege);
            final List<String> normalized = requestedRoles == null ? null : normalize(requestedRoles);
            // A list whose role set is unchanged keeps its stored order, so a save that changes one list does
            // not also show a reorder of another as a difference.
            final List<String> effective = normalized == null || Set.copyOf(normalized).equals(Set.copyOf(storedRoles))
                    ? new ArrayList<>(storedRoles)
                    : normalized;
            if (definition.isSystem() && PrivilegeScope.isModifying(privilege) && !effective.isEmpty()) {
                throw new SystemTableException("table '" + tableName + "' is system-owned; its '"
                        + PrivilegeScope.grantKey(privilege) + "' list must be empty because every modifying "
                        + "operation on it is refused regardless of any grant");
            }
            merged.setRolesFor(privilege, effective);
        }
        // Checked against the merged document BEFORE it is applied, deliberately. Applying it first
        // dirties a managed, @Audited entity, and the invariant's own query then force-flushes that UPDATE —
        // opening an Envers revision — before the rule has decided. The transaction rollback covers it
        // today, but a refused grant must not depend on that: validate, then mutate.
        sensitiveGrants.requireNotWidened(definition, merged);
        // A list's order carries no meaning, but TableAccess equality — what the dirty check compares — is
        // order-sensitive, so the same roles in another order would record an Update whose snapshots differ
        // in nothing anyone can act on. Grants that change no role set are therefore not written at all.
        if (!sameRoleSets(stored, merged)) {
            definition.setAccess(merged);
        }
        final TableDefinitionEntity saved = definitions.save(definition);
        return new AccessReplacement(
                saved.getAccess() == null ? stored : saved.getAccess(),
                callerPermissions.resolve(saved),
                reportIneffective(saved, requested));
    }

    /**
     * Reports, without failing, each grant in the request that changes nothing: a role that already holds
     * the privilege globally, a read grant on an enrichment whose source the same role cannot read, and a
     * {@code sensitive} clearance for a role holding no verb on the table for it to extend.
     *
     * <p>All are reported rather than refused because all are legitimate states to pass through — an
     * operator granting a whole team by hand before narrowing its provider-role mapping, granting the
     * enrichment ahead of the source, or granting the clearance ahead of the verb. Refusing would block that; saying nothing would leave the operator to
     * discover it from an empty query result.
     */
    private List<IneffectiveGrant> reportIneffective(
            TableDefinitionEntity definition, Map<Privilege, List<String>> requested) {
        final List<IneffectiveGrant> report = new ArrayList<>();
        final boolean enrichment = definition.getType() == TableType.ENRICHMENT;
        final TableDefinitionEntity source = enrichment && definition.getSourceTable() != null
                ? definitions.findById(definition.getSourceTable()).orElse(null)
                : null;
        requested.forEach((privilege, roles) -> {
            if (roles == null) {
                return;
            }
            for (final String role : normalize(roles)) {
                if (providerRolePrivileges.holdsGlobally(role, privilege)) {
                    report.add(new IneffectiveGrant(role, privilege, IneffectiveReason.ALREADY_GLOBAL,
                            "role '" + role + "' already holds " + privilege + " over every table, so this grant "
                                    + "changes nothing for it"));
                } else if (enrichment && privilege == Privilege.TABLE_READ && !canReadSource(role, source)) {
                    report.add(new IneffectiveGrant(role, privilege, IneffectiveReason.ENRICHMENT_READ_INERT,
                            "table '" + definition.getName() + "' is an enrichment and is not a queryable entity on "
                                    + "its own; role '" + role + "' cannot read its source '"
                                    + definition.getSourceTable() + "', so no statement can reach these rows"));
                } else if (privilege == Privilege.TABLE_SENSITIVE && !holdsAnyVerb(role, definition)) {
                    report.add(new IneffectiveGrant(role, privilege, IneffectiveReason.SENSITIVE_WITHOUT_VERB,
                            "role '" + role + "' holds no get, read or modify on table '" + definition.getName()
                                    + "', so the sensitive clearance changes nothing for it until one is granted"));
                }
            }
        });
        return List.copyOf(report);
    }

    /** Whether {@code role} could read {@code source}, globally or by a grant on it. */
    private boolean canReadSource(String role, TableDefinitionEntity source) {
        if (providerRolePrivileges.holdsGlobally(role, Privilege.TABLE_READ)) {
            return true;
        }
        return source != null
                && TableCallerPermissions.isGranted(source.getAccess(), Privilege.TABLE_READ, Set.of(role));
    }

    /**
     * Whether {@code role} holds a verb the clearance extends on {@code definition}, globally or by a list.
     * {@code TABLE_GET} is implied by both {@code READ} and {@code MODIFY}, so one grant-side check covers
     * all three lists; the global side is checked per verb because a mapping names what it names.
     */
    private boolean holdsAnyVerb(String role, TableDefinitionEntity definition) {
        for (final Privilege verb : List.of(Privilege.TABLE_GET, Privilege.TABLE_READ, Privilege.TABLE_MODIFY)) {
            if (providerRolePrivileges.holdsGlobally(role, verb)) {
                return true;
            }
        }
        return TableCallerPermissions.isGranted(definition.getAccess(), Privilege.TABLE_GET, Set.of(role));
    }

    private TableDefinitionEntity require(String tableName) {
        return definitions.findById(tableName)
                .orElseThrow(() -> new TableNotFoundException("table '" + tableName + "' not found"));
    }

    /** The requested lists keyed by privilege, with an absent key left out so the merge can keep it. */
    public static Map<Privilege, List<String>> requestedLists(
            List<String> get, List<String> read,
            List<String> sensitive, List<String> write, List<String> modify) {
        final Map<Privilege, List<String>> requested = new EnumMap<>(Privilege.class);
        putIfPresent(requested, Privilege.TABLE_GET, get);
        putIfPresent(requested, Privilege.TABLE_READ, read);
        putIfPresent(requested, Privilege.TABLE_SENSITIVE, sensitive);
        putIfPresent(requested, Privilege.TABLE_WRITE, write);
        putIfPresent(requested, Privilege.TABLE_MODIFY, modify);
        return requested;
    }

    private static void putIfPresent(Map<Privilege, List<String>> into, Privilege privilege, List<String> roles) {
        if (roles != null) {
            into.put(privilege, roles);
        }
    }

    private static boolean sameRoleSets(TableAccess stored, TableAccess merged) {
        for (final Privilege privilege : PrivilegeScope.GRANTABLE) {
            if (!Set.copyOf(stored.rolesFor(privilege)).equals(Set.copyOf(merged.rolesFor(privilege)))) {
                return false;
            }
        }
        return true;
    }

    /** Trims, rejects blanks (422), and de-duplicates while preserving order. */
    private static List<String> normalize(List<String> roles) {
        final Set<String> result = new LinkedHashSet<>();
        if (roles == null) {
            return new ArrayList<>();
        }
        for (final String role : roles) {
            if (StringUtils.isBlank(role)) {
                throw new TableValidationException("access role names must be non-blank");
            }
            result.add(role.trim());
        }
        return new ArrayList<>(result);
    }
}
