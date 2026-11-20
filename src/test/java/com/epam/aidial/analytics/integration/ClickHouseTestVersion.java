package com.epam.aidial.analytics.integration;

/**
 * The ClickHouse server version the integration tests run against, held apart from {@link ClickHouseContainerSupport}
 * so that reading it starts no container: the multi-replica tests read it for their own cluster, and initializing
 * {@code ClickHouseContainerSupport} would also boot its single server for the rest of the JVM.
 */
final class ClickHouseTestVersion {

    /**
     * {@code CLICKHOUSE_TEST_VERSION}, or 26.8, the newest verified. Set it to check the supported range (25.3 LTS and up)
     * without editing the suite.
     */
    static final String VERSION = System.getenv().getOrDefault("CLICKHOUSE_TEST_VERSION", "26.8");

    /** Whether Keeper has the fsync throttle {@code clickhouse/keeper-no-fsync-throttle.xml} lifts: from 26.x; 25.x refuses it. */
    static final boolean KEEPER_FSYNC_THROTTLE = !VERSION.startsWith("25.");

    /** The classpath resource that lifts Keeper's fsync throttle, merged only when {@link #KEEPER_FSYNC_THROTTLE}. */
    static final String NO_FSYNC_THROTTLE = "clickhouse/keeper-no-fsync-throttle.xml";

    private ClickHouseTestVersion() {
    }
}
