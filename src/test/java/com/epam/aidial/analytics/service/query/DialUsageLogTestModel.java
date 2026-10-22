package com.epam.aidial.analytics.service.query;

import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import com.epam.aidial.analytics.web.dto.query.QuerySchemaFieldDto;
import org.jooq.Field;
import org.jooq.impl.DSL;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Test fixture that builds the {@link QueryEntityModel} for {@code dial_usage_log} (no enrichments)
 * offline, mirroring the catalog backfill in {@code V1__create_table_catalog.sql}. Lets the
 * translation/render unit tests run without a database, replacing the former hand-built
 * {@code DialUsageLogSchema}.
 */
public final class DialUsageLogTestModel {

    public static final String ENTITY = "dial_usage_log";

    /** An ACTIVE enrichment of the entity, joined on the shared grain key {@code event_id}. */
    public static final String ENRICHMENT = "usage_client_identity";

    /** The one column {@link #ENRICHMENT} exposes, under the qualified flat name the entity publishes. */
    public static final String ENRICHMENT_COLUMN = ENRICHMENT + ".client_session_id";

    /** The keyed enrichment's low-cardinality column, for grains mixing it with a source column. */
    public static final String ENRICHMENT_TYPE_COLUMN = ENRICHMENT + ".client_type";

    /** The keyed enrichment's timestamp column, for a time bucket over an enrichment column. */
    public static final String ENRICHMENT_TIME_COLUMN = ENRICHMENT + ".client_ts";

    /** The keyed enrichment's array column, which ClickHouse cannot make nullable. */
    public static final String ENRICHMENT_ARRAY_COLUMN = ENRICHMENT + ".client_tags";

    /**
     * The seeded payload enrichment: the five verbatim-content columns live here since the split, and resolve on
     * {@link #ENTITY} only under {@code dial_usage_log_payload.<column>}. Unlike
     * {@link #ENRICHMENT} it is part of {@link #model()} itself, because it is part of the shipped
     * catalog rather than a per-test extra.
     */
    public static final String PAYLOAD = "dial_usage_log_payload";

    /** The qualified flat name of a payload column, e.g. {@code payload("request_body")}. */
    public static String payload(String column) {
        return PAYLOAD + "." + column;
    }

    private DialUsageLogTestModel() {
    }

    public static QueryEntityModel model() {
        final Map<String, QueryFieldBinding> bindings = new LinkedHashMap<>();
        final List<QuerySchemaFieldDto> schema = new ArrayList<>();
        add(bindings, schema, "event_id", QueryFieldType.UUID);
        add(bindings, schema, "event_kind", QueryFieldType.STRING);
        add(bindings, schema, "request_time", QueryFieldType.TIMESTAMP);
        add(bindings, schema, "_ingested_at", QueryFieldType.TIMESTAMP);
        add(bindings, schema, "_source_file", QueryFieldType.STRING);
        add(bindings, schema, "project_id", QueryFieldType.STRING);
        add(bindings, schema, "chat_id", QueryFieldType.STRING);
        add(bindings, schema, "trace_id", QueryFieldType.STRING);
        add(bindings, schema, "core_span_id", QueryFieldType.STRING);
        add(bindings, schema, "core_parent_span_id", QueryFieldType.STRING);
        add(bindings, schema, "execution_path", QueryFieldType.ARRAY);
        add(bindings, schema, "parent_deployment", QueryFieldType.STRING);
        add(bindings, schema, "deployment", QueryFieldType.STRING);
        add(bindings, schema, "response_upstream_uri", QueryFieldType.STRING);
        add(bindings, schema, "user_hash", QueryFieldType.STRING);
        add(bindings, schema, "response_id", QueryFieldType.STRING);
        add(bindings, schema, "prompt_tokens", QueryFieldType.LONG);
        add(bindings, schema, "completion_tokens", QueryFieldType.LONG);
        add(bindings, schema, "total_tokens", QueryFieldType.LONG);
        add(bindings, schema, "cached_prompt_tokens", QueryFieldType.LONG);
        add(bindings, schema, "cache_creation_tokens", QueryFieldType.LONG);
        add(bindings, schema, "reasoning_tokens", QueryFieldType.LONG);
        add(bindings, schema, "number_request_messages", QueryFieldType.LONG);
        add(bindings, schema, "total_price", QueryFieldType.DECIMAL);
        add(bindings, schema, "deployment_price", QueryFieldType.DECIMAL);
        add(bindings, schema, "operation_duration_ms", QueryFieldType.LONG);
        add(bindings, schema, "response_status", QueryFieldType.INTEGER);
        add(bindings, schema, "success", QueryFieldType.BOOLEAN);
        add(bindings, schema, "request_method", QueryFieldType.STRING);
        add(bindings, schema, "request_uri", QueryFieldType.STRING);
        add(bindings, schema, "mcp_method", QueryFieldType.STRING);
        add(bindings, schema, "mcp_tool_call_name", QueryFieldType.STRING);
        add(bindings, schema, "request_body_bytes", QueryFieldType.LONG);
        add(bindings, schema, "response_body_bytes", QueryFieldType.LONG);
        // None of the five verbatim-content columns is a source column: the mapping split stopped
        // capturing them on dial_usage_log (V11 then dropped the physical columns) and V38
        // re-keyed their catalog rows onto the dial_usage_log_payload
        // enrichment, so they resolve only under their qualified flat names, through the grain join
        // below. The three bodies stay seeded heavy — on this entity that is belt-and-braces for a
        // wildcard, since an enrichment column is excluded regardless, but the flag still governs
        // discovery and the validate-sql heavy-column warning. The two documents are not heavy.
        //
        // jwt_claims and request_tags are declared NON-NULLABLE here, matching the catalog: ClickHouse
        // has no Nullable(JSON). That is what makes them the regression case for
        // EntityFields.absenceIsUnrepresentable, which exempts object columns because {} — what an
        // unmatched grain join renders — is indistinguishable from a present-but-keyless document
        // under every accessor.
        final Set<String> payloadColumns = new LinkedHashSet<>();
        addPayload(bindings, schema, payloadColumns, "jwt_claims", QueryFieldType.OBJECT, false);
        addPayload(bindings, schema, payloadColumns, "request_tags", QueryFieldType.OBJECT, false);
        addPayload(bindings, schema, payloadColumns, "request_body", QueryFieldType.STRING, true);
        addPayload(bindings, schema, payloadColumns, "response_body", QueryFieldType.STRING, true);
        addPayload(bindings, schema, payloadColumns, "assembled_response", QueryFieldType.STRING, true);
        final EnrichmentJoin payloadJoin = new EnrichmentJoin(
                DSL.table(DSL.name(PAYLOAD)),
                DSL.field(DSL.name(ENTITY, "event_id")).eq(DSL.field(DSL.name(PAYLOAD, "event_id"))), null, null, null, null,
                Set.copyOf(payloadColumns), null);
        // Partition-local, as the catalog builds it: dial_usage_log partitions on a sort-key column and the
        // payload enrichment partitions on the same request_time it copies from its parent, so every read of
        // the entity deduplicates inside each partition (the default path the executor tests exercise).
        return new QueryEntityModel(
                ENTITY, DSL.table(DSL.name(ENTITY)), bindings, List.of(payloadJoin), List.copyOf(schema), null, true);
    }

