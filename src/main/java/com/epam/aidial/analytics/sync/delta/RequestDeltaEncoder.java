package com.epam.aidial.analytics.sync.delta;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Writes the request-delta columns of payload rows. Specified in {@code openspec/specs/data-sync/spec.md}: "The request
 * body storage mode is configurable", "A chat request body is stored as its new history and a parent link", "A
 * request inherits its parent's tools", "A parent never crosses a UTC day or a user scope", "Cache markers are kept per event" and "A request body that is not a
 * chat request is stored whole".
 *
 * <p>Runs once per flush of the payload sink, on the batch about to be inserted, after the mapping (and so after the
 * inline-file rewrite). Under {@link RequestBodyMode#WHOLE} it only stamps each chat request with its path, depth and
 * tools hash and keeps the body: a row stored whole then still supplies its path's history, so a delta row that names it as parent
 * stays rebuildable when its parent is re-ingested under {@code whole}, and a switch to {@code delta} links onto rows
 * written before it. Under {@link RequestBodyMode#DELTA} it computes the prefix hashes ({@link PathHash}) and takes as
 * parent the deepest prefix {@code pk}, {@code k < n}, that some stored request carries as its path, looking in order
 * at earlier requests of the same flush (by {@code request_time}), at the paths this instance has written
 * ({@link PathCache}), and at the payload table. The table is asked once per flush, only for the requests the first two
 * could not resolve at all, about their {@value #TABLE_CANDIDATES} deepest prefixes, and never about more than
 * {@value #MAX_TABLE_CANDIDATES} prefixes in all. A request is never its own parent ({@code k < n}): a rebuild reads
 * "any row carrying the parent's path", and a row taking its own path as parent could be the row found. Nor does a
 * parent ever end inside the system part of the history ({@code k} at least the request's system element count), so
 * a stored body either carries all of its system keys or inherits all of them.
 *
 * <p>The stored body is the request with its history cut to the elements after the parent's. Its {@code tools} are
 * left out too when they hash the same as those of a stored row carrying the parent's path; {@code request_inherited}
 * names every key left out, and {@code request_tools_hash} lets a rebuild find the tools again.
 *
 * <p>Finding no parent is not an error: the request is a chain root and costs space only. That includes a table lookup
 * that fails — it is logged and counted, and the requests it would have resolved become roots, so a read problem never
 * stops ingest.
 *
 * <p>Memory stays near one batch. Resolving parents needs every request's prefix hashes before any row is written, so
 * the first pass parses each chat body, keeps its hashes (16 bytes per history element, packed) and drops everything
 * else.
 * Writing a row parses its body again for the stored body and markers, which then live only until that row's
 * columns are set. A chat body is therefore parsed twice under {@code delta}, once under {@code whole}: that buys
 * not holding a second copy of the batch, as history strings and bodies, for the whole flush.
 *
 * <p>The caller inserts the rewritten batch and, only once that insert succeeded, calls {@link Encoding#written()},
 * so a path becomes a parent candidate for later flushes only once its row is stored. Rows in the same insert can
 * link to each other, since they land together.
 *
 * <p>Thread-safe: the only shared state is the {@link PathCache}.
 */
@Slf4j
public final class RequestDeltaEncoder {

    /**
     * Prefixes per unresolved request asked of the payload table. A typical parent is one to three elements shallower,
     * and every candidate weakens the path index's pruning, so only the deepest few are asked; a parent further back
     * than that is not looked for, and the request is a root.
     */
    static final int TABLE_CANDIDATES = 8;

    /** Prefixes asked of the payload table per flush, at most; requests beyond it are roots. */
    static final int MAX_TABLE_CANDIDATES = 16_384;

    /** Ancestor paths kept in {@code request_ancestors}, nearest first to go: a read hint, so it need not be complete. */
    static final int ANCESTORS_HINT = 128;

    static final String REQUEST_BODY = "request_body";
    static final String REQUEST_TIME = "request_time";
    static final String PROJECT_ID = "project_id";
    static final String USER_HASH = "user_hash";
    static final String REQUEST_PATH = "request_path";
    static final String REQUEST_PARENT = "request_parent";
    static final String REQUEST_DEPTH = "request_depth";
    static final String REQUEST_ANCESTORS = "request_ancestors";
    static final String REQUEST_TOOLS_HASH = "request_tools_hash";
    static final String REQUEST_INHERITED = "request_inherited";
    static final String REQUEST_CACHE_MARKERS = "request_cache_markers";

    /** The all-zero hash: a root's (and a whole body's) parent, and the tools hash of a request without tools. */
    static final UUID NO_PARENT = new UUID(0L, 0L);

    private static final String METER = "ingest.request.delta.rows";
    private static final String LOOKUP_FAILURES = "ingest.request.delta.lookup.failures";

    /** Exact floats, and a duplicate member fails the parse: kept, the last copy would silently replace the others. */
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();
    private final RequestBodyMode mode;
    private final ParentLookup lookup;
    private final PathCache cache;
    private final MeterRegistry registry;
    private final String table;

    /** An encoder under {@link RequestBodyMode#DELTA}. */
    public RequestDeltaEncoder(ParentLookup lookup, int cacheCapacity, MeterRegistry registry, String table) {
        this(RequestBodyMode.DELTA, lookup, cacheCapacity, registry, table);
    }

    /**
     * @param mode          {@code whole} stamps chat requests with their path only; {@code delta} also links and splits them
     * @param lookup        finds parents stored by earlier flushes, other runs or other instances; unused under {@code whole}
     * @param cacheCapacity how many written paths this instance remembers; unused under {@code whole}
     * @param registry      where the per-outcome row counter is registered
     * @param table         the payload table, tagging the counters
     */
    public RequestDeltaEncoder(RequestBodyMode mode, ParentLookup lookup, int cacheCapacity, MeterRegistry registry, String table) {
        this.mode = mode;
        this.lookup = lookup;
        this.cache = new PathCache(mode == RequestBodyMode.DELTA ? cacheCapacity : 0);
        this.registry = registry;
        this.table = table;
    }

    /**
     * Writes the request-delta columns of the chat requests among {@code rows}, in place. Under {@code delta} their
     * {@code request_body} is cut to what the parent does not supply; under {@code whole} it is kept. Every other row
     * is left untouched.
     *
     * @param rows the payload batch about to be inserted
     * @return what to record once the insert carrying {@code rows} has succeeded
     */
    public Encoding encode(List<Map<String, Object>> rows) {
        List<Item> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Item item = Item.of(mapper, row);
            if (item == null) {
                count("whole");
            } else if (mode == RequestBodyMode.WHOLE) {
                item.stampPath();
                count("whole_with_path");
            } else {
                items.add(item);
            }
        }
        if (items.isEmpty()) {
            return new Encoding(List.of());
        }
        items.sort(Comparator.comparing(Item::time));

        Map<UUID, ChainNode> stored = storedParents(items);
        Map<UUID, ChainNode> written = new HashMap<>();
        List<ChainNode> learned = new ArrayList<>();
        for (Item item : items) {
            Resolution resolution = resolve(item, written, stored);
            boolean split = item.write(mapper, resolution.parent(), resolution.depth());
            if (!split) {
                log.warn("A request body in {} parsed differently the second time; its row is stored whole with its path", table);
            }
            // A row left whole carries its path and supplies its history from the body: a parent like any other, with no
            // parent of its own.
            ChainNode node = ChainNode.written(item.path(), split ? resolution.parent() : null, item.toolsHash());
            if (written.putIfAbsent(item.path(), node) == null) {
                learned.add(node);
            }
            count(split ? resolution.source() : "whole_with_path");
        }
        return new Encoding(learned);
    }

    /**
     * The payload table's answer for the requests that neither an earlier request of the flush nor the cache can link
     * at all. {@code items} are in time order, so "earlier" matches what {@link #resolve} will see.
     */
    private Map<UUID, ChainNode> storedParents(List<Item> items) {
        Set<UUID> earlier = new HashSet<>();
        Set<UUID> candidates = new LinkedHashSet<>();
        Set<String> days = new LinkedHashSet<>();
        for (Item item : items) {
            boolean linkable = item.linkableWithin(earlier, cache);
            earlier.add(item.path());
            int depth = item.depth();
            int first = Math.max(item.shallowestParent(), depth - TABLE_CANDIDATES);
            if (linkable || first >= depth || candidates.size() + (depth - first) > MAX_TABLE_CANDIDATES) {
                continue;
            }
            for (int k = first; k < depth; k++) {
                candidates.add(item.prefix(k));
            }
            days.add(item.day());
        }
        if (candidates.isEmpty()) {
            return Map.of();
        }
        Map<UUID, ParentLookup.Found> found;
        try {
            found = lookup.find(days, candidates);
        } catch (RuntimeException ex) {
            registry.counter(LOOKUP_FAILURES, "table", table).increment();
            log.warn("Parent lookup in {} for {} candidate path(s) failed; the requests waiting on it are stored as chain roots",
                    table, candidates.size(), ex);
            return Map.of();
        }
        Map<UUID, ChainNode> stored = new HashMap<>();
        found.forEach((path, row) -> stored.put(path, ChainNode.stored(path, row)));
        return stored;
    }

    private Resolution resolve(Item item, Map<UUID, ChainNode> written, Map<UUID, ChainNode> stored) {
        for (int k = item.depth() - 1; k >= item.shallowestParent(); k--) {
            UUID prefix = item.prefix(k);
            ChainNode node = written.get(prefix);
            if (node != null) {
                return new Resolution(node, k, "flush");
            }
            node = cache.get(prefix);
            if (node != null) {
                return new Resolution(node, k, "cache");
            }
            node = stored.get(prefix);
            if (node != null) {
                return new Resolution(node, k, "table");
            }
        }
        return new Resolution(null, 0, "root");
    }

    private void count(String outcome) {
        registry.counter(METER, "table", table, "outcome", outcome).increment();
    }

    /** How a request was linked: its parent node (or {@code null} for a root), the parent's depth, and where it was found. */
    private record Resolution(ChainNode parent, int depth, String source) {
    }

    /** The outcome of {@link #encode}: the nodes to remember once the batch is stored. */
    public final class Encoding {

        private final Collection<ChainNode> learned;

        private Encoding(Collection<ChainNode> learned) {
            this.learned = learned;
        }

        /**
         * Records the batch's paths as parent candidates; call only after the insert carrying the batch succeeded. A
         * node read from the table enters the cache only as the parent of a node written here, never on its own, so
         * the cache holds one small entry per written row.
         */
        public void written() {
            cache.putAll(learned);
        }
    }

    /**
     * One chat request of the batch: its row and its prefix hashes. The split body is not kept; {@link #write} parses
     * the body again (see the class note on memory).
     */
    private static final class Item {

        private final Map<String, Object> row;
        private final String time;
        /** {@code p0..pn}, two longs each, so a hash costs 16 bytes rather than a {@link UUID} object and its slot. */
        private final long[] prefixes;
        private final int systemCount;
        private final UUID toolsHash;

        private Item(Map<String, Object> row, String time, UUID[] prefixes, int systemCount, UUID toolsHash) {
            this.row = row;
            this.time = time;
            this.systemCount = systemCount;
            this.toolsHash = toolsHash;
            this.prefixes = new long[prefixes.length * 2];
            for (int k = 0; k < prefixes.length; k++) {
                this.prefixes[2 * k] = prefixes[k].getMostSignificantBits();
                this.prefixes[2 * k + 1] = prefixes[k].getLeastSignificantBits();
            }
        }

        /** The row as a chat request, or {@code null} when its body is stored whole. */
        static Item of(JsonMapper mapper, Map<String, Object> row) {
            if (!(row.get(REQUEST_BODY) instanceof String body) || !(row.get(REQUEST_TIME) instanceof String time) || !utcText(time)) {
                return null;
            }
            ChatRequest request = ChatRequest.parse(mapper, body, false);
            if (request == null) {
                return null;
            }
            String scope = scope(row);
            return new Item(row, time, PathHash.prefixes(scope, time.substring(0, 10), request.canonicalHistory()),
                    request.systemCount(), request.toolsHash());
        }

        /**
         * The chain scope: project and user. Deployment is left out, so a user who switches between deployments that
         * take the same body format keeps one chain; the history hash alone decides what links.
         */
        static String scope(Map<String, Object> row) {
            String project = String.valueOf(row.get(PROJECT_ID));
            // Length-prefixed, so no project id or user hash, whatever characters it holds, can shift the boundary.
            return project.length() + ":" + project + String.valueOf(row.get(USER_HASH));
        }

        /**
         * Whether {@code time} is offset-free or {@code Z} text, whose leading date is then its UTC partition day. A
         * time with any other offset is stored whole rather than hashed under a day that need not be its partition's.
         */
        static boolean utcText(String time) {
            if (time.length() < 10) {
                return false;
            }
            String clock = time.substring(10);
            return clock.indexOf('+') < 0 && clock.indexOf('-') < 0;
        }

        String time() {
            return time;
        }

        /**
         * The UTC day of {@code request_time}, as rendered by the mapping ({@code yyyy-MM-dd HH:mm:ss.SSS}). The column
         * stores that offset-free text as UTC, so its leading date is the row's partition day.
         */
        String day() {
            return time.substring(0, 10);
        }

        int depth() {
            return prefixes.length / 2 - 1;
        }

        UUID prefix(int k) {
            return new UUID(prefixes[2 * k], prefixes[2 * k + 1]);
        }

        UUID path() {
            return prefix(depth());
        }

        /** The request's tools hash, or {@code null} when it carries no tools. */
        UUID toolsHash() {
            return toolsHash;
        }

        /** The shallowest depth a parent may have: past the first element, and past the system part of the history. */
        int shallowestParent() {
            return Math.max(1, systemCount);
        }

        /** Whether some allowed prefix is a path of an earlier request of the flush or of the cache. */
        boolean linkableWithin(Set<UUID> earlier, PathCache cache) {
            for (int k = depth() - 1; k >= shallowestParent(); k--) {
                UUID prefix = prefix(k);
                if (earlier.contains(prefix) || cache.get(prefix) != null) {
                    return true;
                }
            }
            return false;
        }

        /** Under {@code whole}: the row keeps its body and carries its path, depth and tools hash, and is never linked. */
        void stampPath() {
            row.put(REQUEST_PATH, path().toString());
            row.put(REQUEST_DEPTH, (long) depth());
            row.put(REQUEST_TOOLS_HASH, (toolsHash == null ? NO_PARENT : toolsHash).toString());
        }

        /**
         * Sets the row's chain columns for {@code parent} (a root when {@code null}) at {@code parentDepth} and cuts its
         * body to what the parent does not supply, parsing the body again. The parse is deterministic and nothing else in
         * {@link #encode} touches the row, so its history is the one the prefixes were hashed from. Should it ever
         * differ, the row keeps its body and only carries its path, which still supplies the path's history to a child:
         * a surprise costs space, never a failed flush. A row passed twice keeps what its first write stored.
         *
         * @return {@code false} when the row was left whole instead
         */
        boolean write(JsonMapper mapper, ChainNode parent, int parentDepth) {
            ChatRequest request = row.get(REQUEST_BODY) instanceof String body ? ChatRequest.parse(mapper, body, true) : null;
            // A row already carrying a parent was written by an earlier pass: its body is the stored one, not the sent one.
            if (request == null || request.canonicalHistory().size() != depth() || row.containsKey(REQUEST_PARENT)) {
                if (row.get(REQUEST_BODY) instanceof String) {
                    stampPath();
                }
                return false;
            }
            boolean inheritTools = parent != null && toolsHash != null && parent.carriesTools(toolsHash);
            stampPath();
            row.put(REQUEST_PARENT, (parent == null ? NO_PARENT : parent.path()).toString());
            List<UUID> lineage = parent == null ? List.of() : parent.lineage(ANCESTORS_HINT);
            row.put(REQUEST_ANCESTORS, lineage.stream().map(UUID::toString).toList());
            row.put(REQUEST_BODY, request.stored(parentDepth, inheritTools));
            row.put(REQUEST_INHERITED, request.inherited(parentDepth, inheritTools));
            row.put(REQUEST_CACHE_MARKERS, markers(request));
            return true;
        }

        private static String markers(ChatRequest request) {
            if (request.markers().isEmpty()) {
                return null;
            }
            ArrayNode markers = JsonNodeFactory.instance.arrayNode();
            request.markers().forEach(marker -> markers.add(marker.toJson()));
            return ChatRequest.escapeLoneSurrogates(markers.toString());
        }
    }
}
