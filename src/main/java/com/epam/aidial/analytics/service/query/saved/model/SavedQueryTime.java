package com.epam.aidial.analytics.service.query.saved.model;

import java.time.Instant;

/**
 * A saved query's <strong>time intent</strong> — what the author meant, not a resolved range.
 *
 * <p>Exactly one shape is populated, selected by {@link #mode}: {@code RELATIVE} carries a
 * {@link #period} token the client interprets (e.g. {@code last_2d}), {@code ABSOLUTE} carries a
 * {@link #from}/{@link #to} pair. The whole object may be {@code null}, meaning "use whatever the
 * toolbar has on open".
 *
 * <p>A relative period is never resolved to instants at save time: storing
 * {@code 2026-08-04T00:00Z … 2026-08-06T00:00Z} for "last 2 days" would silently freeze the query to
 * the day it was authored. ADAS does not interpret a period's meaning; it only bounds its shape
 * ({@code SavedQueryBodyValidator}), because an unvalidated free-form object in the metadata database
 * is a place to smuggle arbitrary text past every other guard.
 */
public record SavedQueryTime(SavedQueryTimeMode mode, String period, Instant from, Instant to) {}
