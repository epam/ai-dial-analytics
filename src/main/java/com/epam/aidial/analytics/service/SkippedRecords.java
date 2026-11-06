package com.epam.aidial.analytics.service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What one ingest of one blob skipped: the lines it could not parse or that were too long, and the records it dropped for
 * lacking a deduplication key. Kept per cause as a count plus the first {@value #LINES_KEPT} line numbers, so a blob of
 * millions of bad lines still costs a few dozen bytes. A blob with any skip is recorded in the failure ledger as a
 * {@code records} subject whose message is {@link #describe()} (see {@code openspec/specs/data-sync-failures/spec.md}).
 *
 * <p>Not thread-safe: one instance belongs to one blob's read.
 */
public final class SkippedRecords {

    /** Line numbers kept per cause. */
    static final int LINES_KEPT = 10;

    private final Map<Cause, Tally> tallies = new EnumMap<>(Cause.class);

    /** Notes one skipped line or dropped record at the 1-based {@code line}. */
    public void add(Cause cause, long line) {
        tallies.computeIfAbsent(cause, ignored -> new Tally()).add(line);
    }

    /** Whether nothing was skipped. */
    public boolean isEmpty() {
        return tallies.isEmpty();
    }

    /** How many lines or records {@code cause} skipped. */
    public long count(Cause cause) {
        Tally tally = tallies.get(cause);
        return tally == null ? 0 : tally.count;
    }

    /** The first line numbers {@code cause} skipped, at most {@value #LINES_KEPT}, in reading order. */
    public List<Long> lines(Cause cause) {
        Tally tally = tallies.get(cause);
        return tally == null ? List.of() : List.copyOf(tally.lines);
    }

    /**
     * The failure-ledger message: each cause that occurred, with its count and first line numbers, for example
     * {@code malformed_line: 2 (lines 14, 87); missing_dedup_key: 1 (line 203)}. Line numbers are left out from the end
     * once the message would pass {@link IngestFailureEvent#MAX_MESSAGE_LENGTH}; counts are always kept. Never carries
     * record content.
     */
    public String describe() {
        Map<Cause, String> heads = new EnumMap<>(Cause.class);
        tallies.forEach((cause, tally) -> heads.put(cause, cause.wire() + ": " + tally.count));
        // Room the heads still to come need, with their "; " separators, so line numbers never crowd out a later count.
        int reserved = heads.values().stream().mapToInt(head -> head.length() + 2).sum();
        StringBuilder out = new StringBuilder();
        for (Map.Entry<Cause, Tally> entry : tallies.entrySet()) {
            String head = heads.get(entry.getKey());
            reserved -= head.length() + 2;
            if (!out.isEmpty()) {
                out.append("; ");
            }
            out.append(head);
            Tally tally = entry.getValue();
            String label = tally.count == 1 ? " (line " : " (lines ";
            StringBuilder lines = new StringBuilder();
            for (Long line : tally.lines) {
                String next = (lines.isEmpty() ? "" : ", ") + line;
                if (out.length() + label.length() + lines.length() + next.length() + 1 + reserved > IngestFailureEvent.MAX_MESSAGE_LENGTH) {
                    break;
                }
                lines.append(next);
            }
            if (!lines.isEmpty()) {
                out.append(label).append(lines).append(')');
            }
        }
        return out.toString();
    }

    /** Why a line or record was not written. */
    public enum Cause {
        /** A line that does not parse as JSON. */
        MALFORMED_LINE,
        /** A line longer than {@code sync.max-line-bytes}. */
        OVERSIZED_LINE,
        /** A record whose row for some target lacks a deduplication-key column; counted once however many targets drop it. */
        MISSING_DEDUP_KEY;

        /** The lower-case spelling used in the message. */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static final class Tally {
        private long count;
        private final List<Long> lines = new ArrayList<>(2);

        void add(long line) {
            count++;
            if (lines.size() < LINES_KEPT) {
                lines.add(line);
            }
        }
    }
}
