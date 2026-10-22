package com.epam.aidial.analytics.service.enrichment.model;

import java.util.List;
import java.util.Map;

/**
 * One group's assembled member rows. {@code truncated} is true when either the row cap or the response
 * byte cap trimmed the group, so a caller can record that its input was partial and a downstream consumer
 * can identify a partial result.
 *
 * @param rows      the group's members, projected to the pipeline's referenced columns, in the source's total order
 * @param truncated whether either cap trimmed the group
 */
public record GroupPage(List<Map<String, Object>> rows, boolean truncated) {}
