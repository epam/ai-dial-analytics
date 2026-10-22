package com.epam.aidial.analytics.service.table.grant;

import com.epam.aidial.analytics.service.security.SecurityClaimsExtractor;
import com.epam.aidial.analytics.service.table.TableAccess;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.web.security.CallerAuthorities;
import com.epam.aidial.analytics.web.security.Privilege;
import com.epam.aidial.analytics.web.security.SecurityMode;
import com.epam.aidial.analytics.web.security.SecurityModeResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Truth table for {@link TableCallerPermissions}, the six reported flags.
 *
 * <p>Two cases carry the decisions this change made. A {@code system} table now reports its <b>read-side</b>
 * flags honestly instead of all-false — reporting no permissions at all denied the very read grants that
 * make per-table read applicable to {@code dial_usage_log}. And a grant reports <b>after</b> implication, so
 * a role listed only under {@code read} reports {@code read}, {@code get} and {@code list}: the stored list
 * is what was written, the flags are what it means.
 */
class TableCallerPermissionsTest {

    private final TableCallerPermissions oidc = permissions(SecurityMode.OIDC);
    private final TableCallerPermissions none = permissions(SecurityMode.NONE);

    private static TableCallerPermissions permissions(SecurityMode mode) {
        return new TableCallerPermissions(
                new SecurityClaimsExtractor(), new SecurityModeResolver(mode.code()), com.epam.aidial.analytics.service.security.RowPolicyFilterStubs.inert());
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateWith(Set<Privilege> privileges, String... providerRoles) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "caller", "n/a", CallerAuthorities.build(privileges, List.of(providerRoles))));
    }

    private static TableDefinitionEntity table(Map<Privilege, List<String>> grants) {
        final TableDefinitionEntity def = new TableDefinitionEntity();
        def.setName("t");
        final TableAccess access = new TableAccess();
        grants.forEach(access::setRolesFor);
        def.setAccess(access);
        return def;
    }

    @Test
    @DisplayName("a system table reports no write permissions but honours its read grants")
    void systemTableHonoursReadGrants() {
        authenticateWith(EnumSet.allOf(Privilege.class));
        final TableDefinitionEntity def = table(Map.of(Privilege.TABLE_READ, List.of("viewer")));
        def.setSystem(true);

        assertThat(oidc.resolve(def)).isEqualTo(new EffectivePermissions(true, true, true, true, false, false));
        assertThat(none.resolve(def))
                .as("none mode enforces nothing, but a system table's write/modify are still hard false")
                .isEqualTo(new EffectivePermissions(true, true, true, true, false, false));
    }

    @Test
    @DisplayName("a system table's read grant is reported for a caller with no global privilege")
    void systemTableReadGrantIsReported() {
        authenticateWith(EnumSet.noneOf(Privilege.class), "analytics-viewers");
        final TableDefinitionEntity def = table(Map.of(Privilege.TABLE_READ, List.of("analytics-viewers")));
        def.setSystem(true);

        assertThat(oidc.resolve(def)).isEqualTo(new EffectivePermissions(true, true, true, false, false, false));
    }

    @Test
    @DisplayName("none mode reports everything without authentication")
    void noneModeReportsEverything() {
        assertThat(none.resolve(table(Map.of()))).isEqualTo(EffectivePermissions.uniform(true));
    }

    @Test
    @DisplayName("a caller holding every privilege reports everything on a non-system table")
    void fullPrivilegeCallerReportsEverything() {
        authenticateWith(EnumSet.allOf(Privilege.class));
        assertThat(oidc.resolve(table(Map.of()))).isEqualTo(EffectivePermissions.uniform(true));
    }

    @Test
    @DisplayName("a write grant reports write alone")
    void writeGrantReportsWriteOnly() {
        authenticateWith(EnumSet.noneOf(Privilege.class), "writer");
        assertThat(oidc.resolve(table(Map.of(Privilege.TABLE_WRITE, List.of("writer")))))
                .isEqualTo(new EffectivePermissions(false, false, false, false, true, false));
    }

    @Test
    @DisplayName("a modify grant reports modify plus the schema rungs it implies")
    void modifyGrantReportsSchemaVisibility() {
        authenticateWith(EnumSet.noneOf(Privilege.class), "editor");
        assertThat(oidc.resolve(table(Map.of(Privilege.TABLE_MODIFY, List.of("editor")))))
                .isEqualTo(new EffectivePermissions(true, true, false, false, false, true));
    }

    @Test
    @DisplayName("implication is reflected in the reported flags")
    void implicationIsReflected() {
        authenticateWith(EnumSet.noneOf(Privilege.class), "analytics-viewers");
        assertThat(oidc.resolve(table(Map.of(Privilege.TABLE_READ, List.of("analytics-viewers")))))
                .isEqualTo(new EffectivePermissions(true, true, true, false, false, false));
    }

    @Test
    @DisplayName("no matching role reports nothing")
    void noMatchingRoleReportsNothing() {
        authenticateWith(EnumSet.noneOf(Privilege.class), "other");
        assertThat(oidc.resolve(table(Map.of(
                Privilege.TABLE_WRITE, List.of("writer"),
                Privilege.TABLE_MODIFY, List.of("editor")))))
                .isEqualTo(EffectivePermissions.uniform(false));
    }

    @Test
    @DisplayName("null lists and a null access object report nothing")
    void nullAccessReportsNothing() {
        authenticateWith(EnumSet.noneOf(Privilege.class), "writer");
        final TableDefinitionEntity nullLists = table(Map.of());
        nullLists.getAccess().setWrite(null);
        nullLists.getAccess().setRead(null);
        assertThat(oidc.resolve(nullLists)).isEqualTo(EffectivePermissions.uniform(false));

        final TableDefinitionEntity noAccess = new TableDefinitionEntity();
        noAccess.setName("t");
        noAccess.setAccess(null);
        assertThat(oidc.resolve(noAccess)).isEqualTo(EffectivePermissions.uniform(false));
    }
}
