package com.epam.aidial.analytics.service.enrichment.support;

import com.epam.aidial.analytics.exception.ColumnNotEntitledException;
import com.epam.aidial.analytics.exception.SensitiveColumnNotEntitledException;
import com.epam.aidial.analytics.service.query.CatalogQueryService;
import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.ComparisonOp;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.ValueType;
import com.epam.aidial.analytics.service.security.SensitiveColumnAccess;
import com.epam.aidial.analytics.service.security.SensitiveColumnAccess.ColumnVisibility;
import com.epam.aidial.analytics.service.security.SensitiveColumnAccessFixture;
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
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
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
            SensitiveColumnAccessFixture.withoutColumnPolicies(evaluator, new SecurityModeResolver(SecurityMode.OIDC.code())),
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
                SensitiveColumnAccessFixture.withoutColumnPolicies(evaluator, new SecurityModeResolver(SecurityMode.OIDC.code())), columns, catalog);
        when(evaluator.isAllowed(payload, Privilege.TABLE_SENSITIVE)).thenReturn(false);
        when(evaluator.isAllowed(payload, Privilege.TABLE_READ)).thenReturn(true);

        assertThatThrownBy(() -> entityGuard.enforceEntity(SOURCE, List.of(payload + ".request_body_full"), "scan"))
                .isInstanceOf(SensitiveColumnNotEntitledException.class)
                .hasMessageContaining(payload + ".request_body_full")
                .as("the refusal names the source, whose grants the enrichment follows")
                .hasMessageContaining("its source '" + SOURCE + "'");
        assertThatThrownBy(() -> entityGuard.enforceEntity(SOURCE, List.of(payload + ".request_body_rebuild"), "group-rows"))
                .isInstanceOf(SensitiveColumnNotEntitledException.class);
    }

    @Test
    @DisplayName("a policy-hidden column is refused column_not_entitled under either spelling, before the clearance")
    void policyHiddenColumnIsRefusedFirst() {
        final ColumnMappingEntity version = new ColumnMappingEntity();
        version.setTableName(SOURCE);
        version.setName("ingest_time");
        version.setSourceName("ingested");
        final ColumnMappingEntity claims = columns.findByTableNameOrderByOrdinal(SOURCE).getFirst();
        when(columns.findByTableNameOrderByOrdinal(SOURCE)).thenReturn(List.of(claims, version));
        final SensitiveColumnAccess access = mock(SensitiveColumnAccess.class);
        when(access.visibleColumns(SOURCE)).thenReturn(new ColumnVisibility(Set.of("event_id")));
        final SensitiveProjectionGuard restricted = new SensitiveProjectionGuard(access, columns, mock(CatalogQueryService.class));

        assertThatThrownBy(() -> restricted.enforce(SOURCE, List.of("ingested"), "scan"))
                .isInstanceOf(ColumnNotEntitledException.class)
                .hasMessageContaining("'ingest_time'")
                .hasMessageNotContaining("'ingested'");
        assertThatThrownBy(() -> restricted.enforce(SOURCE, List.of("ingest_time"), "scan"))
                .isInstanceOf(ColumnNotEntitledException.class);
        assertThatThrownBy(() -> restricted.enforce(SOURCE, List.of("jwt_claims"), "scan"))
                .as("hidden and unentitled-sensitive is column_not_entitled")
                .isInstanceOf(ColumnNotEntitledException.class);
        assertThat(restricted.reattribute(SOURCE, "scan", new IllegalStateException("parse")))
                .isInstanceOf(ColumnNotEntitledException.class);
    }

    @Test
    @DisplayName("a masked column is refused column_not_entitled as a hidden one, while a parse failure judges hidden columns only")
    void maskedColumnIsRefusedAsHiddenAndParseFailureJudgesHiddenOnly() {
        final ColumnMappingEntity version = new ColumnMappingEntity();
        version.setTableName(SOURCE);
        version.setName("ingest_time");
        version.setSourceName("ingested");
        final ColumnMappingEntity claims = columns.findByTableNameOrderByOrdinal(SOURCE).getFirst();
        when(columns.findByTableNameOrderByOrdinal(SOURCE)).thenReturn(List.of(claims, version));
        final FilterNode p1 = new ComparisonNode(ComparisonOp.EQ, List.of(new FieldExpr("project_id"), new ValueExpr(ValueType.STRING, "P1")));
        final SensitiveColumnAccess access = mock(SensitiveColumnAccess.class);
        when(access.visibleColumns(SOURCE)).thenReturn(new ColumnVisibility(Set.of("jwt_claims"), Map.of("ingest_time", List.of(p1)),
                Set.of("jwt_claims", "ingest_time"), null));
        when(access.mayReadSensitive(SOURCE)).thenReturn(true);
        when(access.maySeeSensitive(SOURCE)).thenReturn(true);
        final SensitiveProjectionGuard restricted = new SensitiveProjectionGuard(access, columns, mock(CatalogQueryService.class));

        for (final String spelling : List.of("ingested", "ingest_time")) {
            assertThatThrownBy(() -> restricted.enforce(SOURCE, List.of(spelling), "scan"))
                    .isInstanceOf(ColumnNotEntitledException.class)
                    .hasMessageContaining("'ingest_time'")
                    .hasMessageContaining("masks");
        }
        assertThatCode(() -> restricted.enforce(SOURCE, List.of("jwt_claims"), "scan")).doesNotThrowAnyException();
        final RuntimeException failure = new IllegalStateException("parse");
        assertThat(restricted.reattribute(SOURCE, "scan", failure)).as("nothing is hidden, so the failure keeps its attribution")
                .isSameAs(failure);
    }

    @Test
    @DisplayName("an unentitled sensitive source column and a policy-hidden enrichment column are column_not_entitled")
    void policyHiddenEnrichmentColumnWinsOverUnentitledSensitiveSourceColumn() {
        final String labels = "usage_labels";
        final ColumnMappingEntity label = new ColumnMappingEntity();
        label.setTableName(labels);
        label.setName("label");
        label.setSourceName("label");
        when(columns.findByTableNameOrderByOrdinal(labels)).thenReturn(List.of(label));
        final TableDefinitionEntity enrichment = new TableDefinitionEntity();
        enrichment.setName(labels);
        final CatalogQueryService catalog = mock(CatalogQueryService.class);
        when(catalog.activeEnrichmentsOf(SOURCE)).thenReturn(List.of(enrichment));
        final SensitiveColumnAccess access = mock(SensitiveColumnAccess.class);
        when(access.visibleColumns(SOURCE)).thenReturn(ColumnVisibility.ALL);
        when(access.visibleColumns(labels)).thenReturn(new ColumnVisibility(Set.of()));
        when(access.visibleColumns(enrichment)).thenReturn(new ColumnVisibility(Set.of()));
        when(access.mayReadSensitive(SOURCE)).thenReturn(false);
        when(access.maySeeSensitive(SOURCE)).thenReturn(false);
        final SensitiveProjectionGuard entityGuard = new SensitiveProjectionGuard(access, columns, catalog);

        // The sensitive source column comes first in the projection, so a per-table order would answer the clearance.
        assertThatThrownBy(() -> entityGuard.enforceEntity(SOURCE, List.of("jwt_claims", labels + ".label"), "scan"))
                .isInstanceOf(ColumnNotEntitledException.class)
                .hasMessageContaining(labels + ".label");
        assertThat(entityGuard.reattribute(SOURCE, "scan", new IllegalStateException("parse"), true))
                .isInstanceOf(ColumnNotEntitledException.class);
    }

    @Test
    @DisplayName("without a column policy hiding anything, a parse failure keeps today's attribution")
    void reattributionFallsBackToTheClearance() {
        when(evaluator.isAllowed(SOURCE, Privilege.TABLE_SENSITIVE)).thenReturn(false);
        final RuntimeException failure = new IllegalStateException("parse");

        assertThat(guard.reattribute(SOURCE, "scan", failure)).isInstanceOf(SensitiveColumnNotEntitledException.class);
        when(evaluator.isAllowed(SOURCE, Privilege.TABLE_SENSITIVE)).thenReturn(true);
        assertThat(guard.reattribute(SOURCE, "scan", failure)).isSameAs(failure);
    }

    @Test
    @DisplayName("none mode fails open")
    void noneModeFailsOpen() {
        final SensitiveProjectionGuard open = new SensitiveProjectionGuard(
                SensitiveColumnAccessFixture.withoutColumnPolicies(evaluator, new SecurityModeResolver(SecurityMode.NONE.code())),
                columns, mock(CatalogQueryService.class));
        assertThatCode(() -> open.enforce(SOURCE, List.of("jwt_claims"), "scan")).doesNotThrowAnyException();
    }
}
