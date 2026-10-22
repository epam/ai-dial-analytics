package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.exception.RevisionNotFoundException;
import com.epam.aidial.analytics.service.audit.ActivityResourceType;
import com.epam.aidial.analytics.service.audit.ActivityType;
import com.epam.aidial.analytics.service.audit.AuditActivityEntity;
import com.epam.aidial.analytics.service.audit.AuditActivityRepository;
import com.epam.aidial.analytics.service.audit.AuditPipelineSnapshotService;
import com.epam.aidial.analytics.service.audit.AuditSnapshotService;
import com.epam.aidial.analytics.service.enrichment.model.MemberSelect;
import com.epam.aidial.analytics.service.enrichment.model.ReadyWhen;
import com.epam.aidial.analytics.service.enrichment.model.TriggerKind;
import com.epam.aidial.analytics.service.otel.OtelSinkEntity;
import com.epam.aidial.analytics.service.otel.OtelSinkRepository;
import com.epam.aidial.analytics.service.pipeline.PipelineEntity;
import com.epam.aidial.analytics.service.pipeline.PipelineKind;
import com.epam.aidial.analytics.service.pipeline.PipelineRepository;
import com.epam.aidial.analytics.service.query.saved.SavedQueryEntity;
import com.epam.aidial.analytics.service.query.saved.SavedQueryRepository;
import com.epam.aidial.analytics.service.query.saved.SavedQueryResultView;
import com.epam.aidial.analytics.service.query.saved.SavedQueryScope;
import com.epam.aidial.analytics.service.table.ColumnMappingEntity;
import com.epam.aidial.analytics.service.table.ColumnMappingRepository;
import com.epam.aidial.analytics.service.table.PipelineGenerationBump;
import com.epam.aidial.analytics.service.table.PipelineTargetCascade;
import com.epam.aidial.analytics.service.table.TableAccess;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import com.epam.aidial.analytics.service.table.TableStatus;
import com.epam.aidial.analytics.service.table.TableType;
import com.epam.aidial.analytics.service.table.TableWriteMode;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The audit trail against real PostgreSQL running the real migrations — the test the whole design is
 * sized to.
 *
 * <p>Its reason for existing is that {@code spring.jpa.hibernate.ddl-auto} is {@code none}: nothing
 * validates the hand-written {@code *_aud} schema against what Envers expects, so a mismatch surfaces
 * not at startup but as a caller's mutation failing at commit — auditing breaking the thing it audits.
 * Every audited entity is therefore created, updated <em>and deleted</em> here; the delete leg is the
 * one that exercises the widest set of audit columns, and the one that would fail first if the mirror
 * disagreed with what Envers writes.
 *
 * <p>Works through the repositories rather than the HTTP API on purpose: this is about the persistence
 * wiring (Envers, the revision listener, the migration), and the API-level grain is covered separately
 * by {@code AuditActivityFeedIntegrationTest}. Requires a running Docker daemon.
 */
class AuditTrailIntegrationTest extends AbstractPostgresIntegrationTest {

    /**
     * Each test gets its own table name, because an audit trail is precisely the thing that outlives the
     * rows it describes: deleting the live row in cleanup does not remove its history — it <em>adds</em> a
     * delete row — so a shared name would let every test read its predecessors' trail.
     */
    private static final AtomicInteger NAMES = new AtomicInteger();

    private String table;

    /**
     * A second, ENRICHMENT-typed table (source = {@link #table}), the target of the ENRICH-kind pipeline
     * tests. Both kinds are exercised, deliberately: they store disjoint column sets on one row, so a
     * {@code pipeline_aud} that mirrors only the aggregate half passes every AGGREGATE test and fails at
     * the first ENRICH write at runtime.
     */
    private String enrichment;

    /** A third, SOURCE-typed table, the target of the AGGREGATE-kind pipeline tests. */
    private String pipelineTarget;

    @Autowired
    private TableDefinitionRepository definitions;
    @Autowired
    private ColumnMappingRepository columns;
    @Autowired
    private AuditActivityRepository activities;
    @Autowired
    private PipelineRepository pipelines;
    @Autowired
    private PipelineTargetCascade pipelineTargetCascade;
    @Autowired
    private PipelineGenerationBump pipelineGenerationBump;
    @Autowired
    private OtelSinkRepository otelSinks;
    @Autowired
    private SavedQueryRepository savedQueries;
    @Autowired
    private TransactionTemplate transactions;
    @Autowired
    private AuditSnapshotService snapshots;
    @Autowired
    private AuditPipelineSnapshotService pipelineSnapshots;
    @Autowired
    private JdbcTemplate jdbc;

