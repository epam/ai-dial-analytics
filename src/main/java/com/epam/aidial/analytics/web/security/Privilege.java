package com.epam.aidial.analytics.web.security;

/**
 * The unit of authorization: one atomic application privilege, named {@code <OBJECT>_<VERB>} over the
 * object classes {@code TABLE}, {@code REGISTRY}, {@code AUDIT}, {@code SAVED_QUERY} and
 * {@code DATA_SYNC}. A gate names the privilege it needs; a {@code roles-mapping} entry names the
 * privileges a provider role confers, directly or through a {@link RoleBundle}.
 *
 * <p>Six of these are <em>table-scoped</em>: they may be held globally, over every table, or granted on a
 * single table, with effective access the union of the two. Which six is classified in
 * {@code service.table.grant.PrivilegeScope}, next to the grant lists that carry them — a {@code TABLE_}
 * prefix does not itself imply table scope ({@code TABLE_CREATE} has no table to be scoped to).
 *
 * <p>Implication between privileges is stated once, in {@link PrivilegeImplications}, and admitted only
 * where it is disclosure-monotone. Notably {@code TABLE_WRITE} implies nothing.
 */
public enum Privilege {

    /** See that a table exists: its name and the summary fields of a catalog list entry. */
    TABLE_LIST,

    /** Read a table's definition and column inventory; sensitive columns appear only with {@link #TABLE_SENSITIVE}. */
    TABLE_GET,

    /** Read a table's non-sensitive column values. */
    TABLE_READ,

    /**
     * The sensitivity clearance. Not a verb: it confers nothing on its own and implies nothing. It extends
     * {@link #TABLE_GET}, {@link #TABLE_READ} and {@link #TABLE_MODIFY}, held on the same table, to that
     * table's sensitive columns and to their {@code sensitive} flag.
     */
    TABLE_SENSITIVE,

    /** Insert rows into a table. Confers no read of any kind. */
    TABLE_WRITE,

    /**
     * Evolve a table's schema and update its description. Sensitive columns and the {@code sensitive}
     * flag additionally need {@link #TABLE_SENSITIVE}.
     */
    TABLE_MODIFY,

    /** Create a catalog table. Global-only: there is no table yet to scope it to. */
    TABLE_CREATE,

    /** Delete a catalog table. Global-only, deliberately not delegable by a grant. */
    TABLE_DELETE,

    /** Read and replace a table's grant lists. Global-only. */
    TABLE_GRANT,

    /** Read the pipeline and sink control planes, and data-sync run status. */
    REGISTRY_READ,

    /** Author the registry: pipelines and pipeline execution. */
    REGISTRY_ADMIN,

    /** Read the audit activity feed. */
    AUDIT_READ,

    /** Advance ingestion without reference to a stored declaration (backfill). */
    DATA_SYNC_OPERATE,

    /** Read saved queries the caller may see. */
    SAVED_QUERY_READ,

    /** Create, replace and delete the caller's own {@code personal} saved queries. */
    SAVED_QUERY_WRITE,

    /** Create, replace and delete {@code common} saved queries — curate shared definitions. */
    SAVED_QUERY_PUBLISH;

}
