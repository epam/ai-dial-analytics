package com.epam.aidial.analytics.service.table.grant;

import com.epam.aidial.analytics.configuration.JsonMapperConfiguration;
import com.epam.aidial.analytics.service.security.RowPolicyFilter;
import com.epam.aidial.analytics.service.security.RowPolicyFilterStubs;
import com.epam.aidial.analytics.service.security.SecurityClaimsExtractor;
import com.epam.aidial.analytics.service.security.SensitiveColumnAccess;
import com.epam.aidial.analytics.service.table.ColumnMappingEntity;
import com.epam.aidial.analytics.service.table.ColumnMappingRepository;
import com.epam.aidial.analytics.service.table.TableAccess;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import com.epam.aidial.analytics.service.table.TableType;
import com.epam.aidial.analytics.service.table.rowpolicy.RowPolicyCodec;
import com.epam.aidial.analytics.web.security.CallerAuthorities;
import com.epam.aidial.analytics.web.security.Privilege;
import com.epam.aidial.analytics.web.security.ProviderRolePrivileges;
import com.epam.aidial.analytics.web.security.SecurityMode;
import com.epam.aidial.analytics.web.security.SecurityModeResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Truth table for {@link TableCallerPermissions}, the reported flags.
 *
 * <p>Two cases carry the decisions this change made. A {@code system} table now reports its <b>read-side</b>
 * flags honestly instead of all-false — reporting no permissions at all denied the very read grants that
 * make per-table read applicable to {@code dial_usage_log}. And a grant reports <b>after</b> implication, so
 * a role listed only under {@code read} reports {@code read}, {@code get} and {@code list}: the stored list
 * is what was written, the flags are what it means.
 */
class TableCallerPermissionsTest {

    private final TableDefinitionRepository definitions = mock(TableDefinitionRepository.class);
    private final RowPolicyFilter rowPolicies = RowPolicyFilterStubs.inert();
    private final ColumnMappingRepository columns = mock(ColumnMappingRepository.class);
    private final TableCallerPermissions oidc = permissions(SecurityMode.OIDC);
    private final TableCallerPermissions none = permissions(SecurityMode.NONE);

    private TableCallerPermissions permissions(SecurityMode mode) {
        final SecurityClaimsExtractor claims = new SecurityClaimsExtractor();
        final SecurityModeResolver resolver = new SecurityModeResolver(mode.code());
        final GoverningAccess governing = new GoverningAccess(definitions);
        final JsonMapper jsonMapper = new JsonMapperConfiguration().jsonMapper();
        final SensitiveColumnAccess columnAccess = new SensitiveColumnAccess(new TableAccessEvaluator(definitions, governing, claims),
                resolver, definitions, governing, claims, mock(ProviderRolePrivileges.class), new RowPolicyCodec(jsonMapper),
                new ColumnPolicyCodec(jsonMapper));
        return new TableCallerPermissions(claims, resolver, rowPolicies, governing, columnAccess, columns);
    }

    @Test
    @DisplayName("columns_restricted is true exactly when the visibility decision hides one of the table's own columns")
    void columnsRestrictedFollowsTheVisibilityDecision() {
        final TableDefinitionEntity t = table(Map.of(Privilege.TABLE_READ, List.of("X")));
        t.setName("t");
        when(columns.findByTableNameOrderByOrdinal("t")).thenReturn(List.of(column("a"), column("b")));
        t.setColumnPolicies("{\"X\": [\"a\"]}");
        authenticateWith(EnumSet.noneOf(Privilege.class), "X");
        assertThat(oidc.resolve(t).columnsRestricted()).isTrue();
        assertThat(none.resolve(t).columnsRestricted()).as("none mode").isFalse();

        t.setColumnPolicies("{\"X\": [\"a\", \"b\"]}");
        assertThat(oidc.resolve(t).columnsRestricted()).as("a list naming every column").isFalse();

        t.setColumnPolicies("{\"X\": [\"a\"]}");
        authenticateWith(EnumSet.of(Privilege.TABLE_LIST), "other");
        assertThat(oidc.resolve(t).columnsRestricted()).as("no governing role").isFalse();
    }

