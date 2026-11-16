package com.epam.aidial.analytics.service.table;

import com.epam.aidial.analytics.exception.ColumnInColumnPolicyException;
import com.epam.aidial.analytics.service.table.grant.ColumnPolicyCodec;
import com.epam.aidial.analytics.service.table.grant.RoleGenerationCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Keeps column policies pointing at the columns they meant across a schema change (see
 * {@code openspec/specs/table-management/spec.md}, "Column policies follow a rename and refuse an emptying drop"):
 * dropped names are pruned first, renamed ones rewritten in the pruned lists, and a list the prune would empty is
 * refused {@code 409 column_in_column_policy}. The caller holds the row lock of the definition whose lists change — the
 * table's own, or its source's for an enrichment, whose columns the source's lists name {@code <enrichment>.<column>}.
 */
@Component
@RequiredArgsConstructor
public class ColumnPolicyRewriter {

    private final ColumnPolicyCodec codec;
    private final RoleGenerationCodec roleGenerations;

    /** Whether {@code owner} stores any column policy, so a caller can skip collecting the names a change removes. */
    public boolean hasPolicies(TableDefinitionEntity owner) {
        return owner != null && !codec.read(owner.getColumnPolicies()).isEmpty();
    }

    /** One rename, by exposed name. */
    public record Rename(String from, String to) {}

    /**
     * The rewritten policies of {@code owner}, or {@code null} when no list changes.
     *
     * @param owner   the definition whose lists change, already locked
     * @param prefix  {@code ""} for the owner's own columns, {@code "<enrichment>."} for an enrichment's
     * @param table   the table whose columns change, for the message
     * @param dropped the exposed names dropped
     * @param renames the renames, in request order, applied after the prune
     * @throws ColumnInColumnPolicyException when a list would be left empty (409)
     */
    public Map<String, List<String>> plan(TableDefinitionEntity owner, String prefix, String table,
            Collection<String> dropped, List<Rename> renames) {
        final Map<String, List<String>> stored = codec.read(owner.getColumnPolicies());
        if (stored.isEmpty() || dropped.isEmpty() && renames.isEmpty()) {
            return null;
        }
        final Set<String> droppedNames = new TreeSet<>();
        dropped.forEach(name -> droppedNames.add(prefix + name));
        final Map<String, List<String>> rewritten = new TreeMap<>();
        final Set<String> emptiedRoles = new TreeSet<>();
        final Set<String> emptiedBy = new TreeSet<>();
        stored.forEach((role, columns) -> {
            final List<String> pruned = new ArrayList<>(columns);
            pruned.removeIf(droppedNames::contains);
            if (pruned.isEmpty()) {
                emptiedRoles.add(role);
                columns.stream().filter(droppedNames::contains).forEach(emptiedBy::add);
            }
            for (final Rename rename : renames) {
                pruned.replaceAll(name -> name.equals(prefix + rename.from()) ? prefix + rename.to() : name);
            }
            rewritten.put(role, ColumnPolicyCodec.canonical(pruned));
        });
        if (!emptiedRoles.isEmpty()) {
            throw new ColumnInColumnPolicyException("removing column(s) " + emptiedBy + " of table '" + table + "' would "
                    + "empty the column policies of role(s) " + emptiedRoles + " on table '" + owner.getName() + "'; "
                    + "edit or remove those access entries first");
        }
        return rewritten.equals(stored) ? null : rewritten;
    }

    /**
     * Writes a non-null {@link #plan} through {@code owner}, bumps its access generation once and marks every role whose
     * list changed at the new generation; a {@code null} plan writes nothing.
     */
    public void apply(TableDefinitionEntity owner, Map<String, List<String>> plan) {
        if (plan == null) {
            return;
        }
        final Map<String, List<String>> stored = codec.read(owner.getColumnPolicies());
        final List<String> changed = plan.keySet().stream().filter(role -> !plan.get(role).equals(stored.get(role))).toList();
        owner.setColumnPolicies(codec.write(plan));
        owner.setAccessGeneration(owner.getAccessGeneration() + 1);
        owner.setAccessRoleGenerations(roleGenerations.mark(owner.getAccessRoleGenerations(), changed, owner.getAccessGeneration()));
    }
}
