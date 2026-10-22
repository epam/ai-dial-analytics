package com.epam.aidial.analytics.web.security;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The privilege implication DAG, stated once as three direct edges and closed transitively on demand.
 *
 * <p>An edge is admitted only where it is <b>disclosure-monotone</b>: the implied privilege reveals a
 * strict subset of what the granting privilege already reveals. Applied per edge rather than per
 * intuition, that rule yields exactly:
 *
 * <ul>
 *   <li>{@code TABLE_GET → TABLE_LIST} — a definition read contains the list entry.</li>
 *   <li>{@code TABLE_READ → TABLE_GET} — reading values presupposes knowing the columns.</li>
 *   <li>{@code TABLE_MODIFY → TABLE_GET} — evolving a schema requires seeing it.</li>
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
 *
 * <p><b>{@code SAVED_QUERY_PUBLISH} implies nothing either</b>, and one review round briefly gave it
 * {@code → SAVED_QUERY_READ} on the argument that it already sees every saved query. That argument does
 * not hold: only the revision endpoint and the audit feed consult
 * {@code SavedQueryAccess.maySeeEverySavedQuery}, while {@code get} and {@code list} go through
 * {@code SavedQueryRepository.findVisible}, whose predicate has no such bypass. The edge therefore let a
 * {@code PUBLISH}-only caller read another user's <em>personal</em> saved query at
 * {@code GET /{id}/revision/{rev}} while {@code GET /{id}} on the same row still answered {@code 404} —
 * a disclosure, and an incoherent one. It does not imply {@code SAVED_QUERY_WRITE} either: the two name
 * different scopes rather than two rungs of one, and a write privilege is not a disclosure. What makes
 * {@code PUBLISH} authorize something on its own is the deferred gate on each saved-query write endpoint,
 * where {@code SavedQueryAccess.requireWritable} decides against the scope in the request body.
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
