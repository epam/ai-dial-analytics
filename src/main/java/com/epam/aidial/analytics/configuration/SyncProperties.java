package com.epam.aidial.analytics.configuration;

import com.epam.aidial.analytics.sync.inline.InlineFilesMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Typed binding for the {@code data-sync} job ({@code sync.*}). Values are env-driven via
 * {@code application.yml}. Validated at startup ({@link Validated}); an out-of-range value fails the
 * context fast rather than degrading the ingest at runtime.
 *
 * @param cron                  Spring cron expression for the daily run (e.g. {@code 0 0 1 * * *})
 * @param dayOffset             number of days back from today to target; {@code 1} = yesterday (logs complete after midnight)
 * @param batchSize             number of rows per ClickHouse INSERT batch
 * @param maxBatchBytes         max accumulated serialized bytes per batch; a batch flushes at this size or {@code batchSize} rows, whichever first
 * @param maxLineBytes          max decompressed bytes of one NDJSON line; a longer line is skipped and counted like a malformed
 *                              one. It bounds the raw line buffer, not the parse: a line just under the cap is also held as its
 *                              decoded string (up to twice the bytes), JSON tree and row at once, so each parsing thread (the
 *                              scheduled sync and a backfill may run together) needs a small multiple of it. At most 1 GiB
 * @param pathPrefix            base path within the container preceding the date directory
 * @param datePathPattern       date layout of the directory between {@code pathPrefix} and the blob file name (default {@code yyyy/MM/dd});
 *                              blank means the producer writes no date directory at all, in which case {@code fileNamePrefixPattern} is
 *                              what has to carry the date and the whole-day prefix ends inside the file name. Validated at startup by
 *                              {@code BlobPrefixResolver}, which is where an unusable layout — or a blank one under a head that does not
 *                              pin the day — fails the context
 * @param fileNamePrefixPattern date layout of the blob file name's leading text, preceding the embedded epoch timestamp that bounded
 *                              ({@code band}) listing filters on (default {@code 'date='yyyy-MM-dd}); blank means the timestamp begins the
 *                              file name. Also validated at startup by {@code BlobPrefixResolver}
 * @param validateSchema        whether to reconcile each target's mapping against its live table schema at startup (fail-fast)
 * @param targets               the ingest targets (table + mapping file); MUST declare at least one
 * @param inlineFilesMode       what ingest does with inline files in the body columns whose mapping declares {@code inline-files};
 *                              {@code null} (unset) means {@link InlineFilesMode#DROP_TRUNCATED}. An unknown value fails binding, and so startup
 */
@Validated
@ConfigurationProperties(prefix = "sync")
public record SyncProperties(
        String cron,
        int dayOffset,
        @Min(1) int batchSize,
        @Min(1024L * 1024L) @Max(Integer.MAX_VALUE / 6) long maxBatchBytes,
        @Min(1024) @Max(SyncProperties.MAX_LINE_BYTES_LIMIT) int maxLineBytes,
        String pathPrefix,
        String datePathPattern,
        String fileNamePrefixPattern,
        boolean validateSchema,
        @NotEmpty List<@Valid Target> targets,
        InlineFilesMode inlineFilesMode) {

    /** The {@code sync.max-line-bytes} default that {@code application.yml} ships (64 MiB). */
    public static final int DEFAULT_MAX_LINE_BYTES = 64 * 1024 * 1024;
    /** Upper bound on {@code sync.max-line-bytes} (1 GiB); the real footprint of a line is a multiple of the cap. */
    public static final int MAX_LINE_BYTES_LIMIT = 1024 * 1024 * 1024;

    public SyncProperties {
        targets = targets == null ? List.of() : List.copyOf(targets);
        // Whitespace only: the pattern's own slashes are load-bearing (`yyyy/MM/dd`), and a quoted literal may
        // legally contain one, so stripping them belongs on the rendered segment rather than on the pattern.
        datePathPattern = datePathPattern == null ? null : datePathPattern.trim();
        // Same reasoning for the file-name head, plus: it is legitimately blank (the epoch begins the file name), so
        // trimming must not turn a configured-but-blank value into anything other than blank.
        fileNamePrefixPattern = fileNamePrefixPattern == null ? null : fileNamePrefixPattern.trim();
        inlineFilesMode = inlineFilesMode == null ? InlineFilesMode.DROP_TRUNCATED : inlineFilesMode;
    }

    /**
     * One configured ingest target.
     *
     * @param table       the ClickHouse table this target inserts into (provisioned by Flyway migrations, or out of band when migrations are disabled)
     * @param mappingFile Spring resource location of this target's column mapping ({@code classpath:...} or {@code file:...})
     */
    public record Target(@NotBlank String table, @NotBlank String mappingFile) {
    }
}
