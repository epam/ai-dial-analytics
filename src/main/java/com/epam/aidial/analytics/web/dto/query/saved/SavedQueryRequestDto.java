package com.epam.aidial.analytics.web.dto.query.saved;

import com.epam.aidial.analytics.service.query.model.StructuredQuery;
import com.epam.aidial.analytics.service.query.saved.SavedQueryResultView;
import com.epam.aidial.analytics.service.query.saved.SavedQueryScope;
import com.epam.aidial.analytics.service.query.saved.model.SavedQueryChart;
import com.epam.aidial.analytics.service.query.saved.model.SavedQueryTime;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import tools.jackson.databind.JsonNode;


/**
 * Create/replace body for a saved query.
 *
 * <p>The trailing components are <strong>not inputs</strong>. They are declared so that supplying one is
 * <em>rejected</em> rather than silently ignored: {@code id}, {@code owner_id}, {@code owner_email},
 * {@code source}, {@code generation}, {@code created_at}, and {@code updated_at} are server-assigned
 * ({@code owner_email} in particular is taken from the caller's own token, never from the body, so it
 * cannot be spoofed into a misleading "Saved by"; and {@code source} is
 * derived from the body, so accepting it would create a second place for the same fact to live and let
 * the two disagree), and {@code params} is not part of this capability at all — a parameterized body
 * cannot be executed by the query translator, so it is refused rather than stored as a query that could
 * never run. A client that sends any of them learns immediately, from a {@code 422}, that it is not an
 * input. {@code SavedQueryBodyValidator} enforces this.
 *
 * <p>Deliberately free of bean-validation annotations of its <em>own</em>: every envelope rule is checked
 * in the validator so that it surfaces as {@code 422 validation_error} with a usable message, rather than
 * as the {@code 400 bad_request} that a {@code MethodArgumentNotValidException} would produce. The one
 * exception is the {@code @Valid} that cascades into {@code query}: those constraints are not this
 * capability's envelope rules but the structured query's own contract, the very one
 * {@code POST /v1/queries/execute} and {@code POST /v1/queries/translate} enforce at their boundaries.
 * Without the cascade a body they would reject — {@code mode} omitted, say — would be stored and could
 * then never be run, breaking the executable-as-stored guarantee; with it, the save reports exactly the
 * {@code 400 bad_request} those endpoints report for the same body, which is the pass-through rule the
 * rest of body validation already follows. {@code time} is
 * declared as a raw {@link JsonNode} for the same reason: bound as {@code SavedQueryTime}, a malformed
 * intent — {@code {"mode":"weekly"}}, an unparseable instant, or a {@code time} that is not an object at
 * all — would fail inside Jackson and surface as the {@code 400 bad_request} that a
 * {@code HttpMessageNotReadableException} produces, while the time-intent rule is specified as
 * {@code 422}. {@code SavedQueryBodyValidator#timeIntent} binds it instead, and owns the rejection.
 *
 * <p>The typed server-assigned fields — {@code source}, {@code generation}, {@code created_at},
 * {@code updated_at} — are declared as {@link JsonNode} for that same reason. Bound as
 * {@code List<String>}/{@code Long}/{@code Instant}, a body carrying {@code "source": ["x"]} or
 * {@code "generation": "soon"} would fail inside Jackson and answer {@code 400} before the validator
 * could say {@code 422}; the field would still be refused, but under the wrong code. As nodes, their
 * <em>presence</em> is what matters and the validator owns every rejection, whatever the value's shape.
 * {@code params} is a node for the same reason.
 */
public record SavedQueryRequestDto(
        @Schema(description = "Display name; required and non-blank", example = "Top 20 chats by turn count")
        String name,

        @Schema(description = "Optional free-text description", example = "Used in the Monday review.")
        String description,

        @Schema(description = "Optional single label", example = "Adoption")
        String tag,

        @Schema(description = "Sharing scope; 'common' requires SAVED_QUERY_PUBLISH to write",
                example = "personal")
        SavedQueryScope scope,

        @Schema(description = "Structured query body. Exactly one of 'query' or 'sql' must be set.")
        @Valid StructuredQuery query,

        @Schema(description = "SQL body. Exactly one of 'query' or 'sql' must be set.",
                example = "SELECT count(*) FROM dial_usage_log")
        String sql,

        @Schema(implementation = SavedQueryTime.class,
                description = "Time intent: null, {mode:relative, period}, or {mode:absolute, from, to}. "
                        + "A relative period is never resolved to instants.")
        JsonNode time,

        @Schema(description = "How the client last rendered the result", example = "chart")
        @JsonProperty("result_view") SavedQueryResultView resultView,

        @Schema(description = "Chart configuration; stored verbatim and never interpreted")
        SavedQueryChart chart,

        @Schema(hidden = true) String id,
        @Schema(hidden = true) @JsonProperty("owner_id") String ownerId,
        @Schema(hidden = true) @JsonProperty("owner_email") String ownerEmail,
        @Schema(hidden = true) JsonNode source,
        @Schema(hidden = true) JsonNode generation,
        @Schema(hidden = true) @JsonProperty("created_at") JsonNode createdAt,
        @Schema(hidden = true) @JsonProperty("updated_at") JsonNode updatedAt,
        @Schema(hidden = true) JsonNode params) {}
