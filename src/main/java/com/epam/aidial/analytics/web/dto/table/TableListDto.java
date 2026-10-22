package com.epam.aidial.analytics.web.dto.table;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** Wrapper object for the table list (an object, not a bare array, so it can grow fields later). */
public record TableListDto(
        @Schema(description = "All table definitions, ordered by name") List<TableDto> tables) {}
