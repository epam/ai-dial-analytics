package com.epam.aidial.analytics.service.table;

import com.epam.aidial.analytics.web.dto.query.QueryFieldType;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Persists {@link QueryFieldType} as its lowercase wire code (e.g. {@code "string"}) rather than the
 * enum constant name, keeping the catalog's stored type vocabulary identical to the DSL/API wire
 * form. Applied automatically to every {@code QueryFieldType} attribute.
 */
@Converter(autoApply = true)
public class QueryFieldTypeConverter implements AttributeConverter<QueryFieldType, String> {

    @Override
    public String convertToDatabaseColumn(QueryFieldType attribute) {
        return attribute == null ? null : attribute.code();
    }

    @Override
    public QueryFieldType convertToEntityAttribute(String dbData) {
        return QueryFieldType.fromCode(dbData);
    }
}
