package com.epam.aidial.analytics.clickhouse;

import java.util.ArrayList;
import java.util.List;

/**
 * How a list of values is split across statements when it is bound as one server-side query parameter. The client
 * sends query parameters in the request URI, which the server bounds ({@code http_max_uri_size}) and so may any proxy
 * in front of it, so a list is bound at most {@value #SIZE} values per statement: about 37 URI bytes per UUID, so a
 * statement stays well inside common URI limits. The one place that size is decided, for every reader that binds an
 * unbounded list this way.
 */
public final class QueryParameterChunks {

    /** Values bound per statement. */
    public static final int SIZE = 1000;

    private QueryParameterChunks() {
    }

    /** {@code values} in consecutive chunks of at most {@link #SIZE}, in order; views of {@code values}, not copies. */
    public static <T> List<List<T>> of(List<T> values) {
        final List<List<T>> chunks = new ArrayList<>();
        for (int start = 0; start < values.size(); start += SIZE) {
            chunks.add(values.subList(start, Math.min(values.size(), start + SIZE)));
        }
        return chunks;
    }
}
