package com.epam.aidial.analytics.sync.inline;

import com.epam.aidial.analytics.sync.inline.InlineFileStripper.Result;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

import static com.epam.aidial.analytics.sync.inline.Api.ANTHROPIC_MESSAGES;
import static com.epam.aidial.analytics.sync.inline.Api.CHAT_COMPLETIONS;
import static com.epam.aidial.analytics.sync.inline.Api.EMBEDDINGS;
import static com.epam.aidial.analytics.sync.inline.Api.MCP;
import static com.epam.aidial.analytics.sync.inline.Api.RESPONSES;
import static com.epam.aidial.analytics.sync.inline.BodySide.REQUEST;
import static com.epam.aidial.analytics.sync.inline.BodySide.RESPONSE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The data-sync spec's "Inline files are removed from payload bodies in drop mode" requirement, exercised on
 * bodies shaped like the real ones: every location of every API and side, each condition, the body formats, the
 * truncation rule, and the guarantee that nothing but a file payload ever changes.
 */
class InlineFileStripperTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String B64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";

    private static Result strip(String body, Api api, BodySide side) {
        return InlineFileStripper.strip(body, api, side);
    }

    private static int copiesOf(String text) {
        int count = 0;
        for (int i = text.indexOf(B64); i >= 0; i = text.indexOf(B64, i + B64.length())) {
            count++;
        }
        return count;
    }

    /** Every stripped JSON body must still parse: removal never breaks the document. */
    private static String stripValid(String body, Api api, BodySide side) {
        String out = strip(body, api, side).body();
        assertThatCode(() -> JSON.readTree(out)).doesNotThrowAnyException();
        return out;
    }

    @Nested
    class Locations {

        @Test
        void chatImageDataUrlKeepsItsPrefix() {
            String body = """
                    {"messages":[{"role":"user","content":[{"type":"text","text":"hi"},{"type":"image_url","image_url":{"url":"data:image/png;base64,%s"}}]}]}"""
                    .formatted(B64);
            Result result = strip(body, CHAT_COMPLETIONS, REQUEST);

            assertThat(result.body()).isEqualTo(body.replace(B64, ""));
            assertThat(result.body()).contains("\"url\":\"data:image/png;base64,\"");
            assertThat(result.filesRemoved()).isEqualTo(1);
            assertThat(result.bytesRemoved()).isEqualTo(B64.length());
        }

        @Test
        void chatImageHttpsUrlIsKept() {
            String body = "{\"messages\":[{\"content\":[{\"image_url\":{\"url\":\"https://example.com/a.png\"}}]}]}";
            assertThat(strip(body, CHAT_COMPLETIONS, REQUEST).body()).isSameAs(body);
        }

        @Test
        void chatAudioAndFileAreRemoved() {
            String body = """
                    {"messages":[{"content":[{"input_audio":{"data":"%s","format":"wav"}},{"file":{"file_data":"data:application/pdf;base64,%s","filename":"a.pdf"}}]}]}"""
                    .formatted(B64, B64);
            String out = stripValid(body, CHAT_COMPLETIONS, REQUEST);

            assertThat(out).contains("\"data\":\"\",\"format\":\"wav\"").contains("\"file_data\":\"data:application/pdf;base64,\"");
            assertThat(out).doesNotContain(B64).contains("\"filename\":\"a.pdf\"");
        }

        @Test
        void dialAttachmentOfTextTypeIsKept() {
            String body = """
                    {"messages":[{"custom_content":{"attachments":[{"type":"text/markdown","data":"%s"}]}}]}""".formatted(B64);
            assertThat(strip(body, CHAT_COMPLETIONS, REQUEST).body()).isSameAs(body);
        }

        @Test
        void dialAttachmentOfBinaryTypeIsRemovedEvenWhenTheTypeFollowsTheData() {
            String body = """
                    {"messages":[{"custom_content":{"attachments":[{"title":"r.pdf","data":"%s","type":"application/pdf"}]}}]}""".formatted(B64);
            assertThat(stripValid(body, CHAT_COMPLETIONS, REQUEST)).isEqualTo(body.replace(B64, ""));
        }

        @Test
        void dialAttachmentDataUrlWithoutTypeIsRemoved() {
            String body = """
                    {"messages":[{"custom_content":{"stages":[{"attachments":[{"data":"data:image/jpeg;base64,%s"}]}]}}]}""".formatted(B64);
            assertThat(stripValid(body, CHAT_COMPLETIONS, REQUEST)).contains("\"data\":\"data:image/jpeg;base64,\"");
        }

        @Test
        void dialAttachmentDataUrlUnderTextTypeIsKept() {
            String body = """
                    {"messages":[{"custom_content":{"attachments":[{"type":"text/plain","data":"data:image/png;base64,%s"}]}}]}""".formatted(B64);
            assertThat(strip(body, CHAT_COMPLETIONS, REQUEST).body()).isSameAs(body);
        }

        @Test
        void svgIsTextNotFile() {
            String body = """
                    {"messages":[{"custom_content":{"attachments":[{"type":"image/svg+xml","data":"%s"}]}}]}""".formatted(B64);
            assertThat(strip(body, CHAT_COMPLETIONS, REQUEST).body()).isSameAs(body);
        }

        @Test
        void chatResponseAudioAndAttachmentsAreRemoved() {
            String body = """
                    {"choices":[{"message":{"audio":{"id":"a1","data":"%s"},"custom_content":{"attachments":[{"type":"image/png","data":"%s"}]}}}]}"""
                    .formatted(B64, B64);
            Result result = strip(body, CHAT_COMPLETIONS, RESPONSE);
            assertThat(result.body()).isEqualTo(body.replace(B64, ""));
            assertThat(result.filesRemoved()).isEqualTo(2);
        }

        @Test
        void responsesInputImageAndGeneratedImageAreRemoved() {
            String request = """
                    {"input":[{"role":"user","content":[{"type":"input_image","image_url":"data:image/png;base64,%s"}]},{"type":"image_generation_call","result":"%s"}]}"""
                    .formatted(B64, B64);
            assertThat(stripValid(request, RESPONSES, REQUEST)).doesNotContain(B64).contains("\"image_url\":\"data:image/png;base64,\"");

            String response = "{\"output\":[{\"type\":\"image_generation_call\",\"result\":\"" + B64 + "\"}]}";
            assertThat(stripValid(response, RESPONSES, RESPONSE)).isEqualTo("{\"output\":[{\"type\":\"image_generation_call\",\"result\":\"\"}]}");
        }

        @Test
        void anthropicBase64SourceIsRemovedAndTextSourceKept() {
            String body = """
                    {"messages":[{"role":"user","content":[{"type":"image","source":{"type":"base64","media_type":"image/png","data":"%s"}},\
                    {"type":"document","source":{"type":"text","media_type":"text/plain","data":"%s"}},\
                    {"type":"tool_result","content":[{"type":"image","source":{"data":"%s","type":"base64"}}]}]}]}"""
                    .formatted(B64, B64, B64);
            String out = stripValid(body, ANTHROPIC_MESSAGES, REQUEST);

            assertThat(copiesOf(out)).isEqualTo(1); // exactly the text document's copy is left
            assertThat(out).contains("\"type\":\"text\",\"media_type\":\"text/plain\",\"data\":\"" + B64 + "\"");
        }

        @Test
        void mcpImageAndBinaryResourceAreRemovedAndTextResourceKept() {
            String body = """
                    {"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"image","data":"%s","mimeType":"image/png"},\
                    {"type":"resource","resource":{"uri":"file:///r.pdf","mimeType":"application/pdf","blob":"%s"}},\
                    {"type":"resource","resource":{"uri":"file:///r.txt","mimeType":"text/plain","blob":"%s"}},\
                    {"type":"text","text":"%s"}]}}"""
                    .formatted(B64, B64, B64, B64);
            Result result = strip(body, MCP, RESPONSE);
            JSON.readTree(result.body());

            assertThat(result.filesRemoved()).isEqualTo(2);
            assertThat(copiesOf(result.body())).isEqualTo(2); // the text/plain blob and the text block keep theirs
        }

        @Test
        void base64EmbeddingIsRemovedAndNumberArrayKept() {
            String body = "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":\"" + B64 + "\"},"
                    + "{\"object\":\"embedding\",\"index\":1,\"embedding\":[0.1,-0.2]}]}";
            assertThat(stripValid(body, EMBEDDINGS, RESPONSE)).isEqualTo(body.replace(B64, ""));
        }

        @Test
        void locationOfOneApiIsNotFileInAnother() {
            String responsesShaped = "{\"input\":[{\"type\":\"image_generation_call\",\"result\":\"" + B64 + "\"}]}";
            assertThat(strip(responsesShaped, CHAT_COMPLETIONS, REQUEST).body()).isSameAs(responsesShaped);
            String chatShaped = "{\"messages\":[{\"content\":[{\"image_url\":{\"url\":\"data:image/png;base64," + B64 + "\"}}]}]}";
            assertThat(strip(chatShaped, ANTHROPIC_MESSAGES, REQUEST).body()).isSameAs(chatShaped);
        }

        @Test
        void requestLocationIsNotScannedOnTheResponseSide() {
            String body = "{\"messages\":[{\"content\":[{\"image_url\":{\"url\":\"data:image/png;base64," + B64 + "\"}}]}]}";
            assertThat(strip(body, CHAT_COMPLETIONS, RESPONSE).body()).isSameAs(body);
            assertThat(strip(body, ANTHROPIC_MESSAGES, RESPONSE).body()).isSameAs(body); // no Anthropic response locations at all
        }
    }

    @Nested
    class TextIsNeverRemoved {

        @Test
        void base64InContentToolArgumentsAndUnknownFieldsIsKept() {
            String big = B64.repeat(100);
            String body = """
                    {"messages":[{"role":"user","content":"%s"},{"role":"user","content":[{"type":"text","text":"%s"}]},\
                    {"role":"assistant","tool_calls":[{"function":{"name":"f","arguments":"{\\"data\\":\\"%s\\"}"}}]}],\
                    "data":"%s","url":"data:image/png;base64,%s"}"""
                    .formatted(big, big, big, big, big);
            assertThat(strip(body, CHAT_COMPLETIONS, REQUEST).body()).isSameAs(body);
        }
    }

    @Nested
    class Escapes {

        @Test
        void anEscapedDataUrlPrefixIsRecognized() {
            String body = "{\"messages\":[{\"content\":[{\"image_url\":{\"url\":\"data:image\\/png;base64," + B64 + "\"}}]}]}";
            assertThat(stripValid(body, CHAT_COMPLETIONS, REQUEST)).contains("\"url\":\"data:image\\/png;base64,\"");
        }

        @Test
        void dataUrlParametersAreAccepted() {
            String body = "{\"messages\":[{\"content\":[{\"image_url\":{\"url\":\"data:image/png;name=a.png;BASE64," + B64 + "\"}}]}]}";
            assertThat(stripValid(body, CHAT_COMPLETIONS, REQUEST)).contains("\"url\":\"data:image/png;name=a.png;BASE64,\"");
        }

        @Test
        void nonBase64DataUrlIsKept() {
            String body = "{\"messages\":[{\"content\":[{\"image_url\":{\"url\":\"data:text/plain,hello;base64,x\"}}]}]}";
            assertThat(strip(body, CHAT_COMPLETIONS, REQUEST).body()).isSameAs(body);
        }

        @Test
        void escapedQuotesAndSlashesInsideAndBeforeValueKeepTheSpansExact() {
            String body = "{\"messages\":[{\"content\":[{\"type\":\"text\",\"text\":\"say \\\"hi\\\" \\/ ok\"},"
                    + "{\"input_audio\":{\"data\":\"ab\\/cd\\\"ef\",\"format\":\"mp3\"}}]}]}";
            String out = stripValid(body, CHAT_COMPLETIONS, REQUEST);
            assertThat(out).isEqualTo(body.replace("ab\\/cd\\\"ef", ""));
        }

        @Test
        void nonAsciiTextBeforeFileKeepsItsCharacters() {
            String body = "{\"messages\":[{\"content\":[{\"type\":\"text\",\"text\":\"Grüße — 日本 😀\"},"
                    + "{\"image_url\":{\"url\":\"data:image/png;base64," + B64 + "\"}}]}]}";
            String out = stripValid(body, CHAT_COMPLETIONS, REQUEST);
            assertThat(out).isEqualTo(body.replace(B64, "")).contains("Grüße — 日本 😀");
        }
    }

    @Nested
    class Sse {

        @Test
        void streamedAttachmentsAreRemovedEventByEventAndFramingKept() {
            String body = "data: {\"choices\":[{\"delta\":{\"content\":\"a\"}}]}\r\n\r\n"
                    + "data:{\"choices\":[{\"delta\":{\"custom_content\":{\"attachments\":[{\"type\":\"image/png\",\"data\":\"" + B64 + "\"}]}}}]}\r\n\r\n"
                    + ": comment\n\n"
                    + "data: [DONE]\n\n";
            Result result = strip(body, CHAT_COMPLETIONS, RESPONSE);

            assertThat(result.body()).isEqualTo(body.replace(B64, ""));
            assertThat(result.filesRemoved()).isEqualTo(1);
            assertThat(result.unscannable()).isZero();
        }

        @Test
        void mcpEventStreamIsScanned() {
            String body = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"result\":{\"content\":[{\"type\":\"image\",\"data\":\""
                    + B64 + "\",\"mimeType\":\"image/png\"}]}}\n\n";
            assertThat(strip(body, MCP, RESPONSE).body()).isEqualTo(body.replace(B64, ""));
        }

        @Test
        void anEventSpreadOverSeveralDataLinesIsLeftAlone() {
            String body = "data: {\"choices\":[{\"delta\":{\"audio\":\n"
                    + "data: {\"data\":\"" + B64 + "\"}}}]}\n\n";
            Result result = strip(body, CHAT_COMPLETIONS, RESPONSE);
            assertThat(result.body()).isSameAs(body);
            assertThat(result.unscannable()).isZero();
        }

        @Test
        void anEventThatDoesNotParseIsCountedAndTheOthersStillStripped() {
            String body = "data: {\"choices\":[{\"delta\":{\"audio\":{\"data\":\"" + B64 + "\"}}}]}\n\n"
                    + "data: {not json\n\n";
            Result result = strip(body, CHAT_COMPLETIONS, RESPONSE);
            assertThat(result.body()).isEqualTo(body.replace(B64, ""));
            assertThat(result.unscannable()).isEqualTo(1);
        }
    }

    @Nested
    class Unscannable {

        @Test
        void plainTextIsStoredUnchangedAndCounted() {
            Result result = strip("Internal Server Error", CHAT_COMPLETIONS, RESPONSE);
            assertThat(result.body()).isEqualTo("Internal Server Error");
            assertThat(result.unscannable()).isEqualTo(1);
        }

        @Test
        void malformedJsonKeepsTheFilesFoundBeforeTheErrorAndIsCounted() {
            String body = "{\"choices\":[{\"message\":{\"audio\":{\"data\":\"" + B64 + "\"}}}]} trailing garbage";
            Result result = strip(body, CHAT_COMPLETIONS, RESPONSE);
            assertThat(result.body()).isEqualTo(body.replace(B64, ""));
            assertThat(result.unscannable()).isEqualTo(1);
        }

        @Test
        void blankAndEmptyBodiesAreUnchanged() {
            assertThat(strip("", CHAT_COMPLETIONS, REQUEST).body()).isEmpty();
            assertThat(strip("   ", CHAT_COMPLETIONS, REQUEST).body()).isEqualTo("   ");
            assertThat(strip(null, CHAT_COMPLETIONS, REQUEST).body()).isNull();
        }
    }

    @Nested
    class Truncation {

        /** Pads {@code prefix} with base64 to exactly 4 MiB of UTF-8, as Core's cut leaves it, then appends the marker. */
        private static String cutInside(String prefix) {
            int bytes = prefix.getBytes(StandardCharsets.UTF_8).length;
            return prefix + "A".repeat(4 * 1024 * 1024 - bytes) + ">>";
        }

        @Test
        void bodyCutInsideFileIsCutBackBeforeIt() {
            String head = "{\"messages\":[{\"content\":[{\"image_url\":{\"url\":\"data:image/png;base64," + B64 + "\"}},"
                    + "{\"type\":\"text\",\"text\":\"look\"},{\"image_url\":{\"url\":\"";
            String body = cutInside(head + "data:image/png;base64,");
            Result result = strip(body, CHAT_COMPLETIONS, REQUEST);

            String expected = head.substring(0, head.length() - 1).replace(B64, "") + ">>";
            assertThat(result.body()).isEqualTo(expected);
            assertThat(result.body()).endsWith("\"image_url\":{\"url\":>>");
            assertThat(result.cutBack()).isTrue();
            assertThat(result.filesRemoved()).isEqualTo(1);
            assertThat(result.unscannable()).isZero();
        }

        @Test
        void bodyCutInsideTextIsLeftAsCutWithEarlierFilesRemoved() {
            String head = "{\"messages\":[{\"content\":[{\"image_url\":{\"url\":\"data:image/png;base64," + B64 + "\"}},"
                    + "{\"type\":\"text\",\"text\":\"";
            String body = cutInside(head);
            Result result = strip(body, CHAT_COMPLETIONS, REQUEST);

            assertThat(result.body()).isEqualTo(body.replace(B64, ""));
            assertThat(result.body()).endsWith("AAAA>>");
            assertThat(result.cutBack()).isFalse();
            assertThat(result.unscannable()).isZero();
        }

        @Test
        void cutInsideDataUrlPrefixIsLeftAsCut() {
            String body = cutInside("{\"messages\":[{\"content\":[{\"image_url\":{\"url\":\"data:image/pn");
            assertThat(strip(body, CHAT_COMPLETIONS, REQUEST).body()).isSameAs(body);
        }

        @Test
        void conditionSiblingAfterTheCutLeavesTheValueAsCut() {
            // The DIAL attachment's type would only come after the data, which the cut removed.
            String body = cutInside("{\"messages\":[{\"custom_content\":{\"attachments\":[{\"data\":\"");
            assertThat(strip(body, CHAT_COMPLETIONS, REQUEST).body()).isSameAs(body);
        }

        @Test
        void openDataUrlAttachmentWhoseTypeFollowsTheCutIsLeftAsCut() {
            // Untruncated, "type":"text/markdown" would follow and keep it: an unread type is unknown, not missing.
            String body = cutInside("{\"messages\":[{\"custom_content\":{\"attachments\":[{\"data\":\"data:text/markdown;base64,");
            assertThat(strip(body, CHAT_COMPLETIONS, REQUEST).body()).isSameAs(body);
        }

        @Test
        void completeDataUrlAttachmentInAnObjectTheCutLeftOpenIsKept() {
            // The attachment's string closed, but its object did not, so its type may still lie after the cut.
            String body = cutInside("{\"messages\":[{\"custom_content\":{\"attachments\":[{\"data\":\"data:text/plain;base64,"
                    + B64 + "\",\"title\":\"");
            assertThat(strip(body, CHAT_COMPLETIONS, REQUEST).body()).isSameAs(body);
        }

        @Test
        void completeDataUrlAttachmentWithoutTypeInClosedObjectIsStillRemoved() {
            String head = "{\"messages\":[{\"custom_content\":{\"attachments\":[{\"data\":\"data:image/png;base64," + B64
                    + "\",\"title\":\"a\"}]},\"content\":\"";
            Result result = strip(cutInside(head), CHAT_COMPLETIONS, REQUEST);
            assertThat(result.filesRemoved()).isEqualTo(1);
            assertThat(copiesOf(result.body())).isZero();
        }

        @Test
        void siblingBeforeTheCutDecidesTheOpenValue() {
            String head = "{\"messages\":[{\"custom_content\":{\"attachments\":[{\"type\":\"application/pdf\",\"data\":\"";
            Result result = strip(cutInside(head), CHAT_COMPLETIONS, REQUEST);
            assertThat(result.body()).isEqualTo(head.substring(0, head.length() - 1) + ">>");
            assertThat(result.cutBack()).isTrue();
        }

        @Test
        void trailingReplacementCharacterStillCountsAsTruncated() {
            // Core decoded a split multi-byte character as U+FFFD, which is 3 bytes: the body is longer, never shorter.
            String head = "{\"messages\":[{\"content\":[{\"type\":\"text\",\"text\":\"";
            int bytes = head.length();
            String body = head + "A".repeat(4 * 1024 * 1024 - bytes - 1) + "�>>";
            assertThat(InlineFileStripper.isTruncated(body)).isTrue();
            assertThat(strip(body, CHAT_COMPLETIONS, REQUEST).body()).isSameAs(body);
        }

        @Test
        void bodyThatEndsWithTheMarkerButIsNotCutIsScannedNormally() {
            String json = "{\"choices\":[{\"message\":{\"audio\":{\"data\":\"" + B64 + "\"}}}]}";
            String body = json + " ".repeat(4 * 1024 * 1024 - json.length() - 2) + ">>"; // 4,194,304 bytes: not over the limit
            assertThat(InlineFileStripper.isTruncated(body)).isFalse();
            Result result = strip(body, CHAT_COMPLETIONS, RESPONSE);
            assertThat(result.filesRemoved()).isEqualTo(1);
            assertThat(result.unscannable()).isEqualTo(1); // the trailing ">>" is not JSON
            assertThat(result.body()).endsWith(">>");
        }

        @Test
        void streamCutInsideFileIsCutBackWithinItsLastEvent() {
            String first = "data: {\"choices\":[{\"delta\":{\"audio\":{\"data\":\"" + B64 + "\"}}}]}\n\n";
            String open = "data: {\"choices\":[{\"delta\":{\"audio\":{\"data\":\"";
            Result result = strip(cutInside(first + open), CHAT_COMPLETIONS, RESPONSE);

            assertThat(result.body()).isEqualTo(first.replace(B64, "") + open.substring(0, open.length() - 1) + ">>");
            assertThat(result.cutBack()).isTrue();
            assertThat(result.unscannable()).isZero();
        }

        @Test
        void theMarkerIsNeverReadAsPartOfTheOpenValue() {
            Result result = strip(cutInside("{\"output\":[{\"result\":\""), RESPONSES, RESPONSE);
            assertThat(result.body()).isEqualTo("{\"output\":[{\"result\":>>");
            assertThat(result.bytesRemoved()).isEqualTo(4 * 1024 * 1024 - "{\"output\":[{\"result\":\"".length());
        }

        @Test
        void dropTruncatedCutsBackTheHalfFileAndKeepsTheCompleteOnes() {
            String head = "{\"messages\":[{\"content\":[{\"image_url\":{\"url\":\"data:image/png;base64," + B64 + "\"}},"
                    + "{\"type\":\"text\",\"text\":\"look\"},{\"image_url\":{\"url\":\"";
            Result result = InlineFileStripper.strip(cutInside(head + "data:image/png;base64,"), CHAT_COMPLETIONS, REQUEST,
                    InlineFilesPolicy.defaults());

            assertThat(result.body()).isEqualTo(head.substring(0, head.length() - 1) + ">>");
            assertThat(copiesOf(result.body())).isEqualTo(1); // the complete image is kept byte for byte
            assertThat(result.cutBack()).isTrue();
            assertThat(result.filesRemoved()).isZero();
            assertThat(result.bytesRemoved()).isEqualTo(4 * 1024 * 1024 - head.length() - "data:image/png;base64,".length());
        }

        @Test
        void dropTruncatedLeavesBodyCutInsideTextAsCut() {
            String body = cutInside("{\"messages\":[{\"content\":[{\"image_url\":{\"url\":\"data:image/png;base64," + B64 + "\"}},"
                    + "{\"type\":\"text\",\"text\":\"");
            assertThat(InlineFileStripper.strip(body, CHAT_COMPLETIONS, REQUEST, InlineFilesPolicy.defaults()).body()).isSameAs(body);
        }

        @Test
        void dropTruncatedKeepsEarlierEventsOfCutStream() {
            String first = "data: {\"choices\":[{\"delta\":{\"audio\":{\"data\":\"" + B64 + "\"}}}]}\n\n";
            String open = "data: {\"choices\":[{\"delta\":{\"audio\":{\"data\":\"";
            Result result = InlineFileStripper.strip(cutInside(first + open), CHAT_COMPLETIONS, RESPONSE, InlineFilesPolicy.defaults());

            assertThat(result.body()).isEqualTo(first + open.substring(0, open.length() - 1) + ">>");
            assertThat(result.cutBack()).isTrue();
        }

        @Test
        void withTheRepairOffTheHalfFileOverTheLimitLosesItsPayload() {
            String head = "{\"messages\":[{\"content\":[{\"image_url\":{\"url\":\"data:image/png;base64,";
            Result result = InlineFileStripper.strip(cutInside(head), CHAT_COMPLETIONS, REQUEST, new InlineFilesPolicy(false, 1024 * 1024));

            assertThat(result.body()).isEqualTo(head + ">>");
            assertThat(result.cutBack()).isTrue();
            assertThat(result.filesRemoved()).isZero();
            assertThat(result.bytesRemoved()).isEqualTo(4 * 1024 * 1024 - head.length());
        }

        @Test
        void withTheRepairOffTheHalfFileWithinTheLimitIsLeftAsCut() {
            String body = cutInside("{\"messages\":[{\"content\":[{\"image_url\":{\"url\":\"data:image/png;base64,");
            Result result = InlineFileStripper.strip(body, CHAT_COMPLETIONS, REQUEST, new InlineFilesPolicy(false, 8 * 1024 * 1024));
            assertThat(result.body()).isSameAs(body);
            assertThat(result.cutBack()).isFalse();
        }

        @Test
        void withTheRepairOnLimitStillKeepsSmallCompleteFiles() {
            String head = "{\"messages\":[{\"content\":[{\"image_url\":{\"url\":\"data:image/png;base64," + B64 + "\"}},"
                    + "{\"image_url\":{\"url\":\"";
            Result result = InlineFileStripper.strip(cutInside(head + "data:image/png;base64,"), CHAT_COMPLETIONS, REQUEST,
                    new InlineFilesPolicy(true, B64.length()));

            assertThat(result.body()).isEqualTo(head.substring(0, head.length() - 1) + ">>");
            assertThat(copiesOf(result.body())).isEqualTo(1);
            assertThat(result.filesRemoved()).isZero();
        }
    }

    @Nested
    class SizeLimit {

        private static String image(String payload) {
            return "{\"image_url\":{\"url\":\"data:image/png;base64," + payload + "\"}}";
        }

        @Test
        void fileOverTheLimitIsRemovedAndOneAtTheLimitKept() {
            String over = "A".repeat(1025);
            String at = "B".repeat(1024);
            String body = "{\"messages\":[{\"content\":[" + image(over) + "," + image(at) + "]}]}";
            Result result = InlineFileStripper.strip(body, CHAT_COMPLETIONS, REQUEST, new InlineFilesPolicy(false, 1024));

            assertThat(result.body()).isEqualTo("{\"messages\":[{\"content\":[" + image("") + "," + image(at) + "]}]}");
            assertThat(result.filesRemoved()).isEqualTo(1);
            assertThat(result.bytesRemoved()).isEqualTo(1025);
        }

        @Test
        void theSizeIsThePayloadAfterTheDataUrlPrefix() {
            // 1,024 payload characters: the prefix would push it over a limit of 1,024 if it were counted.
            String body = "{\"messages\":[{\"content\":[" + image("C".repeat(1024)) + "]}]}";
            assertThat(InlineFileStripper.strip(body, CHAT_COMPLETIONS, REQUEST, new InlineFilesPolicy(false, 1024)).body()).isSameAs(body);
        }

        @Test
        void jsonEscapeCountsWithItsBackslash() {
            String payload = "A".repeat(1022) + "\\/";   // 1,024 characters as stored
            String body = "{\"messages\":[{\"content\":[" + image(payload) + "]}]}";
            assertThat(InlineFileStripper.strip(body, CHAT_COMPLETIONS, REQUEST, new InlineFilesPolicy(false, 1024)).body()).isSameAs(body);
            assertThat(InlineFileStripper.strip(body, CHAT_COMPLETIONS, REQUEST, new InlineFilesPolicy(false, 1023)).filesRemoved()).isEqualTo(1);
        }

        @Test
        void bodyNoLongerThanTheLimitIsNotParsed() {
            // Malformed: a scan would count it as unscannable, so a zero count shows it was never parsed.
            String body = "{\"messages\":[{\"content\":[" + image(B64) + ",oops";
            assertThat(InlineFileStripper.strip(body, CHAT_COMPLETIONS, REQUEST, new InlineFilesPolicy(false, body.length())))
                    .isEqualTo(new Result(body, 0, 0, false, 0));
            assertThat(InlineFileStripper.strip(body, CHAT_COMPLETIONS, REQUEST, new InlineFilesPolicy(false, body.length() - 1)).unscannable())
                    .isEqualTo(1);
        }

        @Test
        void mayChangeOnlyBodyLongerThanTheLimitOrCutUnderTheRepair() {
            assertThat(InlineFileStripper.mayChange("x".repeat(10), new InlineFilesPolicy(false, 10))).isFalse();
            assertThat(InlineFileStripper.mayChange("x".repeat(11), new InlineFilesPolicy(false, 10))).isTrue();
            assertThat(InlineFileStripper.mayChange("x".repeat(11), InlineFilesPolicy.defaults())).isFalse();
        }
    }

    @Nested
    class Policies {

        private final String body = "{\"messages\":[{\"content\":[{\"image_url\":{\"url\":\"data:image/png;base64," + B64 + "\"}}]}]}";

        @Test
        void dropTruncatedReturnsBodyThatWasNotCutUntouched() {
            Result result = InlineFileStripper.strip(body, CHAT_COMPLETIONS, REQUEST, InlineFilesPolicy.defaults());
            assertThat(result.body()).isSameAs(body);
            assertThat(result.filesRemoved()).isZero();
        }

        @Test
        void keepReturnsEveryBodyUntouched() {
            assertThat(InlineFileStripper.strip(body, CHAT_COMPLETIONS, REQUEST, InlineFilesPolicy.keep()).body()).isSameAs(body);
        }

        @Test
        void dropAllIsTheDefaultOfTheThreeArgumentForm() {
            assertThat(InlineFileStripper.strip(body, CHAT_COMPLETIONS, REQUEST))
                    .isEqualTo(InlineFileStripper.strip(body, CHAT_COMPLETIONS, REQUEST, InlineFilesPolicy.dropAll()));
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            CHAT_COMPLETIONS   | REQUEST
            CHAT_COMPLETIONS   | RESPONSE
            ANTHROPIC_MESSAGES | REQUEST
            MCP                | RESPONSE
            """)
    void theOutputIsTheSameOnEveryRun(Api api, BodySide side) {
        String body = "{\"messages\":[{\"content\":[{\"image_url\":{\"url\":\"data:image/png;base64," + B64 + "\"}}]}],"
                + "\"choices\":[{\"message\":{\"audio\":{\"data\":\"" + B64 + "\"}}}],"
                + "\"result\":{\"content\":[{\"type\":\"image\",\"data\":\"" + B64 + "\"}]}}";
        assertThat(strip(body, api, side)).isEqualTo(strip(body, api, side));
    }
}
