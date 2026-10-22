package com.epam.aidial.analytics.service.pipeline;

import com.epam.aidial.analytics.service.enrichment.model.TriggerKind;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Persists {@link TriggerKind} as its constant name, exactly as {@code @Enumerated(STRING)} did, and reads the
 * retired {@code ON_INGEST} back as {@link TriggerKind#SCHEDULE}.
 *
 * <p>The read mapping exists for <b>audit history</b>, not for live rows. {@code V44__retire_on_ingest_trigger}
 * rewrites every live {@code pipeline} row, but {@code pipeline_aud} keeps the revisions written before it
 * unchanged, and Envers hydrates those into this entity. Under {@code @Enumerated} a single historical
 * {@code ON_INGEST} revision would fail to load and turn the audit snapshot endpoint into a 500. History stays
 * as it was written, and it is read in the current vocabulary: {@code on_ingest} was a row-grain trigger that
 * differed from {@code schedule} only in its clock.
 */
@Converter
public class TriggerKindConverter implements AttributeConverter<TriggerKind, String> {

    private static final String RETIRED_ON_INGEST = "ON_INGEST";

    @Override
    public String convertToDatabaseColumn(TriggerKind attribute) {
        return attribute == null ? null : attribute.name();
    }

    @Override
    public TriggerKind convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        return RETIRED_ON_INGEST.equals(dbData) ? TriggerKind.SCHEDULE : TriggerKind.valueOf(dbData);
    }
}
