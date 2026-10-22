package com.epam.aidial.analytics.service.enrichment.model;

import java.util.List;
import java.util.Map;

/**
 * One page of a governed incremental scan. {@code nextCursor} is the opaque token to echo on the
 * next request (unchanged from the input on an empty page); {@code hasMore} is true when the page
 * filled to the limit; {@code lagSeconds} is {@code server_now − max(version)} among returned rows
 * (null on an empty page) so the runner can emit a lag metric without decoding the cursor.
 */
public record ScanPage(
        List<Map<String, Object>> rows,
        String nextCursor,
        boolean hasMore,
        Long lagSeconds) {
}
