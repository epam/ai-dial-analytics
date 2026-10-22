package com.epam.aidial.analytics.web.dto.audit;

import com.epam.aidial.analytics.service.audit.ActivityResourceType;
import com.epam.aidial.analytics.service.audit.ActivityType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * One entry of the activity feed: a named resource changed, in a named way, at a named revision, by a
 * named identity.
 *
 * @param activityId       this entry's identifier, also the value children reference as their parent
 * @param activityType     what happened
 * @param resourceType     the kind of resource it happened to
 * @param resourceId       the resource's identity within its type; opaque, and not to be parsed
 * @param epochTimestampMs when the revision was taken, in epoch milliseconds
 * @param initiatedAuthor  the principal that made the change, or {@code unknown}
 * @param initiatedEmail   that principal's email when the token carried one
 * @param revision         the revision grouping every change made in the same transaction
 * @param parentActivityId the {@code Table} activity this one belongs under, or null
 * @param importId         the catalog import whose revision this is, or null when no import wrote it
 */
@Schema(description = "One recorded change to an audited catalog resource.")
public record AuditActivityDto(
        @Schema(description = "Identifier of this activity.") UUID activityId,
        @Schema(description = "What happened.", example = "Update") ActivityType activityType,
        @Schema(description = "Kind of resource.", example = "TableColumn") ActivityResourceType resourceType,
        @Schema(description = "Opaque identity of the resource within its type.",
                example = "conversations:chat_id") String resourceId,
        @Schema(description = "When the change was made, epoch milliseconds.") long epochTimestampMs,
        @Schema(description = "Principal that made the change, or 'unknown'.") String initiatedAuthor,
        @Schema(description = "That principal's email, when known.") String initiatedEmail,
        @Schema(description = "Revision grouping all changes of one transaction.") int revision,
        @Schema(description = "The Table activity this one belongs under, when it has one.")
        UUID parentActivityId,
        @Schema(description = "The import_id of the catalog import this change was part of; absent when no import made it.")
        UUID importId) {}
