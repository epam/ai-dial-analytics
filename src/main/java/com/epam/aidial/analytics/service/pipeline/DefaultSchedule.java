package com.epam.aidial.analytics.service.pipeline;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The cron an {@code enrich} {@code schedule} trigger is stored with when it declares none: every minute, at
 * a second derived from the pipeline name.
 *
 * <p>The second is spread rather than fixed at {@code 0} because a cron fires on the wall clock, and the
 * Enrichment Runner runs a pipeline's scan ahead of every queued work item: a fixed second would put every
 * defaulted pipeline's scan, and every Class-1 {@code INSERT … SELECT}, into the same instant.
 *
 * <p>The derivation is the first four bytes of the MD5 digest of the UTF-8 name, read as an unsigned
 * big-endian integer, modulo 60. MD5 rather than {@link String#hashCode()} because the same answer must come
 * out of PostgreSQL: {@code V44__retire_on_ingest_trigger.sql} computes it as
 * {@code ('x' || substr(md5(name), 1, 8))::bit(32)::bigint % 60} for the rows it migrates, and the two are
 * pinned against each other by a test. Not a security use of MD5.
 */
public final class DefaultSchedule {

    private DefaultSchedule() {
    }

    /** The default cron for a pipeline of this name. */
    public static String cronFor(String name) {
        return secondFor(name) + " * * * * *";
    }

    /** The name-derived second, in {@code [0, 60)}. */
    static int secondFor(String name) {
        final byte[] digest = md5().digest(name.getBytes(StandardCharsets.UTF_8));
        final int prefix = ((digest[0] & 0xff) << 24) | ((digest[1] & 0xff) << 16)
                | ((digest[2] & 0xff) << 8) | (digest[3] & 0xff);
        return (int) (Integer.toUnsignedLong(prefix) % 60);
    }

    private static MessageDigest md5() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("MD5 is a mandatory JCA algorithm", ex);
        }
    }
}
