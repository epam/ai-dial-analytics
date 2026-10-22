package com.epam.aidial.analytics.service.enrichment.support;

import com.epam.aidial.analytics.clickhouse.ClickHouseDdlExecutor;
import com.epam.aidial.analytics.configuration.ProvenanceReconcileProperties;
import com.epam.aidial.analytics.service.pipeline.PipelineKind;
import com.epam.aidial.analytics.service.pipeline.PipelineRepository;
import com.epam.aidial.analytics.service.table.CatalogSchemaWriter;
import com.epam.aidial.analytics.service.table.ClickHouseTypeMapper;
import com.epam.aidial.analytics.service.table.ColumnInput;
import com.epam.aidial.analytics.service.table.ColumnMappingEntity;
import com.epam.aidial.analytics.service.table.ColumnMappingRepository;
import com.epam.aidial.analytics.service.table.TableDdlRenderer;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import com.epam.aidial.analytics.service.table.TableStatus;
import com.epam.aidial.analytics.service.table.TableType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Adds the {@code pipeline_generation} provenance column, at startup, to every {@code ACTIVE} enrichment
 * that an {@code ENRICH} pipeline targets — in ClickHouse and in the catalog, each half guarded
 * separately so that either one being absent is healed rather than only the pair.
 *
 * <p><b>Why anything needs to do this.</b> Provenance columns are injected into an enrichment's schema at
 * <em>creation</em> only — {@code POST /v1/tables/&#123;name&#125;/schema} is refused with 409 on a
 * materialized table, and {@code PATCH} deliberately does not inject — and the enrich write drops any
 * provenance column the target does not physically carry. So every enrichment that existed before
 * {@code pipeline_generation} was added lacks it, and without something closing that gap the effect of
 * replacing {@code evaluator_version} with it would be that lineage silently <em>stops</em> rather than
 * improves.
 *
 * <p><b>Why in code rather than as an operator step.</b> The alternative was one additive
 * {@code PATCH /v1/tables/&#123;target&#125;/schema} per live enrich target before the deploy: unbounded
 * (one call per target, per environment, from a list nobody maintains), ordered relative to the deploy,
 * and — the part that settles it — <em>less safe</em>. {@code TableSchemaService.patchSchema} issues the
 * ClickHouse {@code ALTER} and <em>then</em> writes the catalog, and its {@code ADD COLUMN} carries no
 * {@code IF NOT EXISTS}: if the catalog write fails after the {@code ALTER} lands, the two diverge and
 * retrying the {@code PATCH} cannot recover, because the duplicate check consults the catalog, passes,
 * re-issues the {@code ALTER} and ClickHouse rejects it. This reconciler emits
 * {@code ADD COLUMN IF NOT EXISTS} and re-runs every boot, so it is idempotent by construction and
 * self-heals a crash between the halves on the next start. DDL first, catalog second — the order
 * {@code IF NOT EXISTS} makes replayable.
 *
 * <p><b>Both directions of divergence, not one.</b> The {@code ALTER} is issued for every target on every
 * boot and only the <em>catalog</em> half is conditional. Gating the {@code ALTER} on the catalog too
 * would make the second divergence — a catalog row whose physical column is missing — permanent: the
 * write's best-effort drop tests catalog-derived columns, so it would keep sending a column ClickHouse
 * does not have and every write-back to that target would fail, while every later boot skipped the target
 * on the strength of the catalog row causing it. See {@link #reconcile(String)}.
 *
 * <p><b>Why the Flyway routes are rejected rather than merely unused.</b> The ClickHouse lane cannot reach
 * runtime-created tables and has said so since {@code V4__enrichment_updated_at_default.sql}, whose only
 * remedy is a hand-written instruction embedded in a checksummed migration. The Postgres lane is actively
 * unsafe: it can seed the {@code column_mapping} row but cannot issue the ClickHouse {@code ALTER},
 * producing a catalog row with no physical column — and because the write's best-effort drop tests
 * <em>catalog</em>-derived columns, that state makes every enrich write-back to that target fail.
 *
 * <p><b>Scoped to pipeline targets, not to every enrichment.</b> Two concrete reasons. A system-owned
 * enrichment ({@code dial_usage_log_payload}, seeded by V38) can never be patched through the API at all —
 * {@code requireModifiable} throws — so reconciling every enrichment would reach a table the API cannot,
 * which is a wider licence than this needs. And a non-target enrichment can never carry the stamp, so the
 * column would be dead weight. {@code PipelineEntity.target} is UNIQUE, so the target list is exact.
 *
 * <p><b>It bumps nothing</b>, and that makes it cheaper than the patch it replaces. The manual patch would
 * have fired {@code PipelineGenerationService.onTargetColumnsChanged}, which is {@code llm}-scoped and
 * would bump each {@code llm} pipeline's {@code generation} once — a runner re-sync for a column no output
 * binds to. A provenance column changes neither the composed {@code response_schema} nor the derived output
 * mapping, so there is nothing for a consumer to re-read, and the omission is deliberate rather than
 * forgotten.
 *
 * <p><b>It warns and continues; it never fails startup.</b> The degraded mode already exists and is
 * defined — the write's best-effort drop — so a ClickHouse hiccup costs lineage for one boot and is
 * retried on the next. Aborting instead would be actively worse: ADAS's liveness probe reads the
 * aggregate {@code /actuator/health}, so a boot-failing contributor restart-loops the service over a
 * lineage column. The accepted cost is that a reconciler which never succeeds logs every boot and stamps
 * nothing, which looks exactly like the pre-change behaviour; the log and the counter are the signal.
 *
 * <p>{@code @Order(2)}: after {@code ClickHouseMigrationRunner} ({@code @Order(0)}, which provisions the
 * schema) and {@code SchemaValidator} ({@code @Order(1)}, which reconciles the data-sync mapping against
 * the live table and issues no DDL). A third runner of the same shape belongs after both.
 */
@Component
@Slf4j
@Order(2)
public class ProvenanceReconciler implements ApplicationRunner {

    private static final String RECONCILED_COUNTER = "adas.enrichment.provenance.reconciled";
    private static final String FAILED_COUNTER = "adas.enrichment.provenance.reconcile_failed";

    private final PipelineRepository pipelines;
    private final TableDefinitionRepository definitions;
    private final ColumnMappingRepository columns;
    private final CatalogSchemaWriter catalog;
    private final TableDdlRenderer ddlRenderer;
    private final ClickHouseTypeMapper typeMapper;
    private final ClickHouseDdlExecutor ddl;
    private final ProvenanceReconcileProperties properties;
    private final Counter reconciled;
    private final Counter failed;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public ProvenanceReconciler(PipelineRepository pipelines, TableDefinitionRepository definitions,
            ColumnMappingRepository columns, CatalogSchemaWriter catalog, TableDdlRenderer ddlRenderer,
            ClickHouseTypeMapper typeMapper, ClickHouseDdlExecutor ddl,
            ProvenanceReconcileProperties properties, MeterRegistry meterRegistry) {
        this.pipelines = pipelines;
        this.definitions = definitions;
        this.columns = columns;
        this.catalog = catalog;
        this.ddlRenderer = ddlRenderer;
        this.typeMapper = typeMapper;
        this.ddl = ddl;
        this.properties = properties;
        this.reconciled = Counter.builder(RECONCILED_COUNTER)
                .description("Enrichment targets that gained the pipeline_generation provenance column")
                .register(meterRegistry);
        this.failed = Counter.builder(FAILED_COUNTER)
                .description("Enrichment targets whose provenance reconciliation failed and was skipped")
                .register(meterRegistry);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.enabled()) {
            log.info("Provenance reconciliation disabled (enrichment.provenance-reconcile.enabled=false);"
                    + " an enrich target missing '{}' will keep dropping that stamp",
                    ProvenanceColumns.PIPELINE_GENERATION);
            return;
        }
        final Set<String> targets = Set.copyOf(pipelines.findTargetsByKind(PipelineKind.ENRICH));
        if (targets.isEmpty()) {
            return;
        }
        int added = 0;
        for (final TableDefinitionEntity enrichment
                : definitions.findByTypeAndStatus(TableType.ENRICHMENT, TableStatus.ACTIVE)) {
            if (!targets.contains(enrichment.getName())) {
                continue;
            }
            if (reconcile(enrichment.getName())) {
                added++;
            }
        }
        if (added > 0) {
            log.info("Provenance reconciliation added '{}' to {} enrich target(s)",
                    ProvenanceColumns.PIPELINE_GENERATION, added);
        }
    }

    /** Whether the target's catalog already declares the column, under either spelling. */
    private boolean declaresGeneration(String target) {
        return columns.findByTableNameOrderByOrdinal(target).stream()
                .anyMatch(column -> ProvenanceColumns.PIPELINE_GENERATION.equals(column.getSourceName())
                        || ProvenanceColumns.PIPELINE_GENERATION.equals(column.getName()));
    }

    /**
     * Adds the column to one target: the conditional {@code ALTER} first, the catalog row second.
     *
     * <p><b>The {@code ALTER} is issued unconditionally, for every target, on every boot</b>, and the
     * catalog check decides only whether a row is appended. That asymmetry is the whole of the
     * both-directions guarantee, and it costs nothing: {@code ADD COLUMN IF NOT EXISTS} on a table that
     * already has the column is a metadata no-op ClickHouse answers without touching a part, and a
     * deployment typically has only a handful of targets.
     *
     * <p>Skipping the {@code ALTER} for a target whose catalog already declares the column would leave
     * the <em>other</em> divergence — a catalog row with no physical column — permanently unrecoverable.
     * Nothing else closes it: {@code TableDataService.physicalColumns} derives from
     * {@code column_mapping.source_name} rather than from {@code DESCRIBE TABLE}, so the enrich write's
     * best-effort {@code dropAbsentProvenance} believes the column exists and keeps sending it, and every
     * {@code /rows} write-back and Class-1 materialize to that target fails with a ClickHouse
     * unknown-column error — while every later boot skipped the target on the strength of the very
     * catalog row that causes it. That is exactly the state the design rejects the Postgres-only route
     * for producing, so the reconciler must not be able to produce it either.
     *
     * @return true when the target gained a catalog row it lacked, false when it already had one or the
     *     attempt was logged and skipped. The {@code ALTER} itself is not counted, because on the steady
     *     state it is a no-op every boot and counting it would report work that did not happen.
     */
    private boolean reconcile(String target) {
        final ColumnInput column = canonicalGenerationColumn();
        try {
            ddl.execute(ddlRenderer.renderAlter(target, List.of(ddlRenderer.addColumnIfNotExistsCommand(
                    new TableDdlRenderer.DdlColumn(column.sourceName(),
                            typeMapper.toDdlType(column.type(), null, null, true))))));
            // After the DDL, not before it: the catalog row is what makes the column visible to every
            // reader, so writing it while the physical column is still missing is the divergence above.
            if (declaresGeneration(target)) {
                return false;
            }
            catalog.appendColumn(target, column, true, nextOrdinal(target));
            reconciled.increment();
            return true;
        } catch (RuntimeException ex) {
            // Never fatal: the enrich write already drops a provenance column the target does not carry,
            // so the cost of a failure here is lineage for one boot, retried on the next. Failing startup
            // instead would restart-loop the service, because its liveness probe reads the aggregate health.
            failed.increment();
            log.warn("Could not add provenance column '{}' to enrich target '{}': {}. Rows written to it"
                    + " will carry no generation stamp until this succeeds on a later start.",
                    ProvenanceColumns.PIPELINE_GENERATION, target, ex.getMessage());
            return false;
        }
    }

    /**
     * The canonical declaration, taken from {@link ProvenanceColumns#canonicalColumns()} rather than
     * restated here — so the reconciled column is byte-for-byte the one creation-time injection writes,
     * including its type, tag and description. A second declaration of the same column is exactly the
     * drift this reads from one place to avoid.
     */
    private static ColumnInput canonicalGenerationColumn() {
        return ProvenanceColumns.canonicalColumns().stream()
                .filter(column -> ProvenanceColumns.PIPELINE_GENERATION.equals(column.name()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no canonical declaration for '"
                        + ProvenanceColumns.PIPELINE_GENERATION + "'"));
    }

    /** One past the target's highest ordinal, so the column appends rather than reorders anything. */
    private int nextOrdinal(String target) {
        return columns.findByTableNameOrderByOrdinal(target).stream()
                .mapToInt(ColumnMappingEntity::getOrdinal)
                .max()
                .orElse(-1) + 1;
    }
}
