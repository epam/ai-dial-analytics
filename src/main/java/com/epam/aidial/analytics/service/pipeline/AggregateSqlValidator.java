package com.epam.aidial.analytics.service.pipeline;

import com.epam.aidial.analytics.exception.PipelineValidationException;
import com.epam.aidial.analytics.service.enrichment.support.EntityFieldResolver;
import com.epam.aidial.analytics.service.enrichment.support.EntityFields;
import com.epam.aidial.analytics.service.enrichment.support.SensitiveProjectionGuard;
import com.epam.aidial.analytics.service.enrichment.validation.ExprColumnCollector;
import com.epam.aidial.analytics.service.pipeline.model.TruncUnit;
import com.epam.aidial.analytics.service.pipeline.preview.PreviewColumn;
import com.epam.aidial.analytics.service.pipeline.preview.PreviewColumnRole;
import com.epam.aidial.analytics.service.query.ArrayValuedFunctions;
import com.epam.aidial.analytics.service.query.QueryFunctionCatalog;
import com.epam.aidial.analytics.service.query.model.ArrayExpr;
import com.epam.aidial.analytics.service.query.model.CaseBranch;
import com.epam.aidial.analytics.service.query.model.CaseExpr;
import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.ComparisonOp;
import com.epam.aidial.analytics.service.query.model.Expr;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.query.model.FnExpr;
import com.epam.aidial.analytics.service.query.model.LambdaExpr;
import com.epam.aidial.analytics.service.query.model.LogicalNode;
import com.epam.aidial.analytics.service.query.model.OutputColumn;
import com.epam.aidial.analytics.service.query.model.QueryMode;
import com.epam.aidial.analytics.service.query.model.RelationColumn;
import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.model.SubqueryNode;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.ValueType;
import com.epam.aidial.analytics.service.query.model.WindowExpr;
import com.epam.aidial.analytics.service.query.model.WindowOrder;
import com.epam.aidial.analytics.service.query.sql.QueryReferences;
import com.epam.aidial.analytics.service.query.sql.ResultColumn;
import com.epam.aidial.analytics.service.query.sql.ResultColumnKind;
import com.epam.aidial.analytics.service.query.sql.ResultColumns;
import com.epam.aidial.analytics.service.query.sql.SqlInsertPlan;
import com.epam.aidial.analytics.service.query.sql.SqlQueryFrontEnd;
import com.epam.aidial.analytics.service.table.ColumnMappingEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import com.epam.aidial.analytics.service.table.TableType;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import com.epam.aidial.analytics.web.dto.query.QueryFunctionDto;
import com.epam.aidial.analytics.web.dto.query.QueryFunctionGroup;
import com.epam.aidial.analytics.web.dto.query.QueryFunctionReturnType;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The governance gate of an aggregate declaration in <b>SQL mode</b> — one {@code INSERT INTO <target> (<columns>)
 * SELECT …} statement — run over the statement as {@link AggregateSqlReader#require} planned it. Reached only through
 * {@link AggregateSpecValidator#validate}'s SQL branch, which has already matched the statement's target and input to the
 * pipeline's and applied the shared input/target checks, so arming, preview and every refresh run it.
 *
 * <p>Every rule here is a form-path rule ({@link AggregateSpecValidator}) restated over the planned block's select
 * expressions, which is the only place an SQL declaration's keys and measures exist: a key is the select item at a
 * {@code GROUP BY} position, bound to the target column at the same position of the column list, and every other
 * select item is a measure. In order:
 *
 * <ol>
 *   <li><b>Statement shape</b> — the column list names every target column but {@code _updated_at}, once each, and is
 *       as long as the select list.</li>
 *   <li><b>Sources</b> — the input read once, nothing else read, no join, set operation or subquery, no user-written
 *       {@code FINAL}. The front end refuses most of these already; they are re-checked on the plan so the gate does
 *       not depend on which layer happened to catch them.</li>
 *   <li><b>Block shape</b> — one aggregation with a {@code GROUP BY}, and no {@code HAVING}, {@code DISTINCT},
 *       {@code ORDER BY}, {@code LIMIT}/{@code OFFSET} or window function.</li>
 *   <li><b>Determinism</b> — no catalog function marked non-deterministic in the select list or the {@code WHERE}
 *       (the {@code GROUP BY} names select items, so it is covered by the select list).</li>
 *   <li><b>Entitlement</b> — the sensitive gate over every column the block reads, before any structural message about
 *       one of them.</li>
 *   <li><b>Group keys</b> — the key set equals the target's {@code ordering_key} as a set; each key reads the input
 *       row, cannot be NULL, reads no enrichment column that cannot represent absence, has its target column's exact
 *       type (a bucket: a temporal type wide enough for its unit), and at most one is a time bucket, stepping by one
 *       unit. No key calls a row-expanding function ({@code array_join}).</li>
 *   <li><b>Measures</b> — the form's type, fraction, nullability and array-element rules.</li>
 *   <li><b>Lineage, grants and enrichment positions</b> — as on the form path.</li>
 * </ol>
 *
 * <p><b>Form parity for measures.</b> A measure that is exactly a form measure — one catalog aggregate over one
 * column, or {@code count(*)} — is held to the form's rules exactly as {@link AggregateSpecValidator} states them.
 * Any other expression is held to the same rules restated over its result's {@link RelationColumn}: a fractional
 * result kind ({@code FLOAT64}/{@code DECIMAL}) needs a {@code decimal} column, a result that can be NULL needs a
 * nullable column, an array result needs an {@code array} column of the collected element type, and a top-level
 * catalog aggregate's declared return type picks the accepted target types exactly as it does for a form measure.
 * Any other result (a string, boolean, date or timestamp expression) must have its target column's exact type.
 * A {@code CASE WHEN} condition inside an aggregate's argument plays the form's {@code measures[].where}: it selects
 * rows rather than producing a value, so — as for a form predicate — it is subject to the entitlement gate and
 * enrichment aggregatability but not to the absence or lineage rule. A {@code CASE} above an aggregate, or in a
 * group key, decides the value written and gets no such exemption.
 */
public final class AggregateSqlValidator {

    /** The column ADAS stamps on every written row; the declaration may not name it. */
    static final String UPDATED_AT = "_updated_at";

    private static final Set<ResultColumnKind> FRACTIONAL_KINDS = EnumSet.of(ResultColumnKind.FLOAT64, ResultColumnKind.DECIMAL);

    private static final Set<ResultColumnKind> NUMERIC_KINDS =
            EnumSet.of(ResultColumnKind.INT32, ResultColumnKind.INT64, ResultColumnKind.FLOAT64, ResultColumnKind.DECIMAL);

    private static final Map<QueryFunctionReturnType, Set<QueryFieldType>> RETURN_TYPE_TARGETS = AggregateGovernance.RETURN_TYPE_TARGETS;

    private static final Set<QueryFieldType> NUMERIC_TARGETS = RETURN_TYPE_TARGETS.get(QueryFunctionReturnType.NUMERIC);

    /**
     * Declared types a {@code STRING}-kind result may land in besides {@code string}: the planner types an
     * {@code enum} or {@code uuid} operand as a string, so the kind alone cannot tell them apart.
     */
    private static final Set<QueryFieldType> STRING_FAMILY = EnumSet.of(QueryFieldType.STRING, QueryFieldType.ENUM, QueryFieldType.UUID);

    private static final String AVG = "avg";
    private static final String SUM = "sum";
    private static final String COUNT = "count";
    private static final String GROUP_UNIQ_ARRAY = ArrayValuedFunctions.GROUP_UNIQ_ARRAY;
    private static final String DATE_TRUNC = "date_trunc";
    private static final String DATE_BIN = "date_bin";

    private static final Set<String> NEVER_NULL_AGGREGATES = AggregateGovernance.NEVER_NULL_AGGREGATES;

    /**
     * Scalar catalog functions whose result is never NULL, per their catalog descriptions ("never null", or an
     * empty/false default for an absent input). Every other scalar is NULL when an operand is.
     */
    private static final Set<String> NEVER_NULL_SCALARS = Set.of(
            "array_has", "array_has_all", "array_has_any", "array_length", "equals", "not_empty", "map_value",
            ArrayValuedFunctions.JSON_EXTRACT_ARRAY);

    /** Scalar catalog functions that yield NULL for a zero divisor whatever their operands' nullability. */
    private static final Set<String> NULL_ON_ZERO = Set.of("div", "modulo");

    private final AggregateGovernance governance;
    private final EntityFieldResolver entityFields;
    private final QueryFunctionCatalog functionCatalog;
    private final SensitiveProjectionGuard sensitiveGuard;
    private final TableDefinitionRepository definitions;
    private final AggregateSqlReader sqlReader;

    AggregateSqlValidator(AggregateGovernance governance, EntityFieldResolver entityFields, QueryFunctionCatalog functionCatalog,
            SensitiveProjectionGuard sensitiveGuard, TableDefinitionRepository definitions, AggregateSqlReader sqlReader) {
        this.governance = governance;
        this.entityFields = entityFields;
        this.functionCatalog = functionCatalog;
        this.sensitiveGuard = sensitiveGuard;
        this.definitions = definitions;
        this.sqlReader = sqlReader;
    }

    /**
     * What the gate established about a statement it accepted, for the compiler to reuse rather than re-derive.
     *
     * @param plan         the planned statement
     * @param keyPositions the select positions of the group keys, in {@code GROUP BY} order
     * @param bucket       the one time-bucket key, or null when no key is a bucket
     */
    public record Analysis(SqlInsertPlan plan, List<Integer> keyPositions, Bucket bucket) {

        public Analysis {
            keyPositions = List.copyOf(keyPositions);
        }
    }

    /** A time-bucket group key: its select position and its unit. */
    public record Bucket(int position, TruncUnit unit) {}

    // --- reading ------------------------------------------------------------------------------------

    /**
     * Plans {@code sql} at the calling identity's {@code TABLE_READ} bar ({@link AggregateSqlReader#require}), turning a
     * failure that is really an entitlement refusal into the 403 it is.
     *
     * <p>A caller not cleared for a table's {@code sensitive} columns plans against a schema those columns are hidden
     * from, so a statement reading one fails to plan as an <em>unknown column</em> — a 422 where the contract promises
     * {@code 403 sensitive_column_not_entitled}. On a planning failure the statement's text is therefore scanned (its
     * identifiers, its {@code table.column} tokens and its double-quoted names, never re-parsed) and put through the
     * entity-wide sensitive gate of the table it reads (with no declared input, of every source table it names): a
     * sensitive column it mentions that the caller may not read is
     * the 403, naming the column. Anything else is the original 422. The scan only ever runs on a statement that
     * already failed, so its over-approximation can turn one refusal into another, never an acceptance into a
     * refusal.
     *
     * @param inputTable the pipeline's declared or derived input, or null when it has none yet
     * @param operation  short label naming the operation in a sensitive-column refusal
     */
    SqlInsertPlan read(String sql, String inputTable, String target, String operation) {
        try {
            return sqlReader.require(sql);
        } catch (PipelineValidationException ex) {
            if (StringUtils.isNotBlank(sql)) {
                final Set<String> mentioned = mentionedNames(sql);
                for (final String source : inputTable != null ? Set.of(inputTable) : readTablesOf(sql, target)) {
                    sensitiveGuard.enforceEntity(source, mentioned, operation);
                }
            }
            throw ex;
        }
    }

    /**
     * Every {@code source} table the text names besides {@code target}, by token scan. All of them, not only a lone
     * one: the text failed to plan, so which of them it reads is unknown, and the gate fails closed over each.
     */
    private Set<String> readTablesOf(String sql, String target) {
        final Set<String> candidates = new LinkedHashSet<>();
        for (final String token : QualifiedColumnTokens.identifiers(sql)) {
            if (!token.equals(target)) {
                definitions.findById(token)
                        .filter(table -> table.getType() == TableType.SOURCE)
                        .ifPresent(table -> candidates.add(table.getName()));
            }
        }
        return candidates;
    }

    private static Set<String> mentionedNames(String sql) {
        final Set<String> names = new LinkedHashSet<>(QualifiedColumnTokens.identifiers(sql));
        names.addAll(QualifiedColumnTokens.of(sql));
        for (final String quoted : QualifiedColumnTokens.quoted(sql)) {
            names.add(quoted);
            names.add(quoted.toLowerCase(Locale.ROOT));
        }
        return names;
    }

    // --- the gate -----------------------------------------------------------------------------------

    /**
     * Validates the planned statement against its input and target.
     *
     * @param operation short label naming the operation in a sensitive-column refusal
     * @throws PipelineValidationException (422) on any violation, naming the clause at fault
     * @throws com.epam.aidial.analytics.exception.SensitiveColumnNotEntitledException (403) if the statement reads a
     *     sensitive column the caller may not read
     */
    Analysis validate(SqlInsertPlan plan, String inputTable, TableDefinitionEntity target, String operation) {
        final StructuredQuery query = plan.translation().query();
        final EntityFields input = entityFields.entityOf(inputTable);
        final EntityFields output = entityFields.tableOf(target.getName(), ColumnMappingEntity::getSourceName);

        requireColumnList(plan, target.getName(), output);
        requireSingleSource(query, inputTable);
        requireAggregatingBlock(query);
        requireDeterministic(query);

        final List<Expr> select = query.select().stream().map(OutputColumn::expr).toList();
        final Set<String> referenced = new LinkedHashSet<>();
        select.forEach(expr -> ExprColumnCollector.collect(expr, referenced));
        ExprColumnCollector.collect(query.filter(), referenced);
        // The entitlement gate first: a caller who may not see a sensitive column should be told that, not told about
        // the shape of the expression it sits in.
        governance.enforceSensitive(inputTable, input, referenced, operation);

        final List<RelationColumn> columns = relationColumns(plan, input);
        final List<Integer> keys = keyPositions(query, plan.translation().columns(), plan.columns(),
                target.getOrderingKey() == null ? List.of() : target.getOrderingKey());
        requireKeySetIsOrderingKey(plan, keys, target);
        final Bucket bucket = validateKeys(plan, keys, columns, input, output, target.getName());
        for (int i = 0; i < select.size(); i++) {
            if (!keys.contains(i)) {
                validateMeasure(plan.columns().get(i), select.get(i), columns.get(i), input, output, target.getName());
            }
        }
        for (int i = 0; i < select.size(); i++) {
            // A key's every read decides the value written — a CASE condition in a key picks the key, it does not
            // select rows — so only a measure gets the row-membership exemption.
            final Set<String> lineage = new LinkedHashSet<>();
            if (keys.contains(i)) {
                ExprColumnCollector.collect(select.get(i), lineage);
            } else {
                lineage.addAll(valueColumns(select.get(i)));
            }
            for (final String column : lineage) {
                AggregateGovernance.requireSensitivityPropagated(column, plan.columns().get(i), input, output, target.getName());
            }
        }

        governance.requireSensitiveGrantsNotWidened(target, inputTable);
        governance.requireSensitiveGrantsNotWidened(target, AggregateGovernance.referencedEnrichmentsOf(input, referenced));
        governance.requireAggregatableEnrichments(inputTable, input, referenced);
        return new Analysis(plan, keys, bucket);
    }

    // --- statement shape ----------------------------------------------------------------------------

    private static void requireColumnList(SqlInsertPlan plan, String target, EntityFields output) {
        final List<String> listed = plan.columns();
        if (listed.stream().anyMatch(UPDATED_AT::equalsIgnoreCase)) {
            throw new PipelineValidationException("the INSERT column list names '" + UPDATED_AT + "'; ADAS stamps "
                    + UPDATED_AT + " itself on every row a refresh writes, so leave it out of the statement");
        }
        final Set<String> seen = new HashSet<>();
        for (final String column : listed) {
            if (!seen.add(column)) {
                throw new PipelineValidationException("the INSERT column list names '" + column + "' more than once; "
                        + "each target column is written by exactly one select item");
            }
        }
        for (final String column : listed) {
            if (!output.contains(column)) {
                throw new PipelineValidationException("the INSERT column list names '" + column + "', which is not a"
                        + " column of target '" + target + "'; its columns are " + output.names());
            }
        }
        final List<String> missing = output.names().stream()
                .filter(column -> !UPDATED_AT.equals(column) && !seen.contains(column))
                .toList();
        if (!missing.isEmpty()) {
            throw new PipelineValidationException("the INSERT column list omits target column(s) " + missing + " of '"
                    + target + "'; every target column but " + UPDATED_AT + " must be written, or it could only ever "
                    + "hold a type default, which would read as real data");
        }
        final int selected = plan.translation().query().select().size();
        if (selected != listed.size()) {
            throw new PipelineValidationException("the INSERT column list names " + listed.size() + " column(s) but the"
                    + " SELECT projects " + selected + " expression(s); the two map by position and must be the same length");
        }
    }

    // --- sources ------------------------------------------------------------------------------------

    private static void requireSingleSource(StructuredQuery query, String inputTable) {
        final QueryReferences refs = QueryReferences.of(query);
        if (!refs.setOperations().isEmpty()) {
            throw new PipelineValidationException("sql: a set operation is not supported in an aggregate SQL declaration;"
                    + " read the input once, in one SELECT");
        }
        if (refs.subqueries() > 0) {
            throw new PipelineValidationException("sql: a subquery used as an expression (scalar, IN or EXISTS) is not"
                    + " supported in an aggregate SQL declaration");
        }
        if (refs.joins() > 0) {
            throw new PipelineValidationException("sql: an explicit JOIN is not supported in an aggregate SQL declaration;"
                    + " read enrichment columns as \"<enrichment>.<column>\" through the implicit grain join");
        }
        final Set<String> others = new TreeSet<>(refs.entityCounts().keySet());
        others.remove(inputTable);
        if (!others.isEmpty()) {
            throw new PipelineValidationException("sql reads " + others + " besides its input '" + inputTable + "'; only"
                    + " the input and its enrichments may be read");
        }
        if (refs.occurrences() > 1) {
            throw new PipelineValidationException("sql: the input '" + inputTable + "' is referenced " + refs.occurrences()
                    + " times; an aggregate SQL declaration reads its input exactly once");
        }
        if (Boolean.TRUE.equals(query.finalRead())) {
            throw new PipelineValidationException("sql: FINAL is not supported in an aggregate SQL declaration; ADAS"
                    + " collapses versions itself with the final = 1 setting on every pipeline read");
        }
    }

    // --- block shape --------------------------------------------------------------------------------

    private static void requireAggregatingBlock(StructuredQuery query) {
        if (query.mode() != QueryMode.AGGREGATE || query.groupBy() == null || query.groupBy().isEmpty()) {
            throw new PipelineValidationException("sql: the SELECT must be an aggregation with a GROUP BY over the "
                    + "target's ordering_key; this one " + (query.mode() == QueryMode.AGGREGATE
                            ? "aggregates with no GROUP BY" : "does not aggregate"));
        }
        if (query.having() != null) {
            throw new PipelineValidationException("sql: HAVING is not supported in an aggregate SQL declaration; a group"
                    + " a refresh recomputes must be written whatever its value, or it keeps its stale row");
        }
        if (query.distinct()) {
            throw new PipelineValidationException("sql: DISTINCT is not supported in an aggregate SQL declaration");
        }
        if (query.sort() != null && !query.sort().isEmpty()) {
            throw new PipelineValidationException("sql: ORDER BY is not supported in an aggregate SQL declaration; the"
                    + " target is ordered by its ordering_key");
        }
        if (query.page() != null) {
            throw new PipelineValidationException("sql: LIMIT/OFFSET is not supported in an aggregate SQL declaration; a"
                    + " refresh writes every group it recomputes");
        }
        for (final OutputColumn column : query.select()) {
            if (containsWindow(column.expr())) {
                throw new PipelineValidationException("sql: " + SqlQueryFrontEnd.WINDOW_REFUSAL);
            }
        }
    }

    // --- determinism --------------------------------------------------------------------------------

    private static void requireDeterministic(StructuredQuery query) {
        for (final OutputColumn column : query.select()) {
            forEachFn(column.expr(), fn -> requireDeterministic(fn, "SELECT"));
        }
        forEachFn(query.filter(), fn -> requireDeterministic(fn, "WHERE"));
    }

    private static void requireDeterministic(FnExpr fn, String clause) {
        if (QueryFunctionCatalog.isNonDeterministic(fn.name())) {
            throw new PipelineValidationException("sql calls non-deterministic function '" + fn.name() + "' in its " + clause
                    + "; a refresh recomputes historical groups, and each must see the same rows and values every time");
        }
    }

    // --- group keys ---------------------------------------------------------------------------------

    /**
     * The select position of each {@code GROUP BY} key. The plan names a key by the field it groups or by the output
     * name of the select item carrying its expression, so every select item matching either is a candidate. A field
     * projected twice ({@code SELECT deployment, deployment …}) yields two candidates carrying the same value; the key
     * binds to the first one whose target column is in the {@code ordering_key} (and not already bound), so the other
     * copy is a measure rather than the reason the key set looks wrong. A key no select item projects has no target
     * column to land in and is refused.
     */
    private static List<Integer> keyPositions(StructuredQuery query, List<ResultColumn> results, List<String> columns,
            List<String> orderingKey) {
        final List<Integer> positions = new ArrayList<>(query.groupBy().size());
        for (final String key : query.groupBy()) {
            final List<Integer> candidates = new ArrayList<>();
            for (int i = 0; i < query.select().size(); i++) {
                if (query.select().get(i).expr() instanceof FieldExpr(String name) && name.equals(key)
                        || i < results.size() && results.get(i).name().equals(key)) {
                    candidates.add(i);
                }
            }
            final int found = candidates.stream()
                    .filter(i -> !positions.contains(i) && orderingKey.contains(columns.get(i)))
                    .findFirst()
                    .or(() -> candidates.stream().filter(i -> !positions.contains(i)).findFirst())
                    .or(() -> candidates.stream().findFirst())
                    .orElse(-1);
            if (found < 0) {
                throw new PipelineValidationException("sql groups by '" + key + "', which no select item projects; every"
                        + " GROUP BY key must be written to an ordering_key column of the target");
            }
            if (positions.contains(found)) {
                throw new PipelineValidationException("sql groups by the select item at position " + (found + 1)
                        + " more than once");
            }
            positions.add(found);
        }
        return positions;
    }

    private static void requireKeySetIsOrderingKey(SqlInsertPlan plan, List<Integer> keys, TableDefinitionEntity target) {
        final List<String> keyColumns = keys.stream().map(plan.columns()::get).toList();
        final List<String> orderingKey = target.getOrderingKey() == null ? List.of() : target.getOrderingKey();
        if (!new HashSet<>(keyColumns).equals(new HashSet<>(orderingKey)) || keyColumns.size() != orderingKey.size()) {
            throw new PipelineValidationException("target '" + target.getName() + "' is ordered by " + orderingKey
                    + " but the statement's GROUP BY keys write " + keyColumns + "; the two must be the same set of "
                    + "columns, because the ordering key is the key the target collapses on");
        }
    }

    private Bucket validateKeys(SqlInsertPlan plan, List<Integer> keys, List<RelationColumn> columns, EntityFields input,
            EntityFields output, String target) {
        final List<Expr> select = plan.translation().query().select().stream().map(OutputColumn::expr).toList();
        Bucket bucket = null;
        for (final int position : keys) {
            final Expr key = select.get(position);
            final String column = plan.columns().get(position);
            final String name = "group key '" + column + "'";
            final Set<String> reads = new LinkedHashSet<>();
            ExprColumnCollector.collect(key, reads);
            if (reads.isEmpty()) {
                throw new PipelineValidationException(name + " is a constant; a group key must read the input row (a "
                        + "source column, an enrichment column, or an expression over them)");
            }
            forEachFn(key, fn -> {
                if (ArrayValuedFunctions.ARRAY_JOIN.equalsIgnoreCase(fn.name())) {
                    throw new PipelineValidationException(name + " calls '" + fn.name() + "', which expands one input row into"
                            + " several; a row-expanding function cannot form a group key, because the refresh restricts each"
                            + " recompute by the key and that restriction is not proven to select the same expanded rows."
                            + " Roll up a table holding the expanded rows instead");
                }
            });
            for (final String read : reads) {
                requireEnrichmentAbsenceIsRepresentable(name, read, input);
            }
            if (nullable(key, input)) {
                throw new PipelineValidationException(name + " can be NULL" + nullableBecause(reads, input) + ", and a NULL"
                        + " group is never recomputed: the refresh restricts each recompute to its dirty keys by a tuple"
                        + " IN, which no NULL component matches. Wrap the key in a non-null default, e.g. "
                        + coalesceSuggestion(key, columns.get(position).type()));
            }
            final QueryFieldType declared = output.typeOf(column);
            final TruncUnit unit = bucketUnit(key, name);
            if (unit != null) {
                if (bucket != null) {
                    throw new PipelineValidationException("sql groups by two time buckets ('" + plan.columns().get(bucket.position())
                            + "' and '" + column + "'); at most one time bucket is allowed, since a second would partition"
                            + " the same time axis twice");
                }
                bucket = new Bucket(position, unit);
                if (!unit.targetTypes().contains(declared)) {
                    throw new PipelineValidationException(name + " is a '" + unit.code() + "' time bucket but the target "
                            + "column is declared '" + declared.code() + "'; it must be declared " + typeList(unit.targetTypes())
                            + ", or the bucket start is truncated on insert and the keys it distinguishes collapse into one row");
                }
                continue;
            }
            final QueryFieldType type = columns.get(position).type();
            if (type != declared) {
                throw new PipelineValidationException(name + " yields '" + type.code() + "' but target column '" + column
                        + "' on '" + target + "' is declared '" + declared.code() + "'; a group key is the input value "
                        + "itself, so the types must match exactly");
            }
        }
        return bucket;
    }

    /**
     * The unit of a key that is a time bucket — {@code date_trunc(unit, t)}, or {@code date_bin(1, unit, t)} — or null
     * when the key is not one. A bucket of a unit the form path cannot declare, or with a step other than one unit,
     * is refused: the refresh's pruning bound steps a bucket start by exactly {@code INTERVAL 1 <unit>}.
     */
    private static TruncUnit bucketUnit(Expr key, String name) {
        if (!(key instanceof FnExpr fn) || fn.args() == null) {
            return null;
        }
        final String unit;
        if (DATE_TRUNC.equalsIgnoreCase(fn.name()) && fn.args().size() == 2) {
            unit = literal(fn.args().getFirst());
        } else if (DATE_BIN.equalsIgnoreCase(fn.name()) && fn.args().size() == 3) {
            final String amount = literal(fn.args().getFirst());
            if (!"1".equals(amount)) {
                throw new PipelineValidationException(name + " is a time bucket of " + amount + " units; a bucket must "
                        + "step by one unit, because the refresh's pruning bound advances a bucket start by one unit and "
                        + "would drop the rest of a wider bucket's rows");
            }
            unit = literal(fn.args().get(1));
        } else {
            return null;
        }
        for (final TruncUnit candidate : TruncUnit.values()) {
            if (candidate.isSupported() && candidate.code().equalsIgnoreCase(unit)) {
                return candidate;
            }
        }
        throw new PipelineValidationException(name + " is a '" + unit + "' time bucket; the supported bucket units are "
                + TruncUnit.supportedCodes());
    }

    private static String literal(Expr expr) {
        return expr instanceof ValueExpr(ValueType ignored, String value) ? value : String.valueOf(expr);
    }

    private static String nullableBecause(Set<String> reads, EntityFields input) {
        final List<String> causes = reads.stream().filter(input::isNullable).toList();
        return causes.isEmpty() ? "" : " (it reads " + causes.stream().map(c -> "'" + c + "'").collect(Collectors.joining(", "))
                + (causes.stream().anyMatch(input::isEnrichmentSourced)
                        ? ", and an enrichment column is NULL for every input row its enrichment has not reached)" : ")");
    }

    private static String coalesceSuggestion(Expr key, QueryFieldType type) {
        final String operand = key instanceof FieldExpr(String name) ? (name.indexOf('.') >= 0 ? "\"" + name + "\"" : name) : "<key>";
        final String fallback = switch (type) {
            case STRING, ENUM, UUID -> "''";
            case INTEGER, LONG, DECIMAL -> "0";
            case BOOLEAN -> "false";
            default -> "<default>";
        };
        return "coalesce(" + operand + ", " + fallback + ")";
    }

    // --- measures -----------------------------------------------------------------------------------

    private void validateMeasure(String column, Expr expr, RelationColumn result, EntityFields input, EntityFields output, String target) {
        final QueryFieldType declared = output.typeOf(column);
        final String name = "measure '" + column + "'";
        forEachFn(expr, fn -> requireAggregateUsable(column, fn, input));
        for (final String read : valueColumns(expr)) {
            requireEnrichmentAbsenceIsRepresentable(name, read, input);
        }
        final FnExpr formShaped = formShaped(expr);
        if (formShaped != null) {
            validateFormMeasure(column, formShaped, input, output, target, declared);
            return;
        }
        final QueryFunctionDto top = expr instanceof FnExpr fn ? aggregate(fn.name()) : null;
        final Set<QueryFieldType> accepted = top != null && RETURN_TYPE_TARGETS.containsKey(top.returns())
                ? RETURN_TYPE_TARGETS.get(top.returns())
                : result.kind() == ResultColumnKind.ARRAY ? RETURN_TYPE_TARGETS.get(QueryFunctionReturnType.ARRAY)
                : NUMERIC_KINDS.contains(result.kind()) ? NUMERIC_TARGETS : null;
        if (accepted != null && !accepted.contains(declared)) {
            throw new PipelineValidationException(name + " yields '" + result.type().code() + "' but target column '" + column
                    + "' on '" + target + "' is declared '" + declared.code() + "'; it must be declared " + typeList(accepted));
        }
        if (accepted == null && !sameType(result, declared)) {
            // No return class and no numeric kind to widen from: a string, boolean, date or timestamp result. ClickHouse
            // converts on insert, which either truncates silently (a timestamp into a date) or fails inside a refresh.
            throw new PipelineValidationException(name + " yields '" + result.type().code() + "' but target column '" + column
                    + "' on '" + target + "' is declared '" + declared.code() + "'; an expression measure of this type "
                    + "must land in a column of the same type, or ClickHouse converts it on insert");
        }
        if (FRACTIONAL_KINDS.contains(result.kind()) && declared != QueryFieldType.DECIMAL) {
            throw new PipelineValidationException(name + " yields a fractional value (an avg, a division or a decimal "
                    + "operand), but target column '" + column + "' on '" + target + "' is declared '" + declared.code()
                    + "'; declare it 'decimal', or the fraction is truncated on insert and the rollup reads as an exact whole number");
        }
        if (result.kind() == ResultColumnKind.ARRAY) {
            final QueryFieldType element = output.elementTypeOf(column);
            final QueryFieldType collected = collectedType(expr, result, input);
            if (element != null && collected != null && element != collected) {
                throw new PipelineValidationException(name + " collects values of type '" + collected.code() + "' but target"
                        + " column '" + column + "' on '" + target + "' is declared array of '" + element.code() + "'; declare "
                        + "the element type '" + collected.code() + "', or ClickHouse converts them on insert");
            }
        }
        if (!output.isNullable(column) && nullable(expr, input)) {
            final List<String> enrichments = valueColumns(expr).stream().filter(input::isEnrichmentSourced).toList();
            throw new PipelineValidationException(name + " can yield NULL"
                    + (enrichments.isEmpty() ? " (a CASE with no ELSE, a division, or a nullable operand)"
                            : " (it reads enrichment column(s) " + enrichments + ", which the outer grain join renders NULL for "
                                    + "every input row the enrichment has not reached)")
                    + ", but target column '" + column + "' on '" + target + "' is declared non-nullable; ClickHouse would "
                    + "store the type default and the rollup would read as a measured zero. Declare the target column "
                    + "nullable, or wrap the expression in an explicit default");
        }
    }

    /**
     * Whether a result with no accepted-type set may land in {@code declared}: only its own type, or — for a
     * {@code STRING}-kind result, which is how the planner types an {@code enum} or {@code uuid} operand too — one of
     * {@link #STRING_FAMILY}.
     */
    private static boolean sameType(RelationColumn result, QueryFieldType declared) {
        return result.type() == declared
                || result.kind() == ResultColumnKind.STRING && result.type() == QueryFieldType.STRING && STRING_FAMILY.contains(declared);
    }

    /**
     * The form path's measure rules, exactly, for a measure the form could have declared: {@code fn(column)} or
     * {@code count(*)}. Mirrors {@code AggregateSpecValidator}'s {@code requireTypeAccepts},
     * {@code requireArrayElementAccepts}, {@code requireFractionPreserved} and {@code requireNullabilityPreserved}
     * (with no {@code where}, which an SQL declaration spells as a {@code CASE} and so is never form-shaped).
     */
    private void validateFormMeasure(String column, FnExpr fn, EntityFields input, EntityFields output, String target,
            QueryFieldType declared) {
        final QueryFunctionDto descriptor = aggregate(fn.name());
        final String operand = fn.args().isEmpty() ? null : ((FieldExpr) fn.args().getFirst()).name();
        final String name = "measure '" + column + "'";
        AggregateGovernance.requireReturnTypeAccepts(column, descriptor.returns(), declared);
        if (descriptor.returns() == QueryFunctionReturnType.ARRAY && operand != null) {
            // The operand's scalar-ness was already required by requireAggregateUsable, which runs first.
            AggregateGovernance.requireArrayElementMatches(column, operand, input.typeOf(operand), output.elementTypeOf(column), target);
        }
        final boolean fractional = AVG.equals(descriptor.name())
                || (SUM.equals(descriptor.name()) && operand != null && input.typeOf(operand) == QueryFieldType.DECIMAL);
        AggregateGovernance.requireFractionPreserved(column, fn.name(), operand, fractional, declared);
        if (!NEVER_NULL_AGGREGATES.contains(descriptor.name()) && !output.isNullable(column)
                && operand != null && input.isNullable(operand)) {
            throw new PipelineValidationException(name + " computes '" + fn.name() + "' over nullable input column '"
                    + operand + "', so it yields NULL for a group holding no value, but target column '" + column + "' on '"
                    + target + "' is declared non-nullable; ClickHouse would store the type default and the rollup would "
                    + "read as a measured zero. Declare the target column nullable — its nullability is fixed at DDL, so "
                    + "this is a change to the table, not to the pipeline");
        }
    }

    /** {@code fn(column)} or {@code count(*)} over a catalog aggregate — a measure the form path could declare. */
    private FnExpr formShaped(Expr expr) {
        if (!(expr instanceof FnExpr fn) || aggregate(fn.name()) == null || fn.args() == null) {
            return null;
        }
        final boolean countStar = COUNT.equalsIgnoreCase(fn.name()) && fn.args().isEmpty();
        return countStar || fn.args().size() == 1 && fn.args().getFirst() instanceof FieldExpr ? fn : null;
    }

    /**
     * The per-aggregate rules the form path applies to a measure's function, applied to every aggregate a measure
     * expression calls: no array-consuming aggregate ({@code requireScalarInputFunction}), and {@code DISTINCT} only
     * where the catalog accepts it ({@code requireDistinctSupported}). The form's single-column arity rule has no
     * counterpart: an SQL measure is an expression and can pass a second operand.
     */
    private void requireAggregateUsable(String column, FnExpr fn, EntityFields input) {
        final String name = "measure '" + column + "'";
        if (ArrayValuedFunctions.isFlatteningAggregate(fn.name())) {
            throw new PipelineValidationException(name + " calls '" + fn.name() + "', which reads an array column; a "
                    + "rollup aggregates scalars. Aggregate it at query time instead, or derive a scalar column on the input first");
        }
        final QueryFunctionDto descriptor = aggregate(fn.name());
        if (descriptor == null) {
            return;
        }
        if (fn.distinct() && GROUP_UNIQ_ARRAY.equals(descriptor.name())) {
            throw new PipelineValidationException(name + " calls '" + fn.name() + "' with DISTINCT; it already returns the "
                    + "distinct values of the group");
        }
        if (fn.distinct() && !descriptor.distinctSupported()) {
            throw new PipelineValidationException(name + " calls '" + fn.name() + "' with DISTINCT, which it does not accept ("
                    + descriptor.signature() + "); the aggregates that do are the ones the function catalog marks 'distinct_supported'");
        }
        if (GROUP_UNIQ_ARRAY.equals(descriptor.name()) && fn.args() != null && fn.args().size() == 1
                && fn.args().getFirst() instanceof FieldExpr(String operand)) {
            AggregateGovernance.requireCollectsScalar(column, fn.name(), operand, input.typeOf(operand));
        }
    }

    /** The element type an array measure collects: its operand's declared type where it is a column, else its kind's. */
    private static QueryFieldType collectedType(Expr expr, RelationColumn result, EntityFields input) {
        if (expr instanceof FnExpr fn && GROUP_UNIQ_ARRAY.equalsIgnoreCase(fn.name()) && fn.args() != null
                && fn.args().size() == 1 && fn.args().getFirst() instanceof FieldExpr(String operand)) {
            return input.typeOf(operand);
        }
        return result.elementType();
    }

    private QueryFunctionDto aggregate(String name) {
        return functionCatalog.listFunctions().stream()
                .filter(fn -> fn.group() == QueryFunctionGroup.AGGREGATE)
                .filter(fn -> fn.name().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }

    // --- shared rules -------------------------------------------------------------------------------

    /**
     * As {@code AggregateSpecValidator.requireEnrichmentAbsenceIsRepresentable}: a value-producing read of an enrichment
     * column the catalog declares non-nullable is refused, because an unmatched outer join renders it as its type
     * default rather than as NULL ({@code join_use_nulls = 0}). Asked of the column, not of the expression around it:
     * {@code coalesce("<enrichment>.<column>", '')} is the author's explicit mapping of absence, and it is sound only
     * when the absence arrives as a NULL to map.
     */
    private static void requireEnrichmentAbsenceIsRepresentable(String what, String column, EntityFields input) {
        if (!input.absenceIsUnrepresentable(column)) {
            return;
        }
        throw new PipelineValidationException(what + " reads '" + column + "', an enrichment column declared non-nullable"
                + " on enrichment '" + input.ownerOf(column) + "'. It is read through an outer grain join, and an unmatched"
                + " outer join renders a non-nullable column as its type default rather than as NULL (join_use_nulls"
                + " defaults to 0), so every input row the enrichment has not reached would contribute that default —"
                + " an empty string, a zero, an epoch date — indistinguishable from a measured value. Declare '"
                + EntityFields.lastSegment(column) + "' nullable on enrichment '" + input.ownerOf(column) + "' so absence"
                + " reads as NULL, or read it only in the WHERE or a CASE condition, where it selects rows instead of"
                + " producing a value");
    }

    private static String typeList(Set<QueryFieldType> types) {
        return types.stream().map(QueryFieldType::code).sorted().collect(Collectors.joining(" or "));
    }

    // --- preview ------------------------------------------------------------------------------------

    /**
     * The preview columns of an accepted statement, in column-list order. A key or measure is typed as the engine
     * yields its expression ({@link #relationColumns}); a time bucket, as on the form path, takes its target column's
     * type where its unit admits it — a day, week or month bucket into {@code date} is lossless — and
     * {@code timestamp} otherwise.
     */
    static List<PreviewColumn> previewColumns(Analysis analysis, EntityFields input, EntityFields output) {
        final List<RelationColumn> types = relationColumns(analysis.plan(), input);
        final List<PreviewColumn> columns = new ArrayList<>(types.size());
        for (int i = 0; i < types.size(); i++) {
            final String name = analysis.plan().columns().get(i);
            final QueryFieldType targetType = output.typeOf(name);
            final boolean key = analysis.keyPositions().contains(i);
            QueryFieldType type = types.get(i).type();
            if (analysis.bucket() != null && analysis.bucket().position() == i) {
                type = targetType != null && analysis.bucket().unit().targetTypes().contains(targetType)
                        ? targetType : QueryFieldType.TIMESTAMP;
            }
            columns.add(new PreviewColumn(name, key ? PreviewColumnRole.KEY : PreviewColumnRole.MEASURE,
                    type == null ? targetType : type, targetType));
        }
        return columns;
    }

    /**
     * The measures whose value is the input's own — a top-level {@code min} or {@code max} — by target column, for
     * which a {@code timestamp} into {@code date} narrows, as {@link AggregateSpecValidator#valuePreservingMeasures}.
     */
    static Set<String> valuePreservingMeasures(Analysis analysis) {
        final List<OutputColumn> select = analysis.plan().translation().query().select();
        final Set<String> preserving = new LinkedHashSet<>();
        for (int i = 0; i < select.size(); i++) {
            if (!analysis.keyPositions().contains(i) && select.get(i).expr() instanceof FnExpr fn
                    && ("min".equalsIgnoreCase(fn.name()) || "max".equalsIgnoreCase(fn.name()))) {
                preserving.add(analysis.plan().columns().get(i));
            }
        }
        return preserving;
    }

    /**
     * One {@link RelationColumn} per select position: the type, kind and element of the value the item yields — a
     * pass-through column's declared type (so an {@code enum} or {@code uuid} stays one), else its kind's.
     */
    private static List<RelationColumn> relationColumns(SqlInsertPlan plan, EntityFields input) {
        final List<OutputColumn> select = plan.translation().query().select();
        final List<ResultColumn> results = plan.translation().columns();
        final List<RelationColumn> columns = new ArrayList<>(results.size());
        for (int i = 0; i < results.size(); i++) {
            final ResultColumn result = results.get(i);
            QueryFieldType type = null;
            QueryFieldType element = null;
            if (select.get(i).expr() instanceof FieldExpr(String name) && input.contains(name)) {
                type = input.typeOf(name);
                element = input.elementTypeOf(name);
            }
            columns.add(new RelationColumn(result.name(), type == null ? ResultColumns.fieldType(result.kind()) : type,
                    element, result.kind(), result.elementKind()));
        }
        return columns;
    }

    // --- expression walkers -------------------------------------------------------------------------

    /**
     * Whether {@code expr} can evaluate to NULL for a group. A column is nullable as {@link EntityFields#isNullable}
     * says (an enrichment column always is); a NULL literal is; {@code count} and {@code group_uniq_array} never are,
     * nor are the catalog scalars documented never to be; {@code div}/{@code modulo} always can be (a zero divisor);
     * any other function is nullable when an operand is. A {@code CASE} is nullable without an {@code ELSE}, or when a
     * branch value is — except a branch guarded by its own {@code IS NOT NULL}, which is how {@code coalesce} plans.
     */
    static boolean nullable(Expr expr, EntityFields input) {
        return switch (expr) {
            case null -> true;
            case FieldExpr(String name) -> input.isNullable(name);
            case ValueExpr value -> value.valueType() == null || value.valueType() == ValueType.NULL;
            case ArrayExpr ignored -> false;
            case FnExpr fn -> {
                final String name = fn.name().toLowerCase(Locale.ROOT);
                if (NEVER_NULL_AGGREGATES.contains(name) || NEVER_NULL_SCALARS.contains(name)) {
                    yield false;
                }
                if (NULL_ON_ZERO.contains(name)) {
                    yield true;
                }
                if ("if".equals(name) && fn.args() != null && fn.args().size() == 3) {
                    yield nullable(fn.args().get(1), input) || nullable(fn.args().get(2), input);
                }
                yield fn.args() != null && fn.args().stream().anyMatch(arg -> nullable(arg, input));
            }
            case CaseExpr caseExpr -> {
                if (caseExpr.otherwise() == null || nullable(caseExpr.otherwise(), input)) {
                    yield true;
                }
                yield caseExpr.when() != null && caseExpr.when().stream()
                        .anyMatch(branch -> !guardedNonNull(branch) && nullable(branch.then(), input));
            }
            default -> true;
        };
    }

    /** {@code WHEN x IS NOT NULL THEN x} — a branch whose value its own condition proves non-null. */
    private static boolean guardedNonNull(CaseBranch branch) {
        return branch.when() instanceof ComparisonNode(ComparisonOp op, List<Expr> args)
                && op == ComparisonOp.NE && args != null && args.size() == 2
                && args.get(1) instanceof ValueExpr(ValueType type, String ignored) && type == ValueType.NULL
                && args.getFirst().equals(branch.then());
    }

    /**
     * The columns a measure {@code expr} copies values from: every column it reads except those read only inside the
     * condition of a {@code CASE} that sits within an aggregate's arguments, which selects the rows the aggregate sees
     * (the SQL spelling of a form measure's {@code where}). A {@code CASE} above every aggregate —
     * {@code CASE WHEN max(x) > 'm' THEN 1 ELSE 0 END} — decides the value written from {@code x}, so its condition's
     * columns are value columns.
     */
    Set<String> valueColumns(Expr expr) {
        final Set<String> out = new LinkedHashSet<>();
        collectValues(expr, out, false);
        return out;
    }

    private void collectValues(Expr expr, Set<String> out, boolean insideAggregate) {
        switch (expr) {
            case null -> { }
            case CaseExpr caseExpr -> {
                if (caseExpr.when() != null) {
                    for (final CaseBranch branch : caseExpr.when()) {
                        if (!insideAggregate) {
                            ExprColumnCollector.collect(branch.when(), out);
                        }
                        collectValues(branch.then(), out, insideAggregate);
                    }
                }
                collectValues(caseExpr.otherwise(), out, insideAggregate);
            }
            case FnExpr fn -> {
                final boolean inside = insideAggregate || aggregate(fn.name()) != null;
                if (fn.args() != null) {
                    fn.args().forEach(arg -> collectValues(arg, out, inside));
                }
            }
            case ArrayExpr array -> {
                if (array.items() != null) {
                    array.items().forEach(item -> collectValues(item, out, insideAggregate));
                }
            }
            default -> ExprColumnCollector.collect(expr, out);
        }
    }

    private static boolean containsWindow(Expr expr) {
        final boolean[] found = {expr instanceof WindowExpr};
        forEachExpr(expr, e -> found[0] |= e instanceof WindowExpr);
        return found[0];
    }

    /** Visits every function call in {@code expr}, including those inside conditions, lambdas and windows. */
    static void forEachFn(Expr expr, Consumer<FnExpr> visitor) {
        forEachExpr(expr, e -> {
            if (e instanceof FnExpr fn) {
                visitor.accept(fn);
            }
        });
    }

    /** Visits every function call in {@code node}. */
    static void forEachFn(FilterNode node, Consumer<FnExpr> visitor) {
        forEachExpr(node, e -> {
            if (e instanceof FnExpr fn) {
                visitor.accept(fn);
            }
        });
    }

    private static void forEachExpr(Expr expr, Consumer<Expr> visitor) {
        if (expr == null) {
            return;
        }
        visitor.accept(expr);
        switch (expr) {
            case FnExpr fn -> {
                if (fn.args() != null) {
                    fn.args().forEach(arg -> forEachExpr(arg, visitor));
                }
            }
            case ArrayExpr array -> {
                if (array.items() != null) {
                    array.items().forEach(item -> forEachExpr(item, visitor));
                }
            }
            case CaseExpr caseExpr -> {
                if (caseExpr.when() != null) {
                    for (final CaseBranch branch : caseExpr.when()) {
                        forEachExpr(branch.when(), visitor);
                        forEachExpr(branch.then(), visitor);
                    }
                }
                forEachExpr(caseExpr.otherwise(), visitor);
            }
            case WindowExpr window -> {
                forEachExpr(window.fn(), visitor);
                if (window.partitionBy() != null) {
                    window.partitionBy().forEach(key -> forEachExpr(key, visitor));
                }
                if (window.orderBy() != null) {
                    for (final WindowOrder order : window.orderBy()) {
                        forEachExpr(order.expr(), visitor);
                    }
                }
            }
            case LambdaExpr lambda -> forEachExpr(lambda.body(), visitor);
            default -> { }
        }
    }

    private static void forEachExpr(FilterNode node, Consumer<Expr> visitor) {
        switch (node) {
            case null -> { }
            case LogicalNode logical -> {
                if (logical.args() != null) {
                    logical.args().forEach(child -> forEachExpr(child, visitor));
                }
            }
            case ComparisonNode comparison -> {
                if (comparison.args() != null) {
                    comparison.args().forEach(arg -> forEachExpr(arg, visitor));
                }
            }
            case SubqueryNode subquery -> {
                if (subquery.operands() != null) {
                    subquery.operands().forEach(arg -> forEachExpr(arg, visitor));
                }
            }
        }
    }
}
