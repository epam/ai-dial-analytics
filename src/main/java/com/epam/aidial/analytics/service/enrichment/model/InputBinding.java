package com.epam.aidial.analytics.service.enrichment.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Binds a transform input variable to a scanned source row, stored inside the pipeline's
 * {@code input_bindings} JSONB list. A binding is either <em>bound</em> — it names a source
 * {@code column} directly — or a {@code jsonata} expression over the row. Both forms may reference
 * source columns, which the governance gate checks for entitlement (a {@code sensitive} column
 * needs an explicit grant). ADAS parses the JSONata (parse-only) at registration; runtime
 * evaluation is the runner's. Unknown properties are tolerated.
 */
@Getter
@Setter
@EqualsAndHashCode
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class InputBinding {

    /** The transform input variable this binding supplies. */
    private String var;

    /** A source column bound directly (the {@code bound} form); null when a JSONata expr is used. */
    private String column;

    /** A JSONata expression over the scanned row; null when a column is bound directly. */
    private String jsonata;
}
