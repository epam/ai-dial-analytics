package com.epam.aidial.analytics.service.catalog.transfer;

import com.epam.aidial.analytics.clickhouse.DdlExecutor;
import com.epam.aidial.analytics.service.pipeline.PipelineService;
import com.epam.aidial.analytics.service.table.TableCatalogService;
import com.epam.aidial.analytics.service.table.TableSchemaCreationService;
import com.epam.aidial.analytics.web.dto.catalog.BundlePipeline;
import com.epam.aidial.analytics.web.dto.catalog.BundleTable;
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
import java.util.Set;
import java.util.stream.Stream;

/**
 * The validation pass both import calls share, so a preview and an import cannot disagree.
 *
 * <p>It runs the real services — table create, define-schema, pipeline create — in import order inside
 * one PostgreSQL transaction that is always rolled back, with ClickHouse DDL replaced by a no-op. Every check
 * those services make is therefore the check the import will meet, and nothing is written: no catalog row, no
 * ClickHouse table, no audit revision. Once every pipeline is created it arms each one the same way
 * {@code PATCH enabled=true} does, inside the same transaction, to report {@code armable}; arming only after all
 * are created means an enrich pipeline reading another one's target is checked against that pipeline, whichever
 * comes first. Existing objects the import keeps are not re-created, so later objects are validated against
 * them rather than against the bundle's version — under {@code FAIL_IF_EXISTS} too, so the preview of a
 * conflicting bundle still validates its new objects.
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

    CatalogImportValidator(PlatformTransactionManager transactionManager, TableCatalogService catalog,
            TableSchemaCreationService schemas, PipelineService pipelineService,
            TableMapper tableMapper, PipelineMapper pipelineMapper, JdbcTemplate jdbc) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.catalog = catalog;
        this.schemas = schemas;
        this.pipelineService = pipelineService;
        this.tableMapper = tableMapper;
        this.pipelineMapper = pipelineMapper;
        this.jdbc = jdbc;
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

        private Pass(ImportPlan plan) {
            this.plan = plan;
            // A conflict is not refused here: the existing object stays, and what needs it is validated against it.
            plan.all().filter(planned -> planned.failed() && !planned.isConflict())
                    .forEach(planned -> refused.add(planned.name()));
        }

        private void run() {
            for (final PlannedObject table : plan.tables()) {
                if (!table.creates() || stopped(table)) {
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
                if (!pipeline.creates() || stopped(pipeline)) {
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
                attempt(pipeline, () -> pipelineService.create(pipelineMapper.toRegistration(pipeline.pipeline())));
            }
            for (final PlannedObject pipeline : plan.pipelines()) {
                if (pipeline.failed()) {
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

        private void refuse(PlannedObject planned, String dependency) {
            planned.fail("depends on '" + dependency + "', which cannot be imported");
            refused.add(planned.name());
        }

        private void createTable(BundleTable table) {
            final String name = table.table().name();
            catalog.create(tableMapper.toCreateInput(table.table()));
            schemas.defineSchema(name, tableMapper.toDefineInput(table.schema()), DdlExecutor.NO_OP);
        }

        /** What {@code PATCH enabled=true} would say about the pipeline as the import leaves it. */
        private void arm(PlannedObject pipeline) {
            if (pipeline.existing() instanceof BundlePipeline existing && existing.exportedEnabled()) {
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
