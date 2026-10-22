package com.epam.aidial.analytics.service;

import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import com.epam.aidial.analytics.service.table.TableType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/** The catalog's answer to "which ingest targets enrich which": read from {@code table_definition}, per run. */
@ExtendWith(MockitoExtension.class)
class CatalogIngestFlushOrderTest {

    @Mock
    private TableDefinitionRepository definitions;

    private static TableDefinitionEntity definition(String name, TableType type, String sourceTable) {
        final TableDefinitionEntity entity = new TableDefinitionEntity();
        entity.setName(name);
        entity.setType(type);
        entity.setSourceTable(sourceTable);
        return entity;
    }

    @Test
    void enrichmentTargetMapsToItsSourceWhenBothAreTargets() {
        given(definitions.findById("dial_usage_log")).willReturn(Optional.of(definition("dial_usage_log", TableType.SOURCE, null)));
        given(definitions.findById("dial_usage_log_payload"))
                .willReturn(Optional.of(definition("dial_usage_log_payload", TableType.ENRICHMENT, "dial_usage_log")));
        given(definitions.findById("rate_analytics")).willReturn(Optional.of(definition("rate_analytics", TableType.SOURCE, null)));

        final Map<String, String> sourceOf = new CatalogIngestFlushOrder(definitions)
                .sourceOf(List.of("dial_usage_log", "dial_usage_log_payload", "rate_analytics"));

        assertThat(sourceOf).containsExactly(Map.entry("dial_usage_log_payload", "dial_usage_log"));
    }

    @Test
    void enrichmentWhoseSourceIsNotAmongTheTargetsIsIndependent() {
        given(definitions.findById("dial_usage_log_payload"))
                .willReturn(Optional.of(definition("dial_usage_log_payload", TableType.ENRICHMENT, "dial_usage_log")));

        assertThat(new CatalogIngestFlushOrder(definitions).sourceOf(List.of("dial_usage_log_payload"))).isEmpty();
    }

    @Test
    void uncataloguedTargetIsIndependent() {
        given(definitions.findById("brand_new")).willReturn(Optional.empty());

        assertThat(new CatalogIngestFlushOrder(definitions).sourceOf(List.of("brand_new"))).isEmpty();
    }
}
