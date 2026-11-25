package com.epam.aidial.analytics.service.catalog.transfer;

import com.epam.aidial.analytics.clickhouse.DdlExecutor;
import com.epam.aidial.analytics.service.pipeline.PipelineService;
import com.epam.aidial.analytics.service.table.TableAccessService;
import com.epam.aidial.analytics.service.table.TableAccessService.EntryRequest;
import com.epam.aidial.analytics.service.table.TableCatalogService;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import com.epam.aidial.analytics.service.table.TableSchemaCreationService;
import com.epam.aidial.analytics.web.dto.catalog.BundlePipeline;
import com.epam.aidial.analytics.web.dto.catalog.BundleTable;
import com.epam.aidial.analytics.web.dto.catalog.PlannedChange;
import com.epam.aidial.analytics.web.mapper.pipeline.PipelineMapper;
import com.epam.aidial.analytics.web.mapper.table.TableMapper;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The validation pass both import calls share, so a preview and an import cannot disagree.
 *
 * <p>It runs the real services — table create, define-schema, pipeline create, and for an {@code OVERWRITE} update the
 * table metadata update, schema patch and pipeline patch ({@link ObjectUpdater}) — in import order inside
 * one PostgreSQL transaction that is always rolled back, with ClickHouse DDL replaced by a no-op. Every check
 * those services make is therefore the check the import will meet, and nothing is written: no catalog row, no
 * ClickHouse table, no audit revision. Once every pipeline is created it checks every access entry of an import
 * with {@code include_access} and, when none is refused, applies every one that changes its table, so the arm checks and the ineffective-grant
 * reports see the access the import would write. It then arms each pipeline the same way {@code PATCH enabled=true}
 * does, inside the same transaction, to report {@code armable}; arming only after all are created means an enrich
 * pipeline reading another one's target is checked against that pipeline, whichever comes first. Existing objects
 * the import keeps are not re-created, so later objects are validated against them rather than against the bundle's
 * version — under {@code FAIL_IF_EXISTS} too, so the preview of a conflicting bundle still validates its new objects —
 * and objects it updates are updated first, so later objects are validated against them as the import leaves them.
 *
 * <p>The pass runs in a transaction of its own ({@code REQUIRES_NEW}), so it can never doom a caller's. The
 * services join it (none uses {@code REQUIRES_NEW}), and a refusal one of them throws marks it rollback-only
 * without stopping later statements. A failure in PostgreSQL itself does stop them: the pass then ends at that
 * object, and every object after it is reported as not validated (an arm check after it, as not checked).
 *
 * <p>The services log what they do as usual, so the pass runs with {@value #MDC_KEY}{@code =true} in the MDC:
 * a "registered" or "enabled" line carrying it was rolled back.
 */
@Slf4j
@Component
class CatalogImportValidator {

    /** The MDC key marking log lines written inside the validation pass, whose writes are all rolled back. */
    static final String MDC_KEY = "catalog_import_validation";

    private final TransactionTemplate transactions;
    private final TableCatalogService catalog;
    private final TableSchemaCreationService schemas;
    private final PipelineService pipelineService;
    private final TableMapper tableMapper;
    private final PipelineMapper pipelineMapper;
    private final JdbcTemplate jdbc;
    private final TableAccessService tableAccess;
    private final TableDefinitionRepository definitions;
    private final ObjectUpdater updater;

    CatalogImportValidator(PlatformTransactionManager transactionManager, TableCatalogService catalog,
            TableSchemaCreationService schemas, PipelineService pipelineService,
            TableMapper tableMapper, PipelineMapper pipelineMapper, JdbcTemplate jdbc, TableAccessService tableAccess,
            TableDefinitionRepository definitions, ObjectUpdater updater) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.catalog = catalog;
        this.schemas = schemas;
        this.pipelineService = pipelineService;
        this.tableMapper = tableMapper;
        this.pipelineMapper = pipelineMapper;
        this.jdbc = jdbc;
        this.tableAccess = tableAccess;
        this.definitions = definitions;
        this.updater = updater;
    }

    /** Runs the pass over {@code plan}, adding each refusal to the object it concerns. Writes nothing. */
    void validate(ImportPlan plan) {
        MDC.put(MDC_KEY, "true");
        try {
            log.debug("Catalog import validation pass started: everything it writes is rolled back");
            transactions.executeWithoutResult(status -> {
                status.setRollbackOnly();
                new Pass(plan).run();
            });
            log.debug("Catalog import validation pass finished and rolled back");
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** One pass. */
    private final class Pass {

        private final ImportPlan plan;
        private final Set<String> refused = new HashSet<>();
        private String stoppedAt;
        private boolean accessFailed;

        private Pass(ImportPlan plan) {
            this.plan = plan;
            // A conflict is not refused here: the existing object stays, and what needs it is validated against it.
            plan.all().filter(planned -> planned.failed() && !planned.isConflict())
                    .forEach(planned -> refused.add(planned.name()));
        }

        private void run() {
            for (final PlannedObject table : plan.tables()) {
                if (!table.writes() || stopped(table)) {
                    continue;
                }
                if (table.updates()) {
                    attempt(table, () -> updater.updateTable(table, DdlExecutor.NO_OP));
                    continue;
                }
                final String source = table.table().table().sourceTable();
                if (source != null && refused.contains(source)) {
                    refuse(table, source);
                    continue;
                }
                attempt(table, () -> createTable(table.table()));
            }
            for (final PlannedObject pipeline : plan.pipelines()) {
                if (!pipeline.writes() || stopped(pipeline)) {
                    continue;
                }
                final BundlePipeline.Declaration declaration = pipeline.pipeline().pipeline();
                final String refusedDependency = Stream.concat(CatalogImportPlanner.dependencies(declaration).stream(),
                                CatalogImportPlanner.sqlReadTables(declaration).stream())
                        .filter(refused::contains).findFirst().orElse(null);
                if (refusedDependency != null) {
                    refuse(pipeline, refusedDependency);
                    continue;
                }
                if (pipeline.updates()) {
                    attempt(pipeline, () -> updater.updatePipeline(pipeline));
                } else {
                    attempt(pipeline, () -> pipelineService.create(pipelineMapper.toRegistration(pipeline.pipeline())));
                }
            }
            checkAccess();
            for (final PlannedObject pipeline : plan.pipelines()) {
                if (pipeline.failed()) {
                    continue;
                }
                if (accessFailed) {
                    pipeline.armProblem("not checked: applying the bundle's access in the validation pass failed, and "
                            + "the access may be partly applied; preview again");
                    continue;
                }
                if (stoppedAt != null) {
                    pipeline.armProblem("not checked: the validation pass stopped at '" + stoppedAt + "', whose failure "
                            + "ended its transaction; fix that object and preview again");
                    continue;
                }
                arm(pipeline);
            }
        }

        /**
         * Checks every access entry with the rules of a {@code PUT}, against the target as the pass leaves it, and, when
         * none is refused, applies every {@code CREATE} and {@code REPLACE}, so arming and the reports see them. A refusal is reported as
         * {@code <code>: <message>}.
         */
        private void checkAccess() {
            if (plan.access().isEmpty()) {
                return;
            }
            for (final PlannedAccess entry : plan.access()) {
                if (entry.failed()) {
                    continue;
                }
                if (stoppedAt != null) {
                    entry.fail("not checked: the validation pass stopped at '" + stoppedAt + "', whose failure ended its "
                            + "transaction; fix that object and preview again");
                    continue;
                }
                if (tableRefused(entry.table())) {
                    entry.fail("table '" + entry.table() + "' cannot be imported, so its access cannot either");
                    continue;
                }
                final TableDefinitionEntity definition = definitions.findById(entry.table()).orElse(null);
                if (definition == null) {
                    entry.fail("table '" + entry.table() + "' does not exist on this environment");
                    continue;
                }
                try {
                    entry.checked(tableAccess.importEntryFor(definition, entry.key(), entry.bundled().toRequest()));
                } catch (RuntimeException ex) {
                    final String code = TableAccessService.refusalCode(ex);
                    entry.fail(code == null ? CatalogImportService.describe(ex) : code + ": " + CatalogImportService.describe(ex));
                    if (code == null && transactionAborted()) {
                        stoppedAt = entry.table();
                    }
                }
            }
            if (plan.access().stream().anyMatch(PlannedAccess::failed)) {
                return;
            }
            // Only a changing entry is applied, so a table whose entries all equal the target's is never locked. The pass
            // has loaded these rows before importEntries locks them, so the preview may judge a copy a moment older than
            // the lock; nothing it writes survives, and the import itself locks first in a fresh transaction.
            final Map<String, Map<String, EntryRequest>> changes = plan.accessBodies(PlannedAccess::changes);
            try {
                if (!changes.isEmpty()) {
                    tableAccess.importEntries(changes);
                }
                plan.access().forEach(entry -> entry.ineffectiveGrants(tableAccess.ineffectiveGrants(entry.table(), entry.role())));
            } catch (RuntimeException ex) {
                accessFailed = true;
                plan.access().forEach(entry -> {
                    entry.clearIneffectiveGrants();
                    entry.fail("applying the access failed: " + CatalogImportService.describe(ex));
                });
            }
        }

        /** Whether the bundle's table or required system table {@code name} is refused, a conflict excepted. */
        private boolean tableRefused(String name) {
            return Stream.concat(plan.systemTables().stream(), plan.tables().stream())
                    .anyMatch(table -> table.name().equals(name) && table.failed() && !table.isConflict());
        }

        private void refuse(PlannedObject planned, String dependency) {
            planned.fail("depends on '" + dependency + "', which cannot be imported");
            refused.add(planned.name());
        }

        private void createTable(BundleTable table) {
            final String name = table.table().name();
            catalog.create(tableMapper.toCreateInput(table.table()));
            schemas.defineSchema(name, tableMapper.toDefineInput(table.schema()), DdlExecutor.NO_OP);
        }

        /**
         * What {@code PATCH enabled=true} would say about the pipeline as the import leaves it. A pipeline the import keeps
         * enabled is armed already: one it skips passed the gate when it was armed, and one it updates passed it again in
         * its own re-declaration, which runs the gate because it leaves the pipeline enabled.
         */
        private void arm(PlannedObject pipeline) {
            if (pipeline.existing() instanceof BundlePipeline existing && existing.exportedEnabled()
                    && !pipeline.hasChange(PlannedChange.Op.DISABLE)) {
                pipeline.armable(true);
                return;
            }
            try {
                pipelineService.patch(pipeline.name(), null, true);
                pipeline.armable(true);
            } catch (RuntimeException ex) {
                pipeline.armProblem(CatalogImportService.describe(ex));
                if (transactionAborted()) {
                    stoppedAt = pipeline.name();
                }
            }
        }

        private boolean attempt(PlannedObject planned, Runnable step) {
            try {
                step.run();
                return true;
            } catch (RuntimeException ex) {
                planned.fail(CatalogImportService.describe(ex));
                refused.add(planned.name());
                if (transactionAborted()) {
                    stoppedAt = planned.name();
                }
                return false;
            }
        }

        private boolean stopped(PlannedObject planned) {
            if (stoppedAt == null) {
                return false;
            }
            planned.fail("not validated: the validation pass stopped at '" + stoppedAt + "', whose failure ended its "
                    + "transaction; fix that object and preview again");
            refused.add(planned.name());
            return true;
        }

        /** Whether PostgreSQL itself refused a statement, which ends the transaction for every later one. */
        private boolean transactionAborted() {
            try {
                jdbc.queryForObject("SELECT 1", Integer.class);
                return false;
            } catch (RuntimeException ex) {
                log.debug("Catalog import validation pass stopped: its transaction is aborted", ex);
                return true;
            }
        }
    }
}
