package com.epam.aidial.analytics.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one logger that may carry statement text. A statement inlines its literals — filter values, cursor tuples,
 * group keys — so it is logged only here, at DEBUG, and never on a class logger: a TRACE on an executor class
 * would otherwise enable its statement line too. {@code log4j2.xml} pins this logger at INFO, and the runtime
 * level file refuses anything below INFO for it and its descendants, so printing statements takes a static,
 * restart-scoped {@code logging.level.*} decision.
 */
public final class SqlLog {

    /** The logger name the XML pin and the runtime-level denylist refer to. */
    public static final String NAME = "com.epam.aidial.analytics.sql";

    public static final Logger LOG = LoggerFactory.getLogger(NAME);

    private SqlLog() {
    }
}
