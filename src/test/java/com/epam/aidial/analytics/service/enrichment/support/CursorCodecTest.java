package com.epam.aidial.analytics.service.enrichment.support;

import com.epam.aidial.analytics.configuration.CursorProperties;
import com.epam.aidial.analytics.exception.InvalidCursorException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies cursor round-trip, tamper/cross-entity rejection, and HMAC key rotation. */
class CursorCodecTest {

    private static CursorCodec codec(String activeKeyId, CursorProperties.Key... keys) {
        return new CursorCodec(new CursorProperties(activeKeyId, List.of(keys)));
    }

    private static final CursorProperties.Key K1 = new CursorProperties.Key("k1", "secret-one");
    private static final CursorProperties.Key K2 = new CursorProperties.Key("k2", "secret-two");

    @Test
    void roundTripsEntityAndValues() {
        CursorCodec codec = codec("k1", K1);
        String token = codec.encode("dial_usage_log", List.of("1700000000000", "abc-uuid"));

        CursorCodec.Cursor decoded = codec.decode(token, "dial_usage_log");

        assertThat(decoded.entity()).isEqualTo("dial_usage_log");
        assertThat(decoded.values()).containsExactly("1700000000000", "abc-uuid");
    }

    @Test
    void rejectsTamperedToken() {
        CursorCodec codec = codec("k1", K1);
        String token = codec.encode("dial_usage_log", List.of("1", "x"));
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("A") ? "B" : "A");

        assertThatThrownBy(() -> codec.decode(tampered, "dial_usage_log"))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void rejectsCrossEntityCursor() {
        CursorCodec codec = codec("k1", K1);
        String token = codec.encode("dial_usage_log", List.of("1", "x"));

        assertThatThrownBy(() -> codec.decode(token, "other_source"))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void keyRotationKeepsPriorCursorsValid() {
        // Issued under k1 (active), then k2 becomes active while k1 stays in the keyring.
        String token = codec("k1", K1).encode("dial_usage_log", List.of("1", "x"));
        CursorCodec rotated = codec("k2", K1, K2);

        CursorCodec.Cursor decoded = rotated.decode(token, "dial_usage_log");
        assertThat(decoded.values()).containsExactly("1", "x");

        // A fresh cursor signs under the new active key and still verifies.
        String fresh = rotated.encode("dial_usage_log", List.of("2", "y"));
        assertThat(rotated.decode(fresh, "dial_usage_log").values()).containsExactly("2", "y");
    }

    @Test
    void rejectsRetiredKey() {
        String token = codec("k1", K1).encode("dial_usage_log", List.of("1", "x"));
        // Keyring no longer contains k1 (retired).
        CursorCodec afterRetirement = codec("k2", K2);

        assertThatThrownBy(() -> afterRetirement.decode(token, "dial_usage_log"))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void rejectsMalformedToken() {
        CursorCodec codec = codec("k1", K1);
        assertThatThrownBy(() -> codec.decode("not-a-cursor", "dial_usage_log"))
                .isInstanceOf(InvalidCursorException.class);
    }
}
