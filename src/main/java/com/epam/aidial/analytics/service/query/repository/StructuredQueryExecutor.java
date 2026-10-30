package com.epam.aidial.analytics.service.query.repository;

import com.clickhouse.client.api.data_formats.internal.BinaryStreamReader;
import com.clickhouse.client.api.query.GenericRecord;
import com.clickhouse.client.api.query.QuerySettings;
import com.epam.aidial.analytics.clickhouse.ClickHouseBudget;
import com.epam.aidial.analytics.clickhouse.ClickHouseClientWrapper;
import com.epam.aidial.analytics.clickhouse.FinalSettings;
import com.epam.aidial.analytics.clickhouse.StatementTag;
import com.epam.aidial.analytics.exception.QueryValidationException;
import com.epam.aidial.analytics.observability.SqlLog;
import com.epam.aidial.analytics.observability.trace.LogExecution;
import com.epam.aidial.analytics.service.query.QueryEntityModel;
import com.epam.aidial.analytics.service.query.model.OffsetPage;
import com.epam.aidial.analytics.service.query.model.QueryMode;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.rebuild.ComputedFieldCompleter;
import com.epam.aidial.analytics.service.query.translate.StructuredQueryBuilder;
import com.epam.aidial.analytics.service.security.RowPolicyFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SelectQuery;
import org.jooq.conf.ParamType;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Entity-agnostic execution engine. It validates the request, builds the jOOQ query model via
 * {@link StructuredQueryBuilder} on the connection-less CLICKHOUSE render context (joining only the
 * enrichments a query references), renders it to a self-contained SQL string (parameters inlined),
 * and runs it through the {@link ClickHouseClientWrapper}. Rows come back as ordered field-name →
 * value maps; a total count is computed (via a separate rendered {@code count(*)}) only for row-mode
 * offset paging with {@code include_total}.
 *
 * <p>jOOQ is render-only here — it never opens a connection. Execution is entirely the ClickHouse HTTP
 * client's job.
 *
 * <p>Two kinds of read pass through here, and they deduplicate differently (see {@link FinalSettings}).
 * A <b>user read</b> — {@link #execute} (the row query and its {@code count(*)} total) and
 * {@link #executeComposite} — carries no {@code final} setting: the builder has already rendered
 * {@code FINAL} on every table the query reads final (see {@code FinalDecision}), and nothing on one it
 * reads raw. A <b>pipeline read</b> — every {@code executeScan} overload — runs under {@code final = 1},
 * so its reads return the deduplicated, latest-version-wins view of {@code ReplacingMergeTree} data whatever
 * any flag says; the setting is a no-op on engines that do not support {@code FINAL}. Either kind carries
 * {@code do_not_merge_across_partitions_select_final = 1} when what it reads final is partition-local
 * ({@link QueryEntityModel#partitionLocal()}), so ClickHouse deduplicates inside each partition and keeps
 * pruning partitions under {@code FINAL} — the difference between a one-row heavy-column lookup reading
 * 4 MB and reading every part of the table. An entry point handed no model runs cross-partition, which is
 * never wrong.
 *
 * <p><b>Row policies ride on user reads only, and last.</b> The two user-read entry points hand their merged
 * settings to {@link RowPolicyFilter#apply} as the final step, so the caller's {@code additional_table_filters}
 * cannot be replaced by anything merged before it, and the {@code count(*)} total — run under a copy of the
 * same settings — is filtered exactly as its page is. The pipeline overloads never carry it: they read under
 * pipeline settings, and the paths that reach them refuse a row-confined caller instead.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@LogExecution
public class StructuredQueryExecutor {

    private final StructuredQueryBuilder queryBuilder;
    private final DSLContext renderContext;
    private final ClickHouseClientWrapper clickHouse;
    private final RowPolicyFilter rowPolicies;
    /** Completes the computed fields a statement projects. */
    private final ComputedFieldCompleter completer;

    /**
     * Translates and runs {@code query} against the resolved {@code model}.
     *
     * @throws QueryValidationException if the query is null, targets a different entity, or uses an
     *     unsupported field/function/feature
     */
    public QueryResultPage execute(QueryEntityModel model, StructuredQuery query) {
        return execute(model, query, StructuredQueryBuilder.MAX_LIMIT, new QuerySettings());
    }

    /**
     * As {@link #execute(QueryEntityModel, StructuredQuery)} but with {@code maxLimit} as the caller's own
     * row ceiling in place of the shared {@link StructuredQueryBuilder#MAX_LIMIT}, and with caller-supplied
     * settings <em>merged over</em> the user-read {@link FinalSettings}. Both executed statements (the row query and the {@code count(*)} total) carry them,
     * since a caller pays for both; a wall-clock ceiling among them is shared across the two rather than
     * granted twice (see {@link ClickHouseBudget#remainingTime}).
     *
     * <p>Opt-in per call site by design. The one caller today is {@code StructuredQueryService}, which
     * passes the Flight transport's own {@code flight.max-rows} where it applies and attaches the
     * {@code query.budget.*} resource ceilings, because the cost of a caller-supplied query is bounded by
     * nothing the caller supplies. The enrichment scan, group-fetch, materialize and aggregate-refresh
     * callers attach {@code pipeline.budget.*} to the
     * {@link #executeScan(SelectQuery, QuerySettings, QueryEntityModel)} overload for the same reason: their
     * own row limits bound what they return, not what they read.
     */
    public QueryResultPage execute(
            QueryEntityModel model, StructuredQuery query, int maxLimit, QuerySettings extra) {
        if (query == null) {
            throw new QueryValidationException("query must not be null");
        }
        if (!model.entity().equals(query.entity())) {
            throw new QueryValidationException("the " + model.entity() + " query model only supports entity '"
                    + model.entity() + "', got '" + query.entity() + "'");
        }

        // readsFinal() may throw IllegalFinalException; build() validates fields/functions/features and may
        // throw QueryValidationException.
        final boolean partitionLocal = model.partitionLocal() && queryBuilder.readsFinal(model, query);
        final QuerySettings settings = rowPolicies.apply(FinalSettings.mergedOver(FinalSettings.userRead(partitionLocal), extra));
        final SelectQuery<Record> select = queryBuilder.build(renderContext, model, query, maxLimit);
        final String sql = select.getSQL(ParamType.INLINED);
        SqlLog.LOG.debug("Executing structured {} query: {}", model.entity(), sql);
        final long startedAt = System.nanoTime();
        final List<Map<String, Object>> rows = materialize(sql, select, settings);
        final Long totalCount = totalCount(model, query, settings, startedAt);
        // After the total, not before: the count is part of the read the caller asked for and fails it when it times
        // out, while a rebuild short of time only degrades its rows to partial — so the count gets the budget first.
        complete(select, rows, settings, startedAt, ComputedFieldCompleter.Surface.QUERY, null);
        log.debug("Structured {} query returned {} row(s), totalCount={}", model.entity(), rows.size(), totalCount);
        return new QueryResultPage(outputColumns(select), rows, totalCount);
    }

    /**
     * Runs everything {@link #execute(QueryEntityModel, StructuredQuery, int, QuerySettings)} does up to —
     * and excluding — the ClickHouse round trip: the entity-coherence check and the full
     * {@link StructuredQueryBuilder} pass (field allowlist, function catalog, mode coherence, window
     * placement, limit governance). The built jOOQ model is deliberately discarded, so a query this method
     * accepts is one execution would not reject, and ClickHouse is never contacted.
     *
     * <p>Kept beside {@code execute} rather than in the caller so the two cannot drift: a governance rule
     * added to the builder is picked up by both, and there is no second copy of the pre-flight sequence.
     *
     * @throws QueryValidationException if the query is null, targets a different entity, or uses an
     *     unsupported field/function/feature
     */
    public void validate(QueryEntityModel model, StructuredQuery query, int maxLimit) {
        if (query == null) {
            throw new QueryValidationException("query must not be null");
        }
        if (!model.entity().equals(query.entity())) {
            throw new QueryValidationException("the " + model.entity() + " query model only supports entity '"
                    + model.entity() + "', got '" + query.entity() + "'");
        }
        queryBuilder.build(renderContext, model, query, maxLimit);
    }

    /**
     * The ClickHouse client materializes {@code Array(...)} values as its internal
     * {@link BinaryStreamReader.ArrayValue} wrapper, which Jackson would serialize by its getters
     * ({@code {"array": [...], "arrayOfObjects": [...]}}) instead of as a JSON array. Unwrap it to a
     * plain {@link List} so array values serialize as the JSON arrays the API contract promises.
     *
     * <p>The unwrap is <em>recursive</em>: an {@code object} ({@code JSON}) cell surfaces as a
     * {@link Map} whose nested members carry the same wrapper, so a claims blob like
     * {@code {"roles": ["admin"]}} would otherwise serialize with the wrapper's getters in place of
     * the inner array. Containers are rebuilt only when a nested value actually changes, so scalar
     * cells (the overwhelming majority) cost one type check and no allocation.
     *
     * <p>An {@code Enum8}/{@code Enum16} cell arrives as {@code BinaryStreamReader.EnumValue}, which
     * <b>extends {@link Number}</b> — so Jackson serializes it as a JSON <em>number</em> and writes its
     * {@code toString()} (the value's name) unquoted, producing {@code {"status":running}}: not merely
     * an odd rendering but <b>malformed JSON</b> that a strict client cannot parse. Unwrapping it to
     * {@code getName()} yields the plain string the contract promises. The Arrow and Flight paths are
     * unaffected — they coerce a string column through {@code value.toString()}, which already reads
     * the name — so this normalization is what keeps the JSON surfaces in step with them.
     *
     * <p>{@code null} is an expected input, not an anomaly: a JSON cell routinely carries null members
     * ({@code {"sub": null}}) and null array elements ({@code ["a", null]}), and a pattern switch throws
     * {@link NullPointerException} on a null selector unless it declares a {@code case null} label.
     * Nulls pass through unchanged so they serialize as JSON nulls.
     */
    private static Object normalize(Object value) {
        return switch (value) {
            case null -> null;
            case BinaryStreamReader.ArrayValue array -> normalizeList(array.asList());
            case BinaryStreamReader.EnumValue enumValue -> enumValue.getName();
            case Map<?, ?> map -> normalizeMap(map);
            case List<?> list -> normalizeList(list);
            default -> value;
        };
    }

    private static Object normalizeList(List<?> list) {
        List<Object> copy = null;
        for (int i = 0; i < list.size(); i++) {
            final Object item = list.get(i);
            final Object normalized = normalize(item);
            if (normalized != item && copy == null) {
                copy = new ArrayList<>(list);
            }
            if (copy != null) {
                copy.set(i, normalized);
            }
        }
        return copy == null ? list : copy;
    }

    private static Object normalizeMap(Map<?, ?> map) {
        Map<Object, Object> copy = null;
        for (final Map.Entry<?, ?> entry : map.entrySet()) {
            final Object normalized = normalize(entry.getValue());
            if (normalized != entry.getValue() && copy == null) {
                copy = new LinkedHashMap<>(map);
            }
            if (copy != null) {
                copy.put(entry.getKey(), normalized);
            }
        }
        return copy == null ? map : copy;
    }

    /**
     * Runs an already-built composite SQL statement (joins, derived tables, subqueries — see
     * {@code CompositeQueryBuilder}) under {@code extra} merged over the user-read {@link FinalSettings}, and
     * returns its rows as a page with no total count (SQL has no total-count opt-in). The statement was
     * rendered by the same builder every block of it would use on its own, so the materialization is the
     * shared one too. Cross-partition: a composite plan whose every entity is partition-local goes through
     * {@link #executeComposite(SelectQuery, QuerySettings, boolean)}.
     */
    public QueryResultPage executeComposite(SelectQuery<Record> select, QuerySettings extra) {
        return executeComposite(select, extra, false);
    }

    /**
     * As {@link #executeComposite(SelectQuery, QuerySettings)}, deduplicating inside each partition when
     * {@code partitionLocal} — which the caller asserts only when the plan reads at least one entity final and
     * <em>every</em> entity it reads final is partition-local, since one statement carries one setting.
     */
    public QueryResultPage executeComposite(SelectQuery<Record> select, QuerySettings extra, boolean partitionLocal) {
        // Translation refuses a computed field anywhere in a composite plan, since an enclosing block would read its
        // stand-in; this asserts it, rather than let a raw tuple reach the caller.
        if (!ComputedFieldCompleter.slots(select.getSelect()).isEmpty()) {
            throw new IllegalStateException("a composite statement projects a computed field's stand-in");
        }
        final QuerySettings settings = rowPolicies.apply(FinalSettings.mergedOver(FinalSettings.userRead(partitionLocal), extra));
        return new QueryResultPage(outputColumns(select), run(select, settings, "composite", ComputedFieldCompleter.Surface.QUERY, null), null);
    }

    /**
     * Runs a pre-built scan-mode {@link SelectQuery} (source-only keyset seek, assembled by the
     * enrichment scan) under {@code final = 1} and returns the rows as ordered field-name → value
     * maps, sharing {@link #execute}'s materialization so every projected column is present and
     * array cells are normalized. Cross-partition, having no model to consult.
     */
    public List<Map<String, Object>> executeScan(SelectQuery<Record> select) {
        return executeScan(select, new QuerySettings());
    }

    /**
     * As {@link #executeScan(SelectQuery, QuerySettings)} for a statement over {@code model}'s entity, so the
     * read deduplicates inside each partition when the model is partition-local. Every pipeline read holds
     * the model it rendered its joins from; one that is tagged ({@code PipelineReadBudget}, the aggregate preview) goes
     * through {@link #executeScan(SelectQuery, QuerySettings, QueryEntityModel, StatementTag)}.
     */
    public List<Map<String, Object>> executeScan(SelectQuery<Record> select, QuerySettings extra, QueryEntityModel model) {
        return scan(select, extra, model.partitionLocal(), null);
    }

    /**
     * As {@link #executeScan(SelectQuery, QuerySettings, QueryEntityModel)} for a statement run on a pipeline's behalf
     * under {@code tag} (whose id {@code extra} already carries): every request-body rebuild statement the read's
     * computed fields cost gets a fresh {@code adas-{pipeline}-{stage}-rebuild-{uuid}} id of its own, so it can be
     * attributed to the pipeline in {@code system.query_log}. The thread's MDC keeps the triggering statement's id, which
     * is the one the call's outcome line describes.
     */
    public List<Map<String, Object>> executeScan(SelectQuery<Record> select, QuerySettings extra, QueryEntityModel model,
            StatementTag tag) {
        return scan(select, extra, model.partitionLocal(), new StatementTag(tag.pipeline(), tag.stage() + "-rebuild"));
    }

    /**
     * As {@link #executeScan(SelectQuery)}, with caller-supplied settings <em>merged over</em>
     * the pipeline {@link FinalSettings} so the caller adds to the deduplication rather than replacing it —
     * a scan that silently dropped {@code final = 1} would change its numbers, not just its cost.
     * Cross-partition: prefer {@link #executeScan(SelectQuery, QuerySettings, QueryEntityModel)} wherever a
     * model is in hand.
     *
     * <p>No production caller is left on this overload or on {@link #executeScan(SelectQuery)}: every
     * pipeline read — the aggregate dry-run preview under its own {@code aggregate.preview.*}, and the
     * refresh probe, enrichment scan, group-fetch and materialize paths under the shared
     * {@code pipeline.budget.*} — supplies settings <em>and</em> the model it rendered against, through
     * {@link #executeScan(SelectQuery, QuerySettings, QueryEntityModel)}. A new pipeline-driven read belongs
     * there, not here: a model-less read is unbudgeted or cross-partition or both, and either is paid for by
     * every other reader on the node rather than by the caller that asked for it. The two model-less
     * overloads survive as the escape hatch for a statement that genuinely has no entity in hand.
     */
    public List<Map<String, Object>> executeScan(SelectQuery<Record> select, QuerySettings extra) {
        return scan(select, extra, false, null);
    }

    private List<Map<String, Object>> scan(SelectQuery<Record> select, QuerySettings extra, boolean partitionLocal, StatementTag rebuild) {
        return run(select, FinalSettings.pipelineRead(extra, partitionLocal), "scan", ComputedFieldCompleter.Surface.PIPELINE, rebuild);
    }

    private List<Map<String, Object>> run(SelectQuery<Record> select, QuerySettings settings, String kind,
            ComputedFieldCompleter.Surface surface, StatementTag rebuild) {
        final String sql = select.getSQL(ParamType.INLINED);
        SqlLog.LOG.debug("Executing {} query: {}", kind, sql);
        final long startedAt = System.nanoTime();
        final List<Map<String, Object>> rows = materialize(sql, select, settings);
        complete(select, rows, settings, startedAt, surface, rebuild);
        log.debug("{} query returned {} row(s)", kind, rows.size());
        return rows;
    }

    /**
     * Runs {@code sql} under {@code settings} (always the pipeline or user-read {@link FinalSettings} plus whatever the call
     * site merged over it) and materializes the result as ordered field-name → value maps. The ClickHouse
     * client's {@code getValues()} omits columns whose value is null in a given row, which would silently
     * drop a projected column (e.g. an unmatched LEFT-JOINed enrichment column) from that row's map — so
     * every projected column is seeded to null and the record's actual (non-null,
     * {@link #normalize normalized}) values overwrite the seeds. (Null cells survive serialization via the
     * JsonMapper's ALWAYS content inclusion.)
     *
     * <p>A computed field's stand-in cells are left as they are here and replaced afterwards by {@link #complete}.
     */
    private List<Map<String, Object>> materialize(String sql, SelectQuery<Record> select, QuerySettings settings) {
        final List<String> outputColumns = outputColumns(select);
        final List<Map<String, Object>> rows = new ArrayList<>();
        for (final GenericRecord record : clickHouse.query(sql, settings)) {
            final Map<String, Object> row = new LinkedHashMap<>(outputColumns.size());
            outputColumns.forEach(col -> row.put(col, null));
            record.getValues().forEach((col, value) -> row.put(col, normalize(value)));
            rows.add(row);
        }
        return rows;
    }

    /**
     * Completes the computed fields {@code select} projects in {@code rows} (see {@link ComputedFieldCompleter}). Every
     * read the service runs passes here once its own statements are done, which is why a computed field is completed
     * here and nowhere else. Each rebuild statement runs under {@code settings} with what is left of their wall-clock
     * ceiling since {@code startedAt}, and is not issued once less than a second is left
     * ({@link ClickHouseBudget#remainingTimeIfAny}): the rebuild shares the read's ceiling rather than earning a fresh
     * floor per statement. The settings never carry the triggering statement's query id; when {@code rebuild} is given
     * (a pipeline read), each statement carries a fresh id minted from it instead.
     */
    private void complete(SelectQuery<Record> select, List<Map<String, Object>> rows, QuerySettings settings, long startedAt,
            ComputedFieldCompleter.Surface surface, StatementTag rebuild) {
        final List<ComputedFieldCompleter.Slot> slots = ComputedFieldCompleter.slots(select.getSelect());
        if (!slots.isEmpty()) {
            completer.complete(slots, rows, () -> {
                final QuerySettings left = ClickHouseBudget.remainingTimeIfAny(settings, Duration.ofNanos(System.nanoTime() - startedAt));
                if (left == null || rebuild == null) {
                    return left;
                }
                // remainingTime hands back the caller's own object when there was nothing to reduce or drop; never tag that.
                final QuerySettings tagged = left == settings ? FinalSettings.mergedOver(new QuerySettings(), left) : left;
                tagged.setQueryId(rebuild.mint());
                return tagged;
            }, surface);
        }
    }

    /** The statement's projected column names, in order — the key set {@link #materialize} gives every row. */
    private static List<String> outputColumns(SelectQuery<Record> select) {
        return select.getSelect().stream().map(Field::getName).toList();
    }

    /**
     * The {@code count(*)} total, run under what is left of {@code settings}' wall-clock ceiling after the
     * row query took {@code startedAt} to now. The ceiling bounds what the caller waits, not what one
     * statement takes: granting this second statement a fresh one would let a query that opts into
     * {@code include_total} occupy twice the configured budget and hand the caller the very
     * past-its-own-timeout failure the budget exists to remove.
     */
    private Long totalCount(
            QueryEntityModel model, StructuredQuery query, QuerySettings settings, long startedAt) {
        // Total count is meaningful for offset row paging; group counts in aggregate mode are out of scope.
        if (query.mode() == QueryMode.AGGREGATE) {
            return null;
        }
        if (query.page() instanceof OffsetPage offset && offset.includeTotal()) {
            final SelectQuery<Record> countQuery = queryBuilder.buildCount(renderContext, model, query);
            final String countSql = countQuery.getSQL(ParamType.INLINED);
            final Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
            final List<GenericRecord> result =
                    clickHouse.query(countSql, ClickHouseBudget.remainingTime(settings, elapsed));
            return result.isEmpty() ? 0L : firstNumericValue(result.getFirst());
        }
        return null;
    }

    private static long firstNumericValue(GenericRecord record) {
        final Collection<Object> values = record.getValues().values();
        for (final Object value : values) {
            if (value instanceof Number number) {
                return number.longValue();
            }
        }
        return 0L;
    }
}
