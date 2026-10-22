package com.epam.aidial.analytics.service.pipeline.preview;

/**
 * One coded remark about a preview. {@code subject} names the column, output or enrichment the code is about,
 * and is null for the codes that name none.
 */
public record PreviewNote(PreviewNoteCode code, String subject, String message) {

    public static PreviewNote of(PreviewNoteCode code, String message) {
        return new PreviewNote(code, null, message);
    }

    public static PreviewNote about(PreviewNoteCode code, String subject, String message) {
        return new PreviewNote(code, subject, message);
    }

    public PreviewNoteSeverity severity() {
        return code.severity();
    }
}
