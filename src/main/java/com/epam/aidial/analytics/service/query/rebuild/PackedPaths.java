package com.epam.aidial.analytics.service.query.rebuild;

import java.util.AbstractList;
import java.util.List;
import java.util.RandomAccess;
import java.util.UUID;

/**
 * An immutable list of request paths held as two {@code long}s each rather than as {@link UUID} objects. A read keeps
 * every resolved link's and every target's chain hint (up to 128 paths each) for its whole life, so the hints dominate
 * the memory a rebuild holds before any body is read; packed, a path costs 16 bytes instead of an object and a
 * reference. Elements are materialized on access, so the list is for iteration, not for identity.
 */
final class PackedPaths extends AbstractList<UUID> implements RandomAccess {

    private static final PackedPaths EMPTY = new PackedPaths(new long[0]);

    private final long[] bits;

    private PackedPaths(long[] bits) {
        this.bits = bits;
    }

    /** A packed copy of {@code paths}; none of them may be {@code null}. */
    static PackedPaths of(List<UUID> paths) {
        if (paths.isEmpty()) {
            return EMPTY;
        }
        final long[] bits = new long[paths.size() * 2];
        int i = 0;
        for (final UUID path : paths) {
            bits[i++] = path.getMostSignificantBits();
            bits[i++] = path.getLeastSignificantBits();
        }
        return new PackedPaths(bits);
    }

    @Override
    public UUID get(int index) {
        if (index < 0 || index >= size()) {
            throw new IndexOutOfBoundsException(index);
        }
        return new UUID(bits[index * 2], bits[index * 2 + 1]);
    }

    @Override
    public int size() {
        return bits.length / 2;
    }
}
