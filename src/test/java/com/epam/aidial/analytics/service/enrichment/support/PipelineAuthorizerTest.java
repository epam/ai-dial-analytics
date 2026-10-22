package com.epam.aidial.analytics.service.enrichment.support;

import com.epam.aidial.analytics.exception.RowPolicyConfinedException;
import com.epam.aidial.analytics.service.security.RowPolicyFilter;
import com.epam.aidial.analytics.service.table.grant.TableAccessEvaluator;
import com.epam.aidial.analytics.web.security.SecurityMode;
import com.epam.aidial.analytics.web.security.SecurityModeResolver;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** The row-policy half of the data-plane authorization: a confined reader is refused, a writer is not asked. */
class PipelineAuthorizerTest {

    private final TableAccessEvaluator tableAccess = mock(TableAccessEvaluator.class);
    private final RowPolicyFilter rowPolicies = mock(RowPolicyFilter.class);
    private final PipelineAuthorizer authorizer =
            new PipelineAuthorizer(tableAccess, new SecurityModeResolver(SecurityMode.OIDC.code()), rowPolicies);

    @Test
    void readingCallerConfinedOnTheSourceIsRefused() {
        given(tableAccess.isAllowed(anyString(), any())).willReturn(true);
        willThrow(new RowPolicyConfinedException("confined on 'events'"))
                .given(rowPolicies).requireUnconfinedPipeline("events", "events_labels", "read through this pipeline");

        assertThatThrownBy(() -> authorizer.authorize("events", "events_labels"))
                .isInstanceOf(RowPolicyConfinedException.class);
    }

    @Test
    void theWriteOnlyPathIsNotAskedAboutRowPolicies() {
        given(tableAccess.isAllowed(anyString(), any())).willReturn(true);

        assertThatCode(() -> authorizer.authorize(null, "events_labels")).doesNotThrowAnyException();
        verify(rowPolicies, never()).requireUnconfinedPipeline(any(), any(), any());
    }
}
