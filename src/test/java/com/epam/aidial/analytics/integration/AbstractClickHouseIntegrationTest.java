package com.epam.aidial.analytics.integration;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.QueryResponse;
import com.clickhouse.client.api.query.QuerySettings;
import com.clickhouse.data.ClickHouseFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Base for the structured-query integration tests. Boots the full Spring Boot application
 * ({@code @SpringBootTest} + {@code @AutoConfigureMockMvc}) against a <em>real</em> ClickHouse running
 * in a Testcontainers container, so the path under test is the production one end to end:
 * {@code MockMvc -> controller -> StructuredQueryService -> CatalogQueryService ->
 * StructuredQueryExecutor -> ClickHouseClientWrapper -> ClickHouse} (with the catalog backfill
 * providing {@code dial_usage_log} as a materialized source).
 *
 * <p>The container is a <strong>singleton</strong> owned by {@link ClickHouseContainerSupport} (started
 * once, reused across every integration-test class, removed by Testcontainers' Ryuk reaper on JVM
 * exit), so the Spring context is shared via the test-context cache. Its host/port and credentials are
 * wired into {@code clickhouse.*} via {@link DynamicPropertySource}; the shared PostgreSQL container is
 * wired in alongside it since the full context now needs a {@code DataSource}. Migrations are left
 * <strong>enabled</strong> so the real {@code ClickHouseMigrationRunner}/Flyway provisions
 * {@code dial_usage_log} on startup — that provisioning is part of the plumbing under test.
 *
 * <p><strong>Data isolation:</strong> there is no shared fixture. Each test owns its data — it calls
 * {@link #truncate()} (typically from a {@code @BeforeEach}) for a clean slate and then inserts only the
 * rows it asserts on via {@link #fixture()}. Writes go through a dedicated in-JVM client-v2
 * {@link Client} (the same API the production {@code UsageLogWriter} uses), deliberately separate from
 * the read-only {@code ClickHouseClientWrapper} the API exercises. Writing in-JVM (rather than via
 * {@code execInContainer}) keeps per-test seeding cheap. Tests run serially, so the clean-slate approach
 * is safe.
 *
 * <p>Requires a running Docker daemon.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
abstract class AbstractClickHouseIntegrationTest {

    /**
     * The property overrides the enrichment integration classes share. They are listed as constants, and
     * every one of those classes declares the <em>same</em> set, because a {@code @TestPropertySource} set
     * is part of the test-context cache key: two classes with identical sets share one Spring context, and
     * each distinct set costs another context — another JDBC pool against the shared PostgreSQL container,
     * whose connection ceiling the full suite already runs close to. Adding a per-class override here is
     * therefore not free; prefer a fixture that works under these values.
     *
     * <p>Safety-lag 0 makes past-dated fixture rows immediately visible to a scan. The lowered
     * group-assembly byte cap lets a group of body-carrying members trip the cap with a few KB instead of
     * megabytes, while a group of ordinary small rows stays far below it.
     */
    static final String SCAN_SAFETY_LAG_OFF = "enrichment.scan.safety-lag=0s";

    /**
     * The date every fixture row's {@code request_time} falls on: <b>today</b>, computed at class load.
     *
     * <p>Two constraints meet here, and a fixed literal cannot satisfy both.
     *
     * <p>{@code dial_usage_log_payload} carries a 90-day row TTL with {@code ttl_only_drop_parts=1}, so a
     * payload part whose newest row is older than that is dropped by the first TTL merge after it lands —
     * within seconds, on a freshly created table with no recorded TTL-merge time. A fixture dated in the
     * past therefore seeds a body that has already vanished by the time the assertion reads it, and the
     * failure looks exactly like a broken join.
     *
     * <p>And the payload row must carry the <em>same</em> {@code request_time} as its parent, because the
     * two are partition-aligned in the catalog: a keyed grain join bounds the payload side by the parent's
     * value, so a fixture that stamps its own clock on one side loses the row to that bound. Real ingest
     * writes both from one record's {@code request.time}, so agreeing here is what makes the fixture
     * faithful rather than merely passing.
     *
     * <p>Together those rule out "date both rows in the past" and "date them independently". Dating both on
     * today satisfies both, and computing it rather than writing it down is what stops the suite from
     * starting to fail 90 days after someone picks a literal.
     */
    static final String FIXTURE_DATE = LocalDate.now(ZoneOffset.UTC).toString();

    /** {@link #FIXTURE_DATE} at {@code timeOfDay} (e.g. {@code "00:00:01.000"}), unquoted. */
    static String fixtureTime(String timeOfDay) {
        return FIXTURE_DATE + " " + timeOfDay;
    }

    /** See {@link #SCAN_SAFETY_LAG_OFF}. */
    static final String GROUP_FETCH_MAX_BYTES_4KB = "enrichment.group-fetch.max-bytes=4KB";

    /**
     * The aggregate bounds, part of the <em>same</em> shared set as the two above and declared alongside
     * them by every class that needs any of the four — including the enrichment classes, which need none
     * of the aggregate ones. That is deliberate: the set is the context-cache key, so one shared set costs
     * one context while two nearly-identical sets cost two. Splitting these into their own set exhausted
     * the shared PostgreSQL container's connection ceiling outright
     * ({@code FATAL: sorry, too many clients already}) and took unrelated integration classes down with
     * it. Adding aggregate properties to the enrichment classes' context is inert — those tests read none
     * of them — and is much cheaper than a second context.
     *
     * <p>All three are unreachable under the shipped defaults, which is why they are overridden rather
     * than fixtured around. The window limit ships at 100,000, so the only fixture that fills a window
     * under the real value is 100,000 rows; the preview memory ceiling ships at 2 GB, which no test-sized
     * aggregation can exceed. The spill threshold is 0 (disabled), so a preview refusal is the memory
     * ceiling rather than an external-aggregation failure.
     */
    static final String AGGREGATE_WINDOW_LIMIT_3 = "aggregate.window-limit=3";

    /** See {@link #AGGREGATE_WINDOW_LIMIT_3}. */
    static final String AGGREGATE_PREVIEW_MEMORY_1KB = "aggregate.preview.max-memory=1KB";

    /** See {@link #AGGREGATE_WINDOW_LIMIT_3}. */
    static final String AGGREGATE_PREVIEW_SPILL_OFF = "aggregate.preview.spill-threshold=0B";

    private static final String TABLE = "dial_usage_log";

    /**
     * The second ingest target, written alongside {@link #TABLE} by the data-sync payload mapping. No
     * query surface reads it (it has no catalog row), but it must be emptied with its parent so an
     * ingest test never sees a previous test's payload rows.
     */
    private static final String PAYLOAD_TABLE = "dial_usage_log_payload";

    /** In-JVM writer for test fixtures; built lazily on first use, shared for the JVM lifetime. */
    private static volatile Client writeClient;

    @Autowired
    protected MockMvc mockMvc;

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        ClickHouseContainerSupport.registerProperties(registry);
        PostgresContainerSupport.registerProperties(registry);
    }

    /**
     * Empties {@code dial_usage_log} and its payload sibling so the calling test starts from clean,
     * known-empty tables.
     */
    protected void truncate() {
        writeClient().queryAll("TRUNCATE TABLE " + TABLE);
        writeClient().queryAll("TRUNCATE TABLE " + PAYLOAD_TABLE);
    }

    /**
     * Runs an arbitrary statement through the in-JVM write client. Lets a test seed or clear a table
     * other than {@code dial_usage_log} (e.g. {@code rate_analytics}) that the {@link Fixture} builder
     * does not model, so a full ingest→store→query round-trip can be exercised against real ClickHouse.
     */
    protected void execute(String sql) {
        writeClient().queryAll(sql);
    }

    /**
     * A stable {@code event_id} for a seed row, derived from its trace id.
     *
     * <p>Seeds that write payload columns have to set {@code event_id} explicitly on both tables — it
     * is the key the catalog's grain join matches on, and left to the column's zero value every row
     * would share the all-zero UUID and the join would pair every payload row with every usage-log
     * row. Deriving it from the trace id keeps the two inserts in agreement without threading an id
     * through the call.
     */
    protected static String eventIdFor(String traceId) {
        return UUID.nameUUIDFromBytes(traceId.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * Seeds the payload row belonging to the usage-log row with this trace id, at
     * {@link #FIXTURE_DATE}{@code  00:00:00.000}.
     *
     * <p>It used to stamp {@code now()}, on the reasoning that the two tables are joined on
     * {@code event_id} and never on time. That reasoning no longer holds: the two are partition-aligned in
     * the catalog, so a keyed grain join bounds the payload side by the parent's {@code request_time} and a
     * payload row stamped with its own clock is excluded by that bound — it reads back as NULL, which looks
     * exactly like a row that has no payload.
     *
     * <p>So the parent row must carry this same value. That is why {@link #FIXTURE_DATE} is today's date
     * rather than a historical one: a past-dated pair satisfies the join but is dropped by the payload
     * table's 90-day TTL before the assertion runs.
     *
     * @param columns physical payload column names, comma-separated (e.g. {@code "jwt_claims"})
     * @param values  their SQL literals, in the same order and already quoted
     */
    protected void seedPayload(String traceId, String columns, String values) {
        execute("INSERT INTO " + PAYLOAD_TABLE + " (event_id, request_time, trace_id, core_span_id, "
                + columns + ") VALUES (toUUID('" + eventIdFor(traceId) + "'), '" + fixtureTime("00:00:00.000")
                + "', '" + traceId + "', '', " + values + ")");
    }

    /** The table's live ClickHouse DDL ({@code SHOW CREATE TABLE}), for asserting physical clauses like TTL. */
    protected String showCreateTable(String table) {
        return writeClient().queryAll("SHOW CREATE TABLE " + table).getFirst().getString(1);
    }

    /**
     * Reads {@code column} from the first row of {@code sql}, as text. For a test that has to run a
     * statement ADAS produced and compare the result against what ADAS reported — asserting a rendered
     * statement is the one that produced a given answer, rather than merely that it looks plausible.
     */
    protected String queryScalar(String sql, String column) {
        return String.valueOf(writeClient().queryAll(sql).getFirst().getString(column));
    }

    /**
     * The whole result of {@code sql} as ClickHouse renders it in {@code TabSeparatedWithNamesAndTypes}: column
     * names, column types and every value, byte for byte. Two statements whose texts are equal returned the same
     * result, NULLs and type defaults included, which is what a statement-rewrite test needs to compare.
     */
    protected String queryText(String sql) {
        final QuerySettings settings = new QuerySettings().setFormat(ClickHouseFormat.TabSeparatedWithNamesAndTypes);
        try (QueryResponse response = writeClient().query(sql, settings).get(60, TimeUnit.SECONDS)) {
            return new String(response.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("query failed: " + sql, ex);
        }
    }

    /** Starts a fixture for this test; add rows then {@link Fixture#insert()}. */
    protected Fixture fixture() {
        return new Fixture();
    }

    private static Client writeClient() {
        Client local = writeClient;
        if (local == null) {
            synchronized (AbstractClickHouseIntegrationTest.class) {
                local = writeClient;
                if (local == null) {
                    final var container = ClickHouseContainerSupport.CLICKHOUSE;
                    local = new Client.Builder()
                            .addEndpoint("http://" + container.getHost() + ":"
                                    + container.getMappedPort(ClickHouseContainerSupport.HTTP_PORT) + "/")
                            .setUsername(container.getUsername())
                            .setPassword(container.getPassword())
                            .setDefaultDatabase(container.getDatabaseName())
                            .build();
                    writeClient = local;
                }
            }
        }
        return local;
    }

    /**
     * Fluent builder for {@code dial_usage_log} fixture rows. Each {@link Row} sets only the columns a
     * test cares about; everything else keeps an inert default ({@code request_time} a fixed constant,
     * ids/text empty, token counts 0, {@code _ingested_at} the table's {@code DEFAULT now()}) — except
     * {@code trace_id}, which defaults to a per-row unique value so the table's {@code ReplacingMergeTree}
     * engine does not collapse otherwise-identical rows on its {@code (…, trace_id, core_span_id)} key.
     * All rows insert in one statement.
     */
    protected static final class Fixture {

        private static final String COLUMNS =
                "(event_id, request_time, event_kind, project_id, user_hash, chat_id, trace_id, deployment, "
                        + "prompt_tokens, completion_tokens, total_tokens)";

        /**
         * The claim/header columns moved to dial_usage_log_payload, so a row that sets either needs a
         * second insert on the other table, keyed on the same {@code event_id}.
         */
        private static final String PAYLOAD_COLUMNS =
                "(event_id, request_time, trace_id, core_span_id, jwt_claims, request_tags)";

        private final List<Row> rows = new ArrayList<>();

        /** Adds a fully-specified row via the {@link Row} builder. */
        public Fixture add(Consumer<Row> spec) {
            final Row row = new Row();
            spec.accept(row);
            rows.add(row);
            return this;
        }

        /** Convenience for the common case: {@code event_kind}, {@code project_id}, {@code total_tokens}. */
        public Fixture row(String eventKind, String projectId, long totalTokens) {
            return add(r -> r.eventKind(eventKind).projectId(projectId).totalTokens(totalTokens));
        }

        /** One row whose {@code deployment} carries the given text — for text-shaped assertions. */
        public Fixture rowWithDeployment(String deployment) {
            return add(r -> r.eventKind("chat").projectId("p1").totalTokens(1).deployment(deployment));
        }

        /** Inserts all accumulated rows in a single in-JVM statement (no-op if empty). */
        public void insert() {
            if (rows.isEmpty()) {
                return;
            }
            final List<String> tuples = new ArrayList<>(rows.size());
            final List<String> payloadTuples = new ArrayList<>();
            for (final Row row : rows) {
                tuples.add(row.tuple());
                if (row.hasPayload()) {
                    payloadTuples.add(row.payloadTuple());
                }
            }
            writeClient().queryAll("INSERT INTO " + TABLE + " " + COLUMNS + " VALUES " + String.join(",", tuples));
            if (!payloadTuples.isEmpty()) {
                writeClient().queryAll("INSERT INTO " + PAYLOAD_TABLE + " " + PAYLOAD_COLUMNS
                        + " VALUES " + String.join(",", payloadTuples));
            }
        }
    }

    /** A single {@code dial_usage_log} fixture row; unset columns keep inert defaults. */
    protected static final class Row {

        /** Assigns each row a distinct default {@code trace_id} so ReplacingMergeTree never dedups fixtures. */
        private static final AtomicLong TRACE_SEQ = new AtomicLong();

        /** One sequence number per row, backing both the trace id and the event id below. */
        private final long seq = TRACE_SEQ.incrementAndGet();

        /**
         * Distinct per row, because it is the key the payload enrichment is joined on. Left to the
         * column's zero value it would be the all-zero UUID on every row, and the grain join would then
         * match every payload row to every usage-log row.
         */
        private final String eventId = String.format("00000000-0000-0000-0000-%012d", seq);

        private String requestTime = "2026-01-01 00:00:00.000";
        private String eventKind = "";
        private String projectId = "";
        private String userHash = "";
        private String chatId = "";
        private String traceId = "trace-" + seq;
        private String deployment = "";
        private long promptTokens;
        private long completionTokens;
        private long totalTokens;
        private String jwtClaims = "{}";
        private String requestTags = "{}";

        /**
         * Whether this row needs a {@code dial_usage_log_payload} row at all. Only a row that sets a
         * claim or header document gets one, and that is deliberate rather than a shortcut: a row
         * without one is left UNMATCHED by the grain join, which renders both columns as the empty
         * document — the exact value the old non-nullable columns held by default before they moved.
         * Every assertion written against the old default therefore still holds, and the fixtures
         * exercise the unmatched-join path for free on every row that does not care about claims.
         */
        private boolean payloadSeeded;

        /** Sets {@code request_time} as a ClickHouse datetime literal, e.g. {@code 2026-06-01 10:00:00.000}. */
        public Row requestTime(String value) {
            this.requestTime = value;
            return this;
        }

        public Row eventKind(String value) {
            this.eventKind = value;
            return this;
        }

        public Row projectId(String value) {
            this.projectId = value;
            return this;
        }

        public Row userHash(String value) {
            this.userHash = value;
            return this;
        }

        public Row chatId(String value) {
            this.chatId = value;
            return this;
        }

        public Row traceId(String value) {
            this.traceId = value;
            return this;
        }

        public Row deployment(String value) {
            this.deployment = value;
            return this;
        }

        public Row promptTokens(long value) {
            this.promptTokens = value;
            return this;
        }

        public Row completionTokens(long value) {
            this.completionTokens = value;
            return this;
        }

        public Row totalTokens(long value) {
            this.totalTokens = value;
            return this;
        }

        /**
         * The record's top-level JWT claims, as a JSON document. Stored in the {@code object}-typed
         * {@code jwt_claims} column, so a test that needs a native ClickHouse {@code JSON} operand — the
         * extraction functions all render differently against one — has a way to seed it.
         */
        public Row jwtClaims(String json) {
            this.jwtClaims = json;
            this.payloadSeeded = true;
            return this;
        }

        /** The record's request headers, as a JSON document; the {@code object}-typed {@code request_tags}. */
        public Row requestTags(String json) {
            this.requestTags = json;
            this.payloadSeeded = true;
            return this;
        }

        /** This row's {@code event_id}, for a test that needs to seed or address its payload row. */
        public String eventId() {
            return eventId;
        }

        private String tuple() {
            return "('" + eventId + "', '" + escape(effectiveRequestTime()) + "', '" + escape(eventKind)
                    + "', '" + escape(projectId)
                    + "', '" + escape(userHash) + "', '" + escape(chatId) + "', '" + escape(traceId)
                    + "', '" + escape(deployment) + "', " + promptTokens + ", " + completionTokens
                    + ", " + totalTokens + ")";
        }

        private boolean hasPayload() {
            return payloadSeeded;
        }

        /**
         * The matching payload row, carrying the <em>same</em> {@code request_time} as its usage-log row
         * — {@link #effectiveRequestTime()}, so the two cannot drift.
         *
         * <p>It used to be stamped {@code now64(3)} instead, on the reasoning that the two are joined on
         * {@code event_id} and never on time. That reasoning no longer holds: the tables are
         * partition-aligned in the catalog, so a keyed grain join bounds the payload side by the parent's
         * value and a payload row with its own timestamp is excluded by that bound — it reads back as
         * NULL, indistinguishable from a row that has no payload. Nothing fails; the body just goes
         * quietly missing.
         */
        private String payloadTuple() {
            return "('" + eventId + "', '" + escape(effectiveRequestTime()) + "', '" + escape(traceId) + "', ''"
                    + ", '" + escape(jwtClaims) + "', '" + escape(requestTags) + "')";
        }

        /**
         * The {@code request_time} both of this row's tables get: its declared one, or the same time of day
         * on {@link #FIXTURE_DATE} when it seeds a payload.
         *
         * <p>A payload row must satisfy two constraints its parent does not. It must equal the parent's
         * value, because of the partition alignment above; and it must be recent, because the payload
         * table's 90-day TTL with {@code ttl_only_drop_parts=1} drops a past-dated part almost as soon as
         * it lands. The fixtures' default {@code request_time} is historical and several tests assert on
         * it, so the date is moved for the rows that seed a payload and left alone for the rest.
         *
         * <p>Derived at render time rather than in {@link #jwtClaims} / {@link #requestTags} so it does not
         * depend on the order the builder's setters are called in.
         */
        private String effectiveRequestTime() {
            if (!payloadSeeded) {
                return requestTime;
            }
            final int timeAt = requestTime.indexOf(' ');
            return timeAt < 0 ? fixtureTime(requestTime) : fixtureTime(requestTime.substring(timeAt + 1));
        }

        private static String escape(String value) {
            return value == null ? "" : value.replace("\\", "\\\\").replace("'", "\\'");
        }
    }
}
