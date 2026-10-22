package com.epam.aidial.analytics.service.table;

import com.epam.aidial.analytics.clickhouse.CatalogRowWriter;
import com.epam.aidial.analytics.configuration.TableLimitsProperties;
import com.epam.aidial.analytics.exception.SystemTableException;
import com.epam.aidial.analytics.exception.TableConflictException;
import com.epam.aidial.analytics.exception.TableNotFoundException;
import com.epam.aidial.analytics.exception.TableValidationException;
import com.epam.aidial.analytics.service.table.WriteValuePreparer.ColumnShape;
import com.epam.aidial.analytics.service.table.relayout.RelayoutStateStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Minimal data-write surface for a materialized catalog table ({@code POST /v1/tables/{name}/rows}):
 * validates the table exists and is materialized, that each row references only the table's physical
 * columns (exposed {@code source_name}s plus, for an enrichment, its hidden grain key), that every
 * non-nullable physical column is present with a non-null value, and that every value fits its column's
 * declared type ({@link WriteValuePreparer}), then inserts the batch via the {@link CatalogRowWriter} (writer
 * credential). Rows are keyed by physical column name — this is the write side, which uses raw names,
 * not the exposed/aliased query names.
 *
 * <p>This method is the row-write choke point: it is the only caller of {@link CatalogRowWriter#insert},
 * and the pipeline-scoped write-back ({@code POST /v1/pipelines/{name}/rows}) and the OTel sink reach
 * ClickHouse through it. Value handling therefore lives here rather than in any controller, so the write
 * surfaces cannot disagree about what they accept.
 *
 * <p>Only {@code timestamp} values (scalar or array elements) are rewritten, into their storage spelling —
 * every other accepted value is passed through verbatim (notably {@code decimal}, which travels as a string so
 * its digits survive a JSON float, and {@code string}, which stores a timestamp-shaped value as-is). The
 * column-set check runs before the value checks, so a row naming an unknown or {@code _}-prefixed system
 * column is still rejected for that reason regardless of its values.
 *
 * <p>The non-nullable check exists because ClickHouse itself never rejects a null: {@code JSONEachRow}
 * inserts default {@code input_format_null_as_default=1}, which silently backfills the column's type
 * default for an omitted or explicitly-{@code null} value on a non-{@code Nullable} column. Without this
 * check, a table declared NOT NULL at schema-definition time would accept null data through this path
 * with no error anywhere. The value checks exist for the same reason: ClickHouse wraps an out-of-range
 * integer, clamps or rolls over a bad date, and stores a type default for a null array element, all without
 * an error.
 */
@Service
@RequiredArgsConstructor
public class TableDataService {

    private final TableDefinitionRepository definitions;
    private final ColumnMappingRepository columns;
    private final CatalogRowWriter rowWriter;
    private final TableLimitsProperties limits;
    private final WriteValuePreparer values;
    private final RelayoutStateStore relayouts;

    /**
     * Inserts {@code rows} into the materialized table {@code name}, returning the number written.
     *
     * @throws TableNotFoundException   if no such table exists
     * @throws SystemTableException     if the table is system-owned (protected from modification)
     * @throws TableConflictException   if the table is not active (materialized)
     * @throws TableValidationException if the batch is empty/oversized, references an unknown column,
     *                                  omits or nulls a non-nullable column, or carries a value its column's
     *                                  declared type cannot hold faithfully (see {@link WriteValuePreparer})
     */
    @Transactional(readOnly = true)
    public int write(String name, List<Map<String, Object>> rows) {
        final TableDefinitionEntity definition = writableDefinition(name);
        if (rows != null && rows.size() > limits.maxWriteBatchRows()) {
            throw new TableValidationException(
                    "a write batch may contain at most " + limits.maxWriteBatchRows() + " rows");
        }
        rowWriter.insert(name, prepare(definition, rows));
        return rows.size();
    }

    /**
     * Like {@link #write}, for a caller whose batch may exceed {@code maxWriteBatchRows} (the OTel sink, which
     * caps a request on its own terms): every row is validated before the first insert, so a value rejected
     * anywhere in the batch writes nothing, and a rejection names the row's index within the whole batch. The
     * validated rows are then inserted in chunks of {@code maxWriteBatchRows}.
     *
     * @throws TableNotFoundException   if no such table exists
     * @throws SystemTableException     if the table is system-owned (protected from modification)
     * @throws TableConflictException   if the table is not active (materialized)
     * @throws TableValidationException as {@link #write}, except that there is no batch-size cap
     */
    @Transactional(readOnly = true)
    public int writeAll(String name, List<Map<String, Object>> rows) {
        final List<Map<String, Object>> prepared = prepare(writableDefinition(name), rows);
        final int chunkSize = Math.max(1, limits.maxWriteBatchRows());
        for (int from = 0; from < prepared.size(); from += chunkSize) {
            rowWriter.insert(name, prepared.subList(from, Math.min(from + chunkSize, prepared.size())));
        }
        return prepared.size();
    }

    private TableDefinitionEntity writableDefinition(String name) {
        final TableDefinitionEntity definition = definitions.findById(name)
                .orElseThrow(() -> new TableNotFoundException("table '" + name + "' not found"));
        definition.requireModifiable();
        if (definition.getStatus() != TableStatus.ACTIVE) {
            throw new TableConflictException("table '" + name + "' is not active; it has no usable ClickHouse table");
        }
        // Every row write reaches ClickHouse through here, the pipeline write-back included: a relayout's writer hold
        // is honoured in one place (503 table_relayout_in_progress). Only an enrichment is ever relaid out, so a
        // source write skips the lookup.
        if (definition.getType() == TableType.ENRICHMENT) {
            relayouts.requireNotHeld(name);
        }
        return definition;
    }

    /**
     * Validates every row of {@code rows} for {@code definition} and returns them in their storage form — {@code rows}
     * itself when no value changed. The caller's maps are never mutated: a row is copied only on the first value
     * that actually differs, mirroring {@code EnrichWriteService.dropAbsentProvenance}.
     */
    private List<Map<String, Object>> prepare(TableDefinitionEntity definition, List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            throw new TableValidationException("at least one row is required");
        }
        // One catalog read per request yields every column set, so no check re-derives anything per row — a
        // batch may carry maxWriteBatchRows rows.
        final WriteColumns writeColumns = writeColumns(definition, true);
        List<Map<String, Object>> prepared = null;
        for (int index = 0; index < rows.size(); index++) {
            final Map<String, Object> row = rows.get(index);
            if (row == null || row.isEmpty()) {
                throw new TableValidationException("a row must declare at least one column");
            }
            for (final String column : row.keySet()) {
                if (!writeColumns.physical().contains(column)) {
                    throw new TableValidationException(
                            "unknown column '" + column + "' for table '" + definition.getName() + "'");
                }
            }
            for (final String column : writeColumns.nonNullable()) {
                if (row.get(column) == null) {
                    throw new TableValidationException("row " + (index + 1) + ": column '" + column
                            + "' is not nullable and must be present with a non-null value");
                }
            }
            // Check values only after the whole row's columns are known-good, so an unknown column keeps
            // reporting as such even when the row also carries a malformed value.
            final Map<String, Object> normalized = prepareRow(row, writeColumns.shapes(), index + 1);
            if (normalized != row) {
                if (prepared == null) {
                    prepared = new ArrayList<>(rows);
                }
                prepared.set(index, normalized);
            }
        }
        return prepared == null ? rows : prepared;
    }

    /**
     * Returns {@code row} with every value checked against its column's shape and rendered into its storage
     * form, or {@code row} itself when nothing changed.
     *
     * @param rowNumber the row's 1-based index within the batch, named in a rejection
     */
    private Map<String, Object> prepareRow(Map<String, Object> row, Map<String, ColumnShape> shapes, int rowNumber) {
        if (shapes.isEmpty()) {
            return row;
        }
        Map<String, Object> copy = null;
        for (final Map.Entry<String, Object> entry : row.entrySet()) {
            final ColumnShape shape = shapes.get(entry.getKey());
            if (shape == null) {
                continue;
            }
            final Object prepared = values.prepare(entry.getKey(), shape, entry.getValue(), rowNumber);
            if (prepared != entry.getValue()) {
                if (copy == null) {
                    copy = new LinkedHashMap<>(row);
                }
                copy.put(entry.getKey(), prepared);
            }
        }
        return copy == null ? row : copy;
    }

    /**
     * Physical ClickHouse columns of a materialized table: exposed {@code source_name}s plus an
     * enrichment's hidden grain key. Exposed for callers (e.g. the pipeline-scoped write-back) that need
     * to reconcile a row's columns against what the target actually carries.
     *
     * @throws TableNotFoundException if no such table exists
     */
    @Transactional(readOnly = true)
    public Set<String> physicalColumns(String name) {
        final TableDefinitionEntity definition = definitions.findById(name)
                .orElseThrow(() -> new TableNotFoundException("table '" + name + "' not found"));
        return physicalColumns(definition);
    }

    /**
     * As {@link #physicalColumns(String)}, for a caller that has already resolved the definition (the pipeline
     * write-back, which reads it once per request).
     */
    @Transactional(readOnly = true)
    public Set<String> physicalColumns(TableDefinitionEntity definition) {
        // resolveGrainKeyType=false: callers that only reconcile column names (the rule write-back, the
        // Class-1 materialize probe) must not pay for the source lookup that only the value checks need.
        return writeColumns(definition, false).physical();
    }

    /**
     * The column sets a write needs, from a single pass over the table's column mapping: every physical
     * column name, the subset that is not nullable, and the shape of every column whose values are checked.
     *
     * <p>An enrichment's grain key is physical but deliberately absent from the enrichment's own mapping, so
     * its declared type has to be resolved from the <em>source</em>, where the same column name is declared.
     * Without that lookup a grain key would skip the value checks — a {@code timestamp} one would keep failing
     * with the opaque ClickHouse error its normalization exists to remove, and an {@code integer} one would
     * wrap. That costs a second mapping read, so it is done only when the caller actually needs the shapes —
     * {@code resolveGrainKeyType}.
     *
     * <p>The grain key is always added to {@code nonNullable} regardless of the source column's own
     * {@code nullable} flag: {@code TableSchemaCreationService} renders it non-{@code Nullable} in DDL
     * unconditionally, so the write path must agree with what was actually materialized.
     */
    private WriteColumns writeColumns(TableDefinitionEntity definition, boolean resolveGrainKeyType) {
        final Set<String> physical = new HashSet<>();
        final Set<String> nonNullable = new HashSet<>();
        final Map<String, ColumnShape> shapes = new HashMap<>();
        for (final ColumnMappingEntity col : columns.findByTableNameOrderByOrdinal(definition.getName())) {
            physical.add(col.getSourceName());
            addShape(shapes, col);
            if (!col.isNullable()) {
                nonNullable.add(col.getSourceName());
            }
        }
        // Null until the enrichment's schema is defined, and always null on a source (DB check), so the
        // null test alone carries what the kind test plus the grain lookup used to.
        final String grainKey = definition.getGrainKey();
        if (grainKey != null) {
            physical.add(grainKey);
            nonNullable.add(grainKey);
            if (resolveGrainKeyType) {
                final ColumnMappingEntity sourceColumn = sourceColumn(definition.getSourceTable(), grainKey);
                if (sourceColumn != null) {
                    addShape(shapes, sourceColumn);
                }
            }
        }
        // A hidden alignment column is the same case as the grain key — physical, absent from the mapping, rendered
        // non-nullable, typed like its source column — and a row without it would land in the epoch partition,
        // matching no source row. An adopted one is a declared non-nullable column, already handled above.
        final String alignment = alignment(definition);
        if (alignment != null && physical.add(alignment)) {
            nonNullable.add(alignment);
            if (resolveGrainKeyType) {
                final ColumnMappingEntity sourceColumn = sourceColumn(definition.getSourceTable(), alignment);
                if (sourceColumn != null) {
                    addShape(shapes, sourceColumn);
                }
            }
        }
        return new WriteColumns(physical, nonNullable, shapes);
    }

    /**
     * {@code definition}'s alignment column, by the one rule every path uses ({@link EnrichmentAlignment#alignedColumn}),
     * or null. A partition column alone is not enough: it makes the enrichment aligned only when it is its source's.
     */
    private String alignment(TableDefinitionEntity definition) {
        if (definition.getType() != TableType.ENRICHMENT || definition.getPartitionColumn() == null
                || definition.getSourceTable() == null) {
            return null;
        }
        return EnrichmentAlignment.alignedColumn(definition, definitions.findById(definition.getSourceTable()).orElse(null));
    }

    private static void addShape(Map<String, ColumnShape> shapes, ColumnMappingEntity col) {
        final ColumnShape shape = WriteValuePreparer.shapeOf(col);
        if (shape != null) {
            shapes.put(col.getSourceName(), shape);
        }
    }

    /** {@code column}'s declaration on {@code sourceTable} (an enrichment's grain key), or null. */
    private ColumnMappingEntity sourceColumn(String sourceTable, String column) {
        if (sourceTable == null) {
            return null;
        }
        for (final ColumnMappingEntity col : columns.findByTableNameOrderByOrdinal(sourceTable)) {
            if (col.getSourceName().equals(column)) {
                return col;
            }
        }
        return null;
    }

    /**
     * A table's write-relevant column sets, resolved once per request.
     *
     * @param physical    every physical ClickHouse column name a row may key
     * @param nonNullable the subset that must be present on every row with a non-null value
     * @param shapes      the declared shape of every column whose values are checked, by physical name
     */
    private record WriteColumns(Set<String> physical, Set<String> nonNullable, Map<String, ColumnShape> shapes) {
    }
}
