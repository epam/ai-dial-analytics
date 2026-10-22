package com.epam.aidial.analytics.web.dto.table.access;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * A <b>merge</b> request for a table's grant lists: a key present in the body replaces that list, an
 * <b>absent</b> key leaves the stored list unchanged, and an explicit empty array revokes it.
 *
 * <p>Absent-keeps rather than full-replace because the document grew from two keys to five. A full replace
 * would turn every client that was written against {@code {write, modify}} — the MCP {@code adas_*} tools
 * among them — from "cannot set read grants" into "silently revokes all read grants on every call", with
 * nothing in the request showing it.
 *
 * <p>There is no {@code list} key: {@code get} is what shows one table, and seeing every table is the
 * global {@code TABLE_LIST}. A body carrying {@code list} is refused {@code 400} like any unknown member.
 *
 * <p>Role names must be non-blank (validated in the service, 422 otherwise) and are trimmed and
 * de-duplicated.
 */
public record ReplaceAccessRequest(
        @Schema(description = "Roles that may see this table and read its definition and columns; absent keeps, "
                + "[] revokes")
        List<String> get,
        @Schema(description = "Roles that may read this table's non-sensitive values; absent keeps, [] revokes")
        List<String> read,
        @Schema(description = "Roles cleared for this table's sensitive columns, for the get/read/modify they also hold; "
                + "absent keeps, [] revokes")
        List<String> sensitive,
        @Schema(description = "Roles that may insert rows; absent keeps, [] revokes")
        List<String> write,
        @Schema(description = "Roles that may change the schema or description; absent keeps, [] revokes")
        List<String> modify) {
}
