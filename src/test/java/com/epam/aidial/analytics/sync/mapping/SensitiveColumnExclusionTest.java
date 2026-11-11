package com.epam.aidial.analytics.sync.mapping;

import com.epam.aidial.analytics.observability.LogCapture;
import com.epam.aidial.analytics.service.table.ColumnMappingEntity;
import com.epam.aidial.analytics.service.table.ColumnMappingRepository;
import com.epam.aidial.analytics.service.table.TableDefinitionEntity;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The columns {@code sync.sensitive-columns-enabled=false} leaves out per ingest target, read from the catalog's
 * {@code sensitive} flags, and the startup guard against excluding a column ingest depends on.
 */
class SensitiveColumnExclusionTest {

    private static final String USAGE_LOG = "dial_usage_log";
    private static final String PAYLOAD = "dial_usage_log_payload";
    private static final String RATE = "rate_analytics";
    private static final List<String> TARGETS = List.of(USAGE_LOG, PAYLOAD, RATE);

    private final ColumnMappingRepository columns = mock(ColumnMappingRepository.class);
    private final TableDefinitionRepository definitions = mock(TableDefinitionRepository.class);
    private final List<ColumnMappingEntity> catalog = new ArrayList<>();
    private final List<TableDefinitionEntity> defined = new ArrayList<>();

    private LogCapture logs;

    @BeforeEach
    void seedCatalog() {
        defined.add(definition(USAGE_LOG, null, "event_id", List.of("project_id", "deployment", "request_time", "trace_id", "core_span_id")));
        defined.add(definition(PAYLOAD, "event_id", null, List.of("event_id")));
        defined.add(definition(RATE, null, "event_id", List.of("request_time", "trace_id", "core_span_id")));

        column(USAGE_LOG, "event_id", false);
        column(USAGE_LOG, "trace_id", false);
        column(USAGE_LOG, "project_id", false);
        column(USAGE_LOG, "user_hash", false);
        column(PAYLOAD, "event_id", false);
        column(PAYLOAD, "jwt_claims", true);
        column(PAYLOAD, "request_tags", true);
        column(PAYLOAD, "request_body", true);
        column(PAYLOAD, "response_body", true);
        column(PAYLOAD, "assembled_response", true);
        column(PAYLOAD, "request_cache_control", true);
        column(RATE, "event_id", false);
        column(RATE, "rate", false);
        column(RATE, "comment", true);

        when(definitions.findAllById(any())).thenAnswer(call -> {
            Collection<?> names = call.getArgument(0);
            return defined.stream().filter(d -> names.contains(d.getName())).toList();
        });
        when(columns.findByTableNameInOrderByTableNameAscOrdinalAsc(any())).thenAnswer(call -> {
            Collection<?> names = call.getArgument(0);
            return catalog.stream().filter(c -> names.contains(c.getTableName())).toList();
        });

        logs = LogCapture.of(SensitiveColumnExclusion.class);
    }

    @AfterEach
    void detach() {
        logs.close();
    }

    @Test
    void seededCatalogExcludesThePayloadContentAndTheRatingComment() {
        Map<String, Set<String>> excluded = SensitiveColumnExclusion.resolve(TARGETS, columns, definitions);

        assertThat(excluded.get(USAGE_LOG)).isEmpty();
        assertThat(excluded.get(PAYLOAD)).containsExactly(
                "jwt_claims", "request_tags", "request_body", "response_body", "assembled_response", "request_cache_control");
        assertThat(excluded.get(RATE)).containsExactly("comment");
        assertThat(messages(Level.INFO)).anySatisfy(m -> assertThat(m).contains(PAYLOAD).contains("request_body"))
                .anySatisfy(m -> assertThat(m).contains(RATE).contains("comment"));
    }

