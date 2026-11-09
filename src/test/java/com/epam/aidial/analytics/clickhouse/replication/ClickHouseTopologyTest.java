package com.epam.aidial.analytics.clickhouse.replication;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.GenericRecord;
import com.epam.aidial.analytics.clickhouse.ClickHouseClientWrapper;
import com.epam.aidial.analytics.clickhouse.ClickHouseWriterClient;
import com.epam.aidial.analytics.configuration.ClickHouseProperties;
import com.epam.aidial.analytics.configuration.ClickHouseReplicasProperties;
import com.epam.aidial.analytics.configuration.ClickHouseWriterProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/** Discovery from {@code system.clusters}, and the startup refusal of a primary endpoint that spreads. */
class ClickHouseTopologyTest {

    private static final ClickHouseProperties CONNECTION = new ClickHouseProperties("chi-primary", 8123, "adas", "r", "");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final Client client = mock(Client.class);
    private final ClickHouseWriterClient writer = mock(ClickHouseWriterClient.class);

    /** Health answers yes; the identity probe answers the queued names in turn, then the last one. */
    private static final class FakeHttp extends ReplicaHttp {

        private final Deque<String> answers;
        private String last;

        FakeHttp(String... answers) {
            this.answers = new ArrayDeque<>(List.of(answers));
        }

        @Override
        Status status(String host, int port) {
            return host.startsWith("chi-down") ? Status.UNREACHABLE : Status.HEALTHY;
        }

        @Override
        Optional<String> answeringReplica(String host, int port, String user, String password, String database) {
            if (!answers.isEmpty()) {
                last = answers.poll();
            }
            return Optional.ofNullable(last);
        }
    }

    private static GenericRecord row(String name, boolean local, boolean active) {
        final GenericRecord row = mock(GenericRecord.class);
        given(row.getString("name")).willReturn(name);
        given(row.getString("host")).willReturn(name + ".hosts");
        given(row.getBoolean("local")).willReturn(local);
        given(row.getBoolean("active")).willReturn(active);
        return row;
    }

    private ClickHouseTopology topology(ReplicaHttp http, Function<String, List<String>> resolver, GenericRecord... rows) {
        given(writer.primaryClient()).willReturn(client);
        given(client.queryAll(anyString(), anyMap())).willReturn(List.of(rows));
        return new ClickHouseTopology(CONNECTION, new ClickHouseWriterProperties("w", ""),
                ClickHouseReplicasProperties.defaults(), writer, mock(ClickHouseClientWrapper.class),
                new ReplicaMeters(registry), http, ReplicaAddressing.REPORTED, resolver);
    }

    @Test
    void discoversTheActiveReplicasAndNamesThePrimary() {
        final ClickHouseTopology topology = topology(new FakeHttp("chi-0"), host -> List.of("10.0.0.1"),
                row("chi-0", true, true), row("chi-1", false, true), row("chi-2", false, false));
        topology.startup();
        assertThat(topology.primaryName()).isEqualTo("chi-0");
        assertThat(topology.candidates()).extracting(Replica::name).containsExactly("chi-0", "chi-1");
        assertThat(registry.get(ReplicaMeters.ACTIVE).gauge().value()).isEqualTo(2.0);
    }

    @Test
    void hostResolvingToSeveralAddressesIsRefusedWithSeveralActiveReplicas() {
        final ClickHouseTopology topology = topology(new FakeHttp("chi-0"), host -> List.of("10.0.0.1", "10.0.0.2"),
                row("chi-0", true, true), row("chi-1", false, true));
        assertThatThrownBy(topology::startup).hasMessageContaining("chi-primary")
                .hasMessageContaining("10.0.0.1").hasMessageContaining("10.0.0.2");
    }

    /** A ClusterIP-style endpoint resolves to one address, but fresh connections reach different replicas. */
    @Test
    void singleAddressEndpointAnsweredByDifferentReplicasIsRefused() {
        final ClickHouseTopology topology = topology(new FakeHttp("chi-0", "chi-1"), host -> List.of("10.0.0.9"),
                row("chi-0", true, true), row("chi-1", false, true));
        assertThatThrownBy(topology::startup).hasMessageContaining("chi-primary")
                .hasMessageContaining("chi-0").hasMessageContaining("chi-1");
    }

    @Test
    void spreadingHostIsAcceptedWhileThereIsOneActiveReplica() {
        final ClickHouseTopology topology = topology(new FakeHttp("chi-0", "chi-1"), host -> List.of("10.0.0.1", "10.0.0.2"),
                row("chi-0", true, true), row("chi-1", false, false));
        topology.startup();
        assertThat(topology.candidates()).extracting(Replica::name).containsExactly("chi-0");
    }

