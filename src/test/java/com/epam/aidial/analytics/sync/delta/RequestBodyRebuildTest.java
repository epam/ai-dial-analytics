package com.epam.aidial.analytics.sync.delta;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The production rebuild over hand-built stored rows: each fixture is the canonical text ingest would store, so a
 * case reads as "these rows were stored, this is the request they rebuild to". Paths are patterned ids; the rebuild
 * follows links and never recomputes a hash.
 */
class RequestBodyRebuildTest {

    private static final UUID ROOT = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID MIDDLE = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID MISSING = UUID.fromString("00000000-0000-4000-8000-0000000000ff");

    private final Map<UUID, RequestBodyRebuild.Ancestor> stored = new HashMap<>();

    @Test
    void followUpTurnGetsItsWholeHistoryBack() {
        root("{\"messages\":[{\"content\":\"u1\",\"role\":\"user\"}],\"model\":\"m\"}");
        child(MIDDLE, ROOT, "{\"messages\":[{\"content\":\"a1\",\"role\":\"assistant\"},{\"content\":\"u2\",\"role\":\"user\"}],\"model\":\"m\"}");

        RequestBodyRebuild.Result result = rebuild("{\"messages\":[{\"content\":\"a2\",\"role\":\"assistant\"},"
                + "{\"content\":\"u3\",\"role\":\"user\"}],\"model\":\"m\"}", MIDDLE, null);

        assertRebuilt(result, "{\"messages\":[{\"content\":\"u1\",\"role\":\"user\"},{\"content\":\"a1\",\"role\":\"assistant\"},"
                + "{\"content\":\"u2\",\"role\":\"user\"},{\"content\":\"a2\",\"role\":\"assistant\"},{\"content\":\"u3\",\"role\":\"user\"}],"
                + "\"model\":\"m\"}");
    }

    @Test
    void anAnthropicSystemPromptComesFromTheRoot() {
        root("{\"messages\":[{\"content\":\"u1\",\"role\":\"user\"}],\"system\":\"be brief\"}");

        RequestBodyRebuild.Result result = rebuild("{\"messages\":[{\"content\":\"a1\",\"role\":\"assistant\"},"
                + "{\"content\":\"u2\",\"role\":\"user\"}]}", ROOT, null);

        assertRebuilt(result, "{\"messages\":[{\"content\":\"u1\",\"role\":\"user\"},{\"content\":\"a1\",\"role\":\"assistant\"},"
                + "{\"content\":\"u2\",\"role\":\"user\"}],\"system\":\"be brief\"}");
    }

    @Test
    void geminiBodyKeepsItsSystemInstructionAndContents() {
        root("{\"contents\":[{\"parts\":[{\"text\":\"q1\"}],\"role\":\"user\"}],\"system_instruction\":{\"parts\":[{\"text\":\"s\"}]}}");

        RequestBodyRebuild.Result result = rebuild("{\"contents\":[{\"parts\":[{\"text\":\"r1\"}],\"role\":\"model\"}]}", ROOT, null);

        assertRebuilt(result, "{\"contents\":[{\"parts\":[{\"text\":\"q1\"}],\"role\":\"user\"},{\"parts\":[{\"text\":\"r1\"}],\"role\":\"model\"}],"
                + "\"system_instruction\":{\"parts\":[{\"text\":\"s\"}]}}");
    }

    @Test
    void anInputMessageArrayIsRebuiltUnderInput() {
        root("{\"input\":[{\"content\":\"u1\",\"role\":\"user\"}],\"instructions\":\"i\"}");

        RequestBodyRebuild.Result result = rebuild("{\"input\":[{\"content\":\"u2\",\"role\":\"user\"}]}", ROOT, null);

        assertRebuilt(result, "{\"input\":[{\"content\":\"u1\",\"role\":\"user\"},{\"content\":\"u2\",\"role\":\"user\"}],\"instructions\":\"i\"}");
    }

    @Test
    void toolsAreInheritedFromTheRoot() {
        root("{\"messages\":[{\"content\":\"u1\",\"role\":\"user\"}],\"tools\":[{\"name\":\"t\"}]}");

        RequestBodyRebuild.Result result = rebuild("{\"messages\":[{\"content\":\"u2\",\"role\":\"user\"}]}", ROOT, null);

        assertRebuilt(result, "{\"messages\":[{\"content\":\"u1\",\"role\":\"user\"},{\"content\":\"u2\",\"role\":\"user\"}],\"tools\":[{\"name\":\"t\"}]}");
    }

    @Test
    void anExplicitNullToolsMemberIsInheritedAsNull() {
        root("{\"messages\":[{\"content\":\"u1\",\"role\":\"user\"}],\"tools\":null}");

        RequestBodyRebuild.Result result = rebuild("{\"messages\":[{\"content\":\"u2\",\"role\":\"user\"}]}", ROOT, null);

        assertRebuilt(result, "{\"messages\":[{\"content\":\"u1\",\"role\":\"user\"},{\"content\":\"u2\",\"role\":\"user\"}],\"tools\":null}");
    }

