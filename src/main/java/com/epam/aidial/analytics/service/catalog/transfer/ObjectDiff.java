package com.epam.aidial.analytics.service.catalog.transfer;

import com.epam.aidial.analytics.service.pipeline.PipelineKind;
import com.epam.aidial.analytics.service.table.TableColumnRules;
import com.epam.aidial.analytics.service.table.TableType;
import com.epam.aidial.analytics.service.table.TableWriteMode;
import com.epam.aidial.analytics.web.dto.catalog.BundlePipeline;
import com.epam.aidial.analytics.web.dto.catalog.BundleTable;
import com.epam.aidial.analytics.web.dto.catalog.ImportEffect;
import com.epam.aidial.analytics.web.dto.catalog.NotApplied;
import com.epam.aidial.analytics.web.dto.catalog.PlannedChange;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineRequests.TriggerRequest;
import com.epam.aidial.analytics.web.dto.table.CreateTableRequest;
import com.epam.aidial.analytics.web.dto.table.CreateTableRequest.ColumnRequest;
import com.epam.aidial.analytics.web.dto.table.DefineSchemaRequest;
import org.apache.commons.lang3.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What an {@code OVERWRITE} import would change on an existing object, and what it would keep: the difference between
 * the target's full object and the bundle's, turned into the changes the existing mutation services can make.
 *
 * <p>A table follows the bundle in its {@code description}, {@code tag_order} and column metadata, gains the columns it
 * lacks, and has {@code sensitive} raised but never cleared. It keeps a column the bundle lacks, its column order and its
 * TTL. A difference no change reaches — a column's physical name, type, element type, nullability or enum values, the
 * table's kind, write mode or frozen keys — is a refusal. A pipeline is re-declared, with its target-owned members taken
 * from the target ({@link TargetOwnedFields}); a different {@code kind} or {@code target} is a refusal.
 *
 * <p>The bundle's side is compared in the form the write paths would store it: a {@code nullable} left out resolves to
 * the kind's default, a source's {@code write} left out is {@code append}, column metadata and enum values are trimmed
 * with blank read as unset, and an aggregate's trigger kind left out is {@code schedule}. {@code cardinality} is not
 * compared: it has one legal value and is not stored. So a hand-edited bundle that leaves a default implicit converges
 * on its second run rather than reporting, or refusing, the same difference every time.
 */
final class ObjectDiff {

    /** The diff of one object. Converged when it has no change; refused when it has a refusal. */
    record Result(List<PlannedChange> changes, List<NotApplied> notApplied, List<ImportEffect> effects,
            List<String> refusals, BundlePipeline.Declaration redeclaration) {

        boolean refused() {
            return !refusals.isEmpty();
        }

        boolean hasChanges() {
            return !changes.isEmpty();
        }
    }

    private static final String NOT_UPDATABLE = " cannot be changed by an import; delete the object and import it again, "
            + "or change it by hand";

    /** The {@code transform} members whose entry order is meaning, not presentation: it is stored, and it is used. */
    private static final Set<String> ORDERED = Set.of("transform.outputs", "transform.inputs");

    private ObjectDiff() {
    }

    /**
     * What a caller who may not read the target's object is told of {@code full}: nothing it changes or keeps, and, for a
     * refusal, only that there is one. The decisions still follow {@code full}.
     *
     * @param what the object as the message names it, as {@code table 't'}
     */
    static Result redacted(Result full, String what) {
        final List<String> refusals = full.refused()
                ? List.of(what + " differs from the bundle's in a member an import cannot change; the caller may not read it, "
                        + "so the preview does not say which")
                : List.of();
        return new Result(List.of(), List.of(), List.of(), refusals, null);
    }

    // --- tables ------------------------------------------------------------------------------------------------------

