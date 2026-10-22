package com.epam.aidial.analytics.sync.inline;

/**
 * What ingest does with the inline files of a body column whose mapping declares {@code inline-files}
 * ({@code sync.inline-files-mode}). Bound leniently (case and {@code -}/{@code _} ignored), so {@code keep},
 * {@code drop-truncated} and {@code drop} in the environment select these constants; any other value fails binding and
 * therefore startup.
 */
public enum InlineFilesMode {

    /** Store the body exactly as the producer sent it; nothing is parsed. */
    KEEP,

    /**
     * Keep every complete file, and cut a body DIAL Core cut at 4 MiB back to before the file the cut landed in: the
     * half file left there cannot be decoded. Only such bodies are parsed. Data hygiene rather than a storage saving.
     * The default: it loses nothing a reader could use.
     */
    DROP_TRUNCATED,

    /**
     * Remove the payload of every inline file before the row is written, and cut a truncated body back to before
     * a file the 4 MiB cut landed in. Lossy: the files are not kept anywhere.
     */
    DROP
}
