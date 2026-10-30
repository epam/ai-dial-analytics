package com.epam.aidial.analytics.service.pipeline;

import com.epam.aidial.analytics.exception.PipelineValidationException;
import com.epam.aidial.analytics.service.enrichment.support.EntityFieldResolver;
import com.epam.aidial.analytics.service.enrichment.support.EntityFields;
import com.epam.aidial.analytics.service.enrichment.support.SensitiveProjectionGuard;
import com.epam.aidial.analytics.service.enrichment.validation.FilterSqlValidator;
import com.epam.aidial.analytics.service.pipeline.model.AggregateSpec;
import com.epam.aidial.analytics.service.pipeline.model.FreshnessMode;
import com.epam.aidial.analytics.service.pipeline.model.GroupKey;
import com.epam.aidial.analytics.service.pipeline.model.Measure;
import com.epam.aidial.analytics.service.pipeline.model.TruncUnit;
import com.epam.aidial.analytics.service.pipeline.preview.PreviewColumn;
import com.epam.aidial.analytics.service.pipeline.preview.PreviewColumnRole;
import com.epam.aidial.analytics.service.query.ArrayValuedFunctions;
import com.epam.aidial.analytics.service.query.ComputedFields;
import com.epam.aidial.analytics.service.query.QueryFunctionCatalog;
import com.epam.aidial.analytics.service.query.sql.SqlInsertPlan;
import com.epam.aidial.analytics.service.table.ColumnMappingEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import com.epam.aidial.analytics.service.table.TableStatus;
import com.epam.aidial.analytics.service.table.TableType;
import com.epam.aidial.analytics.service.table.grant.SensitiveGrantInvariant;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import com.epam.aidial.analytics.web.dto.query.QueryFunctionDto;
import com.epam.aidial.analytics.web.dto.query.QueryFunctionGroup;
import com.epam.aidial.analytics.web.dto.query.QueryFunctionReturnType;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The aggregate pipeline's governance gate, applied in full at registration <em>and</em> re-applied at
 * execution — the source's schema and a column's {@code sensitive} flag can both change under a stored
 * pipeline, and a rollup that silently keeps writing after such a change would produce numbers nobody
 * can account for.
 *
 * <p>The gate is deliberately total. An aggregate pipeline writes derived numbers to a table people read
 * as fact, so every way the declaration could be quietly wrong is rejected loudly instead:
 *
 * <ul>
 *   <li><b>An {@code append} target</b> — the one misconfiguration whose symptom is not an error but
 *       silently multiplied measures, since every refresh would add a second row per key instead of
 *       replacing it.</li>
 *   <li><b>An {@code ordering_key} that differs from the group-by tuple</b> — the target would collapse
 *       on a different key than the pipeline groups by, mixing unrelated buckets.</li>
 *   <li><b>A target column nothing writes</b> — it could only ever hold a type default, reading as a
 *       real zero.</li>
 *   <li><b>A nullable measure bound to a non-nullable target column</b> — the same type default, arrived at
 *       from the other direction: the measure does write, but writes NULL for a group with no value, which
 *       the engine converts to zero.</li>
 *   <li><b>A group key over a nullable column, with no {@code filter} declared</b> — the same failure once
 *       more, and here with no target shape that could absorb it, since the group keys are the target's
 *       {@code ordering_key} and ClickHouse refuses a nullable sorting key. A declared filter is accepted as
 *       the statement that the NULLs are accounted for; see {@link #requireNullGroupKeysAccountedFor}.</li>
 *   <li><b>A sensitive input column read by a caller who may not see it</b> — checked against the calling
 *       identity, not the registering one.</li>
 *   <li><b>A sensitive input column derived into a non-sensitive target column</b> — an aggregate is
 *       otherwise a laundering path: {@code min}/{@code max} over a protected column, or a protected column
 *       as a group key, copies its values into a target column carrying no {@code sensitive} flag.</li>
 *   <li><b>A group key whose target column cannot hold it</b> — a group key is the input value itself, so
 *       a type mismatch is a bad declaration, not something to leave for the engine to trip over.</li>
 * </ul>
 */
@Component
public class AggregateSpecValidator {

    /**
     * The type each measure function yields, as the engine produces it, for the dry-run preview's columns. This
     * is finer than {@link AggregateGovernance#RETURN_TYPE_TARGETS}, which is keyed on the catalog's coarse return class and so
     * cannot tell {@code sum} over a {@code long} (an {@code Int64}) from {@code avg}; the catalog's measure set
     * is exactly these six functions. {@code sum} and {@code min}/{@code max} depend on the input column and are
     * resolved in {@link #measureType}.
     */
    private static final Map<String, QueryFieldType> MEASURE_TYPES = Map.of(
            "count", QueryFieldType.LONG,
            "avg", QueryFieldType.DECIMAL,
            "group_uniq_array", QueryFieldType.ARRAY);

    /** Declared column types a time bucket may be taken over. */
    private static final Set<QueryFieldType> BUCKETABLE =
            EnumSet.of(QueryFieldType.DATE, QueryFieldType.TIMESTAMP);

    /** The one aggregate whose result is fractional whatever it reads. */
    private static final String AVG = "avg";

    /** The one aggregate that carries its input column's scale through to the result. */
    private static final String SUM = "sum";

    /** The one aggregate that may name no input column. */
    private static final String COUNT = "count";

    /** The array-valued aggregate: its result is a set, and an empty set is a value rather than a NULL. */
    private static final String GROUP_UNIQ_ARRAY = ArrayValuedFunctions.GROUP_UNIQ_ARRAY;

    private final TableDefinitionRepository definitions;
    private final QueryFunctionCatalog functionCatalog;
    private final FilterSqlValidator filterValidator;
    private final SensitiveProjectionGuard sensitiveGuard;
    private final EntityFieldResolver entityFields;
    private final PipelineSpecCodec specCodec;
    /** The input, target, sensitivity and enrichment rules both declaration modes share. */
    private final AggregateGovernance governance;
    /** The SQL-mode gate, over the same shared rules. */
    private final AggregateSqlValidator sqlValidator;

    public AggregateSpecValidator(TableDefinitionRepository definitions, PipelineRepository pipelines,
            QueryFunctionCatalog functionCatalog, FilterSqlValidator filterValidator, SensitiveProjectionGuard sensitiveGuard,
            EntityFieldResolver entityFields, EnrichmentFreshness freshness, PipelineSpecCodec specCodec,
            SensitiveGrantInvariant sensitiveGrants, AggregateSqlReader sqlReader) {
        this.definitions = definitions;
        this.functionCatalog = functionCatalog;
        this.filterValidator = filterValidator;
        this.sensitiveGuard = sensitiveGuard;
        this.entityFields = entityFields;
        this.specCodec = specCodec;
        this.governance = new AggregateGovernance(definitions, pipelines, freshness, sensitiveGuard, sensitiveGrants);
        this.sqlValidator = new AggregateSqlValidator(
                governance, entityFields, functionCatalog, sensitiveGuard, definitions, sqlReader);
    }

    /**
     * The validated shape of one pipeline: its resolved input and target definitions, and for an SQL declaration what
     * the gate established about its statement (null for a form declaration).
     */
    public record Validated(TableDefinitionEntity input, TableDefinitionEntity target, AggregateSqlValidator.Analysis sql) {

        public Validated(TableDefinitionEntity input, TableDefinitionEntity target) {
            this(input, target, null);
        }
    }

    /**
     * Validates a complete aggregate declaration against its input and target, and runs the sensitive gate
     * over every input column the declaration reads. The gate runs <em>here</em>, once, rather than again in
     * each caller: this method is the only thing that knows the full referenced set, and two copies of the
     * check could disagree.
     *
     * @param pipelineName the pipeline being validated, excluded from the one-per-target check so a patch
     *     of an existing pipeline does not conflict with itself; null when registering
     * @param operation short label naming the operation in a sensitive-column refusal (e.g.
     *     {@code "aggregate refresh"})
     * @throws PipelineValidationException (422) on any spec, target, or input violation
     * @throws com.epam.aidial.analytics.exception.PipelineConflictException (409) if another pipeline
     *     already targets the same table
     * @throws com.epam.aidial.analytics.exception.SensitiveColumnNotEntitledException (403) if the
     *     declaration reads a sensitive input column the caller is not entitled to
     */
    @Transactional(readOnly = true)
    public Validated validate(String pipelineName, String inputTable, String target, String filterSql,
            AggregateSpec spec, String operation) {
        if (spec.getSql() != null) {
            return validateSql(pipelineName, inputTable, target, filterSql, spec, operation);
        }
        final TableDefinitionEntity inputDefinition = governance.validateInput(inputTable, target);
        final TableDefinitionEntity targetDefinition = governance.validateTarget(pipelineName, target);
        final EntityFields input = entityColumns(inputTable);
        final EntityFields output = columnsBy(target, ColumnMappingEntity::getSourceName);

        validateFreshness(spec);
        deriveGroupKeys(spec, targetDefinition, input);
        validateGroupKeys(spec, inputTable, input, filterSql);
        final Map<String, QueryFunctionDto> functions = validateMeasures(spec, inputTable, input);
        validateMeasurePredicates(spec, inputTable);
        validateTargetBinding(spec, targetDefinition, input, output, functions);

        final Set<String> referenced = referencedInputColumns(spec, inputTable, filterSql);
        governance.enforceSensitive(inputTable, input, referenced, operation);
        governance.requireSensitiveGrantsNotWidened(targetDefinition, inputTable);
        // After the entitlement gate, deliberately: a caller who may not see a sensitive enrichment column
        // should be told that, not told about the shape of the pipeline that writes it.
        governance.requireSensitiveGrantsNotWidened(
                targetDefinition, AggregateGovernance.referencedEnrichmentsOf(input, referenced));
        governance.requireAggregatableEnrichments(inputTable, input, referenced);
        return new Validated(inputDefinition, targetDefinition);
    }

    /**
     * The gate for a declaration in <b>SQL mode</b> ({@link AggregateSpec#getSql()} set), reached through
     * {@link #validate} — so arming, preview and refresh all run it. Never derives {@code group_by}: the
     * statement's own {@code GROUP BY} declares the keys.
     *
     * <p>In order: the statement must be readable (else a 422 naming {@code sql}, before anything else is asked);
     * its {@code INSERT INTO} must name the pipeline's {@code target}, which stays authoritative; a declared
     * {@code inputs} must equal the table the statement reads; then the input and target checks the form path
     * applies. The statement's own eligibility and governance (shape, keys, determinism, types, sensitivity) is
     * {@link AggregateSqlValidator}'s, run last.
     *
     * @param inputTable the declared input, or null when the pipeline derives it from the statement
     */
    private Validated validateSql(String pipelineName, String inputTable, String target, String filterSql,
            AggregateSpec spec, String operation) {
        final SqlInsertPlan plan = sqlValidator.read(spec.getSql(), inputTable, target, operation);
        final String read = plan.translation().query().entity();
        if (!plan.target().equals(target)) {
            throw new PipelineValidationException("sql inserts into '" + plan.target() + "', but the pipeline's"
                    + " target is '" + target + "'; the statement's INSERT INTO must name the pipeline's target"
                    + " (a repoint does not rewrite the statement)");
        }
        if (inputTable != null && !inputTable.equals(read)) {
            throw new PipelineValidationException("inputs names '" + inputTable + "', but sql reads '" + read
                    + "'; omit inputs to read the statement's table, or make the two agree");
        }
        if (filterSql != null) {
            throw new PipelineValidationException("filter cannot be declared beside sql; put the condition in the"
                    + " statement's WHERE");
        }
        final TableDefinitionEntity inputDefinition = governance.validateInput(read, target);
        final TableDefinitionEntity targetDefinition = governance.validateTarget(pipelineName, target);
        return new Validated(inputDefinition, targetDefinition,
                sqlValidator.validate(plan, read, targetDefinition, operation));
    }

    /**
     * The sample columns a preview of {@code spec} carries, in projection order: each group key as {@code key} and
     * each measure as {@code measure}, with the type the compiled expression yields and the type of the target
     * column it writes. Read against the same catalog the gate just validated, so a column named here exists.
     *
     * <p>A plain key yields its input column's type (the gate requires the target's to match). A time bucket
     * yields the target column's type where its unit admits it — a day, week or month bucket into {@code date}
     * is lossless — and {@code timestamp} otherwise. The bucket expression itself can be a UTC-midnight timestamp
     * (a day bucket is), so the preview converts a {@code date}-typed key's cells to match.
     */
    @Transactional(readOnly = true)
    public List<PreviewColumn> previewColumns(String inputTable, String target, AggregateSpec spec) {
        final EntityFields input = entityColumns(inputTable);
        final EntityFields output = columnsBy(target, ColumnMappingEntity::getSourceName);
        final List<PreviewColumn> columns = new ArrayList<>();
        for (final GroupKey key : spec.getGroupBy()) {
            final QueryFieldType targetType = output.typeOf(key.outputName());
            final QueryFieldType type;
            if (key.isBucket()) {
                type = targetType != null && key.getTrunc().getUnit().targetTypes().contains(targetType)
                        ? targetType : QueryFieldType.TIMESTAMP;
            } else {
                type = input.typeOf(key.getColumn());
            }
            columns.add(new PreviewColumn(key.outputName(), PreviewColumnRole.KEY, type, targetType));
        }
        for (final Measure measure : spec.getMeasures()) {
            final QueryFieldType targetType = output.typeOf(measure.getName());
            final QueryFieldType type = measureType(measure, input);
            columns.add(new PreviewColumn(measure.getName(), PreviewColumnRole.MEASURE,
                    type == null ? targetType : type, targetType));
        }
        return columns;
    }

    /**
     * The sample columns a preview of an SQL declaration carries, in column-list order: each {@code GROUP BY}
     * position as {@code key} and every other as {@code measure}, named by its target column (see
     * {@link AggregateSqlValidator#previewColumns}).
     */
    @Transactional(readOnly = true)
    public List<PreviewColumn> previewColumns(String inputTable, String target, AggregateSqlValidator.Analysis sql) {
        return AggregateSqlValidator.previewColumns(sql, entityColumns(inputTable), columnsBy(target, ColumnMappingEntity::getSourceName));
    }

    /** The measures whose value is the input's own, for which a {@code timestamp} into {@code date} narrows. */
    public static Set<String> valuePreservingMeasures(AggregateSpec spec) {
        return spec.getMeasures().stream()
                .filter(measure -> "min".equals(measure.getFn()) || "max".equals(measure.getFn()))
                .map(Measure::getName)
                .collect(Collectors.toSet());
    }

    /**
     * The type {@code measure} yields: {@code sum} over an {@code integer} or {@code long} is a {@code long} (the
     * engine's {@code Int64}) and over a {@code decimal} a {@code decimal}; {@code min} and {@code max} yield the
     * input column's own type. Null for a function outside the catalog's measure set.
     */
    private static QueryFieldType measureType(Measure measure, EntityFields input) {
        final String fn = measure.getFn();
        if (MEASURE_TYPES.containsKey(fn)) {
            return MEASURE_TYPES.get(fn);
        }
        final QueryFieldType column = StringUtils.isBlank(measure.getColumn()) ? null : input.typeOf(measure.getColumn());
        if (SUM.equals(fn)) {
            return column == QueryFieldType.INTEGER || column == QueryFieldType.LONG ? QueryFieldType.LONG : QueryFieldType.DECIMAL;
        }
        return "min".equals(fn) || "max".equals(fn) ? column : null;
    }

    /**
     * The <b>derivation</b> half of {@link #validate}, run alone on a declaration write that does not arm
     * the pipeline: an omitted {@code group_by} is expanded from the target's {@code ordering_key} exactly
     * as the gate would expand it, and nothing is refused.
     *
     * <p>Best-effort by contract. A draft may not name an input yet, or name one that is not ACTIVE, and
     * the derivation may simply not apply (the ordering key reads an enrichment column); in every such case
     * this reports {@code false} and leaves the spec as it was, so the caller can keep the stored value
     * rather than write an empty one. It never throws — the refusal for the same condition belongs to the
     * arming gate and to preview, which run {@link #validate} and name it.
     *
     * @return whether {@code spec} carries group keys after the call, authored or derived
     */
    @Transactional(readOnly = true)
    public boolean deriveGroupKeysBestEffort(String inputTable, String target, AggregateSpec spec) {
        if (spec.getGroupBy() != null && !spec.getGroupBy().isEmpty()) {
            return true;
        }
        if (StringUtils.isAnyBlank(inputTable, target)) {
            return false;
        }
        final TableDefinitionEntity targetDefinition = definitions.findById(target).orElse(null);
        final TableDefinitionEntity inputDefinition = definitions.findById(inputTable).orElse(null);
        if (targetDefinition == null || inputDefinition == null
                || inputDefinition.getStatus() != TableStatus.ACTIVE) {
            return false;
        }
        try {
            deriveGroupKeys(spec, targetDefinition, entityColumns(inputTable));
            return true;
        } catch (PipelineValidationException ex) {
            return false;
        }
    }

    /**
     * The enrichments a <b>stored</b> aggregate declaration reaches, resolved against its input's entity.
     *
     * <p>The aggregate twin of the enrich gate's method of the same name, and it exists for the same two
     * callers: the catalog's dependency guards, and a state read's freshness report. Both have to see one
     * answer per declaration whatever kind it is, or a table an aggregate pipeline reads is protected only
     * when an enrichment declaration happens to read it too.
     *
     * <p>Resolved from the declaration's own field names via {@link QualifiedColumnTokens} rather than by
     * re-running the SQL front end over it. That is deliberate and not a shortcut: this is asked over
     * <em>every</em> stored declaration from inside a delete transaction, and a front-end parse per
     * declaration would both cost a translation per node and let one drifted declaration fail an operation
     * about an unrelated table. It also keeps the answer independent of the caller, since the front end
     * resolves through the sensitive-column gate and this does not.
     *
     * <p><b>This method does not throw</b>, whatever the stored declaration holds — a drifted input, an
     * undecodable spec, either. That is load-bearing rather than defensive: it is {@code @Transactional},
     * so an exception escaping it would mark a caller's transaction rollback-only and turn
     * {@code PipelineFreshnessDiagnostics}'s documented "degrade to no diagnostics" into a failed commit
     * (see that class's own resolution method for the whole shape of it).
     */
    @Transactional(readOnly = true)
    public Set<String> referencedEnrichments(PipelineDeclaration declaration) {
        try {
            return referencedEnrichmentsOrThrow(declaration);
        } catch (RuntimeException ex) {
            return Set.of();
        }
    }

    /**
     * As {@link #referencedEnrichments(PipelineDeclaration)}, but <b>refusing</b> rather than degrading.
     *
     * <p>The degradation above is right for a diagnostic and for a delete guard, and wrong for the grant
     * rule: "this declaration cannot be read" and "this declaration reads no enrichment" are the same
     * answer to the caller, and under the subset rule the second one <em>accepts a grant the rule exists
     * to refuse</em>. A caller that cannot tolerate that ambiguity asks this one instead.
     */
    @Transactional(readOnly = true)
    public Set<String> referencedEnrichmentsOrThrow(PipelineDeclaration declaration) {
        if (StringUtils.isBlank(declaration.inputTable())) {
            return sqlOf(declaration) == null ? Set.of() : enrichmentsNamedBy(declaredFieldsOf(declaration));
        }
        final EntityFields input = entityColumns(declaration.inputTable());
        return declaredFieldsOf(declaration).stream()
                .filter(input::isEnrichmentSourced)
                .map(input::ownerOf)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * The enrichment tables a set of scanned tokens names by its {@code <enrichment>.<column>} spelling — the
     * resolution of an SQL draft that has no input yet, whose references cannot be checked against an entity. It
     * contributes whatever its text names: over-approximating here costs a spurious 409, a miss a silent delete.
     */
    private Set<String> enrichmentsNamedBy(Set<String> tokens) {
        final Set<String> enrichments = new LinkedHashSet<>();
        for (final String token : tokens) {
            final int dot = token.indexOf('.');
            if (dot > 0 && dot == token.lastIndexOf('.')) {
                definitions.findById(token.substring(0, dot))
                        .filter(table -> table.getType() == TableType.ENRICHMENT)
                        .ifPresent(table -> enrichments.add(table.getName()));
            }
        }
        return enrichments;
    }

    /** The stored SQL declaration, or null for a form-mode one; decoding only, never a parse. */
    private String sqlOf(PipelineDeclaration declaration) {
        if (declaration.kind() != PipelineKind.AGGREGATE) {
            return null;
        }
        return ((AggregateSpec) specCodec.read(declaration.kind(), declaration.spec())).getSql();
    }

    /**
     * Every field name a stored aggregate declaration mentions — its group keys' input columns, its
     * measures' columns, and the {@code table.column} tokens in its membership filter and in each
     * measure's own {@code where}. In SQL mode it is {@link QualifiedColumnTokens#ofStatement}'s scan of the
     * stored statement instead — qualified tokens, quoted identifiers and bare identifiers, the
     * {@code INSERT} head excluded — so a bare source column the statement reads is covered too, unlike the
     * form path's {@code filter_sql}/{@code where}.
     *
     * <p>Asked before a schema patch drops or renames a column, so it has to cover every position a column
     * reference can appear in. A group key's {@code trunc} form names its column in the same place a plain
     * key does ({@link GroupKey#inputColumn()}), so both are covered by one read.
     */
    @Transactional(readOnly = true)
    public Set<String> declaredFieldsOf(PipelineDeclaration declaration) {
        if (declaration.kind() != PipelineKind.AGGREGATE) {
            return Set.of();
        }
        final AggregateSpec spec = (AggregateSpec) specCodec.read(declaration.kind(), declaration.spec());
        if (spec.getSql() != null) {
            // SQL mode: a token scan of the stored text, never a parse — see QualifiedColumnTokens#ofStatement.
            return QualifiedColumnTokens.ofStatement(spec.getSql());
        }
        final Set<String> fields = new LinkedHashSet<>(QualifiedColumnTokens.of(declaration.filterSql()));
        // Null-guarded although a stored spec never round-trips a null today (NON_NULL serialization plus
        // the model's field initialisers): this runs over EVERY stored declaration during an unrelated
        // schema patch, and a draft is the first declaration that may legitimately carry neither member.
        for (final GroupKey key : spec.getGroupBy() == null ? List.<GroupKey>of() : spec.getGroupBy()) {
            if (StringUtils.isNotBlank(key.inputColumn())) {
                fields.add(key.inputColumn());
            }
        }
        for (final Measure measure : spec.getMeasures() == null ? List.<Measure>of() : spec.getMeasures()) {
            if (StringUtils.isNotBlank(measure.getColumn())) {
                fields.add(measure.getColumn());
            }
            fields.addAll(QualifiedColumnTokens.of(measure.getWhere()));
        }
        return fields;
    }

    /*
     * One table's declared columns, indexed by the name the pipeline surface addresses them with.
     *
     * <p><b>The two sides use different names, deliberately.</b> An input column is named by its
     * <em>exposed</em> name ({@code column_mapping.name}) — the same spelling the query DSL, the filter
     * grammar, and the compiled {@code SELECT}'s field bindings use, so a declaration reads the way a query
     * over the same table reads. A target column is named by its <em>physical</em> name
     * ({@code column_mapping.source_name}) — the spelling the {@code INSERT}'s column list and the target's
     * {@code ordering_key} use, because those are ClickHouse identifiers, not catalog aliases. Indexing each
     * side by the name that side is actually resolved against is what keeps a declaration from validating
     * under one spelling and compiling under the other.
     */

    // --- spec --------------------------------------------------------------------------------------

    /**
     * The freshness guard, which is now a guard on a <b>hand-edited stored row</b> rather than on a request.
     *
     * <p>{@code freshness} is no longer accepted on the wire and no longer echoed: it declared one legal
     * value through three levels of nesting and carried no information. Nothing else changes, and
     * deliberately so — {@link AggregateSpec}'s member stays field-initialised to a periodic intent, so a
     * stored spec carrying no {@code freshness} already reads back as periodic and no stored declaration
     * changes meaning. What this method now catches is an <em>explicit</em> stored null, which the
     * initialiser does not cover because Jackson honours it by calling the setter. That is why the mapper
     * stops touching the member altogether instead of passing a null through where the request's value used
     * to go: doing the latter would make every aggregate registration and every re-arm a 422.
     */
    private void validateFreshness(AggregateSpec spec) {
        final FreshnessMode mode = spec.getFreshness() == null ? null : spec.getFreshness().getMode();
        if (mode == null) {
            throw new PipelineValidationException("freshness.mode is required");
        }
        if (mode != FreshnessMode.PERIODIC) {
            throw new PipelineValidationException("freshness.mode '" + mode.code() + "' is not supported; "
                    + "only 'periodic' is implemented (an insert-time materialized view over partial "
                    + "aggregate states is deferred), so declare 'periodic' and set the refresh cron");
        }
    }

    /**
     * Fills in an omitted {@code group_by} from the <b>target's {@code ordering_key}</b>, and expands it
     * into the stored spec.
     *
     * <p>The grain was declared twice — once as the pipeline's group keys and once as the target's ordering
     * key — and matched by hand by {@link #validateTargetBinding}. Deriving one from the other leaves that
     * check nothing to disagree with for a declaration that states neither, while keeping it meaningful for
     * one that states its keys explicitly.
     *
     * <p><b>Admitted only when every ordering-key column is an input column of the same name.</b> A rollup
     * keyed on {@code trace_id}, {@code chat_id} or {@code response_id} qualifies; a rollup whose key reads a
     * namespaced enrichment column (say {@code usage_client_identity.client_session_id} aliased to
     * {@code client_session_id}) does not, and there is
     * no way to recover that qualified spelling from the target's side. Such a declaration is refused
     * asking for the explicit form rather than guessed at.
     *
     * <p><b>Normalised on write, not derived on read</b>, for three structural reasons:
     * {@link #validateGroupKeys} refuses an empty {@code group_by} outright, {@code AggregateCompiler}
     * reads {@code spec.getGroupBy()} directly, and an {@code ordering_key} column can neither be dropped
     * nor renamed — so a one-time expansion is stable and needs no second derivation path behind every
     * consumer. The honest consequence is that the authored projection shows the expanded key, because that
     * is what was stored.
     *
     * <p>Derivation matches by <b>name</b> and cannot detect a name that means something else: an input
     * column that happens to share the ordering key's name but is not what the author meant would group the
     * wrong thing silently — such a sessions rollup is one {@code dial_usage_log.client_session_id}
     * column away from exactly that. Name-matching cannot close that, so the derived key is echoed expanded
     * on the compiled projection and in the preview, where it is visible before the pipeline is armed.
     */
    private void deriveGroupKeys(AggregateSpec spec, TableDefinitionEntity target, EntityFields input) {
        if (spec.getGroupBy() != null && !spec.getGroupBy().isEmpty()) {
            return;
        }
        final List<String> orderingKey = target.getOrderingKey();
        if (orderingKey == null || orderingKey.isEmpty()) {
            throw new PipelineValidationException("group_by was omitted and target '" + target.getName()
                    + "' declares no ordering_key to derive it from");
        }
        final List<GroupKey> derived = new ArrayList<>(orderingKey.size());
        for (final String column : orderingKey) {
            if (!input.contains(column) || input.isEnrichmentSourced(column)) {
                throw new PipelineValidationException("group_by was omitted, but target '" + target.getName()
                        + "' orders by '" + column + "', which is not a plain column of the input under that"
                        + " name; declare group_by explicitly (an enrichment column is named"
                        + " '<enrichment>.<column>' and needs an 'as' alias)");
            }
            final GroupKey key = new GroupKey();
            key.setColumn(column);
            derived.add(key);
        }
        spec.setGroupBy(derived);
    }

    /**
     * A computed field is completed after a read's statement runs, so no aggregate statement can group or measure it
     * (see {@code openspec/specs/aggregate-pipeline/spec.md}).
     */
    private static void requireNotComputed(String what, String column, EntityFields input) {
        if (column != null && input.isComputed(column)) {
            throw new PipelineValidationException(ComputedFields.declarationMessage(what, column));
        }
    }

    private void validateGroupKeys(AggregateSpec spec, String inputTable, EntityFields input, String filterSql) {
        final List<GroupKey> keys = spec.getGroupBy();
        if (keys == null || keys.isEmpty()) {
            throw new PipelineValidationException("group_by must declare at least one key");
        }
        final Set<String> outputNames = new LinkedHashSet<>();
        int buckets = 0;
        for (final GroupKey key : keys) {
            final boolean hasColumn = StringUtils.isNotBlank(key.getColumn());
            if (hasColumn == key.isBucket()) {
                throw new PipelineValidationException(
                        "each group_by entry must declare exactly one of 'column' or 'trunc'");
            }
            requireNotComputed("group_by", key.inputColumn(), input);
            if (key.isBucket()) {
                buckets++;
                validateBucket(key, inputTable, input);
            } else if (!input.contains(key.getColumn())) {
                throw new PipelineValidationException(unknownField("group_by column", key.getColumn(), inputTable, input));
            }
            // All three run after the column-exists checks above, so none reads an unresolvable column.
            requireEnrichmentAbsenceIsRepresentable("group key", key.inputColumn(), input);
            requireEnrichmentGroupKeyIsAliased(key, input);
            requireNullGroupKeysAccountedFor(key, input, filterSql);
            final String output = key.outputName();
            if (StringUtils.isBlank(output)) {
                throw new PipelineValidationException("a group_by time bucket must declare 'as'");
            }
            if (!outputNames.add(output)) {
                throw new PipelineValidationException("duplicate group_by output name '" + output + "'");
            }
        }
        if (buckets > 1) {
            throw new PipelineValidationException("group_by may declare at most one time bucket; a second "
                    + "one would partition the same time axis twice");
        }
    }

    /**
     * A plain group key over an enrichment column must declare {@code as}.
     *
     * <p>Not a style preference — without it the declaration cannot be satisfied by any target. A key's
     * {@link GroupKey#outputName()} falls back to the column it reads, so an unaliased enrichment key asks
     * for a target column literally named {@code "<enrichment>.<column>"}; target columns are ClickHouse
     * identifiers validated against {@code Identifiers}, which admits no dot. The declaration therefore
     * always fails, but it fails downstream in {@link #validateTargetBinding} against the target's ordering
     * key — pointing the declarer at the target they wrote correctly instead of at the alias they omitted.
     * A time bucket needs no such rule: it already requires {@code as} for every column, enrichment or not.
     */
    private void requireEnrichmentGroupKeyIsAliased(GroupKey key, EntityFields input) {
        if (key.isBucket() || !input.isEnrichmentSourced(key.getColumn())
                || StringUtils.isNotBlank(key.getAs())) {
            return;
        }
        throw new PipelineValidationException("group_by column '" + key.getColumn() + "' is an enrichment "
                + "column and must declare 'as': its qualified flat name carries a '.', which no target "
                + "column name may, so the key needs an alias to bind to one (e.g. \"as\": \""
                + key.getColumn().substring(key.getColumn().indexOf('.') + 1) + "\")");
    }

    /**
     * A value-producing reference to an enrichment column requires that column to be able to hold the
     * <b>absence</b> of an enrichment row — which, for every scalar type, means being declared nullable.
     *
     * <p><b>This is the rule the rest of the enrichment nullability design rests on, and it is not a
     * restatement of {@link #requireNullabilityPreserved}.</b> The design premise everywhere else is "the
     * grain join is outer, so an unmatched input row carries NULL". That is only true of a column the
     * catalog declares nullable. ClickHouse's {@code join_use_nulls} defaults to {@code 0} and every
     * pipeline statement keeps that default (only a caller-written composite SQL statement sets it to 1, and
     * no pipeline statement is one), so an unmatched LEFT JOIN renders a non-nullable right-hand column as
     * its <em>type default</em> — {@code ''}, {@code 0}, {@code false}, the epoch — not as NULL. Every
     * mitigation built
     * on the NULL premise then misses: a mandated {@code filter} spelled {@code IS NOT NULL} excludes
     * nothing, a nullable target column receives a zero rather than a NULL, {@code avg} and {@code min}
     * silently absorb one default per unreached row, and a bucketed key drags its pruning bound back to
     * 1970. Refusing the declaration is the only answer that does not depend on the declarer noticing.
     *
     * <p><b>Only value-producing positions.</b> A group key, a bucketed column and a measure column all copy
     * the joined value into the rollup, so all three are refused. A {@code filter} and a measure's
     * {@code where} are <em>not</em>: they select rows rather than write values, and
     * {@code "<enrichment>.<column>" != ''} is precisely how a declarer excludes unreached rows for a column
     * that cannot be nullable. Refusing predicates would remove the one correct expression of the intent —
     * the same asymmetry {@link #referencedInputColumns} already documents for the propagation rule.
     *
     * <p><b>{@code array}, {@code map} and {@code object} are exempt, and are not an oversight.</b>
     * {@code TableColumnRules.resolveNullable} makes them unconditionally non-nullable because ClickHouse
     * has no {@code Nullable(Array)}, {@code Nullable(Map)} or {@code Nullable(JSON)}, so the rule would
     * refuse them permanently. It does not need to: their type default is <em>empty</em>, which contributes
     * nothing to the only aggregates that accept them (an array measure yields an empty array either way),
     * so absence and emptiness are already indistinguishable by declaration rather than by accident.
     * {@code map} and {@code object} cannot be group keys; an {@code array} can, and an unreached row then
     * groups under the empty array — which is why {@code AggregateCompiler} never restricts an enrichment
     * join by an {@code array} key. The condition itself lives in
     * {@code EntityFields.absenceIsUnrepresentable}, which this method delegates to; the {@code object}
     * case and its measurement are argued there, once, for both registries.
     */
    private void requireEnrichmentAbsenceIsRepresentable(String what, String name, EntityFields input) {
        if (!input.absenceIsUnrepresentable(name)) {
            return;
        }
        throw new PipelineValidationException(what + " '" + name + "' is an enrichment column declared "
                + "non-nullable, and an aggregate may not derive a value from one. It is read through an "
                + "outer grain join, and ClickHouse renders an unmatched non-nullable column as its type "
                + "default rather than as NULL (join_use_nulls defaults to 0), so every input row the "
                + "enrichment has not reached would contribute that default — an empty string, a zero, an "
                + "epoch date — indistinguishable from a measured value. Declare '"
                + name.substring(name.indexOf('.') + 1) + "' nullable on enrichment '"
                + input.ownerOf(name) + "' so absence reads as NULL, or read it in a filter (where it "
                + "selects rows instead of producing a value) rather than in a group key or a measure");
    }

    /**
     * A group key over a <b>nullable</b> input column requires the pipeline to declare a {@code filter}.
     *
     * <p>A NULL in a group key is silently wrong either way, and which way depends on the key's form:
     *
     * <ul>
     *   <li><b>A plain column</b> — NULL is its own group, and the target column cannot represent it: the
     *       group-key columns <em>are</em> the target's {@code ordering_key}, and ClickHouse refuses a
     *       nullable sorting key, so there is no target shape that could hold it. With
     *       {@code insert_null_as_default} on (the default) the engine stores the column's type default
     *       instead — an empty string, a zero, an epoch date — and the rollup carries a key that looks like
     *       measured data. This is the {@link #requireNullabilityPreserved} failure with the escape route
     *       closed: for a measure the answer is "declare the target column nullable", and here that answer
     *       does not exist.</li>
     *   <li><b>A time bucket</b> — NULL cannot be bucketed at all. The pruning bound in
     *       {@code AggregateCompiler} is a comparison on the raw column, so every row missing the timestamp
     *       drops out of the recompute and out of the rollup, whose totals then stop reconciling with the
     *       input with nothing recording why.</li>
     * </ul>
     *
     * <p><b>Why a declared filter is accepted as the answer.</b> Nothing here parses the predicate, and
     * deliberately: inferring whether an arbitrary boolean excludes NULLs is a job this validator would do
     * badly, and doing it badly means either refusing a correct declaration or passing a wrong one. What the
     * rule can tell is whether the declarer ever considered the question — a pipeline with no membership
     * predicate at all has not. So a filter is taken as the statement that the NULLs are accounted for, which
     * keeps {@code col IS NOT NULL} (the honest fix) working while still refusing the declaration that
     * silently ignores them. Rejecting a nullable key outright would leave that correct declaration with no
     * way to be expressed.
     */
    private void requireNullGroupKeysAccountedFor(GroupKey key, EntityFields input, String filterSql) {
        final String column = key.inputColumn();
        if (StringUtils.isNotBlank(filterSql) || !input.isNullable(column)) {
            return;
        }
        final String consequence = key.isBucket()
                ? "a NULL cannot be bucketed, so every row missing that value is dropped from the rollup and "
                        + "its totals stop reconciling with the input"
                : "a NULL is its own group, and the target's ordering_key cannot hold one (ClickHouse "
                        + "refuses a nullable sorting key), so it would be stored as that column's type "
                        + "default — an empty string, a zero, an epoch date — and read as measured data";
        throw new PipelineValidationException("group key '" + key.outputName() + "' reads nullable input "
                + "column '" + column + "' and this pipeline declares no filter: " + consequence
                + ". Declare a filter that settles it (e.g. \"" + column + " IS NOT NULL\"), which is taken "
                + "as the declaration that the NULLs are accounted for, or group a non-nullable column");
    }

    private void validateBucket(GroupKey key, String inputTable, EntityFields input) {
        final GroupKey.Trunc trunc = key.getTrunc();
        if (StringUtils.isBlank(trunc.getColumn())) {
            throw new PipelineValidationException("a group_by trunc must name a column");
        }
        if (trunc.getUnit() == null) {
            throw new PipelineValidationException("a group_by trunc must name a unit; supported units are "
                    + supportedUnits());
        }
        if (!trunc.getUnit().isSupported()) {
            // Notably a calendar month, which the fixed-width truncation cannot express. Refused rather
            // than approximated, because a 30-day "month" is wrong in a way nobody would notice.
            throw new PipelineValidationException("group_by trunc unit '" + trunc.getUnit().code()
                    + "' is not supported; the available truncation is fixed-width, so the supported units "
                    + "are " + supportedUnits());
        }
        final QueryFieldType type = input.typeOf(trunc.getColumn());
        if (type == null) {
            throw new PipelineValidationException(
                    unknownField("group_by trunc column", trunc.getColumn(), inputTable, input));
        }
        if (!BUCKETABLE.contains(type)) {
            throw new PipelineValidationException("group_by trunc column '" + trunc.getColumn()
                    + "' is declared '" + type.code() + "'; a time bucket needs a date or timestamp column");
        }
    }

    /**
     * Validates every measure and returns each one's resolved catalog function, keyed by measure name, so
     * the target binding below reuses the resolution instead of scanning the catalog a second time.
     */
    private Map<String, QueryFunctionDto> validateMeasures(AggregateSpec spec, String inputTable, EntityFields input) {
        final List<Measure> measures = spec.getMeasures();
        if (measures == null || measures.isEmpty()) {
            throw new PipelineValidationException("measures must declare at least one entry");
        }
        final Map<String, QueryFunctionDto> resolved = new LinkedHashMap<>();
        for (final Measure measure : measures) {
            if (StringUtils.isBlank(measure.getName())) {
                throw new PipelineValidationException("every measure must declare the target column 'name'");
            }
            final QueryFunctionDto fn = aggregateFunction(measure);
            requireSingleColumnArity(measure, fn);
            requireScalarInputFunction(measure, fn);
            requireDistinctSupported(measure, fn);
            if (resolved.put(measure.getName(), fn) != null) {
                throw new PipelineValidationException("duplicate measure name '" + measure.getName() + "'");
            }
            final boolean countStar = COUNT.equals(fn.name()) && StringUtils.isBlank(measure.getColumn());
            if (!countStar) {
                if (StringUtils.isBlank(measure.getColumn())) {
                    throw new PipelineValidationException("measure '" + measure.getName() + "' with fn '"
                            + fn.name() + "' must name a column");
                }
                if (!input.contains(measure.getColumn())) {
                    throw new PipelineValidationException(
                            unknownField("measure column", measure.getColumn(), inputTable, input));
                }
                requireNotComputed("measure '" + measure.getName() + "'", measure.getColumn(), input);
                requireEnrichmentAbsenceIsRepresentable("measure column", measure.getColumn(), input);
            }
        }
        return resolved;
    }

    /**
     * Parses every measure's {@code where} through the shared filter grammar, so a malformed predicate is a
     * 422 at declaration rather than a broken statement at refresh.
     *
     * <p>Runs as its own pass after {@link #validateMeasures} for a reason worth stating: parsing reaches
     * the SQL front end and the sensitive-column guard, and doing it inside the arity/column loop would
     * interleave those failures with the cheap structural ones, so a declaration with both a bad column and
     * a bad predicate would report whichever came first per measure rather than the structural error that
     * explains the rest. {@link #referencedInputColumns} parses again to collect the columns for the
     * entitlement gate; both go through {@link PipelineFilters}, so the two agree by construction.
     */
    private void validateMeasurePredicates(AggregateSpec spec, String inputTable) {
        for (final Measure measure : spec.getMeasures()) {
            if (StringUtils.isNotBlank(measure.getWhere())) {
                PipelineFilters.analyze(filterValidator, sensitiveGuard, inputTable, measure.getWhere());
            }
        }
    }

    /**
     * A measure's function must be satisfiable by the one column a measure carries.
     *
     * <p>The catalog's aggregate group is shared with the query surface, which has a full argument list;
     * a measure has {@code column} and nothing else. So an aggregate declaring two required arguments has
     * no way to be expressed as a measure, and without this check the mismatch would be invisible until
     * execution: {@link #validateMeasures} would confirm the column exists, the declaration would be stored
     * and enabled, and the arity failure would then be raised by {@code ExprTranslator} inside a scheduled
     * refresh, surfacing as a pipeline whose every run fails for a reason recorded only in
     * {@code last_error}. Refusing it at declaration is the same argument the rest of this gate makes, one
     * level up: reject what cannot work, where the operator can still see why.
     *
     * <p><b>This is a live restriction, not a guard on hypotheticals.</b> {@code arg_max(value, order)} is
     * in the catalog and is exactly the shape that trips it: available at query time, refused as a measure.
     * That is the intended outcome — adding a two-operand aggregate to the shared catalog would otherwise
     * silently widen the measure vocabulary to include something a measure cannot express. The check is
     * keyed on the descriptor's <em>required</em> arity rather than a function-name list, which is why
     * {@code arg_max} was covered by it without this class being edited, and why the next such addition
     * will be too. {@code count}'s single argument is optional (arity 0), and no catalog aggregate is
     * variadic.
     *
     * <p>The principled way to lift this is a third measure qualifier — an {@code order_by} <em>column</em>
     * beside the existing {@code where} and {@code distinct}, qualifying which row wins as they qualify
     * which rows and which values are seen. That reopens {@code Measure}'s D-DECL-4 and has to weigh one
     * genuine objection: an {@code arg_max} measure is not re-aggregatable across stored rows, so a
     * materialized "latest value" someone later sums across buckets is silently wrong.
     */
    private void requireSingleColumnArity(Measure measure, QueryFunctionDto fn) {
        final long required = fn.args().stream()
                .filter(arg -> !Boolean.TRUE.equals(arg.optional()))
                .count();
        if (required > 1) {
            throw new PipelineValidationException("measure '" + measure.getName() + "' declares fn '"
                    + fn.name() + "', which takes " + required + " arguments (" + fn.signature() + "); a "
                    + "measure carries exactly one input column, so it cannot express that call. Aggregate "
                    + "it at query time instead, or derive the value into a single input column first");
        }
    }

    /**
     * Refuses a measure whose function <b>consumes an array</b> — {@code groupUniqArrayArray} and
     * {@code groupArrayArray}, ClickHouse's {@code -Array} combinator pair. They are in the shared
     * catalog's aggregate group for the query surface, and the measure vocabulary is derived from that
     * group, so without this gate they would widen the declaration surface silently.
     *
     * <p><b>Why here rather than leaving it to the array-measure rule.</b> That rule would refuse the
     * declaration too, on the grounds that "a set of arrays is not a declarable column type" — true for
     * {@code group_uniq_array} and <em>false</em> for a function whose whole purpose is to flatten the
     * arrays it reads. A refusal carrying a reason that does not hold is worse than no message, because
     * the author's next move is to work around a constraint that is not the real one. The real one is the
     * vocabulary's own: a measure is one function over one <b>scalar</b> input column.
     *
     * <p>Sibling of {@link #requireSingleColumnArity}, which refuses {@code arg_max} for the same class of
     * reason — available at query time, not expressible as a measure. Unlike that gate this one is keyed
     * on a name set, because the catalog descriptor does not carry operand types; the set is
     * {@code ArrayValuedFunctions}', so a flattening aggregate added there is covered here too.
     */
    private void requireScalarInputFunction(Measure measure, QueryFunctionDto fn) {
        if (ArrayValuedFunctions.isFlatteningAggregate(fn.name())) {
            throw new PipelineValidationException("measure '" + measure.getName() + "' declares fn '"
                    + fn.name() + "', which reads an array column; a measure's input column must be a "
                    + "scalar. Aggregate it at query time instead, or derive a scalar column on the input "
                    + "first");
        }
    }

    /**
     * A measure's {@code distinct} quantifier must be one the function accepts, over a column there is
     * something to deduplicate.
     *
     * <p>Keyed on the catalog descriptor's {@code distinct_supported} rather than on a function-name list
     * here, for the reason {@link #requireSingleColumnArity} is keyed on its arity: the catalog is shared
     * with the query surface, so reading the flag off the descriptor is what keeps the two from disagreeing
     * about the same function, and a future aggregate that accepts the quantifier is covered without editing
     * this class.
     *
     * <p>{@code group_uniq_array} is named explicitly, but only to give it the message the query surface
     * already gives ({@code ArrayValuedFunctions.rejectDistinct}) — the flag is not unsupported there so much
     * as redundant, and reporting "does not accept distinct" would leave an operator wondering how else to
     * ask for distinct values from a function that only returns them. The generic branch below still covers
     * it if this one is ever removed.
     *
     * <p><b>Stricter than the query path, deliberately.</b> The DSL ignores the quantifier for
     * {@code min}/{@code max} rather than refusing it, which is harmless there — a minimum over deduplicated
     * values is the same minimum, and the query is gone once answered. A measure is not: it is stored,
     * scheduled, and read back by operators as the description of a materialized column, so a quantifier
     * that silently does nothing is a declaration that misdescribes its own output for as long as it exists.
     * Refusing it at declaration is the same argument the rest of this gate makes.
     *
     * <p>The column requirement covers exactly one reachable shape, {@code count} with no column — every
     * other function already fails the column check in {@link #validateMeasures}. A column-less
     * {@code count} is a row count, so there is no value to deduplicate and the only honest rendering is the
     * non-distinct one; accepting the pair would store a declaration whose text and output disagree.
     */
    private void requireDistinctSupported(Measure measure, QueryFunctionDto fn) {
        if (!measure.isDistinct()) {
            return;
        }
        if (GROUP_UNIQ_ARRAY.equals(fn.name())) {
            throw new PipelineValidationException("measure '" + measure.getName() + "' declares fn '"
                    + fn.name() + "' with 'distinct'; it already returns the distinct values of the group");
        }
        if (!fn.distinctSupported()) {
            throw new PipelineValidationException("measure '" + measure.getName() + "' declares 'distinct' "
                    + "with fn '" + fn.name() + "', which does not accept it (" + fn.signature() + "); the "
                    + "aggregates that do are the ones the function catalog marks 'distinct_supported'");
        }
        if (StringUtils.isBlank(measure.getColumn())) {
            throw new PipelineValidationException("measure '" + measure.getName() + "' declares 'distinct' "
                    + "with no column; a column-less '" + fn.name() + "' counts rows, and rows are not a "
                    + "value to deduplicate — name the column whose distinct values it should count");
        }
    }

    /** Resolves a measure's function against the shared catalog's <em>aggregate</em> group. */
    private QueryFunctionDto aggregateFunction(Measure measure) {
        if (StringUtils.isBlank(measure.getFn())) {
            throw new PipelineValidationException("measure '" + measure.getName() + "' declares no fn");
        }
        return functionCatalog.listFunctions().stream()
                .filter(fn -> fn.group() == QueryFunctionGroup.AGGREGATE)
                .filter(fn -> fn.name().equals(measure.getFn()))
                .findFirst()
                .orElseThrow(() -> new PipelineValidationException("measure fn '" + measure.getFn()
                        + "' is not a supported aggregate function; supported: " + aggregateFunctionNames()));
    }

    // --- target binding ---------------------------------------------------------------------------

    /**
     * Binds the declaration to the target: the group-key output names must equal the target's
     * {@code ordering_key} in order, every group key and measure must land on a type-compatible target
     * column, a column derived from a sensitive input column must itself be sensitive, and no target column
     * may be left unwritten.
     */
    private void validateTargetBinding(AggregateSpec spec, TableDefinitionEntity target, EntityFields input,
            EntityFields output, Map<String, QueryFunctionDto> functions) {
        final List<String> groupOutputs = spec.groupKeyOutputNames();
        final List<String> orderingKey = target.getOrderingKey();
        if (!groupOutputs.equals(orderingKey)) {
            throw new PipelineValidationException("target '" + target.getName() + "' is ordered by "
                    + orderingKey + " but group_by yields " + groupOutputs + "; they must match in order, "
                    + "because the ordering key is the key the target collapses on");
        }
        final Set<String> written = new LinkedHashSet<>();
        for (final GroupKey key : spec.getGroupBy()) {
            final String column = key.outputName();
            final QueryFieldType declared = output.typeOf(column);
            if (declared == null) {
                throw new PipelineValidationException("group key '" + column
                        + "' has no matching column on target '" + target.getName() + "'");
            }
            requireGroupKeyTypeAccepts(key, declared, input);
            AggregateGovernance.requireSensitivityPropagated(key.inputColumn(), column, input, output, target.getName());
            written.add(column);
        }
        for (final Measure measure : spec.getMeasures()) {
            final QueryFieldType declared = output.typeOf(measure.getName());
            if (declared == null) {
                throw new PipelineValidationException("measure '" + measure.getName()
                        + "' has no matching column on target '" + target.getName() + "'");
            }
            requireTypeAccepts(measure, functions.get(measure.getName()), declared);
            requireArrayElementAccepts(measure, functions.get(measure.getName()), input, output, target.getName());
            requireFractionPreserved(measure, input, declared);
            requireNullabilityPreserved(measure, input, output, target.getName());
            AggregateGovernance.requireSensitivityPropagated(measure.getColumn(), measure.getName(), input, output, target.getName());
            written.add(measure.getName());
        }
        final List<String> unwritten = output.names().stream()
                .filter(column -> !written.contains(column))
                .toList();
        if (!unwritten.isEmpty()) {
            throw new PipelineValidationException("target '" + target.getName() + "' declares column(s) "
                    + unwritten + " that no group key or measure writes; they could only ever hold a type "
                    + "default, which would read as real data");
        }
    }

    private void requireTypeAccepts(Measure measure, QueryFunctionDto fn, QueryFieldType declared) {
        // SAME_AS_ARGUMENT (min/max) follows the input column, so any declared type is plausible and the
        // engine reports a genuine mismatch at execution; the closed numeric returns are checked here.
        AggregateGovernance.requireReturnTypeAccepts(measure.getName(), fn.returns(), declared);
    }

    /**
     * An array measure's target column must hold the <em>input column's</em> values: its declared
     * {@code element_type} has to equal the input column's declared type, and the input column has to be a
     * scalar in the first place.
     *
     * <p>This is {@link #requireGroupKeyTypeAccepts} one level down, and exact for the same reason: the set
     * a {@code group_uniq_array} collects holds the input values themselves, unconverted, so there is no
     * widening to allow. Left unchecked the mismatch does not surface as an error — ClickHouse's
     * {@code INSERT … SELECT} converts positionally, so a {@code long} input collected into an
     * {@code array&lt;string&gt;} column stores {@code ["100","250"]}: stringified numbers that every reader
     * then treats as text. The reverse direction ({@code string} input, {@code array&lt;long&gt;} target) does
     * fail, but inside a scheduled refresh rather than at the declaration that caused it — the same argument
     * {@link #requireFractionPreserved} and {@link #requireNullabilityPreserved} make.
     *
     * <p>An {@code array}, {@code object} or {@code map} input column is refused outright: a set of any of
     * them is a nested type no {@code element_type} can name, and the SQL surface refuses the identical
     * expression.
     *
     * <p>A target column declaring no {@code element_type} at all (a catalog row predating element-type
     * validation) is left alone — the check confines itself to the pair it can compare.
     */
    private void requireArrayElementAccepts(
            Measure measure, QueryFunctionDto fn, EntityFields input, EntityFields output, String target) {
        if (fn.returns() != QueryFunctionReturnType.ARRAY || StringUtils.isBlank(measure.getColumn())) {
            return;
        }
        final QueryFieldType source = input.typeOf(measure.getColumn());
        AggregateGovernance.requireCollectsScalar(measure.getName(), measure.getFn(), measure.getColumn(), source);
        AggregateGovernance.requireArrayElementMatches(
                measure.getName(), measure.getColumn(), source, output.elementTypeOf(measure.getName()), target);
    }

    /**
     * A measure whose result carries a fraction must land in a {@code decimal} column.
     *
     * <p>{@link AggregateGovernance#RETURN_TYPE_TARGETS} can only be as precise as the catalog's declared return type, and
     * {@code NUMERIC} covers both {@code sum} — legitimately stored in a {@code long} — and {@code avg},
     * whose result is fractional whatever it reads. ClickHouse converts on insert rather than refusing, so
     * {@code avg} bound to a {@code long} column stores a truncated average: a number that is plausible,
     * wrong, and carries no indication of either. The same holds for a {@code sum} over a {@code decimal}
     * input column, which is a decimal total.
     *
     * <p>{@code min}/{@code max} need no rule here — they return the input value itself, and
     * {@link #requireTypeAccepts} already leaves {@code SAME_AS_ARGUMENT} to the engine.
     */
    private void requireFractionPreserved(Measure measure, EntityFields input, QueryFieldType declared) {
        final String fn = measure.getFn();
        final boolean fractional = AVG.equals(fn)
                || (SUM.equals(fn) && input.typeOf(measure.getColumn()) == QueryFieldType.DECIMAL);
        AggregateGovernance.requireFractionPreserved(measure.getName(), fn, measure.getColumn(), fractional, declared);
    }

    /**
     * A measure that can return NULL must land in a nullable column.
     *
     * <p>{@code sum}, {@code avg}, {@code min} and {@code max} all return NULL for a group holding no
     * non-null value, which happens exactly when their input column is nullable. ClickHouse does not refuse
     * that NULL on insert into a non-nullable column — it converts it to the type default and stores
     * {@code 0}. The rollup then reports a zero indistinguishable from a measured one: "no data for this
     * key" and "this key totalled zero" are different facts, and only the second survives.
     *
     * <p>This is the {@link #requireFractionPreserved} failure exactly — an engine that coerces rather than
     * refusing turns a declaration mistake into a plausible number — and is refused for the same reason
     * rather than papered over with {@code ifNull(measure, 0)}. Whether absent means zero is a question only
     * the declarer can answer: for a token count zero may be fair, for a price or a {@code min} latency it is
     * a fabricated data point that will be averaged and charted.
     *
     * <p>Keyed on the <em>input column</em>, not the function: {@code sum} over a non-nullable column cannot
     * be NULL, so keying on the function would refuse a declaration that is fine. The {@link AggregateGovernance#NEVER_NULL_AGGREGATES}
     * aggregates are exempt outright — {@code count} never returns NULL and may name no input column at all,
     * so it cannot go through the input rule, and {@code group_uniq_array} answers an all-NULL group with an
     * empty array, which is the honest value rather than a fabricated zero.
     *
     * <p><b>A {@code where} is the second way in, and it does not go through the input column.</b> A measure
     * scoped to part of its group returns NULL whenever <em>no</em> row of a group satisfies the predicate,
     * and that is independent of whether the aggregated column is nullable: {@code sum(deployment_price)
     * where parent_deployment IS NULL} over a group whose rows are all mid-chain has nothing to sum, and a
     * non-nullable target would store the zero that reads as "this chain was free". So a declared
     * {@code where} requires a nullable target on its own, and the check cannot be keyed on the input column
     * alone. The {@link AggregateGovernance#NEVER_NULL_AGGREGATES} exemption still holds and for the same reasons — a scoped {@code count}
     * of nothing is 0, a scoped {@code group_uniq_array} of nothing is the empty set, and both are the
     * honest answer rather than a fabricated one.
     */
    private void requireNullabilityPreserved(Measure measure, EntityFields input, EntityFields output, String target) {
        if (AggregateGovernance.NEVER_NULL_AGGREGATES.contains(measure.getFn()) || output.isNullable(measure.getName())) {
            return;
        }
        final String column = measure.getColumn();
        final boolean nullableInput = StringUtils.isNotBlank(column) && input.isNullable(column);
        final boolean scoped = StringUtils.isNotBlank(measure.getWhere());
        if (!nullableInput && !scoped) {
            return;
        }
        // Both causes can hold at once; name the scoping first, because it is the one the declarer just
        // added and the one whose remedy is not "the column was always like that".
        final String cause = scoped
                ? "is scoped by a 'where', so it yields NULL for a group in which no row matches"
                : "over nullable input column '" + column + "', so it yields NULL for a group holding no value";
        throw new PipelineValidationException("measure '" + measure.getName() + "' computes '"
                + measure.getFn() + "' " + cause + ", but target column '" + measure.getName() + "' on '"
                + target + "' is declared non-nullable; ClickHouse would store the type default and the "
                + "rollup would read as a measured zero. Declare the target column nullable — its "
                + "nullability is fixed at DDL, so this is a change to the table, not to the pipeline");
    }

    /**
     * A group key's target column must be able to hold what the key produces. Unlike a measure, a group key
     * is not an aggregate — it is the input value itself (or a truncation of it), so the target column must
     * carry the same declared type. Without this check the mismatch reaches ClickHouse, which either rejects
     * the statement (a 400 for what is really a bad declaration) or silently coerces the value.
     */
    private void requireGroupKeyTypeAccepts(GroupKey key, QueryFieldType declared, EntityFields input) {
        if (key.isBucket()) {
            // Which temporal type will hold the bucket depends on its width, so the rule is the unit's:
            // an hour bucket in a `date` column loses the hour, and since the bucket is part of the
            // ordering_key the engine then collapses a whole day's 24 rows onto one arbitrary survivor.
            final TruncUnit unit = key.getTrunc().getUnit();
            if (!unit.targetTypes().contains(declared)) {
                throw new PipelineValidationException("group key '" + key.outputName() + "' is a '"
                        + unit.code() + "' time bucket but the target column is declared '" + declared.code()
                        + "'; it must be declared " + typeList(unit.targetTypes()) + ", or the bucket start "
                        + "is truncated on insert and the keys it distinguishes collapse into one row");
            }
            return;
        }
        final QueryFieldType source = input.typeOf(key.getColumn());
        if (source != declared) {
            throw new PipelineValidationException("group key '" + key.outputName() + "' groups input column '"
                    + key.getColumn() + "' declared '" + source.code() + "' but the target column is declared '"
                    + declared.code() + "'; a group key is the input value itself, so the types must match");
        }
    }

    // --- referenced columns ------------------------------------------------------------------------

    /**
     * Every input column the declaration reads: the filter predicate, the group keys, the measure columns,
     * and each measure's own {@code where} predicate.
     *
     * <p>A measure predicate has to be in this set for the same reason the pipeline filter is: a predicate
     * reads its columns even though it derives nothing from them, and the entitlement check is on reading.
     * Omitting it would leave {@code sum(x) where jwt_claims = '…'} as a way to test a protected column from
     * a caller not entitled to see it — the aggregate would be over a subset chosen by a value the caller
     * cannot read, which leaks that value one comparison at a time.
     *
     * <p>Note the deliberate asymmetry with {@link AggregateGovernance#requireSensitivityPropagated}, which is <em>not</em>
     * applied to predicate columns here or for the pipeline filter: a predicate selects rows, it does not
     * copy values into the target, so the propagation rule (which exists to stop a protected value being
     * written into an unprotected column) has nothing to attach to. Predicates get the entitlement check;
     * derivations get both.
     */
    private Set<String> referencedInputColumns(AggregateSpec spec, String inputTable, String filterSql) {
        // PipelineFilters does both attributions: a hidden sensitive column becomes the 403 it really is
        // (a non-entitled caller cannot parse a predicate over a column its schema view omits), and a
        // genuine grammar failure becomes this capability's own 422 rather than the enrichment registry's.
        final Set<String> referenced = new LinkedHashSet<>(
                PipelineFilters.analyze(filterValidator, sensitiveGuard, inputTable, filterSql).columns());
        for (final GroupKey key : spec.getGroupBy()) {
            referenced.add(key.inputColumn());
        }
        for (final Measure measure : spec.getMeasures()) {
            if (StringUtils.isNotBlank(measure.getColumn())) {
                referenced.add(measure.getColumn());
            }
            if (StringUtils.isNotBlank(measure.getWhere())) {
                referenced.addAll(PipelineFilters
                        .analyze(filterValidator, sensitiveGuard, inputTable, measure.getWhere())
                        .columns());
            }
        }
        return referenced;
    }

    /**
     * The refusal for a field reference that resolves to nothing, naming the enrichment when the reference
     * was qualified with one. A declaration addresses an enrichment column as {@code "<enrichment>.<column>"},
     * so a typo can be in either half — and "is not a declared column of input 'dial_usage_log'" is a
     * misleading answer for a name that was never meant to be a column of the input at all.
     */
    private static String unknownField(String what, String name, String inputTable, EntityFields input) {
        final int dot = name == null ? -1 : name.indexOf('.');
        if (dot > 0) {
            final String owner = name.substring(0, dot);
            if (input.enrichmentOwners().contains(owner)) {
                return what + " '" + name + "' is not a declared column of enrichment '" + owner
                        + "', which is reached through input '" + inputTable + "'";
            }
        }
        final String qualified = input.enrichmentOwners().isEmpty() ? ""
                : "; an enrichment column is named '<enrichment>.<column>', one of "
                        + String.join(", ", input.enrichmentOwners());
        return what + " '" + name + "' is not a declared column of input '" + inputTable + "'" + qualified;
    }

    /** The target's own declared columns, indexed by {@code key} — no enrichments; see {@link EntityFields}. */
    private EntityFields columnsBy(String table, Function<ColumnMappingEntity, String> key) {
        return entityFields.tableOf(table, key);
    }

    /**
     * The input's flat <em>entity</em> field set — its own columns plus every ACTIVE enrichment's, under
     * the qualified flat name — resolved by {@link EntityFieldResolver} so the enrich declaration gate and
     * this one decide membership once. See {@link EntityFieldResolver#entityOf} for why it is read from
     * the catalog rows rather than from the role-filtered query model.
     */
    private EntityFields entityColumns(String inputTable) {
        return entityFields.entityOf(inputTable);
    }

    private static String supportedUnits() {
        return TruncUnit.supportedCodes();
    }

    /** The wire codes of a set of declared column types, for an error message to name. */
    private static String typeList(Set<QueryFieldType> types) {
        return types.stream().map(QueryFieldType::code).collect(Collectors.joining(" or "));
    }

    private String aggregateFunctionNames() {
        return functionCatalog.listFunctions().stream()
                .filter(fn -> fn.group() == QueryFunctionGroup.AGGREGATE)
                .map(QueryFunctionDto::name)
                .sorted()
                .collect(Collectors.joining(", "));
    }
}
