package com.epam.aidial.analytics.web.dto.table.access;

import com.epam.aidial.analytics.service.table.TableAccessService.AccessEntry;
import com.epam.aidial.analytics.service.table.grant.PrivilegeScope;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** One role's entry as stored, a member of {@code roles} in {@link AccessEntriesDto}. */
public record AccessEntryBodyDto(
        @Schema(description = AccessEntryBodyDto.PRIVILEGES) List<String> privileges,
        @Schema(description = AccessEntryBodyDto.ROWS) @JsonInclude(JsonInclude.Include.ALWAYS) RowsDto rows,
        @Schema(description = AccessEntryBodyDto.COLUMNS) @JsonInclude(JsonInclude.Include.ALWAYS) List<String> columns) {

    static final String PRIVILEGES = "The privileges the role holds on this table, in the order get, read, sensitive, "
            + "write, modify. Implication is derived when a check runs and never written here.";

    static final String ROWS = "The role's row policy as a `filter`, or null for none.";

    static final String COLUMNS = "The role's column allowlist, sorted: exposed column names of this table and "
            + "`<enrichment>.<column>` for its enrichments' columns; null places no restriction.";

    public static AccessEntryBodyDto of(AccessEntry entry) {
        return new AccessEntryBodyDto(keys(entry), RowsDto.of(entry.rows()), entry.columns());
    }

    static List<String> keys(AccessEntry entry) {
        return entry.privileges().stream().map(PrivilegeScope::grantKey).toList();
    }
}
