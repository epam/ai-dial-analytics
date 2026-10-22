package com.epam.aidial.analytics.service.query.saved.model;

/** Discriminator for a saved query's time intent. Serialized lowercase ({@code relative} / {@code absolute}). */
public enum SavedQueryTimeMode {
    RELATIVE,
    ABSOLUTE
}