    private final List<String> createdSavedQueries = new ArrayList<>();
    private final List<String> createdPipelines = new ArrayList<>();
    private final List<String> createdOtelSinks = new ArrayList<>();

    @BeforeEach
    void nameThisTestsTable() {
        final int n = NAMES.incrementAndGet();
        table = "audit_probe_" + n;
        enrichment = "audit_probe_enr_" + n;
        pipelineTarget = "audit_probe_pt_" + n;
    }

    @AfterEach
    void cleanUp() {
        transactions.executeWithoutResult(status -> {
            // Every pipeline this test created goes through the repository, before either of its tables
            // is deleted: input_table has no cascade at all, and target's cascade would remove the
            // pipeline silently, outside the audit trail — exactly the gap TableCatalogService closes,
            // so a raw cleanup here must not reintroduce it for a row the test itself left behind.
            createdPipelines.forEach(name -> pipelines.findById(name).ifPresent(pipelines::delete));
            columns.deleteByTableName(enrichment);
            // The enrichment's source_table FK (RESTRICT, no cascade) must be cleared before the source
            // table itself can be deleted.
            definitions.findById(enrichment).ifPresent(definitions::delete);
            columns.deleteByTableName(pipelineTarget);
            definitions.findById(pipelineTarget).ifPresent(definitions::delete);
            columns.deleteByTableName(table);
            definitions.findById(table).ifPresent(definitions::delete);
            // find-then-delete rather than deleteById everywhere in this method: several tests delete
            // their own row before the test body ends (the pipeline delete legs), and
            // deleteById throws EmptyResultDataAccessException on an id that is already gone.
            createdSavedQueries.forEach(id -> savedQueries.findById(id).ifPresent(savedQueries::delete));
            createdOtelSinks.forEach(name -> otelSinks.findById(name).ifPresent(otelSinks::delete));
        });
    }

    @Test
    void recordsCreateUpdateAndDeleteOfTableDefinition() {
        transactions.executeWithoutResult(status -> definitions.save(definition()));
        transactions.executeWithoutResult(status -> {
            final TableDefinitionEntity entity = definitions.findById(table).orElseThrow();
            entity.setDescription("changed");
            definitions.save(entity);
        });
        // The delete leg is the point: it is the write that would fail first if the mirror's columns
        // disagreed with what Envers writes, and it fails at commit rather than at startup.
        transactions.executeWithoutResult(status -> definitions.deleteById(table));

        assertThat(auditRevisionTypes("table_definition_aud", "name", table))
                .containsExactly(0, 1, 2);
    }

    @Test
    void deletedTableFinalStateIsPreservedRatherThanNulled() {
        transactions.executeWithoutResult(status -> {
            final TableDefinitionEntity entity = definition();
            entity.setDescription("the last thing it said");
            entity.setOrderingKey(new ArrayList<>(List.of("probe_id")));
            definitions.save(entity);
        });
        transactions.executeWithoutResult(status -> definitions.deleteById(table));

        final Map<String, Object> deleteRow = jdbc.queryForMap(
                "SELECT description, ordering_key, access, write_mode, system FROM table_definition_aud "
                        + "WHERE name = ? AND revtype = 2", table);
        assertThat(deleteRow.get("description")).isEqualTo("the last thing it said");
        assertThat(deleteRow.get("write_mode")).isEqualTo("APPEND");
        assertThat(deleteRow.get("access")).isNotNull();
        // The array and boolean columns are the ones whose live NOT NULL/DEFAULT would have been easy to
        // copy into the mirror; asserting them here is what pins that they were not.
        assertThat(deleteRow.get("ordering_key")).isNotNull();
        assertThat(deleteRow.get("system")).isEqualTo(false);
    }

    @Test
    void recordsCreateUpdateAndDeleteOfColumn() {
        transactions.executeWithoutResult(status -> {
            definitions.save(definition());
            columns.save(column("probe_id", 0));
        });
        transactions.executeWithoutResult(status -> {
            final ColumnMappingEntity column = columns.findByTableNameOrderByOrdinal(table).getFirst();
            column.setSensitive(true);
            columns.save(column);
        });
        transactions.executeWithoutResult(status -> columns.deleteByTableName(table));

        assertThat(auditRevisionTypes("column_mapping_aud", "table_name", table))
                .containsExactly(0, 1, 2);
    }

    @Test
    void columnAuditRowCarriesTheLiveIdColumnNames() {
        transactions.executeWithoutResult(status -> {
            definitions.save(definition());
            columns.save(column("probe_id", 0));
        });

        // Envers' `originalId` is a mapping-level component name that never reaches SQL. If it did, this
        // query would fail on an unknown column rather than return a row.
        final Map<String, Object> row = jdbc.queryForMap(
                "SELECT table_name, source_name, sensitive FROM column_mapping_aud WHERE table_name = ?", table);
        assertThat(row.get("source_name")).isEqualTo("probe_id");
        assertThat(row.get("sensitive")).isEqualTo(false);
    }

