package com.epam.aidial.analytics.web.dto.table.access;

import com.epam.aidial.analytics.service.table.TableAccessService.AccessEntries;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.LinkedHashMap;
import java.util.Map;

/** Every role's entry on a table, keyed by raw provider role and sorted by it, with the table's access generation. */
public record AccessEntriesDto(
        @Schema(description = "The table's access generation; send it back on PUT .../access?role={role}") long generation,
        @Schema(description = "Every role's entry, keyed by raw provider role; an empty object when the table has none")
        Map<String, AccessEntryBodyDto> roles) {

    public static AccessEntriesDto of(AccessEntries entries) {
        final Map<String, AccessEntryBodyDto> roles = new LinkedHashMap<>();
        entries.roles().forEach((role, entry) -> roles.put(role, AccessEntryBodyDto.of(entry)));
        return new AccessEntriesDto(entries.generation(), roles);
    }
}
