package com.epam.aidial.analytics.service.catalog.transfer;

import com.epam.aidial.analytics.exception.CatalogImportConflictException;
import com.epam.aidial.analytics.exception.CatalogImportInvalidException;
import com.epam.aidial.analytics.exception.CatalogTransferForbiddenException;
import com.epam.aidial.analytics.service.audit.AuditImportScope;
import com.epam.aidial.analytics.service.pipeline.PipelineEntity;
import com.epam.aidial.analytics.service.pipeline.PipelineRepository;
import com.epam.aidial.analytics.service.pipeline.PipelineService;
import com.epam.aidial.analytics.service.security.SecurityClaimsExtractor;
import com.epam.aidial.analytics.service.table.TableAccessService;
import com.epam.aidial.analytics.service.table.TableAccessService.ImportStatus;
import com.epam.aidial.analytics.service.table.TableCatalogService;
import com.epam.aidial.analytics.service.table.TableSchemaCreationService;
import com.epam.aidial.analytics.service.table.grant.TableAccessEvaluator;
import com.epam.aidial.analytics.web.dto.catalog.BundleTable;
import com.epam.aidial.analytics.web.dto.catalog.CatalogBundle;
import com.epam.aidial.analytics.web.dto.catalog.ImportPreview;
import com.epam.aidial.analytics.web.dto.catalog.ImportResult;
import com.epam.aidial.analytics.web.dto.catalog.PlannedChange;
import com.epam.aidial.analytics.web.dto.catalog.ResolutionPolicy;
import com.epam.aidial.analytics.web.dto.table.CreateTableRequest.ColumnRequest;
import com.epam.aidial.analytics.web.mapper.pipeline.PipelineMapper;
import com.epam.aidial.analytics.web.mapper.table.TableMapper;
import com.epam.aidial.analytics.web.security.Privilege;
import com.epam.aidial.analytics.web.security.SecurityModeResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Imports a catalog bundle: {@link #preview} reports what an import would do, and {@link #apply} does it.
 * Both run the same planner and the same validation pass, so they cannot disagree.
 *
 * <p><b>Apply is step-committed, not one transaction</b>, and deliberately not {@code @Transactional}: ClickHouse
 * DDL cannot share a PostgreSQL transaction, and each created object must be its own audit revision under the
 * importing user, tagged with the import id (see {@link AuditImportScope}). Every collision is checked up front; then
 * each object is created in import order (sources, then
 * enrichments, then pipelines, always disabled), and, with {@code include_access}, the bundle's access entries are
 * written last, in one transaction. On the first
 * failure, what this import created is deleted again and what it updated reverted, in reverse order. That compensation
 * is best effort: a crash mid-import, or a ClickHouse drop that runs before a catalog delete fails, can leave an object
 * behind, and re-running the bundle with {@code SKIP_IF_EXISTS} or {@code OVERWRITE} converges on it.
 *
 * <p><b>Only {@code OVERWRITE} changes an existing object</b>, and only through the services its endpoints use: it adds
 * columns and updates metadata through {@code PUT /v1/tables/{name}} and the schema patch, and re-declares a pipeline
 * through its {@code PATCH}, an enabled aggregate disabled first and left so ({@link ObjectUpdater}). Compensation then
 * reverts what was updated as well as deleting what was created. A required system table is checked by name, never
 * written. Only an import with {@code include_access}, which needs {@code TABLE_GRANT}, writes access entries, onto
 * created, existing and system tables alike: per role, never removing one.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CatalogImportService {

    static final String COMPLETED = "completed";
    static final String CREATED = "created";
    static final String UPDATED = "updated";
    static final String SKIPPED = "skipped";
    static final String ROLLED_BACK = "rolled_back";
    static final String ROLLBACK_FAILED = "rollback_failed";
    static final String FAILED = "failed";

    private static final String PREVIEW_POINTER = "; POST /v1/catalog/import/preview reports each problem";

    private final CatalogBundleParser parser;
    private final CatalogImportPlanner planner;
    private final CatalogImportValidator validator;
    private final ImportTargetReader targetReader;
    private final TableCatalogService catalog;
    private final TableSchemaCreationService schemas;
    private final PipelineService pipelineService;
    private final PipelineRepository pipelines;
    private final TableMapper tableMapper;
    private final PipelineMapper pipelineMapper;
    private final SecurityModeResolver securityMode;
    private final SecurityClaimsExtractor claims;
    private final AuditImportScope importScope;
    private final TableAccessService tableAccess;
    private final ObjectUpdater updater;
    private final TableAccessEvaluator tablePrivileges;

    /** What importing {@code file} would do. Writes nothing; problems are reported, not thrown. */
    public ImportPreview preview(byte[] file, boolean acknowledgeReusedNames, ResolutionPolicy policy, boolean includeAccess) {
        final ImportPlan plan = prepare(file, acknowledgeReusedNames, policy, includeAccess);
        validator.validate(plan);
        return plan.toPreview();
    }

    /**
     * Imports {@code file}: 409 under {@code FAIL_IF_EXISTS} when anything exists, 422 when the validation pass
     * finds a problem, and otherwise the per-object outcome. Conflicts are checked before the validation pass,
     * which a refused import need not pay for.
     */
    public ImportResult apply(byte[] file, boolean acknowledgeReusedNames, ResolutionPolicy policy, boolean includeAccess) {
        final ImportPlan plan = prepare(file, acknowledgeReusedNames, policy, includeAccess);
        if (plan.hasConflicts()) {
            final String existing = plan.all().filter(PlannedObject::isConflict)
                    .map(planned -> planned.kind().code() + " '" + planned.name() + "'")
                    .collect(Collectors.joining(", "));
            throw new CatalogImportConflictException("the bundle holds objects that already exist on this environment ("
                    + existing + ") and resolution_policy is FAIL_IF_EXISTS; nothing was written" + PREVIEW_POINTER);
        }
        validator.validate(plan);
        if (plan.hasProblems()) {
            final long refused = plan.all().filter(PlannedObject::failed).count();
            final long refusedAccess = plan.access().stream().filter(PlannedAccess::failed).count();
            throw new CatalogImportInvalidException("the bundle cannot be imported: " + refused + " object(s) refused"
                    + (refusedAccess == 0 ? "" : ", " + refusedAccess + " access entr" + (refusedAccess == 1 ? "y" : "ies")
                    + " refused") + (plan.validationErrors().isEmpty() ? "" : " and " + plan.validationErrors().size()
                    + " bundle-level problem(s)") + "; nothing was written" + PREVIEW_POINTER);
        }
        return applyPlan(plan, sha256(file), policy);
    }

    /** The authorized plan, before the validation pass. */
    private ImportPlan prepare(byte[] file, boolean acknowledgeReusedNames, ResolutionPolicy policy, boolean includeAccess) {
        final CatalogBundle bundle = parser.parseBundle(file);
        // The access API's own gate, before the target's access is read: it depends on nothing the plan holds.
        if (includeAccess && securityMode.isEnforced()) {
            require(Privilege.TABLE_GRANT, "writing access entries");
        }
        final ImportPlan plan = planner.plan(bundle, acknowledgeReusedNames, policy, targetReader.read(bundle, includeAccess),
                includeAccess);
        authorize(plan);
        return plan;
    }

    /**
     * The endpoint gates of the services import calls in-process, which it bypasses: define-schema's global
     * {@code TABLE_MODIFY}, the schema patch's {@code TABLE_MODIFY} on each table an update changes, held globally or
     * granted on it, the registry's {@code REGISTRY_ADMIN} for a pipeline created or updated, and {@code TABLE_SENSITIVE}
     * for a sensitive column: globally for one created, since a new table has no grants, and held globally or granted on
     * the table for one an update adds or raises. All before the collision check.
     */
    private void authorize(ImportPlan plan) {
        if (!securityMode.isEnforced()) {
            return;
        }
        final List<BundleTable> created = plan.tables().stream().filter(PlannedObject::creates).map(PlannedObject::table)
                .toList();
        if (!created.isEmpty()) {
            require(Privilege.TABLE_MODIFY, "creating tables");
        }
        for (final PlannedObject updated : plan.tables().stream().filter(PlannedObject::intendsUpdate).toList()) {
            if (!tablePrivileges.isAllowed(updated.name(), Privilege.TABLE_MODIFY)) {
                throw new CatalogTransferForbiddenException("importing this bundle means updating table '" + updated.name()
                        + "', which requires the TABLE_MODIFY privilege on it");
            }
        }
        if (plan.pipelines().stream().anyMatch(PlannedObject::creates)) {
            require(Privilege.REGISTRY_ADMIN, "creating pipelines");
        }
        if (plan.pipelines().stream().anyMatch(PlannedObject::intendsUpdate)) {
            require(Privilege.REGISTRY_ADMIN, "re-declaring existing pipelines");
        }
        final boolean sensitive = created.stream()
                .flatMap(table -> table.schema().columns() == null ? Stream.empty() : table.schema().columns().stream())
                .map(ColumnRequest::sensitive)
                .anyMatch(Boolean.TRUE::equals);
        if (sensitive) {
            require(Privilege.TABLE_SENSITIVE, "declaring sensitive columns on tables it creates");
        }
        // The schema patch asks the clearance on the table it changes, so a grant on that table is enough, as for TABLE_MODIFY.
        for (final PlannedObject updated : plan.tables().stream().filter(PlannedObject::intendsUpdate)
                .filter(CatalogImportService::raisesSensitive).toList()) {
            if (!tablePrivileges.isAllowed(updated.name(), Privilege.TABLE_SENSITIVE)) {
                throw new CatalogTransferForbiddenException("importing this bundle means marking columns of table '"
                        + updated.name() + "' sensitive, which requires the TABLE_SENSITIVE privilege on it");
            }
        }
    }

    /** Whether an update adds a sensitive column or sets {@code sensitive: true} on one. */
    private static boolean raisesSensitive(PlannedObject table) {
        final List<ColumnRequest> columns = table.table().schema().columns() == null ? List.of() : table.table().schema().columns();
        return table.changes().stream().anyMatch(change -> switch (change.op()) {
            case UPDATE_COLUMN -> change.fields().contains("sensitive");
            case ADD_COLUMN -> columns.stream()
                    .anyMatch(column -> column.name().equals(change.name()) && Boolean.TRUE.equals(column.sensitive()));
            default -> false;
        });
    }

    private void require(Privilege privilege, String what) {
        if (!claims.hasPrivilege(privilege)) {
            throw new CatalogTransferForbiddenException("importing this bundle means " + what + ", which requires the "
                    + privilege + " privilege");
        }
    }

    // --- apply ---------------------------------------------------------------------------------------

    /**
     * Applies the plan with an audit import scope open, so every revision a step or its compensation commits is
     * tagged with the same id the result and the {@code catalog_import} event carry.
     */
    private ImportResult applyPlan(ImportPlan plan, String bundleHash, ResolutionPolicy policy) {
        final UUID importId = UUID.randomUUID();
        try (AuditImportScope.Scope ignored = importScope.open(importId)) {
            return applySteps(plan, importId.toString(), bundleHash, policy);
        }
    }

    private ImportResult applySteps(ImportPlan plan, String importId, String bundleHash, ResolutionPolicy policy) {
        // What this import wrote, created and updated alike, in apply order, for compensation to undo in reverse.
        final List<PlannedObject> writtenTables = new ArrayList<>();
        final List<PlannedObject> writtenPipelines = new ArrayList<>();
        PlannedObject failedAt = null;
        plan.systemTables().forEach(planned -> planned.status(SKIPPED));
        for (final PlannedObject table : plan.tables()) {
            if (failedAt != null) {
                notAttempted(table, failedAt);
            } else if (!table.writes()) {
                table.status(SKIPPED);
            } else if (table.updates() ? !updateTable(table, writtenTables) : !createTable(table, writtenTables)) {
                failedAt = table;
            }
        }
        for (final PlannedObject pipeline : plan.pipelines()) {
            if (failedAt != null) {
                notAttempted(pipeline, failedAt);
            } else if (!pipeline.writes()) {
                pipeline.status(SKIPPED);
            } else if (pipeline.updates() ? !updatePipeline(pipeline, writtenPipelines) : !createPipeline(pipeline, writtenPipelines)) {
                failedAt = pipeline;
            }
        }
        boolean accessFailed = false;
        if (failedAt != null) {
            final String stoppedAt = failedAt.kind().code() + " '" + failedAt.name() + "'";
            plan.access().forEach(entry -> {
                entry.status(SKIPPED);
                entry.problems().add("not attempted: the import stopped at " + stoppedAt);
            });
            compensate(writtenPipelines, writtenTables, failedAt);
        } else if (!plan.access().isEmpty()) {
            accessFailed = !importAccess(plan);
            if (accessFailed) {
                compensate(writtenPipelines, writtenTables, null);
            }
        }
        final String outcome = failedAt == null && !accessFailed ? COMPLETED
                : plan.all().anyMatch(planned -> ROLLBACK_FAILED.equals(planned.status())) ? ROLLBACK_FAILED : ROLLED_BACK;
        final String objects = Stream.concat(plan.all()
                        .map(planned -> planned.kind().code() + ":" + planned.name() + "(" + planned.action().code().toLowerCase(Locale.ROOT)
                                + ")=" + planned.status()),
                        plan.access().stream().map(entry -> "access:" + entry.table() + "/" + entry.role() + "=" + entry.status()))
                .collect(Collectors.joining(", "));
        if (COMPLETED.equals(outcome)) {
            log.info("catalog_import import_id={} bundle_sha256={} resolution_policy={} outcome={} objects=[{}]", importId,
                    bundleHash, policy, outcome, objects);
        } else {
            log.warn("catalog_import import_id={} bundle_sha256={} resolution_policy={} outcome={} objects=[{}]", importId,
                    bundleHash, policy, outcome, objects);
        }
        return new ImportResult(importId, outcome, ImportPlan.entries(plan.systemTables()), ImportPlan.entries(plan.tables()),
                ImportPlan.entries(plan.pipelines()), List.copyOf(plan.envSpecific()), plan.accessEntries());
    }

    /**
     * Writes every access entry in one transaction, after every object, as one audit revision under the open import
     * scope. Returns false when it fails, which writes none of them and marks each entry rolled back.
     */
    private boolean importAccess(ImportPlan plan) {
        try {
            final Map<String, Map<String, ImportStatus>> written = tableAccess.importEntries(plan.accessBodies(entry -> true));
            plan.access().forEach(entry -> entry.status(written.get(entry.table()).get(entry.role()).name().toLowerCase(Locale.ROOT)));
            return true;
        } catch (RuntimeException ex) {
            log.warn("Catalog import: writing the access failed; compensating", ex);
            plan.access().forEach(entry -> {
                entry.status(ROLLED_BACK);
                entry.problems().add("rolled back: writing the access failed: " + describe(ex));
            });
            return false;
        }
    }

    /** Creates one table and its schema; each its own committed step. */
    private boolean createTable(PlannedObject planned, List<PlannedObject> created) {
        final BundleTable table = planned.table();
        try {
            catalog.create(tableMapper.toCreateInput(table.table()));
            created.add(planned);
            schemas.defineSchema(planned.name(), tableMapper.toDefineInput(table.schema()));
            planned.status(CREATED);
            return true;
        } catch (RuntimeException ex) {
            log.warn("Catalog import: creating table '{}' failed; compensating", planned.name(), ex);
            planned.status(FAILED);
            planned.problems().add(describe(ex));
            return false;
        }
    }

    /** Creates one pipeline, recorded for compensation once it committed. */
    private boolean createPipeline(PlannedObject planned, List<PlannedObject> created) {
        try {
            pipelineService.create(pipelineMapper.toRegistration(planned.pipeline()));
            created.add(planned);
            planned.status(CREATED);
            return true;
        } catch (RuntimeException ex) {
            log.warn("Catalog import: creating pipeline '{}' failed; compensating", planned.name(), ex);
            planned.status(FAILED);
            planned.problems().add(describe(ex));
            return false;
        }
    }

    /**
     * Updates one existing table: its metadata, then its schema patch, each its own committed step. Recorded for
     * compensation before the first step, which reverts only the steps that committed.
     */
    private boolean updateTable(PlannedObject planned, List<PlannedObject> written) {
        written.add(planned);
        try {
            updater.updateTable(planned);
            planned.status(UPDATED);
            return true;
        } catch (RuntimeException ex) {
            log.warn("Catalog import: updating table '{}' failed; compensating", planned.name(), ex);
            planned.status(FAILED);
            planned.problems().add(describe(ex));
            return false;
        }
    }

    /** Disables one existing pipeline when the plan says so, then re-declares it, each its own committed step. */
    private boolean updatePipeline(PlannedObject planned, List<PlannedObject> written) {
        written.add(planned);
        try {
            updater.updatePipeline(planned);
            planned.status(UPDATED);
            return true;
        } catch (RuntimeException ex) {
            log.warn("Catalog import: updating pipeline '{}' failed; compensating", planned.name(), ex);
            planned.status(FAILED);
            planned.problems().add(describe(ex));
            return false;
        }
    }

    private static void notAttempted(PlannedObject planned, PlannedObject failedAt) {
        planned.status(SKIPPED);
        if (planned.writes()) {
            planned.problems().add("not attempted: the import stopped at " + failedAt.kind().code() + " '"
                    + failedAt.name() + "'");
        }
    }

    /**
     * Undoes what this import wrote, pipelines first and then tables, each in reverse apply order (enrichments before
     * their sources, as delete requires): a created object is deleted, an updated one reverted to the target's object as
     * the plan read it ({@link ObjectUpdater}). An updated pipeline the import disabled is re-enabled only after the
     * tables are reverted, since arming runs the gate against its target as it then is. A created table that a pipeline
     * still targets is left in place, since deleting the table would delete that pipeline with it: either the pipeline
     * was not created by this import, or it was and its own rollback failed.
     *
     * @param writtenPipelines the pipelines this import created or updated, in apply order
     * @param writtenTables    the tables this import created or updated, in apply order
     */
    private void compensate(List<PlannedObject> writtenPipelines, List<PlannedObject> writtenTables,
            PlannedObject failedAt) {
        final Map<PlannedObject, String> pipelineFailures = new HashMap<>();
        for (final PlannedObject pipeline : writtenPipelines.reversed()) {
            try {
                if (pipeline.updates()) {
                    updater.revertDeclaration(pipeline);
                } else {
                    pipelineService.delete(pipeline.name());
                }
            } catch (RuntimeException ex) {
                log.warn("Catalog import: rolling back pipeline '{}' failed", pipeline.name(), ex);
                pipelineFailures.put(pipeline, "rollback failed: " + describe(ex));
            }
        }
        revertTables(writtenTables, writtenPipelines, failedAt);
        for (final PlannedObject pipeline : writtenPipelines.reversed()) {
            if (pipeline.updates() && !pipelineFailures.containsKey(pipeline)) {
                try {
                    updater.restoreEnabled(pipeline);
                } catch (RuntimeException ex) {
                    log.warn("Catalog import: re-enabling pipeline '{}' failed", pipeline.name(), ex);
                    pipelineFailures.put(pipeline, "rollback failed: re-enabling it failed: " + describe(ex));
                }
            }
            markRollback(pipeline, failedAt, !pipelineFailures.containsKey(pipeline), pipelineFailures.get(pipeline));
        }
    }

    /** The table half of {@link #compensate}. */
    private void revertTables(List<PlannedObject> writtenTables, List<PlannedObject> writtenPipelines, PlannedObject failedAt) {
        for (final PlannedObject table : writtenTables.reversed()) {
            if (table.updates()) {
                try {
                    updater.revertTable(table);
                    markRollback(table, failedAt, true, null);
                } catch (RuntimeException ex) {
                    log.warn("Catalog import: reverting table '{}' failed", table.name(), ex);
                    markRollback(table, failedAt, false, "rollback failed: " + describe(ex));
                }
                continue;
            }
            final Optional<PipelineEntity> owner = pipelines.findByTarget(table.name());
            if (owner.isPresent()) {
                final String pipeline = owner.get().getName();
                final boolean ours = writtenPipelines.stream().anyMatch(created -> created.creates()
                        && created.name().equals(pipeline));
                markRollback(table, failedAt, false, "kept: pipeline '" + pipeline + "' targets it and "
                        + (ours ? "was created by this import, but its rollback failed" : "was not created by this import"));
                continue;
            }
            try {
                catalog.delete(table.name());
                markRollback(table, failedAt, true, null);
            } catch (RuntimeException ex) {
                log.warn("Catalog import: rolling back table '{}' failed", table.name(), ex);
                markRollback(table, failedAt, false, "rollback failed: " + describe(ex));
            }
        }
    }

    /**
     * The failing object keeps {@code failed}; anything else written before it reports how its rollback went.
     *
     * @param removed whether the object was deleted, or, for an update, reverted
     */
    private static void markRollback(PlannedObject table, PlannedObject failedAt, boolean removed, String problem) {
        if (problem != null) {
            table.problems().add(problem);
        }
        if (table == failedAt) {
            if (removed) {
                table.problems().add("rolled back");
            } else {
                table.status(ROLLBACK_FAILED);
            }
            return;
        }
        table.status(removed ? ROLLED_BACK : ROLLBACK_FAILED);
    }

    /**
     * A refusal as an entry problem: the message of one of ADAS's own exceptions, which is written for API
     * callers, and only the type of anything else, whose message may carry SQL or driver detail.
     */
    static String describe(RuntimeException ex) {
        if (ex.getClass().getPackageName().equals(CatalogImportInvalidException.class.getPackageName())
                && ex.getMessage() != null) {
            return ex.getMessage();
        }
        return "refused by the catalog (" + ex.getClass().getSimpleName() + ")";
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
