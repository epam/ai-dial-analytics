package com.epam.aidial.analytics.sync.delta;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ChatRequestTest {

    @Test
    void openAiMessagesAreTheHistoryAndRootStoresTheWholeRequest() {
        ChatRequest request = parse("{\"model\":\"m\",\"temperature\":0.2,\"tools\":[{\"type\":\"function\"}],"
                + "\"messages\":[{\"role\":\"system\",\"content\":\"s\"},{\"role\":\"user\",\"content\":\"u\"}]}");

        assertThat(request.canonicalHistory()).containsExactly(
                "{\"content\":\"s\",\"role\":\"system\"}", "{\"content\":\"u\",\"role\":\"user\"}");
        assertThat(request.stored(0)).isEqualTo("{\"messages\":[{\"content\":\"s\",\"role\":\"system\"},"
                + "{\"content\":\"u\",\"role\":\"user\"}],\"model\":\"m\",\"temperature\":0.2,\"tools\":[{\"type\":\"function\"}]}");
        assertThat(request.inherited(0)).isEmpty();
        assertThat(request.markers()).isEmpty();
        assertThat(request.tools()).isEqualTo("[{\"type\":\"function\"}]");
    }

    @Test
    void anthropicTopLevelSystemOpensTheHistory() {
        ChatRequest request = parse("{\"system\":[{\"type\":\"text\",\"text\":\"s\"}],\"messages\":[{\"role\":\"user\",\"content\":\"u\"}]}");

        assertThat(request.canonicalHistory()).containsExactly(
                "[{\"text\":\"s\",\"type\":\"text\"}]", "{\"content\":\"u\",\"role\":\"user\"}");
        assertThat(request.systemCount()).isEqualTo(1);
        assertThat(request.stored(0)).isEqualTo(
                "{\"messages\":[{\"content\":\"u\",\"role\":\"user\"}],\"system\":[{\"text\":\"s\",\"type\":\"text\"}]}");
    }

    /** A child leaves out the system keys and the tools its parent supplies, and names them. */
    @Test
    void childLeavesOutWhatItsParentSupplies() {
        ChatRequest request = parse("{\"system\":\"s\",\"tools\":[{\"name\":\"t\"}],\"model\":\"m\",\"messages\":["
                + "{\"role\":\"user\",\"content\":\"u1\"},{\"role\":\"assistant\",\"content\":\"a1\"},{\"role\":\"user\",\"content\":\"u2\"}]}");

        assertThat(request.stored(2)).isEqualTo(
                "{\"messages\":[{\"content\":\"a1\",\"role\":\"assistant\"},{\"content\":\"u2\",\"role\":\"user\"}],\"model\":\"m\"}");
        assertThat(request.inherited(2)).containsExactly("system", "tools");
        assertThat(parse("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"u1\"},{\"role\":\"user\",\"content\":\"u2\"}]}")
                .inherited(1)).isEmpty();
    }

    /** null, an empty array and absent tools are three different requests; key order does not matter. */
    @Test
    void toolsTellNullEmptyAndAbsentApart() {
        String messages = "\"messages\":[{\"role\":\"user\",\"content\":\"u\"}]";

        assertThat(parse("{" + messages + "}").tools()).isNull();
        assertThat(parse("{" + messages + ",\"tools\":null}").tools()).isEqualTo("null");
        assertThat(parse("{" + messages + ",\"tools\":[]}").tools()).isEqualTo("[]");
        assertThat(parse("{" + messages + ",\"tools\":[{\"b\":1,\"a\":2}]}").tools())
                .isEqualTo(parse("{\"tools\":[{\"a\":2,\"b\":1}]," + messages + "}").tools());
    }

    @Test
    void responsesInstructionsAndInputArray() {
        ChatRequest request = parse("{\"instructions\":\"be brief\",\"input\":[{\"role\":\"user\",\"content\":\"u\"}],\"model\":\"m\"}");

        assertThat(request.canonicalHistory()).containsExactly("\"be brief\"", "{\"content\":\"u\",\"role\":\"user\"}");
        assertThat(request.stored(0)).contains("\"input\":[", "\"instructions\":\"be brief\"", "\"model\":\"m\"");
    }

    @Test
    void geminiSystemInstructionAndContents() {
        ChatRequest request = parse("{\"system_instruction\":{\"parts\":[{\"text\":\"s\"}]},\"contents\":[{\"role\":\"user\","
                + "\"parts\":[{\"text\":\"u\"}]}],\"generationConfig\":{\"temperature\":1}}");

        assertThat(request.canonicalHistory()).hasSize(2).first().isEqualTo("{\"parts\":[{\"text\":\"s\"}]}");
        assertThat(request.stored(1)).contains("\"contents\":[", "\"generationConfig\"").doesNotContain("\"system_instruction\"");
        assertThat(request.inherited(1)).containsExactly("system_instruction");
    }

    @Test
    void inputStringIsNotMessageArray() {
        assertThat(parse("{\"model\":\"text-embedding\",\"input\":\"x\"}")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"id\":1}",
        "{\"messages\":[{\"role\":\"user\",\"content\":\"cut",
        "[1,2,3]",
        "\"text\"",
        "{\"model\":\"text-embedding\",\"input\":[\"chunk a\",\"chunk b\"]}",
        "{\"model\":\"text-embedding\",\"input\":[[1,2,3],[4,5]]}",
        "not json",
        ""
    })
    void anythingElseIsNotChatRequest(String body) {
        assertThat(parse(body)).isNull();
    }

    @Test
    void cacheControlIsRemovedFromTheHistoryAndRecordedWithItsLocation() {
        ChatRequest request = parse("{\"system\":[{\"type\":\"text\",\"text\":\"s\",\"cache_control\":{\"type\":\"ephemeral\"}}],"
                + "\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"a/b\",\"cache_control\":{\"type\":\"ephemeral\",\"ttl\":\"1h\"}}]}],"
                + "\"tools\":[{\"name\":\"t\",\"cache_control\":{\"type\":\"ephemeral\"}}]}");

        assertThat(request.canonicalHistory()).noneMatch(element -> element.contains("cache_control"));
        assertThat(request.markers()).extracting(ChatRequest.Marker::element, ChatRequest.Marker::at)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(0, "/0"), org.assertj.core.groups.Tuple.tuple(1, "/content/0"));
        assertThat(request.markers().get(1).value().toString()).isEqualTo("{\"type\":\"ephemeral\",\"ttl\":\"1h\"}");
        // Outside the history a marker stays where it is.
        assertThat(request.stored(0)).contains("\"tools\":[{\"cache_control\":{\"type\":\"ephemeral\"},\"name\":\"t\"}]")
                .doesNotContain("\"ttl\"");
    }

    @Test
    void canonicalFormIgnoresKeyOrderAndWhitespaceAndKeepsNumbersExact() {
        ChatRequest first = parse("{\"messages\":[{\"role\":\"user\",\"n\":0.10000000000000000001,\"content\":\"x\"}]}");
        ChatRequest second = parse("{ \"messages\" : [ { \"content\" : \"x\", \"n\":0.10000000000000000001, \"role\" : \"user\" } ] }");

        assertThat(first.canonicalHistory()).isEqualTo(second.canonicalHistory());
        assertThat(first.canonicalHistory().getFirst()).contains("0.10000000000000000001");
    }

    @Test
    void memberNameWithPointerCharacterIsEscapedInTheMarkerLocation() {
        ChatRequest request = parse("{\"messages\":[{\"a/b~c\":{\"cache_control\":{\"type\":\"ephemeral\"}}}]}");

        assertThat(request.markers()).singleElement().extracting(ChatRequest.Marker::at).isEqualTo("/a~1b~0c");
    }

    /** A lone surrogate comes back as escape text, which ClickHouse stores as it is; a pair stays a raw character. */
    @Test
    void loneSurrogatesAreRenderedAsEscapes() {
        ChatRequest request = parse("{\"messages\":[{\"role\":\"user\",\"content\":\"a\\ud83d b\\udc00 c\\ud83d\\ude00\"}]}");

        assertThat(request.canonicalHistory().getFirst())
                .isEqualTo("{\"content\":\"a\\ud83d b\\udc00 c" + new String(Character.toChars(0x1F600)) + "\",\"role\":\"user\"}");
    }

    @Test
    void bodyWithDuplicateMemberIsNotSplit() {
        String body = "{\"messages\":[{\"role\":\"user\",\"content\":\"a\"}],\"messages\":[{\"role\":\"user\",\"content\":\"b\"}]}";

        assertThat(ChatRequest.parse(tools.jackson.databind.json.JsonMapper.builder()
                .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build(), body)).isNull();
    }

    private static ChatRequest parse(String body) {
        return ChatRequest.parse(Rebuild.MAPPER, body);
    }
}
