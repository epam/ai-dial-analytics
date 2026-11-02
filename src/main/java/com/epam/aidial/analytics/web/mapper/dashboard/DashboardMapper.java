package com.epam.aidial.analytics.web.mapper.dashboard;

import com.epam.aidial.analytics.service.dashboard.DashboardAccess;
import com.epam.aidial.analytics.service.dashboard.DashboardEntity;
import com.epam.aidial.analytics.service.dashboard.DashboardJson;
import com.epam.aidial.analytics.service.dashboard.DashboardSummary;
import com.epam.aidial.analytics.web.dto.dashboard.DashboardDto;
import com.epam.aidial.analytics.web.dto.dashboard.DashboardListDto;
import com.epam.aidial.analytics.web.dto.dashboard.DashboardListItemDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Maps stored dashboards onto their read DTOs, filling each caller's {@code permissions} from
 * {@link DashboardAccess}. Hand-written for the reason {@code SavedQueryMapper} is: the JSON payloads are
 * held as text, so the read direction is a parse, and the write direction stays in the service.
 */
@Component
@RequiredArgsConstructor
public class DashboardMapper {

    private final DashboardJson json;
    private final DashboardAccess access;

    public DashboardDto toDto(DashboardEntity entity) {
        return new DashboardDto(
                entity.getId(),
                entity.getName(),
                entity.getDescription(),
                entity.getVisibility(),
                entity.getOwnerId(),
                entity.getOwnerEmail(),
                json.readTime(entity.getTimeIntent()),
                entity.getCompare(),
                json.readSettings(entity.getSettings()),
                json.readWidgets(entity.getWidgets()),
                entity.getGeneration(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                access.permissions(entity.getVisibility(), entity.getOwnerId()));
    }

    public DashboardListDto toListDto(List<DashboardSummary> summaries) {
        return new DashboardListDto(
                summaries.stream().map(this::toListItem).toList(),
                access.createPermissions());
    }

    private DashboardListItemDto toListItem(DashboardSummary summary) {
        return new DashboardListItemDto(
                summary.id(),
                summary.name(),
                summary.description(),
                summary.visibility(),
                summary.updatedAt(),
                access.permissions(summary.visibility(), summary.ownerId()));
    }
}
