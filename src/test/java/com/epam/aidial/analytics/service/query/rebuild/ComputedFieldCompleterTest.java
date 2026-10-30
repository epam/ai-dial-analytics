package com.epam.aidial.analytics.service.query.rebuild;

import com.clickhouse.client.api.query.GenericRecord;
import com.clickhouse.client.api.query.QuerySettings;
import com.epam.aidial.analytics.clickhouse.ClickHouseClientWrapper;
import com.epam.aidial.analytics.configuration.RequestBodyRebuildProperties;
import com.epam.aidial.analytics.exception.ClickHouseUnavailableException;
import com.epam.aidial.analytics.observability.LogCapture;
import com.epam.aidial.analytics.service.query.ComputedField;
import com.epam.aidial.analytics.sync.delta.RequestBodyRebuild;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The completer over a stubbed client answering the two rebuild statements from an in-memory payload table, so a case
 * reads as "these rows are stored, this page was read": which statements it issues, how it seeds each round, and how
 * every failure degrades to {@code partial} rather than failing the read.
 */
class ComputedFieldCompleterTest {

    private static final int DAY = 20260302;
    private static final int OTHER_DAY = 20260303;
    private static final String FULL = "full";
    private static final String STATUS = "status";
    private static final List<ComputedFieldCompleter.Slot> BOTH = List.of(
            new ComputedFieldCompleter.Slot(FULL, ComputedField.Kind.BODY), new ComputedFieldCompleter.Slot(STATUS, ComputedField.Kind.STATUS));
    private static final List<ComputedFieldCompleter.Slot> STATUS_ONLY = List.of(new ComputedFieldCompleter.Slot(STATUS, ComputedField.Kind.STATUS));

    private final ClickHouseClientWrapper client = mock(ClickHouseClientWrapper.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    /** The payload table: event id to its current row. */
    private final Map<String, Stored> table = new LinkedHashMap<>();
    private final List<String> linkCalls = new ArrayList<>();
    private final List<String> bodyCalls = new ArrayList<>();
    /** Applied after the first phase-1 statement, to simulate a re-ingest between the phases. */
    private Runnable afterFirstLookup;
    /** A day whose every statement fails, or {@code null}. */
    private String failingDay;

    record Stored(String eventId, UUID path, UUID parent, List<UUID> ancestors, String body) {}

    @BeforeEach
    void stubClient() {
        when(client.query(anyString(), anyMap(), any(QuerySettings.class))).thenAnswer(invocation -> {
            final String sql = invocation.getArgument(0);
            final Map<String, Object> params = invocation.getArgument(1);
            assertThat(params.get("days")).isIn(Integer.toString(DAY), Integer.toString(OTHER_DAY));
            if (params.get("days").equals(failingDay)) {
                throw new ClickHouseUnavailableException("down", null);
            }
            assertThat(((QuerySettings) invocation.getArgument(2)).getAllSettings()).containsKeys(
                    com.epam.aidial.analytics.clickhouse.FinalSettings.FINAL_KEY,
                    com.epam.aidial.analytics.clickhouse.FinalSettings.NO_MERGE_ACROSS_PARTITIONS_KEY);
            final List<GenericRecord> rows = new ArrayList<>();
            if (sql.equals(RequestChainReader.linksStatement())) {
                final List<String> paths = Arrays.asList(((String) params.get("paths")).split(","));
                linkCalls.add(String.join(",", paths));
                final Map<UUID, Stored> byPath = new LinkedHashMap<>();
                table.values().forEach(row -> byPath.putIfAbsent(row.path(), row));
                for (final String path : paths) {
                    final Stored row = byPath.get(UUID.fromString(path));
                    if (row != null) {
                        rows.add(record(Map.of("path", path, "parent", row.parent().toString(),
                                "ancestors", String.join(",", row.ancestors().stream().map(UUID::toString).toList()), "event_id", row.eventId())));
                    }
                }
                if (afterFirstLookup != null) {
                    afterFirstLookup.run();
                    afterFirstLookup = null;
                }
            } else {
                assertThat(sql).isEqualTo(RequestChainReader.bodiesStatement());
                final List<String> events = Arrays.asList(((String) params.get("events")).split(","));
                bodyCalls.add(String.join(",", events));
                for (final String event : events) {
                    final Stored row = table.get(event);
                    if (row != null) {
                        final Map<String, String> values = new HashMap<>();
                        values.put("event_id", event);
                        values.put("path", row.path().toString());
                        values.put("parent", row.parent().toString());
                        values.put("body", row.body());
                        rows.add(record(values));
                    }
                }
            }
            return rows;
        });
    }

    private static GenericRecord record(Map<String, String> values) {
        final GenericRecord record = mock(GenericRecord.class);
        when(record.getString(anyString())).thenAnswer(invocation -> values.get(invocation.<String>getArgument(0)));
        return record;
    }

    private ComputedFieldCompleter completer(int maxRounds, int maxAncestors) {
        return new ComputedFieldCompleter(new RequestChainReader(client), new RequestBodyRebuildProperties(maxRounds, maxAncestors), meters);
    }

    private static UUID path(int n) {
        return UUID.fromString(String.format("00000000-0000-4000-8000-%012d", n));
    }

    /** A chain of {@code length} stored turns, path 1 the root; each turn adds message {@code m<n>}. */
    private void chain(int length, int hint) {
        for (int n = 1; n <= length; n++) {
            final List<UUID> ancestors = new ArrayList<>();
            for (int a = Math.max(1, n - hint); a < n; a++) {
                ancestors.add(path(a));
            }
            final String body = n == 1 ? "{\"messages\":[{\"content\":\"m1\",\"role\":\"user\"}],\"tools\":[]}"
                    : "{\"messages\":[{\"content\":\"m" + n + "\",\"role\":\"user\"}]}";
            table.put("e" + n, new Stored("e" + n, path(n), n == 1 ? RequestBodyRebuild.NO_PARENT : path(n - 1), List.copyOf(ancestors), body));
        }
    }

    /** A page row for a turn whose parent is {@code parent}, as the stand-in cell carries it. */
    private static Map<String, Object> row(String body, UUID parent, List<UUID> hint, String markers, List<ComputedFieldCompleter.Slot> slots) {
        return row(body, parent, hint, markers, slots, DAY);
    }

    private static Map<String, Object> row(String body, UUID parent, List<UUID> hint, String markers, List<ComputedFieldCompleter.Slot> slots,
            int day) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("trace_id", "trace-0001");
        for (final ComputedFieldCompleter.Slot slot : slots) {
            final boolean status = slot.kind() == ComputedField.Kind.STATUS;
            final Object first = status ? (Object) (body == null ? (short) 1 : (short) 0) : body;
            final Object fourth = status ? (Object) (markers == null ? (short) 0 : (short) 1) : markers;
            row.put(slot.column(), new Object[] {first, parent.toString(), hint.stream().map(UUID::toString).toList(), fourth, (long) day});
        }
        return row;
    }