    @Test
    void columnFlaggedLaterIsExcludedWithoutCodeChange() {
        column(USAGE_LOG, "user_hash", true);

        assertThat(SensitiveColumnExclusion.resolve(TARGETS, columns, definitions).get(USAGE_LOG)).containsExactly("user_hash");
    }

    @Test
    void sensitiveDeduplicationKeyColumnFailsStartup() {
        column(USAGE_LOG, "trace_id", true);

        assertThatThrownBy(() -> SensitiveColumnExclusion.resolve(TARGETS, columns, definitions))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("trace_id").hasMessageContaining(USAGE_LOG);
    }

    @Test
    void sensitiveGrainKeyFailsStartup() {
        column(PAYLOAD, "event_id", true);

        assertThatThrownBy(() -> SensitiveColumnExclusion.resolve(List.of(PAYLOAD), columns, definitions))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("event_id").hasMessageContaining(PAYLOAD);
    }

    @Test
    void sensitiveOrderingKeyColumnFailsStartup() {
        column(USAGE_LOG, "project_id", true);

        assertThatThrownBy(() -> SensitiveColumnExclusion.resolve(List.of(USAGE_LOG), columns, definitions))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("project_id").hasMessageContaining(USAGE_LOG);
    }

    @Test
    void sensitivePartitionVersionOrTtlColumnFailsStartup() {
        TableDefinitionEntity custom = definition("custom_sink", null, null, List.of("id"));
        custom.setPartitionColumn("day");
        custom.setVersionColumn("version");
        custom.setTtlColumn("expires");
        defined.add(custom);

        for (String column : List.of("day", "version", "expires")) {
            catalog.removeIf(c -> c.getTableName().equals("custom_sink"));
            column("custom_sink", column, true);
            assertThatThrownBy(() -> SensitiveColumnExclusion.resolve(List.of("custom_sink"), columns, definitions))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining(column).hasMessageContaining("custom_sink");
        }
    }

    @Test
    void tableWithoutCatalogDefinitionWarnsAndExcludesNothing() {
        column("custom_sink", "secret", true);

        assertThat(SensitiveColumnExclusion.resolve(List.of("custom_sink"), columns, definitions).get("custom_sink")).isEmpty();
        assertThat(messages(Level.WARN)).anySatisfy(m -> assertThat(m).contains("custom_sink").contains("no catalog definition"));
    }

    @Test
    void withoutKeepsTheMatcherAndTheOtherColumns() {
        RecordMatcher matcher = RecordMatcher.matchAll();
        MappingConfig mapping = new MappingConfig(List.of(
                new ColumnMapping("event_id", "a", ColumnKind.STRING, null, null, false),
                new ColumnMapping("request_body", "b", ColumnKind.STRING, null, null, true)), matcher);

        MappingConfig reduced = SensitiveColumnExclusion.without(mapping, Set.of("request_body", "request_cache_control"));

        assertThat(reduced.columns()).extracting(ColumnMapping::column).containsExactly("event_id");
        assertThat(reduced.matcher()).isSameAs(matcher);
        assertThat(SensitiveColumnExclusion.without(mapping, Set.of())).isSameAs(mapping);
    }

    private List<String> messages(Level level) {
        return logs.messagesAt(level);
    }

    private void column(String table, String sourceName, boolean sensitive) {
        catalog.removeIf(c -> c.getTableName().equals(table) && c.getSourceName().equals(sourceName));
        ColumnMappingEntity column = new ColumnMappingEntity();
        column.setTableName(table);
        column.setSourceName(sourceName);
        column.setName(sourceName);
        column.setSensitive(sensitive);
        catalog.add(column);
    }

    private static TableDefinitionEntity definition(String name, String grainKey, String identityColumn, List<String> orderingKey) {
        TableDefinitionEntity definition = new TableDefinitionEntity();
        definition.setName(name);
        definition.setGrainKey(grainKey);
        definition.setIdentityColumn(identityColumn);
        definition.setOrderingKey(new ArrayList<>(orderingKey));
        return definition;
    }
}
