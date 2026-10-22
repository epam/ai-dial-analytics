package com.epam.aidial.analytics.web.mapper.query;

import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.saved.SavedQueryEntity;
import com.epam.aidial.analytics.service.query.saved.SavedQueryJson;
import com.epam.aidial.analytics.service.query.saved.model.SavedQueryChart;
import com.epam.aidial.analytics.service.query.saved.model.SavedQueryTime;
import com.epam.aidial.analytics.web.dto.query.saved.SavedQueryDto;
import com.epam.aidial.analytics.web.dto.query.saved.SavedQueryListDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Maps stored saved queries onto their read DTO.
 *
 * <p>Hand-written rather than a MapStruct mapper, for the same reason {@code PipelineMapper} hand-writes
 * its top-level conversions: this is not field copying. The entity holds its JSON payloads as text (see
 * {@code SavedQueryJson} for why Hibernate cannot map them directly), so the read direction is a parse,
 * and expressing that as MapStruct {@code expression = "java(…)"} strings would trade compile-checked
 * code for unchecked text.
 *
 * <p>There is one read shape: the list and the single read return the same object. Only the read
 * direction exists at all — the write direction stays in {@code SavedQueryService}, because a request
 * carries a subset of the entity's fields and the rest are server-assigned, so a generated writer would
 * have to be told to skip more fields than it copies, and a field added later would then be silently
 * copied from the request instead of rejected.
 */
@Component
@RequiredArgsConstructor
public class SavedQueryMapper {

    private final SavedQueryJson json;

    /** The read shape: identity, presentation, and the parsed body. */
    public SavedQueryDto toDto(SavedQueryEntity entity) {
        return new SavedQueryDto(
                entity.getId(),
                entity.getName(),
                entity.getDescription(),
                entity.getTag(),
                entity.getScope(),
                entity.getOwnerId(),
                entity.getOwnerEmail(),
                entity.getSource(),
                json.read(entity.getQuery(), StructuredQuery.class),
                entity.getSqlText(),
                json.read(entity.getTimeIntent(), SavedQueryTime.class),
                entity.getResultView(),
                json.read(entity.getChart(), SavedQueryChart.class),
                entity.getGeneration(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    public SavedQueryListDto toListDto(List<SavedQueryEntity> entities) {
        return new SavedQueryListDto(entities.stream().map(this::toDto).toList());
    }
}
