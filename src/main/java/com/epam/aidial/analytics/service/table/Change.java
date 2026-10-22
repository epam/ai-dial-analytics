package com.epam.aidial.analytics.service.table;

/**
 * A single metadata field's merge-patch intent: {@code present} true means "set to {@link #value()}"
 * (which may itself be {@code null}, meaning clear); {@code present} false means "leave unchanged".
 * Distinguishes "leave" from "clear" after normalization collapses a blank input to {@code null}.
 */
public record Change<T>(boolean present, T value) {

    private static final Change<?> LEAVE = new Change<>(false, null);

    /** A no-op change: leave the attribute unchanged. */
    @SuppressWarnings("unchecked")
    public static <T> Change<T> leave() {
        return (Change<T>) LEAVE;
    }

    /** A change that sets the attribute to {@code value} ({@code null} clears it). */
    public static <T> Change<T> set(T value) {
        return new Change<>(true, value);
    }
}
