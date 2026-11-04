package com.epam.aidial.analytics.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;

/**
 * One row of the failure ledger: an attempt on a subject failed, the subject was resolved, or an operator
 * requeued it. The ledger is append-only, and a subject's state is derived from its events (see
 * {@link IngestFailureService}).
 *
 * @param kind             blob, day or records
 * @param subject          the blob's full key, or the day's ISO date
 * @param day              the UTC date the event belongs to: the day itself, or a blob's last-modified date
 * @param blobLastModified the blob's listed last-modified time; {@link Instant#EPOCH} for a day
 * @param type             what happened
 * @param path             the path that wrote it
 * @param mode             for a failed day, the {@link IngestLedger.Mode} its run took, so its retry runs the same way;
 *                         empty for every other event
 * @param reason           why the attempt failed; {@code null} unless {@code type} is {@link Type#FAILED}
 * @param message          a short description of the failure, never record content; empty when none
 * @param eventAt          when it happened
 */
public record IngestFailureEvent(
        IngestFailureKind kind,
        String subject,
        LocalDate day,
        Instant blobLastModified,
        Type type,
        String path,
        IngestFailureReason reason,
        String message,
        Instant eventAt,
        String mode) {

    /** The longest message stored. */
    public static final int MAX_MESSAGE_LENGTH = 512;

    /** The operator path spelling, for a requeue. */
    public static final String OPERATOR_PATH = "operator";

    public IngestFailureEvent {
        message = message == null ? "" : message.length() > MAX_MESSAGE_LENGTH ? message.substring(0, MAX_MESSAGE_LENGTH) : message;
        mode = mode == null ? "" : mode;
    }

    /** An event that records no ledger mode: every event but a failed day. */
    public IngestFailureEvent(IngestFailureKind kind, String subject, LocalDate day, Instant blobLastModified, Type type, String path,
            IngestFailureReason reason, String message, Instant eventAt) {
        this(kind, subject, day, blobLastModified, type, path, reason, message, eventAt, "");
    }

    /** A failed attempt on a blob. */
    static IngestFailureEvent blobFailed(String key, Instant lastModified, IngestPath path, IngestFailureReason reason, String message, Instant at) {
        return failed(IngestFailureKind.BLOB, key, lastModified, path, reason, message, at);
    }

    /** A failed attempt on a blob-keyed subject: a {@code blob}, or the {@code records} of a blob. */
    static IngestFailureEvent failed(IngestFailureKind kind, String key, Instant lastModified, IngestPath path, IngestFailureReason reason,
            String message, Instant at) {
        return new IngestFailureEvent(kind, key, blobDay(lastModified, at), lastModified, Type.FAILED, path.wire(), reason, message, at);
    }

    /** The skipped records of a blob that was otherwise ingested: always {@link IngestFailureReason#SKIPPED_RECORDS}. */
    static IngestFailureEvent recordsFailed(String key, Instant lastModified, IngestPath path, String message, Instant at) {
        return failed(IngestFailureKind.RECORDS, key, lastModified, path, IngestFailureReason.SKIPPED_RECORDS, message, at);
    }

    /** A failed run of a day, which took {@code mode}. */
    static IngestFailureEvent dayFailed(LocalDate day, IngestPath path, IngestLedger.Mode mode, IngestFailureReason reason, String message,
            Instant at) {
        return new IngestFailureEvent(IngestFailureKind.DAY, day.toString(), day, Instant.EPOCH, Type.FAILED, path.wire(), reason, message, at,
                mode.wire());
    }

    /** The subject no longer needs recovery. */
    static IngestFailureEvent resolved(IngestFailureKind kind, String subject, LocalDate day, Instant blobLastModified, String path, Instant at) {
        return new IngestFailureEvent(kind, subject, day, blobLastModified, Type.RESOLVED, path, null, "", at);
    }

    /** An operator asked for the subject to be attempted again. */
    static IngestFailureEvent requeued(IngestFailureKind kind, String subject, LocalDate day, Instant blobLastModified, Instant at) {
        return new IngestFailureEvent(kind, subject, day, blobLastModified, Type.REQUEUED, OPERATOR_PATH, null, "", at);
    }

    /** A blob's day: its last-modified date, or the event's own date when the store listed none. */
    static LocalDate blobDay(Instant lastModified, Instant at) {
        Instant basis = Instant.EPOCH.equals(lastModified) ? at : lastModified;
        return basis.atZone(ZoneOffset.UTC).toLocalDate();
    }

    /** What an event records. */
    public enum Type {
        FAILED,
        RESOLVED,
        REQUEUED;

        /** The lower-case spelling stored. */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
