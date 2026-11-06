package com.epam.aidial.analytics.clickhouse.replication;

import com.clickhouse.client.api.ServerException;
import com.epam.aidial.analytics.clickhouse.ClickHouseClientWrapper;
import com.epam.aidial.analytics.clickhouse.ClickHouseWriterClient;
import com.epam.aidial.analytics.configuration.ClickHouseProperties;
import com.epam.aidial.analytics.configuration.ClickHouseReplicasProperties;
import com.epam.aidial.analytics.configuration.ClickHouseRoutingProperties;
import com.epam.aidial.analytics.configuration.ClickHouseWriterProperties;
import com.epam.aidial.analytics.exception.ClickHouseQueryException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Which replica each unit of work is routed to; see the clickhouse-replication spec. */
class ReplicaRouterTest {

    private static final ClickHouseProperties CONNECTION = new ClickHouseProperties("chi-0", 8123, "adas", "reader", "");

    private final ReplicaMeters meters = new ReplicaMeters(new SimpleMeterRegistry());
    private final ClickHouseTopology topology = new ClickHouseTopology(CONNECTION, new ClickHouseWriterProperties("writer", ""),
            ClickHouseReplicasProperties.defaults(), mock(ClickHouseWriterClient.class), mock(ClickHouseClientWrapper.class),
            meters, new ReplicaHttp(), ReplicaAddressing.REPORTED, host -> List.of());

    private ReplicaRouter router(ClickHouseRoutingProperties routing) {
        return new ReplicaRouter(topology, routing, ClickHouseReplicasProperties.defaults(), CONNECTION, meters);
    }

    private static Replica replica(String name, boolean primary, boolean healthy) {
        return new Replica(name, name + ".hosts", primary, true, healthy);
    }

    @Test
    void beforeAnyDiscoveryEveryRouteIsThePrimary() {
        final ReplicaRouter router = router(ClickHouseRoutingProperties.defaults());
        assertThat(router.home("sessions_rollup").primary()).isTrue();
        assertThat(router.queryTarget().primary()).isTrue();
    }

    @Test
    void singleReplicaRoutesEverythingToItself() {
        topology.seed("chi-0", List.of(replica("chi-0", true, true)));
        final ReplicaRouter router = router(ClickHouseRoutingProperties.defaults());
        assertThat(router.home("sessions_rollup").name()).isEqualTo("chi-0");
        assertThat(router.queryTarget().name()).isEqualTo("chi-0");
    }

    @Test
    void homesAreStableAndOffThePrimary() {
        topology.seed("chi-0", List.of(replica("chi-0", true, true), replica("chi-1", false, true),
                replica("chi-2", false, true)));
        final ReplicaRouter router = router(ClickHouseRoutingProperties.defaults());
        final Set<String> homes = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            final String pipeline = "pipeline-" + i;
            final String home = router.home(pipeline).name();
            assertThat(router.home(pipeline).name()).isEqualTo(home);
            homes.add(home);
        }
        assertThat(homes).containsExactlyInAnyOrder("chi-1", "chi-2");
    }

    /** Rendezvous hashing: removing a replica moves only the pipelines whose home it was. */
    @Test
    void replicaLeavingMovesOnlyItsOwnPipelines() {
        topology.seed("chi-0", List.of(replica("chi-0", true, true), replica("chi-1", false, true),
                replica("chi-2", false, true), replica("chi-3", false, true)));
        final ReplicaRouter router = router(ClickHouseRoutingProperties.defaults());
        final List<String> before = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            before.add(router.home("p" + i).name());
        }
        topology.seed("chi-0", List.of(replica("chi-0", true, true), replica("chi-1", false, true),
                replica("chi-3", false, true)));
        for (int i = 0; i < 60; i++) {
            if (!before.get(i).equals("chi-2")) {
                assertThat(router.home("p" + i).name()).isEqualTo(before.get(i));
            }
        }
    }

    @Test
    void pipelinesPinnedToThePrimaryStayThere() {
        topology.seed("chi-0", List.of(replica("chi-0", true, true), replica("chi-1", false, true)));
        final ReplicaRouter router = router(new ClickHouseRoutingProperties(ClickHouseRoutingProperties.Route.REPLICAS,
                ClickHouseRoutingProperties.Route.PRIMARY));
        assertThat(router.home("sessions_rollup").primary()).isTrue();
    }

    @Test
    void withTwoOrMoreHealthyNonPrimaryReplicasQueriesStayOffThePrimary() {
        topology.seed("chi-0", List.of(replica("chi-0", true, true), replica("chi-1", false, true),
                replica("chi-2", false, true), replica("chi-3", false, false)));
        final ReplicaRouter router = router(ClickHouseRoutingProperties.defaults());
        final Set<String> served = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            served.add(router.queryTarget().name());
        }
        assertThat(served).containsExactlyInAnyOrder("chi-1", "chi-2");
    }

    @Test
    void withExactlyOneNonPrimaryReplicaThePrimarySharesQueries() {
        topology.seed("chi-0", List.of(replica("chi-0", true, true), replica("chi-1", false, true)));
        final ReplicaRouter router = router(ClickHouseRoutingProperties.defaults());
        final Set<String> served = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            served.add(router.queryTarget().name());
        }
        assertThat(served).containsExactlyInAnyOrder("chi-0", "chi-1");
    }

    @Test
    void unreachableReplicaFallsBackToThePrimaryAndIsExcluded() {
        topology.seed("chi-0", List.of(replica("chi-0", true, true), replica("chi-1", false, true),
                replica("chi-2", false, true)));
        final ReplicaRouter router = router(ClickHouseRoutingProperties.defaults());
        final List<String> ran = new ArrayList<>();
        final String answer = router.query(() -> {
            final ReplicaTarget bound = ReplicaScope.current().orElseThrow();
            ran.add(bound.name());
            if (!bound.primary()) {
                throw new RuntimeException(new ConnectException("refused"));
            }
            return "from " + bound.name();
        });
        assertThat(answer).isEqualTo("from chi-0");
        assertThat(ran).hasSize(2);
        assertThat(topology.candidates()).extracting(Replica::name).doesNotContain(ran.getFirst());
    }

    @Test
    void replicaBehindOnDdlFallsBackToThePrimary() {
        topology.seed("chi-0", List.of(replica("chi-0", true, true), replica("chi-1", false, true),
                replica("chi-2", false, true)));
        final ReplicaRouter router = router(ClickHouseRoutingProperties.defaults());
        final String answer = router.query(() -> {
            if (!ReplicaScope.current().orElseThrow().primary()) {
                throw new ClickHouseQueryException("rejected", new ServerException(47, "unknown", 404, "q"));
            }
            return "primary";
        });
        assertThat(answer).isEqualTo("primary");
        assertThat(topology.candidates()).hasSize(3);
    }

    @Test
    void routingQueriesToThePrimaryIgnoresReplicas() {
        topology.seed("chi-0", List.of(replica("chi-0", true, true), replica("chi-1", false, true),
                replica("chi-2", false, true)));
        final ReplicaRouter router = router(new ClickHouseRoutingProperties(ClickHouseRoutingProperties.Route.PRIMARY,
                ClickHouseRoutingProperties.Route.REPLICAS));
        assertThat(router.queryTarget().primary()).isTrue();
    }
}
