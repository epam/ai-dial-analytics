package com.epam.aidial.analytics.service.table.grant;

import com.epam.aidial.analytics.configuration.JsonMapperConfiguration;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link ColumnPolicyCodec}: deterministic output, and decoded values independent of how the stored text is spelled. */
class ColumnPolicyCodecTest {

    private final ColumnPolicyCodec codec = new ColumnPolicyCodec(new JsonMapperConfiguration().jsonMapper());

    @Test
    void writesRolesAndNamesSortedAndDeduplicated() {
        final Map<String, List<String>> policies = new LinkedHashMap<>();
        policies.put("z", List.of("b", "a", "b"));
        policies.put("a", List.of("c"));

        assertThat(codec.write(policies)).isEqualTo("{\"a\":[\"c\"],\"z\":[\"a\",\"b\"]}");
    }

    @Test
    void readsReserialisedTextToTheSameValue() {
        assertThat(codec.read("{\"z\": [\"b\", \"a\"], \"a\": [\"c\"]}"))
                .isEqualTo(codec.read(codec.write(Map.of("a", List.of("c"), "z", List.of("a", "b")))))
                .containsExactly(Map.entry("a", List.of("c")), Map.entry("z", List.of("a", "b")));
    }

    @Test
    void blankAndEmptyDocumentsReadAsNoPolicies() {
        assertThat(codec.read(null)).isEmpty();
        assertThat(codec.read(" ")).isEmpty();
        assertThat(codec.read("{}")).isEmpty();
    }

    @Test
    void unreadableDocumentIsServerError() {
        assertThatThrownBy(() -> codec.read("{not json")).isInstanceOf(IllegalStateException.class);
    }
}
