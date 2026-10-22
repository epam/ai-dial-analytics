package com.epam.aidial.analytics.exception;

/**
 * A request parameter the server refused, carrying a message it is <b>safe to show the caller</b>.
 *
 * <p>That is the entire reason this type exists, and it is a security boundary rather than a convenience.
 * {@code DefaultExceptionHandler} deliberately never echoes a binding failure's own text: Spring's
 * converters and Jackson produce messages that leak type names, field paths and fragments of the input
 * ({@code No enum constant PipelineKind.BOGUS}), so the handler answers every unbindable parameter with one
 * generic sentence. The cost is that a parameter bound by a hand-written {@code @InitBinder} — which knows
 * exactly what it accepts and says so — has its useful message swallowed alongside the leaky ones.
 *
 * <p>Throwing <em>this</em> type is how a binder marks its message as authored here rather than derived
 * from the input, so the handler can echo it without relaxing the rule for anything else. Nothing else
 * should throw it, and the message must never interpolate the rejected value: naming the accepted set is
 * the point, repeating what the caller sent is what the policy forbids.
 *
 * <p>Extends {@link IllegalArgumentException} because a {@code PropertyEditor} may only signal a rejected
 * value that way — Spring catches it and wraps it in {@code MethodArgumentTypeMismatchException}, which is
 * where the handler unwraps it from.
 */
public class InvalidRequestParameterException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public InvalidRequestParameterException(String message) {
        super(message);
    }
}
