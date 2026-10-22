package com.epam.aidial.analytics.service.table;

import com.epam.aidial.analytics.web.security.Privilege;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-table grant lists, persisted as the JSONB {@code access} column of {@link TableDefinitionEntity}:
 * one list of role names per grantable {@link Privilege}, keyed by the privilege name minus its
 * {@code TABLE_} prefix, lowercased. {@code TABLE_LIST} has no list: every grant below except {@code write}
 * and {@code sensitive} already shows the table, and held globally it means enumerating every table (see
 * {@code PrivilegeScope}).
 *
 * <p>{@code get} and {@code read} form a ladder — a grant at one rung confers every rung below it, down to
 * listing — {@code modify} additionally confers {@code get}, and {@code write} confers nothing.
 * {@code sensitive} is the clearance: it confers nothing alone and extends the same role's {@code get},
 * {@code read} and {@code modify} to the sensitive columns. That implication is derived when a
 * check runs and is never written into these lists: a read of them returns exactly the grantees that were
 * stored.
 *
 * <p>Values are raw provider role names (the same names used as {@code roles-mapping} keys), matched
 * against the caller's provider roles. A bundle or privilege name is not a valid grantee. There is no
 * bypass for a privileged caller: one that holds the privilege globally is authorized by that fact.
 *
 * <p>Every list is initialized empty, which is what makes a row written before a key existed read
 * correctly — an absent key grants nothing, exactly like an empty one.
 *
 * <p><b>Value equality is load-bearing, not decoration.</b> Hibernate's dirty check for a JSON-mapped
 * column compares the loaded snapshot — a deep copy, so a different instance — against the current value
 * with {@code Objects.equals}. Without {@code equals} that is identity, so every definition loaded inside
 * a write transaction is reported as changed and rewritten, and because the entity is {@code @Audited}
 * each rewrite adds an Envers revision attributed to whoever happened to trigger the load. The visible
 * symptom is an audit entry with a real author and no cause — a pipeline registration appearing to have
 * edited a table's grants.
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode
public class TableAccess {

    /** Roles that may see the table and read its definition and column inventory. */
    @JsonProperty("get")
    private List<String> get = new ArrayList<>();

    /** Roles that may read the table's non-sensitive column values. */
    @JsonProperty("read")
    private List<String> read = new ArrayList<>();

    /** Roles cleared for the table's sensitive columns, for whichever of get/read/modify they also hold. */
    @JsonProperty("sensitive")
    private List<String> sensitive = new ArrayList<>();

    /** Roles that may insert rows. Confers no read. */
    @JsonProperty("write")
    private List<String> write = new ArrayList<>();

    /** Roles that may change the schema or description; sensitive columns need {@code sensitive} too. */
    @JsonProperty("modify")
    private List<String> modify = new ArrayList<>();

    /** The stored list for a grantable privilege, never null. */
    public List<String> rolesFor(Privilege privilege) {
        return switch (privilege) {
            case TABLE_GET -> nullSafe(get);
            case TABLE_READ -> nullSafe(read);
            case TABLE_SENSITIVE -> nullSafe(sensitive);
            case TABLE_WRITE -> nullSafe(write);
            case TABLE_MODIFY -> nullSafe(modify);
            default -> throw new IllegalArgumentException(privilege + " has no grant list");
        };
    }

    /** Replaces the stored list for a grantable privilege. */
    public void setRolesFor(Privilege privilege, List<String> roles) {
        switch (privilege) {
            case TABLE_GET -> get = roles;
            case TABLE_READ -> read = roles;
            case TABLE_SENSITIVE -> sensitive = roles;
            case TABLE_WRITE -> write = roles;
            case TABLE_MODIFY -> modify = roles;
            default -> throw new IllegalArgumentException(privilege + " has no grant list");
        }
    }

    private static List<String> nullSafe(List<String> roles) {
        return roles == null ? List.of() : roles;
    }
}