    /**
     * The diff of an existing table against the bundle's.
     *
     * @param bumped the pipeline whose generation a column change of this enrichment bumps, or null: an {@code llm}
     *               enrich pipeline targeting it
     */
    static Result table(BundleTable existing, BundleTable bundle, String bumped) {
        final List<PlannedChange> changes = new ArrayList<>();
        final List<NotApplied> notApplied = new ArrayList<>();
        final List<ImportEffect> effects = new ArrayList<>();
        final List<String> refusals = new ArrayList<>();
        final String name = bundle.table().name();

        final CreateTableRequest left = existing.table();
        final CreateTableRequest right = bundle.table();
        final DefineSchemaRequest leftSchema = existing.schema();
        final DefineSchemaRequest rightSchema = bundle.schema();
        final TableType kind = right.type();
        refuseDifferent(refusals, "table '" + name + "'", "type", left.type(), right.type());
        refuseDifferent(refusals, "table '" + name + "'", "write", storedWrite(left.type(), left.write()),
                storedWrite(kind, right.write()));
        refuseDifferent(refusals, "table '" + name + "'", "source_table", left.sourceTable(), right.sourceTable());
        refuseDifferent(refusals, "table '" + name + "'", "ordering_key", leftSchema.orderingKey(), rightSchema.orderingKey());
        refuseDifferent(refusals, "table '" + name + "'", "partition_by", leftSchema.partitionBy(), rightSchema.partitionBy());
        refuseDifferent(refusals, "table '" + name + "'", "grain_key", leftSchema.grainKey(), rightSchema.grainKey());
        refuseDifferent(refusals, "table '" + name + "'", "identity_column", leftSchema.identityColumn(),
                rightSchema.identityColumn());
        refuseDifferent(refusals, "table '" + name + "'", "version_column", leftSchema.versionColumn(),
                rightSchema.versionColumn());

        final List<String> tableFields = new ArrayList<>();
        // A bundle that leaves the description out does not clear it: the update path keeps a null description.
        if (right.description() != null && !Objects.equals(left.description(), right.description())) {
            tableFields.add("description");
        }
        if (!Objects.equals(storedTagOrder(left.tagOrder()), storedTagOrder(right.tagOrder()))) {
            tableFields.add("tag_order");
        }
        if (!tableFields.isEmpty()) {
            changes.add(new PlannedChange(PlannedChange.Op.UPDATE_TABLE, null, List.copyOf(tableFields)));
        }

        final Map<String, ColumnRequest> stored = byName(leftSchema.columns());
        final Map<String, ColumnRequest> bundled = byName(rightSchema.columns());
        final List<String> added = new ArrayList<>();
        boolean descriptionChanged = false;
        for (final ColumnRequest raw : bundled.values()) {
            final ColumnRequest current = stored.get(raw.name());
            if (current == null) {
                added.add(raw.name());
                changes.add(new PlannedChange(PlannedChange.Op.ADD_COLUMN, raw.name(), List.of()));
                continue;
            }
            final ColumnRequest column = storedForm(raw, kind);
            final String what = "column '" + column.name() + "' of table '" + name + "'";
            refuseDifferent(refusals, what, "source_name", current.sourceName(), column.sourceName());
            refuseDifferent(refusals, what, "type", current.type(), column.type());
            refuseDifferent(refusals, what, "element_type", current.elementType(), column.elementType());
            refuseDifferent(refusals, what, "nullable", current.nullable(), column.nullable());
            refuseDifferent(refusals, what, "enum_values", current.enumValues(), column.enumValues());
            final List<String> fields = new ArrayList<>();
            if (!Objects.equals(current.description(), column.description())) {
                fields.add("description");
                descriptionChanged = true;
            }
            if (!Objects.equals(current.displayName(), column.displayName())) {
                fields.add("display_name");
            }
            if (!Objects.equals(current.tag(), column.tag())) {
                fields.add("tag");
            }
            if (flag(current.heavy()) != flag(column.heavy())) {
                fields.add("heavy");
            }
            if (!flag(current.sensitive()) && flag(column.sensitive())) {
                fields.add("sensitive");
            } else if (flag(current.sensitive()) && !flag(column.sensitive())) {
                notApplied.add(new NotApplied(NotApplied.Kind.SENSITIVE_LOOSENING, column.name(), "an import never clears "
                        + "sensitive, which would widen access to the column; clear it with PATCH /v1/tables/" + name
                        + "/schema"));
            }
            if (!fields.isEmpty()) {
                changes.add(new PlannedChange(PlannedChange.Op.UPDATE_COLUMN, column.name(), List.copyOf(fields)));
            }
        }
        for (final String column : stored.keySet()) {
            if (!bundled.containsKey(column)) {
                notApplied.add(new NotApplied(NotApplied.Kind.EXTRA_COLUMN, column, "the bundle does not carry the column, "
                        + "and an import never drops one"));
            }
        }
        // The order the import leaves: the stored columns, then the added ones, as the schema patch appends them.
        final List<String> after = new ArrayList<>(stored.keySet());
        after.addAll(added);
        after.retainAll(bundled.keySet());
        if (!after.equals(new ArrayList<>(bundled.keySet()))) {
            notApplied.add(new NotApplied(NotApplied.Kind.COLUMN_ORDER, name, "an import never reorders columns: it appends "
                    + "the ones it adds"));
        }
        if (!TargetOwnedFields.sameTtl(existing, bundle)) {
            notApplied.add(new NotApplied(NotApplied.Kind.TARGET_OWNED, TargetOwnedFields.TABLE_TTL, "retention is "
                    + "environment-specific, so an update keeps the target's"));
        }
        if (kind == TableType.ENRICHMENT && bumped != null && (!added.isEmpty() || descriptionChanged)) {
            effects.add(new ImportEffect(ImportEffect.Kind.GENERATION_BUMP, bumped));
        }
        return new Result(List.copyOf(changes), List.copyOf(notApplied), List.copyOf(effects), List.copyOf(refusals), null);
    }

