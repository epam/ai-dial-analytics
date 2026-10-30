package com.epam.aidial.analytics.service.enrichment.support;

import com.epam.aidial.analytics.exception.SensitiveColumnNotEntitledException;
import com.epam.aidial.analytics.service.query.CatalogQueryService;
import com.epam.aidial.analytics.service.security.SensitiveColumnAccess;
import com.epam.aidial.analytics.service.table.ColumnMappingEntity;
import com.epam.aidial.analytics.service.table.ColumnMappingRepository;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.grant.TableAccessEvaluator;
import com.epam.aidial.analytics.web.security.Privilege;
import com.epam.aidial.analytics.web.security.SecurityMode;
import com.epam.aidial.analytics.web.security.SecurityModeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The runner's data-plane guard asks for <b>read and clearance</b>, not the clearance alone.
 *
 * <p>Nothing on these paths enforces {@code TABLE_READ} on the column's owning table first — an enrichment
 * reached through the grain join is never gated by the pipeline endpoint, and the aggregate validator
 * enforces per owning table through this guard — so the clearance by itself would let a caller with no read
 * reach sensitive values. The real {@link SensitiveColumnAccess} is used, over a stubbed evaluator, so the
 * test pins the predicate the guard actually calls rather than a mock's answer to it.
 */
class SensitiveProjectionGuardTest {

    private static final String SOURCE = "dial_usage_log";

    private final TableAccessEvaluator evaluator = mock(TableAccessEvaluator.class);
    private final ColumnMappingRepository columns = mock(ColumnMappingRepository.class);
    private final SensitiveProjectionGuard guard = new SensitiveProjectionGuard(
            new SensitiveColumnAccess(evaluator, new SecurityModeResolver(SecurityMode.OIDC.code())),
            columns, mock(CatalogQueryService.class));

    @BeforeEach
    void sourceWithOneSensitiveColumn() {
        final ColumnMappingEntity claims = new ColumnMappingEntity();
        claims.setTableName(SOURCE);
        claims.setName("jwt_claims");
        claims.setSourceName("jwt_claims");
        claims.setSensitive(true);
        when(columns.findByTableNameOrderByOrdinal(SOURCE)).thenReturn(List.of(claims));
    }

    @Test
    @DisplayName("the clearance without a read does not pass the guard")
    void clearanceWithoutReadIsRefused() {
        when(evaluator.isAllowed(SOURCE, Privilege.TABLE_SENSITIVE)).thenReturn(true);
        when(evaluator.isAllowed(SOURCE, Privilege.TABLE_READ)).thenReturn(false);

        assertThatThrownBy(() -> guard.enforce(SOURCE, List.of("jwt_claims"), "scan"))
                .isInstanceOf(SensitiveColumnNotEntitledException.class)
                .hasMessageContaining("TABLE_READ and TABLE_SENSITIVE");
    }

    @Test
    @DisplayName("a read without the clearance does not pass the guard")
    void readWithoutClearanceIsRefused() {
        when(evaluator.isAllowed(SOURCE, Privilege.TABLE_SENSITIVE)).thenReturn(false);
        when(evaluator.isAllowed(SOURCE, Privilege.TABLE_READ)).thenReturn(true);

        assertThatThrownBy(() -> guard.enforce(SOURCE, List.of("jwt_claims"), "scan"))
                .isInstanceOf(SensitiveColumnNotEntitledException.class);
    }

    @Test
    @DisplayName("read and clearance together pass, and a non-sensitive projection needs neither")
    void readAndClearancePass() {
        when(evaluator.isAllowed(SOURCE, Privilege.TABLE_SENSITIVE)).thenReturn(true);
        when(evaluator.isAllowed(SOURCE, Privilege.TABLE_READ)).thenReturn(true);
        assertThatCode(() -> guard.enforce(SOURCE, List.of("jwt_claims"), "scan")).doesNotThrowAnyException();

        when(evaluator.isAllowed(SOURCE, Privilege.TABLE_SENSITIVE)).thenReturn(false);
        when(evaluator.isAllowed(SOURCE, Privilege.TABLE_READ)).thenReturn(false);
        assertThatCode(() -> guard.enforce(SOURCE, List.of("event_id"), "scan")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a computed field is judged by the sensitive flag it carries, like the body column it rebuilds")
    void computedFieldOfAnEnrichmentIsRefusedLikeTheBody() {
        final String payload = "dial_usage_log_payload";
        final ColumnMappingEntity body = new ColumnMappingEntity();
        body.setTableName(payload);
        body.setName("request_body");
        body.setSourceName("request_body");
        body.setSensitive(true);
        when(columns.findByTableNameOrderByOrdinal(payload)).thenReturn(List.of(body));
        final TableDefinitionEntity enrichment = new TableDefinitionEntity();
        enrichment.setName(payload);
        final CatalogQueryService catalog = mock(CatalogQueryService.class);
        when(catalog.activeEnrichmentsOf(SOURCE)).thenReturn(List.of(enrichment));
        final SensitiveProjectionGuard entityGuard = new SensitiveProjectionGuard(
                new SensitiveColumnAccess(evaluator, new SecurityModeResolver(SecurityMode.OIDC.code())), columns, catalog);
        when(evaluator.isAllowed(payload, Privilege.TABLE_SENSITIVE)).thenReturn(false);
        when(evaluator.isAllowed(payload, Privilege.TABLE_READ)).thenReturn(true);

        assertThatThrownBy(() -> entityGuard.enforceEntity(SOURCE, List.of(payload + ".request_body_full"), "scan"))
                .isInstanceOf(SensitiveColumnNotEntitledException.class)
                .hasMessageContaining(payload + ".request_body_full");
        assertThatThrownBy(() -> entityGuard.enforceEntity(SOURCE, List.of(payload + ".request_body_rebuild"), "group-rows"))
                .isInstanceOf(SensitiveColumnNotEntitledException.class);
    }

    @Test
    @DisplayName("none mode fails open")
    void noneModeFailsOpen() {
        final SensitiveProjectionGuard open = new SensitiveProjectionGuard(
                new SensitiveColumnAccess(evaluator, new SecurityModeResolver(SecurityMode.NONE.code())),
                columns, mock(CatalogQueryService.class));
        assertThatCode(() -> open.enforce(SOURCE, List.of("jwt_claims"), "scan")).doesNotThrowAnyException();
    }
}
