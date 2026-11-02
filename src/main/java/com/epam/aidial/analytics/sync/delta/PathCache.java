package com.epam.aidial.analytics.sync.delta;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The paths this instance has written, so most parents are found without asking ClickHouse: a conversation's turns
 * arrive minutes apart, so the previous turn is usually here. A bounded LRU; an evicted node stays reachable through
 * the children that link to it, so evicting never breaks a lineage. Shared by every ingest run of the instance — the
 * scheduled sync, the poller and a backfill may flush at the same time — hence synchronized.
 */
final class PathCache {

    private final Map<UUID, ChainNode> nodes;

    PathCache(int capacity) {
        this.nodes = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<UUID, ChainNode> eldest) {
                return size() > capacity;
            }
        };
    }

    /** The node written under {@code path}, or {@code null}. */
    synchronized ChainNode get(UUID path) {
        return nodes.get(path);
    }

    /** Records nodes whose rows are now written; call only after the insert carrying them succeeded. */
    synchronized void putAll(Collection<ChainNode> written) {
        for (ChainNode node : written) {
            nodes.put(node.path(), node);
        }
    }

    synchronized int size() {
        return nodes.size();
    }
}
