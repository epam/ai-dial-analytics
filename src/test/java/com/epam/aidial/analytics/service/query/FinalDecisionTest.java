package com.epam.aidial.analytics.service.query;

import com.epam.aidial.analytics.exception.IllegalFinalException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FinalDecisionTest {

    @ParameterizedTest(name = "finalCapable={0} requested={1} force={2} -> {3}")
    @CsvSource(nullValues = "null", value = {
        // A final-capable entity (upsert_by_key source, or any enrichment): absent reads final, the flag forces final.
        "true,  null,  false, true",
        "true,  true,  false, true",
        "true,  false, false, false",
        "true,  null,  true,  true",
        "true,  true,  true,  true",
        "true,  false, true,  true",
        // An append source with no enrichment: raw whatever the flag says, unless FINAL is asked for (refused below).
        "false, null,  false, false",
        "false, false, false, false",
        "false, null,  true,  false",
        "false, false, true,  false",
    })
    void decides(boolean finalCapable, Boolean requested, boolean force, boolean expected) {
        assertThat(FinalDecision.readsFinal("e", finalCapable, requested, force)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "force={0}")
    @CsvSource({"false", "true"})
    void refusesFinalOnAppendSourceWhateverTheFlag(boolean force) {
        assertThatThrownBy(() -> FinalDecision.readsFinal("events", false, true, force))
                .isInstanceOf(IllegalFinalException.class)
                .hasMessageContaining("'events'")
                .hasMessageContaining("append");
    }
}
