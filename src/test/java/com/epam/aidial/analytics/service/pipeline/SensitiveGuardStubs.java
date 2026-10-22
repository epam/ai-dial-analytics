package com.epam.aidial.analytics.service.pipeline;

import com.epam.aidial.analytics.exception.PipelineValidationException;
import com.epam.aidial.analytics.service.enrichment.support.SensitiveProjectionGuard;

import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * The default {@link SensitiveProjectionGuard} behaviour for a unit test of the aggregate path, which
 * analyses its predicates <b>through</b> the guard rather than beside it.
 *
 * <p>Both halves are needed and neither is incidental. {@link SensitiveProjectionGuard#reattributing}
 * runs a supplier, so a bare mock answers {@code null} and every analysis in the test collapses into an
 * NPE several frames from the cause. And it applies
 * {@link SensitiveProjectionGuard#reattribute} to a failure, so stubbing only the wrapper would let a
 * test that asserts <em>which</em> exception a bad predicate produces pass for the wrong reason.
 *
 * <p>Shared rather than copied into each test because a second copy is how the two would start
 * disagreeing about what an un-entitled caller sees — the same argument the guard itself makes for
 * having one re-attribution.
 */
public final class SensitiveGuardStubs {

    private SensitiveGuardStubs() {
    }

    /**
     * Stubs {@code guard} as the <b>entitled</b> caller: a supplier runs, and a parse failure comes back
     * untouched — so it stays a validation failure rather than becoming a sensitive-column refusal. A test
     * about the un-entitled case overrides {@code reattribute} afterwards and inherits this wrapper.
     */
    public static void givenEntitledCaller(SensitiveProjectionGuard guard) {
        given(guard.reattribute(anyString(), anyString(), any(), anyBoolean()))
                .willAnswer(invocation -> invocation.getArgument(2));
        given(guard.reattributing(anyString(), anyString(), any(), anyBoolean()))
                .willAnswer(invocation -> {
                    try {
                        return ((Supplier<?>) invocation.getArgument(2)).get();
                    } catch (PipelineValidationException ex) {
                        throw guard.reattribute(invocation.getArgument(0), invocation.getArgument(1), ex,
                                invocation.getArgument(3));
                    }
                });
    }
}
