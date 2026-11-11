package com.epam.aidial.analytics.web.security;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The privilege implication DAG, stated once as six direct edges and closed transitively on demand.
 *
 * <p>An edge is admitted on one of two grounds. The first is <b>disclosure-monotonicity</b>: the implied
 * privilege reveals a strict subset of what the granting privilege already reveals. The second covers a
 * privilege that <b>edits documents by whole-document replace</b>: writing a document presupposes reading
 * it, so the privilege implies that read, provided <b>no read check consults the granting privilege</b>.
 * Then, whatever else a caller holds, the granting privilege adds exactly the implied read to what it may
 * see and nothing more. Applied per edge rather than per intuition, the two grounds yield exactly:
 *
 * <ul>
 *   <li>{@code TABLE_GET → TABLE_LIST} — a definition read contains the list entry.</li>
 *   <li>{@code TABLE_READ → TABLE_GET} — reading values presupposes knowing the columns.</li>
 *   <li>{@code TABLE_MODIFY → TABLE_GET} — evolving a schema requires seeing it.</li>
 *   <li>{@code SAVED_QUERY_WRITE → SAVED_QUERY_READ} and {@code SAVED_QUERY_PUBLISH → SAVED_QUERY_READ} —
 *   on the second ground. Saved queries and dashboards are replaced whole, and a dashboard replace carries
 *   the {@code generation} a read returned. Every saved-query and dashboard read, the revision endpoint and
 *   the activity feed included, decides visibility from scope, visibility and owner alone, never from a
 *   write privilege: a {@code personal} saved query and a {@code private} dashboard are visible to their
 *   owner only, whatever the caller holds.</li>
 *   <li>{@code SETTINGS_WRITE → SETTINGS_READ} — on the second ground. A setting is replaced whole, and the
 *   settings read is gated by {@code SETTINGS_READ} alone; a setting has no owner or visibility, so the edge
 *   discloses exactly what {@code SETTINGS_READ} does.</li>
 * </ul>
 *
 * <p>and no others. <b>{@code TABLE_SENSITIVE} has no edge in either direction</b>: it is a clearance that
 * qualifies {@code GET}, {@code READ} and {@code MODIFY} rather than a rung above {@code READ}, so a caller
 * reaches a sensitive column only by holding the verb <em>and</em> the clearance on its table. Modelling it
 * as a rung is what made "modify, including sensitive columns" inexpressible.
 *
 * <p>In particular <b>{@code TABLE_WRITE} implies nothing</b>: {@code GET \ LIST} is
 * {@code {columns, grain}}, and a row insert only confirms or denies a column name the caller already
 * guessed, so {@code WRITE} leaks the inventory without containing it. Admitting {@code WRITE → READ} is
 * the confusion that produced the rule-path read escalation this model closes, and keeping {@code WRITE}
 * isolated is also the better operational result — an ingest-only identity stays invisible in the catalog.
 * Nor does the second ground reach it: a row insert appends and replaces nothing, so it edits no document.
 *
 * <p>{@code SAVED_QUERY_PUBLISH} does not imply {@code SAVED_QUERY_WRITE}: the two name different scopes
 * rather than two rungs of one, and a write privilege is not a disclosure. What makes either authorize a
 * write on its own is the deferred gate on each saved-query and dashboard write endpoint, where the access
 * check decides against the scope or visibility in the request body.
 *
 * <p>Implication is derived when a check runs and is never materialised into a stored grant list: a read
 * of the lists returns exactly the grantees that were written to them.
 */
public final class PrivilegeImplications {

    /** The direct edges: key implies every privilege in its value set. */
    private static final Map<Privilege, Set<Privilege>> DIRECT;

    static {
        final Map<Privilege, Set<Privilege>> direct = new EnumMap<>(Privilege.class);
        direct.put(Privilege.TABLE_GET, EnumSet.of(Privilege.TABLE_LIST));
        direct.put(Privilege.TABLE_READ, EnumSet.of(Privilege.TABLE_GET));
        direct.put(Privilege.TABLE_MODIFY, EnumSet.of(Privilege.TABLE_GET));
        direct.put(Privilege.SAVED_QUERY_WRITE, EnumSet.of(Privilege.SAVED_QUERY_READ));
        direct.put(Privilege.SAVED_QUERY_PUBLISH, EnumSet.of(Privilege.SAVED_QUERY_READ));
        direct.put(Privilege.SETTINGS_WRITE, EnumSet.of(Privilege.SETTINGS_READ));
        DIRECT = Map.copyOf(direct);
    }

    private PrivilegeImplications() {
    }

    /** The privileges {@code privilege} directly implies, without transitivity. */
    public static Set<Privilege> directlyImplied(Privilege privilege) {
        return DIRECT.getOrDefault(privilege, EnumSet.noneOf(Privilege.class));
    }

    /**
     * {@code privileges} plus everything they imply, transitively. This is what a set of held privileges
     * expands to, and what a bundle's minimally-stored set is closed by at configuration-parse time.
     */
    public static Set<Privilege> closure(Set<Privilege> privileges) {
        final Set<Privilege> closed = EnumSet.noneOf(Privilege.class);
        final Deque<Privilege> pending = new ArrayDeque<>(privileges);
        while (!pending.isEmpty()) {
            final Privilege current = pending.pop();
            if (closed.add(current)) {
                pending.addAll(directlyImplied(current));
            }
        }
        return closed;
    }

    /** {@code privilege} plus everything it implies, transitively. */
    public static Set<Privilege> closureOf(Privilege privilege) {
        return closure(EnumSet.of(privilege));
    }

    /**
     * The privileges whose closure contains {@code required} — i.e. every privilege that would satisfy a
     * check for {@code required}. This is the form the per-table evaluator needs: it must consult each
     * grant list that confers {@code required}, not only the list named after it.
     */
    public static Set<Privilege> conferring(Privilege required) {
        final Set<Privilege> result = EnumSet.noneOf(Privilege.class);
        for (final Privilege candidate : Privilege.values()) {
            if (closureOf(candidate).contains(required)) {
                result.add(candidate);
            }
        }
        return result;
    }
}
