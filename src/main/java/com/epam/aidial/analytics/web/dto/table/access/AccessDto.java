package com.epam.aidial.analytics.web.dto.table.access;

import com.epam.aidial.analytics.service.table.TableAccess;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * A table's grant lists: the raw provider role names granted each table-scoped privilege. Returned as
 * stored — implication is derived when a check runs, never written into these lists, so a role granted
 * {@code read} appears under {@code read} alone even though it can also see the table and read its
 * schema. The effective answer is reported separately as {@code permissions}.
 *
 * <p>An enrichment's {@code get}, {@code read} and {@code sensitive} are empty unless an earlier build stored them,
 * and then confer nothing: its read side is its source's.
 */
public record AccessDto(
        @Schema(description = "Roles that may see this table and read its definition and columns"
                + AccessDto.ENRICHMENT_NOTE) List<String> get,
        @Schema(description = "Roles that may read this table's non-sensitive values" + AccessDto.ENRICHMENT_NOTE) List<String> read,
        @Schema(description = "Roles cleared for this table's sensitive columns, for the get/read/modify they also hold"
                + AccessDto.ENRICHMENT_NOTE)
        List<String> sensitive,
        @Schema(description = "Roles that may insert rows into this table") List<String> write,
        @Schema(description = "Roles that may change this table's schema or description") List<String> modify) {

    /** Appended to the read-side lists' descriptions: an enrichment takes none, and one left there confers nothing. */
    public static final String ENRICHMENT_NOTE = "; on an enrichment it confers nothing, its read side being its source's";

    /**
     * The lists as stored. A null — a historic definition row read back at a revision before it carried
     * grants — is five empty lists, which is what an absent key has always meant: nothing granted.
     */
    public static AccessDto of(TableAccess access) {
        if (access == null) {
            return new AccessDto(List.of(), List.of(), List.of(), List.of(), List.of());
        }
        return new AccessDto(
                access.getGet(), access.getRead(),
                access.getSensitive(), access.getWrite(), access.getModify());
    }
}
