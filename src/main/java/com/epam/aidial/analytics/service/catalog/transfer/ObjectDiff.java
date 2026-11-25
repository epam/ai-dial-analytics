package com.epam.aidial.analytics.service.catalog.transfer;

import com.epam.aidial.analytics.service.pipeline.PipelineKind;
import com.epam.aidial.analytics.service.table.TableType;
import com.epam.aidial.analytics.web.dto.catalog.BundlePipeline;
import com.epam.aidial.analytics.web.dto.catalog.BundleTable;
import com.epam.aidial.analytics.web.dto.catalog.ImportEffect;
import com.epam.aidial.analytics.web.dto.catalog.NotApplied;
import com.epam.aidial.analytics.web.dto.catalog.PlannedChange;
import com.epam.aidial.analytics.web.dto.table.CreateTableRequest;
import com.epam.aidial.analytics.web.dto.table.CreateTableRequest.ColumnRequest;
import com.epam.aidial.analytics.web.dto.table.DefineSchemaRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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

    private ObjectDiff() {
    }

    // --- tables ------------------------------------------------------------------------------------------------------

    /**
     * The diff of an existing table against the bundle's.
     *
     * @param pipeline the pipeline targeting the table on the target, or null; a column change on an enrichment bumps its
     *                 generation
     */
    static Result table(BundleTable existing, BundleTable bundle, String pipeline) {
        final List<PlannedChange> changes = new ArrayList<>();
        final List<NotApplied> notApplied = new ArrayList<>();
        final List<ImportEffect> effects = new ArrayList<>();
        final List<String> refusals = new ArrayList<>();
        final String name = bundle.table().name();

        final CreateTableRequest left = existing.table();
        final CreateTableRequest right = bundle.table();
        final DefineSchemaRequest leftSchema = existing.schema();
        final DefineSchemaRequest rightSchema = bundle.schema();
        refuseDifferent(refusals, "table '" + name + "'", "type", left.type(), right.type());
        refuseDifferent(refusals, "table '" + name + "'", "write", left.write(), right.write());
        refuseDifferent(refusals, "table '" + name + "'", "source_table", left.sourceTable(), right.sourceTable());
        refuseDifferent(refusals, "table '" + name + "'", "ordering_key", leftSchema.orderingKey(), rightSchema.orderingKey());
        refuseDifferent(refusals, "table '" + name + "'", "partition_by", leftSchema.partitionBy(), rightSchema.partitionBy());
        refuseDifferent(refusals, "table '" + name + "'", "grain_key", leftSchema.grainKey(), rightSchema.grainKey());
        refuseDifferent(refusals, "table '" + name + "'", "cardinality", leftSchema.cardinality(), rightSchema.cardinality());
        refuseDifferent(refusals, "table '" + name + "'", "identity_column", leftSchema.identityColumn(),
                rightSchema.identityColumn());
        refuseDifferent(refusals, "table '" + name + "'", "version_column", leftSchema.versionColumn(),
                rightSchema.versionColumn());

        final List<String> tableFields = new ArrayList<>();
        // A bundle that leaves the description out does not clear it: the update path keeps a null description.
        if (right.description() != null && !Objects.equals(left.description(), right.description())) {
            tableFields.add("description");
        }
        if (!Objects.equals(emptyToNull(left.tagOrder()), emptyToNull(right.tagOrder()))) {
            tableFields.add("tag_order");
        }
        if (!tableFields.isEmpty()) {
            changes.add(new PlannedChange(PlannedChange.Op.UPDATE_TABLE, null, List.copyOf(tableFields)));
        }

        final Map<String, ColumnRequest> stored = byName(leftSchema.columns());
        final Map<String, ColumnRequest> bundled = byName(rightSchema.columns());
        final List<String> added = new ArrayList<>();
        boolean descriptionChanged = false;
        for (final ColumnRequest column : bundled.values()) {
            final ColumnRequest current = stored.get(column.name());
            if (current == null) {
                added.add(column.name());
                changes.add(new PlannedChange(PlannedChange.Op.ADD_COLUMN, column.name(), List.of()));
                continue;
            }
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
        if (!Objects.equals(TargetOwnedFields.ttl(existing), TargetOwnedFields.ttl(bundle))) {
            notApplied.add(new NotApplied(NotApplied.Kind.TARGET_OWNED, TargetOwnedFields.TABLE_TTL, "retention is "
                    + "environment-specific, so an update keeps the target's"));
        }
        if (right.type() == TableType.ENRICHMENT && pipeline != null && (!added.isEmpty() || descriptionChanged)) {
            effects.add(new ImportEffect(ImportEffect.Kind.GENERATION_BUMP, pipeline));
        }
        return new Result(List.copyOf(changes), List.copyOf(notApplied), List.copyOf(effects), List.copyOf(refusals), null);
    }

    // --- pipelines ---------------------------------------------------------------------------------------------------

    /** The diff of an existing pipeline against the bundle's. */
    static Result pipeline(BundlePipeline existing, BundlePipeline bundle, JsonMapper jsonMapper) {
        final BundlePipeline.Declaration stored = existing.pipeline();
        final BundlePipeline.Declaration bundled = bundle.pipeline();
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
        final List<String> fields = differingMembers(jsonMapper.valueToTree(stored), jsonMapper.valueToTree(redeclaration));
        if (fields.isEmpty()) {
            return new Result(List.of(), List.copyOf(notApplied), List.of(), List.of(), null);
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
     * The declaration members that differ: each top-level member, and within {@code transform}, {@code trigger} and
     * {@code advanced} each of theirs, as {@code transform.outputs}.
     */
    private static List<String> differingMembers(JsonNode left, JsonNode right) {
        final Set<String> keys = new LinkedHashSet<>();
        left.propertyNames().forEach(keys::add);
        right.propertyNames().forEach(keys::add);
        final List<String> members = new ArrayList<>();
        for (final String key : keys) {
            final JsonNode l = present(left.get(key));
            final JsonNode r = present(right.get(key));
            if (Objects.equals(l, r)) {
                continue;
            }
            if (l != null && r != null && l.isObject() && r.isObject() && Set.of("transform", "trigger", "advanced").contains(key)) {
                differingMembers(l, r).forEach(sub -> members.add(key + "." + sub));
            } else {
                members.add(key);
            }
        }
        return members;
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

    private static List<String> emptyToNull(List<String> list) {
        return list == null || list.isEmpty() ? null : list;
    }
}
