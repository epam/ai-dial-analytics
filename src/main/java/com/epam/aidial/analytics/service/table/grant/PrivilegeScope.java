package com.epam.aidial.analytics.service.table.grant;

import com.epam.aidial.analytics.web.security.Privilege;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which privileges are <em>table-scoped</em> — holdable globally over every table, or granted on a single
 * table — and the key each one occupies in a table's grant lists. The classification lives here, beside
 * the lists that carry it, rather than on {@link Privilege}: scope is a property of the object layer, and
 * a {@code TABLE_} prefix does not imply it ({@code TABLE_CREATE}, {@code TABLE_DELETE} and
 * {@code TABLE_GRANT} are global-only — there is no table to scope a create to, and delegated ownership
 * is deliberately out of scope).
 *
 * <p>A grant key is the privilege name minus its {@code TABLE_} prefix, lowercased:
 * {@code get, read, sensitive, write, modify}.
 *
 * <p>{@link Privilege#TABLE_LIST} is table-scoped but <b>not grantable</b>. It is checked per table — the
 * list endpoint shows a table to a caller holding it there — but on a single table it is only ever held by
 * implication from a grant that already shows the table ({@code get} and up, {@code modify}). Held
 * globally it means "enumerate every table". A per-table {@code list} key would grant a name without its
 * definition, and a definition discloses nothing a name does not: column names are not secret, only values.
 */
public final class PrivilegeScope {

    /** The privileges checkable on a single table, in ladder-then-independent order. */
    public static final List<Privilege> TABLE_SCOPED = List.of(
            Privilege.TABLE_LIST,
            Privilege.TABLE_GET,
            Privilege.TABLE_READ,
            Privilege.TABLE_SENSITIVE,
            Privilege.TABLE_WRITE,
            Privilege.TABLE_MODIFY);

    /** The privileges a table's grant lists carry: every table-scoped one except {@code TABLE_LIST}. */
    public static final List<Privilege> GRANTABLE = List.of(
            Privilege.TABLE_GET,
            Privilege.TABLE_READ,
            Privilege.TABLE_SENSITIVE,
            Privilege.TABLE_WRITE,
            Privilege.TABLE_MODIFY);

    /**
     * The read-side verbs. A missing one of these reports the table as absent rather than forbidden (see
     * the non-disclosure rule in the {@code authentication} capability). {@link Privilege#TABLE_SENSITIVE}
     * is not among them: it is never an endpoint gate, and a missing clearance hides columns, never a table.
     */
    public static final Set<Privilege> READ_SIDE = EnumSet.of(
            Privilege.TABLE_LIST,
            Privilege.TABLE_GET,
            Privilege.TABLE_READ);

    /**
     * The privileges that change a table. Their lists stay empty on a {@code system} table, which refuses
     * every modifying operation; every other key, the clearance included, is settable there.
     */
    public static final Set<Privilege> MODIFYING = EnumSet.of(
            Privilege.TABLE_WRITE,
            Privilege.TABLE_MODIFY);

    private static final Set<Privilege> TABLE_SCOPED_SET = EnumSet.copyOf(TABLE_SCOPED);

    private static final Set<Privilege> GRANTABLE_SET = EnumSet.copyOf(GRANTABLE);

    private static final String TABLE_PREFIX = "TABLE_";

    private PrivilegeScope() {
    }

    /** Whether {@code privilege} can be checked on a single table as well as held globally. */
    public static boolean isTableScoped(Privilege privilege) {
        return TABLE_SCOPED_SET.contains(privilege);
    }

    /** Whether {@code privilege} has a grant list of its own on a table. */
    public static boolean isGrantable(Privilege privilege) {
        return GRANTABLE_SET.contains(privilege);
    }

    /** Whether a denial of {@code privilege} must be reported as absence rather than as a refusal. */
    public static boolean isReadSide(Privilege privilege) {
        return READ_SIDE.contains(privilege);
    }

    /** Whether {@code privilege} changes a table, and so is refused on a {@code system} one. */
    public static boolean isModifying(Privilege privilege) {
        return MODIFYING.contains(privilege);
    }

    /**
     * The grant-list key for a grantable privilege: its name minus {@code TABLE_}, lowercased.
     *
     * @throws IllegalArgumentException when {@code privilege} is not grantable and so has no key
     */
    public static String grantKey(Privilege privilege) {
        if (!isGrantable(privilege)) {
            throw new IllegalArgumentException(privilege + " is not grantable per table and has no grant key");
        }
        return privilege.name().substring(TABLE_PREFIX.length()).toLowerCase(Locale.ROOT);
    }
}