    private static String turn(int n) {
        return "{\"messages\":[{\"content\":\"m" + n + "\",\"role\":\"user\"}]}";
    }

    private static List<UUID> paths(int from, int to) {
        final List<UUID> paths = new ArrayList<>();
        for (int n = from; n <= to; n++) {
            paths.add(path(n));
        }
        return paths;
    }

    private void complete(ComputedFieldCompleter completer, List<ComputedFieldCompleter.Slot> slots, List<Map<String, Object>> rows) {
        completer.complete(slots, rows, QuerySettings::new, ComputedFieldCompleter.Surface.QUERY);
    }

    private double partial(String reason) {
        final var counter = meters.find(ComputedFieldCompleter.PARTIAL_METER).tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void pageStoredWholeIssuesNoStatement() {
        final List<Map<String, Object>> rows = List.of(row("{\"a\":1}", RequestBodyRebuild.NO_PARENT, List.of(), null, BOTH));

        complete(completer(8, 100), BOTH, rows);

        verifyNoInteractions(client);
        assertThat(rows.getFirst()).containsEntry(FULL, "{\"a\":1}").containsEntry(STATUS, "stored");
        assertThat(meters.get(ComputedFieldCompleter.ROWS_METER).tags("outcome", "stored", "surface", "query").counter().count()).isEqualTo(1);
    }

    @Test
    void chainTheHintCoversIsResolvedInOneRoundAndOneBodyRead() {
        chain(3, 128);
        final List<Map<String, Object>> rows = List.of(row(turn(4), path(3), paths(1, 3), null, BOTH));

        complete(completer(8, 100), BOTH, rows);

        assertThat(rows.getFirst().get(STATUS)).isEqualTo("rebuilt");
        assertThat(rows.getFirst().get(FULL)).isEqualTo("{\"messages\":[{\"content\":\"m1\",\"role\":\"user\"},{\"content\":\"m2\",\"role\":\"user\"},"
                + "{\"content\":\"m3\",\"role\":\"user\"},{\"content\":\"m4\",\"role\":\"user\"}],\"tools\":[]}");
        assertThat(linkCalls).hasSize(1);
        assertThat(linkCalls.getFirst().split(",")).hasSize(3);
        assertThat(bodyCalls).containsExactly("e3,e2,e1");
    }

    @Test
    void laterRoundsAreSeededFromTheFoundRowsOwnHints() {
        chain(10, 2);
        final List<Map<String, Object>> rows = List.of(row(turn(11), path(10), paths(9, 10), null, BOTH));

        complete(completer(8, 100), BOTH, rows);

        assertThat(rows.getFirst().get(STATUS)).isEqualTo("rebuilt");
        // Round one finds paths 9 and 10; path 8 is missing, so round two is seeded with it and row 9's hint (7, 8).
        assertThat(linkCalls.get(1)).isEqualTo(path(8) + "," + path(7));
        assertThat(bodyCalls).hasSize(1);
    }

    @Test
    void theRoundBoundEndsTheChainAsLimit() {
        chain(10, 2);
        final Map<String, Object> deep = row(turn(11), path(10), paths(9, 10), null, BOTH);
        final List<Map<String, Object>> rows = List.of(deep);

        complete(completer(1, 100), BOTH, rows);

        assertThat(deep.get(STATUS)).isEqualTo("partial");
        assertThat(deep.get(FULL)).isEqualTo(turn(11));
        assertThat(partial("limit")).isEqualTo(1);
        assertThat(linkCalls).hasSize(1);
    }

    @Test
    void theAncestorBoundEndsTheChainAsLimit() {
        chain(10, 128);
        final Map<String, Object> deep = row(turn(11), path(10), paths(1, 10), null, BOTH);

        complete(completer(8, 4), BOTH, List.of(deep));

        assertThat(deep.get(STATUS)).isEqualTo("partial");
        assertThat(partial("limit")).isEqualTo(1);
        assertThat(String.join(",", linkCalls).split(",")).hasSize(4);
    }

    @Test
    void failingStatementEndsTheReadAsFailedNotAsAnError() {
        chain(2, 128);
        when(client.query(anyString(), anyMap(), any(QuerySettings.class))).thenThrow(new ClickHouseUnavailableException("down", null));
        final Map<String, Object> row = row(turn(3), path(2), paths(1, 2), null, BOTH);

        complete(completer(8, 100), BOTH, List.of(row));

        assertThat(row.get(STATUS)).isEqualTo("partial");
        assertThat(row.get(FULL)).isEqualTo(turn(3));
        assertThat(partial("failed")).isEqualTo(1);
    }

    @Test
    void spentBudgetIssuesNoStatementAndEndsTheRowsAsFailed() {
        chain(2, 128);
        final Map<String, Object> row = row(turn(3), path(2), paths(1, 2), null, BOTH);

        completer(8, 100).complete(BOTH, List.of(row), () -> null, ComputedFieldCompleter.Surface.QUERY);

        verifyNoInteractions(client);
        assertThat(row.get(STATUS)).isEqualTo("partial");
        assertThat(row.get(FULL)).isEqualTo(turn(3));
        assertThat(partial("failed")).isEqualTo(1);
    }

    /** The budget runs out after phase 1: the body read is not issued, and the row is not reported rebuilt. */
    @Test
    void budgetSpentBetweenStatementsStopsTheRebuild() {
        chain(2, 128);
        final Map<String, Object> row = row(turn(3), path(2), paths(1, 2), null, BOTH);
        final int[] asked = {0};

        completer(8, 100).complete(BOTH, List.of(row), () -> asked[0]++ == 0 ? new QuerySettings() : null,
                ComputedFieldCompleter.Surface.QUERY);

        assertThat(linkCalls).hasSize(1);
        assertThat(bodyCalls).isEmpty();
        assertThat(row.get(STATUS)).isEqualTo("partial");
        assertThat(partial("failed")).isEqualTo(1);
    }

    /**
     * In the round where one row of a day still needs a path and another needs its bodies, the lookup fails: the day's
     * rows all end failed, so its body statement is not issued, and the DEBUG line counts the statements sent.
     */
    @Test
    void failedLookupIssuesNoBodyStatementForItsDay() {
        chain(10, 2);
        afterFirstLookup = () -> failingDay = Integer.toString(DAY);
        final Map<String, Object> deep = row(turn(11), path(10), paths(9, 10), null, BOTH);
        final Map<String, Object> shallow = row(turn(3), path(2), paths(1, 2), null, BOTH);

        try (LogCapture logs = LogCapture.of(ComputedFieldCompleter.class)) {
            complete(completer(8, 100), BOTH, List.of(deep, shallow));

            assertThat(logs.messagesAt(Level.DEBUG)).anySatisfy(line -> assertThat(line).contains("2 statement(s)"));
        }
        verify(client, times(2)).query(anyString(), anyMap(), any(QuerySettings.class));
        assertThat(deep.get(STATUS)).isEqualTo("partial");
        assertThat(shallow.get(STATUS)).isEqualTo("partial");
        assertThat(partial("failed")).isEqualTo(2);
    }

    /** A chunk refused for lack of time is not a statement: only the one lookup sent is counted. */
    @Test
    void statementCountLeavesOutWhatTheBudgetRefused() {
        chain(2, 128);
        final Map<String, Object> row = row(turn(3), path(2), paths(1, 2), null, BOTH);
        final int[] asked = {0};

        try (LogCapture logs = LogCapture.of(ComputedFieldCompleter.class)) {
            completer(8, 100).complete(BOTH, List.of(row), () -> asked[0]++ == 0 ? new QuerySettings() : null,
                    ComputedFieldCompleter.Surface.QUERY);

            assertThat(logs.messagesAt(Level.DEBUG)).anySatisfy(line -> assertThat(line).contains("1 statement(s)"));
        }
    }

    @Test
    void failingStatementFailsOnlyTheRowsOfItsDay() {
        chain(2, 128);
        failingDay = Integer.toString(OTHER_DAY);
        final Map<String, Object> ok = row(turn(3), path(2), paths(1, 2), null, BOTH);
        final Map<String, Object> failed = row(turn(3), path(2), paths(1, 2), null, BOTH, OTHER_DAY);

        try (LogCapture logs = LogCapture.of(ComputedFieldCompleter.class)) {
            complete(completer(8, 100), BOTH, List.of(ok, failed));

            // One WARN line per read, whatever failed inside it.
            assertThat(logs.messagesAt(Level.WARN)).hasSize(1);
        }
        assertThat(ok.get(STATUS)).isEqualTo("rebuilt");
        assertThat(failed.get(STATUS)).isEqualTo("partial");
        assertThat(failed.get(FULL)).isEqualTo(turn(3));
        assertThat(partial("failed")).isEqualTo(1);
    }

    @Test
    void missingPathEndsTheChainAsMissing() {
        chain(3, 128);
        table.remove("e2");
        final Map<String, Object> row = row(turn(4), path(3), paths(1, 3), null, BOTH);

        complete(completer(8, 100), BOTH, List.of(row));

        assertThat(row.get(STATUS)).isEqualTo("partial");
        assertThat(partial("missing")).isEqualTo(1);
    }

    /** Path 2's row is re-ingested after phase 1: its body now carries another path, so path 2 is looked up again. */
    @Test
    void rowReingestedBetweenThePhasesIsResolvedAgain() {
        chain(3, 128);
        afterFirstLookup = () -> {
            final Stored moved = table.get("e2");
            table.put("e2", new Stored("e2", path(99), moved.parent(), moved.ancestors(), moved.body()));
            table.put("e2b", new Stored("e2b", path(2), path(1), List.of(path(1)), moved.body()));
        };
        final Map<String, Object> row = row(turn(4), path(3), paths(1, 3), null, BOTH);

        complete(completer(8, 100), BOTH, List.of(row));

        assertThat(row.get(STATUS)).isEqualTo("rebuilt");
        assertThat(linkCalls).hasSize(2);
        assertThat(linkCalls.get(1)).startsWith(path(2).toString());
        assertThat(bodyCalls).containsExactly("e3,e2,e1", "e2b");
    }

    /**
     * Path 2's row is re-ingested after phase 1 linking to another parent (as a root carrying the first two turns), and
     * the old parent's row is gone. The walk follows the link of the row whose body it read, so the body is rebuilt
     * from the new root; following phase 1's link would end at the missing row.
     */
    @Test
    void rowRelinkedBetweenThePhasesIsWalkedThroughItsNewParent() {
        chain(3, 128);
        afterFirstLookup = () -> {
            table.put("e2", new Stored("e2", path(2), RequestBodyRebuild.NO_PARENT, List.of(),
                    "{\"messages\":[{\"content\":\"m1\",\"role\":\"user\"},{\"content\":\"m2\",\"role\":\"user\"}],\"tools\":[]}"));
            table.remove("e1");
        };
        final Map<String, Object> row = row(turn(4), path(3), paths(1, 3), null, BOTH);

        complete(completer(8, 100), BOTH, List.of(row));

        assertThat(row.get(STATUS)).isEqualTo("rebuilt");
        assertThat(row.get(FULL)).isEqualTo("{\"messages\":[{\"content\":\"m1\",\"role\":\"user\"},{\"content\":\"m2\",\"role\":\"user\"},"
                + "{\"content\":\"m3\",\"role\":\"user\"},{\"content\":\"m4\",\"role\":\"user\"}],\"tools\":[]}");
    }

    @Test
    void statusOnlyReadRunsPhaseOneOnly() {
        chain(3, 128);
        final Map<String, Object> row = row(turn(4), path(3), paths(1, 3), null, STATUS_ONLY);

        complete(completer(8, 100), STATUS_ONLY, List.of(row));

        assertThat(row.get(STATUS)).isEqualTo("rebuilt");
        assertThat(linkCalls).hasSize(1);
        assertThat(bodyCalls).isEmpty();
    }

    @Test
    void rootWithMarkersIsRebuiltWithoutStatement() {
        final Map<String, Object> row = row(turn(1), RequestBodyRebuild.NO_PARENT, List.of(), "[{\"at\":\"\",\"i\":0,\"v\":{\"type\":\"ephemeral\"}}]", BOTH);

        complete(completer(8, 100), BOTH, List.of(row));

        verifyNoInteractions(client);
        assertThat(row.get(STATUS)).isEqualTo("rebuilt");
        assertThat((String) row.get(FULL)).contains("\"cache_control\":{\"type\":\"ephemeral\"}");
    }

    @Test
    void lookupsGoOutInChunksOfThousand() {
        final List<Map<String, Object>> rows = new ArrayList<>();
        for (int n = 1; n <= 1500; n++) {
            final UUID root = path(10_000 + n);
            table.put("r" + n, new Stored("r" + n, root, RequestBodyRebuild.NO_PARENT, List.of(), turn(n)));
            rows.add(row(turn(n + 1), root, List.of(), null, STATUS_ONLY));
        }

        complete(completer(8, 20000), STATUS_ONLY, rows);

        assertThat(linkCalls).hasSize(2);
        assertThat(linkCalls.get(0).split(",")).hasSize(RequestChainReader.CHUNK);
        assertThat(rows).allSatisfy(row -> assertThat(row.get(STATUS)).isEqualTo("rebuilt"));
    }

    @Test
    void nullBodyCompletesBothFieldsAsNull() {
        final Map<String, Object> row = row(null, RequestBodyRebuild.NO_PARENT, List.of(), null, BOTH);

        complete(completer(8, 100), BOTH, List.of(row));

        assertThat(row.get(FULL)).isNull();
        assertThat(row.get(STATUS)).isNull();
        assertThat(meters.find(ComputedFieldCompleter.ROWS_METER).counters()).isEmpty();
    }

    @Test
    void unmatchedRowReadUnderJoinUseNullsCompletesBothFieldsAsNull() {
        // Under join_use_nulls = 1 an unmatched join row's stand-in elements are all NULL, not their type defaults.
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put(FULL, new Object[] {null, null, null, null, null});
        row.put(STATUS, new Object[] {null, null, null, null, null});

        complete(completer(8, 100), BOTH, List.of(row));

        verifyNoInteractions(client);
        assertThat(row.get(FULL)).isNull();
        assertThat(row.get(STATUS)).isNull();
    }

    @Test
    void statusOnlyUnmatchedRowUnderJoinUseNullsReadsNull() {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put(STATUS, new Object[] {null, null, null, null, null});

        complete(completer(8, 100), STATUS_ONLY, List.of(row));

        assertThat(row.get(STATUS)).isNull();
    }

    @Test
    void cellThatIsNotTheStandInTupleFailsLoudly() {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put(FULL, "{\"raw\":true}");

        assertThatThrownBy(() -> complete(completer(8, 100), List.of(BOTH.getFirst()), List.of(row)))
                .isInstanceOf(IllegalStateException.class);
    }
}
