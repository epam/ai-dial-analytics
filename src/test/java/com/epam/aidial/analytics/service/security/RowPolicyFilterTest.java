package com.epam.aidial.analytics.service.security;

import com.clickhouse.client.api.ClientConfigProperties;
import com.clickhouse.client.api.query.QuerySettings;
import com.epam.aidial.analytics.configuration.ClickHouseProperties;
import com.epam.aidial.analytics.configuration.JsonMapperConfiguration;
import com.epam.aidial.analytics.exception.RowPolicyConfinedException;
import com.epam.aidial.analytics.service.query.RowPolicyPredicates;
import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.ComparisonOp;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.ValueType;
import com.epam.aidial.analytics.service.table.TableAccess;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository.StoredRowPolicies;
import com.epam.aidial.analytics.service.table.rowpolicy.RowPolicy;
import com.epam.aidial.analytics.service.table.rowpolicy.RowPolicyCodec;
import com.epam.aidial.analytics.web.security.Privilege;
import com.epam.aidial.analytics.web.security.ProviderRolePrivileges;
import com.epam.aidial.analytics.web.security.SecurityMode;
import com.epam.aidial.analytics.web.security.SecurityModeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * The evaluation half of row policies, without ClickHouse: which tables confine a caller, how matching
 * policies combine, how the setting is keyed and escaped, and where the filter is inert. Rendering is
 * stubbed to {@code <table>:<value>} so each assertion reads which policy contributed.
 */
class RowPolicyFilterTest {

    private static final String SETTING_KEY = ClientConfigProperties.serverSetting(RowPolicyFilter.SETTING);

    private final TableDefinitionRepository definitions = mock(TableDefinitionRepository.class);
    private final RowPolicyPredicates predicates = mock(RowPolicyPredicates.class);
    private final SecurityClaimsExtractor claims = mock(SecurityClaimsExtractor.class);
    private final ProviderRolePrivileges rolePrivileges = mock(ProviderRolePrivileges.class);
    private final JsonMapper jsonMapper = new JsonMapperConfiguration().jsonMapper();
    private final RowPolicyCodec codec = new RowPolicyCodec(jsonMapper);

    private RowPolicyFilter oidc;
    private RowPolicyFilter none;

    @BeforeEach
    void setUp() {
        oidc = filter(SecurityMode.OIDC, "analytics");
        none = filter(SecurityMode.NONE, "analytics");
        given(predicates.render(anyString(), any(FilterNode.class))).willAnswer(inv ->
                inv.getArgument(0) + ":" + ((ValueExpr) ((ComparisonNode) inv.getArgument(1)).args().get(1)).value());
        // `analysts` holds a per-table read grant on `events` only; `viewer` maps to TABLE_READ globally.
        given(definitions.findStoredRowPolicies()).willReturn(List.of(
                stored("events", Map.of("team-x", only("X"), "team-y", only("Y")), readGrant("analysts")),
                stored("costs", Map.of("team-x", only("X")), new TableAccess())));
        given(rolePrivileges.holdsGlobally("viewer", Privilege.TABLE_READ)).willReturn(true);
    }

    @Test
    void callerNoneOfWhoseRolesHasPolicyIsConfinedNowhere() {
        callerRoles("team-z");

        assertThat(oidc.filters()).isEmpty();
        final QuerySettings settings = new QuerySettings();
        assertThat(oidc.apply(settings).getAllSettings()).doesNotContainKey(SETTING_KEY);
    }

    @Test
    void everyConfiningTableGetsDatabaseQualifiedEntry() {
        callerRoles("team-x");

        // Both tables, not only one a statement might read: an extra entry is inert, a missing one is not.
        assertThat(oidc.filters()).containsExactly(
                Map.entry("analytics.costs", "costs:X"),
                Map.entry("analytics.events", "events:X"));
    }

    @Test
    void severalConfiningPoliciesAreOred() {
        callerRoles("team-x", "team-y");

        assertThat(oidc.filters()).containsEntry("analytics.events", "(events:X) OR (events:Y)");
    }

    @Test
    void roleThatCannotReadTheTableDoesNotLiftTheConfinement() {
        // An IdP group unrelated to the table: no policy, but no read either, so it counts for nothing.
        callerRoles("team-x", "everyone");

        assertThat(oidc.filters()).containsOnlyKeys("analytics.costs", "analytics.events");
    }

    @Test
    void readGranteeWithoutPolicyLiftsTheConfinementOnItsTableOnly() {
        callerRoles("team-x", "analysts");

        assertThat(oidc.filters()).containsOnlyKeys("analytics.costs");
    }

    @Test
    void globalReaderWithoutPolicyLiftsTheConfinementEverywhere() {
        callerRoles("team-x", "viewer");
        given(claims.hasPrivilege(Privilege.TABLE_READ)).willReturn(true);

        assertThat(oidc.filters()).isEmpty();
    }

    @Test
    void globalMappingCountsOnlyWhenTheCallerActuallyHoldsTheRead() {
        // `viewer` maps to TABLE_READ under some configured provider, but not under the one this caller came
        // through: crediting the role with a read the caller does not hold would widen it.
        callerRoles("team-x", "viewer");
        given(claims.hasPrivilege(Privilege.TABLE_READ)).willReturn(false);

        assertThat(oidc.filters()).containsOnlyKeys("analytics.costs", "analytics.events");
    }

