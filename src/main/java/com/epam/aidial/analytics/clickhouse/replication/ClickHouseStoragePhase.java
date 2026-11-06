package com.epam.aidial.analytics.clickhouse.replication;

import com.epam.aidial.analytics.clickhouse.ClickHouseMigrationRunner;
import com.epam.aidial.analytics.sync.IngestTtlReconciler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The ClickHouse storage phase, the first {@code ApplicationRunner}. It verifies and prepares the storage, then opens
 * the {@link StorageReadyGate}; until then no scheduled job runs, {@code /v1} answers {@code 503} and Flight SQL does
 * not listen. Liveness answers throughout, and readiness stays down until the runners finish.
 *
 * <ol>
 *   <li>the database-engine check ({@link DatabaseEngineCheck}; normally already run ahead of the PostgreSQL
 *       migrations);</li>
 *   <li>replica discovery, which fails startup when {@code clickhouse.host} reaches several replicas;</li>
 *   <li>the writer credential's {@code SYSTEM SYNC} grants ({@link ReplicaRouter#verifySyncGrants}), before any DDL;</li>
 *   <li>under the storage-phase lock ({@link StoragePhaseLock}), when {@code clickhouse.migrations.enabled}: the
 *       conversion of plain tables ({@link ReplicatedTableConversion}), the ClickHouse migrations with their
 *       replicated pre-creates ({@link ClickHouseMigrationRunner}) and the retention reconciliation
 *       ({@link IngestTtlReconciler});</li>
 *   <li>still under the lock, the table check ({@link ReplicatedTableCheck}), whether or not migrations ran.</li>
 * </ol>
 *
 * <p>When ClickHouse could not be reached with migrations disabled, the storage cannot be verified and the gate opens
 * unverified, as the service has always started without ClickHouse. See
 * {@code openspec/specs/clickhouse-replication/spec.md}.
 */
@Slf4j
@Component
// Ordered ahead of every other runner: SchemaValidator (1) and ProvenanceReconciler (2) need the provisioned schema.
@Order(0)
public class ClickHouseStoragePhase implements ApplicationRunner {

    private final DatabaseEngineCheck engineCheck;
    private final ClickHouseTopology topology;
    private final StoragePhaseLock lock;
    private final ReplicatedTableConversion conversion;
    private final ClickHouseMigrationRunner migrations;
    private final IngestTtlReconciler ttlReconciler;
    private final ReplicatedTableCheck tableCheck;
    private final StorageReadyGate gate;
    private final ReplicaRouter router;
    private final boolean migrationsEnabled;

    public ClickHouseStoragePhase(DatabaseEngineCheck engineCheck, ClickHouseTopology topology, StoragePhaseLock lock,
            ReplicatedTableConversion conversion, ClickHouseMigrationRunner migrations, IngestTtlReconciler ttlReconciler,
            ReplicatedTableCheck tableCheck, StorageReadyGate gate, ReplicaRouter router,
            @Value("${clickhouse.migrations.enabled:true}") boolean migrationsEnabled) {
        this.engineCheck = engineCheck;
        this.topology = topology;
        this.lock = lock;
        this.conversion = conversion;
        this.migrations = migrations;
        this.ttlReconciler = ttlReconciler;
        this.tableCheck = tableCheck;
        this.gate = gate;
        this.router = router;
        this.migrationsEnabled = migrationsEnabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (engineCheck.check() == DatabaseEngineCheck.Outcome.UNREACHABLE) {
            log.warn("ClickHouse storage not verified: ClickHouse was unreachable at startup");
            gate.open();
            topology.startRefreshing();
            return;
        }
        topology.startup();
        // Before any DDL: a deploy missing the grants fails here having changed nothing, rather than after converting.
        router.verifySyncGrants(ClickHouseMigrationRunner.HISTORY_TABLE);
        lock.run(() -> {
            Map<String, String> reasons = Map.of();
            if (migrationsEnabled) {
                conversion.convert();
                reasons = conversion.givenUp();
                migrations.migrate();
                ttlReconciler.reconcile();
            }
            tableCheck.check(reasons);
        });
        topology.startRefreshing();
        gate.open();
    }
}
