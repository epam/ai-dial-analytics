package com.epam.aidial.analytics.web.dto.query.saved;

import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.saved.SavedQueryResultView;
import com.epam.aidial.analytics.service.query.saved.SavedQueryScope;
import com.epam.aidial.analytics.service.query.saved.model.SavedQueryChart;
import com.epam.aidial.analytics.service.query.saved.model.SavedQueryTime;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * A saved query in full, as returned by the single read and by create/replace.
 *
 * <p>The body is returned <strong>exactly as persisted</strong>, with no redaction, for every caller
 * permitted to read the row. Column names are schema facts and stay visible; a client that cannot resolve
 * one renders it as a single "unavailable field" state, identical for a dropped column and a restricted
 * one.
 *
 * <p><strong>A literal is not filtered by sensitivity on read.</strong> Since a body may bind a literal
 * to a {@code sensitive} column, and a {@code common} row is readable by every caller holding an
 * application role, a value an entitled author typed into a {@code common} body is returned verbatim to
 * callers who could not have read that column themselves. That widening is deliberate and reviewed — see
 * the {@code saved-queries} spec — not an oversight of this read path: sharing at {@code common} scope is
 * publication, and only a {@code SAVED_QUERY_PUBLISH} holder can publish there.
 */
public record SavedQueryDto(
        @Schema(description = "Server-assigned opaque identifier", example = "sq_3f1ac9d24e7b4f0e8c1d5a6b7c8d9e0f")
        String id,

        @Schema(description = "Display name", example = "Top 20 chats by turn count")
        String name,

        @Schema(description = "Optional free-text description", example = "Used in the Monday review.")
        String description,

        @Schema(description = "Optional single label", example = "Adoption")
        String tag,

        @Schema(description = "Sharing scope", example = "personal")
        SavedQueryScope scope,

        @Schema(description = "Authoring identity's principal, and the only field ownership is decided by. "
                + "Null when security is disabled. For a DIAL project key this is the project name, so "
                + "'personal' isolates per credential identity.",
                example = "8f2c1d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f")
        @JsonProperty("owner_id") String ownerId,

        @Schema(description = "Author's email as recorded when the saved query was created — for display "
                + "(\"Saved by\") only, never for identifying the caller. Absent when security is "
                + "disabled, when the token carries no email claim, or for a project-key author, so a "
                + "client must fall back rather than assume it is present.",
                example = "jane.doe@example.com")
        @JsonProperty("owner_email") String ownerEmail,

        @Schema(description = "Every entity the body reads from, derived from the body. One element for "
                + "a structured (query-kind) body or a single-entity SQL one; more than one for a SQL body "
                + "that joins, or subqueries against, another entity.",
                example = "[\"dial_usage_log\"]")
        List<String> source,

        @Schema(description = "Structured query body; null when this saved query carries SQL")
        StructuredQuery query,

        @Schema(description = "SQL body; null when this saved query carries a structured query")
        String sql,

        @Schema(description = "Time intent as authored; a relative period is never resolved to instants")
        SavedQueryTime time,

        @Schema(description = "How the client last rendered the result", example = "chart")
        @JsonProperty("result_view") SavedQueryResultView resultView,

        @Schema(description = "Chart configuration")
        SavedQueryChart chart,

        @Schema(description = "Incremented on every mutation; a change detector for clients", example = "3")
        long generation,

        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt) {}
