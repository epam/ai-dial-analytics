package com.epam.aidial.analytics.web.dto.datasync;

import com.epam.aidial.analytics.service.IngestFailureEntry;
import com.epam.aidial.analytics.service.IngestFailureKind;
import com.epam.aidial.analytics.service.OpenIngestFailure;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** One outstanding failure-ledger subject. */
@Schema(description = "An outstanding failed blob or day.")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IngestFailureView(
        @JsonProperty("kind") @Schema(description = "`blob`, `day` or `records`.", example = "blob") String kind,
        @JsonProperty("subject") @Schema(description = "The blob's full key, or the day's ISO date.") String subject,
        @JsonProperty("day") @Schema(description = "UTC date: the day itself, or the blob's last-modified date.", example = "2026-05-26") String day,
        @JsonProperty("blob_last_modified") @Schema(description = "The blob's listed last-modified time at its latest failure; absent for a day.")
        String blobLastModified,
        @JsonProperty("state") @Schema(description = "`pending` (retried automatically) or `parked` (waits for a requeue).", example = "parked")
        String state,
        @JsonProperty("failure_class") @Schema(description = "`transient` or `deterministic`, of the latest failure.") String failureClass,
        @JsonProperty("reason") @Schema(description = "Reason code of the latest failure.", example = "corrupt_gzip") String reason,
        @JsonProperty("message") @Schema(description = "Short description of the latest failure; never record content.") String message,
        @JsonProperty("path") @Schema(description = "Ingest path of the latest failure: `poll`, `reconcile`, `backfill` or `retry`.") String path,
        @JsonProperty("attempts") @Schema(description = "Failures since the subject was last requeued.") int attempts,
        @JsonProperty("first_failed_at") @Schema(description = "Earliest failure (UTC, ISO-8601).") String firstFailedAt,
        @JsonProperty("last_failed_at") @Schema(description = "Latest failure (UTC, ISO-8601).") String lastFailedAt,
        @JsonProperty("next_attempt_at") @Schema(description = "When the retry sweep is due to attempt it; pending subjects only.")
        String nextAttemptAt) {

    public static IngestFailureView of(IngestFailureEntry entry) {
        OpenIngestFailure failure = entry.failure();
        boolean blob = failure.kind() != IngestFailureKind.DAY;
        return new IngestFailureView(
                failure.kind().wire(),
                failure.subject(),
                failure.day().toString(),
                blob && !Instant.EPOCH.equals(failure.blobLastModified()) ? failure.blobLastModified().toString() : null,
                entry.state().wire(),
                failure.failureClass(),
                failure.reason(),
                failure.message(),
                failure.path(),
                failure.attempts(),
                failure.firstFailedAt().toString(),
                failure.lastFailedAt().toString(),
                entry.nextAttemptAt() == null ? null : entry.nextAttemptAt().toString());
    }
}
