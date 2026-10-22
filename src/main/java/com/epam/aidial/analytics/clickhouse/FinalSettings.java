package com.epam.aidial.analytics.clickhouse;

import com.clickhouse.client.api.ClientConfigProperties;
import com.clickhouse.client.api.command.CommandSettings;
import com.clickhouse.client.api.query.QuerySettings;

import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The deduplication settings ADAS-issued statements run under. There are two sets, one per kind of read.
 *
 * <ul>
 *   <li>{@link #pipeline pipeline} reads — the enrichment scan, group fetch, the Class-1 materialize and
 *       aggregate-refresh {@code INSERT … SELECT}, the aggregate preview — run under {@code final = 1}, so a
 *       {@code ReplacingMergeTree} read returns the latest version per key whether or not background merges
 *       have run. It is not a caller's choice: their exactly-once and window guarantees rest on it.
 *   <li>{@link #userRead user} reads — the structured query, SQL and Flight surfaces — carry no {@code final}
 *       setting at all: whether each entity is read {@code FINAL} is the query's own choice (or the
 *       {@code query.force-final} flag's) and is rendered into the SQL, per table reference, by the query
 *       builder (see {@code FinalDecision}).
 * </ul>
 *
 * <p>Both add {@code do_not_merge_across_partitions_select_final = 1} when the caller says the statement is
 * <em>partition-local</em>, so that deduplication merges inside each partition rather than across all of
 * them. For a user read that means every entity it reads {@code FINAL} is partition-local; one read raw is
 * not consulted, since the switch affects only tables read under {@code FINAL}.
 *
 * <p>The second setting is a cost decision with a correctness precondition. Under {@code final = 1} alone
 * ClickHouse must assume two versions of one key may sit in different partitions, so it merges every
 * partition of the table before applying a filter — and from 26.3 ({@code defer_partition_pruning_after_final},
 * default on) it stops using the partition and MinMax indexes altogether for a table whose partition column is
 * not part of its sorting key: a one-row lookup of a heavy column on such a table reads a granule from every
 * part (gigabytes for one row of {@code dial_usage_log_payload}). Told that versions never cross
 * partitions, it prunes to the partition that holds the row (megabytes for the same lookup). The precondition —
 * every version of a key lands in one partition — is what {@code partitionLocal} asserts, and it is decided
 * per entity from the catalog by {@code PartitionLocality}, never inferred here; a statement whose entity
 * cannot vouch for it runs with {@code final = 1} alone, slower and equally correct.
 *
 * <p>This setting rather than 26.3's own {@code defer_partition_pruning_after_final = 0} because ClickHouse
 * rejects a setting it does not know, and the supported floor is 25.3: the older switch is accepted everywhere
 * and has the same effect on 26.3.
 *
 * <p>Pipeline statements also pin {@code transform_null_in = 0}, the server default. The aggregate
 * recompute's exactness rests on it: an enrichment restricted to the dirty keys turns a row of another key
 * into an unmatched one, which reads NULL and must stay outside the dirty-key {@code IN} — which holds only
 * while a NULL never satisfies {@code IN}. Pinned here so a deployment's settings profile cannot change it.
 *
 * <p>Both executors build their settings here because both must agree: a name spelled in one and not the
 * other is how a pipeline read comes to dedupe differently from the pipeline write over the same rows. The
 * names live here and nowhere else.
 */
public final class FinalSettings {

    /** ClickHouse's query-level FINAL switch. */
    public static final String FINAL = "final";

    /** ClickHouse's "merge FINAL inside each partition only" switch. */
    public static final String NO_MERGE_ACROSS_PARTITIONS = "do_not_merge_across_partitions_select_final";

    /** ClickHouse's "a NULL satisfies IN" switch; pinned off on pipeline statements. */
    public static final String TRANSFORM_NULL_IN = "transform_null_in";

    /** The key {@link QuerySettings#serverSetting} files {@link #TRANSFORM_NULL_IN} under. */
    public static final String TRANSFORM_NULL_IN_KEY = ClientConfigProperties.serverSetting(TRANSFORM_NULL_IN);

    /** The key {@link QuerySettings#serverSetting} files {@link #FINAL} under in {@code getAllSettings()}. */
    public static final String FINAL_KEY = ClientConfigProperties.serverSetting(FINAL);

    /** The key {@link QuerySettings#serverSetting} files {@link #NO_MERGE_ACROSS_PARTITIONS} under. */
    public static final String NO_MERGE_ACROSS_PARTITIONS_KEY =
            ClientConfigProperties.serverSetting(NO_MERGE_ACROSS_PARTITIONS);

    private FinalSettings() {
    }

    /**
     * Fresh pipeline-read settings carrying {@code final = 1} and, when {@code partitionLocal}, the
     * per-partition merge switch. Callers merge their own settings over the result, never the other way
     * round, so the deduplication always survives.
     */
    public static QuerySettings pipeline(boolean partitionLocal) {
        return pipeline(new QuerySettings(), partitionLocal);
    }

    /**
     * {@code settings} with the pipeline deduplication added, returned as the type it came in as — a
     * {@link CommandSettings} for the statements that are not queries (the Class-1 materialize and
     * aggregate-refresh {@code INSERT … SELECT}), a plain {@link QuerySettings} for reads. Generic because the
     * driver's fluent setter is declared on the base type and answers with it, so chaining on a
     * {@link CommandSettings} would not typecheck; mutating and returning the argument keeps one method for
     * both.
     */
    public static <T extends QuerySettings> T pipeline(T settings, boolean partitionLocal) {
        settings.serverSetting(FINAL, "1");
        settings.serverSetting(TRANSFORM_NULL_IN, "0");
        if (partitionLocal) {
            settings.serverSetting(NO_MERGE_ACROSS_PARTITIONS, "1");
        }
        return settings;
    }

    /**
     * The settings a pipeline read runs under: the pipeline deduplication, with {@code extra} (a budget, a query
     * id) merged over it so the caller adds to the deduplication rather than replacing it. The one place the
     * merge happens, so the statement a preview prints ({@link #render}) and the one the executor runs cannot
     * come to carry different settings.
     */
    public static QuerySettings pipelineRead(QuerySettings extra, boolean partitionLocal) {
        return mergedOver(pipeline(partitionLocal), extra);
    }

    /**
     * {@code extra} merged over {@code base}, which is mutated and returned: an option both carry takes
     * {@code extra}'s value, and every option only {@code base} carries survives. The one merge every executor
     * applies to a base from this class, so the deduplication settings survive whatever the caller adds.
     */
    public static QuerySettings mergedOver(QuerySettings base, QuerySettings extra) {
        extra.getAllSettings().forEach(base::setOption);
        return base;
    }

    /**
     * {@code sql} followed by a {@code SETTINGS} clause naming every server setting of
     * {@link #pipelineRead pipelineRead(extra, partitionLocal)}, sorted by name, so the statement run by hand reads
     * what the executor read under the same limits. Client options — the query id among them — are not server
     * settings and are not rendered, and nor can settings a server profile applies be.
     */
    public static String render(String sql, QuerySettings extra, boolean partitionLocal) {
        final String prefix = ClientConfigProperties.serverSetting("");
        final Map<String, Object> server = new TreeMap<>();
        pipelineRead(extra, partitionLocal).getAllSettings().forEach((key, value) -> {
            if (key.startsWith(prefix)) {
                server.put(key.substring(prefix.length()), value);
            }
        });
        if (server.isEmpty()) {
            return sql;
        }
        return sql + " SETTINGS " + server.entrySet().stream()
                .map(entry -> entry.getKey() + " = " + literal(entry.getValue()))
                .collect(Collectors.joining(", "));
    }

    private static String literal(Object value) {
        final String text = String.valueOf(value);
        return text.matches("-?\\d+") ? text : "'" + text.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    /**
     * Fresh user-read settings: no {@code final} setting — the statement renders {@code FINAL} where it reads
     * final — and the per-partition merge switch when {@code partitionLocal}, which the caller asserts only
     * when the statement reads at least one entity final and every such entity is partition-local.
     */
    public static QuerySettings userRead(boolean partitionLocal) {
        final QuerySettings settings = new QuerySettings();
        if (partitionLocal) {
            settings.serverSetting(NO_MERGE_ACROSS_PARTITIONS, "1");
        }
        return settings;
    }
}