    @Test
    void changedAnsweringReplicaOnLaterRefreshIsCountedNotFatal() {
        final String[] answers = new String[ClickHouseTopology.IDENTITY_PROBES + 1];
        Arrays.fill(answers, "chi-0");
        answers[ClickHouseTopology.IDENTITY_PROBES] = "chi-1";
        final ClickHouseTopology topology = topology(new FakeHttp(answers),
                host -> List.of("10.0.0.1"), row("chi-0", true, true), row("chi-1", false, true));
        topology.startup();
        topology.refresh(false);
        assertThat(registry.get(ReplicaMeters.PRIMARY_CHANGES).counter().count()).isEqualTo(1.0);
    }

    @Test
    void failedDiscoveryKeepsRoutingToThePrimary() {
        given(writer.primaryClient()).willReturn(client);
        given(client.queryAll(anyString(), anyMap())).willThrow(new IllegalStateException("ACCESS_DENIED"));
        final ClickHouseTopology topology = new ClickHouseTopology(CONNECTION, new ClickHouseWriterProperties("w", ""),
                ClickHouseReplicasProperties.defaults(), writer, mock(ClickHouseClientWrapper.class),
                new ReplicaMeters(registry), new FakeHttp(), ReplicaAddressing.REPORTED, host -> List.of());
        topology.startup();
        assertThat(topology.discovered()).isFalse();
        assertThat(topology.candidates()).isEmpty();
        assertThat(registry.get(ReplicaMeters.DISCOVERY).tag("outcome", "failed").counter().count()).isEqualTo(1.0);
    }

    /** Active in Keeper but not answering over HTTP: neither a home nor a query replica until it answers. */
    @Test
    void replicaThatDoesNotAnswerIsNoCandidate() {
        final ClickHouseTopology topology = topology(new FakeHttp("chi-0"), host -> List.of("10.0.0.1"),
                row("chi-0", true, true), row("chi-1", false, true), row("chi-down", false, true));
        topology.startup();
        assertThat(topology.candidates()).extracting(Replica::name).containsExactly("chi-0", "chi-1");
    }

    /** ClickHouse reports activity NULL when it cannot ask Keeper: that is a failed refresh, not zero replicas. */
    @Test
    void unknownActivityKeepsTheLastReplicaSet() {
        final GenericRecord primary = row("chi-0", true, true);
        final GenericRecord other = row("chi-1", false, true);
        final ClickHouseTopology topology = topology(new FakeHttp("chi-0"), host -> List.of("10.0.0.1"), primary, other);
        topology.startup();
        given(other.getBoolean("activity_unknown")).willReturn(true);
        topology.refresh(false);
        assertThat(topology.candidates()).extracting(Replica::name).containsExactly("chi-0", "chi-1");
        assertThat(registry.get(ReplicaMeters.DISCOVERY).tag("outcome", "failed").counter().count()).isEqualTo(1.0);
    }

    @Test
    void missingLocalRowFailsTheRefresh() {
        final ClickHouseTopology topology = topology(new FakeHttp("chi-0"), host -> List.of("10.0.0.1"),
                row("chi-1", false, true));
        topology.startup();
        assertThat(topology.discovered()).isFalse();
        assertThat(registry.get(ReplicaMeters.DISCOVERY).tag("outcome", "failed").counter().count()).isEqualTo(1.0);
    }

    /** Startup discovery failed, so the fresh-connection check runs on the first refresh that succeeds. */
    @Test
    void spreadIsCheckedOnTheFirstSuccessfulRefreshAfterStartupFailed() {
        given(writer.primaryClient()).willReturn(client);
        given(client.queryAll(anyString(), anyMap())).willThrow(new IllegalStateException("unreachable"))
                .willReturn(List.of(row("chi-0", true, true), row("chi-1", false, true)));
        final ClickHouseTopology topology = new ClickHouseTopology(CONNECTION, new ClickHouseWriterProperties("w", ""),
                ClickHouseReplicasProperties.defaults(), writer, mock(ClickHouseClientWrapper.class),
                new ReplicaMeters(registry), new FakeHttp("chi-0", "chi-1"), ReplicaAddressing.REPORTED,
                host -> List.of("10.0.0.9"));
        topology.startup();
        topology.refresh(false);
        assertThat(topology.discovered()).isTrue();
        assertThat(registry.get(ReplicaMeters.PRIMARY_SPREAD).counter().count()).isEqualTo(1.0);
    }
}
