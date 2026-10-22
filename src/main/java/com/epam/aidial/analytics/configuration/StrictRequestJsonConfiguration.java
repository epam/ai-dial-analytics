package com.epam.aidial.analytics.configuration;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Makes HTTP <em>request</em> bodies reject unknown properties, and only HTTP request bodies.
 *
 * <p>Without this a misspelled field is accepted and silently dropped, so the request succeeds while doing
 * something other than what the caller wrote. Two examples of the failure: a
 * {@code member_select} carrying {@code "order"} instead of {@code "order_by"} was stored with no ordering
 * at all — silently changing which members a transform sees — and a {@code PATCH /v1/tables/{name}/schema}
 * carrying {@code "columns"} instead of {@code "add"} answered {@code 200} having changed nothing. Every
 * member of those request records is optional, so neither could be caught by validation.
 *
 * <p><b>Why a separate mapper rather than flipping the primary one.</b> The {@link JsonMapper} from
 * {@link JsonMapperConfiguration} is not only the web converter's: it also reads persisted JSON
 * ({@code PipelineSpecCodec}, saved-query bodies, the usage-log writer) and third-party JSON (DIAL Core's
 * api-key introspection response). Both of those <em>require</em> leniency, and one is load-bearing for a
 * migration: {@code V36__fold_rule_into_pipeline.sql} assembles a spec whose members are mostly JSON nulls
 * and states outright that "both specs tolerate unknown and absent properties". Strictness there would turn
 * an added-then-removed spec field, or a new field in Core's response, into a hard failure on data already
 * written. Requests are the opposite case: the body arrived this instant from a caller who can fix it, and
 * telling them is strictly better than guessing.
 *
 * <p>Serialization is unaffected — {@code FAIL_ON_UNKNOWN_PROPERTIES} governs reads only, so responses are
 * byte-identical to those the primary mapper produces. Bodies bound as {@code JsonNode} (the OTLP ingest
 * path) and as {@code Map<String, Object>} (a row write's arbitrary physical column names) are likewise
 * unaffected: neither has bean properties for a key to be unknown against.
 */
@Configuration
public class StrictRequestJsonConfiguration implements WebMvcConfigurer {

    private final JsonMapper strictMapper;

    public StrictRequestJsonConfiguration(JsonMapper jsonMapper) {
        this.strictMapper = jsonMapper.rebuild()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    /**
     * Substitutes the JSON converter rather than mutating it: a Jackson 3 mapper is immutable once built, so
     * the strict copy has to arrive inside a new converter. {@code withJsonConverter} is the supported seam
     * for that — it replaces the one Boot would have built from the primary mapper and leaves every other
     * converter (string, byte-array, form, Arrow) untouched and in order.
     */
    @Override
    public void configureMessageConverters(HttpMessageConverters.ServerBuilder builder) {
        builder.withJsonConverter(new JacksonJsonHttpMessageConverter(strictMapper));
    }
}
