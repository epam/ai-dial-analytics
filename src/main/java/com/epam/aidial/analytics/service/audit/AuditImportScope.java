package com.epam.aidial.analytics.service.audit;

import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The catalog import, if any, that the current thread is applying, so {@link AuditRevisionListener} can tag
 * every revision it writes with that import's id.
 *
 * <p>An import is step-committed: each object it creates or rolls back is its own transaction, and so its own
 * revision. A scope bound to one transaction, or to one Hibernate session, would end with the first step, so
 * this one is bound to the thread and spans the whole apply. It is not per-revision state — the listener keeps
 * the revision entity as its working set for that — and {@link #open} is used only in try-with-resources, so it
 * is cleared deterministically and no residue reaches a pooled thread.
 *
 * <p><strong>Invariant this depends on:</strong> the one {@link AuditRevisionListener#newRevision} already
 * documents for the author stamp. Envers builds the revision at commit, on the committing thread, so a write
 * the import drives is tagged only while it commits on the thread holding the scope. A write handed to another
 * thread would lose the tag silently.
 *
 * <p>An instance field rather than a static: a static {@code ThreadLocal} would be shared by every Spring
 * context in the JVM, the cross-wiring the listener's own Javadoc rules out for the same reason.
 */
@Component
public class AuditImportScope {

    private final ThreadLocal<UUID> current = new ThreadLocal<>();

    /**
     * Binds {@code importId} to the current thread until the returned scope is closed.
     *
     * @throws IllegalStateException when an import scope is already open on this thread: imports never nest,
     *                               and shadowing the outer id would tag its revisions with the wrong import
     */
    public Scope open(UUID importId) {
        final UUID open = current.get();
        if (open != null) {
            throw new IllegalStateException("import scope " + open + " is already open on this thread");
        }
        current.set(Objects.requireNonNull(importId, "importId"));
        return current::remove;
    }

    /** The import the current thread is applying, or empty. */
    public Optional<UUID> current() {
        return Optional.ofNullable(current.get());
    }

    /** An open import scope; closing it never throws. */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
