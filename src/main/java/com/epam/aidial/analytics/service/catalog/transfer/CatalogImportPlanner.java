package com.epam.aidial.analytics.service.catalog.transfer;

import com.epam.aidial.analytics.exception.TableValidationException;
import com.epam.aidial.analytics.service.enrichment.model.TransformType;
import com.epam.aidial.analytics.service.pipeline.PipelineKind;
import com.epam.aidial.analytics.service.table.ColumnInput;
import com.epam.aidial.analytics.service.table.DefineSchemaInput;
import com.epam.aidial.analytics.service.table.TableCatalogService;
import com.epam.aidial.analytics.service.table.TableColumnRules;
import com.epam.aidial.analytics.service.table.TableSchemaCreationService;
import com.epam.aidial.analytics.service.table.TableStatus;
import com.epam.aidial.analytics.service.table.TableType;
import com.epam.aidial.analytics.web.dto.catalog.BundlePipeline;
import com.epam.aidial.analytics.web.dto.catalog.BundleTable;
import com.epam.aidial.analytics.web.dto.catalog.CatalogBundle;
import com.epam.aidial.analytics.web.dto.catalog.EnvSpecificValue;
import com.epam.aidial.analytics.web.dto.catalog.ResolutionPolicy;
import com.epam.aidial.analytics.web.dto.enrichment.OutputSpec;
import com.epam.aidial.analytics.web.dto.enrichment.TransformVarDto;
import com.epam.aidial.analytics.web.dto.enrichment.VarSpec;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineRequests.GroupKeyRequest;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineRequests.MeasureRequest;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineRequests.PatchTransformRequest;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineRequests.TriggerRequest;
import com.epam.aidial.analytics.web.dto.table.CreateTableRequest;
import com.epam.aidial.analytics.web.dto.table.CreateTableRequest.ColumnRequest;
import com.epam.aidial.analytics.web.dto.table.DefineSchemaRequest;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plans a catalog import against a snapshot of the target ({@link ImportTarget}), touching no repository:
 * checks the required system tables, classifies each collision by the resolution policy,
 * checks that every dependency is in the bundle or already on the target, orders the objects, checks the
 * catalog limits on the totals, and collects the environment-specific values.
 *
 * <p>What the planner cannot see — identifier grammar, column rules, grant invariants, the pipeline gates —
 * is left to the validation pass, which runs the real services ({@link CatalogImportValidator}).
 */
@Component
public class CatalogImportPlanner {

    /** A quoted qualified field, {@code "<table>.<column>"}: the only spelling an enrichment column has. Its table is lowercased on use. */
    private static final Pattern QUALIFIED_TOKEN = Pattern.compile("\"([A-Za-z_][A-Za-z0-9_]*)\\.[A-Za-z_][A-Za-z0-9_]*\"");

