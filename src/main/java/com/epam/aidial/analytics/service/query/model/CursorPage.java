package com.epam.aidial.analytics.service.query.model;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

/**
 * Cursor pagination: a server-issued opaque {@code cursor} echoed verbatim by the client
 * (null/omitted on the first page) and a {@code limit}. The cursor's encoding and staleness
 * behavior are implementation details outside this model. An absent/null {@code limit} defaults to
 * 0 rather than failing deserialization.
 */
public record CursorPage(String cursor, @JsonSetter(nulls = Nulls.AS_EMPTY) int limit) implements PageSpec {}
