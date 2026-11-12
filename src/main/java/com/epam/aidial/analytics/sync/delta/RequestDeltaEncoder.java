package com.epam.aidial.analytics.sync.delta;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Writes the request-delta columns of payload rows. Specified in {@code openspec/specs/data-sync/spec.md}: "The request
 * body storage mode is configurable", "A chat request body is stored as its new history and a parent link", "A
 * request inherits its parent's tools", "A parent never crosses a UTC day or a user scope", "Cache markers are kept per event" and "A request body that is not a
 * chat request is stored whole".
 *
 * <p>Runs once per flush of the payload sink, on the batch about to be inserted, after the mapping (and so after the
 * inline-file rewrite); never under {@link RequestBodyMode#WHOLE}, which parses no body. Under
 * {@link RequestBodyMode#WHOLE_WITH_PATH} it only stamps each chat request with its path and keeps the body: a row
 * stored whole then still supplies its path's history, so a delta row that names it as parent stays rebuildable when
 * its parent is re-ingested under {@code whole-with-path}, and a switch to {@code delta} links onto rows written
 * before it. Under {@link RequestBodyMode#DELTA} it computes the prefix hashes ({@link PathHash}) and takes as
 * parent the deepest prefix {@code pk}, {@code k < n}, that some stored request carries as its path, looking in order
 * at earlier requests of the same flush (by {@code request_time}), at the paths this instance has written
 * ({@link PathCache}), and at the payload table. The table is asked once per flush, only for the requests the first two
 * could not resolve at all, about their {@value #TABLE_CANDIDATES} deepest prefixes, and never about more than
 * {@value #MAX_TABLE_CANDIDATES} prefixes in all. A request is never its own parent ({@code k < n}): a rebuild reads
 * "any row carrying the parent's path", and a row taking its own path as parent could be the row found. Nor does a
 * parent ever end inside the system part of the history ({@code k} at least the request's system element count), so
 * a stored body either carries all of its system keys or inherits all of them.
 *
 * <p>The stored body is the request with its history cut to the elements after the parent's. The prefix hashes cover
 * the system keys by name and the {@code tools}, so every request on a chain has the same ones: a child leaves them
 * out, and a rebuild copies them from the chain's root. A request whose tools changed finds no parent and is a root.
 *
 * <p>A row with neither a project nor a user has no scope to chain within, so it is stored whole with no path: chaining
 * it would put every such row of every tenant in one scope.
 *
 * <p>Finding no parent is not an error: the request is a chain root and costs space only. That includes a table lookup
 * that fails — it is logged and counted, and the requests it would have resolved become roots, so a read problem never
 * stops ingest.
 *
 * <p>Memory stays near one batch. Resolving parents needs every request's prefix hashes before any row is written, so
 * the first pass parses each chat body, keeps its hashes (16 bytes per history element, packed) and drops everything
 * else.
 * Writing a row parses its body again for the stored body and markers, which then live only until that row's
 * columns are set. A chat body is therefore parsed twice under {@code delta}, once under {@code whole-with-path}: that buys
 * not holding a second copy of the batch, as history strings and bodies, for the whole flush.
 *
 * <p>The caller inserts the rewritten batch and, only once that insert succeeded, calls {@link Encoding#written()},
 * so a path becomes a parent candidate for later flushes only once its row is stored. Rows in the same insert can
 * link to each other, since they land together. A batch the caller has to split into several inserts no longer lands
 * together, so before splitting it the caller asks {@link Encoding#isolate} to re-root every row linked to a row of
 * another part: each part then links only within itself or to rows already stored, and a part that is not stored leaves
 * no stored child naming its rows. For that the original body of each row linked within the flush is kept until the
 * batch is stored. Only rows of the current or the previous UTC day enter the cache: a
 * chain never crosses a day, so an older row can never be the parent of live traffic, and a backfill of past days
 * would otherwise push the live paths out.
 *
 * <p>Thread-safe: the only shared state is the {@link PathCache}.
 */
@Slf4j
public final class RequestDeltaEncoder {

    /**
     * Prefixes per unresolved request asked of the payload table. A typical parent is one to three elements shallower,
     * and every candidate weakens the hash index's pruning, so only the deepest few are asked; a parent further back
     * than that is not looked for, and the request is a root.
     */
    static final int TABLE_CANDIDATES = 8;

    /** Prefixes asked of the payload table per flush, at most; requests beyond it are roots. */
    static final int MAX_TABLE_CANDIDATES = 16_384;

    /** Ancestor paths kept in {@code request_ancestor_hashes}, nearest first to go: a read hint, so it need not be complete. */
    static final int ANCESTORS_HINT = 128;

    static final String REQUEST_BODY = "request_body";
    static final String REQUEST_TIME = "request_time";
    static final String PROJECT_ID = "project_id";
    static final String USER_HASH = "user_hash";
    static final String REQUEST_HASH = "request_hash";
    static final String REQUEST_PARENT_HASH = "request_parent_hash";
    static final String REQUEST_ANCESTOR_HASHES = "request_ancestor_hashes";
    static final String REQUEST_CACHE_CONTROL = "request_cache_control";

    /** The all-zero hash: a root's (and a whole body's) parent. */
    static final UUID NO_PARENT = new UUID(0L, 0L);

    private static final String METER = "ingest.request.delta.rows";
    /** The resolution source of a parent found among the earlier requests of the same flush. */
    private static final String FLUSH = "flush";
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
    private final Clock clock;

    /** An encoder under {@link RequestBodyMode#DELTA}. */
    public RequestDeltaEncoder(ParentLookup lookup, int cacheCapacity, MeterRegistry registry, String table) {
        this(RequestBodyMode.DELTA, lookup, cacheCapacity, registry, table);
    }

    /** An encoder on the system clock. */
    public RequestDeltaEncoder(RequestBodyMode mode, ParentLookup lookup, int cacheCapacity, MeterRegistry registry, String table) {
        this(mode, lookup, cacheCapacity, registry, table, Clock.systemUTC());
    }

    /**
     * @param mode          {@code whole-with-path} stamps chat requests with their path only; {@code delta} also links and
     *                      splits them. Not {@code whole}, which needs no encoder
     * @param lookup        finds parents stored by earlier flushes, other runs or other instances; unused under
     *                      {@code whole-with-path}
     * @param cacheCapacity how many written paths this instance remembers; unused under {@code whole-with-path}
     * @param registry      where the per-outcome row counter is registered
     * @param table         the payload table, tagging the counters
     * @param clock         decides which UTC days are recent enough to cache
     */
    public RequestDeltaEncoder(RequestBodyMode mode, ParentLookup lookup, int cacheCapacity, MeterRegistry registry, String table,
                               Clock clock) {
        if (mode == RequestBodyMode.WHOLE) {
            throw new IllegalArgumentException("sync.request-body-mode=whole parses no body and needs no encoder");
        }
        this.mode = mode;
        this.lookup = lookup;
        this.cache = new PathCache(mode == RequestBodyMode.DELTA ? cacheCapacity : 0);
        this.registry = registry;
        this.table = table;
        this.clock = clock;
    }

    /**
     * Writes the request-delta columns of the chat requests among {@code rows}, in place. Under {@code delta} their
     * {@code request_body} is cut to what the parent does not supply; under {@code whole-with-path} it is kept. Every other row
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
            } else if (mode == RequestBodyMode.WHOLE_WITH_PATH) {
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
        // The row whose node `written` holds for each path: the parent row of a request linked within the flush.
        Map<UUID, Map<String, Object>> writtenRows = new HashMap<>();
        // Each learned node with the row that carries its path, so a row that ends up not stored teaches nothing.
        List<Learned> learned = new ArrayList<>();
        List<Link> links = new ArrayList<>();
        String oldestCached = LocalDate.now(clock).minusDays(1).toString();
        for (Item item : items) {
            Resolution resolution = resolve(item, written, stored);
            Object original = item.row.get(REQUEST_BODY);
            boolean split = item.write(mapper, resolution.parent(), resolution.depth());
            if (!split) {
                log.warn("A request body in {} parsed differently the second time; its row is stored whole with its path", table);
            }
            // A row left whole carries its path and supplies its history from the body: a parent like any other, with no
            // parent of its own.
            ChainNode node = ChainNode.written(item.path(), split ? resolution.parent() : null);
            if (split && FLUSH.equals(resolution.source())) {
                links.add(new Link(item.row, writtenRows.get(resolution.parent().path()), (String) original, item.path()));
            }
            if (written.putIfAbsent(item.path(), node) == null) {
                writtenRows.put(item.path(), item.row);
                if (item.day().compareTo(oldestCached) >= 0) {
                    learned.add(new Learned(node, item.row));
                }
            }
            count(split ? resolution.source() : "whole_with_path");
        }
        return new Encoding(learned, links);
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
        Map<UUID, List<UUID>> found;
        try {
            found = lookup.find(days, candidates);
        } catch (RuntimeException ex) {
            registry.counter(LOOKUP_FAILURES, "table", table).increment();
            log.warn("Parent lookup in {} for {} candidate path(s) failed; the requests waiting on it are stored as chain roots",
                    table, candidates.size(), ex);
            return Map.of();
        }
        Map<UUID, ChainNode> stored = new HashMap<>();
        found.forEach((path, ancestors) -> stored.put(path, ChainNode.stored(path, ancestors)));
        return stored;
    }

    private Resolution resolve(Item item, Map<UUID, ChainNode> written, Map<UUID, ChainNode> stored) {
        for (int k = item.depth() - 1; k >= item.shallowestParent(); k--) {
            UUID prefix = item.prefix(k);
            ChainNode node = written.get(prefix);
            if (node != null) {
                return new Resolution(node, k, FLUSH);
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

    /** A node to remember once the batch is stored, with the row that carries its path. */
    private record Learned(ChainNode node, Map<String, Object> row) {
    }

    /**
     * A request linked to the row of an earlier request of the same flush, with the body it was sent with, so it can be
     * re-rooted should the two land in different inserts.
     */
    private record Link(Map<String, Object> child, Map<String, Object> parent, String original, UUID path) {
    }

    /** The outcome of {@link #encode}: the nodes to remember once the batch is stored. */
    public final class Encoding {

        private final List<Learned> learned;
        private final List<Link> links;
        /** The rows {@link #isolate} re-rooted or re-linked, with their new nodes, compared by identity. */
        private final Map<Map<String, Object>, ChainNode> relinked = new IdentityHashMap<>();

        private Encoding(List<Learned> learned) {
            this(learned, List.of());
        }

        private Encoding(List<Learned> learned, List<Link> links) {
            this.learned = learned;
            this.links = links;
        }

        /**
         * Prepares the batch for being inserted in parts: every row linked to the row of an earlier request of the flush
         * that lies in another part is re-rooted — stored whole, in canonical form, with no parent — so no stored row can
         * name a row that a refused part kept out of the table. A row linked within its own part keeps its link, its
         * ancestors hint following any re-rooting above it. A row linked to a row the cache or the table supplied is
         * linked to a stored row already and is left as it is. Call before any part is inserted, at most once.
         *
         * @param partOf the part each row of the batch is inserted with, compared with {@link Objects#equals}
         * @return how many rows were re-rooted
         */
        public int isolate(Function<Map<String, Object>, ?> partOf) {
            int rerooted = 0;
            // Links are in time order, so a parent's new node is known before any of its children is visited.
            for (Link link : links) {
                if (!Objects.equals(partOf.apply(link.child()), partOf.apply(link.parent()))) {
                    reroot(link);
                    relinked.put(link.child(), ChainNode.written(link.path(), null));
                    rerooted++;
                    continue;
                }
                ChainNode parent = relinked.get(link.parent());
                if (parent != null) {
                    link.child().put(REQUEST_ANCESTOR_HASHES, parent.lineage(ANCESTORS_HINT).stream().map(UUID::toString).toList());
                    relinked.put(link.child(), ChainNode.written(link.path(), parent));
                }
            }
            if (rerooted > 0) {
                registry.counter(METER, "table", table, "outcome", "rerooted").increment(rerooted);
                log.info("Re-rooted {} request(s) in {} linked across the parts of a batch inserted in parts", rerooted, table);
            }
            return rerooted;
        }

        private void reroot(Link link) {
            Map<String, Object> row = link.child();
            ChatRequest request = ChatRequest.parse(mapper, link.original(), true);
            row.put(REQUEST_PARENT_HASH, NO_PARENT.toString());
            row.put(REQUEST_ANCESTOR_HASHES, List.of());
            if (request == null) {
                // Parsed twice already, so this cannot happen; should it, the row is stored as it was sent, a root.
                row.put(REQUEST_BODY, link.original());
                row.put(REQUEST_CACHE_CONTROL, null);
                return;
            }
            row.put(REQUEST_BODY, request.stored(0));
            row.put(REQUEST_CACHE_CONTROL, Item.markers(request, 0));
        }

        /**
         * Records the batch's paths as parent candidates; call only after the insert carrying the batch succeeded. A
         * node read from the table enters the cache only as the parent of a node written here, never on its own, so
         * the cache holds one small entry per written row.
         */
        public void written() {
            cache.putAll(learned.stream().map(this::node).toList());
        }

        /**
         * As {@link #written()}, for a batch that was stored in part: the paths of the rows in {@code notStored}, compared
         * by identity, are not recorded, so a request of a later flush never takes a row that is not in the table as its
         * parent. A path that some stored row also carries is left out too; that costs a later table lookup, nothing more.
         */
        public void written(Set<Map<String, Object>> notStored) {
            if (notStored.isEmpty()) {
                written();
                return;
            }
            cache.putAll(learned.stream().filter(entry -> !notStored.contains(entry.row())).map(this::node).toList());
        }

        private ChainNode node(Learned entry) {
            return relinked.getOrDefault(entry.row(), entry.node());
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

        private Item(Map<String, Object> row, String time, UUID[] prefixes, int systemCount) {
            this.row = row;
            this.time = time;
            this.systemCount = systemCount;
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
            if (unscoped(row)) {
                return null;
            }
            ChatRequest request = ChatRequest.parse(mapper, body, false);
            if (request == null) {
                return null;
            }
            String scope = scope(row);
            return new Item(row, time, PathHash.prefixes(scope, time.substring(0, 10), request.tools(), request.hashedHistory()),
                    request.systemCount());
        }

        /**
         * The chain scope: project and user. Deployment is left out, so a user who switches between deployments that
         * take the same body format keeps one chain; the history hash alone decides what links.
         */
        static String scope(Map<String, Object> row) {
            String project = String.valueOf(row.get(PROJECT_ID));
            // Length-prefixed, so no project id or user hash, whatever characters it holds, can shift the boundary.
            return project.length() + ":" + project + row.get(USER_HASH);
        }

        /**
         * Whether the row has neither a project nor a user: no scope a chain could stay within, so its body is stored
         * whole with no path.
         */
        static boolean unscoped(Map<String, Object> row) {
            return blank(row.get(PROJECT_ID)) && blank(row.get(USER_HASH));
        }

        private static boolean blank(Object value) {
            return value == null || value.toString().isBlank();
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

        /** Under {@code whole-with-path}: the row keeps its body and carries its path, and is never linked. */
        void stampPath() {
            row.put(REQUEST_HASH, path().toString());
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
            if (request == null || request.canonicalHistory().size() != depth() || row.containsKey(REQUEST_PARENT_HASH)) {
                if (row.get(REQUEST_BODY) instanceof String) {
                    stampPath();
                }
                return false;
            }
            stampPath();
            row.put(REQUEST_PARENT_HASH, (parent == null ? NO_PARENT : parent.path()).toString());
            List<UUID> lineage = parent == null ? List.of() : parent.lineage(ANCESTORS_HINT);
            row.put(REQUEST_ANCESTOR_HASHES, lineage.stream().map(UUID::toString).toList());
            row.put(REQUEST_BODY, request.stored(parentDepth));
            row.put(REQUEST_CACHE_CONTROL, markers(request, parentDepth));
            return true;
        }

        /**
         * The row's {@code request_cache_control}: its markers and, for a row with a parent, one entry listing the
         * inherited messages it sent as a bare text block, so its rebuild returns each in the shape this request sent
         * whatever shape the row that stores the message sent. The entry is written even when empty: a row without
         * one was stored before shapes were recorded, and inherits its messages as stored.
         */
        private static String markers(ChatRequest request, int parentDepth) {
            if (request.markers().isEmpty() && parentDepth == 0) {
                return null;
            }
            ArrayNode markers = JsonNodeFactory.instance.arrayNode();
            request.markers().forEach(marker -> markers.add(marker.toJson()));
            if (parentDepth > 0) {
                ArrayNode blocks = markers.addObject().putArray(ChatRequest.TEXT_BLOCKS);
                request.textBlocks(parentDepth).forEach(blocks::add);
            }
            return ChatRequest.escapeLoneSurrogates(markers.toString());
        }
    }
}
