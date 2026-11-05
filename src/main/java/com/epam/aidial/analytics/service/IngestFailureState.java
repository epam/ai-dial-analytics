package com.epam.aidial.analytics.service;

import java.util.Locale;

/** The state of an outstanding failure-ledger subject; a resolved subject is not outstanding and has none. */
public enum IngestFailureState {
    /** Retried automatically once its next attempt is due. */
    PENDING,
    /** Not retried until an operator requeues it. */
    PARKED;

    /** The lower-case spelling reported. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The state spelled {@code wire}, matched case-insensitively; {@code null} when none is. */
    public static IngestFailureState fromWire(String wire) {
        for (IngestFailureState state : values()) {
            if (state.wire().equalsIgnoreCase(wire)) {
                return state;
            }
        }
        return null;
    }
}
