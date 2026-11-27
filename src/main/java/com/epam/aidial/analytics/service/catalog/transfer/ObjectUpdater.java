package com.epam.aidial.analytics.service.catalog.transfer;

import com.epam.aidial.analytics.clickhouse.DdlExecutor;
import com.epam.aidial.analytics.exception.PipelineValidationException;
import com.epam.aidial.analytics.service.pipeline.PipelineEntity;
import com.epam.aidial.analytics.service.pipeline.PipelineService;
import com.epam.aidial.analytics.service.pipeline.model.PipelineRegistration;
import com.epam.aidial.analytics.service.table.Change;
import com.epam.aidial.analytics.service.table.PatchInput;
import com.epam.aidial.analytics.service.table.TableCatalogService;
import com.epam.aidial.analytics.service.table.TableSchemaService;
import com.epam.aidial.analytics.web.dto.catalog.BundlePipeline;
import com.epam.aidial.analytics.web.dto.catalog.BundleTable;
import com.epam.aidial.analytics.web.dto.catalog.PlannedChange;
import com.epam.aidial.analytics.web.dto.pipeline.PipelineRequests.PatchPipelineRequest;
import com.epam.aidial.analytics.web.dto.table.CreateTableRequest.ColumnRequest;
import com.epam.aidial.analytics.web.dto.table.PatchSchemaRequest;
import com.epam.aidial.analytics.web.dto.table.PatchSchemaRequest.ColumnMetadataUpdate;
import com.epam.aidial.analytics.web.mapper.pipeline.PipelineMapper;
import com.epam.aidial.analytics.web.mapper.table.TableMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Applies an {@code OVERWRITE} update of an existing object, and reverts one, through the services the object's own
 * endpoints use, so every rule of those paths holds for an import: {@code PUT /v1/tables/{name}} for a table's
 * metadata, one {@code PATCH /v1/tables/{name}/schema} for its added columns and column metadata, and
 * {@code PATCH /v1/pipelines/{name}} for a pipeline's disable and re-declaration. The validation pass and the apply both
 * call it, the pass with {@link DdlExecutor#NO_OP} inside its rolled-back transaction.
 *
 * <p>Each step is marked {@linkplain PlannedObject#applied applied} once it commits, and a revert undoes only the marked
 * steps, from the target's object as the plan read it: a re-declaration with the previous declaration, the previous
 * metadata, a drop of the columns the update added, and, last, the previous {@code enabled} state.
 */
@Component
@RequiredArgsConstructor
class ObjectUpdater {

    /** The step marking a table's metadata update. */
    private static final PlannedChange.Op METADATA = PlannedChange.Op.UPDATE_TABLE;

    /** The step marking a table's schema patch, its added columns and column metadata together. */
    private static final PlannedChange.Op SCHEMA = PlannedChange.Op.UPDATE_COLUMN;

    private final TableCatalogService catalog;
    private final TableSchemaService schemas;
    private final TableMapper tableMapper;
    private final PipelineService pipelineService;
    private final PipelineMapper pipelineMapper;

    // --- tables ------------------------------------------------------------------------------------------------------

    /** Updates the table as its planned changes say: its metadata, then one schema patch, each its own step. */
    void updateTable(PlannedObject planned) {
        updateTable(planned, null);
    }

    /**
     * {@link #updateTable(PlannedObject)} with the schema patch's {@code ALTER} run through {@code executor}, or through
     * the ClickHouse one when it is null.
     */
    void updateTable(PlannedObject planned, DdlExecutor executor) {
        planned.clearApplied();
        final BundleTable bundle = planned.table();
        final List<String> tableFields = fields(planned, PlannedChange.Op.UPDATE_TABLE);
        if (!tableFields.isEmpty()) {
            catalog.replaceMetadata(planned.name(),
                    tableFields.contains("description") ? Change.set(bundle.table().description()) : Change.leave(),
                    tableFields.contains("tag_order") ? Change.set(orEmpty(bundle.table().tagOrder())) : Change.leave());
            planned.applied(METADATA);
        }
        final Map<String, ColumnRequest> bundled = byName(bundle.schema().columns());
        final List<ColumnRequest> add = planned.changes().stream()
                .filter(change -> change.op() == PlannedChange.Op.ADD_COLUMN)
                .map(change -> bundled.get(change.name()))
                .toList();
        final List<ColumnMetadataUpdate> update = planned.changes().stream()
                .filter(change -> change.op() == PlannedChange.Op.UPDATE_COLUMN)
                .map(change -> metadata(bundled.get(change.name()), change.fields()))
                .toList();
        if (!add.isEmpty() || !update.isEmpty()) {
            // The void form returns once the patch committed, so the step is marked exactly when there is one to revert.
            final PatchInput input = tableMapper.toPatchInput(patch(add, List.of(), update));
            if (executor == null) {
                schemas.applyPatch(planned.name(), input);
            } else {
                schemas.applyPatch(planned.name(), input, executor);
            }
            planned.applied(SCHEMA);
        }
    }

    /**
     * Reverts the table's applied steps from the target's object as the plan read it, each step its own write and each
     * attempted even when an earlier one failed: the column metadata, then the table metadata, then a drop of the
     * columns the update added, last because a drop is the step most likely to be refused, and a refusal must not cost
     * the restores. The first failure is thrown once every step was tried.
     */
    void revertTable(PlannedObject planned) {
        final BundleTable existing = (BundleTable) planned.existing();
        final List<Runnable> steps = new ArrayList<>();
        if (planned.wasApplied(SCHEMA)) {
            final Map<String, ColumnRequest> stored = byName(existing.schema().columns());
            final List<ColumnMetadataUpdate> restore = planned.changes().stream()
                    .filter(change -> change.op() == PlannedChange.Op.UPDATE_COLUMN)
                    .map(change -> metadata(stored.get(change.name()), change.fields()))
                    .toList();
            if (!restore.isEmpty()) {
                steps.add(() -> schemas.applyPatch(planned.name(), tableMapper.toPatchInput(patch(List.of(), List.of(), restore))));
            }
        }
        if (planned.wasApplied(METADATA)) {
            final List<String> tableFields = fields(planned, PlannedChange.Op.UPDATE_TABLE);
            steps.add(() -> catalog.replaceMetadata(planned.name(),
                    tableFields.contains("description") ? Change.set(existing.table().description()) : Change.leave(),
                    tableFields.contains("tag_order") ? Change.set(orEmpty(existing.table().tagOrder())) : Change.leave()));
        }
        if (planned.wasApplied(SCHEMA)) {
            final List<String> drop = planned.changes().stream()
                    .filter(change -> change.op() == PlannedChange.Op.ADD_COLUMN)
                    .map(PlannedChange::name)
                    .toList();
            if (!drop.isEmpty()) {
                steps.add(() -> schemas.applyPatch(planned.name(), tableMapper.toPatchInput(patch(List.of(), drop, List.of()))));
            }
        }
        RuntimeException first = null;
        for (final Runnable step : steps) {
            try {
                step.run();
            } catch (RuntimeException ex) {
                if (first == null) {
                    first = ex;
                } else {
                    first.addSuppressed(ex);
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }

    /**
     * One column's metadata as {@code column} has it, for the {@code fields} that change: a blank string clears an
     * attribute the column leaves unset, and the flags are written out.
     */
    private static ColumnMetadataUpdate metadata(ColumnRequest column, List<String> fields) {
        return new ColumnMetadataUpdate(column.name(),
                fields.contains("tag") ? orBlank(column.tag()) : null,
                fields.contains("display_name") ? orBlank(column.displayName()) : null,
                fields.contains("description") ? orBlank(column.description()) : null,
                fields.contains("sensitive") ? Boolean.TRUE.equals(column.sensitive()) : null,
                fields.contains("heavy") ? Boolean.TRUE.equals(column.heavy()) : null,
                null);
    }

    private static PatchSchemaRequest patch(List<ColumnRequest> add, List<String> drop, List<ColumnMetadataUpdate> update) {
        return new PatchSchemaRequest(add.isEmpty() ? null : add, drop.isEmpty() ? null : drop, null,
                update.isEmpty() ? null : update, null, null, null, null, null, null, null);
    }

    // --- pipelines ---------------------------------------------------------------------------------------------------

    /** Disables the pipeline when the plan says so, then re-declares it, each its own step. */
    void updatePipeline(PlannedObject planned) {
        planned.clearApplied();
        if (planned.hasChange(PlannedChange.Op.DISABLE)) {
            pipelineService.patch(planned.name(), null, false);
            planned.applied(PlannedChange.Op.DISABLE);
        }
        redeclare(planned.name(), planned.redeclaration());
        planned.applied(PlannedChange.Op.REDECLARE);
    }

    /**
     * Re-declares the pipeline's previous declaration. Its previous {@code enabled} state is restored separately
     * ({@link #restoreEnabled}), once the tables are reverted too: arming runs the gate against the target, which a
     * column the same import added fails until that column is dropped again.
     */
    void revertDeclaration(PlannedObject planned) {
        if (planned.wasApplied(PlannedChange.Op.REDECLARE)) {
            redeclare(planned.name(), ((BundlePipeline) planned.existing()).pipeline());
        }
    }

    /** Re-enables a pipeline the update disabled, the last step of its revert. */
    void restoreEnabled(PlannedObject planned) {
        if (planned.wasApplied(PlannedChange.Op.DISABLE) && ((BundlePipeline) planned.existing()).exportedEnabled()) {
            pipelineService.patch(planned.name(), null, true);
        }
    }

    /**
     * A declaration patch keeping the stored {@code enabled} state, mapped exactly as the endpoint maps one. A patch
     * naming no declaration member would be an enable-only patch that changes nothing, so it is refused rather than
     * reported as applied.
     */
    private void redeclare(String name, BundlePipeline.Declaration declaration) {
        final PipelineEntity stored = pipelineService.get(name);
        final PatchPipelineRequest request = pipelineMapper.toRedeclaration(declaration);
        final PipelineRegistration registration = pipelineMapper.toRegistration(stored, request,
                () -> pipelineService.patchedTargetParentOf(stored, request.target()));
        if (registration == null) {
            throw new PipelineValidationException("the declaration to re-declare pipeline '" + name + "' with names no "
                    + "member beside name, kind and target, so a patch would change nothing");
        }
        pipelineService.patch(name, registration, null);
    }

    // --- helpers -----------------------------------------------------------------------------------------------------

    private static List<String> fields(PlannedObject planned, PlannedChange.Op op) {
        return planned.changes().stream().filter(change -> change.op() == op).findFirst()
                .map(PlannedChange::fields).orElse(List.of());
    }

    private static Map<String, ColumnRequest> byName(List<ColumnRequest> columns) {
        return columns == null ? Map.of() : columns.stream().collect(Collectors.toMap(ColumnRequest::name, Function.identity()));
    }

    private static String orBlank(String value) {
        return value == null ? "" : value;
    }

    private static List<String> orEmpty(List<String> list) {
        return list == null ? List.of() : list;
    }
}
