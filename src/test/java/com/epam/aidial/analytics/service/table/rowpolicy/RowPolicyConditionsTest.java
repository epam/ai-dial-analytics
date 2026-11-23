package com.epam.aidial.analytics.service.table.rowpolicy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RowPolicyConditionsTest {

    private static final String M = "m:";

    @Test
    void callerValueIsMarkedOutsideQuotesAndComments() {
        assertThat(RowPolicyConditions.markCallerValues("a = caller.project", M)).isEqualTo("a = 'm:caller.project'");
        assertThat(RowPolicyConditions.markCallerValues("a = 'caller.project'", M)).isEqualTo("a = 'caller.project'");
        assertThat(RowPolicyConditions.markCallerValues("a = xcaller.project OR a = caller.project_id", M))
                .isEqualTo("a = xcaller.project OR a = caller.project_id");
    }

    @Test
    void quoteInsideCommentOpensNothing() {
        assertThat(RowPolicyConditions.markCallerValues("/* don't */ a = caller.project", M))
                .isEqualTo("/* don't */ a = 'm:caller.project'");
        assertThat(RowPolicyConditions.markCallerValues("a = 1 -- don't\nAND b = caller.user_hash", M))
                .isEqualTo("a = 1 -- don't\nAND b = 'm:caller.user_hash'");
        assertThat(RowPolicyConditions.markCallerValues("a = 1 // don't\r\nAND b = caller.user_hash", M))
                .isEqualTo("a = 1 // don't\r\nAND b = 'm:caller.user_hash'");
    }

    @Test
    void callerValueInsideCommentIsLeftAlone() {
        assertThat(RowPolicyConditions.markCallerValues("a = 1 /* caller.project */ -- caller.user_hash", M))
                .isEqualTo("a = 1 /* caller.project */ -- caller.user_hash");
        assertThat(RowPolicyConditions.markCallerValues("a = '--' AND b = caller.project /* open", M))
                .isEqualTo("a = '--' AND b = 'm:caller.project' /* open");
    }
}
