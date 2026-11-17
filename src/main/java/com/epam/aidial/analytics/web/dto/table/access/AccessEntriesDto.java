package com.epam.aidial.analytics.web.dto.table.access;

import com.epam.aidial.analytics.service.table.TableAccessService.AccessEntries;
import com.epam.aidial.analytics.web.dto.table.access.AccessEntryReplacedDto.IneffectiveGrantDto;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every role's entry on a table, keyed by raw provider role and sorted by it, with the table's access generation and
 * every entry's report, grouped by role in the same order.
 */
public record AccessEntriesDto(
        @Schema(description = "The table's access generation; send it back on PUT .../access?role={role}") long generation,
        @Schema(description = "Every role's entry, keyed by raw provider role; an empty object when the table has none")
        Map<String, AccessEntryBodyDto> roles,
        @Schema(description = IneffectiveGrantDto.DESCRIPTION + " Every entry's, grouped by role in the order of `roles`.")
        @JsonProperty("ineffective_grants") List<IneffectiveGrantDto> ineffectiveGrants) {

    public static AccessEntriesDto of(AccessEntries entries) {
        final Map<String, AccessEntryBodyDto> roles = new LinkedHashMap<>();
        entries.roles().forEach((role, entry) -> roles.put(role, AccessEntryBodyDto.of(entry)));
        return new AccessEntriesDto(entries.generation(), roles, IneffectiveGrantDto.of(entries.ineffectiveGrants()));
    }
}
