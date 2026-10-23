package com.epam.aidial.analytics.sync.delta;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RequestDeltaEncoderTest {

    private static final String SCOPE = "00000000-0000-4000-8000-000000000001";
    private static final String OTHER_SCOPE = "00000000-0000-4000-8000-000000000002";
    private static final String DAY = "2026-01-15";

    private final Table table = new Table();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private RequestDeltaEncoder encoder = newEncoder();

    @Test
    void followUpTurnStoresOnlyItsNewMessages() {
        Map<String, Object> a = row("e1", "10:00:00.000", chat("[" + msg("system", "s") + "," + msg("user", "u1") + "]"));
        Map<String, Object> b = row("e2", "10:01:00.000",
                chat("[" + msg("system", "s") + "," + msg("user", "u1") + "," + msg("assistant", "a1") + "," + msg("user", "u2") + "]"));

        flush(a);
        flush(b);

        assertThat(b.get("request_parent")).isEqualTo(a.get("request_path"));
        assertThat(b.get("request_depth")).isEqualTo(4L);
        assertThat(b.get("request_delta")).isEqualTo("[" + canonicalMsg("assistant", "a1") + "," + canonicalMsg("user", "u2") + "]");
        assertThat(b.get("request_ancestors")).isEqualTo(List.of(a.get("request_path")));
        assertThat(b.get("request_body")).isNull();
        assertRebuilds(b);
    }

    @Test
    void firstRequestIsRootWithItsWholeHistory() {
        Map<String, Object> a = row("e1", "10:00:00.000", chat("[" + msg("user", "u1") + "]"));

        flush(a);

        assertThat(a.get("request_parent")).isEqualTo(RequestDeltaEncoder.NO_PARENT.toString());
        assertThat(a.get("request_ancestors")).isEqualTo(List.of());
        assertThat(a.get("request_delta")).isEqualTo("[" + canonicalMsg("user", "u1") + "]");
        assertThat(a.get("request_cache_markers")).isNull();
        assertRebuilds(a);
    }

    @Test
    void equalMessagesUnderDifferentHistoryAreNotLinked() {
        flush(row("e1", "10:00:00.000", chat("[" + msg("user", "u0") + "," + msg("assistant", "r0") + "," + msg("user", "u1") + ","
                + msg("assistant", "r1") + "]")));
        Map<String, Object> other = row("e2", "10:01:00.000", chat("[" + msg("user", "u1") + "," + msg("assistant", "r1") + ","
                + msg("user", "u2") + "]"));

        flush(other);

        assertThat(other.get("request_parent")).isEqualTo(RequestDeltaEncoder.NO_PARENT.toString());
        assertThat(Rebuild.MAPPER.readTree((String) other.get("request_delta"))).hasSize(3);
    }

    @Test
    void identicalRetryLinksLikeTheOriginalAndNeverToItself() {
        String first = "[" + msg("user", "u1") + "]";
        String second = "[" + msg("user", "u1") + "," + msg("assistant", "a1") + "," + msg("user", "u2") + "]";
        Map<String, Object> a = row("e1", "10:00:00.000", chat(first));
        Map<String, Object> b = row("e2", "10:01:00.000", chat(second));
        Map<String, Object> retry = row("e3", "10:01:05.000", chat(second));

        flush(a);
        flush(b);
        flush(retry);

        assertThat(retry.get("request_path")).isEqualTo(b.get("request_path"));
        assertThat(retry.get("request_parent")).isEqualTo(a.get("request_path")).isNotEqualTo(retry.get("request_path"));
        assertThat(retry.get("request_delta")).isEqualTo(b.get("request_delta"));
        assertRebuilds(retry);
        assertRebuilds(b);
    }

    @Test
    void anotherUsersIdenticalHistoryIsNotParent() {
        String history = "[" + msg("user", "u1") + "]";
        Map<String, Object> mine = row("e1", "10:00:00.000", chat(history));
        Map<String, Object> theirs = row("e2", "10:00:30.000", OTHER_SCOPE, DAY, chat(history));
        Map<String, Object> theirFollowUp = row("e3", "10:01:00.000", OTHER_SCOPE, DAY,
                chat("[" + msg("user", "u1") + "," + msg("assistant", "a1") + "," + msg("user", "u2") + "]"));

        flush(mine);
        flush(theirs, theirFollowUp);

        assertThat(theirs.get("request_path")).isNotEqualTo(mine.get("request_path"));
        assertThat(theirFollowUp.get("request_parent")).isEqualTo(theirs.get("request_path"));
    }

    @Test
    void conversationCrossingMidnightStartsNewChain() {
        Map<String, Object> late = row("e1", "23:58:00.000", SCOPE, "2026-01-14", chat("[" + msg("user", "u1") + "]"));
        Map<String, Object> early = row("e2", "00:03:00.000", SCOPE, DAY,
                chat("[" + msg("user", "u1") + "," + msg("assistant", "a1") + "," + msg("user", "u2") + "]"));

        flush(late);
        flush(early);

        assertThat(early.get("request_parent")).isEqualTo(RequestDeltaEncoder.NO_PARENT.toString());
        assertThat(Rebuild.MAPPER.readTree((String) early.get("request_delta"))).hasSize(3);
    }

    @Test
    void parentInTheSameFlushIsFoundWithoutAskingTheTable() {
        Map<String, Object> b = row("e2", "10:01:00.000", chat("[" + msg("user", "u1") + "," + msg("assistant", "a1") + "," + msg("user", "u2") + "]"));
        Map<String, Object> a = row("e1", "10:00:00.000", chat("[" + msg("user", "u1") + "]"));

        flush(b, a); // out of time order within the batch

        assertThat(b.get("request_parent")).isEqualTo(a.get("request_path"));
        assertThat(table.lookups).hasValue(0);
        assertThat(outcomes()).containsEntry("flush", 1.0).containsEntry("root", 1.0);
    }

    @Test
    void childIngestedBeforeItsParentIsRootAndStaysOne() {
        Map<String, Object> b = row("e2", "10:01:00.000", chat("[" + msg("user", "u1") + "," + msg("assistant", "a1") + "," + msg("user", "u2") + "]"));
        Map<String, Object> a = row("e1", "10:00:00.000", chat("[" + msg("user", "u1") + "]"));

        flush(b);
        flush(a);

        assertThat(b.get("request_parent")).isEqualTo(RequestDeltaEncoder.NO_PARENT.toString());
        assertThat(table.row("e2").get("request_parent")).isEqualTo(RequestDeltaEncoder.NO_PARENT.toString());
        assertRebuilds(b);
    }

    @Test
    void coldInstanceFindsTheParentInTheTableWithOneLookupPerFlush() {
        Map<String, Object> a = row("e1", "10:00:00.000", chat("[" + msg("user", "u1") + "]"));
        Map<String, Object> other = row("e9", "10:00:10.000", OTHER_SCOPE, DAY, chat("[" + msg("user", "x") + "]"));
        flush(a, other);
        encoder = newEncoder(); // a restart, or another instance: nothing cached
        table.lookups.set(0);

        Map<String, Object> b = row("e2", "10:01:00.000", chat("[" + msg("user", "u1") + "," + msg("assistant", "a1") + "," + msg("user", "u2") + "]"));
        Map<String, Object> y = row("e8", "10:01:10.000", OTHER_SCOPE, DAY, chat("[" + msg("user", "x") + "," + msg("assistant", "y") + "]"));
        flush(b, y);

        assertThat(table.lookups).hasValue(1);
        assertThat(b.get("request_parent")).isEqualTo(a.get("request_path"));
        assertThat(y.get("request_parent")).isEqualTo(other.get("request_path"));
        assertThat(outcomes()).containsEntry("table", 2.0);
    }

    @Test
    void failedInsertTeachesTheCacheNothing() {
        Map<String, Object> a = row("e1", "10:00:00.000", chat("[" + msg("user", "u1") + "]"));
        encoder.encode(new ArrayList<>(List.of(a))); // the insert failed: written() is never called

        Map<String, Object> b = row("e2", "10:01:00.000", chat("[" + msg("user", "u1") + "," + msg("assistant", "a1") + "]"));
        flush(b);

        assertThat(b.get("request_parent")).isEqualTo(RequestDeltaEncoder.NO_PARENT.toString());
    }

    @Test
    void movedCacheMarkerDoesNotBreakTheChainAndIsKeptPerEvent() {
        String marker = ",\"cache_control\":{\"type\":\"ephemeral\"}";
        Map<String, Object> t1 = row("e1", "10:00:00.000", chat("[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"u1\"" + marker + "}]}]"));
        Map<String, Object> t2 = row("e2", "10:01:00.000", chat("[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"u1\"}]},"
                + "{\"role\":\"assistant\",\"content\":\"a1\"},"
                + "{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"u2\"" + marker + "}]}]"));

        flush(t1);
        flush(t2);

        assertThat(t2.get("request_parent")).isEqualTo(t1.get("request_path"));
        assertThat((String) t2.get("request_delta")).doesNotContain("cache_control");
        assertThat(t2.get("request_cache_markers")).isEqualTo("[{\"i\":2,\"at\":\"/content/0\",\"v\":{\"type\":\"ephemeral\"}}]");
        // Each rebuild carries its own markers only: t2's first message comes back without the marker t1 had.
        assertRebuilds(t1);
        assertRebuilds(t2);
    }

    @Test
    void bodiesThatAreNotChatRequestsAreLeftWhole() {
        Map<String, Object> mcp = row("e1", "10:00:00.000", "{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"id\":1}");
        Map<String, Object> truncated = row("e2", "10:00:01.000", "{\"messages\":[{\"role\":\"user\",\"content\":\"cut");
        Map<String, Object> none = row("e3", "10:00:02.000", null);

        flush(mcp, truncated, none);

        assertThat(mcp).doesNotContainKeys("request_path", "request_delta", "request_envelope").containsKey("request_body");
        assertThat(truncated.get("request_body")).isEqualTo("{\"messages\":[{\"role\":\"user\",\"content\":\"cut");
        assertThat(none.get("request_body")).isNull();
        assertThat(outcomes()).containsEntry("whole", 3.0);
    }

    @Test
    void rebuildSurvivesParentRelinkedByReingest() {
        String historyA = "[" + msg("user", "u1") + "]";
        String historyB = "[" + msg("user", "u1") + "," + msg("assistant", "a1") + "," + msg("user", "u2") + "]";
        String historyC = "[" + msg("user", "u1") + "," + msg("assistant", "a1") + "," + msg("user", "u2") + "," + msg("assistant", "a2") + ","
                + msg("user", "u3") + "]";
        flush(row("e2", "10:01:00.000", chat(historyB)));          // B first: a root
        flush(row("e3", "10:02:00.000", chat(historyC)));          // C links to B
        flush(row("e1", "10:00:00.000", chat(historyA)));          // A arrives late
        encoder = newEncoder();
        flush(row("e2", "10:01:00.000", chat(historyB)));          // B re-ingested: now A's child, shorter delta

        assertThat(table.row("e2").get("request_parent")).isEqualTo(table.row("e1").get("request_path"));
        assertRebuilds(table.row("e3"));
        assertRebuilds(table.row("e2"));
    }

    /**
     * Generated agent conversations — branches, identical retries, moved markers, several users and two days — ingested
     * in shuffled flushes, some on a cold encoder, some events twice: every surviving row rebuilds to its body.
     */
    @Test
    void everyStoredRowRebuildsToItsBody() {
        Random random = new Random(42);
        List<Map<String, Object>> events = new ArrayList<>();
        int event = 0;
        for (int conversation = 0; conversation < 40; conversation++) {
            String scope = random.nextBoolean() ? SCOPE : OTHER_SCOPE;
            String day = random.nextInt(4) == 0 ? "2026-01-14" : DAY;
            List<String> history = new ArrayList<>();
            history.add("{\"role\":\"system\",\"content\":\"agent " + random.nextInt(3) + "\"}");
            int turns = 1 + random.nextInt(12);
            for (int turn = 0; turn < turns; turn++) {
                if (turn > 0 && random.nextInt(6) == 0) {
                    history = new ArrayList<>(history.subList(0, Math.max(1, history.size() - 2))); // an edit: branch off
                }
                history.add("{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"q" + conversation + "-" + turn + "\""
                        + (random.nextBoolean() ? ",\"cache_control\":{\"type\":\"ephemeral\"}" : "") + "}]}");
                String body = "{\"model\":\"m\",\"messages\":[" + String.join(",", history) + "]}";
                String time = String.format("%02d:%02d:%02d.000", 8 + conversation % 10, turn, random.nextInt(60));
                events.add(row("e" + event++, time, scope, day, body));
                if (random.nextInt(8) == 0) {
                    events.add(row("e" + event++, time, scope, day, body)); // an identical retry
                }
                history.add("{\"role\":\"assistant\",\"content\":\"a" + conversation + "-" + turn + "\"}");
            }
        }
        List<Map<String, Object>> order = new ArrayList<>(events);
        java.util.Collections.shuffle(order, random);
        for (int i = 0; i < order.size(); ) {
            int size = 1 + random.nextInt(15);
            List<Map<String, Object>> batch = new ArrayList<>();
            for (int j = i; j < Math.min(order.size(), i + size); j++) {
                batch.add(copy(order.get(j)));
                if (random.nextInt(10) == 0) {
                    batch.add(copy(order.get(random.nextInt(order.size())))); // re-ingest of some event
                }
            }
            if (random.nextInt(5) == 0) {
                // A restart; now and then onto the other mode, as a rollback and a re-enable would.
                encoder = newEncoder(random.nextInt(4) == 0 ? RequestBodyMode.WHOLE : RequestBodyMode.DELTA);
            }
            flush(batch.toArray(Map[]::new));
            i += size;
        }

        for (Map<String, Object> original : events) {
            Map<String, Object> stored = table.row((String) original.get("event_id"));
            assertThat(Rebuild.canonical(Rebuild.body(stored, table.rows())))
                    .as("event %s", original.get("event_id"))
                    .isEqualTo(Rebuild.canonical((String) original.get("request_body")));
        }
        assertThat(outcomes().get("root")).isLessThan(events.size());
    }

    /** A long agent session on one encoder: each turn is linked, so a turn stores two messages, not the whole history. */
    @Test
    void longSessionStaysLinkedTurnAfterTurn() {
        StringBuilder history = new StringBuilder("{\"role\":\"system\",\"content\":\"" + "s".repeat(20_000) + "\"}");
        long deltaBytes = 0;
        long bodyBytes = 0;
        long started = System.nanoTime();
        for (int turn = 0; turn < 600; turn++) {
            history.append(",{\"role\":\"user\",\"content\":\"step ").append(turn).append(' ').append("x".repeat(300)).append("\"}");
            String body = "{\"messages\":[" + history + "]}";
            Map<String, Object> row = row("e" + turn, String.format("10:%02d:%02d.%03d", turn / 3600 % 60, turn / 60 % 60, turn % 1000), SCOPE, DAY, body);
            flush(row);
            bodyBytes += body.length();
            deltaBytes += ((String) row.get("request_delta")).length();
            history.append(",{\"role\":\"assistant\",\"content\":\"ok ").append(turn).append("\"}");
        }
        long millis = (System.nanoTime() - started) / 1_000_000;

        assertThat(outcomes()).containsEntry("cache", 599.0).containsEntry("root", 1.0);
        assertThat(deltaBytes * 100).isLessThan(bodyBytes);
        Map<String, Object> last = table.row("e599");
        assertThat((List<?>) last.get("request_ancestors")).hasSize(RequestDeltaEncoder.ANCESTORS_HINT);
        assertRebuilds(last); // the hint is capped; the chain is not
        // Recorded for the reader, not asserted tightly: parsing and hashing every body is linear in its size.
        System.out.printf("request-delta: 600 turns, %d MiB of bodies encoded in %d ms%n", bodyBytes >> 20, millis);
        assertThat(millis).isLessThan(120_000);
    }

    @Test
    void wholeKeepsTheBodyAndStampsOnlyThePath() {
        encoder = newEncoder(RequestBodyMode.WHOLE);
        String body = chat("[" + msg("user", "u1") + "," + msg("assistant", "a1") + "]");
        Map<String, Object> row = row("e1", "10:00:00.000", body);

        flush(row);

        assertThat(row.get("request_body")).isEqualTo(body);
        assertThat(row.get("request_depth")).isEqualTo(2L);
        assertThat(row).containsKey("request_path").doesNotContainKeys("request_parent", "request_delta", "request_envelope");
        assertThat(table.lookups).hasValue(0);
        assertThat(outcomes()).containsEntry("whole_with_path", 1.0);
    }

    @Test
    void switchToDeltaLinksOntoRowsStoredWhole() {
        encoder = newEncoder(RequestBodyMode.WHOLE);
        Map<String, Object> a = row("e1", "10:00:00.000", chat("[" + msg("user", "u1") + "]"));
        flush(a);
        encoder = newEncoder(RequestBodyMode.DELTA);

        Map<String, Object> b = row("e2", "10:01:00.000", chat("[" + msg("user", "u1") + "," + msg("assistant", "a1") + "," + msg("user", "u2") + "]"));
        flush(b);

        assertThat(b.get("request_parent")).isEqualTo(a.get("request_path"));
        assertRebuilds(b);
    }

    @Test
    void parentReingestedUnderWholeKeepsItsChildRebuildable() {
        String parent = chat("[" + msg("user", "u1") + "]");
        flush(row("e1", "10:00:00.000", parent));
        Map<String, Object> child = row("e2", "10:01:00.000", chat("[" + msg("user", "u1") + "," + msg("assistant", "a1") + "," + msg("user", "u2") + "]"));
        flush(child);

        encoder = newEncoder(RequestBodyMode.WHOLE); // rolled back, then the parent's day is backfilled
        flush(row("e1", "10:00:00.000", parent));

        assertThat(table.row("e1").get("request_body")).isEqualTo(parent);
        assertRebuilds(child);
    }

    @Test
    void failedLookupStoresRootsAndDoesNotThrow() {
        RequestDeltaEncoder failing = new RequestDeltaEncoder((days, paths) -> {
            throw new IllegalStateException("ClickHouse is unreachable");
        }, 1000, registry, "dial_usage_log_payload");
        Map<String, Object> b = row("e2", "10:01:00.000", chat("[" + msg("user", "u1") + "," + msg("assistant", "a1") + "," + msg("user", "u2") + "]"));

        failing.encode(new ArrayList<>(List.of(b))).written();

        assertThat(b.get("request_parent")).isEqualTo(RequestDeltaEncoder.NO_PARENT.toString());
        assertThat(registry.get("ingest.request.delta.lookup.failures").counter().count()).isEqualTo(1.0);
    }

    @Test
    void tableCandidatesPerFlushAreBounded() {
        List<Integer> asked = new ArrayList<>();
        RequestDeltaEncoder bounded = new RequestDeltaEncoder((days, paths) -> {
            asked.add(paths.size());
            return Map.of();
        }, 1000, registry, "dial_usage_log_payload");
        List<Map<String, Object>> batch = new ArrayList<>();
        for (int i = 0; i < 2500; i++) { // 2500 x 8 candidates is past the cap
            StringBuilder messages = new StringBuilder("[");
            for (int m = 0; m < 80; m++) {
                messages.append(m == 0 ? "" : ",").append(msg("user", "c" + i + "-" + m));
            }
            batch.add(row("e" + i, String.format("10:%02d:%02d.%03d", i / 60000, i / 1000 % 60, i % 1000), chat(messages + "]")));
        }

        bounded.encode(batch);

        assertThat(asked).singleElement().satisfies(size -> assertThat(size)
                .isLessThanOrEqualTo(RequestDeltaEncoder.MAX_TABLE_CANDIDATES).isGreaterThan(RequestDeltaEncoder.MAX_TABLE_CANDIDATES - 8));
        assertThat(batch).allSatisfy(row -> assertThat(row.get("request_parent")).isEqualTo(RequestDeltaEncoder.NO_PARENT.toString()));
    }

    @Test
    void loneSurrogatesDoNotCollideWithReplacementCharacters() {
        Map<String, Object> surrogate = row("e1", "10:00:00.000", chat("[{\"role\":\"user\",\"content\":\"\ud800\"}]"));
        Map<String, Object> question = row("e2", "10:00:01.000", chat("[{\"role\":\"user\",\"content\":\"?\"}]"));

        flush(surrogate, question);

        assertThat(surrogate.get("request_path")).isNotEqualTo(question.get("request_path"));
    }

    /** A row the second parse cannot reproduce (here: passed twice) is kept as written, never a failed flush. */
    @Test
    void sameRowPassedTwiceDoesNotFailTheFlush() {
        Map<String, Object> row = row("e1", "10:00:00.000", chat("[" + msg("user", "u1") + "," + msg("assistant", "a1") + "]"));

        encoder.encode(new ArrayList<>(List.of(row, row))).written();

        assertThat(row.get("request_body")).isNull();
        assertThat(row.get("request_delta")).isNotNull();
        assertThat(outcomes()).containsEntry("whole_with_path", 1.0);
    }

    @Test
    void timeWithNonUtcOffsetIsStoredWhole() {
        Map<String, Object> row = row("e1", "10:00:00.000", chat("[" + msg("user", "u1") + "]"));
        row.put("request_time", DAY + " 10:00:00.000+03:00");

        flush(row);

        assertThat(row).doesNotContainKey("request_path").containsKey("request_body");
    }

    /** The ancestors hint through a node read from the table: the nearest 128 paths, root side first, nearest last. */
    @Test
    void lineageThroughStoredNodeKeepsTheNearestPaths() {
        List<UUID> hint = new ArrayList<>();
        for (int i = 0; i < 128; i++) {
            hint.add(new UUID(1, i));
        }
        ChainNode stored = ChainNode.stored(new UUID(2, 0), hint);
        ChainNode node = stored;
        List<UUID> written = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            node = ChainNode.written(new UUID(3, i), node);
            written.add(new UUID(3, i));
        }

        List<UUID> expected = new ArrayList<>(hint.subList(6, 128));
        expected.add(new UUID(2, 0));
        expected.addAll(written);
        assertThat(node.lineage(128)).containsExactlyElementsOf(expected);

        for (int i = 5; i < 300; i++) {
            node = ChainNode.written(new UUID(3, i), node);
        }
        List<UUID> nearest = node.lineage(128);
        assertThat(nearest).hasSize(128).first().isEqualTo(new UUID(3, 172));
        assertThat(nearest).last().isEqualTo(new UUID(3, 299));
    }

    // --- harness ------------------------------------------------------------------------------------------------

    private RequestDeltaEncoder newEncoder() {
        return newEncoder(RequestBodyMode.DELTA);
    }

    private RequestDeltaEncoder newEncoder(RequestBodyMode mode) {
        return new RequestDeltaEncoder(mode, table, 1000, registry, "dial_usage_log_payload");
    }

    /** Encodes one batch, "inserts" it, and reports it written — what the payload sink does on a successful flush. */
    @SafeVarargs
    private void flush(Map<String, Object>... rows) {
        List<Map<String, Object>> batch = new ArrayList<>(List.of(rows));
        RequestDeltaEncoder.Encoding encoding = encoder.encode(batch);
        table.insert(batch);
        encoding.written();
    }

    private void assertRebuilds(Map<String, Object> row) {
        Map<String, Object> stored = table.row((String) row.get("event_id"));
        JsonNode rebuilt = Rebuild.body(stored, table.rows());
        assertThat(Rebuild.canonical(rebuilt)).isEqualTo(Rebuild.canonical((String) table.original.get(row.get("event_id"))));
    }

    private Map<String, Double> outcomes() {
        Map<String, Double> outcomes = new HashMap<>();
        registry.find("ingest.request.delta.rows").counters()
                .forEach(counter -> outcomes.put(counter.getId().getTag("outcome"), counter.count()));
        return outcomes;
    }

    private Map<String, Object> row(String eventId, String time, String body) {
        return row(eventId, time, SCOPE, DAY, body);
    }

    private Map<String, Object> row(String eventId, String time, String scope, String day, String body) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("event_id", eventId);
        row.put("request_time", day + " " + time);
        row.put("request_body", body);
        row.put("request_scope", scope);
        table.original.putIfAbsent(eventId, body);
        return row;
    }

    private static Map<String, Object> copy(Map<String, Object> row) {
        Map<String, Object> copy = new LinkedHashMap<>(row);
        copy.keySet().removeIf(key -> key.startsWith("request_") && !List.of("request_time", "request_body", "request_scope").contains(key));
        return copy;
    }

    private static String chat(String messages) {
        return "{\"model\":\"m\",\"messages\":" + messages + "}";
    }

    private static String msg(String role, String content) {
        return "{\"role\":\"" + role + "\",\"content\":\"" + content + "\"}";
    }

    private static String canonicalMsg(String role, String content) {
        return "{\"content\":\"" + content + "\",\"role\":\"" + role + "\"}";
    }

    /** The payload table, as far as the encoder can tell: rows by event id, the latest write winning, and a path lookup. */
    private static final class Table implements ParentLookup {

        private final Map<String, Map<String, Object>> byEvent = new LinkedHashMap<>();
        private final Map<Object, String> original = new HashMap<>();
        private final AtomicInteger lookups = new AtomicInteger();

        void insert(List<Map<String, Object>> rows) {
            rows.forEach(row -> byEvent.put((String) row.get("event_id"), row));
        }

        Map<String, Object> row(String eventId) {
            return byEvent.get(eventId);
        }

        Collection<Map<String, Object>> rows() {
            return byEvent.values();
        }

        @Override
        public Map<UUID, List<UUID>> find(Collection<String> days, Collection<UUID> paths) {
            lookups.incrementAndGet();
            Map<UUID, List<UUID>> found = new HashMap<>();
            for (Map<String, Object> row : byEvent.values()) {
                Object path = row.get("request_path");
                if (path != null && paths.contains(UUID.fromString((String) path))
                        && days.contains(((String) row.get("request_time")).substring(0, 10))) {
                    @SuppressWarnings("unchecked")
                    List<String> ancestors = (List<String>) row.getOrDefault("request_ancestors", List.of());
                    found.put(UUID.fromString((String) path), ancestors.stream().map(UUID::fromString).toList());
                }
            }
            return found;
        }
    }
}
