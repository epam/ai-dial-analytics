package com.epam.aidial.analytics.web.dto.catalog;

import com.epam.aidial.analytics.service.table.TableAccessService.AccessEntry;
import com.epam.aidial.analytics.service.table.TableAccessService.EntryRequest;
import com.epam.aidial.analytics.service.table.TableAccessService.RowsRequest;
import com.epam.aidial.analytics.service.table.grant.PrivilegeScope;
import com.epam.aidial.analytics.web.dto.StrictStringListDeserializer;
import com.epam.aidial.analytics.web.dto.table.access.RowsDto;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.util.List;

/**
 * One role's access entry in a bundle, in the shape {@code GET /v1/tables/{name}/access} returns it and a
 * {@code PUT} takes it without {@code generation}: {@code rows} and {@code columns} are always written, as
 * {@code null} when the role has none. Read, {@code rows} takes {@code filter} or {@code where}.
 */
public record BundleAccessEntry(
        @Schema(description = "The privileges: a subset of get, read, sensitive, write and modify")
        @JsonInclude(JsonInclude.Include.ALWAYS)
        @JsonDeserialize(using = StrictStringListDeserializer.class) List<String> privileges,

        @Schema(description = "The role's row policy as `filter` (or, read, `where`), or null for none")
        @JsonInclude(JsonInclude.Include.ALWAYS) RowsDto rows,

        @Schema(description = "The role's column allowlist, or null for every column")
        @JsonInclude(JsonInclude.Include.ALWAYS)
        @JsonDeserialize(using = StrictStringListDeserializer.class) List<String> columns) {

    /** {@code entry} as a bundle member. */
    public static BundleAccessEntry of(AccessEntry entry) {
        return new BundleAccessEntry(entry.privileges().stream().map(PrivilegeScope::grantKey).toList(),
                RowsDto.of(entry.rows()), entry.columns());
    }

    /** This entry as a {@code PUT} body without a generation. */
    public EntryRequest toRequest() {
        return new EntryRequest(null, privileges, rows == null ? null : new RowsRequest(rows.filter(), rows.where()), columns);
    }
}