    @Test
    void broadRoleWithItsOwnPolicyIsConfinedByIt() {
        given(definitions.findStoredRowPolicies()).willReturn(List.of(
                stored("events", Map.of("team-x", only("X"), "viewer", only("V")), new TableAccess())));
        callerRoles("team-x", "viewer");
        given(claims.hasPrivilege(Privilege.TABLE_READ)).willReturn(true);

        assertThat(oidc.filters()).containsEntry("analytics.events", "(events:X) OR (events:V)");
    }

    @Test
    void theSessionDatabaseKeysTheEntries() {
        callerRoles("team-x");

        assertThat(filter(SecurityMode.OIDC, "  ").filters()).containsKey("default.events");
    }

    @Test
    void noneModeAndTheSystemIdentityAreNeverConfined() {
        callerRoles("team-x");
        assertThat(none.filters()).isEmpty();
        assertThat(none.isConfined(definition("events", Map.of("team-x", only("X"))))).isFalse();

        callerRoles();
        assertThat(oidc.filters()).isEmpty();
        assertThatCode(() -> oidc.requireUnconfined(Set.of("events"), "arm it")).doesNotThrowAnyException();
    }

    @Test
    void theSettingIsMapOfEscapedStringLiterals() {
        assertThat(RowPolicyFilter.settingValue(Map.of("db.t", "x IN ('P0\\') OR 1 OR (\\'')")))
                .isEqualTo("{'db.t':'x IN (\\'P0\\\\\\') OR 1 OR (\\\\\\'\\')'}");
    }

    @Test
    void applyMergesTheFilterIntoTheGivenSettings() {
        callerRoles("team-x");

        final QuerySettings settings = new QuerySettings().serverSetting("max_execution_time", "20");
        assertThat(oidc.apply(settings).getAllSettings())
                .containsEntry(SETTING_KEY, "{'analytics.costs':'costs:X','analytics.events':'events:X'}")
                .containsKey(ClientConfigProperties.serverSetting("max_execution_time"));
    }

    @Test
    void isConfinedAgreesWithEnforcement() {
        final TableDefinitionEntity events = definition("events", Map.of("team-x", only("X")));
        events.setAccess(readGrant("analysts"));

        callerRoles("team-x");
        assertThat(oidc.isConfined(events)).isTrue();
        callerRoles("team-x", "analysts");
        assertThat(oidc.isConfined(events)).isFalse();
        callerRoles("team-z");
        assertThat(oidc.isConfined(events)).isFalse();
    }

    @Test
    void pipelineOverConfinedTableIsRefusedNamingTheTable() {
        callerRoles("team-x");
        final TableDefinitionEntity enrichment = definition("events_labels", Map.of());
        enrichment.setSourceTable("events");
        given(definitions.findById(eq("events_labels"))).willReturn(Optional.of(enrichment));
        given(definitions.findBySourceTable(anyString())).willReturn(List.of());

        // Reached through the target's own source, not named as the input.
        assertThatThrownBy(() -> oidc.requireUnconfinedPipeline(null, "events_labels", "arm this pipeline"))
                .isInstanceOf(RowPolicyConfinedException.class)
                .hasMessageContaining("'events'")
                .hasMessageContaining("arm this pipeline");
        assertThatCode(() -> oidc.requireUnconfinedPipeline("unrelated", null, "arm this pipeline"))
                .doesNotThrowAnyException();
    }

    // --- fixtures ----------------------------------------------------------------------------------------

    private RowPolicyFilter filter(SecurityMode mode, String database) {
        return new RowPolicyFilter(definitions, codec, predicates, claims, new SecurityModeResolver(mode.code()),
                new ClickHouseProperties("localhost", 8123, database, "u", "p"), rolePrivileges);
    }

    private void callerRoles(String... roles) {
        given(claims.getProviderRoles()).willReturn(Set.of(roles));
    }

    private static RowPolicy only(String value) {
        return new RowPolicy(new ComparisonNode(ComparisonOp.EQ,
                List.of(new FieldExpr("project"), new ValueExpr(ValueType.STRING, value))));
    }

    private static TableAccess readGrant(String... roles) {
        final TableAccess access = new TableAccess();
        access.setRolesFor(Privilege.TABLE_READ, List.of(roles));
        return access;
    }

    private StoredRowPolicies stored(String table, Map<String, RowPolicy> policies, TableAccess access) {
        final String json = codec.write(policies);
        final String accessJson = jsonMapper.writeValueAsString(access);
        return new StoredRowPolicies() {
            @Override
            public String getName() {
                return table;
            }

            @Override
            public String getPolicies() {
                return json;
            }

            @Override
            public String getAccess() {
                return accessJson;
            }
        };
    }

    private TableDefinitionEntity definition(String name, Map<String, RowPolicy> policies) {
        final TableDefinitionEntity definition = new TableDefinitionEntity();
        definition.setName(name);
        definition.setRowPolicies(codec.write(policies));
        return definition;
    }
}
