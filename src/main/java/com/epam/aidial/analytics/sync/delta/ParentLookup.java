package com.epam.aidial.analytics.sync.delta;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Finds stored request paths in the payload table, for the parents neither the flush nor the cache knows. */
@FunctionalInterface
public interface ParentLookup {

    /** A lookup that finds nothing: every request it would have resolved becomes a chain root. */
    ParentLookup NONE = (days, paths) -> Map.of();

    /**
     * Returns, for each of {@code paths} stored on one of {@code days}, the {@code request_ancestors} of one row
     * carrying it and the {@code request_tools_hash} values of the rows carrying it. Any row is as good as another for
     * the history: every row carrying a path rebuilds to the same one. The tools may differ between such rows, so all
     * their hashes are returned.
     *
     * @param days  the UTC days ({@code yyyy-MM-dd}) the candidates were hashed under
     * @param paths the candidate paths
     * @return the paths found
     */
    Map<UUID, Found> find(Collection<String> days, Collection<UUID> paths);

    /**
     * A stored path.
     *
     * @param ancestors  the stored ancestors of one row carrying it, root first
     * @param toolsHashes the tools hashes of the rows carrying it; the all-zero hash stands for a row without tools
     */
    record Found(List<UUID> ancestors, List<UUID> toolsHashes) {
    }
}
