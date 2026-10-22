package com.epam.aidial.analytics.sync.inline;

import com.epam.aidial.analytics.sync.inline.InlineFileLocator.Scan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the parser behaviour the truncation cut-back depends on (design D4): Jackson 3 returns {@code VALUE_STRING}
 * for a string before reading it, so for a document that ends inside a string the open value's start offset and
 * path are known before the parse error is thrown. Were that to change, the cut-back would silently stop
 * finding the open file — this test is what says so.
 */
class InlineFileLocatorTest {

    private static final List<InlineFileLocations.Location> CHAT_REQUEST = InlineFileLocations.of(Api.CHAT_COMPLETIONS, BodySide.REQUEST);

    @Test
    void stringOpenAtTheEndIsReportedWithItsStartAndPath() {
        String head = "{\"messages\":[{\"content\":[{\"image_url\":{\"url\":";
        String text = head + "\"data:image/png;base64," + "A".repeat(200_000);

        Scan scan = InlineFileLocator.scan(text, 0, text.length(), CHAT_REQUEST);

        assertThat(scan.parseError()).isTrue();
        assertThat(scan.open()).isNotNull();
        assertThat(scan.open().quote()).isEqualTo(head.length());
        assertThat(scan.open().payloadStart()).isEqualTo(head.length() + 1 + "data:image/png;base64,".length());
        assertThat(scan.files()).isEmpty();
    }

    @Test
    void offsetsOfDocumentInsideLargerTextAreIndexesIntoThatText() {
        String event = "{\"messages\":[{\"content\":[{\"input_audio\":{\"data\":\"QUJD\"}}]}]}";
        String text = "data: " + event + "\n\n";

        Scan scan = InlineFileLocator.scan(text, 6, 6 + event.length(), CHAT_REQUEST);

        assertThat(scan.files()).singleElement().satisfies(span -> {
            assertThat(text.charAt(span.quote())).isEqualTo('"');
            assertThat(text.substring(span.payloadStart(), span.end())).isEqualTo("QUJD");
        });
        assertThat(scan.parseError()).isFalse();
    }

    @Test
    void closingQuoteSkipsEscapes() {
        assertThat(InlineFileLocator.closingQuote("a\\\"b\\\\\"c", 0)).isEqualTo(6);
        assertThat(InlineFileLocator.closingQuote("abc\\", 0)).isEqualTo(-1);
    }
}