    private static ColumnMappingEntity column(String name) {
        final ColumnMappingEntity column = new ColumnMappingEntity();
        column.setTableName("t");
        column.setName(name);
        column.setSourceName(name);
        return column;
    }

    /** An enrichment {@code e} storing exactly {@code grants}, of a source {@code src} storing {@code sourceGrants}. */
    private TableDefinitionEntity enrichment(Map<Privilege, List<String>> grants, Map<Privilege, List<String>> sourceGrants) {
        final TableDefinitionEntity source = table(sourceGrants);
        source.setName("src");
        when(definitions.findById("src")).thenReturn(Optional.of(source));
        final TableDefinitionEntity enrichment = table(grants);
        enrichment.setName("e");
        enrichment.setType(TableType.ENRICHMENT);
        enrichment.setSourceTable("src");
        return enrichment;
    }

    @Test
    @DisplayName("an enrichment reports its source's read side and its own write and modify")
    void enrichmentReportsItsSourcesReadSide() {
        authenticateWith(EnumSet.noneOf(Privilege.class), "x");
        final TableDefinitionEntity e = enrichment(Map.of(Privilege.TABLE_WRITE, List.of("x")),
                Map.of(Privilege.TABLE_READ, List.of("x"), Privilege.TABLE_SENSITIVE, List.of("x")));
        final TableDefinitionEntity source = definitions.findById("src").orElseThrow();
        when(rowPolicies.isConfined(source)).thenReturn(true);

        assertThat(oidc.resolve(e)).isEqualTo(new EffectivePermissions(true, true, true, true, true, false, false, true, false));
    }

    @Test
    @DisplayName("a source modify holder is reported get on the enrichment, the enrichment's own modify likewise")
    void modifyOnEitherSideReportsGet() {
        authenticateWith(EnumSet.noneOf(Privilege.class), "editor");
        assertThat(oidc.resolve(enrichment(Map.of(), Map.of(Privilege.TABLE_MODIFY, List.of("editor")))))
                .isEqualTo(new EffectivePermissions(true, true, false, false, false, false));
        assertThat(oidc.resolve(enrichment(Map.of(Privilege.TABLE_MODIFY, List.of("editor")), Map.of())))
                .isEqualTo(new EffectivePermissions(true, true, false, false, false, true));
    }

    @Test
    @DisplayName("a leftover read-side list on an enrichment reports nothing; a leftover policy reports row-restricted")
    void leftoverStateOnAnEnrichment() {
        authenticateWith(EnumSet.noneOf(Privilege.class), "r");
        final TableDefinitionEntity e = enrichment(Map.of(Privilege.TABLE_READ, List.of("r"), Privilege.TABLE_SENSITIVE, List.of("r")),
                Map.of());
        assertThat(oidc.resolve(e)).isEqualTo(EffectivePermissions.uniform(false));

        final TableDefinitionEntity readable = enrichment(Map.of(), Map.of(Privilege.TABLE_READ, List.of("r")));
        when(rowPolicies.isConfined(readable)).thenReturn(true);
        assertThat(oidc.resolve(readable)).isEqualTo(new EffectivePermissions(true, true, true, false, false, false, false, true, false));
    }

    @Test
    @DisplayName("a '*' in the source's modify reports no get on its enrichment")
    void wildcardInSourceModifyReportsNothing() {
        authenticateWith(EnumSet.noneOf(Privilege.class));
        assertThat(oidc.resolve(enrichment(Map.of(), Map.of(Privilege.TABLE_MODIFY, List.of(SecurityClaimsExtractor.ANYONE)))))
                .isEqualTo(EffectivePermissions.uniform(false));
    }

    @Test
    @DisplayName("an enrichment whose source is missing reports no read side")
    void missingSourceReportsNoReadSide() {
        authenticateWith(EnumSet.of(Privilege.TABLE_GET), "x");
        final TableDefinitionEntity e = enrichment(Map.of(Privilege.TABLE_READ, List.of("x")), Map.of(Privilege.TABLE_READ, List.of("x")));
        when(definitions.findById("src")).thenReturn(Optional.empty());
        assertThat(oidc.resolve(e)).isEqualTo(new EffectivePermissions(false, true, false, false, false, false));
    }

