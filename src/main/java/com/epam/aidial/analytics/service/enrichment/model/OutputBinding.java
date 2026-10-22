package com.epam.aidial.analytics.service.enrichment.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Maps a transform output variable to a physical column on the target enrichment, stored inside
 * the pipeline's {@code output_bindings} JSONB list. The governance gate verifies the {@code column}
 * exists on the target enrichment and that its ClickHouse type is compatible with the output
 * variable's logical type. Unknown properties are tolerated.
 */
@Getter
@Setter
@EqualsAndHashCode
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class OutputBinding {

    /** The physical enrichment column this output is written to. */
    private String column;

    /** The transform output variable that supplies the value. */
    private String var;
}
