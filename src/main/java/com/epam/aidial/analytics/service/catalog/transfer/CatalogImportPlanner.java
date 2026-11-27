package com.epam.aidial.analytics.service.catalog.transfer;

import com.epam.aidial.analytics.exception.TableValidationException;
import com.epam.aidial.analytics.service.enrichment.model.TransformType;
import com.epam.aidial.analytics.service.pipeline.PipelineKind;
import com.epam.aidial.analytics.service.table.ColumnInput;
import com.epam.aidial.analytics.service.table.DefineSchemaInput;
import com.epam.aidial.analytics.service.table.TableAccessService.AccessEntry;
import com.epam.aidial.analytics.service.table.TableCatalogService;
import com.epam.aidial.analytics.service.table.TableColumnRules;
import com.epam.aidial.analytics.service.table.TableSchemaCreationService;
import com.epam.aidial.analytics.service.table.TableStatus;
import com.epam.aidial.analytics.service.table.TableType;
import com.epam.aidial.analytics.web.dto.catalog.BundleAccessEntry;
import com.epam.aidial.analytics.web.dto.catalog.BundlePipeline;
import com.epam.aidial.analytics.web.dto.catalog.BundleTable;
import com.epam.aidial.analytics.web.dto.catalog.CatalogBundle;
import com.epam.aidial.analytics.web.dto.catalog.EnvSpecificValue;
import com.epam.aidial.analytics.web.dto.catalog.PlannedChange;
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
import org.apache.commons.lang3.StringUtils;
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
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plans a catalog import against a snapshot of the target ({@link ImportTarget}), touching no repository:
 * checks the required system tables, classifies each collision by the resolution policy (under {@code OVERWRITE}, and as a
 * preview under {@code SKIP_IF_EXISTS}, through the {@link ObjectDiff} of the existing object),
 * checks that every dependency is in the bundle or already on the target, orders the objects, checks the
 * catalog limits on the totals, and collects the environment-specific values.
 *
 * <p>What the planner cannot see — identifier grammar, column rules, the pipeline gates —
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

    ImportPlan plan(CatalogBundle bundle, boolean acknowledgeReusedNames, ResolutionPolicy policy, ImportTarget target,
            boolean includeAccess) {
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
        final List<PlannedAccess> access = includeAccess ? planAccess(bundle, systemTables, tables, target, validationErrors)
                : List.of();
        return new ImportPlan(systemTables, tables, pipelines, access, envSpecific, validationErrors);
    }

    // --- access --------------------------------------------------------------------------------------

    /**
     * One entry per table and role key of the bundle's {@code access}, sorted by table, trimmed role and key as written,
     * each with the target's stored entry for the role. A key may name a table the bundle does not carry when this
     * environment has it, as a bundle exported {@code access_only} does; such a table has no planned object to refuse its
     * entries. A bundle-level problem (no {@code access} member, a table in neither the bundle nor this environment, a
     * blank role key, two keys trimming to one role) is a validation error and fails every entry under it; an entry on a
     * refused table fails too, a {@code FAIL_IF_EXISTS} conflict excepted. The body itself is checked by the validation
     * pass.
     */
    private static List<PlannedAccess> planAccess(CatalogBundle bundle, List<PlannedObject> systemTables,
            List<PlannedObject> tables, ImportTarget target, List<String> validationErrors) {
        if (bundle.access() == null) {
            validationErrors.add("the bundle carries no access member, so include_access=true has nothing to import; export "
                    + "it with include_access: true, or import it without include_access");
            return List.of();
        }
        final Map<String, PlannedObject> carried = new HashMap<>();
        systemTables.forEach(planned -> carried.put(planned.name(), planned));
        tables.forEach(planned -> carried.put(planned.name(), planned));
        final List<PlannedAccess> planned = new ArrayList<>();
        for (final Map.Entry<String, Map<String, BundleAccessEntry>> table : new TreeMap<>(bundle.access()).entrySet()) {
            final String name = table.getKey();
            final ImportTarget.Table existing = target.tables().get(name);
            final Map<String, AccessEntry> stored = existing == null ? Map.of() : existing.access();
            final List<PlannedAccess> roles = new ArrayList<>();
            table.getValue().forEach((key, entry) -> roles.add(new PlannedAccess(name, key, entry,
                    stored.get(StringUtils.trimToEmpty(key)))));
            final PlannedObject owner = carried.get(name);
            // A table named only by access must be a user table here: a system table the bundle never named as one is
            // a different table that happens to share the name.
            if (owner == null && (existing == null || existing.system())) {
                final String problem = "access names table '" + name + "', which the bundle carries neither under tables nor "
                        + "under required_system_tables, and which " + (existing == null ? "does not exist on this environment"
                        : "is a system table on this environment");
                validationErrors.add(problem);
                roles.forEach(role -> role.fail(problem));
            }
            for (final PlannedAccess role : roles) {
                if (role.role().isEmpty()) {
                    final String problem = "the access of table '" + name + "' has the blank role key '" + role.key() + "'";
                    validationErrors.add(problem);
                    role.fail(problem);
                }
            }
            final Map<String, List<PlannedAccess>> byRole = new TreeMap<>();
            roles.stream().filter(role -> !role.role().isEmpty())
                    .forEach(role -> byRole.computeIfAbsent(role.role(), key -> new ArrayList<>()).add(role));
            byRole.forEach((role, keys) -> {
                if (keys.size() > 1) {
                    final String problem = "the access of table '" + name + "' has " + keys.size() + " role keys that trim "
                            + "to role '" + role + "'";
                    validationErrors.add(problem);
                    keys.forEach(key -> key.fail(problem));
                }
            });
            if (owner != null && owner.failed() && !owner.isConflict()) {
                roles.stream().filter(role -> !role.failed()).forEach(role -> role.fail("table '" + name
                        + "' cannot be imported, so its access cannot either"));
            }
            planned.addAll(roles);
        }
        planned.sort(Comparator.comparing(PlannedAccess::table).thenComparing(PlannedAccess::role)
                .thenComparing(PlannedAccess::key));
        return planned;
    }

    // --- required system tables ----------------------------------------------------------------------

    /**
     * A required system table must exist here, be system-flagged and be ACTIVE. Its columns are not checked here:
     * the validation pass arms each pipeline against the real table, which names any column a pipeline reads
     * that is missing. Its definition is never written, and its access only by an import with {@code include_access},
     * which plans the bundle's entries for it as for any other table.
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
        final ImportTarget.Table existing = target.tables().get(planned.name());
        final BundleTable stored = existing == null || existing.system() ? null : existing.bundle();
        final String bundledTtl = TargetOwnedFields.ttl(table);
        // The target's value as the caller may see it: none for a table it may not get.
        final String storedTtl = TargetOwnedFields.ttl(stored == null ? null : existing.prev());
        if (bundledTtl != null || TargetOwnedFields.ttl(stored) != null) {
            envSpecific.add(new EnvSpecificValue(PlannedObject.Kind.TABLE.code(), planned.name(), TargetOwnedFields.TABLE_TTL,
                    bundledTtl, storedTtl, stored != null));
        }
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
                final String bumped = bumpedPipeline(target, planned.name());
                final ObjectDiff.Result diff = ObjectDiff.table(existing.bundle(), table, bumped);
                final ObjectDiff.Result reported = reported(existing, table, bumped, diff);
                if (policy == ResolutionPolicy.OVERWRITE) {
                    overwrite(planned, diff, reported, updateBlocker(existing, diff));
                } else {
                    planned.diff(reported);
                }
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

    /**
     * Records an {@code OVERWRITE} diff: a refusal fails the object, a change updates it, and an object with nothing to
     * change stays {@code SKIP} with what it keeps. A refused object counts as one the import set out to update, so
     * authorization asks for the update's privileges before the refusal is reported. The decisions follow {@code diff},
     * the report {@code reported}, what the caller may see of it; the two differ only for an object the caller cannot see
     * whole, which is never applied.
     *
     * @param blocker why the object cannot be updated by this caller, or null when it can
     */
    private static void overwrite(PlannedObject planned, ObjectDiff.Result diff, ObjectDiff.Result reported, String blocker) {
        planned.diff(reported);
        if (diff.refused()) {
            planned.intendUpdate();
            reported.refusals().forEach(planned::fail);
        } else if (diff.hasChanges()) {
            planned.update(diff);
            if (blocker != null) {
                planned.diff(reported);
                planned.fail("updating " + planned.kind().code() + " '" + planned.name() + "' needs " + blocker);
            }
        }
    }

    /**
     * What the caller is told of a table's diff: nothing but the fact of a refusal for a table it may not get, and for one
     * with columns hidden from it, the diff of its own view against the bundle without those columns, a refusal on a
     * hidden column reported without its stored value.
     */
    private static ObjectDiff.Result reported(ImportTarget.Table existing, BundleTable table, String bumped,
            ObjectDiff.Result diff) {
        final String what = "table '" + table.table().name() + "'";
        if (existing.prev() == null) {
            return ObjectDiff.redacted(diff, what);
        }
        final Set<String> hidden = hiddenColumnNames(existing);
        if (hidden.isEmpty()) {
            return diff;
        }
        final ObjectDiff.Result view = ObjectDiff.table(existing.prev(), ObjectDiff.withoutColumns(table, hidden), bumped);
        final List<String> refusals = new ArrayList<>(view.refusals());
        if (diff.refusals().size() > view.refusals().size()) {
            refusals.add("a column of " + what + " hidden from the caller differs from the bundle's in a member an import "
                    + "cannot change");
        }
        return new ObjectDiff.Result(view.changes(), view.notApplied(), view.effects(), List.copyOf(refusals), null);
    }

    /** The columns of an existing table the caller does not see, by sensitivity or by a column policy. */
    private static Set<String> hiddenColumnNames(ImportTarget.Table existing) {
        final Set<String> hidden = new LinkedHashSet<>();
        nullSafe(existing.bundle().schema().columns()).forEach(column -> hidden.add(column.name()));
        nullSafe(existing.prev().schema().columns()).forEach(column -> hidden.remove(column.name()));
        return hidden;
    }

    /**
     * Why the caller cannot update an existing table, or null when it can: the schema patch names only the columns the
     * caller sees, so an update needs every column visible, and one adding a column needs no column policy that could
     * hide it, since the drop that reverts the addition must name it.
     */
    private static String updateBlocker(ImportTarget.Table existing, ObjectDiff.Result diff) {
        if (existing.prev() == null) {
            return "the caller to be able to get it";
        }
        final int hidden = hiddenColumnNames(existing).size();
        if (hidden > 0) {
            return "every column of it visible to the caller: " + hidden + " column(s) are hidden from the caller, by "
                    + "sensitivity or by a column policy";
        }
        if (existing.columnPolicy() && diff.changes().stream().anyMatch(change -> change.op() == PlannedChange.Op.ADD_COLUMN)) {
            return "no column policy limiting the columns the caller sees, because it adds columns: one it adds could be "
                    + "hidden from the caller, and the drop that undoes it if the import rolls back could not name it";
        }
        return null;
    }

    /** The pipeline whose generation a column change of {@code table} bumps, or null. */
    private static String bumpedPipeline(ImportTarget target, String table) {
        final String pipeline = target.pipelineByTarget().get(table);
        return pipeline != null && target.bumpedPipelines().contains(pipeline) ? pipeline : null;
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
        final BundlePipeline existing = target.pipelines().get(planned.name());
        collectEnvSpecific(declaration, existing == null ? null : existing.pipeline(), policy, target.pipelinesVisible(),
                envSpecific);
        if (existing != null) {
            planned.found(existing, target.pipelinesVisible() ? existing : null);
            if (policy == ResolutionPolicy.FAIL_IF_EXISTS) {
                planned.conflict("pipeline '" + planned.name() + "' already exists");
            } else {
                planned.skip();
                final ObjectDiff.Result diff = ObjectDiff.pipeline(existing, planned.pipeline(),
                        !target.incompletePipelines().contains(planned.name()), jsonMapper);
                final ObjectDiff.Result reported = target.pipelinesVisible() ? diff
                        : ObjectDiff.redacted(diff, "pipeline '" + planned.name() + "'");
                if (policy == ResolutionPolicy.OVERWRITE) {
                    overwrite(planned, diff, reported, null);
                } else {
                    planned.diff(reported);
                }
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

    /**
     * The pipeline's environment-specific values: each target-owned field the bundle or, on an existing pipeline, the
     * target sets, and each absolute date literal in its filter or statement. An existing pipeline keeps the target's
     * value of a target-owned field, except under {@code OVERWRITE} for a deployment binding whose preset changed, which
     * takes the bundle's, and for an {@code input_token_budget} the bundle moves off a {@code group} trigger. The target's
     * value is reported only to a caller who may read the pipeline.
     *
     * @param stored  the target's declaration, or null when the pipeline is new
     * @param visible whether the caller may read the target's declaration
     */
    private static void collectEnvSpecific(BundlePipeline.Declaration declaration, BundlePipeline.Declaration stored,
            ResolutionPolicy policy, boolean visible, List<EnvSpecificValue> envSpecific) {
        for (final TargetOwnedFields.Field field : TargetOwnedFields.PIPELINE) {
            final Object value = field.read(declaration);
            final Object targetValue = field.read(stored);
            if (value == null && targetValue == null) {
                continue;
            }
            final boolean kept = stored != null && (policy != ResolutionPolicy.OVERWRITE
                    || TargetOwnedFields.kept(field, declaration, stored));
            envSpecific.add(new EnvSpecificValue(PlannedObject.Kind.PIPELINE.code(), declaration.name(), field.path(),
                    text(value), visible ? text(targetValue) : null, kept));
        }
        // A statement's date literals are found by the filter's own scan, so a `WHERE request_time >= '2026-08-05'` in
        // `sql` is as visible as the same predicate written as a filter.
        for (final String field : List.of("filter", "sql")) {
            final String text = "filter".equals(field) ? declaration.filter() : declaration.sql();
            if (text != null) {
                final Matcher dates = DATE_LITERAL.matcher(text);
                while (dates.find()) {
                    envSpecific.add(new EnvSpecificValue(PlannedObject.Kind.PIPELINE.code(), declaration.name(), field,
                            dates.group(1), null, false));
                }
            }
        }
    }

    /** A field value as the report spells it: an enum by its wire code, anything else by its string form. */
    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof Enum<?> constant ? constant.name().toLowerCase(Locale.ROOT) : value.toString();
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
     * Every dependency must be in the bundle, be a required system table, or already exist here and be ACTIVE; and an object
     * whose dependency is refused is refused too, since it could be neither created nor armed.
     */
    private static void requireClosure(List<PlannedObject> systemTables, List<PlannedObject> tables,
            List<PlannedObject> pipelines, ImportTarget target) {
        final Map<String, PlannedObject> bundled = new HashMap<>();
        systemTables.forEach(planned -> bundled.put(planned.name(), planned));
        tables.forEach(planned -> bundled.put(planned.name(), planned));
        for (final PlannedObject table : tables) {
            final CreateTableRequest create = table.table().table();
            if (create.type() == TableType.ENRICHMENT && create.sourceTable() != null && table.writes()) {
                requireDependency(table, create.sourceTable(), "source_table", bundled, target);
            }
        }
        for (final PlannedObject pipeline : pipelines) {
            if (!pipeline.writes()) {
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
        final ImportTarget.Table existing = target.tables().get(needed);
        if (existing == null) {
            planned.fail(what + " '" + needed + "' is neither in the bundle nor on this environment");
        } else if (existing.status() != TableStatus.ACTIVE) {
            // Nothing over it could be armed, and a table a pipeline reads cannot be deleted for repair while the pipeline exists.
            planned.fail(what + " '" + needed + "' is not in the bundle and is " + existing.status()
                    + " on this environment, so " + planned.kind().code() + " '" + planned.name() + "' cannot be imported");
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
     * Whether the target's object differs from the bundle's, by their serialized forms, which carry no access. A
     * pipeline compares its declaration only, so {@code exported_enabled} never counts.
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
