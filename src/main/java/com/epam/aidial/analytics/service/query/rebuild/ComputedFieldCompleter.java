package com.epam.aidial.analytics.service.query.rebuild;

import com.clickhouse.client.api.data_formats.internal.BinaryStreamReader;
import com.clickhouse.client.api.query.QuerySettings;
import com.epam.aidial.analytics.configuration.RequestBodyRebuildProperties;
import com.epam.aidial.analytics.service.query.ComputedField;
import com.epam.aidial.analytics.sync.delta.RequestBodyRebuild;
import com.epam.aidial.analytics.sync.delta.RequestBodyRebuild.Outcome;
import com.epam.aidial.analytics.sync.delta.RequestBodyRebuild.Reason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.Field;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Completes the computed fields of a read after its statement has run (see {@code openspec/specs/data-sync/spec.md},
 * "A rebuilt request body is readable beside the stored one"): every stand-in cell the statement returned is replaced
 * by the field's value — the rebuilt body for {@code request_body_full}, its status for {@code request_body_rebuild}.
 *
 * <p>A row stored whole is answered from its own cell and costs no statement; a root carrying cache markers is rebuilt
 * from its own body. Every other row is resolved in two phases through {@link RequestChainReader}, inside its own
 * day's partition: rounds of phase 1 walk each chain over the small chain columns, seeded from the row's ancestor hint
 * and then from the hint of the row whose parent is still missing; phase 2 then reads only the bodies the walks
 * visited, by {@code event_id}. The walk always prefers the link of a row whose body was read, so a body is never
 * combined with another version's link: a row read in phase 2 that no longer carries the path phase 1 found is looked
 * up again within the remaining rounds, or ends {@code partial}.
 *
 * <p>Nothing here fails the read. A chain that cannot be completed — a path no row carries, a bound reached, a statement
 * that failed (budget breach included), a body of the wrong shape — returns the stored body marked {@code partial},
 * counted on {@code query.request.rebuild.partial}. Only a stand-in cell this class cannot read at all throws: passing
 * it through would return a raw tuple where the caller expects a body.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ComputedFieldCompleter {

    static final String ROWS_METER = "query.request.rebuild.rows";
    static final String PARTIAL_METER = "query.request.rebuild.partial";

    /** Which surface a completed value is counted under. */
    public enum Surface {
        QUERY,
        PIPELINE
    }

    /**
     * One projected stand-in: its output column and which value it completes.
     *
     * @param column the output name the statement projected it under
     * @param kind   the value it completes
     */
    public record Slot(String column, ComputedField.Kind kind) {}

    private final RequestChainReader reader;
    private final RequestBodyRebuildProperties bounds;
    private final MeterRegistry registry;

    /** The stand-ins {@code select} projects, in projection order; empty when it projects none. */
    public static List<Slot> slots(List<? extends Field<?>> select) {
        final List<Slot> slots = new ArrayList<>();
        for (final Field<?> field : select) {
            final ComputedField.StandIn standIn = ComputedField.standInOf(field);
            if (standIn != null) {
                slots.add(new Slot(field.getName(), standIn.computed().kind()));
            }
        }
        return slots;
    }

    /**
     * Replaces every stand-in cell of {@code rows} with its completed value.
     *
     * @param slots    the stand-ins the statement projected ({@link #slots}); nothing is done when empty
     * @param rows     the statement's rows, modified in place
     * @param settings the settings each rebuild statement runs under, asked anew per statement so it carries what is
     *     left of the triggering read's wall-clock ceiling; {@code null} when nothing is left, in which case the
     *     statement is not issued and the rows of its day are returned {@code partial} ({@code failed})
     * @param surface  the surface the values are counted under
     * @throws IllegalStateException when a stand-in cell is not the tuple its stand-in renders
     */
    public void complete(List<Slot> slots, List<Map<String, Object>> rows, Supplier<QuerySettings> settings, Surface surface) {
        if (slots.isEmpty() || rows.isEmpty()) {
            return;
        }
        final boolean full = slots.stream().anyMatch(slot -> slot.kind() == ComputedField.Kind.BODY);
        final Slot source = slots.stream().filter(slot -> !full || slot.kind() == ComputedField.Kind.BODY).findFirst().orElseThrow();
        final Read read = new Read(full, settings);
        final List<Target> pending = new ArrayList<>();
        for (final Map<String, Object> row : rows) {
            final Target target = Target.of(row, row.get(source.column()), full);
            read.targets.add(target);
            if (target.bodyNull) {
                continue;
            }
            if (RequestBodyRebuild.storedWhole(target.parent, target.hasMarkers)) {
                target.finish(Outcome.STORED, null, target.body);
            } else if (RequestBodyRebuild.NO_PARENT.equals(target.parent)) {
                // A root carrying markers needs no ancestor: its own body is its whole history.
                if (full) {
                    target.finish(RequestBodyRebuild.rebuild(target.body, target.parent, target.markers, Map.of()));
                } else {
                    target.finish(Outcome.REBUILT, null, null);
                }
            } else {
                pending.add(target);
            }
        }
        read.resolve(pending);
        for (final Target target : read.targets) {
            for (final Slot slot : slots) {
                target.row.put(slot.column(), slot.kind() == ComputedField.Kind.BODY ? target.value
                        : target.outcome == null ? null : target.outcome.name().toLowerCase(Locale.ROOT));
            }
        }
        record(read, surface);
    }

    private void record(Read read, Surface surface) {
        final String surfaceTag = surface.name().toLowerCase(Locale.ROOT);
        final Map<Reason, Integer> partial = new TreeMap<>();
        for (final Target target : read.targets) {
            if (target.outcome == null) {
                continue;
            }
            counter(ROWS_METER, "outcome", target.outcome.name().toLowerCase(Locale.ROOT), "surface", surfaceTag).increment();
            if (target.outcome == Outcome.PARTIAL) {
                counter(PARTIAL_METER, "reason", target.reason.name().toLowerCase(Locale.ROOT)).increment();
                partial.merge(target.reason, 1, Integer::sum);
            }
        }
        if (!partial.isEmpty()) {
            final Map<String, Integer> byReason = new LinkedHashMap<>();
            partial.forEach((reason, count) -> byReason.put(reason.name().toLowerCase(Locale.ROOT), count));
            log.warn("Request body rebuild returned {} of {} row(s) partial on the {} surface, by reason {}, after {} round(s)",
                    partial.values().stream().mapToInt(Integer::intValue).sum(), read.targets.size(), surfaceTag, byReason,
                    read.rounds);
        }
        log.debug("Request body rebuild over {} row(s): {} round(s), {} ancestor(s), {} statement(s)",
                read.targets.size(), read.rounds, read.ancestors(), read.statements);
    }

    private Counter counter(String name, String... tags) {
        return Counter.builder(name).tags(tags).register(registry);
    }

    /** One read's resolution state: every target, and per partition day the rows found so far. */
    private final class Read {

        private final boolean full;
        private final Supplier<QuerySettings> settings;
        private final List<Target> targets = new ArrayList<>();
        private final Map<Integer, Day> days = new HashMap<>();
        private int rounds;
        private int statements;

        Read(boolean full, Supplier<QuerySettings> settings) {
            this.full = full;
            // Counted where a statement is granted its settings, which the reader asks for just before issuing each
            // chunk: a chunk refused for lack of time is not a statement.
            this.settings = () -> {
                final QuerySettings granted = settings.get();
                if (granted != null) {
                    statements++;
                }
                return granted;
            };
        }

        int ancestors() {
            return days.values().stream().mapToInt(day -> day.links.size()).sum();
        }

        void resolve(List<Target> targets) {
            // An insertion-ordered set: Target keeps identity equality, so removing a finished one costs O(1) however
            // large the page.
            final Set<Target> pending = new LinkedHashSet<>(targets);
            while (!pending.isEmpty()) {
                final Map<Integer, Set<UUID>> lookups = new TreeMap<>();
                final Map<Integer, Set<String>> reads = new TreeMap<>();
                final List<Target> needing = new ArrayList<>();
                for (final Iterator<Target> it = pending.iterator(); it.hasNext(); ) {
                    final Target target = it.next();
                    final Day day = days.computeIfAbsent(target.day, ignored -> new Day());
                    final Walk walk = day.walk(target, full);
                    switch (walk.state) {
                        case COMPLETE -> {
                            target.finish(full ? RequestBodyRebuild.rebuild(target.body, target.parent, target.markers, day.bodies)
                                    : new RequestBodyRebuild.Result(Outcome.REBUILT, null, null));
                            it.remove();
                        }
                        case MISSING, INVALID -> {
                            target.finish(Outcome.PARTIAL, walk.state == WalkState.MISSING ? Reason.MISSING : Reason.INVALID, target.body);
                            it.remove();
                        }
                        case NEED_PATH -> {
                            final Set<UUID> seeds = lookups.computeIfAbsent(target.day, ignored -> new LinkedHashSet<>());
                            seeds.add(walk.path);
                            walk.hint.stream().filter(day::unknown).forEach(seeds::add);
                            needing.add(target);
                        }
                        case NEED_BODIES -> reads.computeIfAbsent(target.day, ignored -> new LinkedHashSet<>()).addAll(walk.events);
                        default -> throw new IllegalStateException("unexpected walk state " + walk.state);
                    }
                }
                if (lookups.isEmpty() && reads.isEmpty()) {
                    return;
                }
                // A statement that fails — budget spent included — fails the rows of its own day only; the other days
                // keep resolving, each later statement under whatever time is still left.
                final Set<Integer> failed = new HashSet<>();
                if (!lookups.isEmpty() && !lookUp(lookups, failed)) {
                    for (final Target target : needing) {
                        target.finish(Outcome.PARTIAL, Reason.LIMIT, target.body);
                        pending.remove(target);
                    }
                }
                for (final Map.Entry<Integer, Set<String>> entry : reads.entrySet()) {
                    // Every row of a day whose lookup just failed ends failed below, so its bodies would be read for
                    // nothing.
                    if (failed.contains(entry.getKey())) {
                        continue;
                    }
                    final Day day = days.get(entry.getKey());
                    try {
                        for (final RequestBodyRebuild.Ancestor row : reader.bodies(entry.getKey(), entry.getValue(), settings)) {
                            day.bodies.put(row.path(), row);
                        }
                    } catch (RuntimeException ex) {
                        failed(entry.getKey(), ex, failed);
                        continue;
                    }
                    day.readEvents.addAll(entry.getValue());
                }
                if (!failed.isEmpty()) {
                    for (final Iterator<Target> it = pending.iterator(); it.hasNext(); ) {
                        final Target target = it.next();
                        if (failed.contains(target.day)) {
                            target.finish(Outcome.PARTIAL, Reason.FAILED, target.body);
                            it.remove();
                        }
                    }
                }
            }
        }

        /** Notes a failed statement of {@code day}; the read's one summary WARN line counts the rows it cost. */
        private void failed(int day, RuntimeException ex, Set<Integer> failed) {
            failed.add(day);
            log.debug("A request body rebuild statement for day {} failed ({}); its rows are returned partial", day,
                    ex.getClass().getSimpleName(), ex);
        }

        /**
         * One phase-1 round over {@code lookups}, or {@code false} when a bound stops it first. The ancestor bound caps
         * the paths a round may add, so the read never resolves more than it allows. A day whose statement fails is
         * added to {@code failed}.
         */
        private boolean lookUp(Map<Integer, Set<UUID>> lookups, Set<Integer> failed) {
            int capacity = bounds.maxAncestors() - ancestors();
            if (rounds >= bounds.maxRounds() || capacity <= 0) {
                return false;
            }
            rounds++;
            for (final Map.Entry<Integer, Set<UUID>> entry : lookups.entrySet()) {
                if (capacity <= 0) {
                    break;
                }
                final Day day = days.get(entry.getKey());
                final List<UUID> paths = entry.getValue().stream().limit(capacity).toList();
                final List<RequestChainReader.Link> found;
                try {
                    found = reader.links(entry.getKey(), paths, settings);
                } catch (RuntimeException ex) {
                    failed(entry.getKey(), ex, failed);
                    continue;
                }
                final Set<UUID> absent = new HashSet<>(paths);
                for (final RequestChainReader.Link link : found) {
                    day.links.put(link.path(), link);
                    absent.remove(link.path());
                }
                day.absent.addAll(absent);
                capacity -= paths.size() - absent.size();
            }
            return true;
        }
    }

    private enum WalkState { COMPLETE, NEED_PATH, NEED_BODIES, MISSING, INVALID }

    private record Walk(WalkState state, UUID path, List<UUID> hint, Set<String> events) {

        static Walk of(WalkState state) {
            return new Walk(state, null, List.of(), Set.of());
        }
    }

    /** What one partition day's lookups have found. */
    private static final class Day {

        private final Map<UUID, RequestChainReader.Link> links = new HashMap<>();
        private final Map<UUID, RequestBodyRebuild.Ancestor> bodies = new HashMap<>();
        private final Set<UUID> absent = new HashSet<>();
        private final Set<String> readEvents = new HashSet<>();

        boolean unknown(UUID path) {
            return !links.containsKey(path) && !absent.contains(path) && !bodies.containsKey(path);
        }

        /**
         * Walks {@code target}'s chain over what is known: a path whose body was read follows that body's own parent;
         * one known only from phase 1 follows its link and, when bodies are wanted, asks for its body. A link whose
         * event was read in phase 2 without carrying the path any more is stale (re-ingested between the phases) and
         * is dropped so the path is looked up again.
         */
        Walk walk(Target target, boolean needBodies) {
            UUID next = target.parent;
            List<UUID> hint = target.hint;
            final Set<String> events = new LinkedHashSet<>();
            int hops = 0;
            while (!RequestBodyRebuild.NO_PARENT.equals(next)) {
                if (++hops > links.size() + bodies.size() + 1) {
                    return Walk.of(WalkState.INVALID);
                }
                final RequestBodyRebuild.Ancestor body = needBodies ? bodies.get(next) : null;
                final RequestChainReader.Link link = links.get(next);
                if (body != null) {
                    hint = link == null ? List.of() : link.ancestors();
                    next = body.parent();
                    continue;
                }
                if (link == null) {
                    return absent.contains(next) ? Walk.of(WalkState.MISSING) : new Walk(WalkState.NEED_PATH, next, hint, Set.of());
                }
                if (needBodies) {
                    if (readEvents.contains(link.eventId())) {
                        links.remove(next);
                        return new Walk(WalkState.NEED_PATH, next, hint, Set.of());
                    }
                    events.add(link.eventId());
                }
                hint = link.ancestors();
                next = link.parent();
            }
            return events.isEmpty() ? Walk.of(WalkState.COMPLETE) : new Walk(WalkState.NEED_BODIES, null, List.of(), events);
        }
    }

    /** One row being completed, read from its stand-in cell. */
    private static final class Target {

        private final Map<String, Object> row;
        private final boolean bodyNull;
        private final String body;
        private final UUID parent;
        private final List<UUID> hint;
        private final String markers;
        private final boolean hasMarkers;
        private final int day;
        private Outcome outcome;
        private Reason reason;
        private Object value;

        private Target(Map<String, Object> row, boolean bodyNull, String body, UUID parent, List<UUID> hint, String markers,
                boolean hasMarkers, int day) {
            this.row = row;
            this.bodyNull = bodyNull;
            this.body = body;
            this.parent = parent;
            this.hint = hint;
            this.markers = markers;
            this.hasMarkers = hasMarkers;
            this.day = day;
        }

        /**
         * Reads a stand-in cell: the tuple {@link ComputedField.StandIn} renders, which the client hands over as an
         * array (or, defensively, a list) of its elements. The paths arrive as text and the day as a number.
         *
         * <p>Whether the row has a body is decided before anything else is parsed. An unmatched join row reads its
         * elements' type defaults under the service's own settings, but {@code NULL} under {@code join_use_nulls = 1}
         * (which a server or user profile can set); either way the row has no body, so both fields read {@code NULL}.
         */
        static Target of(Map<String, Object> row, Object cell, boolean full) {
            final List<?> tuple = elements(cell);
            if (tuple == null || tuple.size() != 5) {
                throw new IllegalStateException("a computed field's stand-in cell is not the tuple it renders: "
                        + (cell == null ? "null" : cell.getClass().getName()));
            }
            final Object first = tuple.get(0);
            if ((full ? first == null : first == null || truth(first)) || tuple.get(1) == null || tuple.get(4) == null) {
                return new Target(row, true, null, RequestBodyRebuild.NO_PARENT, List.of(), null, false, 0);
            }
            final UUID parent = UUID.fromString(String.valueOf(tuple.get(1)));
            final List<UUID> hint = new ArrayList<>();
            final List<?> ancestors = elements(tuple.get(2));
            if (ancestors != null) {
                ancestors.forEach(ancestor -> hint.add(UUID.fromString(String.valueOf(ancestor))));
            }
            final String markers = full ? (String) tuple.get(3) : null;
            final boolean hasMarkers = full ? markers != null : truth(tuple.get(3));
            final int day = ((Number) tuple.get(4)).intValue();
            return new Target(row, false, full ? (String) first : null, parent, List.copyOf(hint), markers,
                    hasMarkers, day);
        }

        void finish(RequestBodyRebuild.Result result) {
            finish(result.outcome(), result.reason(), result.body());
        }

        void finish(Outcome outcome, Reason reason, Object value) {
            this.outcome = outcome;
            this.reason = reason;
            this.value = value;
        }

        private static List<?> elements(Object value) {
            return switch (value) {
                case Object[] array -> Arrays.asList(array);
                case List<?> list -> list;
                case BinaryStreamReader.ArrayValue array -> array.asList();
                case null, default -> null;
            };
        }

        private static boolean truth(Object value) {
            return switch (value) {
                case Boolean bool -> bool;
                case Number number -> number.intValue() != 0;
                case null, default -> throw new IllegalStateException("a computed field's stand-in flag is not boolean: " + value);
            };
        }
    }
}
