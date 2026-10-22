package com.epam.aidial.analytics.exception;

/**
 * A query asked for {@code FINAL} on an entity that collapses nothing — an {@code append} source (a plain
 * {@code MergeTree}, on which ClickHouse itself refuses {@code FINAL} with {@code ILLEGAL_FINAL}) with no
 * enrichment the caller may read. A {@link QueryValidationException} so every surface that already maps a validation failure (the
 * translation endpoints, the Flight transport's {@code INVALID_ARGUMENT}) maps this one too;
 * {@code DefaultExceptionHandler} gives it its own stable code, {@code illegal_final}.
 */
public class IllegalFinalException extends QueryValidationException {

    public IllegalFinalException(String entity) {
        super("FINAL is not supported on entity '" + entity + "': its source is 'append' and it has no enrichment"
                + " the caller may read, so nothing it reads collapses versions");
    }
}