    /** {@code table} without the columns named in {@code hidden}: the bundle as compared with a caller's view. */
    static BundleTable withoutColumns(BundleTable table, Set<String> hidden) {
        final DefineSchemaRequest s = table.schema();
        final List<ColumnRequest> kept = s.columns() == null ? null
                : s.columns().stream().filter(column -> !hidden.contains(column.name())).toList();
        return new BundleTable(table.table(), new DefineSchemaRequest(kept, s.orderingKey(), s.partitionBy(), s.grainKey(),
                s.cardinality(), s.identityColumn(), s.versionColumn(), s.ttl(), s.write()));
    }

    /** A source's write mode as create stores it: one left out is {@code append}. An enrichment has none. */
    private static TableWriteMode storedWrite(TableType kind, TableWriteMode write) {
        return write == null && kind == TableType.SOURCE ? TableWriteMode.APPEND : write;
    }

    /**
     * A bundle column in the form define-schema and the schema patch store it: {@code nullable} resolved, enum values
     * trimmed, and description, display name and tag trimmed with blank as unset ({@link TableColumnRules}).
     */
    private static ColumnRequest storedForm(ColumnRequest c, TableType kind) {
        final List<String> enumValues = c.enumValues() == null ? null
                : c.enumValues().stream().map(value -> value == null ? null : value.strip()).toList();
        return new ColumnRequest(c.sourceName(), c.name(), c.type(), c.elementType(), enumValues,
                TableColumnRules.resolveNullable(c.type(), c.nullable(), kind), StringUtils.stripToNull(c.description()),
                StringUtils.stripToNull(c.displayName()), StringUtils.stripToNull(c.tag()), c.sensitive(), c.heavy());
    }

    /** A tag ordering as the metadata update stores it: each entry trimmed, and an empty list as none. */
    private static List<String> storedTagOrder(List<String> tagOrder) {
        return tagOrder == null || tagOrder.isEmpty() ? null
                : tagOrder.stream().map(entry -> entry == null ? null : entry.strip()).toList();
    }

    // --- pipelines ---------------------------------------------------------------------------------------------------

    /**
     * The diff of an existing pipeline against the bundle's.
     *
     * @param storedComplete whether the target's declaration is complete; an incomplete draft is never updated, since
     *                       compensation could not restore it (a patch may not leave a complete declaration incomplete)
     */
    static Result pipeline(BundlePipeline existing, BundlePipeline bundle, boolean storedComplete, JsonMapper jsonMapper) {
        final BundlePipeline.Declaration stored = existing.pipeline();
        final BundlePipeline.Declaration bundled = storedDeclaration(bundle.pipeline());
        final String what = "pipeline '" + bundled.name() + "'";
        final List<String> refusals = new ArrayList<>();
        refuseDifferent(refusals, what, "kind", stored.kind(), bundled.kind());
        refuseDifferent(refusals, what, "target", stored.target(), bundled.target());
        if (!refusals.isEmpty()) {
            return new Result(List.of(), List.of(), List.of(), List.copyOf(refusals), null);
        }
        final List<NotApplied> notApplied = new ArrayList<>();
        for (final TargetOwnedFields.Field field : TargetOwnedFields.PIPELINE) {
            if (!Objects.equals(field.read(stored), field.read(bundled)) && TargetOwnedFields.kept(field, bundled, stored)) {
                notApplied.add(new NotApplied(NotApplied.Kind.TARGET_OWNED, field.path(), "environment-specific, so an "
                        + "update keeps the target's"));
            }
        }
        final BundlePipeline.Declaration redeclaration = TargetOwnedFields.withTargetOwned(bundled, stored);
        final List<String> fields = differingMembers(jsonMapper.valueToTree(stored), jsonMapper.valueToTree(redeclaration), "");
        if (fields.isEmpty()) {
            return new Result(List.of(), List.copyOf(notApplied), List.of(), List.of(), null);
        }
        // A patch naming no declaration member is an enable-only patch: it would change nothing and still be reported.
        if (!hasDeclaration(redeclaration)) {
            return new Result(List.of(), List.copyOf(notApplied), List.of(), List.of("the bundle's " + what + " declares no "
                    + "member beside name, kind and target, and an import cannot clear a stored declaration; delete the "
                    + "pipeline and import it again"), null);
        }
        if (!storedComplete) {
            return new Result(List.of(), List.copyOf(notApplied), List.of(), List.of(what + " is an incomplete draft on this "
                    + "environment, which an import cannot restore if it fails after updating it; delete the draft and "
                    + "import again, or complete it by hand"), null);
        }
        final boolean aggregate = stored.kind() == PipelineKind.AGGREGATE;
        final boolean enabled = existing.exportedEnabled();
        final List<PlannedChange> changes = new ArrayList<>();
        final List<ImportEffect> effects = new ArrayList<>();
        // PATCH refuses re-declaring an enabled aggregate; the import disables it and leaves it so, as it leaves a created one.
        if (aggregate && enabled) {
            changes.add(new PlannedChange(PlannedChange.Op.DISABLE, null, List.of("enabled")));
        }
        changes.add(new PlannedChange(PlannedChange.Op.REDECLARE, null, fields));
        effects.add(new ImportEffect(ImportEffect.Kind.GENERATION_BUMP, bundled.name()));
        if (aggregate) {
            effects.add(new ImportEffect(ImportEffect.Kind.REBUILD_FROM_ZERO, bundled.name()));
            if (enabled) {
                effects.add(new ImportEffect(ImportEffect.Kind.REARM_REQUIRED, bundled.name()));
            }
        } else {
            effects.add(new ImportEffect(ImportEffect.Kind.EXISTING_ROWS_NOT_REDERIVED, bundled.name()));
        }
        return new Result(List.copyOf(changes), List.copyOf(notApplied), List.copyOf(effects), List.of(), redeclaration);
    }

