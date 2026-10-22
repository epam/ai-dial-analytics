package com.epam.aidial.analytics.sync.inline;

import com.epam.aidial.analytics.sync.inline.InlineFileLocations.Location;
import com.epam.aidial.analytics.sync.inline.InlineFileLocator.Scan;
import com.epam.aidial.analytics.sync.inline.InlineFileLocator.Span;

import java.util.ArrayList;
import java.util.List;

/**
 * Removes the payloads of the inline files in one body (the {@code drop} mode of {@code sync.inline-files-mode}), or
 * only the half file a 4 MiB cut left behind (the {@code drop-truncated} mode).
 *
 * <p>A body whose first non-whitespace character is <code>&#123;</code> or {@code [} is one JSON document; any other body is
 * read as SSE, one single-line {@code data:} event at a time (an event spread over several {@code data:} lines is
 * left alone, since its data is not contiguous in the text). Each file's raw text between its quotes is dropped —
 * a data URL keeps its {@code data:...;base64,} prefix — and every other character, JSON escapes included, is kept.
 *
 * <p>A body DIAL Core cut at 4 MiB ends with its {@code >>} marker and is at least {@link #TRUNCATED_MIN_BYTES} UTF-8
 * bytes long: nothing shorter can carry the marker, since Core cuts only bodies over the limit, and a character the
 * cut split became U+FFFD, which only lengthens it. Such a body is scanned without the marker; when the value open
 * at the cut is a file, the body is cut back to just before that value's opening quote, and the marker is put back
 * in every case.
 *
 * <p>Pure and deterministic: the output depends only on the body, the API, the side and the mode. It never throws.
 */
public final class InlineFileStripper {

    /** Core's {@code MAX_BODY_SIZE_BYTES} (4 MiB) plus the two bytes of the {@code >>} marker. */
    static final int TRUNCATED_MIN_BYTES = 4 * 1024 * 1024 + 2;

    private static final String TRUNCATION_MARKER = ">>";

    /** A UTF-8 character is at most 3 bytes per UTF-16 char, so a shorter string cannot reach the byte bound. */
    private static final int TRUNCATED_MIN_CHARS = TRUNCATED_MIN_BYTES / 3;

    /**
     * The stripped body and what was done to it.
     *
     * @param body         the body to store
     * @param filesRemoved complete files whose payload was removed (a file cut back over is not counted here)
     * @param bytesRemoved UTF-8 bytes of payload removed, a cut-back file's included
     * @param cutBack      whether a truncated body was cut back to before a file
     * @param unscannable  documents (the body, or SSE events) that did not parse; a truncated body failing at its cut is not one
     */
    public record Result(String body, int filesRemoved, long bytesRemoved, boolean cutBack, int unscannable) {

        static Result unchanged(String body, int unscannable) {
            return new Result(body, 0, 0, false, unscannable);
        }
    }

    private InlineFileStripper() {
    }

    /**
     * Removes the inline files of a body, as {@link InlineFilesMode#DROP} does.
     *
     * @param body the body as the producer sent it; may be {@code null}
     * @param api  the record's API
     * @param side which side of the call the body is
     * @return the body to store, with counts; the input itself when nothing was removed
     */
    public static Result strip(String body, Api api, BodySide side) {
        return strip(body, api, side, InlineFilesMode.DROP);
    }

    /**
     * Applies a mode to a body.
     *
     * <ul>
     *   <li>{@link InlineFilesMode#KEEP}: the body is returned as it is.</li>
     *   <li>{@link InlineFilesMode#DROP_TRUNCATED}: only a body DIAL Core cut is scanned, and it is only cut back to
     *       before the file the cut landed in; every complete file is kept. Any other body is returned without being
     *       parsed.</li>
     *   <li>{@link InlineFilesMode#DROP}: every complete file is removed as well.</li>
     * </ul>
     *
     * @param body the body as the producer sent it; may be {@code null}
     * @param api  the record's API
     * @param side which side of the call the body is
     * @param mode what to remove
     * @return the body to store, with counts; the input itself when nothing was removed
     */
    public static Result strip(String body, Api api, BodySide side, InlineFilesMode mode) {
        List<Location> locations = InlineFileLocations.of(api, side);
        if (mode == InlineFilesMode.KEEP || body == null || body.isEmpty() || locations.isEmpty()) {
            return Result.unchanged(body, 0);
        }
        boolean truncated = isTruncated(body);
        boolean keepCompleteFiles = mode == InlineFilesMode.DROP_TRUNCATED;
        if (keepCompleteFiles && !truncated) {
            return Result.unchanged(body, 0);
        }
        int textEnd = truncated ? body.length() - TRUNCATION_MARKER.length() : body.length();
        int first = firstNonWhitespace(body, textEnd);
        if (first < 0) {
            return Result.unchanged(body, 0);
        }

        List<Span> files = new ArrayList<>();
        Span open = null;
        int unscannable = 0;
        char head = body.charAt(first);
        if (head == '{' || head == '[') {
            Scan scan = InlineFileLocator.scan(body, 0, textEnd, locations);
            files.addAll(scan.files());
            if (truncated) {
                open = scan.open();
            } else if (scan.parseError()) {
                unscannable++;
            }
        } else {
            SseEvents events = scanSse(body, textEnd, truncated, locations);
            files.addAll(events.files());
            open = events.open();
            unscannable = events.unscannable();
        }
        if (keepCompleteFiles) {
            files.clear(); // located only so the scan reaches the cut; drop-truncated keeps them
        }
        return assemble(body, textEnd, truncated, files, open, unscannable);
    }

