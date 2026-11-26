package com.epam.aidial.analytics.integration;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /**
     * Whether Keeper has the fsync throttle {@code clickhouse/keeper-no-fsync-throttle.xml} lifts: 26.8 has it, while 25.3 and
     * 26.3 refuse the setting as unknown and fail to start. A version between them runs without the file, only slower; a
     * tag naming no version ({@code latest}) is taken as the newest.
     */
    static final boolean KEEPER_FSYNC_THROTTLE = atLeast(VERSION, 26, 8);

    /** The classpath resource that lifts Keeper's fsync throttle, merged only when {@link #KEEPER_FSYNC_THROTTLE}. */
    static final String NO_FSYNC_THROTTLE = "clickhouse/keeper-no-fsync-throttle.xml";

    private ClickHouseTestVersion() {
    }

    static boolean atLeast(String version, int major, int minor) {
        final Matcher parts = Pattern.compile("^(\\d+)\\.(\\d+)").matcher(version);
        if (!parts.find()) {
            return true;
        }
        final int found = Integer.parseInt(parts.group(1));
        return found > major || found == major && Integer.parseInt(parts.group(2)) >= minor;
    }
}
