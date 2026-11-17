package com.epam.aidial.analytics.sync.delta;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Finds stored request paths in the payload table, for the parents neither the flush nor the cache knows, and the stored
 * carriers of body hashes.
 */
@FunctionalInterface
public interface ParentLookup {

    /** A lookup that finds nothing: every request it would have resolved becomes a chain root. */
    ParentLookup NONE = (days, paths) -> Map.of();

    /**
     * Returns, for each of {@code paths} stored on one of {@code days}, the {@code request_ancestor_hashes} of one row
     * carrying it. Any row is as good as another: every row carrying a path rebuilds to the same history.
     *
     * @param days  the UTC days ({@code yyyy-MM-dd}) the candidates were hashed under
     * @param paths the candidate paths
     * @return the paths found, each with its stored ancestors (root first)
     */
    Map<UUID, List<UUID>> find(Collection<String> days, Collection<UUID> paths);

    /**
     * A stored row holding a body under its body hash.
     *
     * @param hash       the body hash, the row's {@code request_hash}
     * @param eventId    the row's {@code event_id}
     * @param timeMillis its {@code request_time}, in epoch milliseconds
     */
    record Carrier(UUID hash, String eventId, long timeMillis) {}

    /**
     * Returns the rows stored on one of {@code days} whose current version carries one of {@code hashes}. Unlike
     * {@link #find}, a replaced row version must not be returned: it shows a body the table is about to lose. The
     * default finds none, so every row asking is stored with its body.
     *
     * @param days   the UTC days ({@code yyyy-MM-dd}) the hashes were computed under
     * @param hashes the body hashes
     */
    default List<Carrier> carriers(Collection<String> days, Collection<UUID> hashes) {
        return List.of();
    }
}