    /** A whole column-valued member naming a qualified field without quotes. */
    private static final Pattern QUALIFIED_COLUMN = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)\\.[A-Za-z_][A-Za-z0-9_]*$");

    /** A table an SQL declaration's {@code FROM} names, quoted or bare. */
    private static final Pattern FROM_TABLE = Pattern.compile("\\bFROM\\s+(?:\"([^\"]+)\"|([A-Za-z_][A-Za-z0-9_]*))",
            Pattern.CASE_INSENSITIVE);

    /** A common table expression's name, {@code WITH x AS (} or {@code , x AS (}, quoted or bare. */
    private static final Pattern CTE_NAME = Pattern.compile("(?:\\bWITH|,)\\s*(?:\"([^\"]+)\"|([A-Za-z_][A-Za-z0-9_]*))\\s+AS\\s*\\(",
            Pattern.CASE_INSENSITIVE);

    /** A quoted literal that starts with an absolute date. */
    private static final Pattern DATE_LITERAL = Pattern.compile("'(\\d{4}-\\d{2}-\\d{2}[^']*)'");

    private final JsonMapper jsonMapper;

    public CatalogImportPlanner(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    ImportPlan plan(CatalogBundle bundle, boolean acknowledgeReusedNames, ResolutionPolicy policy, ImportTarget target) {
        final List<EnvSpecificValue> envSpecific = new ArrayList<>();
        final List<String> validationErrors = new ArrayList<>();

        final List<PlannedObject> systemTables = new ArrayList<>();
        for (final String name : nullSafe(bundle.requiredSystemTables())) {
            systemTables.add(planSystemTable(new PlannedObject(PlannedObject.Kind.SYSTEM_TABLE, name, null), target));
        }

        final List<PlannedObject> tables = new ArrayList<>();
        for (final BundleTable table : orderedTables(bundle.tables())) {
            tables.add(planTable(new PlannedObject(PlannedObject.Kind.TABLE, table.table().name(), table), policy, target,
                    envSpecific, validationErrors));
        }

        final List<PlannedObject> pipelines = new ArrayList<>();
        for (final BundlePipeline pipeline : orderedPipelines(bundle.pipelines())) {
            pipelines.add(planPipeline(new PlannedObject(PlannedObject.Kind.PIPELINE, pipeline.pipeline().name(), pipeline),
                    policy, acknowledgeReusedNames, target, envSpecific));
        }

        requireUnique(tables, "table", validationErrors);
        requireUnique(pipelines, "pipeline", validationErrors);
        requireUnique(systemTables, "required system table", validationErrors);
        requireDistinctKinds(systemTables, tables, validationErrors);
        requireClosure(systemTables, tables, pipelines, target);
        requireLimits(tables, target, validationErrors);
        return new ImportPlan(systemTables, tables, pipelines, envSpecific, validationErrors);
    }

    // --- required system tables ----------------------------------------------------------------------

    /**
     * A required system table must exist here, be system-flagged and be ACTIVE. Its columns are not checked here:
     * the validation pass arms each pipeline against the real table, which names any column a pipeline reads
     * that is missing. Its grants are never compared or written: they are this environment's operator's.
     */
    private static PlannedObject planSystemTable(PlannedObject planned, ImportTarget target) {
        final String name = planned.name();
        final ImportTarget.Table existing = target.tables().get(name);
        if (existing == null) {
            planned.fail("system table '" + name + "' does not exist on this environment");
            return planned;
        }
        planned.skip();
        if (!existing.system()) {
            planned.fail("table '" + name + "' is not a system table on this environment");
        } else if (existing.status() != TableStatus.ACTIVE) {
            planned.fail("system table '" + name + "' is not ACTIVE on this environment");
        }
        return planned;
    }

    // --- tables --------------------------------------------------------------------------------------

    private PlannedObject planTable(PlannedObject planned, ResolutionPolicy policy, ImportTarget target,
            List<EnvSpecificValue> envSpecific, List<String> validationErrors) {
        final BundleTable table = planned.table();
        final DefineSchemaRequest schema = table.schema();
        if (schema.ttl() != null) {
            envSpecific.add(new EnvSpecificValue(PlannedObject.Kind.TABLE.code(), planned.name(), "ttl",
                    schema.ttl().interval() + " " + schema.ttl().unit()));
        }
        final ImportTarget.Table existing = target.tables().get(planned.name());
        if (existing != null && existing.system()) {
            planned.fail("table '" + planned.name() + "' is a system table on this environment");
            return planned;
        }
        if (existing != null) {
            planned.found(existing.bundle(), existing.prev());
            // A PENDING or FAILED table is what a crash or a failed rollback leaves; skipping it would leave the
            // import incomplete however often it is re-run.
            if (existing.status() != TableStatus.ACTIVE) {
                planned.fail("table '" + planned.name() + "' exists but is " + existing.status()
                        + "; delete it and import again");
                return planned;
            }
            if (policy == ResolutionPolicy.FAIL_IF_EXISTS) {
                planned.conflict("table '" + planned.name() + "' already exists");
            } else {
                planned.skip();
            }
            compare(planned, existing.bundle());
            return planned;
        }
        try {
            TableColumnRules.requireColumnCount(columnsAfterInjection(table), target.maxColumnsPerTable());
        } catch (TableValidationException ex) {
            final String problem = ex.getMessage() + ", counting the provenance columns an enrichment is given";
            planned.fail(problem);
            validationErrors.add("table '" + planned.name() + "': " + problem);
        }
        return planned;
    }

    /** How many columns define-schema would persist for this table: its own plus the provenance it is given. */
    private static int columnsAfterInjection(BundleTable table) {
        final List<ColumnInput> declared = nullSafe(table.schema().columns()).stream()
                .map(CatalogImportPlanner::columnInput)
                .toList();
        final DefineSchemaInput input = new DefineSchemaInput(declared, null, null, null, null, null, null);
        return TableSchemaCreationService.withInjectedProvenance(table.table().type(), input).columns().size();
    }

    private static ColumnInput columnInput(ColumnRequest column) {
        return new ColumnInput(column.sourceName(), column.name(), column.type(), column.elementType(),
                column.enumValues(), column.nullable(), column.description(), column.displayName(), column.tag(),
                Boolean.TRUE.equals(column.sensitive()), Boolean.TRUE.equals(column.heavy()));
    }

    /** Sources first, then enrichments, otherwise in bundle order: an enrichment's source must exist first. */
    private static List<BundleTable> orderedTables(List<BundleTable> tables) {
        return nullSafe(tables).stream()
                .sorted(Comparator.comparingInt(table -> table.table().type() == TableType.ENRICHMENT ? 1 : 0))
                .toList();
    }

    /**
     * Enrich pipelines before aggregates, otherwise in bundle order: the order the runbook arms them in, since an
     * aggregate may read the enrichment an enrich pipeline produces.
     */
    private static List<BundlePipeline> orderedPipelines(List<BundlePipeline> pipelines) {
        return nullSafe(pipelines).stream()
                .sorted(Comparator.comparingInt(pipeline -> pipeline.pipeline().kind() == PipelineKind.ENRICH ? 0 : 1))
                .toList();
    }

    // --- pipelines -----------------------------------------------------------------------------------

    private PlannedObject planPipeline(PlannedObject planned, ResolutionPolicy policy, boolean acknowledgeReusedNames,
            ImportTarget target, List<EnvSpecificValue> envSpecific) {
        final BundlePipeline.Declaration declaration = planned.pipeline().pipeline();
        collectEnvSpecific(declaration, envSpecific);
        final BundlePipeline existing = target.pipelines().get(planned.name());
        if (existing != null) {
            planned.found(existing, target.pipelinesVisible() ? existing : null);
            if (policy == ResolutionPolicy.FAIL_IF_EXISTS) {
                planned.conflict("pipeline '" + planned.name() + "' already exists");
            } else {
                planned.skip();
            }
            compare(planned, existing);
            return planned;
        }
        final String holder = target.pipelineByTarget().get(declaration.target());
        if (holder != null) {
            planned.fail("target '" + declaration.target() + "' is already written by pipeline '" + holder
                    + "' on this environment, and a table has at most one pipeline");
        }
        final boolean reused = target.reusedPipelineNames().contains(planned.name());
        planned.reusedName(reused);
        if (reused && !acknowledgeReusedNames) {
            planned.fail("a pipeline named '" + planned.name() + "' existed on this environment before. The Enrichment "
                    + "Runner keeps a deleted pipeline's scan cursor, dead letters and spend counters by name, so a "
                    + "recreated one resumes from them and skips history; pass acknowledge_reused_names=true to "
                    + "create it anyway");
        }
        return planned;
    }

    private static void collectEnvSpecific(BundlePipeline.Declaration declaration, List<EnvSpecificValue> envSpecific) {
        final BiConsumer<String, Object> add = (field, value) -> {
            if (value != null) {
                envSpecific.add(new EnvSpecificValue(PlannedObject.Kind.PIPELINE.code(), declaration.name(), field,
                        value.toString()));
            }
        };
        add.accept("transform.model", declaration.transform() == null ? null : declaration.transform().model());
        add.accept("advanced.rate_rpm", declaration.advanced() == null ? null : declaration.advanced().rateRpm());
        add.accept("trigger.cron", declaration.trigger() == null ? null : declaration.trigger().cron());
        // A statement's date literals are found by the filter's own scan, so a `WHERE request_time >= '2026-08-05'` in
        // `sql` is as visible as the same predicate written as a filter.
        for (final String field : List.of("filter", "sql")) {
            final String text = "filter".equals(field) ? declaration.filter() : declaration.sql();
            if (text != null) {
                final Matcher dates = DATE_LITERAL.matcher(text);
                while (dates.find()) {
                    add.accept(field, dates.group(1));
                }
            }
        }
    }

    /** The table names a declaration cannot be created or armed without. */
    static Set<String> dependencies(BundlePipeline.Declaration declaration) {
        final Set<String> needs = new LinkedHashSet<>();
        if (declaration.inputs() != null) {
            needs.addAll(declaration.inputs());
        }
        needs.add(declaration.target());
        final List<String> expressions = new ArrayList<>();
        final List<String> columns = new ArrayList<>();
        expressions.add(declaration.filter());
        // SQL mode: the enrichments the statement references, by the same quoted-token scan as a filter's. The input
        // its FROM names is sqlReadTables(), which the closure checks only where it names a known table.
        expressions.add(declaration.sql());
        for (final MeasureRequest measure : nullSafe(declaration.measures())) {
            expressions.add(measure.where());
            columns.add(measure.column());
        }
        for (final GroupKeyRequest key : nullSafe(declaration.groupBy())) {
            columns.add(key.column());
            columns.add(key.trunc() == null ? null : key.trunc().column());
        }
        final TriggerRequest trigger = declaration.trigger();
        if (trigger != null) {
            columns.add(trigger.groupBy());
            expressions.add(trigger.readyWhen() == null ? null : trigger.readyWhen().signal());
            expressions.add(trigger.memberSelect() == null ? null : trigger.memberSelect().preferSql());
        }
        final PatchTransformRequest transform = declaration.transform();
        if (transform != null) {
            if (transform.inputs() != null) {
                for (final VarSpec var : transform.inputs().values()) {
                    columns.add(var == null ? null : var.column());
                }
            }
            if (transform.outputs() != null && transform.type() == TransformType.SQL) {
                for (final OutputSpec output : transform.outputs().values()) {
                    expressions.add(output == null || !output.isBareString() ? null : output.text());
                }
            }
            for (final TransformVarDto var : nullSafe(transform.outputVars())) {
                expressions.add(var.sql());
            }
        }
        for (final String expression : expressions) {
            if (expression != null) {
                final Matcher tokens = QUALIFIED_TOKEN.matcher(expression);
                while (tokens.find()) {
                    needs.add(tokens.group(1).toLowerCase(Locale.ROOT));
                }
            }
        }
        for (final String column : columns) {
            if (column != null) {
                final Matcher qualified = QUALIFIED_COLUMN.matcher(column.trim());
                if (qualified.matches()) {
                    needs.add(qualified.group(1));
                }
            }
        }
        return needs;
    }

    /**
     * The tables an {@code aggregate} declaration's {@code sql} names after a {@code FROM}, its common table expressions
     * excluded — the input the statement reads, found by token scan rather than a parse, because the planner touches
     * neither the catalog nor the front end and the input may itself be a table this bundle has yet to create.
     *
     * <p>An over-approximation: {@code extract(day FROM request_time)} contributes {@code request_time}. So the closure
     * counts a name here as a dependency only when it names a table the bundle carries or this environment has, and a
     * name that is neither is left to the arm gate rather than failing the import; a statement that does not parse
     * therefore never fails by itself. Names come back lowercased, quoted or not: the SQL front end resolves table
     * names case-insensitively, and a quoted {@code "Dial_Usage_Log"} kept as written would match no catalog name.
     */
    static Set<String> sqlReadTables(BundlePipeline.Declaration declaration) {
        final String sql = declaration.sql();
        if (sql == null) {
            return Set.of();
        }
        final Set<String> ctes = new HashSet<>();
        final Matcher cte = CTE_NAME.matcher(sql);
        while (cte.find()) {
            ctes.add((cte.group(1) != null ? cte.group(1) : cte.group(2)).toLowerCase(Locale.ROOT));
        }
        final Set<String> tables = new LinkedHashSet<>();
        final Matcher from = FROM_TABLE.matcher(sql);
        while (from.find()) {
            final String name = (from.group(1) != null ? from.group(1) : from.group(2)).toLowerCase(Locale.ROOT);
            if (!ctes.contains(name)) {
                tables.add(name);
            }
        }
        return tables;
    }

    // --- closure and limits --------------------------------------------------------------------------

    /**
     * Every dependency must be in the bundle, be a required system table, or already exist here; and an object
     * whose dependency is refused is refused too, since it could be neither created nor armed.
     */
    private static void requireClosure(List<PlannedObject> systemTables, List<PlannedObject> tables,
            List<PlannedObject> pipelines, ImportTarget target) {
        final Map<String, PlannedObject> bundled = new HashMap<>();
        systemTables.forEach(planned -> bundled.put(planned.name(), planned));
        tables.forEach(planned -> bundled.put(planned.name(), planned));
        for (final PlannedObject table : tables) {
            final CreateTableRequest create = table.table().table();
            if (create.type() == TableType.ENRICHMENT && create.sourceTable() != null && table.creates()) {
                requireDependency(table, create.sourceTable(), "source_table", bundled, target);
            }
        }
        for (final PlannedObject pipeline : pipelines) {
            if (!pipeline.creates()) {
                continue;
            }
            final BundlePipeline.Declaration declaration = pipeline.pipeline().pipeline();
            for (final String needed : dependencies(declaration)) {
                requireDependency(pipeline, needed, "dependency", bundled, target);
            }
            for (final String read : sqlReadTables(declaration)) {
                if (bundled.containsKey(read) || target.tables().containsKey(read)) {
                    requireDependency(pipeline, read, "dependency", bundled, target);
                }
            }
        }
    }

    private static void requireDependency(PlannedObject planned, String needed, String what,
            Map<String, PlannedObject> bundled, ImportTarget target) {
        final PlannedObject inBundle = bundled.get(needed);
        if (inBundle != null) {
            if (inBundle.failed() && !inBundle.isConflict()) {
                planned.fail(what + " '" + needed + "' cannot be imported, so " + planned.kind().code() + " '"
                        + planned.name() + "' cannot either");
            }
            return;
        }
        if (!target.tables().containsKey(needed)) {
            planned.fail(what + " '" + needed + "' is neither in the bundle nor on this environment");
        }
    }

    private static void requireLimits(List<PlannedObject> tables, ImportTarget target, List<String> validationErrors) {
        final int creates = (int) tables.stream().filter(PlannedObject::creates).count();
        try {
            TableCatalogService.requireCapacity(target.tableCount(), creates, target.maxTables());
        } catch (TableValidationException ex) {
            validationErrors.add(ex.getMessage());
        }
    }

    /** A name is either a system table referenced or a user table carried, never both. */
    private static void requireDistinctKinds(List<PlannedObject> systemTables, List<PlannedObject> tables,
            List<String> validationErrors) {
        final Set<String> tableNames = new HashSet<>();
        tables.forEach(planned -> tableNames.add(planned.name()));
        for (final PlannedObject systemTable : systemTables) {
            if (tableNames.contains(systemTable.name())) {
                validationErrors.add("the bundle carries '" + systemTable.name() + "' both as a table and as a "
                        + "required system table");
            }
        }
    }

    private static void requireUnique(List<PlannedObject> objects, String what, List<String> validationErrors) {
        final Set<String> seen = new HashSet<>();
        for (final PlannedObject planned : objects) {
            if (!seen.add(planned.name())) {
                validationErrors.add("the bundle carries " + what + " '" + planned.name() + "' more than once");
            }
        }
    }

    // --- comparison ----------------------------------------------------------------------------------

    /**
     * Whether the target's object differs from the bundle's, by their serialized forms. Grant lists compare as
     * sets, and an absent bundle grant document is compared as absent on both sides. A pipeline compares its
     * declaration only, so {@code exported_enabled} never counts.
     */
    private void compare(PlannedObject planned, Object existing) {
        if (existing == null) {
            planned.differs(true, false);
            return;
        }
        if (planned.kind() == PlannedObject.Kind.PIPELINE) {
            final boolean differs = !write(((BundlePipeline) existing).pipeline()).equals(write(planned.pipeline().pipeline()));
            planned.differs(differs, false);
            return;
        }
        final BundleTable left = (BundleTable) existing;
        final BundleTable right = planned.table();
        final boolean differs = !write(left).equals(write(right));
        final boolean metadataOnly = differs && write(withoutMetadata(left)).equals(write(withoutMetadata(right)));
        planned.differs(differs, metadataOnly);
    }

    /** A table with its descriptions, display names, tags and tag ordering removed. */
    private static BundleTable withoutMetadata(BundleTable table) {
        final CreateTableRequest create = table.table();
        final CreateTableRequest bareCreate = new CreateTableRequest(create.name(), null, create.type(), create.system(),
                create.write(), create.columns(), create.orderingKey(), create.partitionBy(), create.sourceTable(),
                create.grainKey(), create.cardinality(), create.ttl(), null);
        final DefineSchemaRequest schema = table.schema();
        final List<ColumnRequest> columns = nullSafe(schema.columns()).stream()
                .map(col -> new ColumnRequest(col.sourceName(), col.name(), col.type(), col.elementType(), col.enumValues(),
                        col.nullable(), null, null, null, col.sensitive(), col.heavy()))
                .toList();
        final DefineSchemaRequest bareSchema = new DefineSchemaRequest(columns, schema.orderingKey(), schema.partitionBy(),
                schema.grainKey(), schema.cardinality(), schema.identityColumn(), schema.versionColumn(), schema.ttl(),
                schema.write());
        return new BundleTable(bareCreate, bareSchema);
    }

    private String write(Object value) {
        return jsonMapper.writeValueAsString(value);
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
