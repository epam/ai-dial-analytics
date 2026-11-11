package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.sync.mapping.ColumnMapping;
import com.epam.aidial.analytics.sync.mapping.SyncTarget;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The application started with {@code sync.sensitive-columns-enabled=false}: the {@code syncTargets} bean is built on the
 * real startup path, reading the catalog the Postgres migrations seeded, and leaves the flagged columns out of each
 * target's mapping. The ingest effect of those mappings is covered by {@link SensitiveColumnIngestIntegrationTest}.
 *
 * <p>This property set is a context of its own; its JDBC pool is kept small, since the shared PostgreSQL container's
 * connection ceiling is what every cached context draws on (see {@link AbstractClickHouseIntegrationTest#SCAN_SAFETY_LAG_OFF}).
 */
@TestPropertySource(properties = {"sync.sensitive-columns-enabled=false", "spring.datasource.hikari.maximum-pool-size=2"})
class SensitiveColumnsDisabledStartupIntegrationTest extends AbstractClickHouseIntegrationTest {

    private static final List<String> PAYLOAD_SENSITIVE = List.of(
            "jwt_claims", "request_tags", "request_body", "response_body", "assembled_response", "request_cache_control");

    @Autowired
    private List<SyncTarget> syncTargets;

    @Test
    void startupExcludesTheCatalogsSensitiveColumnsFromEachTarget() {
        Map<String, List<String>> columnsByTable = syncTargets.stream().collect(Collectors.toMap(SyncTarget::table,
                t -> t.mapping().columns().stream().map(ColumnMapping::column).toList()));

        assertThat(columnsByTable.get("dial_usage_log_payload")).doesNotContainAnyElementsOf(PAYLOAD_SENSITIVE)
                .contains("event_id", "request_time", "trace_id", "core_span_id", "project_id", "user_hash");
        assertThat(columnsByTable.get("rate_analytics")).doesNotContain("comment").contains("trace_id", "rate", "rate_raw");
        assertThat(columnsByTable.get("dial_usage_log")).contains("event_id", "trace_id", "deployment", "user_hash");
    }
}
