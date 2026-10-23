package com.epam.aidial.analytics.sync.mapping;

import com.epam.aidial.analytics.clickhouse.TestClickHouseSpans;
import com.epam.aidial.analytics.clickhouse.UsageLogWriter;
import com.epam.aidial.analytics.configuration.SyncProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the startup schema reconciliation against the events column structure: a matching table
 * validates cleanly (including the UUID/Bool calculated columns and the unmapped table-default column), a
 * missing column or an incompatible kind refuses to start (naming the offending column), and an
 * unrecognized type spelling is warned-and-allowed.
 */
class SchemaValidatorTest {

    private static final SyncProperties SYNC = new SyncProperties(
            "-", 1, 10, 268435456L, SyncProperties.DEFAULT_MAX_LINE_BYTES, "logs", "yyyy/MM/dd", "'date='yyyy-MM-dd", true,
            List.of(new SyncProperties.Target("dial_usage_log", "classpath:mapping/dial-usage-log.yaml")), null, null, null);

    private static final MappingConfig MAPPING = loadDefaultMapping();

    private final SchemaValidator validator = new SchemaValidator(
            List.of(new SyncTarget("dial_usage_log", MAPPING,
                    new MappingEngine(MAPPING, new TransformRegistry(),
                            new DeriverRegistry(JsonMapper.builder().build())))),
            new UsageLogWriter(null, null, null, TestClickHouseSpans.noop(), new SimpleMeterRegistry()), SYNC);

    private static final MappingConfig RATE_MAPPING = new MappingFileLoader(
            new DefaultResourceLoader(), new TransformRegistry(), new DeriverRegistry(JsonMapper.builder().build()))
            .load("classpath:mapping/rate-analytics.yaml");

    private static MappingConfig loadDefaultMapping() {
        return new MappingFileLoader(
                new DefaultResourceLoader(), new TransformRegistry(), new DeriverRegistry(JsonMapper.builder().build()))
                .load("classpath:mapping/dial-usage-log.yaml");
    }

    /** The live rate_analytics schema (matches db/migration/clickhouse/V2__create_rate_analytics.sql, codecs stripped). */
    private static Map<String, String> rateLiveSchema() {
        Map<String, String> table = new LinkedHashMap<>();
        table.put("request_time", "DateTime64(3, 'UTC')");
        table.put("_ingested_at", "DateTime64(3, 'UTC')"); // table-default column, intentionally unmapped
        table.put("project_id", "LowCardinality(String)");
        table.put("trace_id", "String");
        table.put("core_span_id", "String");
        table.put("deployment", "LowCardinality(String)");
        table.put("user_hash", "String");
        table.put("chat_id", "String");
        table.put("response_id", "String");
        table.put("rate", "Nullable(Int8)");
        // Nested wrapper, exactly as V8 declares it and as DESCRIBE TABLE reports it back.
        table.put("rate_raw", "LowCardinality(Nullable(String))");
        table.put("comment", "Nullable(String)");
        return table;
    }

    @Test
    void ledgerMatchingItsMigrationValidates() {
        validator.validateLedger(Map.of(
                "blob_key", "String",
                "blob_last_modified", "DateTime64(3, 'UTC')",
                "ingested_at", "DateTime64(3, 'UTC')"));
    }

