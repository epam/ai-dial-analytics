package com.epam.aidial.analytics.service.security;

import com.epam.aidial.analytics.service.table.TableAccess;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
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
 * <p>The per-table cases are the point: one flattened entity can legitimately expose the source's sensitive
 * columns and hide an enrichment's, so a single global answer — what this used to give — cannot express the
 * policy. The {@code none}-mode case still fails open, and it now reads the mode from the one resolver every
 * other gate reads, which is what stops {@code SECURITY_MODE=OIDC} from leaving this open while HTTP
 * enforces.
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
                new TableAccessEvaluator(definitions, claims), resolver(mode));
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
    @DisplayName("the decision is per owning table")
    void decidedPerOwningTable() {
        tableGranting(SOURCE, Privilege.TABLE_SENSITIVE, "pii-readers");
        tableGranting(ENRICHMENT, Privilege.TABLE_READ, "pii-readers");
        authenticateWith(EnumSet.noneOf(Privilege.class), "pii-readers");

        final SensitiveColumnAccess access = access(SecurityMode.OIDC);
        assertThat(access.maySeeSensitive(SOURCE)).isTrue();
        assertThat(access.maySeeSensitive(ENRICHMENT))
                .as("the same caller may read the enrichment but not its sensitive columns")
                .isFalse();
        assertThat(access.mayRead(ENRICHMENT)).isTrue();
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