    /**
     * {@link #model()} with the partition-locality claim withdrawn — the shape the catalog builds for an
     * entity one of whose upsert tables partitions outside its key — so a test can pin what the executor
     * withholds when it cannot vouch for the partition.
     */
    public static QueryEntityModel crossPartitionModel() {
        final QueryEntityModel base = model();
        return new QueryEntityModel(
                ENTITY, base.table(), base.bindings(), base.enrichments(), base.baseSchema(), null, false);
    }

    /**
     * The same model plus one extra {@code long} column, for the cases where an entity column name
     * collides with a derived output name (an entity owning a column called {@code count}).
     */
    public static QueryEntityModel modelWithColumn(String name) {
        final QueryEntityModel base = model();
        final Map<String, QueryFieldBinding> bindings = new LinkedHashMap<>(base.bindings());
        final List<QuerySchemaFieldDto> schema = new ArrayList<>(base.baseSchema());
        add(bindings, schema, name, QueryFieldType.LONG);
        return new QueryEntityModel(
                ENTITY, base.table(), bindings, base.enrichments(), List.copyOf(schema), null, base.partitionLocal());
    }

    /**
     * The same model plus one ACTIVE enrichment, exposing a single column under its qualified flat name and
     * carrying the grain join that reaches it — the shape {@code CatalogQueryService} builds for a source
     * that has one. Bindings render as two-part physical references either way, so a statement that resolves
     * {@link #ENRICHMENT_COLUMN} without emitting the join is valid-looking SQL that fails at execution;
     * that is what the pipeline render tests use this to pin.
     */
    public static QueryEntityModel modelWithEnrichment() {
        final QueryEntityModel base = model();
        final Map<String, QueryFieldBinding> bindings = new LinkedHashMap<>(base.bindings());
        final List<QuerySchemaFieldDto> schema = new ArrayList<>(base.baseSchema());
        final QueryFieldType type = QueryFieldType.STRING;
        final Field<?> field = DSL.field(DSL.name(ENRICHMENT, "client_session_id"), JooqTypes.of(type));
        bindings.put(ENRICHMENT_COLUMN, new QueryFieldBinding(ENRICHMENT_COLUMN, field, type, null, false));
        schema.add(new QuerySchemaFieldDto(
                ENRICHMENT_COLUMN, type, null, null, "client_session_id", null, null, null, false, false));
        final EnrichmentJoin join = new EnrichmentJoin(
                DSL.table(DSL.name(ENRICHMENT)),
                DSL.field(DSL.name(ENTITY, "event_id")).eq(DSL.field(DSL.name(ENRICHMENT, "event_id"))), null, null, null, null,
                Set.of(ENRICHMENT_COLUMN), null);
        // Added to the base model's joins, not substituted for them: the payload enrichment is part of
        // the shipped catalog, so an entity with a second enrichment has two.
        final List<EnrichmentJoin> joins = new ArrayList<>(base.enrichments());
        joins.add(join);
        return new QueryEntityModel(
                ENTITY, base.table(), bindings, List.copyOf(joins), List.copyOf(schema), null, base.partitionLocal());
    }

