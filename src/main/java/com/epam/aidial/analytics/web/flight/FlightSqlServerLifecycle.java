package com.epam.aidial.analytics.web.flight;

import com.epam.aidial.analytics.service.query.arrow.ArrowAllocators;
import lombok.extern.slf4j.Slf4j;
import org.apache.arrow.flight.FlightServer;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.auth2.CallHeaderAuthenticator;
import org.apache.arrow.memory.BufferAllocator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * The Arrow Flight SQL gRPC listener: a {@link FlightServer}
 * hosting {@link AdasFlightSqlProducer}, started after the context is ready and only when
 * {@code flight.enabled} is true (this bean does not exist otherwise). When a
 * {@link CallHeaderAuthenticator} bean is present (security.mode=oidc), it is installed so every
 * RPC authenticates; without one the endpoint is open, matching HTTP behavior in mode=none.
 * Per-stream Arrow buffers use request-scoped child allocators (in the producer); this
 * server-lifetime allocator backs the gRPC/IPC machinery.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "flight", name = "enabled", havingValue = "true")
public class FlightSqlServerLifecycle implements SmartLifecycle {

    private final FlightProperties properties;
    private final AdasFlightSqlProducer producer;
    private final ArrowAllocators allocators;
    private final ObjectProvider<CallHeaderAuthenticator> authenticator;
    private final ObjectProvider<FlightPrincipalMiddleware.Factory> principalMiddleware;
    private volatile FlightServer server;
    private volatile BufferAllocator serverAllocator;
    private volatile boolean running;

    public FlightSqlServerLifecycle(
            FlightProperties properties,
            AdasFlightSqlProducer producer,
            ArrowAllocators allocators,
            ObjectProvider<CallHeaderAuthenticator> authenticator,
            ObjectProvider<FlightPrincipalMiddleware.Factory> principalMiddleware) {
        this.properties = properties;
        this.producer = producer;
        this.allocators = allocators;
        this.authenticator = authenticator;
        this.principalMiddleware = principalMiddleware;
    }

    @Override
    public void start() {
        running = true;
        this.serverAllocator = allocators.newChildAllocator("flight-server", Long.MAX_VALUE);
        final Location location = Location.forGrpcInsecure("0.0.0.0", properties.port());
        final FlightServer.Builder builder = FlightServer.builder(serverAllocator, location, producer);
        final CallHeaderAuthenticator auth = authenticator.getIfAvailable();
        if (auth != null) {
            builder.headerAuthenticator(auth);
        }
        // Capture the authenticated principal per call so the producer can filter sensitive columns by
        // role (oidc mode only; in none mode no factory exists and the producer sees no principal).
        final FlightPrincipalMiddleware.Factory principalFactory = principalMiddleware.getIfAvailable();
        if (principalFactory != null) {
            builder.middleware(FlightPrincipalMiddleware.KEY, principalFactory);
        }
        try {
            this.server = builder.build().start();
        } catch (IOException e) {
            throw new IllegalStateException("Flight SQL listener failed to bind port " + properties.port(), e);
        }
        log.info("Flight SQL endpoint listening on port {} ({})",
                boundPort(), auth != null ? "authenticated" : "no authentication (security.mode=none)");
    }

    /** Actual bound port — differs from configuration when port 0 (ephemeral) is used in tests. */
    public int boundPort() {
        final FlightServer current = this.server;
        return current == null ? -1 : current.getPort();
    }

    @Override
    public void stop() {
        running = false;
        final FlightServer current = this.server;
        if (current != null) {
            try {
                current.shutdown();
                current.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("Flight SQL server did not shut down cleanly", e);
            }
        }
        final BufferAllocator allocator = this.serverAllocator;
        if (allocator != null) {
            try {
                allocator.close();
            } catch (Exception e) {
                log.warn("Flight server allocator did not close cleanly", e);
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
