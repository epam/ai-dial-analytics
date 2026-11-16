package com.epam.aidial.analytics.web.dto.table.access;

import com.epam.aidial.analytics.service.table.TableAccessService.EntryReplacement;
import com.epam.aidial.analytics.service.table.TableAccessService.IneffectiveGrant;
import com.epam.aidial.analytics.service.table.grant.PrivilegeScope;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * The answer to an entry {@code PUT}: the entry as stored, the table's new access generation, and every privilege in it
 * that changes nothing. It carries no {@code permissions}: the caller's own are reported by {@code GET /v1/tables/{name}}.
 */
public record AccessEntryReplacedDto(
        @Schema(description = "The table's new access generation") long generation,
        @Schema(description = "The raw provider role, decoded and trimmed") String role,
        @Schema(description = AccessEntryBodyDto.PRIVILEGES) List<String> privileges,
        @Schema(description = AccessEntryBodyDto.ROWS) @JsonInclude(JsonInclude.Include.ALWAYS) RowsDto rows,
        @Schema(description = AccessEntryBodyDto.COLUMNS) @JsonInclude(JsonInclude.Include.ALWAYS) List<String> columns,
        @Schema(description = IneffectiveGrantDto.DESCRIPTION)
        @JsonProperty("ineffective_grants") List<IneffectiveGrantDto> ineffectiveGrants) {

    public static AccessEntryReplacedDto of(EntryReplacement replacement) {
        return new AccessEntryReplacedDto(replacement.generation(), replacement.role(),
                AccessEntryBodyDto.keys(replacement.entry()), RowsDto.of(replacement.entry().rows()),
                replacement.entry().columns(), IneffectiveGrantDto.of(replacement.ineffectiveGrants()));
    }

    /**
     * One privilege that changes nothing, or a standing warning.
     *
     * @param role    the role whose entry carries it
     * @param key     the privilege ({@code get}, {@code read}, …)
     * @param reason  a stable machine code: {@code already_global}, {@code sensitive_without_verb},
     *                {@code lifts_row_policies}, {@code lifts_column_policies} or {@code everyone_reads_all_rows}
     * @param message the human-readable explanation
     */
    public record IneffectiveGrantDto(String role, String key, String reason, String message) {

        static final String DESCRIPTION = "Privileges that change nothing, and standing warnings, judged on the entries "
                + "as stored: already_global, sensitive_without_verb, lifts_row_policies, lifts_column_policies and "
                + "everyone_reads_all_rows (a read for '*' with no row policy on a table that is not an enrichment). Empty, "
                + "never absent, when nothing is reported.";

        static List<IneffectiveGrantDto> of(List<IneffectiveGrant> grants) {
            return grants.stream()
                    .map(grant -> new IneffectiveGrantDto(grant.role(), PrivilegeScope.grantKey(grant.privilege()),
                            grant.reason().code(), grant.message()))
                    .toList();
        }
    }
}