    private record SseEvents(List<Span> files, Span open, int unscannable) {
    }

    /**
     * Scans every single-line {@code data:} event holding a JSON object. Lines end at {@code \n}, a trailing
     * {@code \r} removed; a blank line ends an event. A body with no {@code data:} line at all is unscannable.
     */
    private static SseEvents scanSse(String body, int textEnd, boolean truncated, List<Location> locations) {
        List<Span> files = new ArrayList<>();
        Span open = null;
        int unscannable = 0;
        boolean anyData = false;
        List<int[]> eventData = new ArrayList<>(1);   // [from, to) of each data: payload of the current event
        int lineStart = 0;
        while (lineStart <= textEnd) {
            int newline = body.indexOf('\n', lineStart);
            int lineEnd = newline < 0 || newline > textEnd ? textEnd : newline;
            int contentEnd = lineEnd > lineStart && body.charAt(lineEnd - 1) == '\r' ? lineEnd - 1 : lineEnd;
            boolean last = lineEnd == textEnd;
            if (contentEnd == lineStart) {
                unscannable += scanEvent(body, eventData, locations, files, null);
                eventData.clear();
            } else if (body.startsWith("data:", lineStart)) {
                anyData = true;
                int data = lineStart + 5;
                if (data < contentEnd && body.charAt(data) == ' ') {
                    data++;
                }
                eventData.add(new int[] {data, last && truncated ? lineEnd : contentEnd});
            }
            if (last) {
                Span[] cut = new Span[1];
                unscannable += scanEvent(body, eventData, locations, files, truncated ? cut : null);
                open = cut[0];
                break;
            }
            lineStart = lineEnd + 1;
        }
        return new SseEvents(files, open, anyData ? unscannable : unscannable + 1);
    }

    /**
     * Scans one event. Returns {@code 1} when its data did not parse, except for the event a truncated body was cut
     * in ({@code cut != null}), which reports its open file through {@code cut} instead.
     */
    private static int scanEvent(String body, List<int[]> eventData, List<Location> locations, List<Span> files, Span[] cut) {
        if (eventData.size() != 1) {
            return 0; // no data, or several data: lines whose data is not contiguous in the body
        }
        int[] range = eventData.getFirst();
        if (range[0] >= range[1] || body.charAt(range[0]) != '{') {
            return 0; // [DONE], or other non-JSON data
        }
        Scan scan = InlineFileLocator.scan(body, range[0], range[1], locations);
        files.addAll(scan.files());
        if (cut != null) {
            cut[0] = scan.open();
            return 0;
        }
        return scan.parseError() ? 1 : 0;
    }

    private static Result assemble(String body, int textEnd, boolean truncated, List<Span> files, Span open, int unscannable) {
        int cutAt = truncated && open != null ? open.quote() : textEnd;
        if (files.isEmpty() && cutAt == textEnd) {
            return Result.unchanged(body, unscannable);
        }
        StringBuilder out = new StringBuilder(cutAt);
        long bytes = 0;
        int removed = 0;
        int copied = 0;
        for (Span span : files) {
            if (span.end() > cutAt) {
                break;
            }
            out.append(body, copied, span.payloadStart());
            bytes += utf8Length(body, span.payloadStart(), span.end());
            copied = span.end();
            removed++;
        }
        out.append(body, copied, cutAt);
        if (cutAt < textEnd) {
            bytes += utf8Length(body, open.payloadStart(), textEnd);
        }
        if (truncated) {
            out.append(TRUNCATION_MARKER);
        }
        return new Result(out.toString(), removed, bytes, cutAt < textEnd, unscannable);
    }

    /** Whether DIAL Core cut this body at 4 MiB: it ends with {@code >>} and is at least {@link #TRUNCATED_MIN_BYTES} bytes. */
    static boolean isTruncated(String body) {
        return body.length() >= TRUNCATED_MIN_CHARS
                && body.endsWith(TRUNCATION_MARKER)
                && utf8Length(body, 0, body.length()) >= TRUNCATED_MIN_BYTES;
    }

    private static int firstNonWhitespace(String body, int end) {
        for (int i = 0; i < end; i++) {
            if (!Character.isWhitespace(body.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /** UTF-8 length of {@code s[from, to)}; an unpaired surrogate counts as the 3 bytes of its replacement. */
    static long utf8Length(String s, int from, int to) {
        long bytes = 0;
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                bytes++;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < to && Character.isLowSurrogate(s.charAt(i + 1))) {
                bytes += 4;
                i++;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }
}
