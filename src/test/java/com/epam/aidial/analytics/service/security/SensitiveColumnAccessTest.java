package com.epam.aidial.analytics.service.security;

import com.epam.aidial.analytics.configuration.JsonMapperConfiguration;
import com.epam.aidial.analytics.service.query.ComputedFields;
import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.ComparisonOp;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.ValueType;
import com.epam.aidial.analytics.service.security.SensitiveColumnAccess.ColumnVisibility;
import com.epam.aidial.analytics.service.table.ColumnMappingEntity;
import com.epam.aidial.analytics.service.table.TableAccess;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import com.epam.aidial.analytics.service.table.TableType;
import com.epam.aidial.analytics.service.table.grant.ColumnPolicyCodec;
import com.epam.aidial.analytics.service.table.grant.GoverningAccess;
import com.epam.aidial.analytics.service.table.grant.TableAccessEvaluator;
import com.epam.aidial.analytics.service.table.rowpolicy.RowPolicy;
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

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Policy matrix for {@link SensitiveColumnAccess}, which is now decided <b>per owning table</b>.
 *
 * <p>Each column is judged on its owning table, and an enrichment's read side is its source's, so within one
 * flattened entity the source's grants decide both tables. The {@code none}-mode case still fails open, and it
 * reads the mode from the one resolver every other gate reads, which is what stops {@code SECURITY_MODE=OIDC}
 * from leaving this open while HTTP enforces.
 */
class SensitiveColumnAccessTest {

    private static final String SOURCE = "dial_usage_log";
    private static final String ENRICHMENT = "usage_insights";

    private final SecurityClaimsExtractor claims = new SecurityClaimsExtractor();
    private final TableDefinitionRepository definitions = mock(TableDefinitionRepository.class);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private final ProviderRolePrivileges rolePrivileges = mock(ProviderRolePrivileges.class);
    private final JsonMapper jsonMapper = new JsonMapperConfiguration().jsonMapper();
    private final RowPolicyCodec rowPolicyCodec = new RowPolicyCodec(jsonMapper);
    private final ColumnPolicyCodec columnPolicyCodec = new ColumnPolicyCodec(jsonMapper);

    private SensitiveColumnAccess access(SecurityMode mode) {
        final GoverningAccess governing = new GoverningAccess(definitions);
        return new SensitiveColumnAccess(new TableAccessEvaluator(definitions, governing, claims), resolver(mode), definitions,
                governing, claims, rolePrivileges, rowPolicyCodec, columnPolicyCodec);
    }

    private static SecurityModeResolver resolver(SecurityMode mode) {
        return new SecurityModeResolver(mode.code());
    }

