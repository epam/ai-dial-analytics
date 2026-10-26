package com.epam.aidial.analytics.integration;

import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests for the {@code /v1/queries} schema-discovery endpoints against the real
 * catalog-backed {@code CatalogQueryService} (no mocks) — verifying the {@code dial_usage_log} source
 * (seeded by the catalog backfill migration) and its flat schema are published as the wire contract
 * describes, and that an unknown entity maps to the {@code ErrorView} 404.
 */
class QuerySchemaDiscoveryIntegrationTest extends AbstractClickHouseIntegrationTest {

    @Test
    void listsTheSeededSourceEntities() throws Exception {
        // Both catalog-seeded SOURCE tables are discoverable (order-independent).
        mockMvc.perform(get("/v1/queries/entities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].name", containsInAnyOrder("dial_usage_log", "rate_analytics")));
    }

    @Test
    void returnsTheFlatBaseSchema() throws Exception {
        mockMvc.perform(get("/v1/queries/entities/schema/dial_usage_log"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entity").value("dial_usage_log"))
                // All 34 columns dial_usage_log still owns (order is asserted separately — the seeded
                // tag_order groups/sorts them, so these checks stay order-independent by name). 34, not
                // the 39 it declared before the payload split: the five verbatim-content columns are
                // dial_usage_log_payload's now, and are counted below under their qualified names.
                //
                // Counted on the UNQUALIFIED names only. A flat entity schema also publishes each ACTIVE
                // enrichment's columns under `<enrichment>.<column>`, so a total would depend on which
                // enrichments happen to exist — which, against the shared container, means on test order.
                // It was already so; provenance injection merely made the dependency visible by adding
                // five columns to every enrichment created through the API. That is why this filters
                // rather than asserting the 39-field total the pre-split test could safely assert.
                .andExpect(jsonPath("$.fields[?(!(@.name =~ /.*\\..*/))]", hasSize(36)))
                // The five that moved and the cache markers (V50), under the qualified names the grain join publishes them at.
                .andExpect(jsonPath("$.fields[?(@.name =~ /^dial_usage_log_payload\\..*/)]", hasSize(6)))
                .andExpect(jsonPath("$.fields[?(@.name=='event_id')].type", contains("uuid")))
                // field types are surfaced per the schema (filtered by name to stay order-independent).
                .andExpect(jsonPath("$.fields[?(@.name=='project_id')].type", contains("string")))
                .andExpect(jsonPath("$.fields[?(@.name=='total_tokens')].type", contains("long")))
                .andExpect(jsonPath("$.fields[?(@.name=='execution_path')].type", contains("array")))
                // The one array field carries its element type; scalar fields carry none.
                .andExpect(jsonPath("$.fields[?(@.name=='execution_path')].element_type", contains("string")))
                .andExpect(jsonPath("$.fields[?(@.name=='project_id')].element_type", hasSize(0)))
                .andExpect(jsonPath("$.fields[?(@.name=='total_price')].type", contains("decimal")))
                .andExpect(jsonPath("$.fields[?(@.name=='dial_usage_log_payload.jwt_claims')].type", contains("object")))
                .andExpect(jsonPath("$.fields[?(@.name=='dial_usage_log_payload.request_body')].type", contains("string")))
                // Seeded sensitivity is surfaced on the flat schema. Security is off in tests, so the
                // filter fails open and every column is still returned — only the flag differs. The
                // identity/raw-content columns are sensitive; dial_usage_log_payload.request_tags joins them because it carries
                // the request headers verbatim. user_hash stays visible (de-identified surrogate).
                .andExpect(jsonPath("$.fields[?(@.name=='dial_usage_log_payload.request_tags')].sensitive", contains(true)))
                .andExpect(jsonPath("$.fields[?(@.name=='dial_usage_log_payload.jwt_claims')].sensitive", contains(true)))
                .andExpect(jsonPath("$.fields[?(@.name=='dial_usage_log_payload.request_body')].sensitive", contains(true)))
                .andExpect(jsonPath("$.fields[?(@.name=='dial_usage_log_payload.response_body')].sensitive", contains(true)))
                // The merged response (V29) joins them on the same grounds: it stores the model's answer
                // verbatim, which is user-visible content. Its shape differs from a raw body; its
                // sensitivity does not.
                .andExpect(jsonPath("$.fields[?(@.name=='dial_usage_log_payload.assembled_response')].sensitive", contains(true)))
                .andExpect(jsonPath("$.fields[?(@.name=='user_hash')].sensitive", contains(false)))
                // Seeded heaviness (V18, extended by V29): the three payload columns are the ones a
                // wildcard leaves behind, and the flag is published so a client can see that before
                // querying. It is a cost hint, not access control — every other column reports false,
                // including the small *_bytes columns and the object columns.
                .andExpect(jsonPath("$.fields[?(@.name=='dial_usage_log_payload.request_body')].heavy", contains(true)))
                .andExpect(jsonPath("$.fields[?(@.name=='dial_usage_log_payload.response_body')].heavy", contains(true)))
                .andExpect(jsonPath("$.fields[?(@.name=='dial_usage_log_payload.assembled_response')].heavy", contains(true)))
                .andExpect(jsonPath("$.fields[?(@.name=='request_body_bytes')].heavy", contains(false)))
                .andExpect(jsonPath("$.fields[?(@.name=='dial_usage_log_payload.jwt_claims')].heavy", contains(false)))
                .andExpect(jsonPath("$.fields[?(@.name=='project_id')].heavy", contains(false)))
                // Tags seeded on the backfill columns are surfaced on the flat schema. The attribution
                // columns are tagged by subject (`principal`/`deployment`); the correlation keys are
                // `identifier`, event_kind is a `dimension`, and the measurement columns keep their
                // role tags (`token-usage`, `cost`).
                .andExpect(jsonPath("$.fields[?(@.name=='event_id')].tag", contains("identifier")))
                .andExpect(jsonPath("$.fields[?(@.name=='trace_id')].tag", contains("identifier")))
                .andExpect(jsonPath("$.fields[?(@.name=='event_kind')].tag", contains("dimension")))
                .andExpect(jsonPath("$.fields[?(@.name=='project_id')].tag", contains("principal")))
                .andExpect(jsonPath("$.fields[?(@.name=='deployment')].tag", contains("deployment")))
                .andExpect(jsonPath("$.fields[?(@.name=='total_tokens')].tag", contains("token-usage")))
                .andExpect(jsonPath("$.fields[?(@.name=='total_price')].tag", contains("cost")))
                // Column descriptions backfilled by the migration are surfaced on the flat schema, and
                // every one of the 42 columns (36 own, 6 payload) carries a non-empty description.
                .andExpect(jsonPath("$.fields[?(@.description != null && @.description != '')]", hasSize(42)))
                .andExpect(jsonPath("$.fields[?(@.name=='event_id')].description",
                        contains("Unique identifier of this usage-log event.")))
                .andExpect(jsonPath("$.fields[?(@.name=='total_price')].description",
                        contains("The total cost of the request (this request plus all related requests), as computed at "
                                + "request-execution time; later cost adjustments (pricing-config fixes, provider discounts, "
                                + "etc.) are not reflected here.")))
                .andExpect(jsonPath("$.fields[?(@.name=='prompt_tokens')].description",
                        contains("Number of prompt (input) tokens consumed.")));
    }

    @Test
    void everyRateAnalyticsColumnIsDescribed() throws Exception {
        mockMvc.perform(get("/v1/queries/entities/schema/rate_analytics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields", hasSize(10)))
                // All 10 exposed columns carry a non-empty description after the backfill migration
                // (8 analytics columns, the event_id identity column registered by V15, and rate_raw
                // registered by V31).
                .andExpect(jsonPath("$.fields[?(@.description != null && @.description != '')]", hasSize(10)))
                .andExpect(jsonPath("$.fields[?(@.name=='request_time')].description",
                        contains("Timestamp when the rating request was made.")))
                .andExpect(jsonPath("$.fields[?(@.name=='response_id')].description",
                        contains("Rated response id, from the request body responseId.")))
                .andExpect(jsonPath("$.fields[?(@.name=='event_id')].type", contains("uuid")))
                .andExpect(jsonPath("$.fields[?(@.name=='event_id')].description",
                        contains("Unique identifier of this rate event.")));
    }

    @Test
    void groupsAttributionColumnsAndKeepsIdentifierAndDimension() throws Exception {
        // dial_usage_log: the attribution columns are grouped by subject — who (`principal`), what
        // (`deployment`). The correlation keys keep the role tag `identifier`, event_kind and
        // request_time are `dimension`, and the retired identity/lineage tags are gone.
        mockMvc.perform(get("/v1/queries/entities/schema/dial_usage_log"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields[?(@.tag=='identity')]", hasSize(0)))
                .andExpect(jsonPath("$.fields[?(@.tag=='lineage')]", hasSize(0)))
                .andExpect(jsonPath("$.fields[?(@.tag=='principal')].name", containsInAnyOrder(
                        "project_id", "user_hash")))
                .andExpect(jsonPath("$.fields[?(@.tag=='deployment')].name", containsInAnyOrder(
                        "deployment", "parent_deployment", "execution_path", "route_name", "route_type")))
                .andExpect(jsonPath("$.fields[?(@.tag=='identifier')].name", containsInAnyOrder(
                        "chat_id", "trace_id", "core_span_id", "core_parent_span_id", "event_id",
                        "response_id")))
                .andExpect(jsonPath("$.fields[?(@.name=='event_kind')].tag", contains("dimension")))
                .andExpect(jsonPath("$.fields[?(@.name=='request_time')].tag", contains("dimension")));

        // rate_analytics: the same concepts are tagged consistently on the columns it exposes —
        // project_id/user_hash `principal`, deployment `deployment`, chat_id/event_id/response_id
        // `identifier`, request_time `dimension`, and no identity/lineage remains.
        mockMvc.perform(get("/v1/queries/entities/schema/rate_analytics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields[?(@.tag=='identity')]", hasSize(0)))
                .andExpect(jsonPath("$.fields[?(@.tag=='lineage')]", hasSize(0)))
                .andExpect(jsonPath("$.fields[?(@.tag=='principal')].name", containsInAnyOrder(
                        "project_id", "user_hash")))
                .andExpect(jsonPath("$.fields[?(@.name=='deployment')].tag", contains("deployment")))
                .andExpect(jsonPath("$.fields[?(@.tag=='identifier')].name", containsInAnyOrder(
                        "chat_id", "event_id", "response_id")))
                .andExpect(jsonPath("$.fields[?(@.name=='request_time')].tag", contains("dimension")))
                // rate_raw joins rate and comment under `request`: all three are submitted with the
                // rating rather than being facts about the call.
                .andExpect(jsonPath("$.fields[?(@.tag=='request')].name", containsInAnyOrder(
                        "rate", "rate_raw", "comment")));
    }

    @Test
    void rawRateIsExposedAsPlainStringNeitherSensitiveNorHeavy() throws Exception {
        // Deliberately not sensitive, unlike the `comment` beside it: gating a rating scalar behind
        // FULL_ADMIN would put the exact thumbs-down count out of reach for no privacy gain. Not heavy
        // either, so a wildcard projection returns it. Both are catalog-metadata decisions (V31),
        // invisible in any DDL, so this is the only place they are checked.
        mockMvc.perform(get("/v1/queries/entities/schema/rate_analytics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields[?(@.name=='rate_raw')].type", contains("string")))
                .andExpect(jsonPath("$.fields[?(@.name=='rate_raw')].sensitive", contains(false)))
                .andExpect(jsonPath("$.fields[?(@.name=='rate_raw')].heavy", contains(false)))
                // The sensitive one on this table is still `comment`, which this must not have copied.
                .andExpect(jsonPath("$.fields[?(@.name=='comment')].sensitive", contains(true)));
    }

    @Test
    void fieldsAreGroupedAndSortedByTheSeededTagOrder() throws Exception {
        // V10 seeds dial_usage_log with tag_order [identifier, dimension, principal, deployment, ...].
        // The discovery response surfaces that ordering verbatim and returns the fields grouped by it,
        // alphabetical by name within each group. The two leading groups are fully deterministic:
        //   identifier (6): chat_id, core_parent_span_id, core_span_id, event_id, response_id, trace_id
        //   dimension  (2): event_kind, request_time
        mockMvc.perform(get("/v1/queries/entities/schema/dial_usage_log"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tag_order[0]").value("identifier"))
                .andExpect(jsonPath("$.tag_order[1]").value("dimension"))
                .andExpect(jsonPath("$.fields[0].name").value("chat_id"))
                .andExpect(jsonPath("$.fields[1].name").value("core_parent_span_id"))
                .andExpect(jsonPath("$.fields[2].name").value("core_span_id"))
                .andExpect(jsonPath("$.fields[3].name").value("event_id"))
                .andExpect(jsonPath("$.fields[4].name").value("response_id"))
                .andExpect(jsonPath("$.fields[5].name").value("trace_id"))
                .andExpect(jsonPath("$.fields[6].name").value("event_kind"))
                .andExpect(jsonPath("$.fields[7].name").value("request_time"));
    }

    @Test
    void unknownEntityMapsToNotFound() throws Exception {
        mockMvc.perform(get("/v1/queries/entities/schema/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("not_found"));
    }
}
