package com.epam.aidial.analytics.observability;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The last logged state of each pipeline on each change-only log line, so a line that would repeat on every
 * call or tick is logged only when the state it reports changes: a materialize that keeps finding its window
 * drained or withheld, a scan that keeps returning an empty page, a refresh that stays held back.
 *
 * <p><b>Per replica and in memory.</b> Several replicas each log their own first line, which the pod name on
 * the exported log resource tells apart. Nothing here is correctness state: a restart only repeats one line.
 *
 * <p><b>Bounded by the registry.</b> Callers record a state only after resolving the name as a registered
 * pipeline, and {@link #forget} runs on every path that deletes or disables one, next to the per-pipeline
 * metric removals.
 */
@Component
public class TransitionLog {

    /** One change-only log line. Each keeps its own state per pipeline. */
    public enum Channel {
        /** The Class-1 materialize outcome line. */
        MATERIALIZE,
        /** The enrichment scan outcome line. */
        SCAN,
        /** The aggregate refresh held-back lines. */
        AGGREGATE_HOLD
    }

    private final Map<Channel, Map<String, String>> states = new EnumMap<>(Channel.class);

    public TransitionLog() {
        for (Channel channel : Channel.values()) {
            states.put(channel, new ConcurrentHashMap<>());
        }
    }

    /**
     * Records {@code state} as {@code pipeline}'s current one on {@code channel}.
     *
     * @return the state recorded before, or {@code null} when none was
     */
    public String swap(Channel channel, String pipeline, String state) {
        return states.get(channel).put(pipeline, state);
    }

    /** Drops every state recorded for {@code pipeline}. */
    public void forget(String pipeline) {
        states.values().forEach(byPipeline -> byPipeline.remove(pipeline));
    }
}