    @Test
    @DisplayName("a system enrichment keeps its own write and modify hard false")
    void systemEnrichmentRefusesWrites() {
        authenticateWith(EnumSet.allOf(Privilege.class));
        final TableDefinitionEntity e = enrichment(Map.of(), Map.of());
        e.setSystem(true);
        assertThat(oidc.resolve(e)).isEqualTo(new EffectivePermissions(true, true, true, true, false, false, true, false, false));
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

        assertThat(oidc.resolve(def)).isEqualTo(new EffectivePermissions(true, true, true, true, false, false, true, false, false));
        assertThat(none.resolve(def))
                .as("none mode enforces nothing, but a system table's write/modify are still hard false")
                .isEqualTo(new EffectivePermissions(true, true, true, true, false, false, true, false, false));
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
    @DisplayName("a '*' stored in write or modify confers nothing, while '*' in read still grants")
    void storedWildcardInModifyingListConfersNothing() {
        authenticateWith(EnumSet.noneOf(Privilege.class));
        final TableDefinitionEntity def = table(Map.of(
                Privilege.TABLE_WRITE, List.of(SecurityClaimsExtractor.ANYONE),
                Privilege.TABLE_MODIFY, List.of(SecurityClaimsExtractor.ANYONE)));
        assertThat(oidc.resolve(def)).isEqualTo(EffectivePermissions.uniform(false));
        assertThat(TableCallerPermissions.isGranted(def.getAccess(), Privilege.TABLE_WRITE, Set.of(SecurityClaimsExtractor.ANYONE))).isFalse();

        def.getAccess().setRolesFor(Privilege.TABLE_READ, List.of(SecurityClaimsExtractor.ANYONE));
        assertThat(oidc.resolve(def)).isEqualTo(new EffectivePermissions(true, true, true, false, false, false));
    }

    @Test
    @DisplayName("grant follows the caller's global TABLE_GRANT, on system tables and enrichments alike")
    void grantFollowsGlobalTableGrant() {
        authenticateWith(EnumSet.of(Privilege.TABLE_GET, Privilege.TABLE_GRANT));
        final TableDefinitionEntity plain = table(Map.of());
        final TableDefinitionEntity system = table(Map.of());
        system.setSystem(true);
        final TableDefinitionEntity e = enrichment(Map.of(), Map.of());
        assertThat(oidc.resolve(plain)).isEqualTo(new EffectivePermissions(false, true, false, false, false, false, true, false, false));
        assertThat(oidc.resolve(system).grant()).as("system table").isTrue();
        assertThat(oidc.resolve(e).grant()).as("enrichment").isTrue();

        authenticateWith(EnumSet.of(Privilege.TABLE_GET));
        assertThat(oidc.resolve(plain).grant()).isFalse();
        assertThat(oidc.resolve(system).grant()).as("system table").isFalse();
        assertThat(oidc.resolve(e).grant()).as("enrichment").isFalse();
    }

    @Test
    @DisplayName("an entry carrying every privilege does not confer grant")
    void entryDoesNotConferGrant() {
        authenticateWith(EnumSet.noneOf(Privilege.class), "x");
        final TableDefinitionEntity def = table(Map.of(Privilege.TABLE_GET, List.of("x"), Privilege.TABLE_READ, List.of("x"),
                Privilege.TABLE_SENSITIVE, List.of("x"), Privilege.TABLE_WRITE, List.of("x"), Privilege.TABLE_MODIFY, List.of("x")));
        assertThat(oidc.resolve(def)).isEqualTo(new EffectivePermissions(true, true, true, true, true, true, false, false, false));
    }

    @Test
    @DisplayName("none mode reports grant on every table, a system table included")
    void noneModeReportsGrant() {
        final TableDefinitionEntity system = table(Map.of());
        system.setSystem(true);
        assertThat(none.resolve(table(Map.of())).grant()).isTrue();
        assertThat(none.resolve(system).grant()).isTrue();
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