    /**
     * {@link #modelWithEnrichment()} with the enrichment joined the way {@code CatalogQueryService} actually
     * builds it — keyed on its grain-key fields, so it can be emitted as a derived table — and exposing a
     * string, a timestamp and an array column besides {@link #ENRICHMENT_COLUMN}. A separate model rather than
     * a change to that one, because the unkeyed join is what every other statement test there pins.
     */
    public static QueryEntityModel modelWithKeyedEnrichment() {
        final QueryEntityModel base = model();
        final Map<String, QueryFieldBinding> bindings = new LinkedHashMap<>(base.bindings());
        final List<QuerySchemaFieldDto> schema = new ArrayList<>(base.baseSchema());
        final Set<String> exposed = new LinkedHashSet<>();
        addEnrichment(bindings, schema, exposed, ENRICHMENT_COLUMN, QueryFieldType.STRING);
        addEnrichment(bindings, schema, exposed, ENRICHMENT_TYPE_COLUMN, QueryFieldType.STRING);
        addEnrichment(bindings, schema, exposed, ENRICHMENT_TIME_COLUMN, QueryFieldType.TIMESTAMP);
        addEnrichment(bindings, schema, exposed, ENRICHMENT_ARRAY_COLUMN, QueryFieldType.ARRAY);
        final EnrichmentJoin join = EnrichmentJoin.keyed(
                DSL.table(DSL.name(ENRICHMENT)),
                DSL.field(DSL.name(ENTITY, "event_id")),
                DSL.field(DSL.name(ENRICHMENT, "event_id")), null, null,
                Set.copyOf(exposed), null);
        final List<EnrichmentJoin> joins = new ArrayList<>(base.enrichments());
        joins.add(join);
        return new QueryEntityModel(
                ENTITY, base.table(), bindings, List.copyOf(joins), List.copyOf(schema), null, base.partitionLocal());
    }

    private static void addEnrichment(
            Map<String, QueryFieldBinding> bindings, List<QuerySchemaFieldDto> schema, Set<String> exposed,
            String name, QueryFieldType type) {
        final String column = name.substring(name.indexOf('.') + 1);
        final QueryFieldType elementType = type == QueryFieldType.ARRAY ? QueryFieldType.STRING : null;
        final Field<?> field = DSL.field(DSL.name(ENRICHMENT, column), JooqTypes.of(type));
        bindings.put(name, new QueryFieldBinding(name, field, type, elementType, false));
        schema.add(new QuerySchemaFieldDto(name, type, elementType, null, column, null, null, null, false, false));
        exposed.add(name);
    }

    /**
     * A one-column model whose only column is {@code heavy}, for the "a wildcard would return nothing"
     * rejection path.
     */
    public static QueryEntityModel allHeavyModel() {
        final QueryFieldType type = QueryFieldType.STRING;
        final QueryFieldBinding binding = new QueryFieldBinding(
                "payload", DSL.field(DSL.name("dumps", "payload"), JooqTypes.of(type)), type, null, true);
        return new QueryEntityModel(
                "dumps", DSL.table(DSL.name("dumps")), Map.of("payload", binding), List.of(),
                List.of(new QuerySchemaFieldDto("payload", type, null, null, "payload", null, null, null, false, true)));
    }

    private static void add(
            Map<String, QueryFieldBinding> bindings, List<QuerySchemaFieldDto> schema, String name, QueryFieldType type) {
        add(bindings, schema, name, type, false);
    }

    private static void add(
            Map<String, QueryFieldBinding> bindings, List<QuerySchemaFieldDto> schema, String name,
            QueryFieldType type, boolean heavy) {
        final Field<?> field = DSL.field(DSL.name(ENTITY, name), JooqTypes.of(type));
        // The catalog backfill declares execution_path as array<string>; every other column is scalar.
        final QueryFieldType elementType = type == QueryFieldType.ARRAY ? QueryFieldType.STRING : null;
        bindings.put(name, new QueryFieldBinding(name, field, type, elementType, heavy));
        schema.add(new QuerySchemaFieldDto(name, type, elementType, null, name, null, null, null, false, heavy));
    }

    /**
     * Adds a column of the payload enrichment: published under its qualified flat name, backed by the
     * two-part physical reference {@code dial_usage_log_payload.<column>}, and recorded in
     * {@code exposed} so the grain join knows which references fire it.
     */
    private static void addPayload(
            Map<String, QueryFieldBinding> bindings, List<QuerySchemaFieldDto> schema, Set<String> exposed,
            String name, QueryFieldType type, boolean heavy) {
        final String flatName = payload(name);
        final Field<?> field = DSL.field(DSL.name(PAYLOAD, name), JooqTypes.of(type));
        bindings.put(flatName, new QueryFieldBinding(flatName, field, type, null, heavy));
        schema.add(new QuerySchemaFieldDto(flatName, type, null, null, name, null, null, null, false, heavy));
        exposed.add(flatName);
    }
}
