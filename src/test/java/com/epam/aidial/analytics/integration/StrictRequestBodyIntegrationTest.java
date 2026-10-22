package com.epam.aidial.analytics.integration;

import com.epam.aidial.analytics.service.pipeline.PipelineEntity;
import com.epam.aidial.analytics.service.pipeline.PipelineKind;
import com.epam.aidial.analytics.service.pipeline.PipelineSpecCodec;
import com.epam.aidial.analytics.service.pipeline.model.EnrichSpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A request body carrying a field the endpoint does not know is refused, and the refusal names the field.
 *
 * <p>Both cases below were found accepted-and-ignored against a running service, which is the failure mode
 * this guards: every member of these request records is optional, so validation cannot catch a misspelling —
 * the request succeeds having done something other than what the caller wrote. The nested case is the worse
 * of the two, because {@code member_select} decides which of a group's rows a transform is shown.
 *
 * <p>The last test is the other half of the contract, and the reason the strict mapper is scoped to the web
 * converter rather than applied to the primary one: JSON that has already been <b>persisted</b> must stay
 * readable when it carries a member the current code no longer models. {@code V36__fold_rule_into_pipeline}
 * depends on exactly that, and a spec column is not a request body — nobody can be asked to fix it.
 */
class StrictRequestBodyIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private PipelineSpecCodec specCodec;

    @Test
    void unknownTopLevelFieldIsRefusedAndNamed() throws Exception {
        mockMvc.perform(post("/v1/tables").contentType("application/json").content("""
                        {"name":"strict_probe","type":"source","write":"upsert_by_key","tyep":"source"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value(
                        "The request body carries an unrecognized field 'tyep'"));
    }

    /**
     * {@code member_select} takes {@code order_by} (a list), not {@code order}. Before this the pipeline was
     * created with no member ordering at all and answered 201.
     */
    @Test
    void unknownNestedFieldIsRefusedAndNamed() throws Exception {
        mockMvc.perform(post("/v1/pipelines").contentType("application/json").content("""
                        {"name":"strict-probe","kind":"enrich",
                         "target":"strict_target","enabled":false,
                         "trigger":{"kind":"group","group_by":"chat_id",
                                    "member_select":{"limit":3,"order":{"column":"request_time"}}}}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"))
                .andExpect(jsonPath("$.message").value(
                        "The request body carries an unrecognized field 'order'"));
    }

    /**
     * The retired evaluator pointers belong to no kind, so each is an unknown member on either one and is
     * named by the binding, before any cross-kind check (capability {@code enrichment-registry}, "Naming an
     * evaluator is refused by name").
     */
    @Test
    void retiredEvaluatorPointersAreRefusedAndNamed() throws Exception {
        final Map<String, String> bodies = Map.of(
                "evaluator_name", "{\"name\":\"strict-retired\",\"kind\":\"enrich\",\"evaluator_name\":\"toxicity\"}",
                "evaluator_version", "{\"name\":\"strict-retired\",\"kind\":\"aggregate\",\"evaluator_version\":1}");
        for (Map.Entry<String, String> body : bodies.entrySet()) {
            mockMvc.perform(post("/v1/pipelines").contentType("application/json").content(body.getValue()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("bad_request"))
                    .andExpect(jsonPath("$.message").value("The request body carries an unrecognized field '" + body.getKey() + "'"));
        }
        mockMvc.perform(patch("/v1/pipelines/strict-retired").contentType("application/json").content("""
                        {"evaluator_name":"toxicity"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The request body carries an unrecognized field 'evaluator_name'"));
        mockMvc.perform(get("/v1/pipelines/strict-retired"))
                .andExpect(status().isNotFound());
    }

    /** A well-formed body is untouched by the swap — the converter still reads and writes normally. */
    @Test
    void wellFormedBodyStillSucceeds() throws Exception {
        mockMvc.perform(post("/v1/tables").contentType("application/json").content("""
                        {"name":"strict_ok","type":"source","write":"upsert_by_key"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("strict_ok"))
                .andExpect(jsonPath("$.status").value("pending"));
    }

    @Test
    void persistedSpecStillToleratesAnUnknownMember() {
        final PipelineEntity pipeline = new PipelineEntity();
        pipeline.setName("strict-persisted");
        pipeline.setKind(PipelineKind.ENRICH);
        pipeline.setSpec("{\"rows_per_scan\":15,\"retired_member\":\"whatever\"}");

        final EnrichSpec spec = specCodec.readEnrich(pipeline);

        assertThat(spec.getBatchScanLimit()).isEqualTo(15);
    }
}
