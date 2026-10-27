package com.epam.aidial.analytics.sync.delta;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * A stored request's place in a chain, as ingest knows it: its path and a link to its parent's node. Linking nodes
 * instead of copying ancestor lists keeps a cache entry small however deep the chain is. A node learned from the
 * payload table has no parent node; it carries the {@code request_ancestor_hashes} hint its row was stored with instead,
 * packed as two longs per path — and it is cached only as the parent of a node written here.
 */
final class ChainNode {

    private static final long[] NONE = new long[0];

    private final UUID path;
    private final ChainNode parent;
    private final long[] storedAncestors;

    private ChainNode(UUID path, ChainNode parent, long[] storedAncestors) {
        this.path = path;
        this.parent = parent;
        this.storedAncestors = storedAncestors;
    }

    /** A node written by this instance: a root when {@code parent} is {@code null}. */
    static ChainNode written(UUID path, ChainNode parent) {
        return new ChainNode(path, parent, NONE);
    }

    /** A node read back from the payload table, with the {@code request_ancestor_hashes} its row carries (root side first). */
    static ChainNode stored(UUID path, List<UUID> ancestors) {
        long[] packed = new long[ancestors.size() * 2];
        for (int i = 0; i < ancestors.size(); i++) {
            packed[2 * i] = ancestors.get(i).getMostSignificantBits();
            packed[2 * i + 1] = ancestors.get(i).getLeastSignificantBits();
        }
        return new ChainNode(path, null, packed);
    }

    UUID path() {
        return path;
    }

    /**
     * The nearest {@code limit} paths of the chain, down to and including this node, root side first: what a child stores
     * as its ancestors hint. The walk stops after {@code limit} nodes, so a deep chain costs no more than a shallow one.
     * For a chain that passes through a node read from the table, the paths above it come from that row's own hint.
     */
    List<UUID> lineage(int limit) {
        Deque<UUID> chain = new ArrayDeque<>();
        ChainNode node = this;
        while (true) {
            chain.addFirst(node.path);
            if (node.parent == null || chain.size() == limit) {
                break;
            }
            node = node.parent;
        }
        List<UUID> lineage = new ArrayList<>(limit);
        if (node.parent == null) {
            int stored = node.storedAncestors.length / 2;
            for (int i = Math.max(0, stored - (limit - chain.size())); i < stored; i++) {
                lineage.add(new UUID(node.storedAncestors[2 * i], node.storedAncestors[2 * i + 1]));
            }
        }
        lineage.addAll(chain);
        return lineage;
    }
}
