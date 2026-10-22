package com.epam.aidial.analytics.web.handler;

import jakarta.servlet.http.HttpServletRequest;
import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Stable JSON error contract shared by every endpoint:
 * {@code {"status": <int>, "error": "<machine_code>", "message": "<human readable>", ...}}.
 *
 * <p>{@code error} is a stable machine-readable code (e.g. {@code internal_error},
 * {@code clickhouse_unavailable}); {@code message} is human-readable. Stack traces and raw
 * upstream internals must never be exposed through this view.
 */
@Getter
public class ErrorView {

    /** Hard cap on the sanitized message length, bounding worst-case exposure in a response body. */
    private static final int MAX_MESSAGE_CHARS = 2000;

    private final Integer status;
    private final String error;
    private final String message;
    private final String path;
    private final String method;

    public ErrorView(HttpServletRequest request, HttpStatus status, String error, String message) {
        this.status = status.value();
        this.error = error;
        this.message = sanitizeMessage(message);
        this.path = request.getServletPath();
        this.method = request.getMethod();
    }

    /**
     * Best-effort sanitization to prevent accidental stacktrace leakage in API responses.
     * Error messages should already be sanitized at the exception-handling layer; this is a last
     * line of defense.
     */
    private static String sanitizeMessage(String message) {
        String safe = Objects.requireNonNullElse(message, "");
        if (STACK_FRAME.matcher(safe).find()) {
            // A stack trace follows the first line break (any of \n, \r, \r\n); keep only the leading line.
            int firstBreak = indexOfLineBreak(safe);
            if (firstBreak >= 0) {
                safe = safe.substring(0, firstBreak);
            }
        }

        if (safe.length() > MAX_MESSAGE_CHARS) {
            safe = safe.substring(0, MAX_MESSAGE_CHARS);
        }
        return safe;
    }

    /**
     * Matches a stack-frame line ("... at com.foo.Bar(...)") after any line terminator, tolerating
     * CRLF endings and frames indented with spaces or no leading tab at all.
     */
    private static final Pattern STACK_FRAME = Pattern.compile("\\R\\s*at\\s");

    private static int indexOfLineBreak(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\n' || c == '\r') {
                return i;
            }
        }
        return -1;
    }
}
