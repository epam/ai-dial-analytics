package com.epam.aidial.analytics.configuration;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds the application's primary {@link JsonMapper}.
 *
 * <p>The Jackson 2/3 package split here is intentional and required, not an inconsistency to "clean up":
 * Jackson 3 moved databind to {@code tools.jackson.*} (hence {@link JsonMapper}, {@code MapperFeature},
 * {@code DeserializationFeature}, {@code EnumFeature}) but kept the public annotations under the original
 * {@code com.fasterxml.jackson.annotation.*} package (hence {@link JsonInclude}). Switching either group
 * to the other package will not compile.
 */
@Configuration
public class JsonMapperConfiguration {

    @Bean
    @Primary
    public JsonMapper jsonMapper() {
        return JsonMapper.builder()
                .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                .enable(EnumFeature.WRITE_ENUMS_TO_LOWERCASE)
                .disable(EnumFeature.READ_ENUMS_USING_TO_STRING)
                .disable(EnumFeature.WRITE_ENUMS_USING_TO_STRING)
                .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                // value inclusion NON_NULL omits null bean properties (e.g. an absent totalCount); content
                // inclusion ALWAYS keeps null values *inside* containers, so a projected query column that is
                // null (notably an unmatched LEFT-JOINed enrichment column) still appears as a null cell in the
                // result-row map rather than vanishing from the JSON.
                .changeDefaultPropertyInclusion(incl -> incl
                        .withValueInclusion(JsonInclude.Include.NON_NULL)
                        .withContentInclusion(JsonInclude.Include.ALWAYS))
                .build();
    }
}
