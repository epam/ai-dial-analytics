package com.epam.aidial.analytics.service.enrichment.validation;

import com.epam.aidial.analytics.exception.PipelineValidationException;
import com.epam.aidial.analytics.service.query.CatalogQueryService;
import com.epam.aidial.analytics.service.table.ColumnMappingEntity;
import com.epam.aidial.analytics.service.table.ColumnMappingRepository;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Every governed SQL fragment of a pipeline passes {@link SourceScopeGuard}, so it is where a computed field named in
 * SQL — a filter, a readiness signal, a {@code prefer_sql}, a {@code sql} transform's expression — is refused by name.
 */
class SourceScopeGuardTest {

    private static final String SOURCE = "dial_usage_log";
    private static final String PAYLOAD = "dial_usage_log_payload";

    private final ColumnMappingRepository columns = mock(ColumnMappingRepository.class);
    private final CatalogQueryService catalog = mock(CatalogQueryService.class);
    private final SourceScopeGuard guard = new SourceScopeGuard(columns, catalog);

    SourceScopeGuardTest() {
        final TableDefinitionEntity payload = new TableDefinitionEntity();
        payload.setName(PAYLOAD);
        when(catalog.activeEnrichmentsOf(SOURCE)).thenReturn(List.of(payload));
        when(columns.findByTableNameOrderByOrdinal(SOURCE)).thenReturn(List.of(column(SOURCE, "trace_id")));
        when(columns.findByTableNameOrderByOrdinal(PAYLOAD)).thenReturn(List.of(column(PAYLOAD, "request_body")));
    }

    private static ColumnMappingEntity column(String table, String name) {
        final ColumnMappingEntity column = new ColumnMappingEntity();
        column.setTableName(table);
        column.setName(name);
        column.setSourceName(name);
        column.setType(QueryFieldType.STRING);
        return column;
    }

    @Test
    void computedFieldInFragmentIsRefusedNamingIt() {
        for (final String field : List.of(PAYLOAD + ".request_body_full", PAYLOAD + ".request_body_rebuild")) {
            assertThatThrownBy(() -> guard.requireSourceScoped(SOURCE, SOURCE, Set.of(field), "ready_when.signal", true))
                    .isInstanceOf(PipelineValidationException.class)
                    .hasMessageContaining("ready_when.signal")
                    .hasMessageContaining("'" + field + "'")
                    .hasMessageContaining("bound as a transform input");
        }
    }

    @Test
    void theStoredBodyBesideItIsStillAccepted() {
        assertThatCode(() -> guard.requireSourceScoped(SOURCE, SOURCE, Set.of(PAYLOAD + ".request_body", "trace_id"), "filter", true))
                .doesNotThrowAnyException();
    }
}
