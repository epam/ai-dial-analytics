package com.epam.aidial.analytics.sync.mapping;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link RecordMatcher}: each operator, AND semantics across conditions, a missing source
 * path never matching, and match-all when no conditions are declared (the single-target default).
 */
class RecordMatcherTest {

    private final JsonMapper json = JsonMapper.builder().build();

    private JsonNode record(String rawJson) {
        return json.readTree(rawJson);
    }

    private RecordMatcher.Condition condition(String path, RecordMatcher.Op op, String value) {
        return new RecordMatcher.Condition(JsonPaths.compile(path), op, value);
    }

    @Test
    void matchAllAcceptsEveryRecord() {
        assertThat(RecordMatcher.matchAll().matches(record("{\"anything\":1}"))).isTrue();
        assertThat(new RecordMatcher(List.of()).matches(record("{}"))).isTrue();
    }

    @Test
    void endsWithEqualsAndContainsOperators() {
        JsonNode rec = record("{\"request\":{\"uri\":\"/v1/gpt-4o/rate\",\"method\":\"POST\"}}");

        assertThat(new RecordMatcher(List.of(
                condition("request.uri", RecordMatcher.Op.ENDS_WITH, "/rate"))).matches(rec)).isTrue();
        assertThat(new RecordMatcher(List.of(
                condition("request.method", RecordMatcher.Op.EQUALS, "POST"))).matches(rec)).isTrue();
        assertThat(new RecordMatcher(List.of(
                condition("request.uri", RecordMatcher.Op.CONTAINS, "gpt-4o"))).matches(rec)).isTrue();

        assertThat(new RecordMatcher(List.of(
                condition("request.uri", RecordMatcher.Op.ENDS_WITH, "/chat"))).matches(rec)).isFalse();
        assertThat(new RecordMatcher(List.of(
                condition("request.method", RecordMatcher.Op.EQUALS, "GET"))).matches(rec)).isFalse();
    }

    @Test
    void conditionsAreAndedTogether() {
        RecordMatcher matcher = new RecordMatcher(List.of(
                condition("request.uri", RecordMatcher.Op.ENDS_WITH, "/rate"),
                condition("request.method", RecordMatcher.Op.EQUALS, "POST")));

        assertThat(matcher.matches(record("{\"request\":{\"uri\":\"/x/rate\",\"method\":\"POST\"}}"))).isTrue();
        // Second condition fails -> whole matcher fails.
        assertThat(matcher.matches(record("{\"request\":{\"uri\":\"/x/rate\",\"method\":\"GET\"}}"))).isFalse();
        // First condition fails -> whole matcher fails.
        assertThat(matcher.matches(record("{\"request\":{\"uri\":\"/x/chat\",\"method\":\"POST\"}}"))).isFalse();
    }

    @Test
    void ignoreCaseFoldsBothSides() {
        JsonNode lowerMethod = record("{\"request\":{\"method\":\"post\"}}");
        // Case-sensitive (default) fails on a lower-case method; ignore-case matches it.
        assertThat(new RecordMatcher(List.of(
                new RecordMatcher.Condition(JsonPaths.compile("request.method"), RecordMatcher.Op.EQUALS, "POST")))
                .matches(lowerMethod)).isFalse();
        assertThat(new RecordMatcher(List.of(
                new RecordMatcher.Condition(JsonPaths.compile("request.method"), RecordMatcher.Op.EQUALS, "POST", true)))
                .matches(lowerMethod)).isTrue();
    }

    @Test
    void missingSourcePathNeverMatches() {
        RecordMatcher matcher = new RecordMatcher(List.of(
                condition("request.uri", RecordMatcher.Op.ENDS_WITH, "/rate")));
        assertThat(matcher.matches(record("{\"request\":{\"method\":\"POST\"}}"))).isFalse();
        assertThat(matcher.matches(record("{}"))).isFalse();
    }

    @Test
    void unknownOperatorTokenResolvesToNull() {
        assertThat(RecordMatcher.Op.fromToken("ends-with")).isEqualTo(RecordMatcher.Op.ENDS_WITH);
        assertThat(RecordMatcher.Op.fromToken("EQUALS")).isEqualTo(RecordMatcher.Op.EQUALS);
        assertThat(RecordMatcher.Op.fromToken("startswith")).isNull();
        assertThat(RecordMatcher.Op.fromToken(null)).isNull();
    }
}