    @Test
    void changesInOneTransactionShareOneRevision() {
        transactions.executeWithoutResult(status -> {
            definitions.save(definition());
            columns.save(column("probe_id", 0));
            columns.save(column("score", 1));
        });

        final List<AuditActivityEntity> recorded = activitiesFor(table);
        assertThat(recorded).hasSize(3);
        assertThat(recorded).extracting(AuditActivityEntity::getRevisionNumber).containsOnly(
                recorded.getFirst().getRevisionNumber());
    }

    @Test
    void columnActivitiesPointAtTheirTablesActivity() {
        transactions.executeWithoutResult(status -> {
            definitions.save(definition());
            columns.save(column("probe_id", 0));
        });

        final List<AuditActivityEntity> recorded = activitiesFor(table);
        final UUID tableActivity = recorded.stream()
                .filter(activity -> activity.getResourceType() == ActivityResourceType.Table)
                .findFirst().orElseThrow().getActivityId();
        assertThat(recorded).filteredOn(activity -> activity.getResourceType() == ActivityResourceType.TableColumn)
                .allSatisfy(child -> assertThat(child.getParentActivityId()).isEqualTo(tableActivity));
    }

    @Test
    void anUnauthenticatedChangeIsAttributedToUnknown() {
        transactions.executeWithoutResult(status -> definitions.save(definition()));

        // The whole suite runs in `none` security mode, so there is never an authentication to read —
        // which is exactly the case that must record `unknown` rather than a fabricated identity.
        assertThat(activitiesFor(table)).allSatisfy(activity -> {
            assertThat(activity.getInitiatedAuthor()).isEqualTo("unknown");
            assertThat(activity.getInitiatedEmail()).isNull();
        });
    }