    @Test
    void rootWithMarkersGetsThemBackWithoutReadingAnything() {
        RequestBodyRebuild.Result result = rebuild("{\"messages\":[{\"content\":\"u1\",\"role\":\"user\"}]}",
                RequestBodyRebuild.NO_PARENT, "[{\"at\":\"\",\"i\":0,\"v\":{\"type\":\"ephemeral\"}}]");

        assertRebuilt(result, "{\"messages\":[{\"cache_control\":{\"type\":\"ephemeral\"},\"content\":\"u1\",\"role\":\"user\"}]}");
    }

    @Test
    void childRestoresItsOwnMarkersOnly() {
        // The root's markers are its own: a later turn moved the marker, so they are not inherited.
        root("{\"messages\":[{\"content\":\"u1\",\"role\":\"user\"}]}");

        RequestBodyRebuild.Result result = rebuild("{\"messages\":[{\"content\":[{\"text\":\"u2\",\"type\":\"text\"}],\"role\":\"user\"}]}",
                ROOT, "[{\"at\":\"/content/0\",\"i\":1,\"v\":{\"type\":\"ephemeral\"}}]");

        assertRebuilt(result, "{\"messages\":[{\"content\":\"u1\",\"role\":\"user\"},"
                + "{\"content\":[{\"cache_control\":{\"type\":\"ephemeral\"},\"text\":\"u2\",\"type\":\"text\"}],\"role\":\"user\"}]}");
    }

    @Test
    void missingAncestorReturnsTheStoredTextAsPartial() {
        String body = "{\"messages\":[{\"content\":\"u2\",\"role\":\"user\"}]}";
        child(MIDDLE, MISSING, "{\"messages\":[{\"content\":\"a1\",\"role\":\"assistant\"}]}");

        RequestBodyRebuild.Result result = rebuild(body, MIDDLE, null);

        assertThat(result.outcome()).isEqualTo(RequestBodyRebuild.Outcome.PARTIAL);
        assertThat(result.reason()).isEqualTo(RequestBodyRebuild.Reason.MISSING);
        assertThat(result.body()).isSameAs(body);
    }

    @Test
    void rootThatIsNotChatRequestIsInvalid() {
        root("{\"prompt\":\"not a chat request\"}");
        String body = "{\"messages\":[{\"content\":\"u2\",\"role\":\"user\"}]}";

        RequestBodyRebuild.Result result = rebuild(body, ROOT, null);

        assertThat(result.outcome()).isEqualTo(RequestBodyRebuild.Outcome.PARTIAL);
        assertThat(result.reason()).isEqualTo(RequestBodyRebuild.Reason.INVALID);
        assertThat(result.body()).isSameAs(body);
    }

    @Test
    void markerOutsideTheHistoryIsInvalid() {
        root("{\"messages\":[{\"content\":\"u1\",\"role\":\"user\"}]}");
        String body = "{\"messages\":[{\"content\":\"u2\",\"role\":\"user\"}]}";

        RequestBodyRebuild.Result result = rebuild(body, ROOT, "[{\"at\":\"\",\"i\":7,\"v\":{\"type\":\"ephemeral\"}}]");

        assertThat(result.outcome()).isEqualTo(RequestBodyRebuild.Outcome.PARTIAL);
        assertThat(result.reason()).isEqualTo(RequestBodyRebuild.Reason.INVALID);
        assertThat(result.body()).isSameAs(body);
    }

    @Test
    void decimalsAndLoneSurrogatesSurvive() {
        root("{\"messages\":[{\"content\":\"cut \\ud83d\",\"role\":\"user\"}],\"temperature\":0.10}");

        RequestBodyRebuild.Result result = rebuild("{\"messages\":[{\"content\":\"u2\",\"role\":\"user\"}],\"temperature\":0.10}", ROOT, null);

        assertThat(result.outcome()).isEqualTo(RequestBodyRebuild.Outcome.REBUILT);
        assertThat(result.body()).contains("\"temperature\":0.10").contains("cut \\ud83d");
        assertThat(Rebuild.MAPPER.readTree(result.body()).get("messages")).hasSize(2);
    }

    @Test
    void bodyStoredWholeIsReturnedByteForByte() {
        // Not canonical on purpose: a stored-whole body is handed back as stored, never re-serialized.
        String body = "{ \"model\": \"m\",  \"messages\": [] }";

        RequestBodyRebuild.Result result = rebuild(body, RequestBodyRebuild.NO_PARENT, null);

        assertThat(result.outcome()).isEqualTo(RequestBodyRebuild.Outcome.STORED);
        assertThat(result.body()).isSameAs(body);
    }

    @Test
    void nullBodyStaysNull() {
        assertThat(rebuild(null, ROOT, null).body()).isNull();
    }

    private void root(String body) {
        stored.put(ROOT, new RequestBodyRebuild.Ancestor("e-root", ROOT, RequestBodyRebuild.NO_PARENT, body));
    }

    private void child(UUID path, UUID parent, String body) {
        stored.put(path, new RequestBodyRebuild.Ancestor("e-" + path, path, parent, body));
    }

    private RequestBodyRebuild.Result rebuild(String body, UUID parent, String markers) {
        return RequestBodyRebuild.rebuild(body, parent, markers, stored);
    }

    private static void assertRebuilt(RequestBodyRebuild.Result result, String expected) {
        assertThat(result.outcome()).isEqualTo(RequestBodyRebuild.Outcome.REBUILT);
        assertThat(result.reason()).isNull();
        assertThat(result.body()).isEqualTo(expected);
    }
}
