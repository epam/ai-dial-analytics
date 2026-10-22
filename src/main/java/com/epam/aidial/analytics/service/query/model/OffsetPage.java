package com.epam.aidial.analytics.service.query.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

/**
 * Offset pagination: a 0-based row {@code offset} and {@code limit}, with opt-in total count
 * via {@code include_total} (default false). {@code limit}/max-offset governance is applied
 * by the execution layer, not modeled here. Absent/null primitives default (offset 0, limit 0,
 * include_total false) rather than failing deserialization.
 */
public record OffsetPage(
        @JsonSetter(nulls = Nulls.AS_EMPTY) long offset,
        @JsonSetter(nulls = Nulls.AS_EMPTY) int limit,
        @JsonProperty("include_total") @JsonSetter(nulls = Nulls.AS_EMPTY) boolean includeTotal) implements PageSpec {}
