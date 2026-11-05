package com.epam.aidial.analytics.clickhouse.replication;

import com.clickhouse.client.api.Client;

import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * A {@link ReplicaRouter} for unit tests that runs every unit of work in place and issues no sync. A pipeline run binds
 * {@link #PIPELINE}, a stand-in target, so a pipeline statement passes {@link ReplicaScope#requirePipelineRun}; the
 * clients it would hand out are never used, because these tests mock the statement issuers.
 */
public final class ReplicaRouterStubs {

    /** The stand-in home a stubbed pipeline run binds. */
    public static final ReplicaTarget PIPELINE = new ReplicaTarget() {
        @Override
        public String name() {
            return "test-home";
        }

        @Override
        public boolean primary() {
            return true;
        }

        @Override
        public Route route() {
            return Route.PIPELINE;
        }

        @Override
        public Client readClient() {
            throw new UnsupportedOperationException("the stand-in home has no client");
        }

        @Override
        public Client writerClient() {
            throw new UnsupportedOperationException("the stand-in home has no client");
        }

        @Override
        public void unreachable() {
        }
    };

    private ReplicaRouterStubs() {
    }

    /** Runs user queries and pipeline runs where they are called; issues no sync. */
    @SuppressWarnings("unchecked")
    public static ReplicaRouter inPlace() {
        final ReplicaRouter router = mock(ReplicaRouter.class);
        lenient().when(router.query(any())).thenAnswer(call -> ((Supplier<Object>) call.getArgument(0)).get());
        lenient().when(router.openRun(anyString(), any()))
                .thenAnswer(call -> ReplicaRouter.PipelineRun.unsynced(call.getArgument(0), PIPELINE));
        lenient().when(router.run(anyString(), any(), any())).thenAnswer(call -> {
            try (ReplicaRouter.PipelineRun ignored = ReplicaRouter.PipelineRun.unsynced(call.getArgument(0), PIPELINE)) {
                return ((Supplier<Object>) call.getArgument(2)).get();
            }
        });
        return router;
    }

    /** Binds {@link #PIPELINE} until closed, for a test that drives a pipeline statement issuer directly. */
    public static ReplicaScope.Binding pipelineRun() {
        return ReplicaScope.bind(PIPELINE);
    }
}
