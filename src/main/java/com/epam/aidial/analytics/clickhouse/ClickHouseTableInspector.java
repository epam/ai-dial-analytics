package com.epam.aidial.analytics.clickhouse;

import com.clickhouse.client.api.query.GenericRecord;
import com.epam.aidial.analytics.exception.ClickHouseQueryException;
import com.epam.aidial.analytics.exception.ClickHouseUnavailableException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads what the ClickHouse server itself knows about the tables ADAS manages — the facts the catalog does not
 * hold, because a migration rather than the API set them: a table's full engine clause (its TTL and settings),
 * its skip indexes, and the current database's engine. Runs on the writer credential, like the DDL it informs;
 * the read-only query client stays out of DDL decisions.
 *
 * <p>Every value is bound as a ClickHouse query parameter, never concatenated into the statement.
 */
@Component
@RequiredArgsConstructor
public class ClickHouseTableInspector {

    private final ClickHouseWriterClient writer;
    private final ClickHouseSpans clickHouseSpans;

    /** {@code table}'s {@code engine_full} in the current database — engine, keys, TTL and settings — if it exists. */
    public Optional<String> engineFull(String table) {
        final List<GenericRecord> rows = query(
                "SELECT engine_full FROM system.tables WHERE database = currentDatabase() AND name = {table:String}",
                Map.of("table", table));
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.getFirst().getString("engine_full"));
    }

    /** Whether {@code table} exists in the current database. */
    public boolean exists(String table) {
        return engineFull(table).isPresent();
    }

    /** The engine of the current database: {@code Atomic}, {@code Replicated}, {@code Ordinary}, … */
    public String databaseEngine() {
        final List<GenericRecord> rows = query(
                "SELECT engine FROM system.databases WHERE name = currentDatabase()", Map.of());
        return rows.isEmpty() ? "" : rows.getFirst().getString("engine");
    }

    /**
     * One skip index of a table: its name, its expression as the server renders it, and its type without
     * parameters ({@code bloom_filter}, {@code minmax}, …).
     *
     * <p>Judged by expression, not name: a {@code RENAME COLUMN} rewrites the expression of an index over the column
     * and keeps the index's name, so {@code idx_<old>} goes on indexing the renamed column.
     */
    public record SkipIndex(String name, String expr, String type) {

        /** Whether this index is over exactly {@code column} — the bare column, quoted or not. */
        public boolean covers(String column) {
            return expr.equals(column) || expr.equals("`" + column + "`");
        }
    }

    /** {@code table}'s skip indexes. */
    public List<SkipIndex> skipIndexes(String table) {
        final List<SkipIndex> indexes = new ArrayList<>();
        for (final GenericRecord row : query("SELECT name, expr, type FROM system.data_skipping_indices "
                + "WHERE database = currentDatabase() AND table = {table:String}", Map.of("table", table))) {
            indexes.add(new SkipIndex(row.getString("name"), row.getString("expr"), row.getString("type")));
        }
        return indexes;
    }

    /**
     * Runs a read on the writer credential.
     *
     * @throws ClickHouseUnavailableException if ClickHouse cannot be reached
     * @throws ClickHouseQueryException       if ClickHouse rejects the statement
     */
    public List<GenericRecord> query(String sql, Map<String, Object> params) {
        return clickHouseSpans.query(null, () -> {
            try {
                return writer.client().queryAll(sql, params);
            } catch (Exception ex) {
                if (ClickHouseFailures.isUnavailable(ex)) {
                    throw new ClickHouseUnavailableException("ClickHouse is unreachable", ex);
                }
                throw new ClickHouseQueryException("ClickHouse rejected a system catalog read", ex);
            }
        });
    }
}
