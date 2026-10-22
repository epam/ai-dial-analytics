package com.epam.aidial.analytics.web.mapper.audit;

import com.epam.aidial.analytics.service.audit.AuditActivityEntity;
import com.epam.aidial.analytics.web.dto.audit.AuditActivityDto;
import com.epam.aidial.analytics.web.dto.page.PageDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.NullValueMappingStrategy;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Maps stored activities onto their read DTO. Plain field copying, so it is generated rather than
 * hand-written.
 *
 * <p>The one non-identity mapping is {@code revision}: the entity owns that column through its
 * association to the revision and exposes it a second time as a read-only scalar, which is the one the
 * wire carries.
 */
@Mapper(componentModel = "spring", nullValueIterableMappingStrategy = NullValueMappingStrategy.RETURN_DEFAULT)
public interface AuditActivityMapper {

    @Mapping(target = "revision", source = "revisionNumber")
    AuditActivityDto toDto(AuditActivityEntity entity);

    List<AuditActivityDto> toDtos(List<AuditActivityEntity> entities);

    /** Wraps a page of activities in the {@code {total, totalPages, data}} envelope the contract returns. */
    default PageDto<AuditActivityDto> toPageDto(Page<AuditActivityEntity> page) {
        return new PageDto<>(page.getTotalElements(), page.getTotalPages(), toDtos(page.getContent()));
    }
}
