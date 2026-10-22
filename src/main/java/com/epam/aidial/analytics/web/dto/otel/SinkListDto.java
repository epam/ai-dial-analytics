package com.epam.aidial.analytics.web.dto.otel;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** Wrapper object for the sink list (an object, not a bare array, so it can grow fields later). */
public record SinkListDto(
        @Schema(description = "All OTLP sinks, ordered by name") List<SinkDto> sinks) {}
