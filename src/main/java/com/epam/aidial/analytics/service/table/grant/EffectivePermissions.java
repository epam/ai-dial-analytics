package com.epam.aidial.analytics.service.table.grant;

import com.epam.aidial.analytics.web.security.Privilege;

import java.util.Map;

/**
 * The calling identity's effective per-table permissions, as reported on the table read surfaces
 * (list/get) and in the response to a grant replacement. One flag per table-scoped {@link Privilege},
 * computed <b>after</b> implication — so a role listed only under {@code read} reports {@code read},
 * {@code get} and {@code list} all true.
 *
 * <p>{@code list} is internal: it decides whether the list endpoint shows the table, and is not reported,
 * since a caller that sees a table at all would only ever see it {@code true}.
 *
 * <p>{@code sensitive} reports the clearance itself, not "may read sensitive values": that is
 * {@code read && sensitive}, as seeing sensitive columns is {@code get && sensitive}.
 *
 * <p>{@code rowsRestricted} is not a privilege: it reports whether row policies confine the caller's reads
 * of the table to some of its rows. It never discloses the policy itself.
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
        boolean rowsRestricted) {

    /** The six privilege flags, with no row confinement. */
    public EffectivePermissions(boolean list, boolean get, boolean read, boolean sensitive, boolean write, boolean modify) {
        this(list, get, read, sensitive, write, modify, false);
    }

    /** Every privilege flag {@code value}, with no row confinement. */
    public static EffectivePermissions uniform(boolean value) {
        return new EffectivePermissions(value, value, value, value, value, value);
    }

    /** Every flag but {@code write} and {@code modify} set to {@code readSide} — the clearance included — and those two false. */
    public static EffectivePermissions readSideOnly(boolean readSide) {
        return new EffectivePermissions(readSide, readSide, readSide, readSide, false, false);
    }

    /** Builds from a per-privilege decision and the row-confinement answer. */
    public static EffectivePermissions of(Map<Privilege, Boolean> flags, boolean rowsRestricted) {
        return new EffectivePermissions(
                Boolean.TRUE.equals(flags.get(Privilege.TABLE_LIST)),
                Boolean.TRUE.equals(flags.get(Privilege.TABLE_GET)),
                Boolean.TRUE.equals(flags.get(Privilege.TABLE_READ)),
                Boolean.TRUE.equals(flags.get(Privilege.TABLE_SENSITIVE)),
                Boolean.TRUE.equals(flags.get(Privilege.TABLE_WRITE)),
                Boolean.TRUE.equals(flags.get(Privilege.TABLE_MODIFY)),
                rowsRestricted);
    }

}
