package com.epam.aidial.analytics.service.otel;

import com.epam.aidial.analytics.configuration.OtelSinkProperties;
import com.epam.aidial.analytics.exception.OtelDisabledException;
import com.epam.aidial.analytics.exception.TableValidationException;
import com.epam.aidial.analytics.service.table.TableCatalogService;
import com.epam.aidial.analytics.service.table.TableDefinitionRepository;
import com.epam.aidial.analytics.service.table.TableSchemaCreationService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Request-validation contract of the sink control plane. Every rule about the create body is enforced
 * here rather than by bean validation at the DTO, so that all of them report one status —
 * {@link TableValidationException} → HTTP 422 {@code validation_error} — instead of an empty list or an
 * unknown signal code failing at binding time as a 400.
 */
class OtelSinkServiceTest {

    private final OtelSinkRepository sinks = Mockito.mock(OtelSinkRepository.class);
    private final TableCatalogService catalog = Mockito.mock(TableCatalogService.class);
    private final TableSchemaCreationService schemas = Mockito.mock(TableSchemaCreationService.class);
    private final TableDefinitionRepository definitions = Mockito.mock(TableDefinitionRepository.class);

    private final OtelSinkService service = new OtelSinkService(
            sinks, new OtelSinkProperties(true, 50_000, 30), catalog, schemas, definitions);

    private void assertRejected(String name, List<String> signals, Integer retentionDays) {
        assertThatExceptionOfType(TableValidationException.class)
                .isThrownBy(() -> service.create(name, null, signals, retentionDays));
        Mockito.verifyNoInteractions(catalog, schemas);
        Mockito.verify(sinks, Mockito.never()).save(Mockito.any());
    }

    @Test
    void emptySignalListIsRejectedAsValidationError() {
        assertRejected("app", List.of(), null);
        assertRejected("app", null, null);
    }

    @Test
    void unknownSignalCodeIsRejectedAsValidationError() {
        // The DTO binds signals as raw text precisely so this lands here, as 422, rather than failing
        // enum deserialization and reporting 400.
        assertRejected("app", List.of("profiles"), null);
        assertRejected("app", Arrays.asList("logs", null), null);
    }

    @Test
    void duplicateSignalIsRejectedAsValidationError() {
        assertRejected("app", List.of("logs", "logs"), null);
    }

    @Test
    void blankOrUngrammaticalNameIsRejectedAsValidationError() {
        assertRejected("", List.of("logs"), null);
        assertRejected(null, List.of("logs"), null);
        assertRejected("App Sink", List.of("logs"), null);
        assertRejected("a".repeat(OtelTableNames.MAX_SINK_NAME_LENGTH + 1), List.of("logs"), null);
    }

    @Test
    void retentionBelowOneIsRejectedAsValidationError() {
        assertRejected("app", List.of("logs"), 0);
    }

    @Test
    void disabledSurfaceIsRefusedBeforeAnythingIsValidated() {
        final OtelSinkService disabled = new OtelSinkService(
                sinks, new OtelSinkProperties(false, 50_000, 30), catalog, schemas, definitions);

        assertThatThrownBy(() -> disabled.create("app", null, List.of("logs"), null))
                .isInstanceOf(OtelDisabledException.class);
    }
}
