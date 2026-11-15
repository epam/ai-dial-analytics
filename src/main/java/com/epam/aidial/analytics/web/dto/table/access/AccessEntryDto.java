package com.epam.aidial.analytics.web.dto.table.access;

import com.epam.aidial.analytics.service.table.TableAccessService.RoleEntry;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** One role's entry on a table, with the table's access generation. */
public record AccessEntryDto(
        @Schema(description = "The table's access generation; send it back on PUT") long generation,
        @Schema(description = "The raw provider role, decoded and trimmed") String role,
        @Schema(description = AccessEntryBodyDto.PRIVILEGES) List<String> privileges,
        @Schema(description = AccessEntryBodyDto.ROWS) @JsonInclude(JsonInclude.Include.ALWAYS) RowsDto rows,
        @Schema(description = AccessEntryBodyDto.COLUMNS) @JsonInclude(JsonInclude.Include.ALWAYS) List<String> columns) {

    public static AccessEntryDto of(RoleEntry entry) {
        return new AccessEntryDto(entry.generation(), entry.role(), AccessEntryBodyDto.keys(entry.entry()),
                RowsDto.of(entry.entry().rows()), entry.entry().columns());
    }
}
