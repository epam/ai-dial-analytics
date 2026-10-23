package com.epam.aidial.analytics.sync.inline;

/**
 * What ingest does with the inline files of a body column whose mapping declares {@code inline-files}: the two
 * settings {@code sync.inline-files-drop-truncated} and {@code sync.inline-files-max-size}, which apply independently.
 *
 * @param dropTruncated whether a body DIAL Core cut at 4 MiB is cut back to before the file the cut landed in: the half
 *                      file left there cannot be decoded
 * @param maxFileSize   the largest payload, in characters of its raw text as it stands in the body, that a file may have
 *                      and be kept; a longer one has its payload removed. {@code 0} removes every non-empty file, and
 *                      {@link #NO_LIMIT} none. Base64 is ASCII, so for a base64 file this is its stored size in bytes
 */
public record InlineFilesPolicy(boolean dropTruncated, long maxFileSize) {

    /** The {@code maxFileSize} under which no file is too long. */
    public static final long NO_LIMIT = Long.MAX_VALUE;

    public InlineFilesPolicy {
        if (maxFileSize < 0) {
            throw new IllegalArgumentException("maxFileSize must not be negative: " + maxFileSize);
        }
    }

    /** Keeps every body exactly as the producer sent it: nothing is parsed. */
    public static InlineFilesPolicy keep() {
        return new InlineFilesPolicy(false, NO_LIMIT);
    }

    /** The defaults: only a truncated body is touched, and it is only cut back; every complete file is kept. */
    public static InlineFilesPolicy defaults() {
        return new InlineFilesPolicy(true, NO_LIMIT);
    }

    /** Removes every inline file, and cuts a truncated body back before the file the cut landed in. */
    public static InlineFilesPolicy dropAll() {
        return new InlineFilesPolicy(true, 0);
    }

    /** Whether a body can be changed at all; when not, no body is parsed and no counter is registered. */
    public boolean rewrites() {
        return dropTruncated || maxFileSize != NO_LIMIT;
    }

    /** Whether a file whose payload is {@code length} characters long has that payload removed. */
    boolean tooLong(long length) {
        return length > maxFileSize;
    }
}
