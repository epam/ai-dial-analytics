package com.epam.aidial.analytics.web.mapper.table;

import com.epam.aidial.analytics.service.audit.TableSnapshot;
import com.epam.aidial.analytics.service.query.model.ComparisonNode;
import com.epam.aidial.analytics.service.query.model.ComparisonOp;
import com.epam.aidial.analytics.service.query.model.FieldExpr;
import com.epam.aidial.analytics.service.query.model.FilterNode;
import com.epam.aidial.analytics.service.query.model.ValueExpr;
import com.epam.aidial.analytics.service.query.model.ValueType;
import com.epam.aidial.analytics.service.security.SensitiveColumnAccess;
import com.epam.aidial.analytics.service.security.SensitiveColumnAccess.ColumnVisibility;
import com.epam.aidial.analytics.service.table.ColumnMappingEntity;
import com.epam.aidial.analytics.service.table.TableAccessService;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableStatus;
import com.epam.aidial.analytics.service.table.TableType;
import com.epam.aidial.analytics.service.table.TableView;
import com.epam.aidial.analytics.service.table.grant.EffectivePermissions;
import com.epam.aidial.analytics.service.table.grant.TableCallerPermissions;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import com.epam.aidial.analytics.web.dto.table.TableDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The catalog read surface's single column chokepoint. Every {@link TableDto} that carries columns — get,
 * the mutation responses, the revision read — is built by {@code TableMapper.toDto}, so this is where a
 * sensitive column is shown or omitted. A revision's snapshot rows carry the flags as they stood then, so
 * the same filter judges a revision on its own flags with the caller's current clearance.
 */
class TableMapperColumnVisibilityTest {

    private final TableCallerPermissions permissions = mock(TableCallerPermissions.class);
    private final SensitiveColumnAccess sensitiveAccess = mock(SensitiveColumnAccess.class);
    private final com.epam.aidial.analytics.service.table.relayout.TableLayouts layouts =
            mock(com.epam.aidial.analytics.service.table.relayout.TableLayouts.class);
    private final TableMapper mapper = new TableMapper(permissions, sensitiveAccess, mock(TableAccessService.class), layouts);

    @BeforeEach
    void permissionsAreIrrelevantHere() {
        when(permissions.resolve(any(), any(), any())).thenReturn(EffectivePermissions.uniform(true));
        when(sensitiveAccess.visibleColumns(any(TableDefinitionEntity.class))).thenReturn(ColumnVisibility.ALL);
        when(sensitiveAccess.visibleColumnsAtRevision(any(TableDefinitionEntity.class), any(), any())).thenReturn(ColumnVisibility.ALL);
    }

    private static TableSnapshot snapshot() {
        return new TableSnapshot(view(), null, null);
    }

    private static TableView view() {
        final TableDefinitionEntity definition = new TableDefinitionEntity();
        definition.setName("finance_costs");
        definition.setType(TableType.SOURCE);
        definition.setStatus(TableStatus.ACTIVE);
        definition.setOrderingKey(new ArrayList<>(List.of("k")));
        return new TableView(definition, List.of(column("k", 0, false), column("employee_name", 1, true)));
    }

    private static ColumnMappingEntity column(String name, int ordinal, boolean sensitive) {
        final ColumnMappingEntity col = new ColumnMappingEntity();
        col.setTableName("finance_costs");
        col.setName(name);
        col.setSourceName(name);
        col.setType(QueryFieldType.STRING);
        col.setOrdinal(ordinal);
        col.setSensitive(sensitive);
        return col;
    }

    @Test
    @DisplayName("without the clearance a sensitive column is omitted, not redacted")
    void unclearedCallerDoesNotSeeTheColumn() {
        when(sensitiveAccess.maySeeSensitive("finance_costs")).thenReturn(false);

        assertThat(mapper.toDto(view()).columns()).extracting(TableDto.ColumnDto::name).containsExactly("k");
    }

