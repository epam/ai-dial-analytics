package com.epam.aidial.analytics.service.query.arrow;

import jakarta.annotation.PreDestroy;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The application-scoped Arrow {@link RootAllocator}. Every
 * request gets its own bounded child allocator, closed when the request ends, so
 * {@link #allocatedBytes()} returning to zero between requests is the no-leak invariant the
 * integration tests assert. Only instantiated when the Flight endpoint is enabled.
 */
@Component
@ConditionalOnProperty(prefix = "flight", name = "enabled", havingValue = "true")
public class ArrowAllocators {

    private final BufferAllocator root = new RootAllocator();

    /** A named, size-bounded child allocator; the caller must close it. */
    public BufferAllocator newChildAllocator(String name, long limitBytes) {
        return root.newChildAllocator(name, 0, limitBytes);
    }

    /** Bytes currently allocated across all children — zero when no request is in flight. */
    public long allocatedBytes() {
        return root.getAllocatedMemory();
    }

    @PreDestroy
    void close() {
        root.close();
    }
}