    private static void authenticateWith(Set<Privilege> privileges, String... providerRoles) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "caller", "n/a", CallerAuthorities.build(privileges, List.of(providerRoles))));
    }

    /** A table whose {@code sensitive} list names {@code roles}. */
    private void tableGranting(String name, Privilege key, String... roles) {
        final TableDefinitionEntity definition = new TableDefinitionEntity();
        definition.setName(name);
        final TableAccess grants = new TableAccess();
        grants.setRolesFor(key, List.of(roles));
        definition.setAccess(grants);
        when(definitions.findById(name)).thenReturn(Optional.of(definition));
    }

    @Test
    @DisplayName("none mode fails open, with no privileges and no grants")
    void securityDisabledSeesSensitive() {
        when(definitions.findById(anyString())).thenReturn(Optional.empty());
        final SensitiveColumnAccess access = access(SecurityMode.NONE);
        assertThat(access.maySeeSensitive(SOURCE)).isTrue();
        assertThat(access.mayRead(SOURCE)).isTrue();
        assertThat(access.mayGet(SOURCE)).isTrue();
        assertThat(access.mayList(SOURCE)).isTrue();
    }

    @Test
    @DisplayName("the global clearance sees every table's sensitive columns, and reads them only with a read")
    void globalClearanceSeesSensitive() {
        when(definitions.findById(anyString())).thenReturn(Optional.empty());
        authenticateWith(EnumSet.of(Privilege.TABLE_SENSITIVE));
        final SensitiveColumnAccess access = access(SecurityMode.OIDC);
        assertThat(access.maySeeSensitive(SOURCE)).isTrue();
        assertThat(access.mayReadSensitive(SOURCE))
                .as("the clearance is not a read: a path with no read bar of its own must not admit it alone")
                .isFalse();
    }

    @Test
    @DisplayName("a global read combines with a per-table clearance")
    void globalReadAndPerTableClearanceCombine() {
        tableGranting(SOURCE, Privilege.TABLE_SENSITIVE, "pii-readers");
        when(definitions.findById(ENRICHMENT)).thenReturn(Optional.empty());
        authenticateWith(EnumSet.of(Privilege.TABLE_READ, Privilege.TABLE_GET, Privilege.TABLE_LIST), "pii-readers");
        final SensitiveColumnAccess access = access(SecurityMode.OIDC);
        assertThat(access.mayReadSensitive(SOURCE)).isTrue();
        assertThat(access.mayReadSensitive(ENRICHMENT)).isFalse();
    }

    @Test
    @DisplayName("global TABLE_READ reads but does not see sensitive columns")
    void globalReadDoesNotSeeSensitive() {
        when(definitions.findById(anyString())).thenReturn(Optional.empty());
        authenticateWith(EnumSet.of(Privilege.TABLE_READ, Privilege.TABLE_GET, Privilege.TABLE_LIST));
        final SensitiveColumnAccess access = access(SecurityMode.OIDC);
        assertThat(access.mayRead(SOURCE)).isTrue();
        assertThat(access.maySeeSensitive(SOURCE)).isFalse();
    }

    @Test
    @DisplayName("an enrichment's read and clearance are its source's")
    void enrichmentFollowsItsSource() {
        final TableDefinitionEntity source = new TableDefinitionEntity();
        source.setName(SOURCE);
        source.getAccess().setRolesFor(Privilege.TABLE_READ, List.of("analysts", "pii-readers"));
        source.getAccess().setRolesFor(Privilege.TABLE_SENSITIVE, List.of("pii-readers"));
        when(definitions.findById(SOURCE)).thenReturn(Optional.of(source));
        final TableDefinitionEntity enrichment = new TableDefinitionEntity();
        enrichment.setName(ENRICHMENT);
        enrichment.setType(TableType.ENRICHMENT);
        enrichment.setSourceTable(SOURCE);
        when(definitions.findById(ENRICHMENT)).thenReturn(Optional.of(enrichment));

        authenticateWith(EnumSet.noneOf(Privilege.class), "pii-readers");
        assertThat(access(SecurityMode.OIDC).mayReadSensitive(ENRICHMENT))
                .as("the source's read and clearance reach an enrichment that grants nothing itself")
                .isTrue();

        authenticateWith(EnumSet.noneOf(Privilege.class), "analysts");
        final SensitiveColumnAccess access = access(SecurityMode.OIDC);
        assertThat(access.mayRead(ENRICHMENT)).isTrue();
        assertThat(access.maySeeSensitive(ENRICHMENT)).isFalse();
    }

    @Test
    @DisplayName("a write grant confers no read and no sensitive read")
    void writeGrantConfersNothing() {
        tableGranting(SOURCE, Privilege.TABLE_WRITE, "ingest-bot");
        authenticateWith(EnumSet.noneOf(Privilege.class), "ingest-bot");

        final SensitiveColumnAccess access = access(SecurityMode.OIDC);
        assertThat(access.mayList(SOURCE)).isFalse();
        assertThat(access.mayGet(SOURCE)).isFalse();
        assertThat(access.mayRead(SOURCE)).isFalse();
        assertThat(access.maySeeSensitive(SOURCE)).isFalse();
    }

    // --- column visibility -------------------------------------------------------------------------

    @Test
    @DisplayName("a role sees only its listed columns")
    void roleSeesOnlyItsListedColumns() {
        final TableDefinitionEntity t = table(SOURCE);
        entry(t, "X", List.of(Privilege.TABLE_READ), null, List.of("a", "b"));
        authenticateWith(EnumSet.noneOf(Privilege.class), "X");

        assertThat(access(SecurityMode.OIDC).visibleColumns(t).visible()).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    @DisplayName("two roles' lists are combined when neither carries a row policy")
    void unionWithoutRowPolicies() {
        final TableDefinitionEntity t = table(SOURCE);
        entry(t, "X", List.of(Privilege.TABLE_READ), null, List.of("a"));
        entry(t, "Y", List.of(Privilege.TABLE_READ), null, List.of("b"));
        authenticateWith(EnumSet.noneOf(Privilege.class), "X", "Y");

        assertThat(access(SecurityMode.OIDC).visibleColumns(t).visible()).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    @DisplayName("one role's columns are not shown on another role's rows")
    void confiningRolesIntersect() {
        final TableDefinitionEntity t = table(SOURCE);
        entry(t, "R", List.of(Privilege.TABLE_READ), "P1", List.of("a", "project"));
        entry(t, "U", List.of(Privilege.TABLE_READ), "P2", null);
        authenticateWith(EnumSet.noneOf(Privilege.class), "R", "U");

        assertThat(access(SecurityMode.OIDC).visibleColumns(t).visible()).containsExactlyInAnyOrder("a", "project");
    }

    @Test
    @DisplayName("a role with no row policy shows the columns it allows on every row")
    void rowUnrestrictedRoleWidens() {
        final TableDefinitionEntity t = table(SOURCE);
        entry(t, "R", List.of(Privilege.TABLE_READ), "P1", List.of("a", "project"));
        entry(t, "U", List.of(Privilege.TABLE_READ), null, null);
        authenticateWith(EnumSet.noneOf(Privilege.class), "R", "U");

        assertThat(access(SecurityMode.OIDC).visibleColumns(t).isAll()).isTrue();
    }

    @Test
    @DisplayName("a column every confining role lists is visible")
    void intersectionOfConfiningRoles() {
        final TableDefinitionEntity t = table(SOURCE);
        entry(t, "R", List.of(Privilege.TABLE_READ), "P1", List.of("a", "c"));
        entry(t, "U", List.of(Privilege.TABLE_READ), "P2", List.of("b", "c"));
        authenticateWith(EnumSet.noneOf(Privilege.class), "R", "U");

        assertThat(access(SecurityMode.OIDC).visibleColumns(t).visible()).containsExactly("c");
    }

    @Test
    @DisplayName("a rows-only entry without a read path adds rows but no columns")
    void rowsOnlyEntryTakesNoPart() {
        final TableDefinitionEntity t = table(SOURCE);
        entry(t, "X", List.of(Privilege.TABLE_READ), "P1", List.of("a", "project"));
        entry(t, "N", List.of(), "P2", null);
        authenticateWith(EnumSet.noneOf(Privilege.class), "X", "N");

        assertThat(access(SecurityMode.OIDC).visibleColumns(t).visible()).containsExactlyInAnyOrder("a", "project");
    }

    @Test
    @DisplayName("an unrestricted reading role through a global mapping lifts the restriction")
    void globallyMappedReaderLifts() {
        final TableDefinitionEntity t = table(SOURCE);
        entry(t, "X", List.of(Privilege.TABLE_READ), null, List.of("a"));
        when(rolePrivileges.holdsGlobally("Y", Privilege.TABLE_READ)).thenReturn(true);
        authenticateWith(EnumSet.of(Privilege.TABLE_READ, Privilege.TABLE_GET, Privilege.TABLE_LIST), "X", "Y");

        assertThat(access(SecurityMode.OIDC).visibleColumns(t).isAll()).isTrue();
    }

    @Test
    @DisplayName("a role that only gets the table does not widen a reader's columns")
    void getOnlyRoleDoesNotWiden() {
        final TableDefinitionEntity t = table(SOURCE);
        entry(t, "X", List.of(Privilege.TABLE_READ), null, List.of("a"));
        entry(t, "G", List.of(Privilege.TABLE_GET), null, null);
        authenticateWith(EnumSet.noneOf(Privilege.class), "X", "G");

        assertThat(access(SecurityMode.OIDC).visibleColumns(t).visible()).containsExactly("a");
    }

    @Test
    @DisplayName("a global reader is confined by its role's entry with no privileges")
    void globalReaderConfinedByEntryWithNoPrivileges() {
        final TableDefinitionEntity t = table(SOURCE);
        entry(t, "X", List.of(), null, List.of("a"));
        when(rolePrivileges.holdsGlobally("X", Privilege.TABLE_READ)).thenReturn(true);
        authenticateWith(EnumSet.of(Privilege.TABLE_READ, Privilege.TABLE_GET, Privilege.TABLE_LIST), "X");

        assertThat(access(SecurityMode.OIDC).visibleColumns(t).visible()).containsExactly("a");
    }

    @Test
    @DisplayName("a * entry with columns and no privileges confines no one")
    void everyoneEntryWithoutPrivilegesConfinesNoOne() {
        final TableDefinitionEntity t = table(SOURCE);
        entry(t, "*", List.of(), null, List.of("a"));
        entry(t, "X", List.of(Privilege.TABLE_READ), null, null);
        authenticateWith(EnumSet.noneOf(Privilege.class), "X");

        assertThat(access(SecurityMode.OIDC).visibleColumns(t).isAll()).isTrue();
    }

    @Test
    @DisplayName("a caller that only gets the table is shown its listed columns")
    void getOnlyCaller() {
        final TableDefinitionEntity t = table(SOURCE);
        entry(t, "G", List.of(Privilege.TABLE_GET), null, List.of("a"));
        authenticateWith(EnumSet.noneOf(Privilege.class), "G");

        assertThat(access(SecurityMode.OIDC).visibleColumns(t).visible()).containsExactly("a");
    }

    @Test
    @DisplayName("a caller with no governing role is not narrowed, nor is none mode or a caller without roles")
    void noGoverningRoleIsNotNarrowed() {
        final TableDefinitionEntity t = table(SOURCE);
        entry(t, "X", List.of(Privilege.TABLE_READ), null, List.of("a"));
        authenticateWith(EnumSet.of(Privilege.TABLE_LIST), "someone-else");
        assertThat(access(SecurityMode.OIDC).visibleColumns(t).isAll()).isTrue();

        authenticateWith(EnumSet.of(Privilege.TABLE_LIST), "X");
        assertThat(access(SecurityMode.NONE).visibleColumns(t).isAll()).isTrue();

        SecurityContextHolder.clearContext();
        assertThat(access(SecurityMode.OIDC).visibleColumns(t).isAll()).isTrue();
    }

    @Test
    @DisplayName("an enrichment's columns follow its source's lists by qualified name")
    void enrichmentByQualifiedName() {
        final TableDefinitionEntity source = table(SOURCE);
        entry(source, "X", List.of(Privilege.TABLE_READ), null, List.of("project_id", ENRICHMENT + ".display_name"));
        final TableDefinitionEntity enrichment = enrichment();
        authenticateWith(EnumSet.noneOf(Privilege.class), "X");

        final ColumnVisibility visibility = access(SecurityMode.OIDC).visibleColumns(enrichment);
        assertThat(visibility.visible()).containsExactly("display_name");
        assertThat(access(SecurityMode.OIDC).visibleColumns(source).allows("project_id")).isTrue();
    }

    @Test
    @DisplayName("an enrichment maintainer that may not read the source sees every column of the enrichment")
    void enrichmentMaintainer() {
        final TableDefinitionEntity source = table(SOURCE);
        entry(source, "X", List.of(Privilege.TABLE_READ), null, List.of("project_id"));
        final TableDefinitionEntity enrichment = enrichment();
        enrichment.getAccess().setModify(List.of("M"));
        authenticateWith(EnumSet.noneOf(Privilege.class), "M");

        assertThat(access(SecurityMode.OIDC).visibleColumns(enrichment).isAll()).isTrue();
    }

    @Test
    @DisplayName("a computed field is visible exactly when the columns it returns are")
    void computedFieldsFollowTheirColumns() {
        final ColumnMappingEntity body = column("request_body");
        final ColumnMappingEntity markers = column("request_cache_control");
        final List<ColumnMappingEntity> columns = List.of(body, markers);
        final ColumnMappingEntity computed = ComputedFields.of(ComputedFields.TABLE, columns).getFirst().column();

        assertThat(new ColumnVisibility(Set.of("request_body")).allows(computed, columns)).isFalse();
        assertThat(new ColumnVisibility(Set.of("request_body", "request_cache_control")).allows(computed, columns)).isTrue();
        assertThat(ColumnVisibility.ALL.allows(computed, columns)).isTrue();
    }

    private TableDefinitionEntity table(String name) {
        final TableDefinitionEntity definition = new TableDefinitionEntity();
        definition.setName(name);
        definition.setType(TableType.SOURCE);
        when(definitions.findById(name)).thenReturn(Optional.of(definition));
        return definition;
    }

    private TableDefinitionEntity enrichment() {
        final TableDefinitionEntity enrichment = new TableDefinitionEntity();
        enrichment.setName(ENRICHMENT);
        enrichment.setType(TableType.ENRICHMENT);
        enrichment.setSourceTable(SOURCE);
        when(definitions.findById(ENRICHMENT)).thenReturn(Optional.of(enrichment));
        return enrichment;
    }

    /** Stores {@code role}'s entry on {@code table}: privileges, a row policy on {@code project} when {@code rowsProject} is set, and columns. */
    private void entry(TableDefinitionEntity table, String role, List<Privilege> privileges, String rowsProject, List<String> columns) {
        privileges.forEach(privilege -> {
            final List<String> roles = new ArrayList<>(table.getAccess().rolesFor(privilege));
            roles.add(role);
            table.getAccess().setRolesFor(privilege, roles);
        });
        if (rowsProject != null) {
            final Map<String, RowPolicy> policies = new TreeMap<>(rowPolicyCodec.read(table.getRowPolicies()));
            policies.put(role, new RowPolicy(new ComparisonNode(ComparisonOp.EQ,
                    List.of(new FieldExpr("project"), new ValueExpr(ValueType.STRING, rowsProject)))));
            table.setRowPolicies(rowPolicyCodec.write(policies));
        }
        if (columns != null) {
            final Map<String, List<String>> policies = new TreeMap<>(columnPolicyCodec.read(table.getColumnPolicies()));
            policies.put(role, columns);
            table.setColumnPolicies(columnPolicyCodec.write(policies));
        }
    }

    private static ColumnMappingEntity column(String name) {
        final ColumnMappingEntity column = new ColumnMappingEntity();
        column.setTableName(ComputedFields.TABLE);
        column.setName(name);
        column.setSourceName(name);
        return column;
    }

    @Test
    @DisplayName("unauthenticated in oidc mode sees nothing")
    void unauthenticatedInOidcModeSeesNothing() {
        when(definitions.findById(anyString())).thenReturn(Optional.empty());
        final SensitiveColumnAccess access = access(SecurityMode.OIDC);
        assertThat(access.maySeeSensitive(SOURCE)).isFalse();
        assertThat(access.mayRead(SOURCE)).isFalse();
    }
}
