package com.epam.aidial.analytics.service.enrichment;

import com.epam.aidial.analytics.observability.CursorPosition;
import com.epam.aidial.analytics.observability.LogCapture;
import com.epam.aidial.analytics.service.pipeline.PipelineRepository;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * The position writer's log lines: a position is named as {@code (version,#digest)}, never with the source
 * identity it holds, and a discard is logged only when a generation was stored.
 */
class MaterializePositionWriterTest {

    private static final String PIPELINE = "c1-live";
    private static final String IDENTITY = "user-42@example.com";

    private final PipelineRepository pipelines = mock(PipelineRepository.class);
    private final MaterializePositionWriter writer = new MaterializePositionWriter(pipelines);

    @Test
    void positionLinesCarryTheIdentitysDigestNotTheIdentity() {
        given(pipelines.discardMarkOfOtherGeneration(anyString(), anyLong())).willReturn(Optional.empty());
        given(pipelines.advanceMaterializedThrough(anyString(), anyLong(), anyString())).willReturn(0);

        try (LogCapture log = LogCapture.of(MaterializePositionWriter.class)) {
            writer.recordMaterialized(PIPELINE, 3L, 1_700_000_000_000L, IDENTITY, true);

            assertThat(log.messagesAt(Level.DEBUG)).singleElement().asString()
                    .contains("at or past " + CursorPosition.of(1_700_000_000_000L, IDENTITY));
            assertThat(log.messagesAt(Level.INFO)).singleElement().asString()
                    .contains("ending at " + CursorPosition.of(1_700_000_000_000L, IDENTITY));
            assertThat(log.messagesAt(Level.DEBUG)).noneMatch(line -> line.contains("user-42"));
            assertThat(log.messagesAt(Level.INFO)).noneMatch(line -> line.contains("user-42"));
        }
    }

    @Test
    void discardIsLoggedOnlyWhenSomeGenerationWasStored() {
        given(pipelines.advanceMaterializedThrough(anyString(), anyLong(), anyString())).willReturn(1);
        given(pipelines.discardMarkOfOtherGeneration(PIPELINE, 4L))
                .willReturn(Optional.of(stored(null)))
                .willReturn(Optional.of(stored(3L)))
                .willReturn(Optional.empty());

        try (LogCapture log = LogCapture.of(MaterializePositionWriter.class)) {
            writer.recordMaterialized(PIPELINE, 4L, 1L, "a", false);
            writer.recordDrained(PIPELINE, 4L, Instant.EPOCH);
            writer.recordMaterialized(PIPELINE, 4L, 2L, "b", false);

            assertThat(log.messagesAt(Level.INFO))
                    .containsExactly("pipeline=" + PIPELINE + " position discarded: stored_gen=3 -> gen=4");
        }
    }

    private static PipelineRepository.DiscardedMark stored(Long generation) {
        return () -> generation;
    }
}
