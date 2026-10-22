package com.epam.aidial.analytics.observability;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Captures the log events one class emits, so a test can assert on them. Attaches an in-memory appender
 * to that class's logger and pins its level, restoring both on {@link #close()}.
 *
 * <p>Log output is usually not worth asserting on — it is incidental to behaviour and brittle to
 * reword. Two claims in the aggregate capability are exceptions, because the log <em>is</em> the
 * observable: a scheduler tick must stay silent when nothing was due (an unconditional line on the
 * default per-minute cron is ~1,440 entries a day per replica saying nothing happened, which trains an
 * operator to filter the logger the useful line arrives on), and a successful refresh must be visible at
 * INFO rather than only when it fails. Neither has any other surface to test against.
 *
 * <p>Assert on the <b>level and the presence</b> of a line, and on identifiers a reader would grep for —
 * not on full message text, or a reworded log line fails a test it should not.
 *
 * <p>Not thread-safe across tests: it mutates a shared logger. Use it inside one test, in
 * try-with-resources.
 */
public final class LogCapture implements AutoCloseable {

    private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());
    private final Logger logger;
    private final Level previousLevel;
    private final AbstractAppender appender;

    private LogCapture(String name, Level level) {
        this.logger = (Logger) LogManager.getLogger(name);
        this.previousLevel = logger.getLevel();
        this.appender = new AbstractAppender("log-capture-" + name,
                null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                events.add(event.toImmutable());
            }
        };
        appender.start();
        logger.addAppender(appender);
        // Pinned rather than inherited: what the surrounding configuration happens to enable must not
        // decide whether the assertion can see the line it is about.
        logger.setLevel(level);
    }

    /** Captures everything {@code type} logs at DEBUG or above. */
    public static LogCapture of(Class<?> type) {
        return of(type.getName());
    }

    /** Captures everything the logger named {@code name} logs at DEBUG or above, e.g. {@link SqlLog#NAME}. */
    public static LogCapture of(String name) {
        return new LogCapture(name, Level.DEBUG);
    }

    /** The captured messages at exactly {@code level}, formatted. */
    public List<String> messagesAt(Level level) {
        synchronized (events) {
            return events.stream()
                    .filter(event -> event.getLevel().equals(level))
                    .map(event -> event.getMessage().getFormattedMessage())
                    .toList();
        }
    }

    /** The captured events at exactly {@code level}, with their context data and attached throwable. */
    public List<LogEvent> eventsAt(Level level) {
        synchronized (events) {
            return events.stream().filter(event -> event.getLevel().equals(level)).toList();
        }
    }

    @Override
    public void close() {
        logger.removeAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
    }
}
