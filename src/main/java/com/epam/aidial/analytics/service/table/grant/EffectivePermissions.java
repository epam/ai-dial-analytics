package com.epam.aidial.analytics.service.table.grant;

import com.epam.aidial.analytics.web.security.Privilege;

import java.util.Map;

/**
 * The calling identity's effective per-table permissions, as reported on the table read surfaces
 * (list/get): a flag per table-scoped {@link Privilege}, plus {@code grant},
 * computed <b>after</b> implication — so a role listed only under {@code read} reports {@code read},
 * {@code get} and {@code list} all true.
 *
 * <p>{@code list} is internal: it decides whether the list endpoint shows the table, and is not reported,
 * since a caller that sees a table at all would only ever see it {@code true}.
 *
 * <p>{@code sensitive} reports the clearance itself, not "may read sensitive values": that is
 * {@code read && sensitive}, as seeing sensitive columns is {@code get && sensitive}.
 *
 * <p>{@code grant} reports the global-only {@link Privilege#TABLE_GRANT}, so whether the caller may use the table's
 * access API; no entry sets it, and it is the same on every table.
 *
 * <p>{@code rowsRestricted} is not a privilege: it reports whether row policies confine the caller's reads
 * of the table to some of its rows, and {@code columnsRestricted} whether column policies hide one of its columns from
 * the caller; judged without columns, as on a system table's revision snapshot, it is {@code true} whenever a column
 * policy confines the caller (see {@link TableCallerPermissions}). Neither discloses the policies themselves.
 *
 * <p>Computed by {@link TableCallerPermissions}, which shares its role-matching with the enforcement path
 * so reporting and enforcement cannot disagree.
 */
public record EffectivePermissions(
        boolean list,
        boolean get,
        boolean read,
        boolean sensitive,
        boolean write,
        boolean modify,
        boolean grant,
        boolean rowsRestricted,
        boolean columnsRestricted) {

    /** The six privilege flags, with no {@code grant} and no row or column restriction. */
    public EffectivePermissions(boolean list, boolean get, boolean read, boolean sensitive, boolean write, boolean modify) {
        this(list, get, read, sensitive, write, modify, false, false, false);
    }

    /** Every privilege flag and {@code grant} {@code value}, with no row confinement. */
    public static EffectivePermissions uniform(boolean value) {
        return new EffectivePermissions(value, value, value, value, value, value, value, false, false);
    }

    /** Builds from a per-privilege decision, {@code TABLE_GRANT} included, and the row-confinement and column-restriction answers. */
    public static EffectivePermissions of(Map<Privilege, Boolean> flags, boolean rowsRestricted, boolean columnsRestricted) {
        return new EffectivePermissions(
                Boolean.TRUE.equals(flags.get(Privilege.TABLE_LIST)),
                Boolean.TRUE.equals(flags.get(Privilege.TABLE_GET)),
                Boolean.TRUE.equals(flags.get(Privilege.TABLE_READ)),
                Boolean.TRUE.equals(flags.get(Privilege.TABLE_SENSITIVE)),
                Boolean.TRUE.equals(flags.get(Privilege.TABLE_WRITE)),
                Boolean.TRUE.equals(flags.get(Privilege.TABLE_MODIFY)),
                Boolean.TRUE.equals(flags.get(Privilege.TABLE_GRANT)),
                rowsRestricted,
                columnsRestricted);
    }

}
