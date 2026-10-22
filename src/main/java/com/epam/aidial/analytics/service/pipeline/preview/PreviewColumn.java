package com.epam.aidial.analytics.service.pipeline.preview;

import com.epam.aidial.analytics.service.query.QueryFieldBinding;
import com.epam.aidial.analytics.web.dto.query.QueryFieldType;

/**
 * One column of a preview sample.
 *
 * @param type       the type of the value as sampled
 * @param targetType the type of the target column the value is written to; null for a {@code grain_key} or
 *                   an {@code input}, which write nothing
 * @param maxBytes   the largest UTF-8 size of a non-null sampled value, measured before the value cap; null
 *                   for a type that is not measured or a column that sampled no non-null value
 */
public record PreviewColumn(String name, PreviewColumnRole role, QueryFieldType type, QueryFieldType targetType,
        Long maxBytes) {

    public PreviewColumn(String name, PreviewColumnRole role, QueryFieldType type, QueryFieldType targetType) {
        this(name, role, type, targetType, null);
    }

    /**
     * A column read straight off the source — a grain key or a bound input — typed by its binding, and written
     * nowhere. A name the role-filtered model carries no binding for is read through the raw-name fallback, which
     * only an unannotated base column reaches, and is reported as {@code string}.
     */
    public static PreviewColumn read(String name, PreviewColumnRole role, QueryFieldBinding binding) {
        return new PreviewColumn(name, role, binding == null ? QueryFieldType.STRING : binding.type(), null);
    }

    PreviewColumn withMaxBytes(Long measured) {
        return new PreviewColumn(name, role, type, targetType, measured);
    }
}
