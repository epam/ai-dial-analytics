package com.epam.aidial.analytics.service.security;

import com.epam.aidial.analytics.service.table.TableAccess;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import com.epam.aidial.analytics.service.table.TableType;
import com.epam.aidial.analytics.service.table.grant.GoverningAccess;
import com.epam.aidial.analytics.service.table.grant.TableAccessEvaluator;
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
import java.util.Optional;
import java.util.Set;

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

    private SensitiveColumnAccess access(SecurityMode mode) {
        return new SensitiveColumnAccess(
                new TableAccessEvaluator(definitions, new GoverningAccess(definitions), claims), resolver(mode));
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

    @Test
    @DisplayName("unauthenticated in oidc mode sees nothing")
    void unauthenticatedInOidcModeSeesNothing() {
        when(definitions.findById(anyString())).thenReturn(Optional.empty());
        final SensitiveColumnAccess access = access(SecurityMode.OIDC);
        assertThat(access.maySeeSensitive(SOURCE)).isFalse();
        assertThat(access.mayRead(SOURCE)).isFalse();
    }
}
