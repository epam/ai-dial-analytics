package com.epam.aidial.analytics.web.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The privilege vocabulary itself: the implication DAG, the bundles' closures, and the mapping parser.
 *
 * <p>These are the invariants everything else assumes. The bundles are stored minimally and closed by the
 * DAG, so a wrong edge would silently change what {@code FULL_ADMIN} and {@code READ_ONLY_ADMIN} confer —
 * and the change's whole compatibility claim is that those two keep exactly the authority the two
 * application roles carried.
 */
class PrivilegeModelTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    @DisplayName("the DAG has exactly the three admitted direct edges")
    void directEdges() {
        assertThat(PrivilegeImplications.directlyImplied(Privilege.TABLE_GET))
                .containsExactly(Privilege.TABLE_LIST);
        assertThat(PrivilegeImplications.directlyImplied(Privilege.TABLE_READ))
                .containsExactly(Privilege.TABLE_GET);
        assertThat(PrivilegeImplications.directlyImplied(Privilege.TABLE_MODIFY))
                .containsExactly(Privilege.TABLE_GET);
        // Every other privilege implies nothing at all — the sensitivity clearance included.
        for (final Privilege privilege : Privilege.values()) {
            if (!EnumSet.of(Privilege.TABLE_GET, Privilege.TABLE_READ, Privilege.TABLE_MODIFY).contains(privilege)) {
                assertThat(PrivilegeImplications.directlyImplied(privilege))
                        .as("%s must imply nothing", privilege)
                        .isEmpty();
            }
        }
    }

    @Test
    @DisplayName("publishing confers nothing; the write endpoints decide against the body's scope")
    void publishImplications() {
        // An earlier round gave PUBLISH -> SAVED_QUERY_READ on the argument that it already sees every
        // saved query. Only the revision endpoint and the audit feed do; get and list go through
        // SavedQueryRepository.findVisible, which has no such bypass. The edge let a PUBLISH-only caller
        // read another user's personal saved query's history while its current state still answered 404.
        assertThat(PrivilegeImplications.closureOf(Privilege.SAVED_QUERY_PUBLISH))
                .containsExactly(Privilege.SAVED_QUERY_PUBLISH);
        assertThat(PrivilegeImplications.closureOf(Privilege.SAVED_QUERY_WRITE))
                .containsExactly(Privilege.SAVED_QUERY_WRITE);
    }

    @Test
    @DisplayName("the top read rung closes over the whole ladder, and the clearance over nothing")
    void readLadderClosure() {
        assertThat(PrivilegeImplications.closureOf(Privilege.TABLE_SENSITIVE))
                .as("the clearance is not a rung: it qualifies a verb and confers none")
                .containsExactly(Privilege.TABLE_SENSITIVE);
        assertThat(PrivilegeImplications.closureOf(Privilege.TABLE_READ)).containsExactlyInAnyOrder(
                Privilege.TABLE_READ, Privilege.TABLE_GET, Privilege.TABLE_LIST);
        assertThat(PrivilegeImplications.closureOf(Privilege.TABLE_MODIFY)).containsExactlyInAnyOrder(
                Privilege.TABLE_MODIFY, Privilege.TABLE_GET, Privilege.TABLE_LIST);
    }

    @Test
    @DisplayName("write confers no read of any kind")
    void writeIsIsolated() {
        assertThat(PrivilegeImplications.closureOf(Privilege.TABLE_WRITE))
                .as("GET \\ LIST is {columns, grain}; a row insert only confirms a column name the caller "
                        + "already guessed, so WRITE leaks the inventory without containing it")
                .containsExactly(Privilege.TABLE_WRITE);
        assertThat(PrivilegeImplications.conferring(Privilege.TABLE_READ))
                .doesNotContain(Privilege.TABLE_WRITE);
        assertThat(PrivilegeImplications.conferring(Privilege.TABLE_LIST))
                .doesNotContain(Privilege.TABLE_WRITE);
    }

    @Test
    @DisplayName("conferring names every list a check must consult")
    void conferringSets() {
        assertThat(PrivilegeImplications.conferring(Privilege.TABLE_LIST)).containsExactlyInAnyOrder(
                Privilege.TABLE_LIST, Privilege.TABLE_GET, Privilege.TABLE_READ, Privilege.TABLE_MODIFY);
        assertThat(PrivilegeImplications.conferring(Privilege.TABLE_GET)).containsExactlyInAnyOrder(
                Privilege.TABLE_GET, Privilege.TABLE_READ, Privilege.TABLE_MODIFY);
        assertThat(PrivilegeImplications.conferring(Privilege.TABLE_READ)).containsExactly(Privilege.TABLE_READ);
        assertThat(PrivilegeImplications.conferring(Privilege.TABLE_SENSITIVE))
                .containsExactly(Privilege.TABLE_SENSITIVE);
        assertThat(PrivilegeImplications.conferring(Privilege.TABLE_MODIFY))
                .containsExactly(Privilege.TABLE_MODIFY);
    }

    @Test
    @DisplayName("each bundle closes to exactly its expected privilege set")
    void bundleClosures() {
        assertThat(RoleBundle.FULL_ADMIN.privileges()).containsExactlyInAnyOrder(Privilege.values());

        assertThat(RoleBundle.READ_ONLY_ADMIN.privileges()).containsExactlyInAnyOrder(
                Privilege.TABLE_READ, Privilege.TABLE_GET, Privilege.TABLE_LIST,
                Privilege.REGISTRY_READ, Privilege.AUDIT_READ,
                Privilege.SAVED_QUERY_READ, Privilege.SAVED_QUERY_WRITE);

        assertThat(RoleBundle.READ_ONLY_ANALYTIC.privileges()).containsExactlyInAnyOrder(
                Privilege.TABLE_READ, Privilege.TABLE_GET, Privilege.TABLE_LIST,
                Privilege.SAVED_QUERY_READ, Privilege.SAVED_QUERY_WRITE);

        assertThat(RoleBundle.READ_ONLY_SENSITIVE_ANALYTIC.privileges()).containsExactlyInAnyOrder(
                Privilege.TABLE_SENSITIVE, Privilege.TABLE_READ, Privilege.TABLE_GET, Privilege.TABLE_LIST,
                Privilege.SAVED_QUERY_READ, Privilege.SAVED_QUERY_WRITE);
    }

    @Test
    @DisplayName("the analyst bundles carry no administrative authority")
    void analystBundlesAreNotAdministrative() {
        final Set<Privilege> administrative = EnumSet.of(
                Privilege.TABLE_CREATE, Privilege.TABLE_DELETE, Privilege.TABLE_GRANT,
                Privilege.TABLE_WRITE, Privilege.TABLE_MODIFY, Privilege.REGISTRY_ADMIN,
                Privilege.DATA_SYNC_OPERATE, Privilege.SAVED_QUERY_PUBLISH, Privilege.AUDIT_READ);
        assertThat(RoleBundle.READ_ONLY_ANALYTIC.privileges()).doesNotContainAnyElementsOf(administrative);
        assertThat(RoleBundle.READ_ONLY_SENSITIVE_ANALYTIC.privileges())
                .as("this bundle is what finally makes PII read reachable without administrative authority")
                .doesNotContainAnyElementsOf(administrative);
    }

    @Test
    @DisplayName("bundles are stored minimally, not as their closure")
    void bundlesAreStoredMinimally() {
        assertThat(RoleBundle.READ_ONLY_SENSITIVE_ANALYTIC.declaredPrivileges())
                .as("one source of truth for implication: the DAG, not a hand-copied closure")
                .containsExactlyInAnyOrder(Privilege.TABLE_READ, Privilege.TABLE_SENSITIVE,
                        Privilege.SAVED_QUERY_READ, Privilege.SAVED_QUERY_WRITE);
    }

    @Test
    @DisplayName("a mapping accepts bundle and privilege names mixed, closed under implication")
    void mappingMixesBundlesAndPrivileges() {
        final Map<String, Set<Privilege>> mapping = RolesMappingParser.parse(jsonMapper,
                "{\"admin\":[\"FULL_ADMIN\"],\"viewer\":[\"TABLE_GET\",\"SAVED_QUERY_READ\"]}",
                "security.defaults.roles-mapping");
        assertThat(mapping.get("admin")).containsExactlyInAnyOrder(Privilege.values());
        assertThat(mapping.get("viewer")).containsExactlyInAnyOrder(
                Privilege.TABLE_GET, Privilege.TABLE_LIST, Privilege.SAVED_QUERY_READ);
    }

    @Test
    @DisplayName("an unknown mapping value fails startup, naming the value and the property")
    void unknownMappingValueFails() {
        assertThatThrownBy(() -> RolesMappingParser.parse(
                jsonMapper, "{\"analyst\":[\"TABLE_PEEK\"]}", "security.api-key.roles-mapping"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TABLE_PEEK")
                .hasMessageContaining("security.api-key.roles-mapping")
                .hasMessageContaining("analyst");
    }

    @Test
    @DisplayName("a blank mapping yields no privileges rather than failing")
    void blankMappingIsEmpty() {
        assertThat(RolesMappingParser.parse(jsonMapper, "  ", "security.defaults.roles-mapping")).isEmpty();
        assertThat(RolesMappingParser.parse(jsonMapper, null, "security.defaults.roles-mapping")).isEmpty();
    }

    @Test
    @DisplayName("security.mode is matched case-insensitively and rejects anything else")
    void securityModeParsing() {
        assertThat(SecurityMode.parse("oidc")).isEqualTo(SecurityMode.OIDC);
        assertThat(SecurityMode.parse("OIDC")).isEqualTo(SecurityMode.OIDC);
        assertThat(SecurityMode.parse(" Oidc ")).isEqualTo(SecurityMode.OIDC);
        assertThat(SecurityMode.parse("none")).isEqualTo(SecurityMode.NONE);
        assertThat(SecurityMode.parse(null)).isEqualTo(SecurityMode.NONE);
        assertThat(SecurityMode.parse("")).isEqualTo(SecurityMode.NONE);
        assertThatThrownBy(() -> SecurityMode.parse("disabled"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disabled")
                .hasMessageContaining("security.mode");
    }

    @Test
    @DisplayName("every entry point builds the same authority shape")
    void authorityShape() {
        assertThat(CallerAuthorities.build(
                        EnumSet.of(Privilege.TABLE_READ), java.util.List.of("analytics-viewers")))
                .extracting(org.springframework.security.core.GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("PRIVILEGE_TABLE_READ", "PROVIDER_ROLE_analytics-viewers");
        assertThat(PrivilegeAuthority.parse("PRIVILEGE_TABLE_READ")).isEqualTo(Privilege.TABLE_READ);
        assertThat(PrivilegeAuthority.parse("PROVIDER_ROLE_analytics-viewers")).isNull();
    }
}
