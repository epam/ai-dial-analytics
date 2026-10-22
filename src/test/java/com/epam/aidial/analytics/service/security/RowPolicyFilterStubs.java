package com.epam.aidial.analytics.service.security;

import com.clickhouse.client.api.query.QuerySettings;
import org.mockito.Mockito;

import static org.mockito.ArgumentMatchers.any;

/** Test doubles for {@link RowPolicyFilter}, for unit tests whose subject is not row policies. */
public final class RowPolicyFilterStubs {

    private RowPolicyFilterStubs() {
    }

    /**
     * A filter that confines nobody: {@code apply} hands back the settings it was given, {@code isConfined}
     * answers false, and the refusals are no-ops — what the real filter does when no table carries a policy.
     */
    public static RowPolicyFilter inert() {
        final RowPolicyFilter filter = Mockito.mock(RowPolicyFilter.class);
        Mockito.lenient().when(filter.apply(any(QuerySettings.class))).thenAnswer(inv -> inv.getArgument(0));
        return filter;
    }
}
