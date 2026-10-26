package com.epam.aidial.analytics.sync.delta;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * A stored request's place in a chain, as ingest knows it: its path, the hash of its tools, and a link to its parent's
 * node. Linking nodes
 * instead of copying ancestor lists keeps a cache entry small however deep the chain is. A node learned from the
 * payload table has no parent node; it carries the {@code request_ancestors} hint its row was stored with instead,
 * packed as two longs per path, and the tools hashes of every row carrying its path — and it is cached only as the
 * parent of a node written here.
 */
final class ChainNode {

    private static final long[] NONE = new long[0];

    private final UUID path;
    private final ChainNode parent;
    private final long[] storedAncestors;
    /** The tools hashes of the rows carrying this path, packed; the all-zero hash stands for a row without tools. */
    private final long[] toolsHashes;

    private ChainNode(UUID path, ChainNode parent, long[] storedAncestors, long[] toolsHashes) {
        this.path = path;
        this.parent = parent;
        this.storedAncestors = storedAncestors;
        this.toolsHashes = toolsHashes;
    }

    /** A node written by this instance: a root when {@code parent} is {@code null}; {@code toolsHash} null for no tools. */
    static ChainNode written(UUID path, ChainNode parent, UUID toolsHash) {
        return new ChainNode(path, parent, NONE, toolsHash == null ? NONE : pack(List.of(toolsHash)));
    }

    /** A node read back from the payload table, with the {@code request_ancestors} one of its rows carries (root side first). */
    static ChainNode stored(UUID path, ParentLookup.Found found) {
        return new ChainNode(path, null, pack(found.ancestors()), pack(found.toolsHashes()));
    }

    private static long[] pack(List<UUID> uuids) {
        long[] packed = new long[uuids.size() * 2];
        for (int i = 0; i < uuids.size(); i++) {
            packed[2 * i] = uuids.get(i).getMostSignificantBits();
            packed[2 * i + 1] = uuids.get(i).getLeastSignificantBits();
        }
        return packed;
    }

    UUID path() {
        return path;
    }

    /**
     * Whether some stored row carrying this path has tools hashing to {@code toolsHash}: then a child with those tools
     * may leave them out, and a rebuild finds them on that row or on the ancestor it inherited them from.
     */
    boolean carriesTools(UUID toolsHash) {
        for (int i = 0; i < toolsHashes.length; i += 2) {
            if (toolsHashes[i] == toolsHash.getMostSignificantBits() && toolsHashes[i + 1] == toolsHash.getLeastSignificantBits()) {
                return true;
            }
        }
        return false;
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
