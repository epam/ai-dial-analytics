package com.epam.aidial.analytics.service.table;

import com.epam.aidial.analytics.exception.TableValidationException;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The rules governing a table's scan-metadata pair — {@code identity_column} and {@code version_column},
 * the tuple the governed incremental scan pages a source by (see
 * {@code service.enrichment.support.ScanSourceMetadata} and {@code CursorPredicates}).
 *
 * <p>Both halves of the contract live here. {@link #requireDeclarable} says which table kind may declare
 * the pair at all, and runs before anything is persisted; {@link #validateSource} says what a declared name
 * must physically be, and runs against the resolved column rows of the same request. Two entry points
 * because the kind rule has no columns to look at yet — not because the rules differ by caller.
 *
 * <p>{@link #requireDroppable} and {@link #requireNotSensitive} keep those same properties true for the
 * <em>life</em> of the table: a declared pair is verified once at define time, and a live
 * {@code PATCH .../schema} must not be able to undo the verification afterwards. A rename is deliberately
 * <b>not</b> among them — it preserves every property this class checks (type, nullability, sensitivity all
 * travel with the column) and the stored name is repointed in the same transaction, so there is nothing to
 * refuse.
 *
 * <p>The pair is <b>optional</b> for a source. A source that declares it is scannable; one that does not is
 * rejected by the scan itself, with a 400 naming the missing configuration. Optional rather than required
 * because the identity half cannot always be told the truth: a rollup's row identity is its whole
 * {@code ordering_key}, so a multi-column-grain rollup has no single column that is unique per row, and
 * requiring one would only ever have produced a declaration naming part of the key. Under keyset paging
 * that is not a cosmetic inaccuracy — rows sharing a cursor tuple are skipped, silently.
 *
 * <p>What IS checked, of a declared name: it is a column of this table, it is not nullable, it is not
 * sensitive, and a version column is a {@code timestamp}. Those are not style preferences — the scan
 * renders the cursor as {@code (toUnixTimestamp64Milli(version), toString(identity))} and lags it against
 * {@code now64(3)}, so a non-temporal version is a ClickHouse error at scan time and a {@code NULL} in
 * either half silently drops the row from every page.
 *
 * <p>What is NOT checked — that the version is assigned at ingest, monotonic and never backdated, and that
 * the identity is unique per row — are physical facts about ingest that no layer here can verify (an
 * operator migration could not either), and remain the declaring caller's promise. Declaring the pair is
 * therefore a claim the caller makes deliberately, which is exactly why nothing forces one.
 */
final class ScanMetadataRules {

    private ScanMetadataRules() {
    }

    /**
     * The kind rule, checkable before the columns are resolved: an enrichment declares neither member. It is
     * keyed on its {@code grain_key} and versioned by the {@code ReplacingMergeTree} column the platform
     * stamps, so neither name would resolve to anything a caller declared — rejecting says so instead of
     * returning 200 having ignored the field. A source MAY declare the pair; see {@link #validateSource} for
     * what a declared name has to be.
     */
    static void requireDeclarable(TableDefinitionEntity definition, DefineSchemaInput input) {
        if (definition.getType() != TableType.ENRICHMENT) {
            return;
        }
        requireAbsent("identity_column", input.identityColumn(),
                "an enrichment is keyed on its grain_key");
        requireAbsent("version_column", input.versionColumn(),
                "an enrichment's row version is the ReplacingMergeTree column the platform stamps, not a"
                        + " caller-declared one");
    }

    /**
     * The physical rules for a source's declared pair, against the columns the same request declares:
     * each name is one of them, neither is nullable or sensitive, and the version column is a
     * {@code timestamp} — or, for an {@code upsert_by_key} source, the engine's own version column
     * {@code _updated_at}, which such a table physically carries and which is deliberately absent from
     * {@code column_mapping}.
     *
     * <p>An absent member is not an error: the pair is optional, and omitting one on a re-post leaves the
     * stored value alone.
     */
    static void validateSource(TableDefinitionEntity definition, DefineSchemaInput draft,
                               List<ColumnMappingEntity> rows) {
        final Map<String, ColumnMappingEntity> declared = new HashMap<>();
        rows.forEach(row -> declared.put(row.getSourceName(), row));

        if (draft.identityColumn() != null) {
            requireUsable("identity_column", requireDeclared("identity_column", draft.identityColumn(), declared));
        }
        final String version = draft.versionColumn();
        if (version == null || isEngineVersion(definition, version)) {
            return;
        }
        if (TableDdlRenderer.UPDATED_AT.equals(version)) {
            // Reachable only for an append source: the engine version exists on a ReplacingMergeTree, which
            // is what upsert_by_key selects. Say that, rather than "not a declared column" — the column is
            // real, it is this table that does not have it.
            throw new TableValidationException("version_column '" + TableDdlRenderer.UPDATED_AT + "' is the"
                    + " ReplacingMergeTree version, which only an 'upsert_by_key' table carries; an 'append'"
                    + " source declares its own non-nullable timestamp column as its version");
        }
        final ColumnMappingEntity column = requireDeclared("version_column", version, declared);
        requireUsable("version_column", column);
        if (column.getType() != QueryFieldType.TIMESTAMP) {
            throw new TableValidationException("version_column '" + version + "' is declared '"
                    + column.getType().code() + "'; the scan orders and safety-lags a source by this column as"
                    + " a millisecond timestamp, so it must be declared 'timestamp'"
                    + (definition.getWriteMode() == TableWriteMode.UPSERT_BY_KEY
                            ? " (or name the engine version '" + TableDdlRenderer.UPDATED_AT + "')" : ""));
        }
    }

    /**
     * The drop rule for a materialized table: a column the pair names cannot be dropped. Nothing repoints
     * the pair to a different column, so a drop would leave the table advertising a scan column that no
     * longer exists, with no request a caller could make to repair it. A <em>rename</em> is the operation
     * that has an answer — the stored name follows it — which is why only the drop is refused here.
     */
    static void requireDroppable(TableDefinitionEntity definition, String sourceName) {
        if (sourceName.equals(definition.getIdentityColumn())) {
            throw new TableValidationException("the identity column '" + sourceName + "' cannot be dropped;"
                    + " no endpoint repoints the scan-metadata pair, so the table would advertise a scan"
                    + " column that no longer exists (a rename is allowed, and repoints the stored name)");
        }
        if (sourceName.equals(definition.getVersionColumn())) {
            throw new TableValidationException("the version column '" + sourceName + "' cannot be dropped;"
                    + " no endpoint repoints the scan-metadata pair, so the table would advertise a scan"
                    + " column that no longer exists (a rename is allowed, and repoints the stored name)");
        }
    }

    /**
     * The one column attribute a metadata {@code update} may not set on a cursor column. Everything else
     * {@code update} carries is invisible to the scan; {@code sensitive} is not — {@code ScanService}
     * projects both cursor columns through its sensitive-projection guard and carries the identity inside
     * the page cursor, so flipping the flag after materialization would make every rule over this source
     * answer 403 to every caller without the sensitive entitlement. {@link #requireUsable} refuses exactly
     * this at declaration; refusing it here is what stops a metadata patch from undoing that.
     *
     * <p>{@code identityColumn}/{@code versionColumn} are the names the pair holds <em>after</em> the same
     * request's renames, so marking a just-renamed cursor column sensitive is refused too.
     */
    static void requireNotSensitive(String identityColumn, String versionColumn, String sourceName) {
        final String field;
        if (sourceName.equals(identityColumn)) {
            field = "identity column";
        } else if (sourceName.equals(versionColumn)) {
            field = "version column";
        } else {
            return;
        }
        throw new TableValidationException("the " + field + " '" + sourceName + "' cannot be marked"
                + " sensitive; the scan projects both cursor columns and carries the identity in its"
                + " cursor, so every rule over this source would be refused for any caller not entitled"
                + " to sensitive columns");
    }

    /** Whether {@code version} names the engine's own version column, which only an upsert table carries. */
    private static boolean isEngineVersion(TableDefinitionEntity definition, String version) {
        return definition.getWriteMode() == TableWriteMode.UPSERT_BY_KEY
                && TableDdlRenderer.UPDATED_AT.equals(version);
    }

    private static ColumnMappingEntity requireDeclared(String field, String column,
                                                       Map<String, ColumnMappingEntity> declared) {
        final ColumnMappingEntity row = declared.get(column);
        if (row == null) {
            throw new TableValidationException(
                    field + " '" + column + "' is not a declared column of this table");
        }
        return row;
    }

    /**
     * The two properties a cursor column must have whichever half it is. Nullable: the cursor tuple compare
     * and the safety-lag predicate both yield {@code NULL} for a {@code NULL} value, so such a row is never
     * returned by any page — a silent gap, which is worse than an error. Sensitive: the scan projects both
     * cursor columns and echoes the identity inside the page cursor, so a pipeline over this source would be
     * refused (403) for every caller not entitled to sensitive columns.
     */
    private static void requireUsable(String field, ColumnMappingEntity column) {
        if (column.getType() == QueryFieldType.ARRAY
                || column.getType() == QueryFieldType.OBJECT
                || column.getType() == QueryFieldType.MAP) {
            throw new TableValidationException(field + " '" + column.getSourceName() + "' is '"
                    + column.getType().code() + "'; a cursor column must be an ordered scalar, and the DSL"
                    + " has no comparison over that type");
        }
        if (column.isNullable()) {
            throw new TableValidationException(field + " '" + column.getSourceName() + "' is nullable; a NULL"
                    + " fails every cursor comparison, so the scan would silently skip those rows — declare it"
                    + " \"nullable\": false");
        }
        if (column.isSensitive()) {
            throw new TableValidationException(field + " '" + column.getSourceName() + "' is sensitive; the scan"
                    + " projects both cursor columns and carries the identity in its cursor, so a pipeline over"
                    + " this source would be refused for any caller not entitled to sensitive columns");
        }
    }

    private static void requireAbsent(String field, String supplied, String because) {
        if (supplied != null) {
            throw new TableValidationException(field + " is not accepted for an enrichment; " + because);
        }
    }
}
