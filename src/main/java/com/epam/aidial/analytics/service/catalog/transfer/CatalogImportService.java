package com.epam.aidial.analytics.service.catalog.transfer;

import com.epam.aidial.analytics.exception.CatalogImportConflictException;
import com.epam.aidial.analytics.exception.CatalogImportForbiddenException;
import com.epam.aidial.analytics.exception.CatalogImportInvalidException;
import com.epam.aidial.analytics.exception.ResolutionPolicyUnsupportedException;
import com.epam.aidial.analytics.service.audit.AuditImportScope;
import com.epam.aidial.analytics.service.pipeline.PipelineEntity;
import com.epam.aidial.analytics.service.pipeline.PipelineRepository;
import com.epam.aidial.analytics.service.pipeline.PipelineService;
import com.epam.aidial.analytics.service.security.SecurityClaimsExtractor;
import com.epam.aidial.analytics.service.table.TableCatalogService;
import com.epam.aidial.analytics.service.table.TableSchemaCreationService;
import com.epam.aidial.analytics.web.dto.catalog.BundleTable;
import com.epam.aidial.analytics.web.dto.catalog.CatalogBundle;
import com.epam.aidial.analytics.web.dto.catalog.ImportPreview;
import com.epam.aidial.analytics.web.dto.catalog.ImportResult;
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
import java.util.HexFormat;
import java.util.List;
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
 * enrichments, then pipelines, always disabled; no grants). On the first
 * failure, what this import created is deleted again in reverse order. That compensation is best effort: a crash
 * mid-import, or a ClickHouse drop that runs before a catalog delete fails, can leave an object behind, and
 * re-running the bundle with {@code SKIP_IF_EXISTS} converges on it.
 *
 * <p><b>Import never mutates an existing object.</b> System tables included: a required system table is
 * checked by name, and its grants are neither compared nor written.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CatalogImportService {

    static final String COMPLETED = "completed";
    static final String CREATED = "created";
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

    /** What importing {@code file} would do. Writes nothing; problems are reported, not thrown. */
    public ImportPreview preview(byte[] file, boolean acknowledgeReusedNames, ResolutionPolicy policy) {
        final ImportPlan plan = prepare(file, acknowledgeReusedNames, policy);
        validator.validate(plan);
        return plan.toPreview();
    }

    /**
     * Imports {@code file}: 409 under {@code FAIL_IF_EXISTS} when anything exists, 422 when the validation pass
     * finds a problem, and otherwise the per-object outcome. Conflicts are checked before the validation pass,
     * which a refused import need not pay for.
     */
    public ImportResult apply(byte[] file, boolean acknowledgeReusedNames, ResolutionPolicy policy) {
        final ImportPlan plan = prepare(file, acknowledgeReusedNames, policy);
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
            throw new CatalogImportInvalidException("the bundle cannot be imported: " + refused + " object(s) refused"
                    + (plan.validationErrors().isEmpty() ? "" : " and " + plan.validationErrors().size()
                    + " bundle-level problem(s)") + "; nothing was written" + PREVIEW_POINTER);
        }
        return applyPlan(plan, sha256(file), policy);
    }

    /** The authorized plan, before the validation pass. */
    private ImportPlan prepare(byte[] file, boolean acknowledgeReusedNames, ResolutionPolicy policy) {
        if (policy == ResolutionPolicy.OVERWRITE) {
            throw new ResolutionPolicyUnsupportedException("resolution_policy OVERWRITE is not supported: import never "
                    + "changes an existing object; use SKIP_IF_EXISTS and change existing objects through their own "
                    + "endpoints");
        }
        final CatalogBundle bundle = parser.parseBundle(file);
        final ImportPlan plan = planner.plan(bundle, acknowledgeReusedNames, policy, targetReader.read(bundle));
        authorize(plan);
        return plan;
    }

    /**
     * The endpoint gates of the services import calls in-process, which it bypasses: define-schema's global
     * {@code TABLE_MODIFY}, pipeline registration's {@code REGISTRY_ADMIN}, and {@code TABLE_SENSITIVE}
     * for a sensitive column, which on a table with no grants only the global clearance can satisfy.
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
        if (plan.pipelines().stream().anyMatch(PlannedObject::creates)) {
            require(Privilege.REGISTRY_ADMIN, "creating pipelines");
        }
        final boolean sensitive = created.stream()
                .flatMap(table -> table.schema().columns() == null ? Stream.empty() : table.schema().columns().stream())
                .map(ColumnRequest::sensitive)
                .anyMatch(Boolean.TRUE::equals);
        if (sensitive) {
            require(Privilege.TABLE_SENSITIVE, "declaring sensitive columns on tables it creates");
        }
    }

    private void require(Privilege privilege, String what) {
        if (!claims.hasPrivilege(privilege)) {
            throw new CatalogImportForbiddenException("importing this bundle means " + what + ", which requires the "
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
        final List<PlannedObject> createdTables = new ArrayList<>();
        final List<PlannedObject> createdPipelines = new ArrayList<>();
        PlannedObject failedAt = null;
        plan.systemTables().forEach(planned -> planned.status(SKIPPED));
        for (final PlannedObject table : plan.tables()) {
            if (failedAt != null) {
                notAttempted(table, failedAt);
            } else if (!table.creates()) {
                table.status(SKIPPED);
            } else if (!createTable(table, createdTables)) {
                failedAt = table;
            }
        }
        for (final PlannedObject pipeline : plan.pipelines()) {
            if (failedAt != null) {
                notAttempted(pipeline, failedAt);
            } else if (!pipeline.creates()) {
                pipeline.status(SKIPPED);
            } else if (createPipeline(pipeline)) {
                createdPipelines.add(pipeline);
            } else {
                failedAt = pipeline;
            }
        }
        if (failedAt != null) {
            compensate(createdPipelines, createdTables, failedAt);
        }
        final String outcome = failedAt == null ? COMPLETED
                : plan.all().anyMatch(planned -> ROLLBACK_FAILED.equals(planned.status())) ? ROLLBACK_FAILED : ROLLED_BACK;
        final String objects = plan.all()
                .map(planned -> planned.kind().code() + ":" + planned.name() + "=" + planned.status())
                .collect(Collectors.joining(", "));
        if (COMPLETED.equals(outcome)) {
            log.info("catalog_import import_id={} bundle_sha256={} resolution_policy={} outcome={} objects=[{}]", importId,
                    bundleHash, policy, outcome, objects);
        } else {
            log.warn("catalog_import import_id={} bundle_sha256={} resolution_policy={} outcome={} objects=[{}]", importId,
                    bundleHash, policy, outcome, objects);
        }
        return new ImportResult(importId, outcome, ImportPlan.entries(plan.systemTables()), ImportPlan.entries(plan.tables()),
                ImportPlan.entries(plan.pipelines()), List.copyOf(plan.envSpecific()));
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

    private boolean createPipeline(PlannedObject planned) {
        try {
            pipelineService.create(pipelineMapper.toRegistration(planned.pipeline()));
            planned.status(CREATED);
            return true;
        } catch (RuntimeException ex) {
            log.warn("Catalog import: creating pipeline '{}' failed; compensating", planned.name(), ex);
            planned.status(FAILED);
            planned.problems().add(describe(ex));
            return false;
        }
    }

    private static void notAttempted(PlannedObject planned, PlannedObject failedAt) {
        planned.status(SKIPPED);
        if (planned.creates()) {
            planned.problems().add("not attempted: the import stopped at " + failedAt.kind().code() + " '"
                    + failedAt.name() + "'");
        }
    }

    /**
     * Deletes what this import created, pipelines first and then tables in reverse order (enrichments before their
     * sources, as delete requires). A table that a pipeline still targets is left in place, since deleting the
     * table would delete that pipeline with it: either the pipeline was not created by this import, or it was and
     * its own rollback failed.
     */
    private void compensate(List<PlannedObject> createdPipelines, List<PlannedObject> createdTables,
            PlannedObject failedAt) {
        for (final PlannedObject pipeline : createdPipelines.reversed()) {
            try {
                pipelineService.delete(pipeline.name());
                pipeline.status(ROLLED_BACK);
            } catch (RuntimeException ex) {
                log.warn("Catalog import: rolling back pipeline '{}' failed", pipeline.name(), ex);
                pipeline.status(ROLLBACK_FAILED);
                pipeline.problems().add("rollback failed: " + describe(ex));
            }
        }
        for (final PlannedObject table : createdTables.reversed()) {
            final Optional<PipelineEntity> owner = pipelines.findByTarget(table.name());
            if (owner.isPresent()) {
                final String pipeline = owner.get().getName();
                final boolean ours = createdPipelines.stream().anyMatch(created -> created.name().equals(pipeline));
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

    /** The failing object keeps {@code failed}; anything else created before it reports how its rollback went. */
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
