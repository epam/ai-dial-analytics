package com.epam.aidial.analytics.service.pipeline.preview;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The closed set of preview note codes, each with the one severity it is always reported at. A client
 * switches on the code; the note's message is for people. A new code is added only by amending the
 * pipeline-registry spec's list, which this enum mirrors.
 */
public enum PreviewNoteCode {
    NO_MATCHING_ROWS("no_matching_rows", PreviewNoteSeverity.WARNING),
    HELD_BY_ENRICHMENT("held_by_enrichment", PreviewNoteSeverity.WARNING),
    ENRICHMENT_BEHIND("enrichment_behind", PreviewNoteSeverity.INFO),
    TARGET_TYPE_NARROWING("target_type_narrowing", PreviewNoteSeverity.WARNING),
    VALUE_OUT_OF_TARGET_RANGE("value_out_of_target_range", PreviewNoteSeverity.WARNING),
    NO_GROUP_FOUND("no_group_found", PreviewNoteSeverity.WARNING),
    GROUP_CUT_BY_FETCH_LIMIT("group_cut_by_fetch_limit", PreviewNoteSeverity.WARNING),
    OUTPUT_WITHOUT_DESCRIPTION("output_without_description", PreviewNoteSeverity.INFO),
    UNCONSTRAINED_RESPONSE("unconstrained_response", PreviewNoteSeverity.WARNING),
    VALUES_TRUNCATED("values_truncated", PreviewNoteSeverity.INFO);

    private final String code;
    private final PreviewNoteSeverity severity;

    PreviewNoteCode(String code, PreviewNoteSeverity severity) {
        this.code = code;
        this.severity = severity;
    }

    @JsonValue
    public String code() {
        return code;
    }

    public PreviewNoteSeverity severity() {
        return severity;
    }
}
