package com.epam.aidial.analytics.web.dto.table.rowpolicy;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/** A table's row policies, keyed by raw provider role; an empty object when the table has none. */
public record RowPoliciesDto(
        @Schema(description = "Row policies keyed by raw provider role name") Map<String, RowPolicyDto> policies) {}