    /**
     * A bundle declaration in the form the registry stores it: the trigger kind trimmed and lowercased, and an
     * aggregate's left out as {@code schedule}, the only kind it has.
     */
    private static BundlePipeline.Declaration storedDeclaration(BundlePipeline.Declaration d) {
        final TriggerRequest trigger = d.trigger();
        if (trigger == null) {
            return d;
        }
        String kind = StringUtils.isBlank(trigger.kind()) ? null : trigger.kind().trim().toLowerCase(Locale.ROOT);
        if (kind == null && d.kind() == PipelineKind.AGGREGATE) {
            kind = "schedule";
        }
        if (Objects.equals(kind, trigger.kind())) {
            return d;
        }
        return new BundlePipeline.Declaration(d.name(), d.kind(), d.target(), d.inputs(), d.filter(),
                new TriggerRequest(kind, trigger.cron(), trigger.groupBy(), trigger.readyWhen(), trigger.memberSelect()),
                d.groupBy(), d.measures(), d.sql(), d.transform(), d.advanced());
    }

    /** Whether a re-declaration names a member a {@code PATCH} reads as a declaration, as {@code hasDeclaration} does. */
    private static boolean hasDeclaration(BundlePipeline.Declaration d) {
        return d.inputs() != null || d.filter() != null || d.groupBy() != null || d.measures() != null || d.sql() != null
                || d.trigger() != null || d.transform() != null || d.advanced() != null;
    }

    /**
     * The declaration members that differ: each top-level member, and within {@code transform}, {@code trigger} and
     * {@code advanced} each of theirs, as {@code transform.outputs}. A JSON object compares as a map, so the members
     * whose entry order is stored and used ({@link #ORDERED}) also compare their key order.
     */
    private static List<String> differingMembers(JsonNode left, JsonNode right, String prefix) {
        final Set<String> keys = new LinkedHashSet<>();
        left.propertyNames().forEach(keys::add);
        right.propertyNames().forEach(keys::add);
        final List<String> members = new ArrayList<>();
        for (final String key : keys) {
            final String path = prefix + key;
            final JsonNode l = present(left.get(key));
            final JsonNode r = present(right.get(key));
            // A block is always compared member by member: equal as maps, it may still differ in an ordered member.
            if (l != null && r != null && l.isObject() && r.isObject() && Set.of("transform", "trigger", "advanced").contains(key)
                    && prefix.isEmpty()) {
                members.addAll(differingMembers(l, r, key + "."));
            } else if (!Objects.equals(l, r) || ORDERED.contains(path) && l != null && !keyOrder(l).equals(keyOrder(r))) {
                members.add(path);
            }
        }
        return members;
    }

    private static List<String> keyOrder(JsonNode node) {
        final List<String> keys = new ArrayList<>();
        if (node.isObject()) {
            node.propertyNames().forEach(keys::add);
        }
        return keys;
    }

    private static JsonNode present(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : node;
    }

    // --- helpers -----------------------------------------------------------------------------------------------------

    private static void refuseDifferent(List<String> refusals, String what, String field, Object left, Object right) {
        if (!Objects.equals(left, right)) {
            refusals.add(field + " of " + what + " differs from the bundle's (" + left + " here, " + right + " in the bundle), "
                    + "and" + NOT_UPDATABLE);
        }
    }

    private static Map<String, ColumnRequest> byName(List<ColumnRequest> columns) {
        final Map<String, ColumnRequest> byName = new LinkedHashMap<>();
        if (columns != null) {
            columns.forEach(column -> byName.put(column.name(), column));
        }
        return byName;
    }

    private static boolean flag(Boolean value) {
        return Boolean.TRUE.equals(value);
    }
}