    @Test
    void rolledBackTransactionLeavesNoTrace() {
        final long revisionsBefore = revisionCount();

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            definitions.save(definition());
            columns.save(column("probe_id", 0));
            throw new IllegalStateException("deliberate rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(revisionCount()).isEqualTo(revisionsBefore);
        assertThat(activitiesFor(table)).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM table_definition_aud WHERE name = ?", Long.class, table)).isZero();
    }

    @Test
    void reSavingIdenticalColumnValuesRecordsNothing() {
        transactions.executeWithoutResult(status -> {
            definitions.save(definition());
            columns.save(column("probe_id", 0));
        });
        transactions.executeWithoutResult(status -> {
            final ColumnMappingEntity stored = columns.findByTableNameOrderByOrdinal(table).getFirst();
            stored.setSensitive(stored.isSensitive());
            columns.save(stored);
        });

        // Hibernate's dirty checking issues no UPDATE for an unchanged row, so Envers never sees it. This
        // is why the feed's answer to "what changed" cannot be inflated by how a request is written.
        assertThat(auditRevisionTypes("column_mapping_aud", "table_name", table)).containsExactly(0);
    }

    @Test
    void loadingPipelineInReadWriteTransactionRecordsNoPhantomUpdate() {
        // Guards against the Hibernate JSON dirty-checking phantom: Hibernate snapshots a JSON-mapped
        // field by deep-copying it at load and comparing the copy at flush with equals(), so without
        // @EqualsAndHashCode on the value class the copy is never equal to the live instance. Any
        // read-write transaction that merely LOADS the entity is then flagged dirty and fires a spurious
        // Envers Update. An ENRICH row is the one that carries them — ready_when and member_select are
        // JSON-mapped objects on the entity, where an AGGREGATE row's declaration is a raw JSON string
        // (which compares by value for free), so this is the case that would actually break.
        transactions.executeWithoutResult(status -> {
            definitions.save(definition());
            definitions.save(enrichmentDefinition());
        });
        transactions.executeWithoutResult(status -> pipelines.save(enrichPipeline()));

        // The same pattern as a governance gate running inside PipelineService.create's @Transactional.
        transactions.executeWithoutResult(status -> pipelines.findById(enrichPipelineName()));

        assertThat(activities.findAll().stream()
                .filter(activity -> activity.getResourceType() == ActivityResourceType.Pipeline
                        && activity.getResourceId().equals(enrichPipelineName())))
                .as("pipeline must have only its Create activity — no phantom Update from a bare load")
                .hasSize(1)
                .allSatisfy(activity -> assertThat(activity.getActivityType()).isEqualTo(ActivityType.Create));
    }

    @Test
    void deletingAndReinsertingOneColumnInOneTransactionCollapses() {
        transactions.executeWithoutResult(status -> {
            definitions.save(definition());
            columns.save(column("probe_id", 0));
        });
        // Exactly what CatalogSchemaWriter.replaceColumns does when a schema is re-posted: delete every
        // column, flush, insert them again — same ids, one transaction.
        transactions.executeWithoutResult(status -> {
            columns.deleteByTableName(table);
            columns.flush();
            final ColumnMappingEntity reinserted = column("probe_id", 0);
            reinserted.setDescription("now with a description");
            columns.save(reinserted);
        });

        // Envers merges the delete and the insert of one id within a revision (DelWorkUnit.merge), so this
        // writes a single MOD row rather than colliding on the (rev, table_name, source_name) primary key.
        // The design depends on that behaviour instead of reconciling in the writer, so it is pinned here:
        // if a future Hibernate stopped merging, this test fails rather than production.
        assertThat(auditRevisionTypes("column_mapping_aud", "table_name", table)).containsExactly(0, 1);
        assertThat(jdbc.queryForObject(
                "SELECT description FROM column_mapping_aud WHERE table_name = ? AND revtype = 1",
                String.class, table)).isEqualTo("now with a description");
    }

    @Test
    void reinsertingAnUnchangedColumnStillRecordsAnUpdate() {
        transactions.executeWithoutResult(status -> {
            definitions.save(definition());
            columns.save(column("probe_id", 0));
        });
        transactions.executeWithoutResult(status -> {
            columns.deleteByTableName(table);
            columns.flush();
            columns.save(column("probe_id", 0));
        });

        // Documents a limit rather than a guarantee, and the assertion is deliberately the pessimistic
        // one. Envers' merge collapses the pair into a single MOD, which is what keeps the primary key
        // safe — but it does NOT compare equal to "nothing happened" here, so a schema re-post reports
        // every column it re-states, not only the ones that differ. That over-reporting is accepted:
        // it is confined to re-posting a not-yet-materialized table's schema, it names real rewrites
        // rather than inventing them, and the alternative (reconciling row-by-row in the writer) breaks
        // the case where two columns swap exposed names. See design.md.
        assertThat(auditRevisionTypes("column_mapping_aud", "table_name", table)).containsExactly(0, 1);
    }

    @Test
    void tablePredatingTheTrailHasNoReconstructableState() {
        // A table written straight to the database, as one that existed before this feature deployed
        // would have been: no audit rows at all. Then it is touched, which audits the definition row but
        // NOT its columns, since nothing changed them.
        jdbc.update("INSERT INTO table_definition (name, type, status, write_mode, system, access, ordering_key) "
                + "VALUES (?, 'SOURCE', 'ACTIVE', 'APPEND', false, '{\"write\":[],\"modify\":[]}'::jsonb, '{legacy_id}')",
                table);
        jdbc.update("INSERT INTO column_mapping (table_name, source_name, name, type, nullable, ordinal, "
                + "sensitive, heavy) VALUES (?, 'legacy_id', 'legacy_id', 'string', false, 0, false, false)", table);
        transactions.executeWithoutResult(status -> {
            final TableDefinitionEntity entity = definitions.findById(table).orElseThrow();
            entity.setDescription("touched after the upgrade");
            definitions.save(entity);
        });

        final int revision = jdbc.queryForObject(
                "SELECT max(rev) FROM table_definition_aud WHERE name = ?", Integer.class, table);

        // Without the creation check this answers 200 with an EMPTY column list beside an orderingKey
        // that names a column — a self-contradicting reply that reads as "every column was dropped".
        assertThatThrownBy(() -> snapshots.snapshot(table, revision))
                .isInstanceOf(RevisionNotFoundException.class);
    }

    @Test
    void entitiesOutsideTheAuditedSurfaceProduceNoRevision() {
        final long revisionsBefore = revisionCount();

        transactions.executeWithoutResult(status -> {
            otelSinks.save(otelSink());
        });

        // The coverage boundary asserted rather than assumed: annotating one of these @Audited later
        // without extending AuditResourceMapper would write audit rows nothing in the feed explains.
        assertThat(revisionCount()).isEqualTo(revisionsBefore);
    }

    /**
     * The ENRICH half of one merged registry, mirrored end to end. Its reason for existing separately
     * from the AGGREGATE test below is the {@code pipeline_aud} widening {@code V36} had to add: the six
     * control-plane columns the fold gave {@code pipeline} are written only by an ENRICH row, so a mirror
     * missing any of them passes every aggregate test and fails at the first enrich write.
     */
    @Test
    void recordsCreateUpdateAndDeleteOfEnrichPipeline() {
        // The table/enrichment setup deliberately runs in its own transaction, separate from
        // the pipeline's creation: a real PipelineController.create() is its own transaction, never
        // sharing one with the table it happens to derive its source from. Creating both in one
        // transaction here would put the pipeline's Create activity in the same revision as the source
        // table's Create, which would then — correctly, per the "Bulk generation bump" parent-linking
        // rule — link the pipeline to that table, contradicting this test's "no parent" assertion for
        // what is actually a direct, unrelated pipeline mutation.
        transactions.executeWithoutResult(status -> {
            definitions.save(definition());
            definitions.save(enrichmentDefinition());
        });
        transactions.executeWithoutResult(status -> pipelines.save(enrichPipeline()));
        transactions.executeWithoutResult(status -> {
            final PipelineEntity entity = pipelines.findById(enrichPipelineName()).orElseThrow();
            entity.setEnabled(false);
            pipelines.save(entity);
        });
        transactions.executeWithoutResult(status -> pipelines.deleteById(enrichPipelineName()));

        assertThat(auditRevisionTypes("pipeline_aud", "name", enrichPipelineName()))
                .containsExactly(0, 1, 2);
        assertThat(activities.findAll().stream()
                .filter(activity -> activity.getResourceType() == ActivityResourceType.Pipeline
                        && activity.getResourceId().equals(enrichPipelineName())))
                .allSatisfy(activity -> assertThat(activity.getParentActivityId()).isNull());
    }

    /**
     * A revision recorded before {@code V44} retired {@code on_ingest} keeps its {@code ON_INGEST}, and still
     * loads: {@code TriggerKindConverter} reads it as {@code SCHEDULE}. Without it, Envers would fail to map the
     * value onto the enum and the revision endpoint would answer 500 for any pipeline's pre-migration history.
     */
    @Test
    void preRetirementOnIngestRevisionStillLoadsAsSchedule() {
        transactions.executeWithoutResult(status -> {
            definitions.save(definition());
            definitions.save(enrichmentDefinition());
        });
        transactions.executeWithoutResult(status -> pipelines.save(enrichPipeline()));
        final int revision = jdbc.queryForObject("SELECT max(rev) FROM pipeline_aud WHERE name = ?",
                Integer.class, enrichPipelineName());
        // The shape V44 leaves in history: no audit row is rewritten, so the retired value stays as written.
        jdbc.update("UPDATE pipeline_aud SET trigger_kind = 'ON_INGEST', trigger_group_by = NULL"
                + " WHERE name = ? AND rev = ?", enrichPipelineName(), revision);

        final PipelineEntity snapshot = pipelineSnapshots.snapshot(enrichPipelineName(), revision);

        assertThat(snapshot.getTriggerKind()).isEqualTo(TriggerKind.SCHEDULE);
        assertThat(jdbc.queryForObject("SELECT trigger_kind FROM pipeline_aud WHERE name = ? AND rev = ?",
                String.class, enrichPipelineName(), revision)).isEqualTo("ON_INGEST");
    }

    /**
     * The delete row keeps the enrich-only columns rather than nulling them — the assertion that names
     * the {@code V36} additions one by one, so a column left out of the mirror fails here by name
     * instead of as an opaque "column does not exist" at commit.
     *
     * <p>{@code spec} is the member that matters most now: the transform lives there, so a delete
     * revision carries the declaration — the prompt included — that the pipeline was running when it was
     * removed. The evaluator registry never did, which is why the fold needed no new {@code pipeline_aud}
     * column at all: every folded member lands in a column the mirror already had.
     */
    @Test
    void deletedEnrichPipelineFinalStateIsPreservedRatherThanNulled() {
        transactions.executeWithoutResult(status -> {
            definitions.save(definition());
            definitions.save(enrichmentDefinition());
        });
        transactions.executeWithoutResult(status -> pipelines.save(enrichPipeline()));
        transactions.executeWithoutResult(status -> pipelines.deleteById(enrichPipelineName()));

        final Map<String, Object> deleteRow = jdbc.queryForMap(
                "SELECT kind, trigger_kind, trigger_group_by, ready_when, member_select, spec FROM pipeline_aud "
                        + "WHERE name = ? AND revtype = 2",
                enrichPipelineName());
        assertThat(deleteRow.get("kind")).isEqualTo(PipelineKind.ENRICH.name());
        assertThat(deleteRow.get("trigger_kind")).isEqualTo(TriggerKind.GROUP.name());
        assertThat(deleteRow.get("trigger_group_by")).isEqualTo("probe_id");
        assertThat(deleteRow.get("ready_when")).isNotNull();
        assertThat(deleteRow.get("member_select")).isNotNull();
        // Whitespace-insensitive: the column is `jsonb`, so PostgreSQL returns it re-rendered with its
        // own spacing rather than the bytes that were written.
        assertThat(deleteRow.get("spec").toString().replace(" ", ""))
                .as("the transform is mirrored, so a revision carries the prompt that produced a row")
                .contains("\"model\":\"gpt-4o\"")
                .contains("request_template");
    }

    @Test
    void deletingEnrichmentTargetOfPipelineRecordsThePipeline() {
        transactions.executeWithoutResult(status -> {
            definitions.save(definition());
            definitions.save(enrichmentDefinition());
            pipelines.save(enrichPipeline());
        });

        // Exactly what TableCatalogService.delete() does for a table that is a pipeline's target: the
        // dependent pipeline is removed through the registry's own repository, in the same transaction as
        // the table, before the table itself is deleted — so both removals land in one revision instead
        // of the pipeline vanishing to pipeline.target's untracked ON DELETE CASCADE. One cascade covers
        // both kinds since the fold; this is its enrichment-target case.
        transactions.executeWithoutResult(status -> {
            pipelineTargetCascade.deletePipelineTargetingTable(enrichment);
            columns.deleteByTableName(enrichment);
            columns.flush();
            definitions.deleteById(enrichment);
        });

        // ADD then DEL, with no MOD step: revtype 0 then 2.
        assertThat(auditRevisionTypes("pipeline_aud", "name", enrichPipelineName())).containsExactly(0, 2);
        assertThat(auditRevisionTypes("table_definition_aud", "name", enrichment)).containsExactly(0, 2);
        final AuditActivityEntity pipelineDelete = activities.findAll().stream()
                .filter(activity -> activity.getResourceType() == ActivityResourceType.Pipeline
                        && activity.getResourceId().equals(enrichPipelineName()))
                .filter(activity -> activity.getActivityType() == ActivityType.Delete)
                .findFirst().orElseThrow();
        final AuditActivityEntity enrichmentDelete = activitiesFor(enrichment).stream()
                .filter(activity -> activity.getResourceType() == ActivityResourceType.Table)
                .filter(activity -> activity.getActivityType() == ActivityType.Delete)
                .findFirst().orElseThrow();
        assertThat(pipelineDelete.getRevisionNumber()).isEqualTo(enrichmentDelete.getRevisionNumber());
    }

    @Test
    void bulkGenerationBumpRecordsOneUpdatePerAffectedPipelineParentedToItsSource() {
        transactions.executeWithoutResult(status -> {
            definitions.save(definition());
            definitions.save(enrichmentDefinition());
            pipelines.save(enrichPipeline());
        });

        // Mirrors what CatalogSchemaWriter.applyPatch does in one transaction: repoint the source's
        // version_column, then bump every ENRICH pipeline reading it — sharing that same revision. The
        // pipeline declares no input_table of its own, so it derives the source through its target
        // enrichment, which is the half of the bump whose parent cannot be read off the resource id.
        transactions.executeWithoutResult(status -> {
            final TableDefinitionEntity source = definitions.findById(table).orElseThrow();
            source.setVersionColumn("v");
            definitions.save(source);
            pipelineGenerationBump.onVersionColumnRepointed(table);
        });

        final AuditActivityEntity sourceActivity = activitiesFor(table).stream()
                .filter(activity -> activity.getResourceType() == ActivityResourceType.Table)
                .filter(activity -> activity.getActivityType() == ActivityType.Update)
                .findFirst().orElseThrow();
        final AuditActivityEntity pipelineActivity = activities.findAll().stream()
                .filter(activity -> activity.getResourceType() == ActivityResourceType.Pipeline
                        && activity.getResourceId().equals(enrichPipelineName()))
                .filter(activity -> activity.getActivityType() == ActivityType.Update)
                .findFirst().orElseThrow();
        assertThat(pipelineActivity.getRevisionNumber()).isEqualTo(sourceActivity.getRevisionNumber());
        assertThat(pipelineActivity.getParentActivityId()).isEqualTo(sourceActivity.getActivityId());
    }

    @Test
    void recordsCreateUpdateAndDeleteOfAggregatePipeline() {
        // The table setup runs in its own transaction, separate from the pipeline's own mutations, for
        // the reason spelled out on the ENRICH case above: a real PipelineController.create() never
        // shares a transaction with the tables it happens to name.
        transactions.executeWithoutResult(status -> {
            definitions.save(definition());
            definitions.save(pipelineTargetDefinition());
        });
        transactions.executeWithoutResult(status -> pipelines.save(pipeline()));
        transactions.executeWithoutResult(status -> {
            final PipelineEntity entity = pipelines.findById(pipelineName()).orElseThrow();
            entity.setEnabled(true);
            pipelines.save(entity);
        });
        transactions.executeWithoutResult(status -> pipelines.deleteById(pipelineName()));

        assertThat(auditRevisionTypes("pipeline_aud", "name", pipelineName())).containsExactly(0, 1, 2);
        assertThat(activities.findAll().stream()
                .filter(activity -> activity.getResourceType() == ActivityResourceType.Pipeline
                        && activity.getResourceId().equals(pipelineName())))
                .allSatisfy(activity -> assertThat(activity.getParentActivityId()).isNull());
    }

    @Test
    void deletingSourceTargetOfAggregatePipelineRecordsThePipeline() {
        transactions.executeWithoutResult(status -> {
            definitions.save(definition());
            definitions.save(pipelineTargetDefinition());
            pipelines.save(pipeline());
        });

        // The source-target case of the same one cascade the enrichment-target test above covers.
        transactions.executeWithoutResult(status -> {
            pipelineTargetCascade.deletePipelineTargetingTable(pipelineTarget);
            columns.deleteByTableName(pipelineTarget);
            columns.flush();
            definitions.deleteById(pipelineTarget);
        });

        // ADD then DEL, with no MOD step: revtype 0 then 2.
        assertThat(auditRevisionTypes("pipeline_aud", "name", pipelineName())).containsExactly(0, 2);
        assertThat(auditRevisionTypes("table_definition_aud", "name", pipelineTarget)).containsExactly(0, 2);
        final AuditActivityEntity pipelineDelete = activities.findAll().stream()
                .filter(activity -> activity.getResourceType() == ActivityResourceType.Pipeline
                        && activity.getResourceId().equals(pipelineName()))
                .filter(activity -> activity.getActivityType() == ActivityType.Delete)
                .findFirst().orElseThrow();
        final AuditActivityEntity targetDelete = activitiesFor(pipelineTarget).stream()
                .filter(activity -> activity.getResourceType() == ActivityResourceType.Table)
                .filter(activity -> activity.getActivityType() == ActivityType.Delete)
                .findFirst().orElseThrow();
        assertThat(pipelineDelete.getRevisionNumber()).isEqualTo(targetDelete.getRevisionNumber());
    }

    @Test
    void recordsCreateUpdateAndDeleteOfSavedQuery() {
        final SavedQueryEntity created = savedQuery();
        transactions.executeWithoutResult(status -> savedQueries.save(created));
        transactions.executeWithoutResult(status -> {
            final SavedQueryEntity entity = savedQueries.findById(created.getId()).orElseThrow();
            entity.setDescription("now with a description");
            savedQueries.save(entity);
        });
        transactions.executeWithoutResult(status -> savedQueries.deleteById(created.getId()));

        assertThat(auditRevisionTypes("saved_query_aud", "id", created.getId())).containsExactly(0, 1, 2);
        assertThat(activities.findAll().stream()
                .filter(activity -> activity.getResourceType() == ActivityResourceType.SavedQuery
                        && activity.getResourceId().equals(created.getId())))
                .allSatisfy(activity -> assertThat(activity.getParentActivityId()).isNull());
    }

    private TableDefinitionEntity definition() {
        final TableDefinitionEntity entity = new TableDefinitionEntity();
        entity.setName(table);
        entity.setType(TableType.SOURCE);
        entity.setStatus(TableStatus.PENDING);
        entity.setWriteMode(TableWriteMode.APPEND);
        entity.setAccess(new TableAccess());
        return entity;
    }

    private ColumnMappingEntity column(String sourceName, int ordinal) {
        final ColumnMappingEntity column = new ColumnMappingEntity();
        column.setTableName(table);
        column.setSourceName(sourceName);
        column.setName(sourceName);
        column.setType(QueryFieldType.STRING);
        column.setOrdinal(ordinal);
        return column;
    }

    private TableDefinitionEntity enrichmentDefinition() {
        final TableDefinitionEntity entity = new TableDefinitionEntity();
        entity.setName(enrichment);
        entity.setType(TableType.ENRICHMENT);
        entity.setSourceTable(table);
        entity.setStatus(TableStatus.PENDING);
        entity.setWriteMode(TableWriteMode.APPEND);
        entity.setAccess(new TableAccess());
        return entity;
    }

    private String enrichPipelineName() {
        return "enrich_" + table;
    }

    /**
     * An ENRICH row on a GROUP trigger, which is the shape that populates every enrich-only column at
     * once: the trigger's group key, both JSON-mapped policy objects, and the transform in {@code spec}. A
     * {@code SCHEDULE} row would leave two of them null and prove less about the
     * mirror. It declares no {@code input_table}, so it also stands in for the derive-through-target
     * half of the generation bump.
     */
    private PipelineEntity enrichPipeline() {
        final PipelineEntity entity = new PipelineEntity();
        entity.setName(enrichPipelineName());
        entity.setKind(PipelineKind.ENRICH);
        entity.setTarget(enrichment);
        // The transform is part of the spec now, so a Pipeline snapshot carries the declaration — including
        // the prompt — that produced a row, which the evaluator registry never did.
        entity.setSpec("{\"transform\":{\"type\":\"llm\",\"model\":\"gpt-4o\","
                + "\"request_template\":\"{{text}}\",\"input_bindings\":[],"
                + "\"outputs\":[{\"name\":\"verdict\",\"prose\":\"Whether the text is toxic.\"}]},"
                + "\"output_bindings\":[]}");
        entity.setTriggerKind(TriggerKind.GROUP);
        entity.setTriggerGroupBy("probe_id");
        final ReadyWhen readyWhen = new ReadyWhen();
        readyWhen.setIdle("PT10M");
        entity.setReadyWhen(readyWhen);
        final MemberSelect memberSelect = new MemberSelect();
        memberSelect.setLimit(50);
        entity.setMemberSelect(memberSelect);
        entity.setEnabled(true);
        entity.setHasMore(false);
        entity.setRebuiltAt(Instant.now());
        entity.setGeneration(0);
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        createdPipelines.add(entity.getName());
        return entity;
    }

    private TableDefinitionEntity pipelineTargetDefinition() {
        final TableDefinitionEntity entity = new TableDefinitionEntity();
        entity.setName(pipelineTarget);
        entity.setType(TableType.SOURCE);
        entity.setStatus(TableStatus.PENDING);
        entity.setWriteMode(TableWriteMode.UPSERT_BY_KEY);
        entity.setAccess(new TableAccess());
        return entity;
    }

    private String pipelineName() {
        return "pipeline_" + table;
    }

    private PipelineEntity pipeline() {
        final PipelineEntity entity = new PipelineEntity();
        entity.setName(pipelineName());
        entity.setKind(PipelineKind.AGGREGATE);
        entity.setTarget(pipelineTarget);
        entity.setInputTable(table);
        entity.setTriggerKind(TriggerKind.SCHEDULE);
        entity.setTriggerCron("0 0 * * * *");
        entity.setEnabled(false);
        entity.setHasMore(false);
        entity.setRebuiltAt(Instant.now());
        entity.setGeneration(0);
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        createdPipelines.add(entity.getName());
        return entity;
    }

    private OtelSinkEntity otelSink() {
        final OtelSinkEntity entity = new OtelSinkEntity();
        entity.setName("otel_sink_" + table);
        entity.setCreatedAt(Instant.now());
        createdOtelSinks.add(entity.getName());
        return entity;
    }

    private SavedQueryEntity savedQuery() {
        final SavedQueryEntity entity = new SavedQueryEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setName("audit probe query");
        entity.setScope(SavedQueryScope.PERSONAL);
        entity.setSource(List.of(table));
        entity.setSqlText("SELECT 1");
        entity.setResultView(SavedQueryResultView.TABLE);
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        createdSavedQueries.add(entity.getId());
        return entity;
    }

    /** The revision types written for one entity, oldest revision first: ADD=0, MOD=1, DEL=2. */
    private List<Integer> auditRevisionTypes(String auditTable, String idColumn, String id) {
        return jdbc.queryForList(
                "SELECT revtype FROM " + auditTable + " WHERE " + idColumn + " = ? ORDER BY rev",
                Integer.class, id);
    }

    private List<AuditActivityEntity> activitiesFor(String table) {
        return activities.findAll().stream()
                .filter(activity -> activity.getResourceId().equals(table)
                        || activity.getResourceId().startsWith(table + ":"))
                .toList();
    }

    private long revisionCount() {
        return jdbc.queryForObject("SELECT count(*) FROM revinfo", Long.class);
    }

    /** Guards against a silent change of the wire spelling, which the admin frontend compares exactly. */
    @Test
    void activityEnumsAreStoredWithTheirWireSpelling() {
        transactions.executeWithoutResult(status -> definitions.save(definition()));

        final Map<String, Object> row = jdbc.queryForMap(
                "SELECT activity_type, resource_type FROM audit_activity WHERE resource_id = ?", table);
        assertThat(row.get("activity_type")).isEqualTo(ActivityType.Create.name());
        assertThat(row.get("resource_type")).isEqualTo(ActivityResourceType.Table.name());
    }
}
