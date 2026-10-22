package com.epam.aidial.analytics.web.dto.backfill;

import com.epam.aidial.analytics.service.BackfillRun;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * Live status of a backfill run. {@code currentDate} and {@code finishedAt} are present only while
 * running / once finished respectively (null fields are omitted from the JSON), and {@code failedDates} only
 * when at least one day failed (an empty list is omitted too, under any mapper).
 */
@Schema(description = "Backfill run status.")
public record BackfillStatusResponse(
        @Schema(description = "Run identifier.")
        String runId,
        @Schema(description = "Inclusive start date (UTC).", example = "2026-05-26")
        String from,
        @Schema(description = "Inclusive end date (UTC).", example = "2026-06-24")
        String to,
        @Schema(description = "Total days in the inclusive range.", example = "30")
        int daysTotal,
        @Schema(description = "Lifecycle state.", example = "running")
        String state,
        @Schema(description = "Date currently being processed (present while running).", example = "2026-06-10")
        String currentDate,
        @Schema(description = "Days successfully ingested so far.", example = "12")
        int daysCompleted,
        @Schema(description = "Days that failed to ingest.", example = "0")
        int daysFailed,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        @ArraySchema(schema = @Schema(type = "string", format = "date"),
                arraySchema = @Schema(description = "Dates (UTC, ascending) of the days that failed; absent when none did."))
        List<String> failedDates,
        @Schema(description = "Files processed across all days so far.")
        long filesProcessed,
        @Schema(description = "Rows inserted across all days so far.")
        long rowsInserted,
        @Schema(description = "Rows skipped (unparseable lines) across all days so far.")
        long rowsSkipped,
        @Schema(description = "Rows dropped (missing deduplication key) across all days so far.")
        long rowsDropped,
        @Schema(description = "Run start time (UTC, ISO-8601).")
        String startedAt,
        @Schema(description = "Run completion time (UTC, ISO-8601); present once finished.")
        String finishedAt) {

    public static BackfillStatusResponse of(BackfillRun.Snapshot s) {
        return new BackfillStatusResponse(
                s.runId(),
                s.from().toString(),
                s.to().toString(),
                s.daysTotal(),
                s.state().name().toLowerCase(Locale.ROOT),
                format(s.currentDate()),
                s.daysCompleted(),
                s.daysFailed(),
                s.failedDates().stream().map(LocalDate::toString).toList(),
                s.filesProcessed(),
                s.rowsInserted(),
                s.rowsSkipped(),
                s.rowsDropped(),
                format(s.startedAt()),
                format(s.finishedAt()));
    }

    private static String format(LocalDate date) {
        return date == null ? null : date.toString();
    }

    private static String format(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