    /** A deployment that skipped the ledger migration: DESCRIBE of a missing table yields nothing usable. */
    @Test
    void missingLedgerColumnsRefuseStartup() {
        assertThatThrownBy(() -> validator.validateLedger(Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("_dial_internal_ingested_blob")
                .hasMessageContaining("'blob_key' is missing");
    }

    @Test
    void mistypedLedgerColumnRefusesStartup() {
        assertThatThrownBy(() -> validator.validateLedger(Map.of(
                "blob_key", "String",
                "blob_last_modified", "DateTime",
                "ingested_at", "DateTime64(3, 'UTC')")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'blob_last_modified' has type 'DateTime'");
    }

    @Test
    void rateMappingReconcilesWithRateTable() {
        validator.validateAgainst("rate_analytics", RATE_MAPPING, rateLiveSchema());
    }

    private static final MappingConfig PAYLOAD_MAPPING = new MappingFileLoader(
            new DefaultResourceLoader(), new TransformRegistry(), new DeriverRegistry(JsonMapper.builder().build()))
            .load("classpath:mapping/dial-usage-log-payload.yaml");

    /**
     * The live dial_usage_log_payload schema (matches
     * db/migration/clickhouse/V10__create_dial_usage_log_payload.sql, codecs stripped, plus the request_scope V16 adds).
     */
    private static Map<String, String> payloadLiveSchema() {
        Map<String, String> table = new LinkedHashMap<>();
        table.put("event_id", "UUID");
        table.put("request_time", "DateTime64(3, 'UTC')");
        table.put("_updated_at", "DateTime64(3)"); // table-default column, intentionally unmapped
        table.put("trace_id", "String");
        table.put("core_span_id", "String");
        // Non-nullable: ClickHouse has no Nullable(JSON), which is exactly why an unmatched grain join
        // renders these as {} rather than NULL — safe here because no accessor can tell the two apart.
        table.put("jwt_claims", "JSON");
        table.put("request_tags", "JSON");
        table.put("request_body", "Nullable(String)");
        table.put("response_body", "Nullable(String)");
        table.put("assembled_response", "Nullable(String)");
        table.put("request_scope", "UUID");
        return table;
    }

    @Test
    void payloadMappingReconcilesWithPayloadTable() {
        validator.validateAgainst("dial_usage_log_payload", PAYLOAD_MAPPING, payloadLiveSchema());
    }

    /**
     * A body column declared nullable in the mapping must be backed by a {@code Nullable(...)} column, or
     * the engine's null-on-absent output would be rejected at insert.
     */
    @Test
    void payloadMappingRefusesNonNullableBodyColumn() {
        Map<String, String> table = payloadLiveSchema();
        table.put("request_body", "String");

        assertThatThrownBy(() -> validator.validateAgainst("dial_usage_log_payload", PAYLOAD_MAPPING, table))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("request_body");
    }

    /**
     * {@code rate_raw} is declared {@code LowCardinality(Nullable(String))}, so its nullability sits one
     * wrapper deep. A nullability check that only looked at the outermost wrapper would read it as
     * non-nullable and refuse startup against a correctly migrated table — a failure that would surface
     * only on a real boot, never in the mapping tests. Pinned here rather than left to that discovery.
     */
    @Test
    void rateMappingSeesNullabilityThroughTheLowCardinalityWrapper() {
        validator.validateAgainst("rate_analytics", RATE_MAPPING, rateLiveSchema());

        Map<String, String> nonNullable = rateLiveSchema();
        nonNullable.put("rate_raw", "LowCardinality(String)"); // mapping declares it nullable, table is not

        assertThatThrownBy(() -> validator.validateAgainst("rate_analytics", RATE_MAPPING, nonNullable))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rate_raw")
                .hasMessageContaining("nullable");
    }

    /**
     * The invariant that makes the V8 migration and the mapping entry a single shipping unit: a mapped
     * column the physical table lacks aborts startup.
     */
    @Test
    void rateMappingRejectsTableMissingTheRawRateColumn() {
        Map<String, String> table = rateLiveSchema();
        table.remove("rate_raw");

        assertThatThrownBy(() -> validator.validateAgainst("rate_analytics", RATE_MAPPING, table))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rate_raw")
                .hasMessageContaining("missing");
    }

    @Test
    void rateMappingRejectsNonNullableRateColumn() {
        Map<String, String> table = rateLiveSchema();
        table.put("rate", "Int8"); // mapping declares rate nullable, table is not

        assertThatThrownBy(() -> validator.validateAgainst("rate_analytics", RATE_MAPPING, table))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rate")
                .hasMessageContaining("nullable");
    }

    /** The live dial_usage_log schema (matches db/migration/clickhouse/V1__create_dial_usage_log.sql, codecs stripped). */
    private static Map<String, String> liveSchema() {
        Map<String, String> table = new LinkedHashMap<>();
        table.put("event_id", "UUID");
        table.put("event_kind", "LowCardinality(String)");
        table.put("request_time", "DateTime64(3, 'UTC')");
        table.put("_ingested_at", "DateTime('UTC')"); // table-default column, intentionally unmapped
        table.put("_source_file", "LowCardinality(String)");
        table.put("project_id", "LowCardinality(String)");
        table.put("chat_id", "String");
        table.put("trace_id", "String");
        table.put("core_span_id", "String");
        table.put("core_parent_span_id", "Nullable(String)");
        table.put("execution_path", "Array(LowCardinality(String))");
        table.put("parent_deployment", "LowCardinality(Nullable(String))");
        table.put("deployment", "LowCardinality(String)");
        table.put("response_upstream_uri", "LowCardinality(Nullable(String))");
        table.put("user_hash", "String");
        table.put("response_id", "String");
        table.put("prompt_tokens", "Nullable(UInt32)");
        table.put("completion_tokens", "Nullable(UInt32)");
        table.put("total_tokens", "Nullable(UInt32)");
        table.put("cached_prompt_tokens", "Nullable(UInt32)");
        table.put("cache_creation_tokens", "Nullable(UInt32)");
        table.put("reasoning_tokens", "UInt32");
        table.put("number_request_messages", "UInt32");
        table.put("total_price", "Nullable(Decimal(38, 12))");
        table.put("deployment_price", "Nullable(Decimal(38, 12))");
        table.put("operation_duration_ms", "UInt32");
        table.put("response_status", "UInt16");
        table.put("success", "Bool");
        table.put("request_method", "LowCardinality(String)");
        table.put("request_uri", "String");
        table.put("mcp_method", "LowCardinality(String)");
        table.put("mcp_tool_call_name", "LowCardinality(String)");
        // All five verbatim-content columns are absent from this mapped set — they live on
        // dial_usage_log_payload now (see payloadLiveSchema), and V11 dropped them from the physical
        // dial_usage_log table as well. The *_bytes pair stayed: it sizes a payload without holding
        // one, and must outlive the payload it describes.
        table.put("request_body_bytes", "UInt32");
        table.put("response_body_bytes", "UInt32");
        // Appended by V15__dial_usage_log_route.sql.
        table.put("route_name", "LowCardinality(Nullable(String))");
        table.put("route_type", "LowCardinality(Nullable(String))");
        return table;
    }

    @Test
    void matchingTableValidatesCleanly() {
        validator.validateAgainst("dial_usage_log", MAPPING, liveSchema());
    }

    @Test
    void unmappedTableDefaultColumnIsPermitted() {
        Map<String, String> table = liveSchema();
        // _ingested_at is present in the table but not in the mapping (it carries a table-side DEFAULT);
        // the mapping need not populate every table column. It is the only such column now that
        // operation_duration_ms is mapped.
        assertThat(table).containsKey("_ingested_at");
        validator.validateAgainst("dial_usage_log", MAPPING, table); // does not throw
    }

    /** The claim/header documents moved with the bodies, so the object-kind rules now bind there. */
    @Test
    void objectKindReconcilesWithJsonTableColumn() {
        Map<String, String> table = payloadLiveSchema();
        table.put("jwt_claims", "JSON");
        table.put("request_tags", "JSON");

        validator.validateAgainst("dial_usage_log_payload", PAYLOAD_MAPPING, table); // does not throw
    }

    @Test
    void objectKindMismatchRefusesToStart() {
        Map<String, String> table = payloadLiveSchema();
        table.put("jwt_claims", "String"); // mapping declares kind object -> JSON family

        assertThatThrownBy(() -> validator.validateAgainst("dial_usage_log_payload", PAYLOAD_MAPPING, table))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt_claims")
                .hasMessageContaining("incompatible");
    }

    @Test
    void missingColumnRefusesToStart() {
        Map<String, String> table = liveSchema();
        table.remove("trace_id");

        assertThatThrownBy(() -> validator.validateAgainst("dial_usage_log", MAPPING, table))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trace_id")
                .hasMessageContaining("missing");
    }

    @Test
    void incompatibleKindRefusesToStart() {
        Map<String, String> table = liveSchema();
        table.put("prompt_tokens", "Nullable(String)"); // mapping declares kind long -> INTEGER family

        assertThatThrownBy(() -> validator.validateAgainst("dial_usage_log", MAPPING, table))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prompt_tokens")
                .hasMessageContaining("incompatible");
    }

    @Test
    void nullabilityMismatchRefusesToStart() {
        Map<String, String> table = liveSchema();
        table.put("prompt_tokens", "UInt32"); // mapping declares this column nullable, table is not

        assertThatThrownBy(() -> validator.validateAgainst("dial_usage_log", MAPPING, table))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prompt_tokens")
                .hasMessageContaining("nullable");
    }

    @Test
    void missedNullabilityMigrationRefusesToStart() {
        Map<String, String> table = liveSchema();
        // An operator running with migrations disabled who provisioned from the CREATE TABLE alone and
        // skipped the ALTER that made this column Nullable. Ingesting against it would store 0 where the
        // mapping intends NULL, so startup must stop instead.
        table.put("cache_creation_tokens", "UInt32");

        assertThatThrownBy(() -> validator.validateAgainst("dial_usage_log", MAPPING, table))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cache_creation_tokens")
                .hasMessageContaining("nullable");
    }

    @Test
    void skippedAssembledResponseMigrationRefusesToStart() {
        Map<String, String> table = payloadLiveSchema();
        // An operator running with migrations disabled who deployed the build that maps
        // assembled_response without first applying the DDL that adds the column — V7 when the column
        // lived on dial_usage_log, V10 now that it is provisioned with the payload table. The insert
        // would be rejected per batch, so startup must stop instead — the ordering invariant the
        // migration's header states, enforced here.
        table.remove("assembled_response");

        assertThatThrownBy(() -> validator.validateAgainst(
                "dial_usage_log_payload", PAYLOAD_MAPPING, table))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("assembled_response")
                .hasMessageContaining("missing");
    }

    @Test
    void unexpectedNullableRefusesToStart() {
        Map<String, String> table = liveSchema();
        table.put("project_id", "Nullable(String)"); // mapping does not declare this column nullable

        assertThatThrownBy(() -> validator.validateAgainst("dial_usage_log", MAPPING, table))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("project_id")
                .hasMessageContaining("nullable");
    }

    @Test
    void unrecognizedTypeIsWarnedAndAllowed() {
        Map<String, String> table = liveSchema();
        table.put("user_hash", "Enum8('a' = 1)"); // unrecognized spelling -> warn-and-allow

        // does not throw
        validator.validateAgainst("dial_usage_log", MAPPING, table);
        assertThat(table).containsKey("user_hash");
    }
}