    @Test
    @DisplayName("with the clearance (or in none mode, where it fails open) every column is shown with its flag")
    void clearedCallerSeesEveryColumn() {
        when(sensitiveAccess.maySeeSensitive("finance_costs")).thenReturn(true);

        final List<TableDto.ColumnDto> columns = mapper.toDto(view()).columns();
        assertThat(columns).extracting(TableDto.ColumnDto::name).containsExactly("k", "employee_name");
        assertThat(columns.get(1).sensitive()).isTrue();
    }

    @Test
    @DisplayName("a column hidden by a column policy is omitted like an unentitled sensitive one")
    void policyHiddenColumnIsOmitted() {
        when(sensitiveAccess.maySeeSensitive("finance_costs")).thenReturn(true);
        when(sensitiveAccess.visibleColumns(any(TableDefinitionEntity.class))).thenReturn(new ColumnVisibility(Set.of("employee_name")));

        assertThat(mapper.toDto(view()).columns()).extracting(TableDto.ColumnDto::name).containsExactly("employee_name");
    }

    @Test
    @DisplayName("a masked column is listed like a visible one, on the current read and on a revision")
    void maskedColumnIsListed() {
        when(sensitiveAccess.maySeeSensitive("finance_costs")).thenReturn(true);
        final FilterNode p1 = new ComparisonNode(ComparisonOp.EQ, List.of(new FieldExpr("project"), new ValueExpr(ValueType.STRING, "P1")));
        final ColumnVisibility masked = new ColumnVisibility(Set.of("k"), Map.of("employee_name", List.of(p1)), Set.of("k", "employee_name"), null);
        when(sensitiveAccess.visibleColumns(any(TableDefinitionEntity.class))).thenReturn(masked);
        when(sensitiveAccess.visibleColumnsAtRevision(any(TableDefinitionEntity.class), any(), any())).thenReturn(masked);

        assertThat(mapper.toDto(view()).columns()).extracting(TableDto.ColumnDto::name).containsExactly("k", "employee_name");
        assertThat(mapper.toDto(new TableSnapshot(view(), new TableDefinitionEntity(), new TableDefinitionEntity()), true).columns())
                .extracting(TableDto.ColumnDto::name).containsExactly("k", "employee_name");
    }

    @Test
    @DisplayName("a revision is judged by the decision that also applies the table's current column policies")
    void revisionUsesTheRevisionDecision() {
        when(sensitiveAccess.maySeeSensitive("finance_costs")).thenReturn(true);
        final TableView view = view();
        final TableDefinitionEntity life = new TableDefinitionEntity();
        final TableDefinitionEntity sourceLife = new TableDefinitionEntity();
        when(sensitiveAccess.visibleColumnsAtRevision(same(view.definition()), same(life), same(sourceLife)))
                .thenReturn(new ColumnVisibility(Set.of("k")));

        assertThat(mapper.toDto(new TableSnapshot(view, life, sourceLife), true).columns())
                .extracting(TableDto.ColumnDto::name).containsExactly("k");
        verify(sensitiveAccess).visibleColumnsAtRevision(same(view.definition()), same(life), same(sourceLife));
        assertThat(mapper.toDto(view()).columns()).extracting(TableDto.ColumnDto::name).containsExactly("k", "employee_name");
    }

    @Test
    @DisplayName("visibility intersects: a column either decision hides stays hidden")
    void visibilitiesIntersect() {
        final ColumnVisibility then = new ColumnVisibility(Set.of("a", "b"));
        assertThat(then.and(ColumnVisibility.ALL)).isEqualTo(then);
        assertThat(ColumnVisibility.ALL.and(then)).isEqualTo(then);
        assertThat(then.and(new ColumnVisibility(Set.of("b", "c"))).visible()).containsExactly("b");
        assertThat(ColumnVisibility.ALL.and(ColumnVisibility.ALL)).isEqualTo(ColumnVisibility.ALL);
    }

    @Test
    @DisplayName("the layout is the table's current state, so the revision read omits it")
    void revisionReadCarriesNoLayout() {
        when(layouts.layout(any())).thenReturn("aligned");

        assertThat(mapper.toDto(view()).layout()).isEqualTo("aligned");
        assertThat(mapper.toDto(snapshot(), true).layout()).isNull();
    }
}
