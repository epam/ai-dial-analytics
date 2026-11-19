package com.epam.aidial.analytics.sync.mapping;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shape of {@code sync.sensitive-columns}: its keywords, its bare and qualified entries, and the startup failure on
 * the removed boolean {@code sync.sensitive-columns-enabled}.
 */
class SensitiveColumnPolicyTest {

    @Test
    void unsetAndStarWriteEverything() {
        assertThat(SensitiveColumnPolicy.parse(null)).isSameAs(SensitiveColumnPolicy.ALL);
        assertThat(parse("*")).isSameAs(SensitiveColumnPolicy.ALL);
        assertThat(parse(" * ", "*")).isSameAs(SensitiveColumnPolicy.ALL);
        assertThat(SensitiveColumnPolicy.ALL.writes("any_table", "any_column")).isTrue();
    }

    @Test
    void noneAndEmptyWriteNothing() {
        assertThat(parse("none")).isSameAs(SensitiveColumnPolicy.NONE);
        assertThat(parse()).isSameAs(SensitiveColumnPolicy.NONE);
        assertThat(parse("", "  ")).isSameAs(SensitiveColumnPolicy.NONE);
        assertThat(SensitiveColumnPolicy.NONE.writes("dial_usage_log_payload", "request_body")).isFalse();
    }

    @Test
    void bareEntryNamesTheColumnOnEveryTable() {
        SensitiveColumnPolicy policy = parse(" request_tags ", "jwt_claims", "request_tags", "");

        assertThat(policy.entries()).extracting(SensitiveColumnPolicy.Entry::toString).containsExactly("request_tags", "jwt_claims");
        assertThat(policy.writes("dial_usage_log_payload", "request_tags")).isTrue();
        assertThat(policy.writes("custom_sink", "request_tags")).isTrue();
        assertThat(policy.writes("dial_usage_log_payload", "request_body")).isFalse();
    }

    @Test
    void qualifiedEntryNamesTheColumnOnItsTableOnly() {
        SensitiveColumnPolicy policy = parse("rate_analytics.comment");

        assertThat(policy.writes("rate_analytics", "comment")).isTrue();
        assertThat(policy.writes("dial_usage_log_payload", "comment")).isFalse();
    }

    @Test
    void matchingIsCaseSensitive() {
        assertThat(parse("Request_Tags").writes("dial_usage_log_payload", "request_tags")).isFalse();
    }

    @Test
    void keywordMixedWithEntriesIsRefused() {
        for (List<String> value : List.of(List.of("*", "request_body"), List.of("none", "request_tags"), List.of("*", "none"))) {
            assertThatThrownBy(() -> SensitiveColumnPolicy.parse(value)).as(value.toString())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sync.sensitive-columns");
        }
    }

    @Test
    void entryWithAnEmptyPartIsRefused() {
        for (String entry : List.of(".comment", "rate_analytics.", "a.b.c")) {
            assertThatThrownBy(() -> parse(entry)).as(entry)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sync.sensitive-columns").hasMessageContaining(entry);
        }
    }

    @Test
    void removedBooleanSettingFailsWhateverItsValue() {
        for (String value : List.of("false", "true", "")) {
            MockEnvironment environment = new MockEnvironment();
            environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("env",
                    Map.of("SYNC_SENSITIVE_COLUMNS_ENABLED", value)));
            assertThatThrownBy(() -> SensitiveColumnPolicy.rejectRemovedSetting(environment)).as(value)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("sync.sensitive-columns-enabled").hasMessageContaining("sync.sensitive-columns (");
        }
    }

    /** A mock environment, not the JVM's: a shell that still exports the removed variable must not fail this test. */
    @Test
    void removedSettingUnsetPasses() {
        MockEnvironment environment = new MockEnvironment().withProperty("sync.sensitive-columns", "none");

        assertThatCode(() -> SensitiveColumnPolicy.rejectRemovedSetting(environment)).doesNotThrowAnyException();
    }

    private static SensitiveColumnPolicy parse(String... entries) {
        return SensitiveColumnPolicy.parse(Arrays.asList(entries));
    }
}
