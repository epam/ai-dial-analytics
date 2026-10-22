package com.epam.aidial.analytics.service.otel;

import com.epam.aidial.analytics.exception.TableValidationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit coverage for the {@code otel_<sink>_<signal>} derivation and the sink-name bound behind it. */
class OtelTableNamesTest {

    @Test
    void derivesOneTableNamePerSignal() {
        assertThat(OtelTableNames.tableName("app", OtelSignal.LOGS)).isEqualTo("otel_app_logs");
        assertThat(OtelTableNames.tableName("app", OtelSignal.TRACES)).isEqualTo("otel_app_traces");
        assertThat(OtelTableNames.tableName("app", OtelSignal.METRICS)).isEqualTo("otel_app_metrics");
    }

    @Test
    void endpointIsTheOtlpBasePathAnExporterIsConfiguredWith() {
        assertThat(OtelTableNames.endpoint("app")).isEqualTo("/v1/otel/sink/app");
    }

    @Test
    void acceptsSinkNameAtTheBound() {
        final String longest = "a".repeat(OtelTableNames.MAX_SINK_NAME_LENGTH);
        assertThat(OtelTableNames.requireValidSinkName(longest)).isEqualTo(longest);
        // The point of the bound: the longest derived name still fits the 64-char catalog limit.
        assertThat(OtelTableNames.tableName(longest, OtelSignal.METRICS)).hasSize(64);
    }

    @Test
    void rejectsSinkNameThatWouldOverflowDerivedTableName() {
        assertThatThrownBy(() -> OtelTableNames.requireValidSinkName(
                "a".repeat(OtelTableNames.MAX_SINK_NAME_LENGTH + 1)))
                .isInstanceOf(TableValidationException.class)
                .hasMessageContaining("64-character catalog identifier limit");
    }

    @Test
    void rejectsNameThatIsNotCatalogIdentifier() {
        assertThatThrownBy(() -> OtelTableNames.requireValidSinkName("My-Sink"))
                .isInstanceOf(TableValidationException.class);
        assertThatThrownBy(() -> OtelTableNames.requireValidSinkName("_reserved"))
                .isInstanceOf(TableValidationException.class);
        assertThatThrownBy(() -> OtelTableNames.requireValidSinkName(null))
                .isInstanceOf(TableValidationException.class);
    }

    @Test
    void signalCodesRoundTrip() {
        for (final OtelSignal signal : OtelSignal.values()) {
            assertThat(OtelSignal.fromCode(signal.code())).isEqualTo(signal);
        }
        assertThat(OtelSignal.fromCode("profiles")).isNull();
        assertThat(OtelSignal.fromCode(null)).isNull();
    }
}
